package com.thor.assistant

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.min

/** Lightweight animated HUD drawn with Canvas; no image assets or heavy UI framework. */
class ThorHudView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var phase = 0f
    private val animator = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 6000L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { phase = it.animatedValue as Float; invalidate() }
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
        val radius = min(width, height) * 0.31f
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.4f * density
        paint.color = 0xFF17465A.toInt()
        canvas.drawCircle(cx, cy, radius, paint)
        paint.color = 0xFF36CFFF.toInt()
        paint.strokeWidth = 2f * density
        canvas.drawArc(RectF(cx-radius, cy-radius, cx+radius, cy+radius), phase, 82f, false, paint)
        paint.color = 0xFF83E7FF.toInt()
        canvas.drawArc(RectF(cx-radius*0.78f, cy-radius*0.78f, cx+radius*0.78f, cy+radius*0.78f), -phase*1.3f, 48f, false, paint)
        paint.style = Paint.Style.FILL
        paint.color = 0xFF52D9FF.toInt()
        canvas.drawCircle(cx, cy, 3.5f*density, paint)
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = 10f*density
        paint.color = 0xFF8CEBFF.toInt()
        canvas.drawText("THOR CORE  •  ONLINE / OFFLINE", cx, height-8f*density, paint)
    }
}
