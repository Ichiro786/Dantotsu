package ani.dantotsu.media.manga

import ani.dantotsu.App
import ani.dantotsu.util.Logger
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

/**
 * Disk cache of reader page images (Mihon's ChapterCache idea): the bytes a source returned,
 * after its interceptors (descrambled), keyed by chapter + page position so an expired/refreshed
 * image url still hits. Re-reading a chapter, scrolling back after an eviction, a settings change
 * that re-decodes, or reopening the reader no longer costs another request on rate-limited
 * sources. LRU by last access, bounded to [MAX_BYTES]; lives in the app's cache dir, so the
 * system may also clear it.
 */
object ReaderPageCache {

    private const val MAX_BYTES = 256L * 1024 * 1024
    private const val TRIM_TO_BYTES = MAX_BYTES * 3 / 4
    private const val MAX_ENTRY_BYTES = 48L * 1024 * 1024

    private val lock = Any()
    private val size = AtomicLong(-1)

    private val dir: File? by lazy {
        val base = App.context?.cacheDir ?: return@lazy null
        File(base, "reader_pages").apply { mkdirs() }.takeIf { it.isDirectory }
    }

    private fun fileFor(key: String): File? {
        val d = dir ?: return null
        val digest = MessageDigest.getInstance("SHA-1").digest(key.toByteArray())
        val name = digest.joinToString("") { "%02x".format(it) }
        return File(d, name)
    }

    fun get(key: String): ByteArray? {
        val file = fileFor(key) ?: return null
        return try {
            if (!file.isFile) return null
            val bytes = file.readBytes()
            if (bytes.isEmpty()) {
                file.delete()
                return null
            }
            file.setLastModified(System.currentTimeMillis())
            bytes
        } catch (e: Exception) {
            null
        }
    }

    fun put(key: String, bytes: ByteArray) {
        if (bytes.isEmpty() || bytes.size > MAX_ENTRY_BYTES) return
        val file = fileFor(key) ?: return
        try {
            val tmp = File(file.parentFile, file.name + ".tmp" + Thread.currentThread().id)
            tmp.writeBytes(bytes)
            val previous = if (file.isFile) file.length() else 0L
            if (!tmp.renameTo(file)) {
                tmp.delete()
                return
            }
            val total = currentSize() + bytes.size - previous
            size.set(total)
            if (total > MAX_BYTES) trim()
        } catch (e: Exception) {
            Logger.log("ReaderPageCache: write failed: ${e.message}")
        }
    }

    private fun currentSize(): Long {
        val known = size.get()
        if (known >= 0) return known
        synchronized(lock) {
            if (size.get() < 0) {
                size.set(dir?.listFiles()?.sumOf { it.length() } ?: 0L)
            }
        }
        return size.get()
    }

    private fun trim() {
        synchronized(lock) {
            val files = dir?.listFiles()?.sortedBy { it.lastModified() } ?: return
            var total = files.sumOf { it.length() }
            for (f in files) {
                if (total <= TRIM_TO_BYTES) break
                val len = f.length()
                if (f.delete()) total -= len
            }
            size.set(total)
        }
    }

    fun clear() {
        synchronized(lock) {
            dir?.listFiles()?.forEach { it.delete() }
            size.set(0)
        }
    }
}
