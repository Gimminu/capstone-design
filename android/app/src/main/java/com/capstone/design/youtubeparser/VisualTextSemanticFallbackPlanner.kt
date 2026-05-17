package com.capstone.design.youtubeparser

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal object VisualTextSemanticFallbackPlanner {
    private const val THUMBNAIL_TERM_TOP_REGION_RATIO = 0.60f
    private const val THUMBNAIL_TERM_MIN_WIDTH_RATIO = 0.48f
    private const val SEMANTIC_TITLE_PREFIX_MAX_CHARS = 110
    private const val THUMBNAIL_TERM_TOP_RATIO = 0.40f
    private const val THUMBNAIL_TERM_HEIGHT_RATIO = 0.13f
    private const val THUMBNAIL_TERM_MIN_HEIGHT_PX = 64
    private const val THUMBNAIL_TERM_MAX_HEIGHT_PX = 128
    private const val THUMBNAIL_TERM_MIN_ROI_HEIGHT_PX = 480
    private const val THUMBNAIL_TERM_MAX_WIDTH_RATIO = 0.46f
    private const val THUMBNAIL_TERM_CENTER_X_RATIO = 0.50f
    private const val THUMBNAIL_TERM_MIN_PREFIX_CHARS = 3
    private const val SEMANTIC_FALLBACK_SOURCE = "youtube-semantic-card"

    fun selectCandidates(
        visualRoiPlan: VisualTextRoiPlan,
        screenWidth: Int,
        screenHeight: Int,
        baseResponse: AndroidAnalysisResponse? = null
    ): List<ParsedComment> {
        return visualRoiPlan.rois.flatMap { roi ->
            semanticThumbnailTermCandidates(
                roi = roi,
                screenWidth = screenWidth,
                screenHeight = screenHeight
            )
        }
    }

    private fun semanticThumbnailTermCandidates(
        roi: VisualTextRoi,
        screenWidth: Int,
        screenHeight: Int
    ): List<ParsedComment> {
        if (!isFullWidthThumbnailSemanticRoi(roi, screenWidth, screenHeight)) return emptyList()
        if (isPlaylistCompositeDescription(roi.sourceText)) return emptyList()

        val range = VisualTextOcrCandidateFilter.findAnalysisRanges(roi.sourceText)
            .firstOrNull { candidateRange ->
                semanticPrefixContentLength(roi.sourceText, candidateRange) >= THUMBNAIL_TERM_MIN_PREFIX_CHARS &&
                    isLikelyHeroTitleTextHit(roi.sourceText, candidateRange)
            }
            ?: return emptyList()

        val bounds = semanticThumbnailTermMaskBounds(
            roi = roi,
            visualText = range.visualText
        ) ?: return emptyList()

        return listOf(
            ParsedComment(
                commentText = range.analysisText,
                boundsInScreen = bounds,
                authorId = VisualTextOcrMetadataCodec.encode(
                    source = SEMANTIC_FALLBACK_SOURCE,
                    roiBoundsInScreen = roi.boundsInScreen,
                    visualText = range.visualText
                )
            )
        )
    }

    private fun isFullWidthThumbnailSemanticRoi(
        roi: VisualTextRoi,
        screenWidth: Int,
        screenHeight: Int
    ): Boolean {
        if (roi.source != "youtube-composite-card") return false

        val bounds = roi.boundsInScreen
        val width = bounds.right - bounds.left
        val height = bounds.bottom - bounds.top
        return bounds.top < (screenHeight * THUMBNAIL_TERM_TOP_REGION_RATIO).roundToInt() &&
            width >= (screenWidth * THUMBNAIL_TERM_MIN_WIDTH_RATIO).roundToInt() &&
            height >= THUMBNAIL_TERM_MIN_ROI_HEIGHT_PX
    }

    private fun isPlaylistCompositeDescription(sourceText: String): Boolean {
        val normalized = sourceText.replace(Regex("\\s+"), " ").trim()
        val lower = normalized.lowercase()
        return lower.startsWith("playlist - ") ||
            Regex("""(?:^|\s)-\s*\d+\s+videos?\b""", RegexOption.IGNORE_CASE).containsMatchIn(normalized)
    }

    private fun isLikelyHeroTitleTextHit(
        sourceText: String,
        range: VisualTextOcrCandidateFilter.CandidateRange
    ): Boolean {
        val before = sourceText.substring(0, range.start.coerceIn(0, sourceText.length))
        val compactPrefix = before
            .lowercase()
            .replace(Regex("""[\s"'`“”‘’.,!?_\-\[\]\(\):|]+"""), "")

        return compactPrefix.length <= 18 ||
            (compactPrefix.contains("playvideo") && compactPrefix.length <= 32) ||
            (compactPrefix.contains("동영상재생") && compactPrefix.length <= 24) ||
            (
                before.codePointCount(0, before.length) <= SEMANTIC_TITLE_PREFIX_MAX_CHARS &&
                    !looksLikeMetadataBeforeHeroTitle(before)
            )
    }

    private fun semanticPrefixContentLength(
        sourceText: String,
        range: VisualTextOcrCandidateFilter.CandidateRange
    ): Int {
        val before = sourceText.substring(0, range.start.coerceIn(0, sourceText.length))
        return before.count { char ->
            char.isLetterOrDigit() || char.code in 0xAC00..0xD7A3
        }
    }

    private fun looksLikeMetadataBeforeHeroTitle(text: String): Boolean {
        val lower = text.lowercase()
        return lower.contains(" views") ||
            lower.contains(" view") ||
            lower.contains("조회수") ||
            lower.contains("go to channel") ||
            Regex("""\b\d+(?:\.\d+)?\s*(?:k|m|b|thousand|million|billion)\s+views?\b""").containsMatchIn(lower) ||
            Regex("""\b\d+\s+(?:second|seconds|minute|minutes|hour|hours|day|days|week|weeks|month|months|year|years)\s+ago\b""").containsMatchIn(lower) ||
            Regex("""\d+\s*(?:초|분|시간|일|주|개월|년)\s*전""").containsMatchIn(text)
    }

    private fun semanticThumbnailTermMaskBounds(
        roi: VisualTextRoi,
        visualText: String
    ): BoundsRect? {
        val roiBounds = roi.boundsInScreen
        val roiWidth = roiBounds.right - roiBounds.left
        val roiHeight = roiBounds.bottom - roiBounds.top
        if (roiWidth <= 0 || roiHeight <= 0) return null

        val height = (roiHeight * THUMBNAIL_TERM_HEIGHT_RATIO)
            .roundToInt()
            .coerceIn(THUMBNAIL_TERM_MIN_HEIGHT_PX, THUMBNAIL_TERM_MAX_HEIGHT_PX)
        val maxWidth = (roiWidth * THUMBNAIL_TERM_MAX_WIDTH_RATIO)
            .roundToInt()
            .coerceAtLeast(96)
        val width = estimateSemanticThumbnailMaskWidth(
            visualText = visualText,
            textHeight = height,
            maxWidth = maxWidth
        )
        val centerX = roiBounds.left + (roiWidth * THUMBNAIL_TERM_CENTER_X_RATIO).roundToInt()
        val left = (centerX - width / 2).coerceIn(roiBounds.left, roiBounds.right - width)
        val top = (roiBounds.top + (roiHeight * THUMBNAIL_TERM_TOP_RATIO).roundToInt())
            .coerceIn(roiBounds.top, roiBounds.bottom - height)
        val right = min(roiBounds.right, left + width)
        val bottom = min(roiBounds.bottom, top + height)
        if (right - left < 24 || bottom - top < 16) return null

        return BoundsRect(
            left = left,
            top = top,
            right = right,
            bottom = bottom
        )
    }

    private fun estimateSemanticThumbnailMaskWidth(
        visualText: String,
        textHeight: Int,
        maxWidth: Int
    ): Int {
        val text = visualText.ifBlank { "tlqkf" }
        val length = text.codePointCount(0, text.length).coerceAtLeast(1)
        val hasKorean = text.any { it.code in 0xAC00..0xD7A3 }
        val charWidth = if (hasKorean) {
            max(34, (textHeight * 0.98f).roundToInt())
        } else {
            max(34, (textHeight * 0.92f).roundToInt())
        }
        return (length * charWidth + 28).coerceIn(112, max(112, maxWidth))
    }

}
