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
    val isVisibleToUser: Boolean
)

data class ParsedComment(
    val commentText: String,
    val boundsInScreen: BoundsRect,
    @SerializedName("author_id")
    val authorId: String? = null
)

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
    val url: String,
    val latencyMs: Long,
    val commentCount: Int,
    val offensiveCount: Int,
    val filteredCount: Int,
    val response: AndroidAnalysisResponse? = null,
    val error: String? = null
)

data class AndroidAnalysisDiagnostics(
    val analyzedAt: Long,
    val ok: Boolean,
    val url: String,
    val latencyMs: Long,
    val commentCount: Int,
    val offensiveCount: Int,
    val filteredCount: Int,
    val error: String?
)
