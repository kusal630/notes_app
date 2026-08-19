package com.premiumnotes.speech

import com.premiumnotes.model.TranscriptSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SummaryGeneratorTest {

    private fun segments(vararg texts: String) =
        texts.mapIndexed { i, t -> TranscriptSegment(id = i.toLong() + 1, startMs = i * 1000L, endMs = (i + 1) * 1000L, text = t) }

    @Test
    fun emptyTranscriptProducesNull() {
        assertNull(SummaryGenerator.summarize(emptyList()))
        assertNull(SummaryGenerator.summarize(segments("   ", "  ")))
    }

    @Test
    fun singleSentenceIsReturned() {
        val out = SummaryGenerator.summarize(segments("Photosynthesis converts light into energy."))
        assertNotNull(out)
        assertEquals("Photosynthesis converts light into energy.", out)
    }

    @Test
    fun summaryRespectsWordBudget() {
        val text = "Mitochondria produce energy for the cell. Nuclei contain genetic information. " +
            "Ribosomes build proteins from amino acids. The membrane controls what enters the cell."
        val out = SummaryGenerator.summarize(segments(text), maxWords = 6)!!
        assertTrue("summary too long: $out", out.split(" ").size <= 6)
        // A top-scoring, informative sentence is retained within budget (a repeat of the
        // word "cell" can tie the two 6-word candidates, so either is acceptable).
        assertTrue(out.contains("Mitochondria") || out.contains("Ribosomes"))
    }

    @Test
    fun summaryKeepsOriginalSentenceOrder() {
        val text = "First the intro sentence is spoken. Then the key idea appears here. " +
            "Then a minor filler sentence trails behind."
        val out = SummaryGenerator.summarize(segments(text), maxWords = 30)!!
        val firstIdx = out.indexOf("First")
        val keyIdx = out.indexOf("key idea")
        assertTrue(firstIdx >= 0 && keyIdx > firstIdx)
    }

    @Test
    fun summaryAcrossMultipleSegmentsJoinsThem() {
        val out = SummaryGenerator.summarize(
            segments(
                "Gravity is a fundamental force. It pulls masses toward each other.",
                "On Earth gravity gives objects weight. The stronger the mass the stronger the pull.",
            ),
            maxWords = 30,
        )!!
        // Words from both segments are considered (a single source corpus).
        assertTrue(out.split(" ").size <= 30)
        assertTrue(out.contains("Gravity"))
    }
}