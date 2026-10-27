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

    private var currentChapter: MangaChapter? = null
    private var images: List<MangaImage> = emptyList()

    private val pageCache = ConcurrentHashMap<Int, ImagePage>()
    private val loadingJobs = ConcurrentHashMap<Int, Job>()
    private val maxCacheSize = 8

    /** Pages whose every load attempt failed; a tap retries them. */
    private val failedPages = ConcurrentHashMap.newKeySet<Int>()

    /** Bumped by [clearCache]: a load started for an older bind must not install its page. */
    @Volatile
    private var generation = 0

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Last page number reported to the activity (continuous mode reports from the render thread). */
    @Volatile
    private var reportedPage = -1

    var currentPosition: Int = 0
        private set

    inner class ProgressPage(val pageIndex: Int) : ImagePage.Render(0, 0) {
        /** Every attempt failed: drawn as a red dot instead of the grey loading dot. */
        @Volatile
        var failed = false

        override val width: Int
            get() = (pagedViewer?.state?.width ?: continuousViewer?.state?.width)?.takeIf { it > 0 } ?: 1080

        override val height: Int
            get() = (pagedViewer?.state?.height ?: continuousViewer?.state?.height)?.takeIf { it > 0 } ?: 1920

        override val backgroundColor: Int
            get() = when (activity.defaultSettings.backgroundColor) {
                1 -> Color.BLACK
                2 -> Color.DKGRAY
                3 -> Color.WHITE
                else -> Color.BLACK
            }

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
                    val target = (currentPosition + delta).coerceIn(0, (images.size - 1).coerceAtLeast(0))
                    if (target != currentPosition) {
                        currentPosition = target
                        mainHandler.post { onCurrentPageChanged(target, target) }
                    }
                }
                // Observation only (called on the render thread): the deepest page read through
                // drives the page counter / progress, never the page provider.
                onViewport = { readThrough ->
                    val idx = pageCache.entries.firstOrNull { it.value === readThrough }?.key
                    val shown = max(idx ?: currentPosition, currentPosition)
                    if (shown != reportedPage) {
                        mainHandler.post { onCurrentPageChanged(currentPosition, shown) }
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
                    val target = currentPosition + delta
                    if (target in images.indices) {
                        currentPosition = target
                        mainHandler.post { onCurrentPageChanged(target, target) }
                    }
                }
            }
        }
    }

    /** Main thread: report the page to the reader and keep the decode window around [center]. */
    private fun onCurrentPageChanged(center: Int, shown: Int) {
        val chap = currentChapter ?: return
        if (images.isEmpty()) return
        val page = shown.coerceIn(0, images.size - 1)
        if (page != reportedPage) {
            reportedPage = page
            activity.onChapterScrolledTo(chap, page + 1, images.size)
            checkPreloadBoundaries(page)
        }
        preloadAround(center)
    }

    /** Retries every page whose load failed (after a tap, or when the network is back). */
    fun retryFailedPages() {
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
        currentChapter = chapter
        images = chapter.images()
        currentPosition = (initialPage - 1).coerceIn(0, (images.size - 1).coerceAtLeast(0))
        reportedPage = currentPosition
        clearCache()
        if (currentPosition in images.indices) {
            pageCache[currentPosition] = ProgressPage(currentPosition)
            loadPage(currentPosition)
        }
        preloadAround(currentPosition)
        invalidate()
    }

    fun scrollToPage(pageNumber: Int) {
        val target = (pageNumber - 1).coerceIn(0, (images.size - 1).coerceAtLeast(0))
        val delta = target - currentPosition
        currentPosition = target
        preloadAround(currentPosition)
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
        val targetIdx = currentPosition + delta
        if (targetIdx !in images.indices) return null

        val cached = pageCache[targetIdx]
        if (cached != null) return cached

        val placeholder = ProgressPage(targetIdx)
        pageCache[targetIdx] = placeholder
        loadPage(targetIdx)
        return placeholder
    }

    private fun preloadAround(center: Int) {
        // Short pages can put more than 3 on screen below the current one.
        val below = max(3, (continuousViewer?.state?.pagesBelow ?: 0) + 2)
        val range = (center - 2)..(center + below)
        for (i in range) {
            if (i in images.indices && !pageCache.containsKey(i)) {
                loadPage(i)
            }
        }
        trimCache(center)
    }

    private fun checkPreloadBoundaries(pos: Int) {
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

    private fun loadPage(index: Int) {
        val currentEntry = pageCache[index]
        if (currentEntry != null && currentEntry !is ProgressPage) return
        val img = images.getOrNull(index) ?: return
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
                        if (abs(index - currentPosition) <= 1) {
                            withContext(Dispatchers.Main) {
                                snackString(activity.getString(R.string.reader_page_failed_tap_retry, index + 1))
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
    }
}
