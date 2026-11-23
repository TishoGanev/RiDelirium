package com.bgdeveloper.ridelirium.ai

import com.bgdeveloper.ridelirium.domain.InsightFacts
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A short written summary of a ride, in the shape iOS shows it. */
data class AiSummary(val headline: String, val observations: List<String>, val tip: String)

sealed interface AiResult {
    data class Ready(val summary: AiSummary) : AiResult

    /** Gemini Nano is not on this phone, or not ready; the screen shows the numbers only. */
    data object Unavailable : AiResult

    data object Failed : AiResult
}

/**
 * Writes the summary with Gemini Nano through ML Kit's on-device Prompt API (AICore), on the phones that have it.
 * Nothing leaves the phone. Everywhere else the result is [AiResult.Unavailable] and only the numbers show.
 */
object AiInsights {
    private val lock = Mutex()
    private val cache = object : LinkedHashMap<String, AiSummary>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, AiSummary>?) = size > CACHE_SIZE
    }

    /** Generates once per ride or period: reopening the sheet shows the same text. [regenerate] gives a new take. */
    suspend fun generate(facts: InsightFacts, regenerate: Boolean = false): AiResult {
        if (!facts.hasRuns) return AiResult.Unavailable
        val key = facts.instructions + "\n" + facts.prompt
        if (!regenerate) lock.withLock { cache[key] }?.let { return AiResult.Ready(it) }
        return try {
            val model = Generation.getClient()
            try {
                run(model, facts, key)
            } finally {
                model.close()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Includes LinkageError, so a phone without AICore never crashes the sheet.
            AiResult.Failed
        }
    }

    private suspend fun run(model: GenerativeModel, facts: InsightFacts, key: String): AiResult {
        when (model.checkStatus()) {
            FeatureStatus.UNAVAILABLE -> return AiResult.Unavailable
            FeatureStatus.AVAILABLE -> Unit
            // The system downloads the model; wait for it, and give up quietly if that fails.
            else -> {
                var failed = false
                model.download().collect { if (it is DownloadStatus.DownloadFailed) failed = true }
                if (failed || model.checkStatus() != FeatureStatus.AVAILABLE) return AiResult.Unavailable
            }
        }
        val request = generateContentRequest(TextPart(buildPrompt(facts))) {
            temperature = 0.3f
            maxOutputTokens = MAX_OUTPUT_TOKENS
        }
        val text = model.generateContent(request).candidates.firstOrNull()?.text.orEmpty()
        val summary = parse(text) ?: return AiResult.Failed
        lock.withLock { cache[key] = summary }
        return AiResult.Ready(summary)
    }

    private fun buildPrompt(facts: InsightFacts): String =
        """
        ${facts.instructions}

        Answer in exactly this format, nothing else:
        HEADLINE: one short sentence that sums up ${if (facts.isPeriod) "the period" else "the day"}, under 14 words
        - first observation, using only numbers from the data
        - second observation, using only numbers from the data
        - third observation, using only numbers from the data
        TIP: one practical tip for the next ${if (facts.isPeriod) "outing" else "ride"}, one sentence

        Data:
        ${facts.prompt}
        """.trimIndent()

    /** Reads the HEADLINE / bullets / TIP format; null when the model did not follow it. */
    internal fun parse(text: String): AiSummary? {
        var headline: String? = null
        var tip: String? = null
        val observations = mutableListOf<String>()
        for (raw in text.lines()) {
            val line = raw.trim().trimStart('*', '#').trim()
            when {
                line.startsWith("HEADLINE:", ignoreCase = true) -> headline = line.substringAfter(':').clean()
                line.startsWith("TIP:", ignoreCase = true) -> tip = line.substringAfter(':').clean()
                line.startsWith("- ") || line.startsWith("• ") -> observations += line.drop(2).clean()
            }
        }
        if (headline.isNullOrBlank() || tip.isNullOrBlank() || observations.isEmpty()) return null
        return AiSummary(headline, observations.filter { it.isNotBlank() }.take(3), tip)
    }

    private fun String.clean() = trim().trim('*').trim()

    private const val CACHE_SIZE = 8
    private const val MAX_OUTPUT_TOKENS = 400
}
