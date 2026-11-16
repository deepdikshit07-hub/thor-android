package com.thor.assistant

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.min
import kotlin.math.sin

/** Lightweight Canvas-only animated energy core for the dedicated Live tab. */
class ThorLiveOrbView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var phase = 0f
    private var liveState = ThorLiveSession.State.IDLE
    private val animator = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 5200L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { phase = it.animatedValue as Float; invalidate() }
    }

    fun setLiveState(value: ThorLiveSession.State) {
        liveState = value
        invalidate()
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
        val cy = height / 2f
        val radius = min(width, height) * 0.34f
        val active = liveState == ThorLiveSession.State.LISTENING ||
            liveState == ThorLiveSession.State.SPEAKING ||
            liveState == ThorLiveSession.State.CONNECTING
        val pulse = if (active) 1f + 0.045f * sin(Math.toRadians(phase.toDouble())).toFloat() else 1f
        val cyan = 0xFF43C8FF.toInt()
        val bright = if (liveState == ThorLiveSession.State.SPEAKING) 0xFF9AEAFF.toInt() else cyan

        paint.style = Paint.Style.FILL
        paint.color = 0x222CAFFF
        canvas.drawCircle(cx, cy, radius * 1.62f * pulse, paint)
        paint.color = 0xFF091A2C.toInt()
        canvas.drawCircle(cx, cy, radius * 1.22f, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.2f * density
        paint.color = 0xFF1B5B88.toInt()
        canvas.drawCircle(cx, cy, radius * 1.14f, paint)

        for (i in 0 until 4) {
            paint.strokeWidth = (3.2f - i * 0.45f) * density
            paint.color = if (i == 0) bright else 0xFF24638C.toInt()
            val inset = i * radius * 0.13f
            val rect = RectF(cx - radius + inset, cy - radius + inset,
                cx + radius - inset, cy + radius - inset)
            canvas.drawArc(rect, phase * (if (i % 2 == 0) 1f else -0.7f) + i * 42f,
                250f, false, paint)
        }

        paint.style = Paint.Style.FILL
        paint.color = 0xFF0C2D48.toInt()
        canvas.drawCircle(cx, cy, radius * 0.70f * pulse, paint)
        paint.color = bright
        paint.alpha = if (active) 220 else 120
        canvas.drawCircle(cx, cy, radius * 0.49f * pulse, paint)
        paint.color = 0xFFE5FAFF.toInt()
        canvas.drawCircle(cx - radius * 0.10f, cy - radius * 0.12f, radius * 0.16f, paint)
        paint.alpha = 255
    }
}
