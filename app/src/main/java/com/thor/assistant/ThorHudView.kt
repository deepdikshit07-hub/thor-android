package com.thor.assistant

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.min

/**
 * Premium THOR Arc Reactor: restrained champagne-gold and brushed-silver detailing.
 * The slow orbit motion is decorative and intentionally subtle.
 */
class ThorHudView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var phase = 0f
    private val animator = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 18000L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            phase = it.animatedValue as Float
            invalidate()
        }
    }

    init {
        setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        contentDescription = "THOR premium Arc Reactor emblem"
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
        val cy = height / 2f - 2f * density
        val radius = min(width * 0.34f, height * 0.43f).coerceAtLeast(1f)
        val gold = 0xFFD7B879.toInt()
        val paleGold = 0xFFF2DCA8.toInt()
        val silver = 0xFFE0E3E8.toInt()
        val darkSilver = 0xFF727983.toInt()

        // Soft warm reflection, not a neon glow.
        paint.style = Paint.Style.FILL
        paint.shader = RadialGradient(
            cx, cy, radius * 1.35f,
            intArrayOf(0x33D8B978, 0x14B9A16C, 0x00101010),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, radius * 1.35f, paint)
        paint.shader = null

        // Outer polished housing.
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = 7f * density
        paint.color = darkSilver
        canvas.drawCircle(cx, cy, radius * 1.02f, paint)
        paint.strokeWidth = 3.2f * density
        paint.color = silver
        canvas.drawCircle(cx, cy, radius * 1.02f, paint)
        paint.strokeWidth = 1.2f * density
        paint.color = 0xFF8D7449.toInt()
        canvas.drawCircle(cx, cy, radius * 1.10f, paint)

        // Twelve restrained metallic segments around the core.
        val ring = RectF(cx - radius * 0.91f, cy - radius * 0.91f,
            cx + radius * 0.91f, cy + radius * 0.91f)
        for (index in 0 until 12) {
            paint.strokeWidth = if (index % 3 == 0) 7f * density else 4.5f * density
            paint.color = if (index % 3 == 0) paleGold else silver
            canvas.drawArc(ring, index * 30f + 2f, 19f, false, paint)
        }

        // Fine inner gold and silver rings.
        paint.strokeWidth = 2.2f * density
        paint.color = gold
        canvas.drawCircle(cx, cy, radius * 0.76f, paint)
        paint.strokeWidth = 1.5f * density
        paint.color = 0xFFBFC3CA.toInt()
        canvas.drawCircle(cx, cy, radius * 0.68f, paint)

        // Dark enamel center with a soft metallic edge.
        paint.style = Paint.Style.FILL
        paint.shader = RadialGradient(
            cx - radius * 0.12f, cy - radius * 0.18f, radius * 0.72f,
            intArrayOf(0xFF34373D.toInt(), 0xFF17191D.toInt(), 0xFF090A0C.toInt()),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, radius * 0.63f, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f * density
        paint.color = paleGold
        canvas.drawCircle(cx, cy, radius * 0.63f, paint)

        // Thin orbital rings, moving slowly around the emblem.
        val orbit = RectF(cx - radius * 1.28f, cy - radius * 0.43f,
            cx + radius * 1.28f, cy + radius * 0.43f)
        canvas.save()
        canvas.rotate(phase * 0.22f, cx, cy)
        paint.strokeWidth = 1.7f * density
        paint.color = 0x99D9BD83.toInt()
        canvas.drawOval(orbit, paint)
        canvas.restore()

        // THOR wordmark.
        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = android.graphics.Typeface.create(
            "sans-serif", android.graphics.Typeface.BOLD
        )
        paint.textSize = radius * 0.27f
        paint.color = silver
        paint.setShadowLayer(2.5f * density, 0f, 1f * density, 0x887D694A.toInt())
        canvas.drawText("THOR", cx, cy + radius * 0.09f, paint)
        paint.clearShadowLayer()

        // Small, understated core marker.
        paint.color = gold
        canvas.drawCircle(cx, cy + radius * 0.28f, 2.2f * density, paint)
    }
}
