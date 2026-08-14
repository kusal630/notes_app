package com.premiumnotes.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class StrokeSmootherTest {

    private fun runThrough(mode: SmoothingMode, points: List<FloatArray>): List<FloatArray> {
        val smoother = StrokeSmoother.create(mode)
        val out = ArrayList<FloatArray>()
        var t = 0L
        points.forEach { p ->
            out += smoother.process(p[0], p[1], t)
            t += 16
        }
        out += smoother.flush()
        return out
    }

    private fun jitteredLine(n: Int, step: Float, jitter: Float): List<FloatArray> {
        return (0 until n).map { i ->
            floatArrayOf(i * step, if (i > 0 && i < n - 1) (i * step * 0.4f) + ((Math.random() - 0.5) * 2 * jitter).toFloat() else i * step * 0.4f)
        }
    }

    @Test
    fun nonePreservesEveryPoint() {
        val input = listOf(floatArrayOf(0f, 0f), floatArrayOf(10f, 5f), floatArrayOf(20f, 9f))
        val out = runThrough(SmoothingMode.NONE, input)
        assertEquals(input.size, out.size)
        for (i in input.indices) {
            assertEquals(input[i][0], out[i][0], 0.0001f)
            assertEquals(input[i][1], out[i][1], 0.0001f)
        }
    }

    @Test
    fun endpointsAreExactForAllModes() {
        for (mode in SmoothingMode.entries) {
            val input = listOf(floatArrayOf(0f, 0f), floatArrayOf(10f, 5f), floatArrayOf(20f, 9f), floatArrayOf(30f, 12f))
            val out = runThrough(mode, input)
            assertEquals(input.first()[0], out.first()[0], 0.001f)
            assertEquals(input.first()[1], out.first()[1], 0.001f)
            assertEquals(input.last()[0], out.last()[0], 0.001f)
            assertEquals(input.last()[1], out.last()[1], 0.001f)
        }
    }

    @Test
    fun smoothingReducesJitter() {
        val input = jitteredLine(60, 8f, 3f)
        val raw = runThrough(SmoothingMode.NONE, input)
        val smooth = runThrough(SmoothingMode.MEDIUM, input)
        val rawVariance = variance(raw.map { it[1] })
        val smoothVariance = variance(smooth.map { it[1] })
        assertTrue("smooth variance ($smoothVariance) should be below raw ($rawVariance)", smoothVariance < rawVariance)
    }

    @Test
    fun kalmanKeepsUpWithFastStrokes() {
        // A fast diagonal sweep (no jitter) must not lose significant distance.
        val input = (0 until 100).map { i -> floatArrayOf(i * 10f, i * 10f) }
        val out = runThrough(SmoothingMode.HIGH, input)
        val last = out.last()
        val expected = 990f
        // Kalman lag on a straight line at constant velocity should be small.
        assertTrue("lag = ${abs(last[0] - expected)}", abs(last[0] - expected) < 40f)
    }

    private fun variance(values: List<Float>): Double {
        if (values.isEmpty()) return 0.0
        val mean = values.average()
        return values.map { (it - mean) * (it - mean) }.average()
    }
}