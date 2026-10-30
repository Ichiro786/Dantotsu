package ani.dantotsu.widgets

import android.content.Context
import android.graphics.Canvas
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

    override fun draw(canvas: Canvas) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && recording && canvas.isHardwareAccelerated) {
            drawRecorded(canvas)
        } else {
            super.draw(canvas)
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun drawRecorded(canvas: Canvas) {
        val node = (contentNode as? RenderNode) ?: RenderNode("BackdropSource").also { contentNode = it }
        node.setPosition(0, 0, width, height)
        val rc = node.beginRecording(width, height)
        try {
            super.draw(rc)
        } finally {
            node.endRecording()
        }
        canvas.drawRenderNode(node)
    }
}

@RequiresApi(Build.VERSION_CODES.S)
class BackdropBlurDrawable private constructor(
    private val host: View,
    private val source: BackdropSourceLayout,
    private val baseColor: Int,
    tintColor: Int,
    private val cornerRadiusPx: Float,
    blurRadiusPx: Float,
) : Drawable() {

    private val blurNode = RenderNode("BackdropBlur").apply {
        setRenderEffect(RenderEffect.createBlurEffect(blurRadiusPx, blurRadiusPx, Shader.TileMode.CLAMP))
    }
    private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = tintColor }
    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = baseColor }
    private val rect = RectF()
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
        canvas.drawRoundRect(rect, r, r, tintPaint)
    }

    override fun getOutline(outline: Outline) {
        outline.setRoundRect(bounds, radius())
        outline.alpha = 1f
    }

    override fun setAlpha(alpha: Int) {
        tintPaint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        tintPaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {
        /**
         * Gives [bar] a blurred backdrop of [source] (API 31+; a no-op below, where the bar keeps
         * its current background). [surfaceColor] is the theme's surface container colour, so
         * dynamic/monochrome palettes and light/dark apply; [solidColor] is the old opaque
         * background, used until the first frame is recorded.
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
            val tint = ColorUtils.setAlphaComponent(surfaceColor, if (isDark) 0xA6 else 0xB8)
            val drawable = BackdropBlurDrawable(
                host = bar,
                source = source,
                baseColor = solidColor,
                tintColor = tint,
                cornerRadiusPx = 40f * density,
                blurRadiusPx = 22f * density,
            )
            source.recording = true
            if (bar !in source.blurHosts) source.blurHosts += bar
            bar.background = drawable
            source.invalidate()
        }
    }
}
