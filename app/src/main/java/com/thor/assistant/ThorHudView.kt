package com.thor.assistant

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * THOR's lightweight animated Arc Reactor emblem.
 * Drawn natively so the home screen needs no remote artwork or extra image assets.
 */
class ThorHudView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var phase = 0f
    private val animator = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 7200L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            phase = it.animatedValue as Float
            invalidate()
        }
    }

    init {
        setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        contentDescription = "THOR Arc Reactor emblem"
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!animator.isStarted) animator.start()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f - 3f * density
        val radius = min(width * 0.34f, height * 0.43f).coerceAtLeast(1f)

        // Diffused cyan aura behind the reactor.
        paint.style = Paint.Style.FILL
        paint.shader = RadialGradient(
            cx, cy, radius * 1.38f,
            intArrayOf(0x5534CFFF, 0x2225A9D8, 0x001078A8),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, radius * 1.38f, paint)
        paint.shader = null

        // Outer glass containment ring.
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.2f * density
        paint.color = 0x557BCDE8
        canvas.drawCircle(cx, cy, radius * 1.12f, paint)
        paint.color = 0xFF1D526A.toInt()
        paint.strokeWidth = 3f * density
        canvas.drawCircle(cx, cy, radius * 0.98f, paint)

        // Segmented rotating outer arcs.
        val outer = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
        paint.strokeWidth = 3.2f * density
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = 0xFF62DFFF.toInt()
        for (index in 0 until 4) {
            canvas.drawArc(outer, phase + index * 90f, 47f, false, paint)
        }

        // Inner counter-rotating ring.
        val innerRadius = radius * 0.77f
        val inner = RectF(cx - innerRadius, cy - innerRadius, cx + innerRadius, cy + innerRadius)
        paint.strokeWidth = 2f * density
        paint.color = 0xFFB0F3FF.toInt()
        canvas.drawArc(inner, -phase * 1.25f, 78f, false, paint)
        paint.color = 0xFF258FB8.toInt()
        canvas.drawArc(inner, 180f - phase * 1.25f, 54f, false, paint)

        // Fine radial index marks.
        paint.strokeWidth = 1.4f * density
        paint.color = 0xFF6CCCEB.toInt()
        for (index in 0 until 12) {
            val angle = Math.toRadians((index * 30f + phase * 0.12f).toDouble())
            val startR = radius * 0.84f
            val endR = radius * (if (index % 3 == 0) 0.96f else 0.91f)
            canvas.drawLine(
                cx + cos(angle).toFloat() * startR,
                cy + sin(angle).toFloat() * startR,
                cx + cos(angle).toFloat() * endR,
                cy + sin(angle).toFloat() * endR,
                paint
            )
        }

        // Hexagonal reactor housing.
        val hex = Path()
        for (index in 0 until 6) {
            val angle = Math.toRadians((index * 60f - 30f).toDouble())
            val x = cx + cos(angle).toFloat() * radius * 0.53f
            val y = cy + sin(angle).toFloat() * radius * 0.53f
            if (index == 0) hex.moveTo(x, y) else hex.lineTo(x, y)
        }
        hex.close()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f * density
        paint.color = 0xFF66DFFF.toInt()
        canvas.drawPath(hex, paint)

        // Luminous center core.
        paint.style = Paint.Style.FILL
        paint.shader = RadialGradient(
            cx, cy, radius * 0.43f,
            intArrayOf(0xFFE9FCFF.toInt(), 0xFF78E5FF.toInt(), 0xAA168AB8.toInt(), 0x001078A8),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, radius * 0.43f, paint)
        paint.shader = null
        paint.color = 0xFFECFCFF.toInt()
        paint.setShadowLayer(8f * density, 0f, 0f, 0xFF42D8FF.toInt())
        canvas.drawCircle(cx, cy, radius * 0.19f, paint)
        paint.clearShadowLayer()

        // THOR monogram in the reactor center.
        paint.color = 0xFF0B4A68.toInt()
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = android.graphics.Typeface.create("sans-serif-black", android.graphics.Typeface.BOLD)
        paint.textSize = radius * 0.23f
        canvas.drawText("T", cx, cy + radius * 0.085f, paint)

        paint.textAlign = Paint.Align.CENTER
        paint.typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        paint.textSize = 8.5f * density
        paint.color = 0xFF9BDDF3.toInt()
        canvas.drawText("ENERGY CORE  •  ACTIVE", cx, height - 2f * density, paint)
    }
}
