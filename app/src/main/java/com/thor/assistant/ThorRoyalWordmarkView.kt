package com.thor.assistant

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View
import kotlin.math.min

/**
 * Compact, scalable Royal Chrome THOR wordmark for the Home header.
 * Drawn natively so it stays crisp on different Android screen densities.
 */
class ThorRoyalWordmarkView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("serif", Typeface.BOLD)
        style = Paint.Style.FILL
        setShadowLayer(5f * density, 0f, 2f * density, Color.rgb(0, 110, 255))
    }
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.1f * density
        color = Color.rgb(25, 75, 125)
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.2f * density
        color = Color.rgb(64, 190, 255)
        setShadowLayer(7f * density, 0f, 0f, Color.BLUE)
    }
    private val innerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = Color.rgb(205, 238, 255)
    }
    private val bluePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.rgb(55, 185, 255)
        setShadowLayer(6f * density, 0f, 0f, Color.BLUE)
    }
    private val crownPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        setShadowLayer(4f * density, 0f, 1f * density, Color.BLUE)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val size = min(48f * density, w / 5.8f)
        textPaint.textSize = size
        val letters = "THOR"
        val gap = size * 0.035f
        val widths = letters.map { textPaint.measureText(it.toString()) }
        val total = widths.sum() + gap * (letters.length - 1)
        var x = (w - total) / 2f
        val baseline = h * 0.78f
        var oCenter = w / 2f

        textPaint.shader = LinearGradient(
            0f, baseline - size, 0f, baseline + size * 0.08f,
            intArrayOf(Color.WHITE, Color.rgb(184, 199, 218), Color.rgb(91, 111, 139)),
            null, Shader.TileMode.CLAMP
        )
        letters.forEachIndexed { index, letter ->
            val advance = widths[index]
            val center = x + advance / 2f
            canvas.drawText(letter.toString(), center, baseline, textPaint)
            canvas.drawText(letter.toString(), center, baseline, outlinePaint)
            if (letter == 'O') oCenter = center
            x += advance + gap
        }
        textPaint.shader = null
        textPaint.clearShadowLayer()

        val reactorY = baseline - size * 0.37f
        val reactorRadius = size * 0.29f
        canvas.drawCircle(oCenter, reactorY, reactorRadius, ringPaint)
        canvas.drawCircle(oCenter, reactorY, reactorRadius * 0.78f, innerRingPaint)
        canvas.drawCircle(oCenter, reactorY, reactorRadius * 0.13f, bluePaint)

        val star = Path().apply {
            val r = reactorRadius * 0.66f
            moveTo(oCenter, reactorY - r)
            lineTo(oCenter + r * 0.16f, reactorY - r * 0.16f)
            lineTo(oCenter + r, reactorY)
            lineTo(oCenter + r * 0.16f, reactorY + r * 0.16f)
            lineTo(oCenter, reactorY + r)
            lineTo(oCenter - r * 0.16f, reactorY + r * 0.16f)
            lineTo(oCenter - r, reactorY)
            lineTo(oCenter - r * 0.16f, reactorY - r * 0.16f)
            close()
        }
        canvas.drawPath(star, bluePaint)

        // Small royal crown centered over the O; no hammer or oversized poster artwork.
        val crownWidth = size * 1.18f
        val crownBase = h * 0.34f
        val crownTop = h * 0.07f
        val crown = Path().apply {
            moveTo(oCenter - crownWidth / 2f, crownBase)
            lineTo(oCenter - crownWidth * 0.42f, crownTop + size * 0.18f)
            lineTo(oCenter - crownWidth * 0.18f, crownTop + size * 0.27f)
            lineTo(oCenter, crownTop)
            lineTo(oCenter + crownWidth * 0.18f, crownTop + size * 0.27f)
            lineTo(oCenter + crownWidth * 0.42f, crownTop + size * 0.18f)
            lineTo(oCenter + crownWidth / 2f, crownBase)
            close()
        }
        crownPaint.shader = LinearGradient(
            0f, crownTop, 0f, crownBase,
            intArrayOf(Color.WHITE, Color.rgb(174, 190, 211), Color.rgb(75, 96, 126)),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawPath(crown, crownPaint)
        crownPaint.shader = null
        canvas.drawPath(crown, outlinePaint)
        canvas.drawCircle(oCenter, crownTop + size * 0.25f, size * 0.055f, bluePaint)

        val underlineY = h * 0.93f
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(190, 30, 150, 255)
            strokeWidth = 1.2f * density
            setShadowLayer(4f * density, 0f, 0f, Color.BLUE)
        }
        canvas.drawLine(w * 0.19f, underlineY, w * 0.81f, underlineY, linePaint)
        canvas.drawCircle(w / 2f, underlineY, 2.2f * density, bluePaint)
    }
}
