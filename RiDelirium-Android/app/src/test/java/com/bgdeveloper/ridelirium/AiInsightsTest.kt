package com.bgdeveloper.ridelirium

import com.bgdeveloper.ridelirium.ai.AiInsights
import com.bgdeveloper.ridelirium.domain.DayMetrics
import com.bgdeveloper.ridelirium.domain.InsightFacts
import com.bgdeveloper.ridelirium.domain.Units
import com.bgdeveloper.ridelirium.friends.Sport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiInsightsTest {
    @Test
    fun parsesTheRequestedFormat() {
        val summary = AiInsights.parse(
            """
            HEADLINE: A steady day with seven runs
            - You rode 7 runs.
            - **Run 3** had the most descent.
            - Top speed was 52.0 KM/H.
            TIP: Take a break after run 5.
            """.trimIndent(),
        )
        assertNotNull(summary)
        assertEquals("A steady day with seven runs", summary!!.headline)
        assertEquals(3, summary.observations.size)
        assertEquals("Take a break after run 5.", summary.tip)
    }

    @Test
    fun rejectsReplyThatIgnoresTheFormat() {
        assertNull(AiInsights.parse("Sure! Here is a nice summary of your day."))
        assertNull(AiInsights.parse("HEADLINE: Only a headline"))
    }

    @Test
    fun instructionsFollowTheSport() {
        fun facts(sport: Sport, period: Boolean = false) = InsightFacts(listOf("x"), "data", true, period, sport)
        assertTrue(facts(Sport.HIKE).instructions.contains("hiking guide"))
        assertTrue(facts(Sport.HIKE).instructions.contains("never mention snow"))
        assertTrue(facts(Sport.BOARD).instructions.contains("snowboarding coach"))
        assertTrue(facts(Sport.SKI).instructions.contains("ski coach"))
        assertFalse(facts(Sport.BOARD).instructions.contains("improving"))
        assertTrue(facts(Sport.BOARD, period = true).instructions.contains("improving"))
    }

    @Test
    fun periodFactsNameTheSportAndHaveNoRunsWhenEmpty() {
        val units = Units(metric = true)
        val day = DayMetrics(1_700_000_000_000, runs = 6, downhillDistance = 9000.0, vertical = 2400.0, topSpeed = 15.0, ridingSeconds = 3600.0, totalSeconds = 18000.0)
        val facts = InsightFacts.period(listOf(day), "7 DAYS", units, Sport.HIKE)
        assertTrue(facts.hasRuns)
        assertTrue(facts.prompt.startsWith("Sport: hiking"))
        assertFalse(InsightFacts.period(emptyList(), "7 DAYS", units, Sport.BOARD).hasRuns)
    }
}
