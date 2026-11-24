package com.bgdeveloper.ridelirium

import com.bgdeveloper.ridelirium.ai.InsightChat
import com.bgdeveloper.ridelirium.domain.InsightFacts
import com.bgdeveloper.ridelirium.friends.Sport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InsightChatTest {
    private fun facts(sport: Sport = Sport.BOARD, isPeriod: Boolean = false) =
        InsightFacts(listOf("a"), "Runs: 3\nRun 1: 2 min", hasRuns = true, isPeriod = isPeriod, sport = sport)

    @Test fun blankQuestionsAreDropped() {
        assertNull(InsightChat.clean("   \n"))
        assertEquals("fastest run?", InsightChat.clean("  fastest run? "))
    }

    @Test fun longQuestionIsCapped() {
        assertEquals(InsightChat.MAX_QUESTION_LENGTH, InsightChat.clean("a".repeat(900))!!.length)
    }

    @Test fun promptCarriesTheFactsAndTheRules() {
        val text = InsightChat.prompt(facts(), "Which run was fastest?", emptyList())
        assertTrue(text.contains("Run 1: 2 min"))
        assertTrue(text.contains("If the data does not contain the answer, say you don't have that"))
        assertTrue(text.contains("snowboarding coach"))
        assertTrue(text.endsWith("Rider: Which run was fastest?\nCoach:"))
        assertTrue(InsightChat.prompt(facts(Sport.HIKE), "q", emptyList()).contains("hiking guide"))
        assertTrue(InsightChat.prompt(facts(isPeriod = true), "q", emptyList()).contains("several days"))
    }

    @Test fun promptKeepsOnlyTheLastExchanges() {
        val history = (1..5).map { InsightChat.Exchange("q$it", "a$it") }
        val text = InsightChat.prompt(facts(), "now?", history)
        assertFalse(text.contains("Rider: q1"))
        assertFalse(text.contains("Rider: q2"))
        assertTrue(text.contains("Rider: q3\nCoach: a3"))
    }

    @Test fun hikeSuggestionsNeverAskForFastest() {
        assertFalse(InsightChat.suggestions(facts(Sport.HIKE)).any { it.contains("fastest") })
        assertTrue(InsightChat.suggestions(facts(Sport.SKI)).any { it.contains("fastest") })
        assertEquals(2, InsightChat.suggestions(facts(isPeriod = true)).size)
    }
}
