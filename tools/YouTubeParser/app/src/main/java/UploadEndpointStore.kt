package com.example.youtubeparser

import android.content.Context

object UploadEndpointStore {

    private const val PREFS_NAME = "youtube_parser_settings"
    private const val KEY_SERVER_INPUT = "server_input"
    private const val DEFAULT_SERVER_HOST = "100.95.209.72"
    private const val DEFAULT_SERVER_PATH = "/upload_youtube_parser"

    fun getRawInput(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_SERVER_INPUT, DEFAULT_SERVER_HOST).orEmpty()
    }

    fun saveRawInput(context: Context, value: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_SERVER_INPUT, value.trim()).apply()
    }

    fun resolveUploadUrl(context: Context): String {
        val rawInput = getRawInput(context).trim().ifBlank { DEFAULT_SERVER_HOST }

        if (rawInput.startsWith("http://") || rawInput.startsWith("https://")) {
            return appendDefaultPathIfNeeded(rawInput)
        }

        val normalized = rawInput.trimEnd('/')
        val baseUrl = if (normalized.contains(":")) {
            "http://$normalized"
        } else {
            "http://$normalized:5000"
        }

        return appendDefaultPathIfNeeded(baseUrl)
    }

    private fun appendDefaultPathIfNeeded(url: String): String {
        val normalized = url.trimEnd('/')
        return if (normalized.endsWith(DEFAULT_SERVER_PATH)) {
            normalized
        } else {
            "$normalized$DEFAULT_SERVER_PATH"
        }
    }
}
