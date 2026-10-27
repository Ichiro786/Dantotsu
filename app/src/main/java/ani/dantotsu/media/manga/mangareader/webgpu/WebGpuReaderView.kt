package ani.dantotsu.media.manga.mangareader.webgpu

import android.graphics.Color
import android.view.View
import androidx.lifecycle.lifecycleScope
import androidx.webgpu.GPUTexture
import ani.dantotsu.media.manga.MangaChapter
import ani.dantotsu.media.manga.mangareader.MangaReaderActivity
import ani.dantotsu.parsers.MangaImage
import ani.dantotsu.util.Logger
import ca.mpreg.imagedecoder.ImageDecoder
import ca.mpreg.webgpuviewer.ImageView
import ca.mpreg.webgpuviewer.ImageViewContinuous
import ca.mpreg.webgpuviewer.renderer.Image
import ca.mpreg.webgpuviewer.transition.TransitionBasic
import ca.mpreg.webgpuviewer.viewer.ImagePage
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import ani.dantotsu.R
import ani.dantotsu.media.manga.MangaImageFetcher
import ani.dantotsu.snackString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import ca.mpreg.webgpuviewer.draw.TextAlign
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

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

    /** The chapter whose page is on screen (changes while continuous mode scrolls across chapters). */
    @Volatile
    private var currentChapter: MangaChapter? = null
    @Volatile
    private var images: List<MangaImage> = emptyList()

    /**
     * What the viewer scrolls through, keyed by a stable "slot key". Paged mode: the bound chapter's
     * pages at keys 0 until n. Continuous mode (Mihon-style webtoon): transitions and the pages of
     * neighbouring chapters are added before/after as the reader nears a chapter end; keys of
     * existing slots never change, so appending or prepending never moves what is on screen.
     */
    private sealed class Slot {
        class PageSlot(val chapter: MangaChapter, val images: List<MangaImage>, val index: Int) : Slot() {
            val image: MangaImage get() = images[index]
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
    /** Slot the viewer considers "current" (getPage(delta) is relative to it). */
    @Volatile
    private var currentKey = 0

    /** Chapters (uniqueNumber) whose pages are in [slots]; and chapters whose page list is loading. */
    private val chaptersInTimeline = ConcurrentHashMap.newKeySet<String>()
    private val chapterLoads = ConcurrentHashMap.newKeySet<String>()

    private val pageCache = ConcurrentHashMap<Int, ImagePage>()
    private val loadingJobs = ConcurrentHashMap<Int, Job>()
    private val maxCacheSize = 8

    /** Pages whose every load attempt failed; a tap retries them. */
    private val failedPages = ConcurrentHashMap.newKeySet<Int>()

    /** Bumped by [clearCache]: a load started for an older bind must not install its page. */
    @Volatile
    private var generation = 0

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Last slot reported to the activity (continuous mode reports from the render thread). */
    @Volatile
    private var reportedKey = Int.MIN_VALUE

    /** Page index (0-based) within the current chapter; a transition counts as its chapter's edge. */
    val currentPosition: Int
        get() = when (val slot = slots[currentKey]) {
            is Slot.PageSlot -> slot.index
            is Slot.TransitionSlot -> if (slot.previous) 0 else (images.size - 1).coerceAtLeast(0)
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

    inner class ProgressPage(val pageIndex: Int) : ImagePage.Render(0, 0) {
        /** Every attempt failed: drawn as a red dot instead of the grey loading dot. */
        @Volatile
        var failed = false

        override val width: Int
            get() = viewerWidth

        override val height: Int
            get() = viewerHeight

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
            val full = min(width, height) * 0.25f * scale
            circle(cx, cy, full / 2f, if (failed) 0x88CC4444.toInt() else 0x55888888.toInt())
        }
    }

    /** Slim "Finished: Ch X / Next: Ch Y" item between two chapters in continuous mode. */
    private inner class TransitionPage(val slot: Slot.TransitionSlot) : ImagePage.Render(0, 0) {
        override val width: Int
            get() = viewerWidth

        override val height: Int
            get() = (viewerHeight * 0.3f).toInt().coerceAtLeast(1)

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
        val bgColor = when (activity.defaultSettings.backgroundColor) {
            1 -> Color.BLACK
            2 -> Color.DKGRAY
            3 -> Color.WHITE
            else -> Color.BLACK
        }

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
                fetchPage = { delta ->
                    getPageForDelta(delta)
                }
                // getPage(n) is relative to the current page, and the viewer moves "current" with
                // onPageChange while it scrolls. Without this the current page never advanced:
                // after scrolling past page 1 the viewer was handed page 1 again, so the strip
                // jumped back and the counter stayed at 1/N.
                onPageChange = { delta ->
                    val target = (currentKey + delta).coerceIn(firstKey, lastKey.coerceAtLeast(firstKey))
                    if (target != currentKey) {
                        currentKey = target
                        mainHandler.post { onCurrentPageChanged(target, target) }
                    }
                }
                // Observation only (called on the render thread): the deepest page read through
                // drives the page counter / progress, never the page provider.
                onViewport = { readThrough ->
                    val key = pageCache.entries.firstOrNull { it.value === readThrough }?.key
                    val center = currentKey
                    val shown = max(key ?: center, center)
                    if (shown != reportedKey) {
                        mainHandler.post { onCurrentPageChanged(center, shown) }
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
                fetchPage = { delta ->
                    getPageForDelta(delta)
                }
                onPageChange = { delta ->
                    val target = currentKey + delta
                    if (target in firstKey..lastKey) {
                        currentKey = target
                        mainHandler.post { onCurrentPageChanged(target, target) }
                    }
                }
            }
        }
    }

    /**
     * Main thread: report the page (and, in continuous mode, a chapter change) to the reader and
     * keep the decode window around [center]. [onChapterScrolledTo] updates the chapter title,
     * counter, seekbar and saves progress (incognito / save-progress settings respected there).
     */
    private fun onCurrentPageChanged(center: Int, shown: Int) {
        if (activity.isFinishing || activity.isDestroyed) return
        if (shown != reportedKey) {
            when (val slot = slots[shown]) {
                is Slot.PageSlot -> {
                    reportedKey = shown
                    if (currentChapter?.uniqueNumber() != slot.chapter.uniqueNumber()) {
                        currentChapter = slot.chapter
                        images = slot.images
                    }
                    activity.onChapterScrolledTo(slot.chapter, slot.index + 1, slot.images.size)
                    checkPreloadBoundaries(slot)
                }
                is Slot.TransitionSlot -> {
                    reportedKey = shown
                    slot.to?.let { ensureChapterInTimeline(it, forward = !slot.previous) }
                }
                null -> {}
            }
        }
        preloadAround(center)
    }

    private fun neighbourChapter(chap: MangaChapter, offset: Int): MangaChapter? {
        val idx = activity.chaptersArr.indexOf(chap.uniqueNumber())
        if (idx == -1) return null
        return activity.chaptersArr.getOrNull(idx + offset)?.let { activity.chapters[it] }
    }

    /** Continuous mode: loads [chap]'s page list if needed and splices it into the timeline. */
    private fun ensureChapterInTimeline(chap: MangaChapter, forward: Boolean) {
        if (!isContinuous) return
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
                if (gen != generation) return@withContext
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
        toRetry.forEach { index ->
            (pageCache[index] as? ProgressPage)?.failed = false
            loadPage(index)
        }
        invalidate()
    }

    fun updateBackgroundColor(color: Int) {
        viewer.setBackgroundColor(color)
        if (isContinuous) {
            continuousViewer?.state?.backgroundColor = color
        }
        invalidate()
    }

    fun bind(chapter: MangaChapter, initialPage: Int) {
        clearCache()
        slots.clear()
        chaptersInTimeline.clear()
        currentChapter = chapter
        images = chapter.images()
        images.indices.forEach { i -> slots[i] = Slot.PageSlot(chapter, images, i) }
        firstKey = 0
        lastKey = images.size - 1
        if (isContinuous) {
            chaptersInTimeline.add(chapter.uniqueNumber())
            slots[-1] = Slot.TransitionSlot(chapter, neighbourChapter(chapter, -1), previous = true)
            slots[images.size] = Slot.TransitionSlot(chapter, neighbourChapter(chapter, 1), previous = false)
            firstKey = -1
            lastKey = images.size
        }
        val start = (initialPage - 1).coerceIn(0, (images.size - 1).coerceAtLeast(0))
        currentKey = if (images.isEmpty()) firstKey.coerceAtMost(lastKey).coerceAtLeast(0) else start
        reportedKey = currentKey
        if (slots[currentKey] is Slot.PageSlot) {
            pageCache[currentKey] = ProgressPage(start)
            loadPage(currentKey)
        }
        preloadAround(currentKey)
        (slots[currentKey] as? Slot.PageSlot)?.let { checkPreloadBoundaries(it) }
        invalidate()
    }

    /** Key of page [pageIndex] of the current chapter (paged mode: the index itself). */
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

    fun scrollToPage(pageNumber: Int) {
        val targetIndex = (pageNumber - 1).coerceIn(0, (images.size - 1).coerceAtLeast(0))
        val target = keyForPage(targetIndex)
        val delta = target - currentKey
        currentKey = target
        preloadAround(currentKey)
        if (!isContinuous) {
            pagedViewer?.state?.animatePageTurn(if (isReversed) -delta else delta)
        } else {
            continuousViewer?.state?.resetScroll()
        }
        invalidate()
    }

    fun scrollUp() {
        if (isContinuous) {
            continuousViewer?.state?.let { s ->
                s.animateScroll(-s.height / 2f)
            }
        } else {
            pagedViewer?.state?.animatePageTurn(if (isReversed) 1 else -1)
        }
    }

    fun scrollDown() {
        if (isContinuous) {
            continuousViewer?.state?.let { s ->
                s.animateScroll(s.height / 2f)
            }
        } else {
            pagedViewer?.state?.animatePageTurn(if (isReversed) -1 else 1)
        }
    }

    private fun getPageForDelta(delta: Int): ImagePage? {
        val key = currentKey + delta
        val slot = slots[key] ?: return null

        val cached = pageCache[key]
        if (cached != null) return cached

        return when (slot) {
            is Slot.PageSlot -> {
                val placeholder = ProgressPage(slot.index)
                pageCache[key] = placeholder
                loadPage(key)
                placeholder
            }
            is Slot.TransitionSlot -> TransitionPage(slot).also { pageCache[key] = it }
        }
    }

    private fun preloadAround(center: Int) {
        // Short pages can put more than 3 on screen below the current one.
        val below = max(3, (continuousViewer?.state?.pagesBelow ?: 0) + 2)
        val range = (center - 2)..(center + below)
        for (k in range) {
            if (slots[k] is Slot.PageSlot && !pageCache.containsKey(k)) {
                loadPage(k)
            }
        }
        trimCache(center)
    }

    private fun checkPreloadBoundaries(slot: Slot.PageSlot) {
        if (isContinuous) {
            // Mihon-style: fetch the neighbouring chapter's page list while the last/first
            // pages are still being read, so the strip flows straight into it.
            if (slot.images.size - (slot.index + 1) <= PRELOAD_CHAPTER_PAGES) {
                neighbourChapter(slot.chapter, 1)?.let { ensureChapterInTimeline(it, forward = true) }
            }
            if (slot.index < PRELOAD_CHAPTER_PAGES) {
                neighbourChapter(slot.chapter, -1)?.let { ensureChapterInTimeline(it, forward = false) }
            }
            return
        }
        val pos = slot.index
        if (images.isEmpty()) return
        if (images.size - (pos + 1) <= 5) {
            val nextIdx = activity.currentChapterIndex + 1
            activity.chaptersArr.getOrNull(nextIdx)?.let { key ->
                activity.chapters[key]?.let { nextChap ->
                    activity.preloadChapterAndAppend(nextChap)
                }
            }
        }
        if (pos <= 5) {
            val prevIdx = activity.currentChapterIndex - 1
            activity.chaptersArr.getOrNull(prevIdx)?.let { key ->
                activity.chapters[key]?.let { prevChap ->
                    activity.preloadChapterAndPrepend(prevChap)
                }
            }
        }
    }

    /** Loads the page in slot [index] (a slot key; equals the page index in paged mode). */
    private fun loadPage(index: Int) {
        val currentEntry = pageCache[index]
        if (currentEntry != null && currentEntry !is ProgressPage) return
        val pageSlot = slots[index] as? Slot.PageSlot ?: return
        val img = pageSlot.image
        val gen = generation

        synchronized(loadingJobs) {
            if (loadingJobs.containsKey(index)) return
            // Registered before it can run, so a fast finish can't leave a stale entry behind
            // (which blocked every later load of the page).
            val job = activity.lifecycleScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
                val self = coroutineContext[Job]
                try {
                    var imagePage: ImagePage? = null
                    var lastError: Throwable? = null
                    for (attempt in 0 until MAX_ATTEMPTS) {
                        if (attempt > 0) delay(RETRY_DELAY_MS * attempt)
                        if (gen != generation) return@launch
                        try {
                            val bytes = MangaImageFetcher.fetchBytes(activity, img.url, img)
                            if (bytes == null) {
                                lastError = IllegalStateException("no image data")
                                continue
                            }
                            imagePage = decodeToGpuImage(bytes)
                            if (imagePage != null) break
                            lastError = IllegalStateException("could not decode image")
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            lastError = e
                            Logger.log("WebGpuReaderView: page $index attempt ${attempt + 1} failed: $e")
                        }
                    }

                    if (gen != generation) {
                        imagePage?.cleanup()
                        return@launch
                    }
                    if (imagePage != null) {
                        failedPages.remove(index)
                        val old = pageCache.put(index, imagePage)
                        if (old != null && old !== imagePage) old.cleanup()
                        if (!isContinuous) imagePage.fadeIn()
                    } else {
                        Logger.log("WebGpuReaderView: giving up on page $index: $lastError")
                        failedPages.add(index)
                        val placeholder = pageCache[index] as? ProgressPage
                        placeholder?.failed = true
                        if (abs(index - currentKey) <= 1) {
                            withContext(Dispatchers.Main) {
                                snackString(activity.getString(R.string.reader_page_failed_tap_retry, pageSlot.index + 1))
                            }
                        }
                    }
                    withContext(Dispatchers.Main) { invalidate() }
                } finally {
                    synchronized(loadingJobs) {
                        if (loadingJobs[index] === self) loadingJobs.remove(index)
                    }
                }
            }
            loadingJobs[index] = job
            job.start()
        }
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
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
            if (argb !== bitmap) argb.recycle()
            ImagePage.ImageSingle(
                Image(
                    buffer,
                    bitmap.width,
                    bitmap.height,
                    createMipMaps = true,
                    trimColors = trimColors,
                    trimThreshold = 0.15f
                )
            )
        } finally {
            bitmap.recycle()
        }
    }

    private fun trimCache(center: Int) {
        if (pageCache.size <= maxCacheSize) return
        val below = max(4, (continuousViewer?.state?.pagesBelow ?: 0) + 3)
        val iterator = pageCache.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key < center - 4 || entry.key > center + below) {
                entry.value.cleanup()
                iterator.remove()
            }
        }
    }

    fun invalidate() {
        if (isContinuous) {
            continuousViewer?.state?.invalidate()
        } else {
            pagedViewer?.state?.invalidate()
        }
    }

    fun clearCache() {
        generation++
        failedPages.clear()
        synchronized(loadingJobs) {
            loadingJobs.values.forEach { it.cancel() }
            loadingJobs.clear()
        }
        pageCache.values.forEach { it.cleanup() }
        pageCache.clear()
    }

    fun destroy() {
        mainHandler.removeCallbacksAndMessages(null)
        clearCache()
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
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
        const val RETRY_DELAY_MS = 800L
        /** Start loading the next/previous chapter this many pages before its edge. */
        const val PRELOAD_CHAPTER_PAGES = 3
    }
}
