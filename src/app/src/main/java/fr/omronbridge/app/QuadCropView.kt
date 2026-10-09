package fr.omronbridge.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.min

/**
 * Displays the photograph and the automatically detected Omron LCD frame.
 *
 * There is intentionally no manual corner editing in the stable workflow.
 * The four points are supplied by [AutoOmronLocator], stored in normalized
 * bitmap coordinates, and projectively warped to the canonical 600x900 LCD.
 */
class QuadCropView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
    }
    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCCFFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = dp(1.4f)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dp(13f)
        setShadowLayer(dp(2f), 0f, 0f, Color.BLACK)
    }

    private var bitmap: Bitmap? = null
    private val imageRect = RectF()

    // TL, TR, BR, BL, normalized relative to the source bitmap.
    private val corners = arrayOf(
        PointF(0.30f, 0.12f),
        PointF(0.70f, 0.12f),
        PointF(0.70f, 0.88f),
        PointF(0.30f, 0.88f),
    )

    fun setBitmap(value: Bitmap?) {
        bitmap = value
        setPlaceholderFrame()
        invalidate()
    }

    /** Set the automatically detected LCD quadrilateral (TL, TR, BR, BL). */
    fun setAutoQuad(points: Array<PointF>): Boolean {
        val b = bitmap ?: return false
        if (points.size != 4 || b.width <= 0 || b.height <= 0) return false
        for (i in 0..3) {
            corners[i].set(
                (points[i].x / b.width.toFloat()).coerceIn(0f, 1f),
                (points[i].y / b.height.toFloat()).coerceIn(0f, 1f),
            )
        }
        invalidate()
        return hasValidQuad()
    }

    private fun setPlaceholderFrame() {
        val b = bitmap
        val h = 0.76f
        val rawW = if (b != null && b.width > 0) {
            h * (2f / 3f) * b.height.toFloat() / b.width.toFloat()
        } else {
            h * 2f / 3f
        }
        val w = rawW.coerceIn(0.30f, 0.90f)
        val left = (1f - w) / 2f
        val top = (1f - h) / 2f
        corners[0].set(left, top)
        corners[1].set(left + w, top)
        corners[2].set(left + w, top + h)
        corners[3].set(left, top + h)
    }

    fun hasValidQuad(): Boolean {
        val b = bitmap ?: return false
        if (b.width <= 0 || b.height <= 0) return false
        val pts = corners.map { PointF(it.x * b.width, it.y * b.height) }
        if (polygonArea(pts) < b.width * b.height * 0.015f) return false

        var sign = 0f
        for (i in 0..3) {
            val a = pts[i]
            val c = pts[(i + 1) % 4]
            val d = pts[(i + 2) % 4]
            val cross = (c.x - a.x) * (d.y - c.y) - (c.y - a.y) * (d.x - c.x)
            if (abs(cross) < 1e-3f) return false
            if (sign == 0f) sign = cross else if (cross * sign < 0f) return false
        }
        return true
    }

    /** Projectively rectify the detected LCD to the reader's canonical grid. */
    fun rectifiedBitmap(
        outWidth: Int = OmronGuideGeometry.NORMALIZED_WIDTH,
        outHeight: Int = OmronGuideGeometry.NORMALIZED_HEIGHT,
    ): Bitmap? {
        val srcBitmap = bitmap ?: return null
        if (!hasValidQuad()) return null

        val src = floatArrayOf(
            corners[0].x * srcBitmap.width, corners[0].y * srcBitmap.height,
            corners[1].x * srcBitmap.width, corners[1].y * srcBitmap.height,
            corners[2].x * srcBitmap.width, corners[2].y * srcBitmap.height,
            corners[3].x * srcBitmap.width, corners[3].y * srcBitmap.height,
        )
        val dst = floatArrayOf(
            0f, 0f,
            outWidth.toFloat(), 0f,
            outWidth.toFloat(), outHeight.toFloat(),
            0f, outHeight.toFloat(),
        )

        val matrix = Matrix()
        if (!matrix.setPolyToPoly(src, 0, dst, 0, 4)) return null

        val out = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            drawColor(Color.WHITE)
            drawBitmap(srcBitmap, matrix, bitmapPaint)
        }
        return out
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val b = bitmap ?: return
        updateImageRect(b)
        canvas.drawBitmap(b, null, imageRect, bitmapPaint)

        val p = Array(4) { i -> toView(corners[i]) }
        val path = Path().apply {
            moveTo(p[0].x, p[0].y)
            lineTo(p[1].x, p[1].y)
            lineTo(p[2].x, p[2].y)
            lineTo(p[3].x, p[3].y)
            close()
        }
        canvas.drawPath(path, borderPaint)

        drawInterpolatedGuide(canvas, p, 0.36f, "SYS")
        drawInterpolatedGuide(canvas, p, 0.65f, "DIA")
        drawInterpolatedGuide(canvas, p, 0.89f, "PULSE")
    }

    private fun drawInterpolatedGuide(canvas: Canvas, p: Array<PointF>, t: Float, label: String) {
        val left = lerp(p[0], p[3], t)
        val right = lerp(p[1], p[2], t)
        canvas.drawLine(left.x, left.y, right.x, right.y, guidePaint)
        canvas.drawText(label, left.x + dp(5f), left.y - dp(4f), labelPaint)
    }

    private fun updateImageRect(b: Bitmap) {
        if (width <= 0 || height <= 0) return
        val scale = min(width.toFloat() / b.width, height.toFloat() / b.height)
        val dw = b.width * scale
        val dh = b.height * scale
        val left = (width - dw) / 2f
        val top = (height - dh) / 2f
        imageRect.set(left, top, left + dw, top + dh)
    }

    private fun toView(p: PointF): PointF = PointF(
        imageRect.left + p.x * imageRect.width(),
        imageRect.top + p.y * imageRect.height(),
    )

    private fun lerp(a: PointF, b: PointF, t: Float) = PointF(
        a.x + (b.x - a.x) * t,
        a.y + (b.y - a.y) * t,
    )

    private fun polygonArea(points: List<PointF>): Float {
        var sum = 0f
        for (i in points.indices) {
            val a = points[i]
            val b = points[(i + 1) % points.size]
            sum += a.x * b.y - b.x * a.y
        }
        return abs(sum) * 0.5f
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}
