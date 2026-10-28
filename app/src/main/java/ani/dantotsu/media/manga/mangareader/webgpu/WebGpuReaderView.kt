package ani.dantotsu.media.manga.mangareader.webgpu

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.lifecycleScope
import androidx.webgpu.GPUTexture
import ani.dantotsu.R
import ani.dantotsu.media.manga.MangaChapter
import ani.dantotsu.media.manga.MangaImageFetcher
import ani.dantotsu.media.manga.MangaImageFetcher.FailureKind
import ani.dantotsu.media.manga.mangareader.MangaReaderActivity
import ani.dantotsu.parsers.MangaImage
import ani.dantotsu.snackString
import ani.dantotsu.util.Logger
import ca.mpreg.imagedecoder.ImageDecoder
import ca.mpreg.webgpuviewer.ImageView
import ca.mpreg.webgpuviewer.ImageViewContinuous
import ca.mpreg.webgpuviewer.draw.TextAlign
import ca.mpreg.webgpuviewer.renderer.Image
import ca.mpreg.webgpuviewer.transition.TransitionBasic
import ca.mpreg.webgpuviewer.viewer.ImagePage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

/**
 * Dantotsu's adapter between the reader and the webgpuviewer library (paged and continuous).
 *
 * The library's page provider is relative: fetchPage(delta) is asked for pages around the one it
 * considers current and onPageChange(±n) moves "current". This class keeps a timeline of stable
 * slot keys (pages of one or more chapters plus transition items) and serves pages from it.
 *
 * Loading (Mihon-style): one prioritised queue - the current page first, then reading order
 * ahead, then behind - with at most [NETWORK_WORKERS] fetches in flight; fetches that leave the
 * window are cancelled (a seekbar drag or fling no longer queues dozens of requests on a 2 req/s
 * source), decodes run on one dedicated thread, and retries depend on why a page failed.
 * Eviction never frees a page the viewer is drawing; it is deferred until it's off screen.
 */
class WebGpuReaderView(
    private val activity: MangaReaderActivity,
    val isContinuous: Boolean = false,
    val isVertical: Boolean = false,
    val isReversed: Boolean = false
) {
    val viewer: View = if (isContinuous) {
        ImageViewContinuous(activity)
    } else {
        ImageView(activity, isVertical = isVertical, isReversed = isReversed)
    }

    val pagedViewer: ImageView? get() = viewer as? ImageView
    val continuousViewer: ImageViewContinuous? get() = viewer as? ImageViewContinuous

    /** The chapter whose page is on screen (changes while scrolling/turning across chapters). */
    @Volatile
    private var currentChapter: MangaChapter? = null

    /** Chapter the timeline was built around by [bind]. */
    val boundChapter: MangaChapter? get() = boundChapterRef

    @Volatile
    private var boundChapterRef: MangaChapter? = null

    /** Crop setting the pages in the cache were decoded with. */
    @Volatile
    var boundCropBorders: Boolean = false
        private set

    /**
     * What the viewer moves through, keyed by a stable "slot key": the bound chapter's pages at
     * keys 0 until n, a transition item before and after, and neighbouring chapters spliced in
     * before/after as the reader nears an end. Keys of existing slots never change, so appending
     * or prepending never moves what is on screen.
     */
    private sealed class Slot {
        class PageSlot(val chapter: MangaChapter, val images: List<MangaImage>, val index: Int) : Slot() {
            val image: MangaImage get() = images[index]
            val pageId: String get() = "${chapter.uniqueNumber()}#$index"
        }

        /**
         * [previous] = false: end of [from], leading to [to] ("Finished / Next").
         * [previous] = true: start of [from], preceded by [to] ("Previous / Current").
         */
        class TransitionSlot(val from: MangaChapter, val to: MangaChapter?, val previous: Boolean) : Slot() {
            @Volatile
            var failed = false
        }
    }

    private val slots = ConcurrentHashMap<Int, Slot>()
    @Volatile
    private var firstKey = 0
    @Volatile
    private var lastKey = -1
    /** Slot the viewer considers "current" (fetchPage(delta) is relative to it). */
    @Volatile
    private var currentKey = 0

    /** Chapters (uniqueNumber) whose pages are in [slots]; and chapters whose page list is loading. */
    private val chaptersInTimeline = ConcurrentHashMap.newKeySet<String>()
    private val chapterLoads = ConcurrentHashMap.newKeySet<String>()

    private val pageCache = ConcurrentHashMap<Int, ImagePage>()

    /** Natural size of every page decoded this session, so its placeholder keeps the layout. */
    private val pageSizes = ConcurrentHashMap<String, IntArray>()

    /** Evicted pages the viewer was still drawing: freed once they are off screen. */
    private val deferredCleanup = mutableListOf<ImagePage>()

    // ---- loading state (scheduled on the main thread) ----------------------------------------
    /** Keys whose page should be loaded, in no order: [schedule] picks by priority. */
    private val pendingLoads = ConcurrentHashMap.newKeySet<Int>()
    private val loadingJobs = ConcurrentHashMap<Int, Job>()
    /** Keys the current window wants decoded (main thread). */
    @Volatile
    private var window: IntRange = IntRange.EMPTY
    @Volatile
    private var scheduled = false
    @Volatile
    private var preloadPosted = false

    /** Pages whose every load attempt failed; a tap retries them. */
    private val failedPages = ConcurrentHashMap.newKeySet<Int>()

    /** Bumped by [clearCache]: a load started for an older bind must not install its page. */
    @Volatile
    private var generation = 0

    @Volatile
    private var destroyed = false

    private val mainHandler = Handler(Looper.getMainLooper())

    /** One decoder thread: decodes run in the order loads finish, current page first. */
    private val decodeExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "WebGpuReader-Decode").apply { isDaemon = true }
    }
    private val decodeDispatcher = decodeExecutor.asCoroutineDispatcher()

    /** Last slot reported to the activity. */
    @Volatile
    private var reportedKey = Int.MIN_VALUE

    /** Continuous: the page last reported as read through (identity), see [onViewport]. */
    @Volatile
    private var lastReadThrough: ImagePage? = null

    /** Page index (0-based) within the current chapter; a transition counts as its chapter's edge. */
    val currentPosition: Int
        get() = when (val slot = slots[currentKey]) {
            is Slot.PageSlot -> slot.index
            is Slot.TransitionSlot -> if (slot.previous) 0 else (slot.from.images().size - 1).coerceAtLeast(0)
            null -> 0
        }

    private val viewerWidth: Int
        get() = (pagedViewer?.state?.width ?: continuousViewer?.state?.width)?.takeIf { it > 0 } ?: 1080

    private val viewerHeight: Int
        get() = (pagedViewer?.state?.height ?: continuousViewer?.state?.height)?.takeIf { it > 0 } ?: 1920

    private val pageBackground: Int
        get() = when (activity.defaultSettings.backgroundColor) {
            1 -> Color.BLACK
            2 -> Color.DKGRAY
            3 -> Color.WHITE
            else -> Color.BLACK
        }

    /**
     * Stand-in while a page loads. Continuous mode: sized like the page was last time it decoded
     * (scrolling back to an evicted page no longer shifts the strip when it re-decodes).
     */
    inner class ProgressPage(val pageIndex: Int, private val knownSize: IntArray?) : ImagePage.Render(0, 0) {
        /** Every attempt failed: drawn as a red dot instead of the grey loading dot. */
        @Volatile
        var failed = false

        override val width: Int
            get() = knownSize?.get(0) ?: viewerWidth

        override val height: Int
            get() = knownSize?.get(1) ?: viewerHeight

        override val backgroundColor: Int
            get() = pageBackground

        init {
            minScale = 1f
            maxScale = 1f
            homeScale = 1f
        }

        override fun render(dst: GPUTexture, x: Float, y: Float, scale: Float) {
            fillPage(dst, x, y, scale, backgroundColor)
            val cx = dst.width * (0.5f + scale * x)
            val cy = dst.height * (0.5f + scale * y)
            val full = min(min(width, height), viewerWidth) * 0.25f * scale
            circle(cx, cy, full / 2f, if (failed) 0x88CC4444.toInt() else 0x55888888.toInt())
        }
    }

    /** "Finished: Ch X / Next: Ch Y" item between two chapters. */
    private inner class TransitionPage(val slot: Slot.TransitionSlot) : ImagePage.Render(0, 0) {
        override val width: Int
            get() = viewerWidth

        override val height: Int
            get() = if (isContinuous) (viewerHeight * 0.3f).toInt().coerceAtLeast(1) else viewerHeight

        override val backgroundColor: Int
            get() = pageBackground

        init {
            minScale = 1f
            maxScale = 1f
            homeScale = 1f
        }

        override fun render(dst: GPUTexture, x: Float, y: Float, scale: Float) {
            fillPage(dst, x, y, scale, backgroundColor)
            val light = backgroundColor == Color.WHITE
            val strong = if (light) 0xFF202020.toInt() else 0xFFEEEEEE.toInt()
            val dim = if (light) 0xFF707070.toInt() else 0xFF9E9E9E.toInt()
            val cx = dst.width * (0.5f + scale * x)
            val cy = dst.height * (0.5f + scale * y)
            val size = android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_SP, 15f, activity.resources.displayMetrics
            ) * scale
            val maxWidth = width * scale * 0.86f
            val lines = transitionLines(slot)
            val lineHeight = size * 1.5f
            var baseline = cy - lineHeight * (lines.size - 1) / 2f + size * 0.35f
            for ((line, isStrong) in lines) {
                text(
                    dst, activity, FontFamily.Default, line, cx, baseline, size,
                    if (isStrong) strong else dim,
                    if (isStrong) FontWeight.Bold else FontWeight.Normal,
                    FontStyle.Normal, TextAlign.Center, maxWidth
                )
                baseline += lineHeight
            }
        }
    }

    private fun transitionLines(slot: Slot.TransitionSlot): List<Pair<String, Boolean>> {
        val lines = mutableListOf<Pair<String, Boolean>>()
        val to = slot.to
        val loading = to != null && chapterLoads.contains(to.uniqueNumber())
        if (slot.previous) {
            if (to != null) {
                lines += "${activity.getString(R.string.transition_previous)} ${activity.getChapterDisplayTitle(to)}" to false
            } else {
                lines += activity.getString(R.string.transition_no_previous) to false
            }
            lines += "${activity.getString(R.string.transition_current)} ${activity.getChapterDisplayTitle(slot.from)}" to true
            if (loading) lines += activity.getString(R.string.transition_loading_previous) to false
        } else {
            lines += "${activity.getString(R.string.transition_finished)} ${activity.getChapterDisplayTitle(slot.from)}" to false
            if (to != null) {
                lines += "${activity.getString(R.string.transition_next)} ${activity.getChapterDisplayTitle(to)}" to true
                if (loading) lines += activity.getString(R.string.transition_loading_next) to false
            } else {
                lines += activity.getString(R.string.transition_no_next) to true
            }
        }
        if (slot.failed && !loading) lines += activity.getString(R.string.transition_load_failed) to false
        return lines
    }

    init {
        val bgColor = pageBackground
        viewer.setBackgroundColor(bgColor)

        if (isContinuous) {
            continuousViewer?.state?.apply {
                backgroundColor = bgColor
                onTap = { _ ->
                    mainHandler.post {
                        retryFailedPages()
                        activity.handleController()
                    }
                }
                fetchPage = { delta -> getPageForDelta(delta) }
                // fetchPage(n) is relative to the current page and the viewer moves "current" with
                // onPageChange while it scrolls (render/UI thread, under the viewer's scroll lock):
                // only bookkeeping here, the page counter comes from onViewport.
                onPageChange = { delta ->
                    val target = (currentKey + delta).coerceIn(firstKey, lastKey.coerceAtLeast(firstKey))
                    if (target != currentKey) {
                        currentKey = target
                        postPreload()
                    }
                }
                // The single source of the page counter / progress in continuous mode: the deepest
                // page read through (Mihon's rule - scrolling up to peek at the page above doesn't
                // walk progress back). Called every frame while scrolling, so only a change posts.
                onViewport = { readThrough ->
                    if (readThrough !== lastReadThrough) {
                        lastReadThrough = readThrough
                        val key = readThrough?.let { keyOf(it) } ?: currentKey
                        if (key != reportedKey) mainHandler.post { reportPage(key) }
                    }
                }
            }
        } else {
            pagedViewer?.state?.apply {
                transition = if (isVertical) TransitionBasic.Vertical else TransitionBasic
                onTap = { _ ->
                    mainHandler.post {
                        retryFailedPages()
                        activity.handleController()
                    }
                }
                fetchPage = { delta -> getPageForDelta(delta) }
                onPageChange = { delta ->
                    val target = currentKey + delta
                    if (target in firstKey..lastKey) {
                        currentKey = target
                        mainHandler.post {
                            reportPage(target)
                            preloadAround(target)
                        }
                    }
                }
            }
        }
    }

    private fun keyOf(page: ImagePage): Int? {
        for ((k, v) in pageCache) if (v === page) return k
        return null
    }

    private fun postPreload() {
        if (preloadPosted) return
        preloadPosted = true
        mainHandler.post {
            preloadPosted = false
            preloadAround(currentKey)
        }
    }

    /**
     * Main thread: report [key]'s page (and a chapter change) to the reader. onChapterScrolledTo
     * updates the chapter title, counter, seekbar and saves progress (incognito respected there).
     */
    private fun reportPage(key: Int) {
        if (destroyed || activity.isFinishing || activity.isDestroyed) return
        if (key == reportedKey) return
        when (val slot = slots[key]) {
            is Slot.PageSlot -> {
                reportedKey = key
                currentChapter = slot.chapter
                activity.onChapterScrolledTo(slot.chapter, slot.index + 1, slot.images.size)
            }
            is Slot.TransitionSlot -> {
                reportedKey = key
                slot.to?.let { ensureChapterInTimeline(it, forward = !slot.previous) }
            }
            null -> {}
        }
    }

    private fun neighbourChapter(chap: MangaChapter, offset: Int): MangaChapter? {
        val idx = activity.chaptersArr.indexOf(chap.uniqueNumber())
        if (idx == -1) return null
        return activity.chaptersArr.getOrNull(idx + offset)?.let { activity.chapters[it] }
    }

    /** Loads [chap]'s page list if needed and splices it into the timeline (main thread). */
    private fun ensureChapterInTimeline(chap: MangaChapter, forward: Boolean) {
        val key = chap.uniqueNumber()
        if (chaptersInTimeline.contains(key)) return
        if (chap.images().isNotEmpty()) {
            insertChapter(chap, forward)
            return
        }
        if (!chapterLoads.add(key)) return
        val gen = generation
        invalidateTransitions()
        activity.lifecycleScope.launch(Dispatchers.IO) {
            val ok = try {
                activity.loadChapterImagesForReader(chap)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Logger.log("WebGpuReaderView: loading chapter ${chap.number} failed: $e")
                false
            } finally {
                chapterLoads.remove(key)
            }
            withContext(Dispatchers.Main) {
                if (destroyed || gen != generation) return@withContext
                if (ok && chap.images().isNotEmpty()) {
                    insertChapter(chap, forward)
                } else {
                    slots.values.forEach {
                        if (it is Slot.TransitionSlot && it.to?.uniqueNumber() == key) it.failed = true
                    }
                }
                invalidateTransitions()
                invalidate()
            }
        }
    }

    /** Main thread. Appends after the last slot / prepends before the first one; never renumbers. */
    private fun insertChapter(chap: MangaChapter, forward: Boolean) {
        val key = chap.uniqueNumber()
        if (chaptersInTimeline.contains(key)) return
        val chapImages = chap.images()
        if (chapImages.isEmpty()) return
        if (forward) {
            val edge = slots[lastKey] as? Slot.TransitionSlot ?: return
            if (edge.previous || edge.to?.uniqueNumber() != key) return
            edge.failed = false
            var k = lastKey
            chapImages.indices.forEach { i -> slots[++k] = Slot.PageSlot(chap, chapImages, i) }
            slots[++k] = Slot.TransitionSlot(chap, neighbourChapter(chap, 1), previous = false)
            chaptersInTimeline.add(key)
            lastKey = k
        } else {
            val edge = slots[firstKey] as? Slot.TransitionSlot ?: return
            if (!edge.previous || edge.to?.uniqueNumber() != key) return
            edge.failed = false
            var k = firstKey
            chapImages.indices.reversed().forEach { i -> slots[--k] = Slot.PageSlot(chap, chapImages, i) }
            slots[--k] = Slot.TransitionSlot(chap, neighbourChapter(chap, -1), previous = true)
            chaptersInTimeline.add(key)
            firstKey = k
        }
        invalidateTransitions()
        preloadAround(currentKey)
        invalidate()
    }

    private fun invalidateTransitions() {
        pageCache.values.forEach { if (it is TransitionPage) it.invalidate() }
    }

    private fun retryFailedChapters() {
        slots.values.forEach { slot ->
            if (slot is Slot.TransitionSlot && slot.failed) {
                slot.failed = false
                slot.to?.let { ensureChapterInTimeline(it, forward = !slot.previous) }
            }
        }
    }

    /** Retries every page whose load failed (after a tap, or when the network is back). */
    fun retryFailedPages() {
        retryFailedChapters()
        if (failedPages.isEmpty()) return
        val toRetry = failedPages.toList()
        failedPages.clear()
        toRetry.forEach { key ->
            (pageCache[key] as? ProgressPage)?.failed = false
            requestLoad(key)
        }
        invalidate()
    }

    fun updateBackgroundColor(color: Int) {
        viewer.setBackgroundColor(color)
        if (isContinuous) {
            continuousViewer?.state?.backgroundColor = color
        }
        pageCache.values.forEach { if (it is ProgressPage || it is TransitionPage) it.invalidate() }
        invalidate()
    }

    /** Rebuilds the timeline around [chapter] at [initialPage] (1-based). */
    fun bind(chapter: MangaChapter, initialPage: Int) {
        clearCache()
        slots.clear()
        chaptersInTimeline.clear()
        boundChapterRef = chapter
        boundCropBorders = activity.defaultSettings.cropBorders
        currentChapter = chapter
        val images = chapter.images()
        images.indices.forEach { i -> slots[i] = Slot.PageSlot(chapter, images, i) }
        chaptersInTimeline.add(chapter.uniqueNumber())
        // Both modes get transition items: continuous scrolls through them, paged turns onto them
        // and on into the neighbouring chapter once its pages are in.
        slots[-1] = Slot.TransitionSlot(chapter, neighbourChapter(chapter, -1), previous = true)
        slots[images.size] = Slot.TransitionSlot(chapter, neighbourChapter(chapter, 1), previous = false)
        firstKey = -1
        lastKey = images.size
        val start = (initialPage - 1).coerceIn(0, (images.size - 1).coerceAtLeast(0))
        currentKey = if (images.isEmpty()) lastKey else start
        reportedKey = currentKey
        lastReadThrough = null
        if (isContinuous) continuousViewer?.state?.resetScroll()
        preloadAround(currentKey)
        invalidate()
    }

    /** True when [chap]'s pages are part of the current timeline (bound or spliced in). */
    fun hasChapter(chap: MangaChapter): Boolean = chaptersInTimeline.contains(chap.uniqueNumber())

    /** Same chapter and page again, e.g. after its page list was replaced. */
    fun rebind() {
        val chap = currentChapter ?: boundChapterRef ?: return
        bind(chap, currentPosition + 1)
    }

    /** Key of page [pageIndex] of the current chapter. */
    private fun keyForPage(pageIndex: Int): Int {
        val chapKey = currentChapter?.uniqueNumber()
        val current = slots[currentKey]
        if (current is Slot.PageSlot && current.chapter.uniqueNumber() == chapKey) {
            return currentKey + (pageIndex - current.index)
        }
        return slots.entries.firstOrNull { (_, s) ->
            s is Slot.PageSlot && s.index == pageIndex && s.chapter.uniqueNumber() == chapKey
        }?.key ?: pageIndex
    }

    /** Jumps to page [pageNumber] (1-based) of the current chapter. */
    fun scrollToPage(pageNumber: Int) {
        val chapterSize = currentChapter?.images()?.size ?: 0
        val targetIndex = (pageNumber - 1).coerceIn(0, (chapterSize - 1).coerceAtLeast(0))
        val target = keyForPage(targetIndex).coerceIn(firstKey, lastKey.coerceAtLeast(firstKey))
        val delta = target - currentKey
        if (delta == 0) return
        val from = pageCache[currentKey]
        currentKey = target
        lastReadThrough = null
        reportPage(target)
        preloadAround(currentKey)
        val direction = delta.sign
        if (!isContinuous) {
            pagedViewer?.state?.let { s ->
                s.transitionFromPage = from
                // Mihon's convention: forward through the pages slides in from -1 (unreversed).
                s.animatePageTurn(if (isReversed) direction else -direction)
            }
        } else {
            continuousViewer?.state?.let { s ->
                s.resetScroll()
                s.animateSlideIn(direction)
            }
        }
        invalidate()
    }

    fun scrollUp() {
        if (isContinuous) {
            continuousViewer?.state?.let { s -> s.animateScroll(-s.height / 2f) }
        } else {
            pagedViewer?.state?.animatePageTurnRelative(-1)
        }
    }

    fun scrollDown() {
        if (isContinuous) {
            continuousViewer?.state?.let { s -> s.animateScroll(s.height / 2f) }
        } else {
            pagedViewer?.state?.animatePageTurnRelative(1)
        }
    }

    /** Paged volume keys: move one page in reading order (the library animates, we move "current"). */
    private fun ca.mpreg.webgpuviewer.viewer.ImageViewerState.animatePageTurnRelative(step: Int) {
        val target = currentKey + step
        if (target !in firstKey..lastKey || slots[target] == null) return
        transitionFromPage = pageCache[currentKey]
        currentKey = target
        reportPage(target)
        preloadAround(target)
        animatePageTurn(if (isReversed) step else -step)
        invalidate()
    }

    /** Called by the viewer (render or UI thread): must stay cheap and thread-safe. */
    private fun getPageForDelta(delta: Int): ImagePage? {
        if (destroyed) return null
        val key = currentKey + delta
        val slot = slots[key] ?: return null

        pageCache[key]?.let { return it }

        val page: ImagePage = when (slot) {
            is Slot.PageSlot -> ProgressPage(slot.index, pageSizes[slot.pageId])
            is Slot.TransitionSlot -> TransitionPage(slot)
        }
        val existing = pageCache.putIfAbsent(key, page)
        if (existing != null) {
            page.cleanup()
            return existing
        }
        if (slot is Slot.PageSlot) requestLoad(key)
        return page
    }

    // ---- window, priority and eviction -------------------------------------------------------

    private val pagesBelow: Int get() = continuousViewer?.state?.pagesBelow ?: 0
    private val pagesAbove: Int get() = continuousViewer?.state?.pagesAbove ?: 0

    private val preloadAhead: Int get() = if (isContinuous) max(3, pagesBelow + 2) else 3
    private val preloadBehind: Int get() = if (isContinuous) max(2, pagesAbove + 1) else 2
    private val maxCacheSize: Int
        get() = if (isContinuous) 2 + 2 * max(4, pagesAbove + pagesBelow) else 2 + preloadAhead + preloadBehind + 1

    /** Main thread: sets the decode window around [center], loads it in priority order, trims. */
    private fun preloadAround(center: Int) {
        if (destroyed) return
        val from = max(firstKey, center - preloadBehind)
        val to = min(lastKey, center + preloadAhead)
        window = from..to
        for (k in window) {
            when (val slot = slots[k]) {
                is Slot.PageSlot -> {
                    val cached = pageCache[k]
                    if (cached == null || (cached is ProgressPage && !failedPages.contains(k))) {
                        if (cached == null) {
                            pageCache.putIfAbsent(k, ProgressPage(slot.index, pageSizes[slot.pageId]))
                        }
                        requestLoad(k)
                    }
                }
                is Slot.TransitionSlot -> slot.to?.let { ensureChapterInTimeline(it, forward = !slot.previous) }
                null -> {}
            }
        }
        // Mihon-style: fetch the neighbouring chapter's page list while the last/first pages are
        // still being read, so the strip/page turn flows straight into it.
        (slots[center] as? Slot.PageSlot)?.let { slot ->
            if (slot.images.size - (slot.index + 1) <= PRELOAD_CHAPTER_PAGES) {
                neighbourChapter(slot.chapter, 1)?.let { ensureChapterInTimeline(it, forward = true) }
            }
            if (slot.index < PRELOAD_CHAPTER_PAGES) {
                neighbourChapter(slot.chapter, -1)?.let { ensureChapterInTimeline(it, forward = false) }
            }
        }
        // Loads that left the window: drop queued ones, cancel running ones (one page of slack
        // so a page right at the edge isn't cancelled and restarted on every scroll step).
        val keep = (window.first - 1)..(window.last + 1)
        pendingLoads.removeAll { it !in keep }
        loadingJobs.entries.forEach { (k, job) -> if (k !in keep) job.cancel() }
        trimCache(center)
        schedule()
    }

    /** Any thread: asks for [key]'s page to be loaded. */
    private fun requestLoad(key: Int) {
        if (destroyed) return
        if (loadingJobs.containsKey(key)) return
        pendingLoads.add(key)
        if (!scheduled) {
            scheduled = true
            mainHandler.post {
                scheduled = false
                schedule()
            }
        }
    }

    /** Lower = sooner: the current page, then reading order ahead, then behind. */
    private fun priority(key: Int): Int {
        val d = key - currentKey
        return if (d >= 0) d * 2 else -d * 2 + 1
    }

    /** Main thread: starts the most urgent pending loads up to [NETWORK_WORKERS]. */
    private fun schedule() {
        if (destroyed) return
        while (loadingJobs.size < NETWORK_WORKERS) {
            val next = pendingLoads.filter { !loadingJobs.containsKey(it) }.minByOrNull { priority(it) } ?: return
            pendingLoads.remove(next)
            startLoad(next)
        }
    }

    /**
     * Evicts pages outside the window once the cache is over its size, farthest first. A page the
     * viewer is drawing is never freed while on screen: it's parked in [deferredCleanup].
     */
    private fun trimCache(center: Int) {
        flushDeferredCleanup()
        if (pageCache.size <= maxCacheSize) return
        val keepFrom = center - max(preloadBehind, pagesAbove + 1)
        val keepTo = center + max(preloadAhead, pagesBelow + 1)
        val candidates = pageCache.keys
            .filter { it < keepFrom || it > keepTo }
            .sortedByDescending { abs(it - center) }
        for (k in candidates) {
            if (pageCache.size <= maxCacheSize) break
            val page = pageCache.remove(k) ?: continue
            pendingLoads.remove(k)
            loadingJobs[k]?.cancel()
            releasePage(page)
        }
    }

    private fun releasePage(page: ImagePage) {
        if (page.isOnScreen) {
            synchronized(deferredCleanup) { deferredCleanup.add(page) }
        } else {
            page.cleanup()
        }
    }

    private fun flushDeferredCleanup() {
        val ready = synchronized(deferredCleanup) {
            val off = deferredCleanup.filter { !it.isOnScreen }
            deferredCleanup.removeAll(off)
            off
        }
        ready.forEach { it.cleanup() }
    }

    // ---- loading -----------------------------------------------------------------------------

    /** Main thread. Fetch (IO, error-aware retries) then decode (single decode thread), then install. */
    private fun startLoad(key: Int) {
        val pageSlot = slots[key] as? Slot.PageSlot ?: return
        val current = pageCache[key]
        if (current != null && current !is ProgressPage) return
        val img = pageSlot.image
        val gen = generation

        val job = activity.lifecycleScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            val self = coroutineContext[Job]
            try {
                var imagePage: ImagePage? = null
                var lastError: Throwable? = null
                var attempt = 0
                while (true) {
                    if (gen != generation || destroyed) return@launch
                    val bytes = try {
                        MangaImageFetcher.fetchBytesOrThrow(activity, img.url, img)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        val failure = MangaImageFetcher.classify(e)
                        lastError = failure
                        Logger.log("WebGpuReaderView: page ${pageSlot.index + 1} attempt ${attempt + 1} failed: $failure")
                        val wait = retryDelay(failure.kind, attempt) ?: break
                        attempt++
                        delay(wait)
                        continue
                    }
                    if (gen != generation || destroyed) return@launch
                    imagePage = withContext(decodeDispatcher) {
                        // Still wanted? A fast fling may have moved on while this downloaded.
                        if (gen != generation || destroyed) null else decodeToGpuImage(bytes)
                    }
                    if (imagePage == null) {
                        lastError = MangaImageFetcher.PageLoadException(FailureKind.DECODE, "could not decode image")
                    }
                    break
                }

                withContext(Dispatchers.Main) {
                    val page = imagePage
                    if (destroyed || gen != generation || slots[key] !is Slot.PageSlot) {
                        page?.cleanup()
                        return@withContext
                    }
                    if (page != null) {
                        pageSizes[pageSlot.pageId] = intArrayOf(page.width, page.height)
                        failedPages.remove(key)
                        // Off the window by now (fast scroll): don't grow the cache with it.
                        val wanted = key in (window.first - 1)..(window.last + 1) || pageCache[key]?.isOnScreen == true
                        if (!wanted) {
                            pageCache.remove(key)?.let { releasePage(it) }
                            page.cleanup()
                        } else {
                            val old = pageCache.put(key, page)
                            if (old != null && old !== page) releasePage(old)
                            if (!isContinuous) page.fadeIn()
                        }
                    } else {
                        Logger.log("WebGpuReaderView: giving up on page ${pageSlot.index + 1}: $lastError")
                        failedPages.add(key)
                        (pageCache[key] as? ProgressPage)?.failed = true
                        if (abs(key - currentKey) <= 1) {
                            snackString(activity.getString(R.string.reader_page_failed_tap_retry, pageSlot.index + 1))
                        }
                    }
                    invalidate()
                }
            } finally {
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) {
                    if (loadingJobs[key] === self) loadingJobs.remove(key)
                    // A cancelled load whose page is wanted again goes back in the queue.
                    if (!destroyed && gen == generation && self?.isCancelled == true &&
                        key in window && pageCache[key] is ProgressPage && !failedPages.contains(key)
                    ) {
                        pendingLoads.add(key)
                    }
                    schedule()
                }
            }
        }
        loadingJobs[key] = job
        job.start()
    }

    /**
     * How long to wait before trying again after a [kind] failure on try [attempt] (0-based), or
     * null to give up. Every retry goes back through the source (its client, headers, rate limit).
     */
    private fun retryDelay(kind: FailureKind, attempt: Int): Long? = when (kind) {
        // Slow down: the source's limiter/CDN refused us.
        FailureKind.RATE_LIMITED -> if (attempt < 4) 2_000L shl attempt else null
        FailureKind.NETWORK, FailureKind.SERVER -> if (attempt < 3) 800L * (attempt + 1) else null
        // The fetcher already refreshed the url once; one more go after a pause.
        FailureKind.EXPIRED, FailureKind.NO_DATA -> if (attempt < 1) 1_500L else null
        // Same bytes would fail the same way.
        FailureKind.DECODE -> null
    }

    /**
     * libvips first (AVIF/JXL/HEIF, animated formats); Android's decoder as a fallback, so a page
     * libvips rejects still shows instead of leaving the loading dot forever.
     */
    private suspend fun decodeToGpuImage(bytes: ByteArray): ImagePage? {
        val trimColors = if (activity.defaultSettings.cropBorders) {
            listOf(floatArrayOf(1f, 1f, 1f), floatArrayOf(0f, 0f, 0f))
        } else null

        val fromLibvips = try {
            ImageDecoder.open(ByteArrayInputStream(bytes)).use { dec ->
                if (dec.hasNext) {
                    val frame = dec.decodeNext()
                    try {
                        frame.image.rewind()
                        val gpuImage = Image(
                            frame.image,
                            frame.width,
                            frame.height,
                            createMipMaps = true,
                            trimColors = trimColors,
                            trimThreshold = 0.15f
                        )
                        ImagePage.ImageSingle(gpuImage)
                    } finally {
                        frame.close()
                    }
                } else null
            }
        } catch (e: OutOfMemoryError) {
            Logger.log("WebGpuReaderView: libvips decode OOM")
            null
        } catch (e: Exception) {
            Logger.log("WebGpuReaderView: libvips decode failed: ${e.message}")
            null
        }
        if (fromLibvips != null) return fromLibvips

        val bitmap = MangaImageFetcher.decodeBitmap(bytes, Bitmap.Config.ARGB_8888) ?: return null
        return try {
            val argb = if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap
            else bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return null
            // ARGB_8888 pixels are laid out as RGBA bytes, which is what Image takes.
            val buffer = ByteBuffer.allocateDirect(argb.byteCount)
            argb.copyPixelsToBuffer(buffer)
            buffer.rewind()
            val w = argb.width
            val h = argb.height
            if (argb !== bitmap) argb.recycle()
            ImagePage.ImageSingle(
                Image(
                    buffer,
                    w,
                    h,
                    createMipMaps = true,
                    trimColors = trimColors,
                    trimThreshold = 0.15f
                )
            )
        } catch (e: OutOfMemoryError) {
            Logger.log("WebGpuReaderView: fallback decode OOM")
            null
        } finally {
            bitmap.recycle()
        }
    }

    fun invalidate() {
        if (isContinuous) {
            continuousViewer?.state?.invalidate()
        } else {
            pagedViewer?.state?.invalidate()
        }
    }

    /** Drops every page and load (a new bind or decode settings). */
    fun clearCache() {
        generation++
        failedPages.clear()
        pendingLoads.clear()
        loadingJobs.values.forEach { it.cancel() }
        loadingJobs.clear()
        val pages = pageCache.values.toList()
        pageCache.clear()
        pages.forEach { releasePage(it) }
    }

    fun destroy() {
        destroyed = true
        mainHandler.removeCallbacksAndMessages(null)
        if (isContinuous) {
            continuousViewer?.state?.onViewport = null
            continuousViewer?.state?.onPageChange = null
            continuousViewer?.state?.onTap = null
            continuousViewer?.state?.fetchPage = null
        } else {
            pagedViewer?.state?.onTap = null
            pagedViewer?.state?.fetchPage = null
            pagedViewer?.state?.onPageChange = null
        }
        clearCache()
        // Nothing draws any more: free what was parked too.
        val parked = synchronized(deferredCleanup) {
            deferredCleanup.toList().also { deferredCleanup.clear() }
        }
        parked.forEach { it.cleanup() }
        lastReadThrough = null
        decodeDispatcher.close()
    }

    private companion object {
        /** Page fetches in flight at once (the source's own rate limiter still applies). */
        const val NETWORK_WORKERS = 2
        /** Start loading the next/previous chapter this many pages before its edge. */
        const val PRELOAD_CHAPTER_PAGES = 3
    }
}
