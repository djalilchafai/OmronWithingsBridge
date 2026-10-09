package fr.omronbridge.app

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Omron M3 reader based on the geometry of the real LCD segments.
 *
 * The camera guide normalizes the LCD to 600x900.  SYS/DIA/PULSE therefore
 * live on fixed grids with a known digit pitch.  We first use the existing
 * colour-local normalization to reject coloured reflections.  Recognition is
 * then template based: each candidate digit is compared with the seven real
 * segment positions using both filled-segment occupancy and contour distance.
 *
 * No general-purpose OCR and no free component segmentation is involved.
 */
object OmronTemplateReader {

    enum class Kind { SYS, DIA, PULSE }

    data class RowReading(
        val value: Int?,
        val confidence: Double,
        val debug: String,
    )

    data class ScreenReading(
        val sys: RowReading,
        val dia: RowReading,
        val pulse: RowReading,
    ) {
        val complete: Boolean
            get() = sys.value != null && dia.value != null && pulse.value != null
    }

    private data class Pt(val x: Int, val y: Int)
    private data class Polygon(val points: List<Pt>)
    private data class SegmentSamples(val interior: List<Pt>, val outline: List<Pt>)

    private data class Geometry(
        val kind: Kind,
        val rowRect: Rect,
        val rights: IntArray,
        val top: Int,
        val middle: Int,
        val bottom: Int,
        val horizontalLength: Int,
        val thickness: Int,
        val searchX: IntRange,
        val searchY: IntRange,
        val searchStep: Int,
        val sampleStep: Int,
    )

    private data class SegmentStats(val occupancy: Double, val contourPenalty: Double)
    private data class CellChoice(
        val digit: Int?,
        val cost: Double,
        val secondCost: Double,
    )

    private data class SearchResult(
        val dx: Int,
        val dy: Int,
        val cells: List<CellChoice>,
        val totalCost: Double,
    )

    // Normalized LCD geometry calibrated from both reference photographs.
    private val sysGeometry = Geometry(
        kind = Kind.SYS,
        rowRect = Rect(100, 190, 520, 455), // 420 x 265
        rights = intArrayOf(59, 209, 359),
        top = 30,
        middle = 132,
        bottom = 234,
        horizontalLength = 96,
        thickness = 26,
        searchX = -50..40,
        searchY = -20..20,
        searchStep = 2,
        sampleStep = 3,
    )

    private val diaGeometry = Geometry(
        kind = Kind.DIA,
        rowRect = Rect(100, 450, 520, 720), // same 3-cell big-digit grid
        rights = intArrayOf(59, 209, 359),
        top = 30,
        middle = 132,
        bottom = 234,
        horizontalLength = 96,
        thickness = 26,
        searchX = -50..40,
        searchY = -20..20,
        searchStep = 2,
        sampleStep = 3,
    )

    private val pulseGeometry = Geometry(
        kind = Kind.PULSE,
        rowRect = Rect(170, 705, 520, 900), // 350 x 195, 3 smaller cells
        rights = intArrayOf(99, 195, 291),
        top = 42,
        middle = 108,
        bottom = 174,
        horizontalLength = 62,
        thickness = 17,
        searchX = -42..34,
        searchY = -26..18,
        searchStep = 2,
        sampleStep = 2,
    )

    private val geometries = mapOf(
        Kind.SYS to sysGeometry,
        Kind.DIA to diaGeometry,
        Kind.PULSE to pulseGeometry,
    )

    // a=top, b=upper-right, c=lower-right, d=bottom,
    // e=lower-left, f=upper-left, g=middle.
    // Omron's 7 deliberately includes f (the extra upper-left vertical bar).
    private val digitMasks = intArrayOf(
        bits('a', 'b', 'c', 'd', 'e', 'f'),          // 0
        bits('b', 'c'),                              // 1
        bits('a', 'b', 'd', 'e', 'g'),               // 2
        bits('a', 'b', 'c', 'd', 'g'),               // 3
        bits('b', 'c', 'f', 'g'),                    // 4
        bits('a', 'c', 'd', 'f', 'g'),               // 5
        bits('a', 'c', 'd', 'e', 'f', 'g'),          // 6
        bits('a', 'b', 'c', 'f'),                    // 7 Omron variant
        bits('a', 'b', 'c', 'd', 'e', 'f', 'g'),     // 8
        bits('a', 'b', 'c', 'd', 'f', 'g'),          // 9
    )

    private val sampleCache: Map<Kind, Array<SegmentSamples>> = geometries.mapValues { (_, g) ->
        buildSegmentSamples(g)
    }

    fun readScreen(source: Bitmap): ScreenReading {
        val normalized = if (
            source.width == OmronGuideGeometry.NORMALIZED_WIDTH &&
            source.height == OmronGuideGeometry.NORMALIZED_HEIGHT
        ) {
            source
        } else {
            Bitmap.createScaledBitmap(
                source,
                OmronGuideGeometry.NORMALIZED_WIDTH,
                OmronGuideGeometry.NORMALIZED_HEIGHT,
                true,
            )
        }

        try {
            return ScreenReading(
                sys = readNormalizedRow(normalized, sysGeometry),
                dia = readNormalizedRow(normalized, diaGeometry),
                pulse = readNormalizedRow(normalized, pulseGeometry),
            )
        } finally {
            if (normalized !== source) normalized.recycle()
        }
    }

    private fun readNormalizedRow(screen: Bitmap, geometry: Geometry): RowReading {
        val r = geometry.rowRect
        val row = Bitmap.createBitmap(screen, r.left, r.top, r.width(), r.height())
        val binary = LcdOcrPreprocessor.robustRgbBinary(row, radiusFactor = 1.0, thresholdBias = 0)
        row.recycle()
        try {
            return readBinary(binary, geometry)
        } finally {
            binary.recycle()
        }
    }

    private fun readBinary(binary: Bitmap, geometry: Geometry): RowReading {
        val w = binary.width
        val h = binary.height
        val pixels = IntArray(w * h)
        binary.getPixels(pixels, 0, w, 0, 0, w, h)
        val foreground = BooleanArray(w * h) { i ->
            val p = pixels[i]
            Color.red(p) + Color.green(p) + Color.blue(p) < 3 * 128
        }
        val distance = contourDistance(foreground, w, h)
        val samples = sampleCache.getValue(geometry.kind)

        var best: SearchResult? = null
        for (dx in geometry.searchX step geometry.searchStep) {
            for (dy in geometry.searchY step geometry.searchStep) {
                val statsByCell = Array(3) { cell ->
                    Array(7) { seg ->
                        segmentStats(
                            foreground = foreground,
                            distance = distance,
                            w = w,
                            h = h,
                            samples = samples[seg],
                            baseRight = geometry.rights[cell],
                            dx = dx,
                            dy = dy,
                        )
                    }
                }

                // The Omron display is right aligned and suppresses leading
                // zeroes.  A field therefore has exactly two or three visible
                // digits.  This known grid is part of the optical model and
                // prevents reflections from becoming spurious extra digits.
                for (digitCount in 2..3) {
                    val choices = ArrayList<CellChoice>(3)
                    for (cell in 0..2) {
                        val choice = if (digitCount == 2 && cell == 0) {
                            blankChoice(statsByCell[cell])
                        } else {
                            val firstSignificant =
                                (digitCount == 2 && cell == 1) ||
                                    (digitCount == 3 && cell == 0)
                            digitChoice(statsByCell[cell], forbidZero = firstSignificant)
                        }
                        choices += choice
                    }

                    val value = choices
                        .mapNotNull { it.digit }
                        .fold(0) { acc, d -> acc * 10 + d }
                    if (!plausible(geometry.kind, value)) continue

                    var total = choices.sumOf { it.cost }
                    total += 0.00025 * (dx * dx + dy * dy)
                    if (best == null || total < best!!.totalCost) {
                        best = SearchResult(dx, dy, choices, total)
                    }
                }
            }
        }

        val result = best ?: return RowReading(null, 0.0, "no-search-result")
        val digits = result.cells.map { it.digit }
        val nonBlank = digits.dropWhile { it == null }
        if (nonBlank.size !in 2..3 || nonBlank.any { it == null }) {
            return RowReading(
                null,
                0.0,
                "${geometry.kind} unresolved dx=${result.dx} dy=${result.dy} digits=$digits cost=${"%.3f".format(result.totalCost)}",
            )
        }

        val value = nonBlank.filterNotNull().fold(0) { acc, d -> acc * 10 + d }
        val digitChoices = result.cells.filter { it.digit != null }
        val confidence = digitChoices.map { choice ->
            val margin = ((choice.secondCost - choice.cost) / 0.25).coerceIn(0.0, 1.0)
            val absolute = ((0.80 - choice.cost) / 0.80).coerceIn(0.0, 1.0)
            0.55 * margin + 0.45 * absolute
        }.average().coerceIn(0.0, 1.0)

        val dbg = buildString {
            append(geometry.kind).append('=').append(value)
            append(" conf=").append("%.3f".format(confidence))
            append(" shift=").append(result.dx).append(',').append(result.dy)
            append(" cost=").append("%.3f".format(result.totalCost))
            append(" cells=")
            append(result.cells.joinToString(prefix = "[", postfix = "]") { c ->
                "${c.digit ?: "_"}:${"%.2f".format(c.cost)}/${"%.2f".format(c.secondCost)}"
            })
        }
        return RowReading(value, confidence, dbg)
    }

    private fun blankChoice(stats: Array<SegmentStats>): CellChoice {
        val meanOccupancy = stats.map { it.occupancy }.average()
        val cost = min(1.2, 2.0 * meanOccupancy)
        return CellChoice(null, cost, 1.2)
    }

    private fun digitChoice(
        stats: Array<SegmentStats>,
        forbidZero: Boolean,
    ): CellChoice {
        var bestDigit = -1
        var bestCost = Double.POSITIVE_INFINITY
        var second = Double.POSITIVE_INFINITY

        for (digit in 0..9) {
            if (forbidZero && digit == 0) continue
            val cost = digitCost(stats, digit)
            if (cost < bestCost) {
                second = bestCost
                bestCost = cost
                bestDigit = digit
            } else if (cost < second) {
                second = cost
            }
        }
        if (!second.isFinite()) second = 1.2
        return CellChoice(bestDigit, bestCost, second)
    }

    private fun digitCost(stats: Array<SegmentStats>, digit: Int): Double {
        val mask = digitMasks[digit]
        var activeSum = 0.0
        var activeCount = 0
        var absentSum = 0.0
        var absentCount = 0
        for (seg in 0..6) {
            val active = mask and (1 shl seg) != 0
            val s = stats[seg]
            if (active) {
                activeSum += 0.70 * (1.0 - s.occupancy) + 0.30 * s.contourPenalty
                activeCount++
            } else {
                absentSum += s.occupancy
                absentCount++
            }
        }
        val activeCost = activeSum / max(1, activeCount)
        val absentCost = if (absentCount == 0) 0.0 else absentSum / absentCount
        return activeCost + 0.55 * absentCost
    }

    private fun segmentStats(
        foreground: BooleanArray,
        distance: IntArray,
        w: Int,
        h: Int,
        samples: SegmentSamples,
        baseRight: Int,
        dx: Int,
        dy: Int,
    ): SegmentStats {
        var fg = 0
        var inCount = 0
        for (p in samples.interior) {
            val x = baseRight + dx + p.x
            val y = dy + p.y
            if (x !in 0 until w || y !in 0 until h) continue
            inCount++
            if (foreground[y * w + x]) fg++
        }
        val occupancy = if (inCount == 0) 0.0 else fg.toDouble() / inCount

        val cap = 27 // 9 pixels in the 3/4 chamfer metric below
        var contourSum = 0.0
        var outlineCount = 0
        for (p in samples.outline) {
            val x = baseRight + dx + p.x
            val y = dy + p.y
            outlineCount++
            contourSum += if (x !in 0 until w || y !in 0 until h) {
                1.0
            } else {
                min(cap, distance[y * w + x]).toDouble() / cap
            }
        }
        val contourPenalty = if (outlineCount == 0) 1.0 else contourSum / outlineCount
        return SegmentStats(occupancy, contourPenalty)
    }

    /** Chamfer distance to the contour of the black foreground. */
    private fun contourDistance(foreground: BooleanArray, w: Int, h: Int): IntArray {
        val inf = 1_000_000
        val d = IntArray(w * h) { inf }
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                if (!foreground[i]) continue
                val boundary = x == 0 || y == 0 || x == w - 1 || y == h - 1 ||
                    !foreground[i - 1] || !foreground[i + 1] ||
                    !foreground[i - w] || !foreground[i + w]
                if (boundary) d[i] = 0
            }
        }

        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                var v = d[i]
                if (x > 0) v = min(v, d[i - 1] + 3)
                if (y > 0) v = min(v, d[i - w] + 3)
                if (x > 0 && y > 0) v = min(v, d[i - w - 1] + 4)
                if (x + 1 < w && y > 0) v = min(v, d[i - w + 1] + 4)
                d[i] = v
            }
        }
        for (y in h - 1 downTo 0) {
            for (x in w - 1 downTo 0) {
                val i = y * w + x
                var v = d[i]
                if (x + 1 < w) v = min(v, d[i + 1] + 3)
                if (y + 1 < h) v = min(v, d[i + w] + 3)
                if (x + 1 < w && y + 1 < h) v = min(v, d[i + w + 1] + 4)
                if (x > 0 && y + 1 < h) v = min(v, d[i + w - 1] + 4)
                d[i] = v
            }
        }
        return d
    }

    private fun buildSegmentSamples(g: Geometry): Array<SegmentSamples> {
        val polygons = segmentPolygons(g)
        return Array(7) { seg ->
            val poly = polygons[seg]
            val minX = poly.points.minOf { it.x }
            val maxX = poly.points.maxOf { it.x }
            val minY = poly.points.minOf { it.y }
            val maxY = poly.points.maxOf { it.y }
            val interior = ArrayList<Pt>()
            val inset = 2
            for (y in minY..maxY step g.sampleStep) {
                for (x in minX..maxX step g.sampleStep) {
                    if (
                        insideConvex(poly, x, y) &&
                        insideConvex(poly, x - inset, y) &&
                        insideConvex(poly, x + inset, y) &&
                        insideConvex(poly, x, y - inset) &&
                        insideConvex(poly, x, y + inset)
                    ) {
                        interior += Pt(x, y)
                    }
                }
            }

            val outline = ArrayList<Pt>()
            val points = poly.points
            val outlineStep = if (g.kind == Kind.PULSE) 3.0 else 4.0
            for (i in points.indices) {
                val a = points[i]
                val b = points[(i + 1) % points.size]
                val length = sqrt(
                    ((b.x - a.x) * (b.x - a.x) + (b.y - a.y) * (b.y - a.y)).toDouble(),
                )
                val n = max(1, (length / outlineStep).toInt())
                for (j in 0..n) {
                    val t = j.toDouble() / n
                    outline += Pt(
                        (a.x * (1.0 - t) + b.x * t).roundToInt(),
                        (a.y * (1.0 - t) + b.y * t).roundToInt(),
                    )
                }
            }
            SegmentSamples(interior.distinct(), outline.distinct())
        }
    }

    /** Polygons are relative to a cell's right-segment centre (x=0). */
    private fun segmentPolygons(g: Geometry): Array<Polygon> {
        val r = 0.0
        val thick = g.thickness.toDouble()
        val horizontalLength = g.horizontalLength.toDouble()
        val leftCenter = r - horizontalLength + thick * 0.48

        fun horizontal(cy: Int): Polygon {
            val xRight = r + thick * 0.35
            val xLeft = r - horizontalLength
            val half = thick * 0.42
            val bevel = thick * 0.40
            return Polygon(
                listOf(
                    Pt((xLeft + bevel).roundToInt(), (cy - half).roundToInt()),
                    Pt((xRight - bevel).roundToInt(), (cy - half).roundToInt()),
                    Pt(xRight.roundToInt(), cy),
                    Pt((xRight - bevel).roundToInt(), (cy + half).roundToInt()),
                    Pt((xLeft + bevel).roundToInt(), (cy + half).roundToInt()),
                    Pt(xLeft.roundToInt(), cy),
                ),
            )
        }

        fun vertical(cx: Double, y0: Double, y1: Double): Polygon {
            val half = thick * 0.42
            val bevel = thick * 0.40
            return Polygon(
                listOf(
                    Pt(cx.roundToInt(), y0.roundToInt()),
                    Pt((cx + half).roundToInt(), (y0 + bevel).roundToInt()),
                    Pt((cx + half).roundToInt(), (y1 - bevel).roundToInt()),
                    Pt(cx.roundToInt(), y1.roundToInt()),
                    Pt((cx - half).roundToInt(), (y1 - bevel).roundToInt()),
                    Pt((cx - half).roundToInt(), (y0 + bevel).roundToInt()),
                ),
            )
        }

        val upperY0 = g.top + thick * 0.25
        val upperY1 = g.middle - thick * 0.25
        val lowerY0 = g.middle + thick * 0.25
        val lowerY1 = g.bottom - thick * 0.25

        val byLetter = mapOf(
            'a' to horizontal(g.top),
            'b' to vertical(r, upperY0, upperY1),
            'c' to vertical(r, lowerY0, lowerY1),
            'd' to horizontal(g.bottom),
            'e' to vertical(leftCenter, lowerY0, lowerY1),
            'f' to vertical(leftCenter, upperY0, upperY1),
            'g' to horizontal(g.middle),
        )
        return Array(7) { i -> byLetter.getValue(('a'.code + i).toChar()) }
    }

    private fun insideConvex(poly: Polygon, x: Int, y: Int): Boolean {
        var sign = 0
        val p = poly.points
        for (i in p.indices) {
            val a = p[i]
            val b = p[(i + 1) % p.size]
            val cross = (b.x - a.x).toLong() * (y - a.y) -
                (b.y - a.y).toLong() * (x - a.x)
            if (cross == 0L) continue
            val s = if (cross > 0) 1 else -1
            if (sign == 0) sign = s else if (sign != s) return false
        }
        return true
    }

    private fun plausible(kind: Kind, value: Int): Boolean = when (kind) {
        Kind.SYS -> value in 20..300
        Kind.DIA -> value in 10..300
        Kind.PULSE -> value in 1..300
    }

    private fun bits(vararg segments: Char): Int {
        var mask = 0
        for (s in segments) mask = mask or (1 shl (s.code - 'a'.code))
        return mask
    }
}
