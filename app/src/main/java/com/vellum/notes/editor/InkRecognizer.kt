package com.vellum.notes.editor

import com.vellum.notes.model.Point
import com.vellum.notes.model.Stroke
import kotlin.math.hypot

/**
 * On-device print handwriting recognizer (no network, no downloads, no new
 * permissions). A $1-style unistroke matcher without the rotation step
 * (orientation distinguishes P/b, 6/9, M/W):
 *
 * 1. Selected strokes are grouped into characters by horizontal overlap —
 *    strokes whose x-ranges overlap belong to one multi-stroke character
 *    (A's bar, H's stems, i's dot), split on real x-gaps between letters.
 * 2. Each group is joined in stroke order, resampled, scaled and translated
 *    exactly like the built-in templates, then nearest-matched.
 * 3. A–Z and 0–9 are recognized. Lowercase letters whose print form coincides
 *    with a capital (c, o, s, u, v, w, x, z) resolve to that capital: case is
 *    unrecoverable for a single isolated shape once size is normalized away,
 *    so the engine answers deterministically instead of guessing.
 *    Anything below confidence becomes "?".
 *
 * Best with printed capitals; cursive and lookalikes (0/O, 1/I, 5/S, 8/B)
 * are inherent template-matching limits and left visible as "?" for the user
 * to fix in the text box.
 */
object InkRecognizer : HandwritingConvert.Recognizer {

    /** Normalized space (templates are authored in this grid, y down). */
    private const val NORM = 100f

    /** Resampled points per character. */
    private const val SAMPLES = 32

    /**
     * Acceptance distance (average point error in normalized units). Tuned so
     * exact and slightly perturbed template copies pass while deliberate
     * scribbles fail — see InkRecognizerTest.
     */
    private const val ACCEPT_DISTANCE = 30f

    override fun recognize(strokes: List<Stroke>): String {
        if (strokes.isEmpty()) return ""
        val groups = groupCharacters(strokes)
        if (groups.isEmpty()) return ""
        val out = StringBuilder()
        for (group in groups) {
            out.append(recognizeGroup(group))
        }
        return out.toString()
    }

    // --- character grouping ---------------------------------------------------

    private data class StrokeSpan(val stroke: Stroke, val minX: Float, val maxX: Float, val height: Float)

    private fun groupCharacters(strokes: List<Stroke>): List<List<Stroke>> {
        val spans = strokes.mapNotNull { s ->
            val pts = s.pointsPacked
            if (pts.size < 4) return@mapNotNull null
            var l = pts[0]
            var r = pts[0]
            var t = pts[1]
            var b = pts[1]
            var i = 2
            while (i + 1 < pts.size) {
                val x = pts[i]
                val y = pts[i + 1]
                if (x < l) l = x
                if (x > r) r = x
                if (y < t) t = y
                if (y > b) b = y
                i += 2
            }
            StrokeSpan(s, l, r, (b - t).coerceAtLeast(0.5f))
        }.sortedBy { it.minX }
        if (spans.isEmpty()) return emptyList()

        val medianHeight = spans.map { it.height }.sorted()
            .let { it[it.size / 2] }
        // A new character starts on a real horizontal gap: scaled to the
        // writing size so small and large print both split correctly.
        val gapThreshold = (medianHeight * 0.35f).coerceIn(1f, 8f)

        val groups = ArrayList<ArrayList<Stroke>>()
        var current = arrayListOf(spans[0].stroke)
        var groupMaxX = spans[0].maxX
        for (k in 1 until spans.size) {
            val span = spans[k]
            if (span.minX - groupMaxX > gapThreshold) {
                groups += current
                current = arrayListOf(span.stroke)
                groupMaxX = span.maxX
            } else {
                current += span.stroke
                if (span.maxX > groupMaxX) groupMaxX = span.maxX
            }
        }
        groups += current
        return groups
    }

    // --- single-character match -------------------------------------------------

    private fun recognizeGroup(group: List<Stroke>): Char {
        val points = ArrayList<Point>()
        for (s in group) {
            val pts = s.pointsPacked
            var i = 0
            while (i + 1 < pts.size) {
                points += Point(pts[i], pts[i + 1])
                i += 2
            }
        }
        if (pathLength(points) < 1f) return '?'
        val normalized = normalize(points)
        var bestChar = '?'
        var bestDist = Float.MAX_VALUE
        for (template in TEMPLATES) {
            val dist = pathDistance(normalized, template.normalized)
            if (dist < bestDist) {
                bestDist = dist
                bestChar = template.char
            }
        }
        if (bestDist > ACCEPT_DISTANCE) return '?'
        return bestChar
    }

    /** Resample → scale (non-uniform, degenerate-axis safe) → center at origin. */
    private fun normalize(points: List<Point>): FloatArray {
        val resampled = resample(points, SAMPLES)
        var l = Float.MAX_VALUE
        var t = Float.MAX_VALUE
        var r = -Float.MAX_VALUE
        var b = -Float.MAX_VALUE
        for (p in resampled) {
            if (p.x < l) l = p.x
            if (p.x > r) r = p.x
            if (p.y < t) t = p.y
            if (p.y > b) b = p.y
        }
        val w = (r - l).coerceAtLeast(1e-6f)
        val h = (b - t).coerceAtLeast(1e-6f)
        // A near-1D stroke (I, 1, -) keeps its shape: only the live axis is
        // stretched, the dead axis is centered.
        val sx = if (r - l < 1e-3f) 0f else NORM / w
        val sy = if (b - t < 1e-3f) 0f else NORM / h
        val cx = (l + r) / 2f
        val cy = (t + b) / 2f
        val out = FloatArray(SAMPLES * 2)
        for (i in resampled.indices) {
            out[i * 2] = (resampled[i].x - cx) * sx
            out[i * 2 + 1] = (resampled[i].y - cy) * sy
        }
        return out
    }

    private fun resample(points: List<Point>, n: Int): List<Point> {
        val total = pathLength(points)
        if (total <= 1e-6f) return List(n) { points.first() }
        val interval = total / (n - 1)
        val out = arrayListOf(points.first())
        var remaining = interval
        var prev = points.first()
        var i = 1
        while (i < points.size && out.size < n) {
            val dist = hypot(points[i].x - prev.x, points[i].y - prev.y)
            if (dist < 1e-9f) {
                i++
                continue
            }
            if (remaining + 1e-9f >= dist) {
                remaining -= dist
                prev = points[i]
                i++
            } else {
                val t = remaining / dist
                val nx = prev.x + t * (points[i].x - prev.x)
                val ny = prev.y + t * (points[i].y - prev.y)
                prev = Point(nx, ny)
                out += prev
                remaining = interval
            }
        }
        while (out.size < n) out += points.last()
        return out.take(n)
    }

    private fun pathLength(points: List<Point>): Float {
        var total = 0f
        for (i in 1 until points.size) {
            total += hypot(points[i].x - points[i - 1].x, points[i].y - points[i - 1].y)
        }
        return total
    }

    private fun pathDistance(a: FloatArray, b: FloatArray): Float {
        var total = 0f
        for (i in a.indices step 2) {
            total += hypot(a[i] - b[i], a[i + 1] - b[i + 1])
        }
        return total / (a.size / 2)
    }

    // --- templates --------------------------------------------------------------
    //
    // Authored in the 100x100 NORM grid, y down, "|" separates pen-up strokes.
    // Canonical print forms; the matcher is forgiving, so stroke counts follow
    // natural writing (crossbars and dots are their own strokes).

    private class NormalizedTemplate(val char: Char, val normalized: FloatArray)

    private fun template(char: Char, def: String): NormalizedTemplate {
        val strokes = def.split("|").map { stroke ->
            stroke.trim().split(Regex("\\s+")).map { xy ->
                val (x, y) = xy.split(",")
                Point(x.toFloat(), y.toFloat())
            }
        }
        // Canonical stroke order (sorted by min-x, stable): the runtime
        // grouping sorts the same way, so multi-stroke characters join
        // identically on both sides regardless of authoring or writing order.
        val ordered = strokes.sortedBy { stroke -> stroke.minOf { it.x } }
        return NormalizedTemplate(char, normalize(ordered.flatten()))
    }

    private val TEMPLATES: List<NormalizedTemplate> by lazy {
        TEMPLATE_DEFS.map { (char, def) -> template(char, def) }
    }

    /**
     * Test seam: template strokes as world-mm input strokes (print-sized),
     * optionally transformed. Lets tests feed every template through the real
     * pipeline (grouping + normalization + match).
     */
    internal fun templateInput(
        char: Char,
        scale: Float = 0.08f,
        dx: Float = 0f,
        dy: Float = 0f,
        jitter: Float = 0f,
        firstId: Long = 1L,
    ): List<Stroke> {
        val def = TEMPLATE_DEFS.firstOrNull { it.first == char }?.second
            ?: return emptyList()
        var seed = char.code * 7919L
        fun nextJitter(): Float {
            if (jitter <= 0f) return 0f
            seed = (seed * 6364136223846793005L + 1442695040888963407L) and Long.MAX_VALUE
            return ((seed % 1000L) / 1000f * 2f - 1f) * jitter
        }
        return def.split("|").mapIndexed { index, stroke ->
            val flat = stroke.trim().split(Regex("\\s+")).flatMap { xy ->
                val (x, y) = xy.split(",")
                listOf(
                    x.toFloat() * scale + dx + nextJitter(),
                    y.toFloat() * scale + dy + nextJitter(),
                )
            }.toFloatArray()
            Stroke(
                id = firstId + index,
                style = com.vellum.notes.model.PenStyle(),
                pointsPacked = flat,
            )
        }
    }

    private val TEMPLATE_DEFS: List<Pair<Char, String>> = listOf(
            'A' to "10,90 50,10 90,90|28,62 72,62",
            'B' to "25,10 25,90|25,10 60,10 72,28 60,48 25,48|25,48 62,48 74,68 58,90 25,90",
            'C' to "80,25 55,10 25,25 15,50 25,75 55,90 80,75",
            'D' to "25,10 25,90|25,10 60,15 75,50 60,85 25,90",
            'E' to "75,10 25,10 25,90 75,90|25,50 60,50",
            'F' to "75,10 25,10 25,90|25,50 60,50",
            'G' to "80,25 55,10 25,25 15,50 25,75 55,90 80,80 80,60 60,60",
            'H' to "25,10 25,90|75,10 75,90|25,50 75,50",
            'I' to "30,10 70,10|50,10 50,90|30,90 70,90",
            'J' to "70,10 70,75 55,90 35,90 25,80",
            'K' to "25,10 25,90|70,10 25,50|45,60 75,90",
            'L' to "25,10 25,90 75,90",
            'M' to "15,90 15,10 50,60 85,10 85,90",
            'N' to "20,90 20,10 80,90 80,10",
            'O' to "50,10 78,24 84,50 78,76 50,90 22,76 16,50 22,24",
            'P' to "25,90 25,10|25,10 60,10 72,28 60,48 25,48",
            'Q' to "50,10 78,24 84,50 78,76 50,90 22,76 16,50 22,24|60,68 86,94",
            'R' to "25,90 25,10|25,10 60,10 72,28 60,48 25,48|45,48 75,90",
            'S' to "75,20 60,10 35,12 25,30 35,45 60,50 75,65 65,85 40,90 25,80",
            'T' to "15,10 85,10|50,10 50,90",
            'U' to "20,10 20,70 35,88 65,88 80,70 80,10",
            'V' to "15,10 50,90 85,10",
            'W' to "10,10 30,90 50,50 70,90 90,10",
            'X' to "20,10 80,90|80,10 20,90",
            'Y' to "15,10 50,55|85,10 50,55|50,55 50,90",
            'Z' to "15,10 85,10 15,90 85,90",
            '0' to "50,8 76,24 80,50 76,76 50,92 24,76 20,50 24,24",
            '1' to "35,25 55,10 55,90|35,90 75,90",
            '2' to "20,25 40,10 65,15 70,35 55,55 20,90 80,90",
            '3' to "25,15 55,10 70,30 55,48 38,50|38,50 60,54 72,70 55,90 25,85",
            '4' to "65,10 30,60 75,60|65,10 65,90",
            '5' to "75,10 35,10 30,45 55,45 70,60 65,80 45,90 25,85",
            '6' to "70,20 50,10 30,35 25,60 35,85 60,88 72,70 60,55 35,55",
            '7' to "15,10 85,10 55,45 40,90",
            '8' to "38,22 50,12 66,18 68,34 54,44 42,40|54,46 68,56 70,74 56,88 38,84 32,66 44,52",
            '9' to "30,80 50,90 70,65 75,40 65,15 40,12 28,30 40,45 65,48",
        )
}
