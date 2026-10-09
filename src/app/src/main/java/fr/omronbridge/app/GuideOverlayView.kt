package fr.omronbridge.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * Purely visual live-camera guide. Recognition still runs on the full frame.
 * The user only needs to keep the LCD reasonably large and frontal.
 */
class GuideOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 2.5f
    }
    private val shadePaint = Paint().apply {
        color = 0x44000000
        style = Paint.Style.FILL
    }
    private val rowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x99FFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density
    }

    private fun guideRect(): RectF {
        val margin = resources.displayMetrics.density * 18f
        val maxWidth = width - 2f * margin
        val maxHeight = height - 2f * margin
        val guideWidth = min(maxWidth * 0.88f, maxHeight * (2f / 3f))
        val guideHeight = guideWidth * 1.5f
        val left = (width - guideWidth) / 2f
        val top = (height - guideHeight) / 2f
        return RectF(left, top, left + guideWidth, top + guideHeight)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val r = guideRect()
        canvas.drawRect(0f, 0f, width.toFloat(), r.top, shadePaint)
        canvas.drawRect(0f, r.bottom, width.toFloat(), height.toFloat(), shadePaint)
        canvas.drawRect(0f, r.top, r.left, r.bottom, shadePaint)
        canvas.drawRect(r.right, r.top, width.toFloat(), r.bottom, shadePaint)
        canvas.drawRoundRect(r, 14f, 14f, borderPaint)

        // Approximate locations of the three display rows; guidance only.
        for (fraction in floatArrayOf(0.35f, 0.62f, 0.84f)) {
            val y = r.top + fraction * r.height()
            canvas.drawLine(r.left + 0.66f * r.width(), y, r.right - 0.04f * r.width(), y, rowPaint)
        }
    }
}
