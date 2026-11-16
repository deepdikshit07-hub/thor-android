package com.thor.assistant

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * THOR reactor home visual.
 * Canvas-only rendering keeps the HUD light on memory and avoids a large bitmap asset.
 */
class ThorHudView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var phase = 0f
    private val animator = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 18000L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { phase = it.animatedValue as Float; invalidate() }
    }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        contentDescription = "Animated blue THOR reactor core"
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!animator.isStarted) animator.start()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    private fun fill(color: Int) {
        paint.shader = null
        paint.clearShadowLayer()
        paint.style = Paint.Style.FILL
        paint.color = color
    }

    private fun stroke(color: Int, width: Float) {
        paint.shader = null
        paint.clearShadowLayer()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = width
        paint.color = color
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height * 0.48f
        val r = min(width * 0.43f, height * 0.43f)
        if (r <= 0f) return

        // Blue atmospheric halo behind the metallic reactor.
        paint.style = Paint.Style.FILL
        paint.shader = RadialGradient(
            cx, cy, r * 1.16f,
            intArrayOf(0x5535BFFF, 0x22306DFF, Color.TRANSPARENT),
            floatArrayOf(0.35f, 0.72f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, r * 1.16f, paint)
        paint.shader = null

        // Outer dark metallic segmented housing and illuminated blue windows.
        for (i in 0 until 16) {
            val angle = i * 22.5f + phase * 0.10f - 90f
            canvas.save()
            canvas.rotate(angle, cx, cy)
            val outer = RectF(cx - r * 0.115f, cy - r * 1.02f, cx + r * 0.115f, cy - r * 0.67f)
            fill(0xFF111A2B.toInt())
            paint.setShadowLayer(5f * density, 0f, 2f * density, 0xAA075CFF.toInt())
            canvas.drawRoundRect(outer, 3f * density, 3f * density, paint)
            paint.clearShadowLayer()
            val metal = RectF(outer.left + 3f * density, outer.top + 3f * density,
                outer.right - 3f * density, outer.bottom - 4f * density)
            fill(0xFF26344D.toInt())
            canvas.drawRoundRect(metal, 2f * density, 2f * density, paint)
            val window = RectF(cx - r * 0.075f, cy - r * 0.91f, cx + r * 0.075f, cy - r * 0.73f)
            fill(0xFF48BFFF.toInt())
            paint.setShadowLayer(8f * density, 0f, 0f, 0xFF168BFF.toInt())
            canvas.drawRoundRect(window, 2f * density, 2f * density, paint)
            paint.clearShadowLayer()
            canvas.restore()
        }

        // Inner circular mechanical frame.
        fill(0xFF101B2D.toInt())
        canvas.drawCircle(cx, cy, r * 0.73f, paint)
        stroke(0xFF253C59.toInt(), 5f * density)
        canvas.drawCircle(cx, cy, r * 0.73f, paint)
        stroke(0xFF0A79D8.toInt(), 2f * density)
        canvas.drawCircle(cx, cy, r * 0.66f, paint)

        // Rotating segmented inner ring.
        for (i in 0 until 48) {
            val angle = Math.toRadians((i * 7.5f + phase * 0.35f).toDouble())
            val inner = r * 0.59f
            val outer = if (i % 3 == 0) r * 0.69f else r * 0.65f
            val x1 = cx + cos(angle).toFloat() * inner
            val y1 = cy + sin(angle).toFloat() * inner
            val x2 = cx + cos(angle).toFloat() * outer
            val y2 = cy + sin(angle).toFloat() * outer
            stroke(if (i % 3 == 0) 0xFF64D9FF.toInt() else 0xFF284D72.toInt(),
                if (i % 3 == 0) 2.2f * density else 1.2f * density)
            canvas.drawLine(x1, y1, x2, y2, paint)
        }

        // Concentric energy channels.
        for (ring in 0..3) {
            val radius = r * (0.52f - ring * 0.075f)
            stroke(if (ring == 0) 0xFF69DFFF.toInt() else 0xFF527A36.toInt(),
                (2.4f - ring * 0.25f) * density)
            val start = phase * (if (ring % 2 == 0) 0.35f else -0.28f) + ring * 38f
            canvas.drawArc(RectF(cx - radius, cy - radius, cx + radius, cy + radius),
                start, 286f, false, paint)
        }

        // Bright central reactor lens.
        fill(0xFF071A2B.toInt())
        canvas.drawCircle(cx, cy, r * 0.30f, paint)
        stroke(0xFF2B8EC8.toInt(), 4f * density)
        canvas.drawCircle(cx, cy, r * 0.30f, paint)
        paint.style = Paint.Style.FILL
        paint.shader = RadialGradient(
            cx - r * 0.035f, cy - r * 0.045f, r * 0.30f,
            intArrayOf(0xFFFFFFFF.toInt(), 0xFFBDF5FF.toInt(), 0xFF4CCBFF.toInt(), 0xFF1685E8.toInt()),
            floatArrayOf(0f, 0.38f, 0.78f, 1f), Shader.TileMode.CLAMP
        )
        paint.setShadowLayer(16f * density, 0f, 0f, 0xFF20AFFF.toInt())
        canvas.drawCircle(cx, cy, r * 0.255f, paint)
        paint.shader = null
        paint.clearShadowLayer()

        // Subtle outer rim highlights.
        stroke(0xAA3ABEFF.toInt(), 1.6f * density)
        canvas.drawCircle(cx, cy, r * 1.04f, paint)
        stroke(0x663E9BFF, 1f * density)
        canvas.drawCircle(cx, cy, r * 0.78f, paint)

        paint.textAlign = Paint.Align.CENTER
        paint.textSize = 9f * density
        paint.typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        paint.color = 0xFF8CEBFF.toInt()
        canvas.drawText("THOR REACTOR  •  ONLINE / OFFLINE", cx, height - 5f * density, paint)
    }
}
