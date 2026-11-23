package com.arkreader.comic.data

import android.content.Context
import org.json.JSONObject
import java.time.LocalDate

internal data class TranslationTokens(
    val requests: Long = 0,
    val reported: Long = 0,
    val input: Long = 0,
    val output: Long = 0,
    val reasoning: Long = 0
) {
    val total get() = input + output
    operator fun plus(other: TranslationTokens) = TranslationTokens(
        requests + other.requests, reported + other.reported, input + other.input,
        output + other.output, reasoning + other.reasoning
    )
}

internal data class TranslationUsageDay(val day: String, val model: String, val tokens: TranslationTokens)

internal object TranslationUsage {
    private fun prefs(context: Context) = context.getSharedPreferences("translation_usage", Context.MODE_PRIVATE)

    @Synchronized
    fun record(context: Context, model: String, tokens: TranslationTokens) {
        val prefs = prefs(context)
        val day = LocalDate.now().toString()
        val models = decode(prefs.getString(day, null))
        val total = read(models.optJSONObject(model)) + tokens
        models.put(model, JSONObject().put("requests", total.requests).put("reported", total.reported)
            .put("input", total.input).put("output", total.output).put("reasoning", total.reasoning))
        prefs.edit().putString(day, models.toString()).apply()
    }

    @Synchronized
    fun recent(context: Context, days: Int = 7): List<TranslationUsageDay> {
        val today = LocalDate.now()
        val prefs = prefs(context)
        return (0 until days).flatMap { offset ->
            val day = today.minusDays(offset.toLong()).toString()
            val models = decode(prefs.getString(day, null))
            models.keys().asSequence().map { TranslationUsageDay(day, it, read(models.optJSONObject(it))) }.toList()
        }
    }

    private fun decode(value: String?) = runCatching { JSONObject(value.orEmpty()) }.getOrElse { JSONObject() }
    private fun read(json: JSONObject?) = if (json == null) TranslationTokens() else TranslationTokens(
        json.optLong("requests"), json.optLong("reported"), json.optLong("input"), json.optLong("output"), json.optLong("reasoning")
    )
}
