package fr.omronbridge.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import kotlin.math.abs

/**
 * Headless version of the validated v1.4 photo pipeline.
 * The projective warp is intentionally identical to QuadCropView.rectifiedBitmap().
 */
object OmronImagePipeline {

    data class Result(
        val reading: OmronTemplateReader.ScreenReading,
        val quad: Array<PointF>,
        val locatorDebug: String,
    )

    fun isValidQuad(bitmap: Bitmap, points: Array<PointF>): Boolean {
        if (points.size != 4 || bitmap.width <= 0 || bitmap.height <= 0) return false
        if (polygonArea(points.toList()) < bitmap.width * bitmap.height * 0.015f) return false

        var sign = 0f
        for (i in 0..3) {
            val a = points[i]
            val c = points[(i + 1) % 4]
            val d = points[(i + 2) % 4]
            val cross = (c.x - a.x) * (d.y - c.y) - (c.y - a.y) * (d.x - c.x)
            if (abs(cross) < 1e-3f) return false
            if (sign == 0f) sign = cross else if (cross * sign < 0f) return false
        }
        return true
    }

    fun rectify(
        bitmap: Bitmap,
        points: Array<PointF>,
        outWidth: Int = OmronGuideGeometry.NORMALIZED_WIDTH,
        outHeight: Int = OmronGuideGeometry.NORMALIZED_HEIGHT,
    ): Bitmap? {
        if (!isValidQuad(bitmap, points)) return null

        val src = floatArrayOf(
            points[0].x, points[0].y,
            points[1].x, points[1].y,
            points[2].x, points[2].y,
            points[3].x, points[3].y,
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
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        Canvas(out).apply {
            drawColor(Color.WHITE)
            drawBitmap(bitmap, matrix, paint)
        }
        return out
    }

    private fun polygonArea(points: List<PointF>): Float {
        var sum = 0f
        for (i in points.indices) {
            val a = points[i]
            val b = points[(i + 1) % points.size]
            sum += a.x * b.y - b.x * a.y
        }
        return abs(sum) * 0.5f
    }
}
