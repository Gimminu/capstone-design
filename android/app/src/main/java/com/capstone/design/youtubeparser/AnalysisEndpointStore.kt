package com.capstone.design.youtubeparser

import android.content.Context
import android.os.Build

object AnalysisEndpointStore {

    private const val PREFS_NAME = "youtube_parser_settings"
    private const val KEY_ANALYSIS_INPUT = "analysis_input"
    private const val DEFAULT_EMULATOR_ANALYSIS_HOST = "10.0.2.2:8000"
    private const val DEFAULT_DEVICE_ANALYSIS_HOST = "100.95.209.72:8000"
    private const val LEGACY_DEFAULT_ANALYSIS_HOST = "100.95.209.72:8000"
    private const val LEGACY_DEFAULT_ANALYSIS_HOST_BARE = "100.95.209.72"
    private const val DEFAULT_ANALYSIS_PATH = "/analyze_android"

    fun getRawInput(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_ANALYSIS_INPUT, null)?.trim().orEmpty()
        if (stored.isBlank()) {
            return defaultHostForRuntime()
        }

        return if (
            isLikelyEmulator() &&
            (stored == LEGACY_DEFAULT_ANALYSIS_HOST || stored == LEGACY_DEFAULT_ANALYSIS_HOST_BARE)
        ) {
            DEFAULT_EMULATOR_ANALYSIS_HOST
        } else {
            stored
        }
    }

    fun saveRawInput(context: Context, value: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_ANALYSIS_INPUT, value.trim()).apply()
    }

    fun resolveAnalyzeUrl(context: Context): String {
        return resolveAnalyzeUrl(getRawInput(context))
    }

    fun resolveAnalyzeUrl(rawInput: String): String {
        val raw = rawInput.trim().ifBlank { DEFAULT_EMULATOR_ANALYSIS_HOST }

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

    private fun defaultHostForRuntime(): String {
        return if (isLikelyEmulator()) {
            DEFAULT_EMULATOR_ANALYSIS_HOST
        } else {
            DEFAULT_DEVICE_ANALYSIS_HOST
        }
    }

    private fun isLikelyEmulator(): Boolean {
        val fingerprint = Build.FINGERPRINT.lowercase()
        val model = Build.MODEL.lowercase()
        val product = Build.PRODUCT.lowercase()
        val hardware = Build.HARDWARE.lowercase()

        return fingerprint.startsWith("generic") ||
            fingerprint.contains("emulator") ||
            model.contains("emulator") ||
            model.contains("sdk_gphone") ||
            product.contains("sdk") ||
            hardware.contains("goldfish") ||
            hardware.contains("ranchu")
    }
}
