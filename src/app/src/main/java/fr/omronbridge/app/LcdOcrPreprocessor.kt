package fr.omronbridge.app

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.max
import kotlin.math.min

/**
 * Generic LCD preprocessing for OCR.
 *
 * A seven-segment LCD digit is approximately neutral/dark in R, G and B,
 * whereas phone/hand/lamp reflections are often coloured.  For each colour
 * channel we estimate the slowly varying local background with a box blur,
 * measure how much darker the pixel is than that background, and keep the
 * minimum of the three channel responses.  Thus a pixel has to be dark in all
 * three channels to become foreground.
 *
 * The resulting image is black text on a white background, which is much
 * closer to the images on which ordinary OCR recognisers are trained.
 */
object LcdOcrPreprocessor {

    data class Variant(val name: String, val bitmap: Bitmap)

    fun variants(source: Bitmap): List<Variant> = listOf(
        Variant("rgb-local-small", robustRgbBinary(source, radiusFactor = 0.78, thresholdBias = 1)),
        Variant("rgb-local", robustRgbBinary(source, radiusFactor = 1.00, thresholdBias = 0)),
        Variant("rgb-local-large", robustRgbBinary(source, radiusFactor = 1.30, thresholdBias = 3)),
    )

    /** Caller owns the returned bitmap. */
    fun robustRgbBinary(
        source: Bitmap,
        radiusFactor: Double = 1.0,
        thresholdBias: Int = 0,
    ): Bitmap {
        val w = source.width
        val h = source.height
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)

        val r = IntArray(w * h)
        val g = IntArray(w * h)
        val b = IntArray(w * h)
        for (i in pixels.indices) {
            val p = pixels[i]
            r[i] = Color.red(p)
            g[i] = Color.green(p)
            b[i] = Color.blue(p)
        }

        // The background varies on a scale much larger than a segment stroke.
        // Keep the window odd-ish and bounded for both small and large crops.
        val baseRadius = max(9, min(w / 22, h / 7)).coerceAtMost(45)
        val radius = (baseRadius * radiusFactor).toInt().coerceIn(7, 60)
        val ir = integral(r, w, h)
        val ig = integral(g, w, h)
        val ib = integral(b, w, h)

        val dark = IntArray(w * h)
        val hist = IntArray(256)
        for (y in 0 until h) {
            val y0 = max(0, y - radius)
            val y1 = min(h - 1, y + radius)
            for (x in 0 until w) {
                val x0 = max(0, x - radius)
                val x1 = min(w - 1, x + radius)
                val area = (x1 - x0 + 1) * (y1 - y0 + 1)
                val idx = y * w + x
                val br = rectSum(ir, w, x0, y0, x1, y1) / area
                val bg = rectSum(ig, w, x0, y0, x1, y1) / area
                val bb = rectSum(ib, w, x0, y0, x1, y1) / area
                val dr = (br - r[idx]).coerceAtLeast(0)
                val dg = (bg - g[idx]).coerceAtLeast(0)
                val db = (bb - b[idx]).coerceAtLeast(0)
                // Coloured reflections are rejected because at least one channel
                // usually fails to be darker than its local background.
                val d = min(dr, min(dg, db)).coerceIn(0, 255)
                dark[idx] = d
                hist[d]++
            }
        }

        var threshold = otsu(hist, dark.size)
        // Otsu can be too permissive on nearly uniform crops.  LCD segments on
        // our photographs are substantially darker than the local background.
        threshold = (threshold + thresholdBias).coerceIn(8, 80)

        val outPixels = IntArray(w * h) { Color.WHITE }
        for (i in dark.indices) {
            if (dark[i] >= threshold) outPixels[i] = Color.BLACK
        }

        // Remove isolated speckles by a conservative 3x3 neighbour test.  We do
        // not erode long thin components, so the digit '1' remains intact.
        val cleaned = outPixels.copyOf()
        if (w >= 3 && h >= 3) {
            for (y in 1 until h - 1) {
                for (x in 1 until w - 1) {
                    val idx = y * w + x
                    if (outPixels[idx] != Color.BLACK) continue
                    var neighbours = 0
                    for (dy in -1..1) for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        if (outPixels[(y + dy) * w + x + dx] == Color.BLACK) neighbours++
                    }
                    if (neighbours <= 1) cleaned[idx] = Color.WHITE
                }
            }
        }

        return Bitmap.createBitmap(cleaned, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun integral(values: IntArray, w: Int, h: Int): LongArray {
        // Integral image has one zero border: (w+1) x (h+1).
        val stride = w + 1
        val out = LongArray((w + 1) * (h + 1))
        for (y in 0 until h) {
            var row = 0L
            for (x in 0 until w) {
                row += values[y * w + x].toLong()
                out[(y + 1) * stride + (x + 1)] = out[y * stride + (x + 1)] + row
            }
        }
        return out
    }

    private fun rectSum(ii: LongArray, w: Int, x0: Int, y0: Int, x1: Int, y1: Int): Int {
        val stride = w + 1
        val xa = x0
        val ya = y0
        val xb = x1 + 1
        val yb = y1 + 1
        val sum = ii[yb * stride + xb] - ii[ya * stride + xb] -
            ii[yb * stride + xa] + ii[ya * stride + xa]
        return sum.toInt()
    }

    private fun otsu(hist: IntArray, count: Int): Int {
        if (count <= 0) return 16
        var sum = 0.0
        for (i in hist.indices) sum += i.toDouble() * hist[i]
        var sumBackground = 0.0
        var weightBackground = 0
        var bestVariance = -1.0
        var best = 16
        for (t in 0..255) {
            weightBackground += hist[t]
            if (weightBackground == 0) continue
            val weightForeground = count - weightBackground
            if (weightForeground == 0) break
            sumBackground += t.toDouble() * hist[t]
            val meanBackground = sumBackground / weightBackground
            val meanForeground = (sum - sumBackground) / weightForeground
            val delta = meanBackground - meanForeground
            val variance = weightBackground.toDouble() * weightForeground.toDouble() * delta * delta
            if (variance > bestVariance) {
                bestVariance = variance
                best = t
            }
        }
        return best
    }
}
