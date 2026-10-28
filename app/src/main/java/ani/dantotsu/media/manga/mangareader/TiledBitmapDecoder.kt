package ani.dantotsu.media.manga.mangareader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Point
import android.graphics.Rect
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.davemorrissey.labs.subscaleview.decoder.ImageRegionDecoder
import java.util.concurrent.atomic.AtomicLong

/**
 * SubsamplingScaleImageView region decoder over a bitmap that is already decoded (any format the
 * reader pipeline handles: BitmapFactory, libvips AVIF/JXL/HEIF, transformed/cropped pages).
 *
 * Handing SSIV the bitmap itself (ImageSource.cachedBitmap) draws it as ONE texture: a webtoon
 * strip taller than the GPU's max texture size (4096/8192 on some GPUs) then renders blank. Through
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
        /** Used until the GPU limit is known (and if it can't be queried). */
        private const val FALLBACK_MAX_TEXTURE = 8192

        /** Largest tile SSIV may ask for. */
        const val TILE_SIZE = 2048

        private const val SCHEME = "dantotsu-tile"
        private val seq = AtomicLong()

        @Volatile
        private var maxTextureSize = 0

        @Volatile
        private var querying = false

        /**
         * The device's GL_MAX_TEXTURE_SIZE (16384 on recent Adreno/Mali): only bitmaps above it need
         * tiles. Tiling every page over 4096 px - every webtoon strip - cost a full-size region
         * copy per tile for nothing on GPUs that draw the strip in one texture.
         */
        val safeDimension: Int
            get() {
                val known = maxTextureSize
                if (known > 0) return known
                if (!querying) {
                    querying = true
                    Thread({ maxTextureSize = queryMaxTextureSize() ?: FALLBACK_MAX_TEXTURE }, "GlLimits").apply {
                        isDaemon = true
                        start()
                    }
                }
                return FALLBACK_MAX_TEXTURE
            }

        private fun queryMaxTextureSize(): Int? {
            var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
            var context: EGLContext = EGL14.EGL_NO_CONTEXT
            var surface: EGLSurface = EGL14.EGL_NO_SURFACE
            return try {
                display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
                if (display == EGL14.EGL_NO_DISPLAY) return null
                val version = IntArray(2)
                if (!EGL14.eglInitialize(display, version, 0, version, 1)) return null
                val configs = arrayOfNulls<EGLConfig>(1)
                val count = IntArray(1)
                val attribs = intArrayOf(
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                    EGL14.EGL_NONE
                )
                if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, count, 0) || count[0] == 0) return null
                val config = configs[0] ?: return null
                context = EGL14.eglCreateContext(
                    display, config, EGL14.EGL_NO_CONTEXT,
                    intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
                )
                if (context == EGL14.EGL_NO_CONTEXT) return null
                surface = EGL14.eglCreatePbufferSurface(
                    display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0
                )
                if (surface == EGL14.EGL_NO_SURFACE) return null
                if (!EGL14.eglMakeCurrent(display, surface, surface, context)) return null
                val out = IntArray(1)
                GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, out, 0)
                out[0].takeIf { it >= 2048 }
            } catch (e: Throwable) {
                null
            } finally {
                try {
                    if (display != EGL14.EGL_NO_DISPLAY) {
                        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                        if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
                        if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                        // No eglTerminate: the display is shared with the app's renderers.
                    }
                } catch (_: Throwable) {
                }
            }
        }

        /**
         * Handed from the adapter to the decoder SSIV creates. Access-ordered and small: an entry is
         * normally taken within a frame; anything left behind (a recycled view) must not pin
         * full-size page bitmaps.
         */
        private val pending = object : LinkedHashMap<String, Bitmap>(8, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean =
                size > 4
        }

        fun needsTiling(bitmap: Bitmap): Boolean {
            val limit = safeDimension
            return bitmap.width > limit || bitmap.height > limit
        }

        /** Registers [bitmap] and returns the uri to give SSIV (ImageSource.uri). */
        fun register(bitmap: Bitmap): Uri {
            val key = "$SCHEME://page/${seq.incrementAndGet()}"
            synchronized(pending) { pending[key] = bitmap }
            return Uri.parse(key)
        }

        private fun take(uri: Uri): Bitmap? = synchronized(pending) { pending.remove(uri.toString()) }
    }
}
