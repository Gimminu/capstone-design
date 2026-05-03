package com.capstone.design.youtubeparser

import android.content.Context

object AnalysisSensitivityStore {
    private const val PREFS_NAME = "youtube_parser_settings"
    private const val KEY_ANALYSIS_SENSITIVITY = "analysis_sensitivity"
    const val DEFAULT_SENSITIVITY = 60

    fun get(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return clamp(prefs.getInt(KEY_ANALYSIS_SENSITIVITY, DEFAULT_SENSITIVITY))
    }

    fun save(context: Context, value: Int) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_ANALYSIS_SENSITIVITY, clamp(value)).apply()
    }

    internal fun clamp(value: Int): Int {
        return value.coerceIn(0, 100)
    }
}
