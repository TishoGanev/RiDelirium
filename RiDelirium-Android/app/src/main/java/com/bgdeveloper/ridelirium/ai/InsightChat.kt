package com.bgdeveloper.ridelirium.ai

import com.bgdeveloper.ridelirium.domain.InsightFacts
import com.bgdeveloper.ridelirium.domain.showsSpeed
import com.bgdeveloper.ridelirium.domain.words
import com.bgdeveloper.ridelirium.friends.Sport

/**
 * Questions about one ride or period, answered by the on-device model from the same facts as the summary.
 * Keep in sync with `InsightChat.swift` on iOS.
 */
object InsightChat {
    data class Exchange(val question: String, val answer: String)

    /** Hidden for now: the answers were not reliable enough. Set to true to show the question box again. */
    const val ENABLED = false

    /** Earlier exchanges sent along with a question; the on-device model's window is small. */
    const val MAX_HISTORY = 3
    const val MAX_QUESTION_LENGTH = 300

    /** The question trimmed and capped, or null when there is nothing to ask. */
    fun clean(question: String): String? = question.trim().take(MAX_QUESTION_LENGTH).ifEmpty { null }

    /** Who the model is, the rules, every fact it may use, then the last few exchanges and the new question. */
    fun prompt(facts: InsightFacts, question: String, history: List<Exchange>): String {
        val scope = if (facts.isPeriod) "several days of ride data" else "one ride"
        val turns = history.takeLast(MAX_HISTORY).joinToString("\n") { "Rider: ${it.question}\nCoach: ${it.answer}" }
        return buildString {
            append("You are a concise ${facts.role} answering the rider's questions about $scope. ")
            append("Use only the data below. If the data does not contain the answer, say you don't have that. ")
            append("Answer in one to three short sentences. Never invent numbers, places or events. ")
            append("Plain, friendly language. No emojis. If asked about anything other than this ride's data, ")
            append("say you can only answer questions about the ride. ")
            append(facts.brief)
            append("\n\nData:\n").append(facts.prompt).append("\n\n")
            if (turns.isNotEmpty()) append(turns).append('\n')
            append("Rider: ").append(question).append("\nCoach:")
        }
    }

    /** A few questions to start from, matched to the sport and to ride or period. */
    fun suggestions(facts: InsightFacts): List<String> {
        if (facts.isPeriod) return listOf("Am I improving?", "What changed the most?")
        val words = if (facts.sports.size > 1) Sport.BIKE.words else facts.sport.words
        val list = mutableListOf("Which ${words.run} was my biggest?", "How did I pace the day?")
        if (facts.sports.any { it.showsSpeed }) list.add(0, "Which ${words.run} was fastest?")
        return list
    }
}
