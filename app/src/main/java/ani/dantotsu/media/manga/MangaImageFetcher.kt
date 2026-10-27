package ani.dantotsu.media.manga

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import ani.dantotsu.FileUrl
import ani.dantotsu.parsers.MangaImage
import ani.dantotsu.util.Logger
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.io.IOException

/**
 * One place that turns a reader page into image bytes, used by the WebGPU reader and (through
 * [ImageData]) the classic reader.
 *
 * Order: data: URI -> content:// -> local file -> the extension source (getImageUrl when the page
 * has no image url yet, then getImage = imageRequest(page) with the source's headers on the
 * source's OkHttp client, so Referer/cookies/Cloudflare/descramble interceptors all apply) ->
 * a plain request with the page's (valid) headers as a last resort.
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

    suspend fun fetchFromSource(page: Page, source: HttpSource): ByteArray? {
        ensureImageUrl(page, source)
        val imageUrl = page.imageUrl
        if (imageUrl != null && isDataUri(imageUrl)) return decodeDataUri(imageUrl)
        val response = source.getImage(page)
        return response.use { if (it.isSuccessful) it.body.bytes() else null }
    }

    /**
     * Bytes of the image behind [link] (and [image], if known). Null when every route failed;
     * [CancellationException] is always rethrown.
     */
    suspend fun fetchBytes(context: Context, link: FileUrl, image: MangaImage? = null): ByteArray? =
        withContext(Dispatchers.IO) {
            val url = link.url
            if (url.isEmpty()) return@withContext null
            if (isDataUri(url)) return@withContext decodeDataUri(url)
            if (url.startsWith("content://")) {
                return@withContext try {
                    context.contentResolver.openInputStream(Uri.parse(url))?.use { it.readBytes() }
                } catch (e: Exception) {
                    Logger.log("MangaImageFetcher: content uri failed: ${e.message}")
                    null
                }
            }
            val localFile = File(url.removePrefix("file://"))
            if (!url.startsWith("http") && localFile.isFile) {
                return@withContext try {
                    localFile.readBytes()
                } catch (e: Exception) {
                    Logger.log("MangaImageFetcher: local file failed: ${e.message}")
                    null
                }
            }

            val data = imageDataFor(image, url)
            if (data != null) {
                try {
                    fetchFromSource(data.page, data.source)?.let { return@withContext it }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Logger.log("MangaImageFetcher: source.getImage failed for $url: $e")
                }
            }

            if (!url.startsWith("http")) return@withContext null
            try {
                val client = Injekt.get<OkHttpClient>()
                val request = Request.Builder().url(url).apply {
                    validHeaders(link.headers).forEach { (k, v) -> header(k, v) }
                }.build()
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) response.body.bytes()
                    else throw IOException("HTTP ${response.code}")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Logger.log("MangaImageFetcher: plain request failed for $url: $e")
                null
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
