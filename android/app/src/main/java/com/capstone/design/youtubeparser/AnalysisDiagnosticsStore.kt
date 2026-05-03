package com.capstone.design.youtubeparser

import android.content.Context

object AnalysisDiagnosticsStore {

    private const val PREFS_NAME = "youtube_parser_settings"
    private const val KEY_ANALYZED_AT = "analysis_diagnostics_analyzed_at"
    private const val KEY_OK = "analysis_diagnostics_ok"
    private const val KEY_PACKAGE = "analysis_diagnostics_package"
    private const val KEY_URL = "analysis_diagnostics_url"
    private const val KEY_LATENCY_MS = "analysis_diagnostics_latency_ms"
    private const val KEY_COMMENT_COUNT = "analysis_diagnostics_comment_count"
    private const val KEY_OFFENSIVE_COUNT = "analysis_diagnostics_offensive_count"
    private const val KEY_FILTERED_COUNT = "analysis_diagnostics_filtered_count"
    private const val KEY_OVERLAY_CANDIDATE_COUNT = "analysis_diagnostics_overlay_candidate_count"
    private const val KEY_OVERLAY_RENDERED_COUNT = "analysis_diagnostics_overlay_rendered_count"
    private const val KEY_OVERLAY_SKIPPED_UNSTABLE_COUNT = "analysis_diagnostics_overlay_skipped_unstable_count"
    private const val KEY_VISUAL_CAPTURE_SUPPORTED = "analysis_diagnostics_visual_capture_supported"
    private const val KEY_VISUAL_CAPTURE_REASON = "analysis_diagnostics_visual_capture_reason"
    private const val KEY_ACTIONABLE_SAMPLES = "analysis_diagnostics_actionable_samples"
    private const val KEY_ERROR = "analysis_diagnostics_error"

    fun saveAttempt(context: Context, attempt: AndroidAnalysisAttempt) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putLong(KEY_ANALYZED_AT, System.currentTimeMillis())
            .putBoolean(KEY_OK, attempt.ok)
            .putString(KEY_PACKAGE, attempt.packageName.orEmpty())
            .putString(KEY_URL, attempt.url)
            .putLong(KEY_LATENCY_MS, attempt.latencyMs)
            .putInt(KEY_COMMENT_COUNT, attempt.commentCount)
            .putInt(KEY_OFFENSIVE_COUNT, attempt.offensiveCount)
            .putInt(KEY_FILTERED_COUNT, attempt.filteredCount)
            .putInt(KEY_OVERLAY_CANDIDATE_COUNT, attempt.overlayCandidateCount)
            .putInt(KEY_OVERLAY_RENDERED_COUNT, attempt.overlayRenderedCount)
            .putInt(KEY_OVERLAY_SKIPPED_UNSTABLE_COUNT, attempt.overlaySkippedUnstableCount)
            .putBoolean(KEY_VISUAL_CAPTURE_SUPPORTED, attempt.visualCaptureSupported)
            .putString(KEY_VISUAL_CAPTURE_REASON, attempt.visualCaptureReason)
            .putString(KEY_ACTIONABLE_SAMPLES, attempt.actionableSamples.joinToString("\n"))
            .putString(KEY_ERROR, attempt.error.orEmpty())
            .apply()
    }

    fun getLatest(context: Context): AndroidAnalysisDiagnostics? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val analyzedAt = prefs.getLong(KEY_ANALYZED_AT, 0L)
        if (analyzedAt <= 0L) return null

        return AndroidAnalysisDiagnostics(
            analyzedAt = analyzedAt,
            ok = prefs.getBoolean(KEY_OK, false),
            packageName = prefs.getString(KEY_PACKAGE, "").orEmpty().ifBlank { null },
            url = prefs.getString(KEY_URL, "").orEmpty(),
            latencyMs = prefs.getLong(KEY_LATENCY_MS, 0L),
            commentCount = prefs.getInt(KEY_COMMENT_COUNT, 0),
            offensiveCount = prefs.getInt(KEY_OFFENSIVE_COUNT, 0),
            filteredCount = prefs.getInt(KEY_FILTERED_COUNT, 0),
            overlayCandidateCount = prefs.getInt(KEY_OVERLAY_CANDIDATE_COUNT, 0),
            overlayRenderedCount = prefs.getInt(KEY_OVERLAY_RENDERED_COUNT, 0),
            overlaySkippedUnstableCount = prefs.getInt(KEY_OVERLAY_SKIPPED_UNSTABLE_COUNT, 0),
            visualCaptureSupported = prefs.getBoolean(KEY_VISUAL_CAPTURE_SUPPORTED, false),
            visualCaptureReason = prefs.getString(
                KEY_VISUAL_CAPTURE_REASON,
                VisualTextCaptureSupport.REASON_SERVICE_NOT_CONNECTED
            ).orEmpty(),
            actionableSamples = prefs.getString(KEY_ACTIONABLE_SAMPLES, "").orEmpty()
                .lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() },
            error = prefs.getString(KEY_ERROR, "").orEmpty().ifBlank { null }
        )
    }
}
