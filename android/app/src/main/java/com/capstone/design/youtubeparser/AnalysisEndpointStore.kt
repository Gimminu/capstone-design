package com.capstone.design.youtubeparser

import android.content.Context

object AnalysisEndpointStore {

    private const val PREFS_NAME = "youtube_parser_settings"
    private const val KEY_ANALYSIS_INPUT = "analysis_input"
    private const val DEFAULT_ANALYSIS_HOST = "100.95.209.72:8000"
    private const val DEFAULT_ANALYSIS_PATH = "/analyze_android"

    fun getRawInput(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_ANALYSIS_INPUT, DEFAULT_ANALYSIS_HOST).orEmpty()
    }

    fun saveRawInput(context: Context, value: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_ANALYSIS_INPUT, value.trim()).apply()
    }

    fun resolveAnalyzeUrl(context: Context): String {
        return resolveAnalyzeUrl(getRawInput(context))
    }

    fun resolveAnalyzeUrl(rawInput: String): String {
        val raw = rawInput.trim().ifBlank { DEFAULT_ANALYSIS_HOST }

        if (raw.startsWith("http://") || raw.startsWith("https://")) {
            return appendDefaultPathIfNeeded(raw)
        }

        val normalized = raw.trimEnd('/')
        val baseUrl = if (normalized.contains(":")) {
            "http://$normalized"
        } else {
            "http://$normalized:8000"
        }

        return appendDefaultPathIfNeeded(baseUrl)
    }

    private fun appendDefaultPathIfNeeded(url: String): String {
        val normalized = url.trimEnd('/')
        return if (normalized.endsWith(DEFAULT_ANALYSIS_PATH)) {
            normalized
        } else {
            "$normalized$DEFAULT_ANALYSIS_PATH"
        }
    }
}
