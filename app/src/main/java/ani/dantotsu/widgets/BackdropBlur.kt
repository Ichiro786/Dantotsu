package ani.dantotsu.widgets

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import androidx.annotation.RequiresApi
import androidx.core.graphics.ColorUtils
import kotlin.math.min
import kotlin.random.Random

/**
 * Frosted "Pixel style" backdrop blur for a floating bar, without copying pixels.
 *
 * [BackdropSourceLayout] wraps the content that scrolls behind the bar and, on API 31+,
 * records itself into its own [RenderNode] (then draws that node normally, so it looks and
 * behaves exactly as before). [BackdropBlurDrawable] is the bar's background: it re-draws only
 * the region of that node under the bar into a small RenderNode with a GPU blur
 * [RenderEffect], clipped to the bar's rounded shape, under a translucent surface tint.
 * Everything stays on the RenderThread (no bitmaps, no readback). The bar is re-recorded only
 * when something in the source invalidates (scroll, animation), so a still screen costs nothing
 * and scrolling costs one small blurred layer per frame.
 */
class BackdropSourceLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    /** Last recording of this layout's content (API 31+, hardware canvas only). */
    internal var contentNode: Any? = null
        private set

    /** Set by [BackdropBlurDrawable.attach]; recording only happens while something blurs it. */
    internal var recording = false

    /** Views showing a blur of this content; redrawn when anything in here changes. */
    internal val blurHosts = ArrayList<View>(1)

    override fun onDescendantInvalidated(child: View, target: View) {
        super.onDescendantInvalidated(child, target)
        // Scrolling, animations and property changes inside the content all come through here
        // (hardware-accelerated invalidation). Nothing changes -> no extra frames.
        for (i in blurHosts.indices) {
            val host = blurHosts[i]
            if (host.isShown) host.invalidate()
        }
    }

    // Recording MUST happen in dispatchDraw, not draw(): a ViewGroup without a background has
    // WILL_NOT_DRAW set, so View.updateDisplayListIfDirty takes the PFLAG_SKIP_DRAW fast path and
    // calls dispatchDraw() directly; an override of draw() is never reached and nothing is ever
    // recorded (the v1/v2 bug: the pill always fell back to the solid colour).
    override fun dispatchDraw(canvas: Canvas) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && recording && canvas.isHardwareAccelerated) {
            dispatchDrawRecorded(canvas)
        } else {
            super.dispatchDraw(canvas)
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun dispatchDrawRecorded(canvas: Canvas) {
        val first = contentNode == null
        val node = (contentNode as? RenderNode) ?: RenderNode("BackdropSource").also { contentNode = it }
        node.setPosition(0, 0, width, height)
        val rc = node.beginRecording(width, height)
        try {
            super.dispatchDraw(rc)
        } finally {
            node.endRecording()
        }
        canvas.drawRenderNode(node)
        // A bar drawn before this first recording showed the solid fallback; redraw it now.
        if (first) for (i in blurHosts.indices) blurHosts[i].postInvalidateOnAnimation()
    }
}

@RequiresApi(Build.VERSION_CODES.S)
class BackdropBlurDrawable private constructor(
    private val host: View,
    private val source: BackdropSourceLayout,
    private val baseColor: Int,
    tintColor: Int,
    strokeColor: Int,
    private val strokeWidthPx: Float,
    private val cornerRadiusPx: Float,
    blurRadiusPx: Float,
    isDark: Boolean,
) : Drawable() {

    private val blurNode = RenderNode("BackdropBlur").apply {
        // Blur first, then boost saturation (and nudge brightness toward the surface) so cover
        // colours glow through the glass instead of turning into grey mush.
        val blur = RenderEffect.createBlurEffect(blurRadiusPx, blurRadiusPx, Shader.TileMode.CLAMP)
        val vibrancy = RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(vibrancyMatrix(isDark)))
        setRenderEffect(RenderEffect.createChainEffect(vibrancy, blur))
    }
    private val tintAlpha = Color.alpha(tintColor)
    private val strokeAlpha = Color.alpha(strokeColor)
    private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = tintColor }
    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = baseColor }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokeWidthPx
        color = strokeColor
    }
    private val grainPaint = Paint().apply {
        shader = BitmapShader(grainTile(isDark), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }
    private var drawableAlpha = 255
    private var fallbackDraws = 0
    private val rect = RectF()
    private val strokeRect = RectF()
    private val path = Path()
    private val hostLoc = IntArray(2)
    private val sourceLoc = IntArray(2)

    private fun radius(): Float = min(cornerRadiusPx, min(bounds.width(), bounds.height()) / 2f)

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        rect.set(b)
        val r = radius()
        val content = source.contentNode as? RenderNode
        if (!canvas.isHardwareAccelerated || content == null || !content.hasDisplayList()) {
            // Not recorded yet (first frame) or software canvas: the solid bar, as before.
            canvas.drawRoundRect(rect, r, r, basePaint)
            if (canvas.isHardwareAccelerated && ++fallbackDraws == 30) {
                // Safety net: the source should have recorded long before this.
                android.util.Log.w("BackdropBlur", "backdrop source never recorded; blur inactive")
            }
            return
        }
        // Where the bar sits over the source, translations and animations included.
        host.getLocationInWindow(hostLoc)
        source.getLocationInWindow(sourceLoc)
        val dx = (hostLoc[0] - sourceLoc[0] + b.left).toFloat()
        val dy = (hostLoc[1] - sourceLoc[1] + b.top).toFloat()

        blurNode.setPosition(0, 0, b.width(), b.height())
        val rc = blurNode.beginRecording(b.width(), b.height())
        try {
            // Opaque base so the result never shows the bar's own shadow through it.
            rc.drawColor(baseColor or 0xFF000000.toInt())
            rc.translate(-dx, -dy)
            rc.drawRenderNode(content)
        } finally {
            blurNode.endRecording()
        }

        path.reset()
        path.addRoundRect(rect, r, r, Path.Direction.CW)
        val save = canvas.save()
        canvas.clipPath(path)
        canvas.translate(b.left.toFloat(), b.top.toFloat())
        canvas.drawRenderNode(blurNode)
        canvas.restoreToCount(save)
        tintPaint.alpha = tintAlpha * drawableAlpha / 255
        canvas.drawRoundRect(rect, r, r, tintPaint)
        // Faint grain (tiny tiled bitmap, one draw) breaks up banding in the smooth blur.
        grainPaint.alpha = drawableAlpha
        canvas.drawRoundRect(rect, r, r, grainPaint)
        // 1dp hairline just inside the outline: the glass edge.
        val inset = strokeWidthPx / 2f
        strokeRect.set(rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset)
        val sr = (r - inset).coerceAtLeast(0f)
        strokePaint.alpha = strokeAlpha * drawableAlpha / 255
        canvas.drawRoundRect(strokeRect, sr, sr, strokePaint)
    }

    override fun getOutline(outline: Outline) {
        outline.setRoundRect(bounds, radius())
        outline.alpha = 1f
    }

    override fun setAlpha(alpha: Int) {
        // Scales the glass layers; never replaces the tint's own translucency.
        drawableAlpha = alpha.coerceIn(0, 255)
        invalidateSelf()
    }

    override fun getAlpha(): Int = drawableAlpha

    override fun setColorFilter(colorFilter: ColorFilter?) {
        tintPaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {
        private const val SATURATION = 1.7f
        private const val GRAIN_SIZE = 64
        private var grainDark: Bitmap? = null
        private var grainLight: Bitmap? = null

        /** Saturation boost, then a gentle pull toward dark/light so labels stay readable. */
        private fun vibrancyMatrix(isDark: Boolean): ColorMatrix {
            val m = ColorMatrix().apply { setSaturation(SATURATION) }
            val scale = if (isDark) 0.82f else 0.88f
            val lift = if (isDark) 0f else 255f * 0.12f
            m.postConcat(
                ColorMatrix(
                    floatArrayOf(
                        scale, 0f, 0f, 0f, lift,
                        0f, scale, 0f, 0f, lift,
                        0f, 0f, scale, 0f, lift,
                        0f, 0f, 0f, 1f, 0f,
                    )
                )
            )
            return m
        }

        /** 64x64 random-alpha noise tile, generated once per process (16 KB). */
        private fun grainTile(isDark: Boolean): Bitmap {
            (if (isDark) grainDark else grainLight)?.let { return it }
            val rnd = Random(0x6A1DE041)
            val rgb = if (isDark) 0xFFFFFF else 0x000000
            val maxAlpha = if (isDark) 10 else 8
            val px = IntArray(GRAIN_SIZE * GRAIN_SIZE) { (rnd.nextInt(maxAlpha + 1) shl 24) or rgb }
            val bmp = Bitmap.createBitmap(px, GRAIN_SIZE, GRAIN_SIZE, Bitmap.Config.ARGB_8888)
            if (isDark) grainDark = bmp else grainLight = bmp
            return bmp
        }

        /**
         * Gives [bar] a frosted, blurred backdrop of [source] (API 31+; a no-op below, where the
         * bar keeps its current background). [surfaceColor] is the theme's surface container
         * colour, so dynamic/monochrome palettes and light/dark apply; [solidColor] is the old
         * opaque background, used until the first frame is recorded.
         */
        fun attach(
            bar: View,
            source: BackdropSourceLayout,
            surfaceColor: Int,
            solidColor: Int,
            isDark: Boolean,
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
            val density = bar.resources.displayMetrics.density
            val tint = ColorUtils.setAlphaComponent(surfaceColor, if (isDark) 0x66 else 0x8C)
            val stroke = if (isDark) 0x29FFFFFF else 0x14000000
            val drawable = BackdropBlurDrawable(
                host = bar,
                source = source,
                baseColor = solidColor,
                tintColor = tint,
                strokeColor = stroke,
                strokeWidthPx = 1f * density,
                cornerRadiusPx = 40f * density,
                blurRadiusPx = 34f * density,
                isDark = isDark,
            )
            source.recording = true
            if (bar !in source.blurHosts) source.blurHosts += bar
            bar.backgroundTintList = null
            bar.background = drawable
            source.invalidate()
        }
    }
}
