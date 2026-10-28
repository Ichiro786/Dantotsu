package ani.dantotsu.media.manga

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import ani.dantotsu.FileUrl
import ani.dantotsu.parsers.MangaImage
import ani.dantotsu.util.Logger
import android.os.SystemClock
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.util.Collections
import java.util.WeakHashMap

/**
 * One place that turns a reader page into image bytes, used by the WebGPU reader and (through
 * [ImageData]) the classic reader.
 *
 * Order: data: URI -> content:// -> local file -> the extension source (getImageUrl when the page
 * has no image url yet, then getImage = imageRequest(page) with the source's headers on the
 * source's OkHttp client, so Referer/cookies/Cloudflare/descramble interceptors and the
 * source's rate limit all apply; cached on disk in [ReaderPageCache]). Only a page with no source
 * at all uses a plain request with the page's (valid) headers.
 */
object MangaImageFetcher {

    private val headerName = Regex("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$")

    /** Drops entries OkHttp would reject ("Unexpected char ... in header name"). */
    fun validHeaders(headers: Map<String, String>): Map<String, String> =
        headers.filter { (k, v) ->
            k.isNotEmpty() && headerName.matches(k) && v.none { it == '\n' || it == '\r' || it == '\u0000' }
        }

    /** The [ImageData] (page + source) for [image]: its own source first, then the URL-keyed cache. */
    fun imageDataFor(image: MangaImage?, url: String): ImageData? {
        val page = image?.page
        val source = image?.source
        if (page != null && source != null) return ImageData(page, source)
        return try {
            Injekt.get<MangaCache>().get(url)
        } catch (_: Throwable) {
            null
        }
    }

    /** Resolves a page's image url through the source when the extension left it null (Mihon behaviour). */
    suspend fun ensureImageUrl(page: Page, source: HttpSource) {
        if (page.imageUrl.isNullOrBlank()) {
            page.imageUrl = source.getImageUrl(page)
        }
    }

    fun isDataUri(url: String) = url.startsWith("data:", ignoreCase = true)

    fun decodeDataUri(url: String): ByteArray? {
        val comma = url.indexOf(',')
        if (comma == -1) return null
        val meta = url.substring(0, comma)
        val payload = url.substring(comma + 1)
        return try {
            if (meta.contains(";base64", ignoreCase = true)) Base64.decode(payload, Base64.DEFAULT)
            else Uri.decode(payload).toByteArray()
        } catch (_: Exception) {
            null
        }
    }

    // ---- Failure classification ----------------------------------------------------------

    /** Why a page could not be loaded; decides whether and how soon a retry makes sense. */
    enum class FailureKind {
        /** 401/403/404/410...: usually an expired/signed url - refresh the page list, don't hammer. */
        EXPIRED,
        /** 429 / 503: the source (or its CDN) asks us to slow down - back off longer. */
        RATE_LIMITED,
        /** Other 5xx: transient server trouble. */
        SERVER,
        /** Connection reset, timeout, DNS...: retry soon. */
        NETWORK,
        /** The response had no usable bytes. */
        NO_DATA,
        /** Bytes arrived but no decoder could read them: refetching the same bytes won't help. */
        DECODE,
    }

    class PageLoadException(
        val kind: FailureKind,
        message: String,
        cause: Throwable? = null,
        val code: Int = 0,
    ) : IOException(message, cause)

    /** Maps any failure from a page fetch to a [PageLoadException]; cancellation passes through. */
    fun classify(e: Throwable): PageLoadException = when (e) {
        is PageLoadException -> e
        is HttpException -> PageLoadException(
            when (e.code) {
                429, 503 -> FailureKind.RATE_LIMITED
                in 500..599 -> FailureKind.SERVER
                else -> FailureKind.EXPIRED
            },
            "HTTP ${e.code}", e, e.code
        )
        is InterruptedIOException, is IOException -> PageLoadException(FailureKind.NETWORK, e.toString(), e)
        else -> PageLoadException(FailureKind.NETWORK, e.toString(), e)
    }

    /**
     * Bytes of [page] through its own source: getImage = imageRequest(page) on the source's OkHttp
     * client, so its headers, rate limiter, Cloudflare and descramble interceptors (MangaFire,
     * Mangago...) all apply. Cached on disk after the interceptors ran (descrambled bytes).
     * An expired url is refreshed from the chapter's page list once. Throws [PageLoadException].
     */
    suspend fun fetchFromSourceOrThrow(page: Page, source: HttpSource): ByteArray {
        val key = cacheKey(page, source)
        ReaderPageCache.get(key)?.let { return it }
        val bytes = withPageRefreshOrThrow(page, source) { fetchFromSourceOnce(page, source) }
        // data: URIs are already local; everything else is worth keeping.
        if (page.imageUrl?.let { isDataUri(it) } != true) ReaderPageCache.put(key, bytes)
        return bytes
    }

    suspend fun fetchFromSource(page: Page, source: HttpSource): ByteArray? = try {
        fetchFromSourceOrThrow(page, source)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Logger.log("MangaImageFetcher: page ${page.index} failed: $e")
        null
    }

    private suspend fun fetchFromSourceOnce(page: Page, source: HttpSource): ByteArray {
        try {
            ensureImageUrl(page, source)
            val imageUrl = page.imageUrl
            if (imageUrl != null && isDataUri(imageUrl)) {
                return decodeDataUri(imageUrl)
                    ?: throw PageLoadException(FailureKind.NO_DATA, "bad data: uri")
            }
            val bytes = source.getImage(page).use {
                // extensions may override getImage without awaitSuccess(): never cache an error page
                if (!it.isSuccessful) throw HttpException(it.code)
                it.body.bytes()
            }
            if (bytes.isEmpty()) throw PageLoadException(FailureKind.NO_DATA, "empty image body")
            return bytes
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw classify(e)
        }
    }

    /** Stable across url refreshes: the chapter (and its page count) plus the page's position. */
    private fun cacheKey(page: Page, source: HttpSource): String {
        val group = chapterOfPage[page]
        return if (group != null) {
            "${source.id}|${group.chapter.url}|${group.pages.size}|${page.index}"
        } else {
            val url = page.url.ifBlank { page.imageUrl.orEmpty().substringBefore('?') }
            "${source.id}|$url|${page.index}"
        }
    }

    // ---- Expiring image urls (token / signed CDN links), Mihon-style ------------------------

    /** One chapter's page list as the reader holds it (the same Page objects as its MangaImages). */
    private class ChapterPages(val chapter: SChapter, val source: HttpSource, val pages: List<Page>) {
        val mutex = Mutex()
        @Volatile
        var lastRefresh = 0L
        @Volatile
        var lastRefreshOk = false
    }

    private val chapterOfPage = Collections.synchronizedMap(WeakHashMap<Page, ChapterPages>())

    /**
     * Called (on an IO thread) when a refreshed page list has a different number of pages than the
     * one the reader holds: matching by index would show the wrong pages, so the reader reloads
     * the whole chapter instead. Set by the reader while it is open.
     */
    @Volatile
    var onPageCountChanged: ((SChapter) -> Unit)? = null

    /** Remembers which chapter [pages] belong to, so an expired image url can be re-fetched. */
    fun registerChapterPages(pages: List<Page>, chapter: SChapter, source: HttpSource) {
        val group = ChapterPages(chapter, source, pages)
        pages.forEach { chapterOfPage[it] = group }
    }

    /**
     * Runs [block]; if it fails, refreshes the page's image url from the source once and runs it
     * again - but only for failures a new url can fix (expired/forbidden/not found, no data).
     * Rate limiting, server and network errors are rethrown as they are, so the caller backs off
     * instead of spending another page-list request. Throws [PageLoadException].
     */
    suspend fun <T : Any> withPageRefreshOrThrow(page: Page, source: HttpSource, block: suspend () -> T?): T {
        val failure = try {
            block()?.let { return it }
            PageLoadException(FailureKind.NO_DATA, "no image data")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            classify(e)
        }
        if (failure.kind != FailureKind.EXPIRED && failure.kind != FailureKind.NO_DATA) throw failure
        Logger.log("MangaImageFetcher: page ${page.index} failed ($failure), refreshing its url")
        if (!refreshPageUrl(page, source)) throw failure
        return try {
            block() ?: throw PageLoadException(FailureKind.NO_DATA, "no image data after refresh")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw classify(e)
        }
    }

    /** [withPageRefreshOrThrow] returning null instead of throwing (classic reader). */
    suspend fun <T : Any> withPageRefresh(page: Page, source: HttpSource, block: suspend () -> T?): T? =
        try {
            withPageRefreshOrThrow(page, source, block)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Logger.log("MangaImageFetcher: page ${page.index} failed: $e")
            null
        }

    /**
     * Re-fetches the chapter's page list (at most once per [REFRESH_INTERVAL_MS] per chapter;
     * concurrent failures share one request, and the source's rate limiter applies) and updates
     * the cached Page objects in place when the list still has the same number of pages, so every
     * holder of the list sees the fresh urls. A list of another size is not matched by index:
     * [onPageCountChanged] asks the reader to reload the chapter. Pages with no known chapter get
     * their image url re-resolved (getImageUrl) when they have a page url. Returns true when a
     * retry is worth it.
     */
    suspend fun refreshPageUrl(page: Page, source: HttpSource): Boolean = withContext(Dispatchers.IO) {
        val group = chapterOfPage[page]
        if (group == null) {
            if (page.url.isBlank()) return@withContext false
            return@withContext try {
                page.imageUrl = source.getImageUrl(page)
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Logger.log("MangaImageFetcher: getImageUrl refresh failed: $e")
                false
            }
        }
        val urlBefore = page.imageUrl
        var sizeChanged = false
        val ok = group.mutex.withLock {
            val now = SystemClock.elapsedRealtime()
            if (group.lastRefresh != 0L && now - group.lastRefresh < REFRESH_INTERVAL_MS) {
                // Another page of this chapter just refreshed the list: retry only if ours changed.
                return@withLock group.lastRefreshOk && page.imageUrl != urlBefore
            }
            group.lastRefresh = now
            group.lastRefreshOk = try {
                val fresh = group.source.getPageList(group.chapter)
                if (fresh.size != group.pages.size) {
                    Logger.log("MangaImageFetcher: refreshed page list has ${fresh.size} pages, had ${group.pages.size}: reloading the chapter")
                    sizeChanged = fresh.isNotEmpty()
                    false
                } else {
                    group.pages.forEachIndexed { i, old ->
                        // Null = resolved lazily again through getImageUrl(page).
                        old.imageUrl = fresh[i].imageUrl?.takeIf { it.isNotBlank() }
                    }
                    Logger.log("MangaImageFetcher: refreshed page list of ${group.chapter.name}")
                    fresh.isNotEmpty()
                }
            } catch (e: CancellationException) {
                group.lastRefresh = 0L
                throw e
            } catch (e: Throwable) {
                Logger.log("MangaImageFetcher: page list refresh failed: $e")
                false
            }
            group.lastRefreshOk
        }
        if (sizeChanged) {
            try {
                onPageCountChanged?.invoke(group.chapter)
            } catch (e: Throwable) {
                Logger.log(e)
            }
        }
        ok
    }

    private const val REFRESH_INTERVAL_MS = 30_000L

    /**
     * Bytes of the image behind [link] (and [image], if known). Null when every route failed;
     * [CancellationException] is always rethrown.
     */
    suspend fun fetchBytes(context: Context, link: FileUrl, image: MangaImage? = null): ByteArray? =
        try {
            fetchBytesOrThrow(context, link, image)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Logger.log("MangaImageFetcher: ${link.url} failed: $e")
            null
        }

    /** Like [fetchBytes] but throws a [PageLoadException] saying why, for retry decisions. */
    suspend fun fetchBytesOrThrow(context: Context, link: FileUrl, image: MangaImage? = null): ByteArray =
        withContext(Dispatchers.IO) {
            val url = link.url
            if (url.isEmpty()) throw PageLoadException(FailureKind.NO_DATA, "empty url")
            if (isDataUri(url)) {
                return@withContext decodeDataUri(url)
                    ?: throw PageLoadException(FailureKind.NO_DATA, "bad data: uri")
            }
            if (url.startsWith("content://")) {
                return@withContext try {
                    context.contentResolver.openInputStream(Uri.parse(url))?.use { it.readBytes() }
                } catch (e: Exception) {
                    throw PageLoadException(FailureKind.NO_DATA, "content uri failed: ${e.message}", e)
                } ?: throw PageLoadException(FailureKind.NO_DATA, "content uri empty")
            }
            val localFile = File(url.removePrefix("file://"))
            if (!url.startsWith("http") && localFile.isFile) {
                return@withContext try {
                    localFile.readBytes()
                } catch (e: Exception) {
                    throw PageLoadException(FailureKind.NO_DATA, "local file failed: ${e.message}", e)
                }
            }

            val data = imageDataFor(image, url)
            if (data != null) {
                // Never fall back to a plain client for a page that has a source: that skipped the
                // source's headers, rate limit (Atsumaru: 2 req/s) and descramble interceptors.
                return@withContext fetchFromSourceOrThrow(data.page, data.source)
            }

            if (!url.startsWith("http")) throw PageLoadException(FailureKind.NO_DATA, "no route for $url")
            ReaderPageCache.get(url)?.let { return@withContext it }
            try {
                val client = Injekt.get<OkHttpClient>()
                val request = Request.Builder().url(url).apply {
                    validHeaders(link.headers).forEach { (k, v) -> header(k, v) }
                }.build()
                val bytes = client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) response.body.bytes()
                    else throw HttpException(response.code)
                }
                if (bytes.isEmpty()) throw PageLoadException(FailureKind.NO_DATA, "empty body")
                ReaderPageCache.put(url, bytes)
                bytes
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                throw classify(e)
            }
        }

    /**
     * Decodes [bytes] with BitmapFactory (sampled down only if the bitmap could not be drawn at
     * all: > ~96 MB), falling back to libvips for formats Android can't decode (JXL, AVIF < 12...).
     */
    fun decodeBitmap(bytes: ByteArray, config: Bitmap.Config = Bitmap.Config.RGB_565): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth > 0 && bounds.outHeight > 0) {
            val bpp = if (config == Bitmap.Config.RGB_565) 2L else 4L
            var sample = 1
            // Canvas refuses bitmaps over 100 MB ("trying to draw too large bitmap").
            while (bounds.outWidth.toLong() / sample * (bounds.outHeight.toLong() / sample) * bpp > MAX_BITMAP_BYTES) {
                sample *= 2
            }
            val options = BitmapFactory.Options().apply {
                inPreferredConfig = config
                inSampleSize = sample
            }
            try {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.let { return it }
            } catch (e: OutOfMemoryError) {
                Logger.log("MangaImageFetcher: OOM decoding ${bounds.outWidth}x${bounds.outHeight}")
                return null
            }
        }
        return ani.dantotsu.media.manga.mangareader.BaseImageAdapter.decodeWithLibvips(bytes.inputStream())
    }

    private const val MAX_BITMAP_BYTES = 96L * 1024 * 1024
}
