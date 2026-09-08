// Copyright (C) 2026 codeRed
// Vellum is free software: you can redistribute it and/or modify it under
// the terms of the GNU General Public License as published by the Free Software
// Foundation, either version 3 of the License, or (at your option) any later version.
package com.vellum.notes.render

import com.vellum.notes.model.Point
import com.vellum.notes.model.Stroke

/**
 * Handwriting beautification: Chaikin corner-cutting on committed strokes.
 * Pure Kotlin, zero deps. One pass keeps endpoints fixed; two passes max to
 * avoid over-shrinking short strokes. Strokes with < 3 points pass through.
 */
object InkBeautify {
    fun smoothStroke(stroke: Stroke, passes: Int = 1): Stroke {
        val pts = stroke.points
        if (pts.size < 3 || passes <= 0) return stroke
        var cur = pts
        repeat(passes.coerceAtMost(2)) { cur = chaikinOnce(cur) }
        return stroke.copy(pointsPacked = Stroke.pack(cur))
    }

    fun smoothStrokes(strokes: List<Stroke>, passes: Int = 1): List<Stroke> =
        strokes.map { smoothStroke(it, passes) }

    private fun chaikinOnce(pts: List<Point>): List<Point> {
        if (pts.size < 3) return pts
        val out = ArrayList<Point>(pts.size * 2)
        out += pts.first()
        for (i in 0 until pts.size - 1) {
            val a = pts[i]
            val b = pts[i + 1]
            out += Point(a.x * 0.75f + b.x * 0.25f, a.y * 0.75f + b.y * 0.25f)
            out += Point(a.x * 0.25f + b.x * 0.75f, a.y * 0.25f + b.y * 0.75f)
        }
        out += pts.last()
        return out
    }
}
