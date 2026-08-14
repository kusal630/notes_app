package com.premiumnotes.input

/** User-selectable handwriting smoothing strength. */
enum class SmoothingMode { NONE, LOW, MEDIUM, HIGH }

/**
 * Streaming point smoother. Consumes raw (noise-filtered, dead-zone-tested) input points
 * and emits the points that should be appended to the active stroke. Endpoints of the
 * stroke are always preserved exactly.
 */
interface StrokeSmoother {
    fun reset()

    /** Processes one raw input sample; returns points to append to the stroke. */
    fun process(x: Float, y: Float, t: Long): List<FloatArray>

    /** Called at pen-up; emits any buffered points that close the stroke. */
    fun flush(): List<FloatArray>

    companion object {
        fun create(mode: SmoothingMode): StrokeSmoother = when (mode) {
            SmoothingMode.NONE -> NoSmoothing()
            SmoothingMode.LOW -> MovingAverageSmoother(3)
            SmoothingMode.MEDIUM -> CatmullRomSmoother(interpolationSteps = 3)
            SmoothingMode.HIGH -> KalmanSmoother(processNoise = 1e-3f, measurementNoise = 2.5f)
        }
    }
}

/** Pass-through: the raw point is emitted untouched. */
class NoSmoothing : StrokeSmoother {
    override fun reset() {}
    override fun process(x: Float, y: Float, t: Long): List<FloatArray> = listOf(floatArrayOf(x, y))
    override fun flush(): List<FloatArray> = emptyList()
}

/** Windowed moving average. Keeps endpoints exact by re-anchoring on the first point. */
class MovingAverageSmoother(private val window: Int) : StrokeSmoother {
    private val buffer = ArrayDeque<FloatArray>()
    private var first = true
    private var lastAnchor = floatArrayOf(0f, 0f)
    private var lastRaw = floatArrayOf(0f, 0f)

    override fun reset() {
        buffer.clear()
        first = true
    }

    override fun process(x: Float, y: Float, t: Long): List<FloatArray> {
        if (first) {
            first = false
            lastAnchor = floatArrayOf(x, y)
            lastRaw = floatArrayOf(x, y)
            buffer.add(floatArrayOf(x, y))
            return listOf(floatArrayOf(x, y))
        }
        lastRaw = floatArrayOf(x, y)
        buffer.addLast(floatArrayOf(x, y))
        while (buffer.size > window) buffer.removeFirst()
        if (buffer.size == window) {
            val out = floatArrayOf(
                buffer.sumOf { it[0].toDouble() }.toFloat() / window,
                buffer.sumOf { it[1].toDouble() }.toFloat() / window,
            )
            lastAnchor = out
            return listOf(out)
        }
        return emptyList()
    }

    override fun flush(): List<FloatArray> =
        if (lastRaw[0] != lastAnchor[0] || lastRaw[1] != lastAnchor[1]) listOf(lastRaw) else emptyList()
}

/**
 * Streaming Catmull-Rom interpolation. Maintains up to four anchor points and emits the
 * interpolated curve between the middle two anchors as each new anchor arrives, so the
 * visible stroke is always the smooth curve rather than a jittery polyline.
 */
class CatmullRomSmoother(
    private val interpolationSteps: Int = 3,
    private val minSegmentPx: Float = 0.5f,
) : StrokeSmoother {
    private val anchors = ArrayDeque<FloatArray>()
    private var lastEmitted: FloatArray? = null

    override fun reset() {
        anchors.clear()
        lastEmitted = null
    }

    override fun process(x: Float, y: Float, t: Long): List<FloatArray> {
        val p = floatArrayOf(x, y)
        val out = ArrayList<FloatArray>()
        if (lastEmitted == null) {
            lastEmitted = p
            anchors.addLast(p)
            return listOf(p)
        }
        anchors.addLast(p)
        while (anchors.size > 4) anchors.removeFirst()

        if (anchors.size == 4) {
            val pts = interpolate(anchors[0], anchors[1], anchors[2], anchors[3], interpolationSteps)
            val filtered = pts.filter { it[0] != lastEmitted!![0] || it[1] != lastEmitted!![1] }
            if (filtered.isNotEmpty()) lastEmitted = filtered.last()
            out += filtered
        }
        return out
    }

    override fun flush(): List<FloatArray> {
        val out = ArrayList<FloatArray>()
        if (anchors.size >= 2) {
            val last = anchors.last()
            out += interpolate(
                anchors.getOrElse(anchors.size - 3) { anchors[0] },
                anchors.getOrElse(anchors.size - 2) { anchors[0] },
                anchors[anchors.size - 2],
                last,
                interpolationSteps,
            ).filter { it[0] != lastEmitted?.get(0) || it[1] != lastEmitted?.get(1) }
            // Pen-up point is always exact.
            out += last
        }
        return out
    }

    private fun interpolate(
        p0: FloatArray, p1: FloatArray, p2: FloatArray, p3: FloatArray,
        steps: Int,
    ): List<FloatArray> {
        if (steps <= 1) return listOf(p2)
        val out = ArrayList<FloatArray>(steps)
        for (i in 1..steps) {
            val t = i.toFloat() / steps
            val t2 = t * t
            val t3 = t2 * t
            val x = 0.5f * ((2f * p1[0]) + (-p0[0] + p2[0]) * t + (2f * p0[0] - 5f * p1[0] + 4f * p2[0] - p3[0]) * t2 + (-p0[0] + 3f * p1[0] - 3f * p2[0] + p3[0]) * t3)
            val y = 0.5f * ((2f * p1[1]) + (-p0[1] + p2[1]) * t + (2f * p0[1] - 5f * p1[1] + 4f * p2[1] - p3[1]) * t2 + (-p0[1] + 3f * p1[1] - 3f * p2[1] + p3[1]) * t3)
            out += floatArrayOf(x, y)
        }
        return out
    }
}

/**
 * 2D constant-velocity Kalman filter with independent axes. Follows fast pen strokes
 * with low lag while strongly suppressing slow jitter (the dominant noise in
 * handwriting). Tunable via process/measurement noise.
 */
class KalmanSmoother(
    private val processNoise: Float = 1e-3f,
    private val measurementNoise: Float = 2.5f,
) : StrokeSmoother {
    private var initialized = false
    // state: pos, vel for each axis
    private var px = 0f; private var pvx = 0f
    private var py = 0f; private var pvy = 0f
    private var cov11 = 1f; private var cov12 = 0f; private var cov22 = 1f
    private var lastT = 0L
    private var lastRawX = 0f
    private var lastRawY = 0f

    override fun reset() {
        initialized = false
    }

    override fun process(x: Float, y: Float, t: Long): List<FloatArray> {
        if (!initialized) {
            initialized = true
            px = x; py = y; pvx = 0f; pvy = 0f
            cov11 = 1f; cov12 = 0f; cov22 = 1f
            lastT = t
            return listOf(floatArrayOf(x, y))
        }
        val dt = ((t - lastT).coerceAtLeast(0L)) / 1_000_000_000.0f
        lastT = t
        val dt2 = dt * dt

        // Predict: x' = x + v*dt; P' = F P F^T + Q
        px += pvx * dt; py += pvy * dt
        val q = processNoise
        val new11 = cov11 + 2 * dt * cov12 + dt2 * cov22 + q
        val new12 = cov12 + dt * cov22 + q * 0.5f
        val new22 = cov22 + q
        cov11 = new11; cov12 = new12; cov22 = new22

        // Update with measurement
        val r = measurementNoise
        val s = cov11 + r
        val k1 = cov11 / s
        val k2 = cov12 / s
        val residX = x - px
        val residY = y - py
        px += k1 * residX; pvx += k2 * residX
        py += k1 * residY; pvy += k2 * residY
        cov11 *= (1f - k1); cov12 *= (1f - k1)

        lastRawX = x
        lastRawY = y
        return listOf(floatArrayOf(px, py))
    }

    override fun flush(): List<FloatArray> {
        if (!initialized) return emptyList()
        // Pen-up point snaps exactly to the measured position.
        if (px != lastRawX || py != lastRawY) {
            px = lastRawX
            py = lastRawY
            return listOf(floatArrayOf(px, py))
        }
        return emptyList()
    }
}