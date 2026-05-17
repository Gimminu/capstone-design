package com.capstone.design.youtubeparser

import com.google.gson.annotations.SerializedName

data class BoundsRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
)

data class ParsedTextNode(
    val packageName: String,
    val text: String?,
    val contentDescription: String?,
    val displayText: String?,
    val className: String?,
    val viewIdResourceName: String?,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val approxTop: Int,
    val isVisibleToUser: Boolean,
    val charBoxes: List<CharBox> = emptyList()
)

data class ParsedComment(
    val commentText: String,
    val boundsInScreen: BoundsRect,
    @SerializedName("author_id")
    val authorId: String? = null,
    @Transient val charBoxes: List<CharBox> = emptyList()
)

enum class CandidateSource {
    ACCESSIBILITY_TEXT,
    ACCESSIBILITY_TEXT_WITH_OCR_GEOMETRY,
    VISUAL_OCR
}

enum class CandidateRole {
    CONTENT,
    USER_INPUT,
    TITLE,
    SNIPPET,
    THUMBNAIL_TEXT,
    VIDEO_FRAME_TEXT,
    BUTTON_OR_NAVIGATION
}

enum class CandidateSurface {
    YOUTUBE_SEARCH_INPUT,
    YOUTUBE_TITLE,
    YOUTUBE_SHORTS_TITLE,
    YOUTUBE_COMMENT,
    YOUTUBE_VISUAL_TEXT,
    BROWSER_RESULT,
    GENERIC_TEXT,
    UNKNOWN
}

enum class CandidateGeometryPolicy {
    ACCESSIBILITY_EXACT,
    ACCESSIBILITY_ESTIMATED,
    ACCESSIBILITY_LOOKAHEAD,
    VISUAL_OCR_EXACT,
    VISUAL_FALLBACK,
    ANALYSIS_ONLY
}

enum class CandidateRenderPolicy {
    DIRECT_OVERLAY,
    CACHE_ONLY,
    OCR_REQUIRED,
    ANALYSIS_ONLY
}

data class CandidateRoute(
    val surface: CandidateSurface,
    val geometryPolicy: CandidateGeometryPolicy,
    val renderPolicy: CandidateRenderPolicy,
    val reason: String
) {
    fun summaryKey(): String {
        return "${surface.name.lowercase()}/${geometryPolicy.name.lowercase()}/${renderPolicy.name.lowercase()}"
    }
}

data class CharBox(
    val start: Int,
    val end: Int,
    val boundsInScreen: BoundsRect,
    val text: String? = null
)

data class ScreenTextCandidate(
    val id: String,
    val packageName: String,
    val source: CandidateSource,
    val role: CandidateRole,
    val rawText: String,
    val normalizedVariants: List<String> = emptyList(),
    val screenRect: BoundsRect,
    val charBoxes: List<CharBox>? = null,
    val confidence: Float? = null,
    val sceneRevision: Long = 0L,
    val captureId: String? = null,
    val roiId: String? = null,
    val backendSourceId: String? = null,
    val route: CandidateRoute = CandidateRoutingPolicy.routeFor(
        packageName = packageName,
        sourceId = backendSourceId,
        source = source,
        role = role
    )
) {
    fun toParsedComment(): ParsedComment {
        return ParsedComment(
            commentText = rawText,
            boundsInScreen = screenRect,
            authorId = backendSourceId ?: "screen:${source.name.lowercase()}:${role.name.lowercase()}"
        )
    }
}

data class ParseSnapshot(
    val timestamp: Long,
    val comments: List<ParsedComment>
)

data class EvidenceSpan(
    val text: String,
    val start: Int,
    val end: Int,
    val score: Double
)

data class HarmScores(
    val profanity: Double = 0.0,
    val toxicity: Double = 0.0,
    val hate: Double = 0.0
)

data class AndroidAnalysisResultItem(
    val original: String,
    val boundsInScreen: BoundsRect,
    @SerializedName("author_id")
    val authorId: String? = null,
    @SerializedName("is_offensive")
    val isOffensive: Boolean,
    @SerializedName("is_profane")
    val isProfane: Boolean,
    @SerializedName("is_toxic")
    val isToxic: Boolean,
    @SerializedName("is_hate")
    val isHate: Boolean,
    val scores: HarmScores,
    @SerializedName("evidence_spans")
    val evidenceSpans: List<EvidenceSpan>
)

data class AndroidAnalysisResponse(
    val timestamp: Long,
    @SerializedName("filtered_count")
    val filteredCount: Int,
    val results: List<AndroidAnalysisResultItem>
)

data class AndroidAnalysisAttempt(
    val ok: Boolean,
    val packageName: String? = null,
    val url: String,
    val sensitivity: Int? = null,
    val latencyMs: Long,
    val parseDelayMs: Long = -1L,
    val candidateExtractionMs: Long = -1L,
    val accessibilityMaskLatencyMs: Long = -1L,
    val backendMaskLatencyMs: Long = -1L,
    val visualOcrLatencyMs: Long = -1L,
    val visualMaskLatencyMs: Long = -1L,
    val commentCount: Int,
    val offensiveCount: Int,
    val filteredCount: Int,
    val overlayCandidateCount: Int = 0,
    val overlayRenderedCount: Int = 0,
    val overlaySkippedUnstableCount: Int = 0,
    val overlayRenderedSamples: List<String> = emptyList(),
    val visualCaptureSupported: Boolean = false,
    val visualCaptureReason: String = VisualTextCaptureSupport.REASON_SERVICE_NOT_CONNECTED,
    val visualRoiCandidateCount: Int = 0,
    val visualRoiSelectedCount: Int = 0,
    val visualOcrRawCount: Int = 0,
    val visualOcrSelectedCount: Int = 0,
    val candidateRouteSamples: List<String> = emptyList(),
    val response: AndroidAnalysisResponse? = null,
    val actionableSamples: List<String> = emptyList(),
    val error: String? = null
)

data class AndroidAnalysisDiagnostics(
    val analyzedAt: Long,
    val ok: Boolean,
    val packageName: String?,
    val url: String,
    val sensitivity: Int?,
    val latencyMs: Long,
    val parseDelayMs: Long,
    val candidateExtractionMs: Long,
    val accessibilityMaskLatencyMs: Long,
    val backendMaskLatencyMs: Long,
    val visualOcrLatencyMs: Long,
    val visualMaskLatencyMs: Long,
    val commentCount: Int,
    val offensiveCount: Int,
    val filteredCount: Int,
    val overlayCandidateCount: Int,
    val overlayRenderedCount: Int,
    val overlaySkippedUnstableCount: Int,
    val overlayRenderedSamples: List<String>,
    val visualCaptureSupported: Boolean,
    val visualCaptureReason: String,
    val visualRoiCandidateCount: Int,
    val visualRoiSelectedCount: Int,
    val visualOcrRawCount: Int,
    val visualOcrSelectedCount: Int,
    val candidateRouteSamples: List<String>,
    val actionableSamples: List<String>,
    val error: String?
)
