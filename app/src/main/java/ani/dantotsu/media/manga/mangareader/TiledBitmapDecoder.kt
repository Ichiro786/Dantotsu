package ani.dantotsu.media.manga.mangareader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Point
import android.graphics.Rect
import android.net.Uri
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.davemorrissey.labs.subscaleview.decoder.ImageRegionDecoder
import java.util.concurrent.atomic.AtomicLong

/**
 * SubsamplingScaleImageView region decoder over a bitmap that is already decoded (any format the
 * reader pipeline handles: BitmapFactory, libvips AVIF/JXL/HEIF, transformed/cropped pages).
 *
 * Handing SSIV the bitmap itself (ImageSource.cachedBitmap) draws it as ONE texture: a webtoon
 * strip taller than the GPU's max texture size (4096/8192 on many GPUs) then renders blank. Through
 * this decoder SSIV cuts it into tiles of at most [TILE_SIZE] px and draws those instead, keeping
 * its normal width-fit, zoom and layout behaviour.
 *
 * The source bitmap is owned by the reader's bitmap cache: it is never recycled here.
 */
class TiledBitmapDecoder : ImageRegionDecoder {

    private val lock = Any()
    private var bitmap: Bitmap? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    override fun init(context: Context, uri: Uri): Point {
        val source = take(uri) ?: throw IllegalStateException("Tiled page bitmap is gone: $uri")
        synchronized(lock) { bitmap = source }
        return Point(source.width, source.height)
    }

    override fun decodeRegion(sRect: Rect, sampleSize: Int): Bitmap {
        val source = synchronized(lock) { bitmap }
            ?.takeIf { !it.isRecycled }
            ?: throw IllegalStateException("Tiled page bitmap was released")
        val sample = sampleSize.coerceAtLeast(1)
        val width = (sRect.width() / sample).coerceAtLeast(1)
        val height = (sRect.height() / sample).coerceAtLeast(1)
        val config = SubsamplingScaleImageView.getPreferredBitmapConfig()
            ?.takeIf { it == Bitmap.Config.RGB_565 || it == Bitmap.Config.ARGB_8888 }
            ?: Bitmap.Config.ARGB_8888
        val tile = Bitmap.createBitmap(width, height, config)
        Canvas(tile).drawBitmap(source, sRect, Rect(0, 0, width, height), paint)
        return tile
    }

    override fun isReady(): Boolean = synchronized(lock) { bitmap?.isRecycled == false }

    override fun recycle() {
        synchronized(lock) { bitmap = null }
    }

    companion object {
        /** Bitmaps with a side above this are tiled (lowest common GL_MAX_TEXTURE_SIZE). */
        const val SAFE_DIMENSION = 4096

        /** Largest tile SSIV may ask for. */
        const val TILE_SIZE = 2048

        private const val SCHEME = "dantotsu-tile"
        private val seq = AtomicLong()

        /** Handed from the adapter to the decoder SSIV creates; old entries are dropped. */
        private val pending = object : LinkedHashMap<String, Bitmap>() {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean =
                size > 16
        }

        fun needsTiling(bitmap: Bitmap): Boolean =
            bitmap.width > SAFE_DIMENSION || bitmap.height > SAFE_DIMENSION

        /** Registers [bitmap] and returns the uri to give SSIV (ImageSource.uri). */
        fun register(bitmap: Bitmap): Uri {
            val key = "$SCHEME://page/${seq.incrementAndGet()}"
            synchronized(pending) { pending[key] = bitmap }
            return Uri.parse(key)
        }

        private fun take(uri: Uri): Bitmap? = synchronized(pending) { pending.remove(uri.toString()) }
    }
}
