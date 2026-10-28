package ani.dantotsu.media.manga

import android.content.ContentResolver
import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.LruCache
import ani.dantotsu.snackString
import ani.dantotsu.util.Logger
import ani.dantotsu.util.createDataSaver
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream

data class ImageData(
    val page: Page,
    val source: HttpSource
) {
    /**
     * The page as a bitmap, through its source (headers, rate limit, interceptors), the reader's
     * disk cache, and - like Mihon - one url refresh for an expired/signed url.
     */
    suspend fun fetchAndProcessImage(
        page: Page,
        httpSource: HttpSource
    ): Bitmap? = withContext(Dispatchers.IO) {
        try {
            // Pages whose image url needs its own request are resolved here, on first load.
            MangaImageFetcher.ensureImageUrl(page, httpSource)
            val originalUrl = page.imageUrl
            if (originalUrl != null && !MangaImageFetcher.isDataUri(originalUrl)) {
                val compressedUrl = createDataSaver().compress(originalUrl)
                if (compressedUrl != originalUrl) {
                    fetchDataSaver(page, httpSource, originalUrl, compressedUrl)?.let { return@withContext it }
                    Logger.log("DataSaver failed or was blocked by Cloudflare; falling back to original URL: $originalUrl")
                }
            }
            val bytes = MangaImageFetcher.fetchFromSource(page, httpSource) ?: return@withContext null
            // Bytes first, then BitmapFactory with a libvips fallback (AVIF/JXL/HEIF).
            MangaImageFetcher.decodeBitmap(bytes)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.log("MangaCache image fetch error: ${e.message}")
            null
        }
    }

    private suspend fun fetchDataSaver(
        page: Page,
        httpSource: HttpSource,
        originalUrl: String,
        compressedUrl: String
    ): Bitmap? {
        return try {
            page.imageUrl = compressedUrl
            val response = httpSource.getImage(page)
            Logger.log("DataSaver Response: ${response.code} - ${response.message}")
            response.use {
                if (it.isSuccessful) MangaImageFetcher.decodeBitmap(it.body.bytes()) else null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.log("DataSaver failed, falling back to original: ${e.message}")
            null
        } finally {
            page.imageUrl = originalUrl
        }
    }
}

fun saveImage(
    bitmap: Bitmap,
    contentResolver: ContentResolver,
    filename: String,
    format: Bitmap.CompressFormat,
    quality: Int
) {
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/${format.name.lowercase()}")
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    "${Environment.DIRECTORY_DOWNLOADS}/Dantotsu/Manga"
                )
            }

            val uri: Uri? =
                contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)

            uri?.let {
                contentResolver.openOutputStream(it)?.use { os ->
                    bitmap.compress(format, quality, os)
                } ?: throw FileNotFoundException("Failed to open output stream for URI: $uri")
            }
        } else {
            val directory =
                File("${Environment.getExternalStorageDirectory()}${File.separator}Dantotsu${File.separator}Manga")
            if (!directory.exists()) {
                directory.mkdirs()
            }

            val file = File(directory, filename)

            // Check if the file already exists
            if (file.exists()) {
                println("File already exists: ${file.absolutePath}")
                return
            }

            FileOutputStream(file).use { outputStream ->
                bitmap.compress(format, quality, outputStream)
            }
        }
    } catch (e: FileNotFoundException) {
        println("File not found: ${e.message}")
    } catch (e: Exception) {
        println("Exception while saving image: ${e.message}")
    }
}

class MangaCache {
    // ImageData is tiny (a Page and a source reference); keep plenty so a long session or a
    // second reader instance never falls back to loading without the source's client.
    private val maxEntries = 3000
    private val cache = LruCache<String, ImageData>(maxEntries)
    private val cacheSizeKb = ((Runtime.getRuntime().maxMemory() / 1024) / 8).toInt().coerceIn(32 * 1024, 96 * 1024)
    private class CachedBitmap(val bitmap: Bitmap, val sizeKb: Int)

    private val bitmapCache = object : LruCache<String, CachedBitmap>(cacheSizeKb) {
        override fun sizeOf(key: String, value: CachedBitmap): Int {
            return value.sizeKb
        }
    }

    @Synchronized
    fun put(key: String, imageDate: ImageData) {
        cache.put(key, imageDate)
    }

    @Synchronized
    fun get(key: String): ImageData? = cache.get(key)

    @Synchronized
    fun remove(key: String) {
        cache.remove(key)
        bitmapCache.remove(key)
    }

    @Synchronized
    fun clear() {
        cache.evictAll()
        bitmapCache.evictAll()
    }

    @Synchronized
    fun putBitmap(key: String, bitmap: Bitmap) {
        if (bitmap.isRecycled) return
        val sizeKb = (bitmap.allocationByteCount / 1024).coerceAtLeast(1)
        bitmapCache.put(key, CachedBitmap(bitmap, sizeKb))
    }

    @Synchronized
    fun getBitmap(key: String): Bitmap? {
        val entry = bitmapCache.get(key) ?: return null
        return if (!entry.bitmap.isRecycled) {
            entry.bitmap
        } else {
            bitmapCache.remove(key)
            null
        }
    }

    @Synchronized
    fun clearBitmaps() {
        bitmapCache.evictAll()
    }

    fun size(): Int = cache.size()


}
