package com.vellum.notes.render

/**
 * Word-wrap for canvas text boxes (60fps workstream).
 *
 * Wrapping used to run per text box on every draw frame (string splits plus a
 * fresh list allocation each time). It now runs once when the text list changes
 * and the result is cached; this pure function holds the algorithm so it can be
 * unit-tested without Android graphics classes.
 *
 * @param measure width of [candidate] in the same units as [maxWidth].
 */
object TextLayout {

    fun wrap(text: String, measure: (String) -> Float, maxWidth: Float): List<String> {
        val maxW = maxWidth.coerceAtLeast(1f)
        val lines = ArrayList<String>()
        for (raw in text.split('\n')) {
            var line = ""
            for (word in raw.split(' ')) {
                val candidate = if (line.isEmpty()) word else "$line $word"
                if (measure(candidate) > maxW && line.isNotEmpty()) {
                    lines += line
                    line = word
                } else {
                    line = candidate
                }
            }
            lines += line
        }
        return lines
    }
}
