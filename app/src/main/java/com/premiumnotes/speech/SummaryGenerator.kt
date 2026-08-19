package com.premiumnotes.speech

import com.premiumnotes.model.TranscriptSegment

/**
 * On-device, offline extractive summarizer for Classroom Notes transcripts. Runs entirely
 * on the device (like the Vosk recognizer) with no network access.
 *
 * Approach — classic extractive ranking:
 *  1. Concatenate all transcript segments into one corpus.
 *  2. Split it into sentences.
 *  3. Score every sentence by the summed frequency of its words (stop words removed),
 *     lightly length-normalized so one-word fragments don't dominate.
 *  4. Emit the highest-scoring sentences in their original spoken order until the word
 *     budget is exhausted.
 *
 * Deterministic and cheap (a few ms for a lecture-sized transcript), which keeps it
 * unit-testable on the JVM. The design is deliberately simple so it can be swapped for a
 * neural/LLM extractor later without touching the sidebar UI.
 */
object SummaryGenerator {

    /** Generates an extractive summary of at most [maxWords] words, or null when empty. */
    fun summarize(segments: List<TranscriptSegment>, maxWords: Int = 120): String? {
        val corpus = segments.joinToString(" ") { it.text }.trim()
        if (corpus.isBlank() || maxWords <= 0) return null

        val sentences = splitSentences(corpus)
        if (sentences.isEmpty()) return null

        val frequencies = HashMap<String, Int>()
        for (word in words(corpus)) frequencies[word] = (frequencies[word] ?: 0) + 1

        val scored = sentences.mapIndexed { index, sentence ->
            val fullCount = sentence.split(Regex("\\s+")).size
            val contentWords = words(sentence)
            val score = if (contentWords.isEmpty()) {
                0.0
            } else {
                contentWords.sumOf { frequencies[it] ?: 0 }.toDouble() / kotlin.math.sqrt(fullCount.toDouble())
            }
            Sentence(sentence, fullCount, score, index)
        }

        val top = scored.sortedByDescending { it.score }
        val selected = LinkedHashSet<Sentence>()
        var budget = maxWords
        for (sentence in top) {
            if (sentence.wordCount > budget) continue
            selected += sentence
            budget -= sentence.wordCount
        }

        val ordered = selected.sortedBy { it.index }
        if (ordered.isEmpty()) {
            // Fall back to the first sentence so a summary is never empty when asked for.
            return scored.first().text
        }
        return ordered.joinToString(" ") { it.text }
    }

    private data class Sentence(val text: String, val wordCount: Int, val score: Double, val index: Int)

    private fun splitSentences(text: String): List<String> =
        text.split(Regex("(?<=[.!?])\\s+")).map { it.trim() }.filter { it.isNotBlank() }

    /** Lowercased, punctuation-free word tokens, stop words removed. */
    private fun words(text: String): List<String> = text
        .lowercase()
        .split(Regex("[^a-z0-9']+"))
        .map { it.trim('\'') }
        .filter { it.isNotBlank() && it !in STOP_WORDS }

    private val STOP_WORDS = setOf(
        "a", "an", "the", "and", "or", "but", "if", "then", "so", "of", "in", "on", "at",
        "to", "for", "with", "by", "from", "as", "is", "are", "was", "were", "be", "been",
        "being", "have", "has", "had", "do", "does", "did", "will", "would", "can", "could",
        "should", "may", "might", "must", "shall", "this", "that", "these", "those", "it",
        "its", "he", "she", "they", "we", "you", "i", "me", "him", "her", "them", "us",
        "my", "your", "his", "their", "our", "not", "no", "yes", "just", "like", "about",
        "into", "over", "again", "there", "here", "all", "some", "any", "also", "very",
        "really", "right", "okay", "um", "uh", "so", "let", "get", "got", "go", "going",
        "one", "two", "now", "well", "yeah", "know", "think", "talk", "say", "said",
    )
}