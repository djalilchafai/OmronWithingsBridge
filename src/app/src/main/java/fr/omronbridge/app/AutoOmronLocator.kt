package fr.omronbridge.app

import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.PointF
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.Closeable
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * Automatic locator for the LCD of the Omron M3.
 *
 * We deliberately do NOT ask ML Kit to read the large seven-segment values.
 * ML Kit is used only for the printed, high-contrast landmarks SYS / DIA / PULSE.
 * Their relative geometry is fixed on the M3.  From those three anchors we infer
 * the LCD position, then refine its four borders with a luminance-gradient scan.
 *
 * The returned quadrilateral is ordered TL, TR, BR, BL in bitmap coordinates.
 */
class AutoOmronLocator : Closeable {

    data class Result(
        val quad: Array<PointF>,
        val debug: String,
    )

    private data class Anchor(
        val target: String,
        val rawText: String,
        val box: Rect,
        val corners: Array<Point>?,
    ) {
        val center: PointF
            get() = PointF(box.exactCenterX(), box.exactCenterY())

        fun points(): List<PointF> {
            val c = corners
            if (c != null && c.size >= 4) {
                return c.map { PointF(it.x.toFloat(), it.y.toFloat()) }
            }
            return listOf(
                PointF(box.left.toFloat(), box.top.toFloat()),
                PointF(box.right.toFloat(), box.top.toFloat()),
                PointF(box.right.toFloat(), box.bottom.toFloat()),
                PointF(box.left.toFloat(), box.bottom.toFloat()),
            )
        }
    }

    private data class Basis(
        val x: PointF,
        val y: PointF,
    )

    private val recognizer: TextRecognizer =
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun locate(bitmap: Bitmap): Result {
        val text = recognize(bitmap)
        val anchors = collectAnchors(text)

        val sys = chooseAnchor(anchors, "SYS")
            ?: throw IllegalArgumentException("Repère SYS non trouvé")
        val dia = chooseAnchor(anchors, "DIA")
            ?: throw IllegalArgumentException("Repère DIA non trouvé")
        val pulse = chooseAnchor(anchors, "PULSE")
            ?: throw IllegalArgumentException("Repère PULSE non trouvé")

        val sysC = sys.center
        val diaC = dia.center
        val pulseC = pulse.center

        // Do not use the centres of SYS/DIA/PULSE to estimate vertical tilt:
        // the words have different widths (PULSE is much wider).  Their centres
        // therefore move horizontally even in a perfectly frontal photograph.
        // ML Kit gives the orientation of each printed word via cornerPoints;
        // average those horizontal directions and take the perpendicular.
        val xUnit = estimateHorizontalAxis(listOf(sys, dia, pulse))
        var yUnit = PointF(-xUnit.y, xUnit.x)
        if (project(pulseC, yUnit) < project(sysC, yUnit)) {
            yUnit = PointF(-yUnit.x, -yUnit.y)
        }
        val basis = Basis(xUnit, yUnit)

        val sysY = project(sysC, yUnit)
        val diaY = project(diaC, yUnit)
        val pulseY = project(pulseC, yUnit)
        val total = pulseY - sysY
        if (total < min(bitmap.width, bitmap.height) * 0.08f) {
            throw IllegalArgumentException("Repères Omron trop petits dans l'image")
        }
        val d1 = diaY - sysY
        val d2 = pulseY - diaY
        if (d1 <= 0f || d2 <= 0f) {
            throw IllegalArgumentException("Ordre SYS / DIA / PULSE incohérent")
        }
        val ratio = max(d1, d2) / min(d1, d2)
        if (ratio > 1.65f) {
            throw IllegalArgumentException("Espacement SYS / DIA / PULSE incohérent")
        }
        val pitchY = (d1 + d2) / 2f

        // Use the printed labels' right edge: unlike their centre, it remains
        // stable if ML Kit returns a slightly different word width.
        val labelRightX = median(
            listOf(
                maxProjection(sys.points(), xUnit),
                maxProjection(dia.points(), xUnit),
                maxProjection(pulse.points(), xUnit),
            ),
        )

        // Geometry calibrated from two real M3 photographs at very different
        // distances.  Values are expressed in units of the vertical spacing
        // between the printed SYS/DIA/PULSE labels, so scale cancels out.
        var left = labelRightX + 0.22f * pitchY
        var right = left + 2.52f * pitchY
        var top = sysY - 0.96f * pitchY
        var bottom = pulseY + 0.52f * pitchY

        // Refine the inferred borders from the actual LCD edge.  The search is
        // deliberately narrow: OCR landmarks decide *which* rectangle this is;
        // image gradients only make the placement more precise.
        val predicted = floatArrayOf(left, right, top, bottom)
        left = refineVertical(bitmap, basis, left, top, bottom, pitchY)
        right = refineVertical(bitmap, basis, right, top, bottom, pitchY)
        top = refineHorizontal(bitmap, basis, top, left, right, pitchY)
        bottom = refineHorizontal(bitmap, basis, bottom, left, right, pitchY)

        if (right <= left || bottom <= top) {
            throw IllegalArgumentException("Cadre LCD automatique invalide")
        }

        val quad = arrayOf(
            unproject(left, top, basis),
            unproject(right, top, basis),
            unproject(right, bottom, basis),
            unproject(left, bottom, basis),
        )

        val margin = 3f
        if (quad.any { it.x < -margin || it.y < -margin || it.x > bitmap.width + margin || it.y > bitmap.height + margin }) {
            throw IllegalArgumentException("LCD détecté hors de la photo : reprenez la photo en cadrant le tensiomètre")
        }

        val debug = buildString {
            append("anchors=")
            append("SYS(").append(sys.rawText).append(')')
            append(" DIA(").append(dia.rawText).append(')')
            append(" PULSE(").append(pulse.rawText).append(')')
            append(" pitch=").append("%.1f".format(Locale.US, pitchY))
            append(" predicted=[")
            append(predicted.joinToString(",") { "%.1f".format(Locale.US, it) })
            append("] refined=[")
            append(listOf(left, right, top, bottom).joinToString(",") { "%.1f".format(Locale.US, it) })
            append(']')
        }
        return Result(quad, debug)
    }

    private suspend fun recognize(bitmap: Bitmap): Text = suspendCoroutine { cont ->
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { cont.resume(it) }
            .addOnFailureListener { cont.resumeWithException(it) }
    }

    private fun collectAnchors(text: Text): List<Anchor> {
        val out = ArrayList<Anchor>()
        for (block in text.textBlocks) {
            for (line in block.lines) {
                val lineBox = line.boundingBox
                val lineRaw = line.text.trim()
                if (lineBox != null && lineRaw.isNotEmpty()) {
                    out += Anchor("", lineRaw, lineBox, line.cornerPoints)
                }
                for (element in line.elements) {
                    val box = element.boundingBox ?: continue
                    val raw = element.text.trim()
                    if (raw.isEmpty()) continue
                    out += Anchor("", raw, box, element.cornerPoints)
                }
            }
        }
        return out
    }

    private fun chooseAnchor(all: List<Anchor>, target: String): Anchor? {
        val ranked = all.mapNotNull { a ->
            val norm = normalize(a.rawText)
            val dist = levenshtein(norm, target)
            if (dist > 1) return@mapNotNull null
            if (norm.length !in (target.length - 1)..(target.length + 1)) return@mapNotNull null
            val exactBonus = if (norm == target) 1000000 else 0
            val area = a.box.width().coerceAtLeast(1) * a.box.height().coerceAtLeast(1)
            Triple(exactBonus + area, dist, a.copy(target = target))
        }
        return ranked
            .sortedWith(compareByDescending<Triple<Int, Int, Anchor>> { it.first }.thenBy { it.second })
            .firstOrNull()?.third
    }

    private fun normalize(s: String): String = s
        .uppercase(Locale.ROOT)
        .filter { it in 'A'..'Z' }

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in a.indices) {
            cur[0] = i + 1
            for (j in b.indices) {
                val cost = if (a[i] == b[j]) 0 else 1
                cur[j + 1] = minOf(
                    cur[j] + 1,
                    prev[j + 1] + 1,
                    prev[j] + cost,
                )
            }
            val tmp = prev
            prev = cur
            cur = tmp
        }
        return prev[b.length]
    }

    private fun estimateHorizontalAxis(anchors: List<Anchor>): PointF {
        var sx = 0f
        var sy = 0f
        var count = 0
        for (a in anchors) {
            val c = a.corners
            if (c != null && c.size >= 4) {
                // ML Kit order is TL, TR, BR, BL.  Average top and bottom edges.
                val vx = ((c[1].x - c[0].x) + (c[2].x - c[3].x)).toFloat()
                val vy = ((c[1].y - c[0].y) + (c[2].y - c[3].y)).toFloat()
                val n = hypot(vx, vy)
                if (n > 1f) {
                    var ux = vx / n
                    var uy = vy / n
                    if (ux < 0f) { ux = -ux; uy = -uy }
                    sx += ux
                    sy += uy
                    count++
                }
            }
        }
        if (count == 0) return PointF(1f, 0f)
        val n = hypot(sx, sy)
        return if (n > 1e-4f) PointF(sx / n, sy / n) else PointF(1f, 0f)
    }

    private fun project(p: PointF, axis: PointF): Float = p.x * axis.x + p.y * axis.y

    private fun maxProjection(points: List<PointF>, axis: PointF): Float =
        points.maxOf { project(it, axis) }

    private fun median(values: List<Float>): Float {
        val s = values.sorted()
        return s[s.size / 2]
    }

    private fun unproject(x: Float, y: Float, basis: Basis): PointF = PointF(
        basis.x.x * x + basis.y.x * y,
        basis.x.y * x + basis.y.y * y,
    )

    private fun refineVertical(
        bitmap: Bitmap,
        basis: Basis,
        predictedX: Float,
        topY: Float,
        bottomY: Float,
        pitch: Float,
    ): Float {
        val span = 0.14f * pitch
        val step = max(1f, 0.012f * pitch)
        val base = verticalEdgeScore(bitmap, basis, predictedX, topY, bottomY, pitch)
        var bestX = predictedX
        var bestScore = base
        var x = predictedX - span
        while (x <= predictedX + span) {
            val score = verticalEdgeScore(bitmap, basis, x, topY, bottomY, pitch)
            if (score > bestScore) {
                bestScore = score
                bestX = x
            }
            x += step
        }
        // Do not let a local texture displace the OCR-derived geometry unless
        // the edge evidence is materially stronger.
        return if (bestScore >= max(7f, base * 1.10f)) bestX else predictedX
    }

    private fun refineHorizontal(
        bitmap: Bitmap,
        basis: Basis,
        predictedY: Float,
        leftX: Float,
        rightX: Float,
        pitch: Float,
    ): Float {
        val span = 0.14f * pitch
        val step = max(1f, 0.012f * pitch)
        val base = horizontalEdgeScore(bitmap, basis, predictedY, leftX, rightX, pitch)
        var bestY = predictedY
        var bestScore = base
        var y = predictedY - span
        while (y <= predictedY + span) {
            val score = horizontalEdgeScore(bitmap, basis, y, leftX, rightX, pitch)
            if (score > bestScore) {
                bestScore = score
                bestY = y
            }
            y += step
        }
        return if (bestScore >= max(7f, base * 1.10f)) bestY else predictedY
    }

    private fun verticalEdgeScore(
        bitmap: Bitmap,
        basis: Basis,
        x: Float,
        topY: Float,
        bottomY: Float,
        pitch: Float,
    ): Float {
        val y0 = topY + 0.12f * (bottomY - topY)
        val y1 = bottomY - 0.12f * (bottomY - topY)
        val delta = max(1.5f, 0.013f * pitch)
        var sum = 0f
        var count = 0
        val n = 72
        for (i in 0 until n) {
            val t = (i + 0.5f) / n
            val y = y0 + (y1 - y0) * t
            val p1 = unproject(x - delta, y, basis)
            val p2 = unproject(x + delta, y, basis)
            val a = luminance(bitmap, p1.x, p1.y) ?: continue
            val b = luminance(bitmap, p2.x, p2.y) ?: continue
            sum += abs(a - b)
            count++
        }
        return if (count == 0) 0f else sum / count
    }

    private fun horizontalEdgeScore(
        bitmap: Bitmap,
        basis: Basis,
        y: Float,
        leftX: Float,
        rightX: Float,
        pitch: Float,
    ): Float {
        val x0 = leftX + 0.12f * (rightX - leftX)
        val x1 = rightX - 0.12f * (rightX - leftX)
        val delta = max(1.5f, 0.013f * pitch)
        var sum = 0f
        var count = 0
        val n = 72
        for (i in 0 until n) {
            val t = (i + 0.5f) / n
            val x = x0 + (x1 - x0) * t
            val p1 = unproject(x, y - delta, basis)
            val p2 = unproject(x, y + delta, basis)
            val a = luminance(bitmap, p1.x, p1.y) ?: continue
            val b = luminance(bitmap, p2.x, p2.y) ?: continue
            sum += abs(a - b)
            count++
        }
        return if (count == 0) 0f else sum / count
    }

    private fun luminance(bitmap: Bitmap, xf: Float, yf: Float): Float? {
        val x = xf.roundToInt()
        val y = yf.roundToInt()
        if (x !in 0 until bitmap.width || y !in 0 until bitmap.height) return null
        val p = bitmap.getPixel(x, y)
        val r = (p shr 16) and 0xff
        val g = (p shr 8) and 0xff
        val b = p and 0xff
        return 0.2126f * r + 0.7152f * g + 0.0722f * b
    }

    override fun close() {
        recognizer.close()
    }
}
