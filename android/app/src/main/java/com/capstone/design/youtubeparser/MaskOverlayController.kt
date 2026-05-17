package com.capstone.design.youtubeparser

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class MaskOverlaySpec(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
    val label: String,
    val allowScrollTranslation: Boolean = true,
    val debugSource: String = ""
)

data class MaskOverlayPlan(
    val specs: List<MaskOverlaySpec>,
    val candidateCount: Int,
    val skippedUnstableCount: Int,
    val suppressedOverlapCount: Int,
    val renderedSamples: List<String> = emptyList()
)

enum class MaskOverlayTranslationStatus {
    TRANSLATED,
    UNCHANGED,
    REJECTED_DELTA,
    NO_TRANSLATABLE_MASKS,
    ALL_OFFSCREEN
}

data class MaskOverlayTranslationPlan(
    val status: MaskOverlayTranslationStatus,
    val specs: List<MaskOverlaySpec>
)

object AndroidMaskOverlayPlanner {
    private const val MIN_WIDTH_PX = 24
    private const val MIN_HEIGHT_PX = 16
    private const val MIN_SPAN_MASK_WIDTH_PX = 24
    private const val SPAN_HORIZONTAL_PADDING_PX = 8
    private const val MAX_SPAN_MASK_HEIGHT_PX = 32
    private const val MAX_PRECISE_VISUAL_SPAN_MASK_HEIGHT_PX = 56
    private const val KOREAN_SPAN_CHAR_WIDTH_PX = 28
    private const val LATIN_SPAN_CHAR_WIDTH_PX = 16
    private const val KOREAN_SPAN_MAX_CHAR_WIDTH_PX = 28
    private const val LATIN_SPAN_MAX_CHAR_WIDTH_PX = 16
    private const val KOREAN_SPAN_HEIGHT_WIDTH_RATIO = 0.56f
    private const val LATIN_SPAN_HEIGHT_WIDTH_RATIO = 0.38f
    private const val PRECISE_VISUAL_KOREAN_WIDTH_RATIO = 0.86f
    private const val PRECISE_VISUAL_LATIN_WIDTH_RATIO = 0.76f
    private const val PRECISE_VISUAL_WIDTH_PADDING_PX = 10
    private const val TOP_HERO_DISPLAY_MASK_MAX_VERTICAL_RATIO = 0.40f
    private const val TOP_HERO_DISPLAY_MIN_ROI_WIDTH_PX = 640
    private const val TOP_HERO_DISPLAY_MASK_MIN_SOURCE_WIDTH_PX = 160
    private const val TOP_HERO_DISPLAY_MASK_MIN_SOURCE_HEIGHT_PX = 44
    private const val TOP_HERO_DISPLAY_TOP_ROI_MAX_RATIO = 0.16f
    private const val TOP_HERO_DISPLAY_TOP_ROI_MIN_SOURCE_WIDTH_PX = 140
    private const val TOP_HERO_DISPLAY_TOP_ROI_MIN_SOURCE_HEIGHT_PX = 32
    private const val TOP_HERO_DISPLAY_MASK_HEIGHT_MULTIPLIER = 2.2f
    private const val TOP_HERO_DISPLAY_MASK_TOP_OFFSET_RATIO = 0.45f
    private const val TOP_HERO_DISPLAY_MASK_MIN_HEIGHT_PX = 72
    private const val TOP_HERO_DISPLAY_MASK_MAX_HEIGHT_PX = 132
    private const val TOP_HERO_DISPLAY_CLIPPED_ROI_MAX_HEIGHT_PX = 320
    private const val TOP_HERO_DISPLAY_CLIPPED_ROI_MAX_HEIGHT_RATIO = 0.48f
    private const val TOP_HERO_DISPLAY_LATIN_WIDTH_RATIO = 0.50f
    private const val TOP_HERO_DISPLAY_KOREAN_WIDTH_RATIO = 0.72f
    private const val TOP_HERO_DISPLAY_WIDTH_PADDING_PX = 28
    private const val TOP_HERO_DISPLAY_LEFT_PADDING_PX = 32
    private const val TOP_HERO_DISPLAY_MAX_WIDTH_RATIO = 0.42f
    private const val MAX_COMPACT_KOREAN_SPAN_WIDTH_PX = 112
    private const val MAX_COMPACT_LATIN_SPAN_WIDTH_PX = 112
    private const val COMPACT_SPAN_CODEPOINT_LIMIT = 8
    private const val LEADING_SPAN_PREFIX_TOLERANCE = 2
    private const val ESTIMATED_LINE_HEIGHT_PX = 34
    private const val MAX_MASK_COUNT = 24
    private const val MAX_SCREEN_WIDTH_RATIO = 0.88f
    private const val MAX_SCREEN_HEIGHT_RATIO = 0.22f
    private const val MAX_HIGH_CONFIDENCE_HEIGHT_PX = 160
    private const val MAX_HIGH_CONFIDENCE_TEXT_LENGTH = 180
    private const val MAX_HIGH_CONFIDENCE_AREA_RATIO = 0.09f
    private const val MAX_ACCESSIBILITY_SOURCE_HEIGHT_PX = 360
    private const val MAX_ACCESSIBILITY_SOURCE_TEXT_LENGTH = 420
    private const val MAX_ACCESSIBILITY_SOURCE_AREA_RATIO = 0.14f
    private const val MAX_ESTIMATED_ACCESSIBILITY_TEXT_LENGTH = 96
    private const val MAX_ESTIMATED_ACCESSIBILITY_HEIGHT_PX = 96
    private const val MAX_ESTIMATED_ACCESSIBILITY_LINE_COUNT = 2
    private const val MAX_ESTIMATED_ACCESSIBILITY_WIDTH_RATIO = 0.78f
    private const val MAX_ACCESSIBILITY_RANGE_WIDTH_PX = 180
    private const val MAX_ACCESSIBILITY_RANGE_HEIGHT_PX = 64
    private const val MAX_COMMENT_ACCESSIBILITY_TEXT_LENGTH = 420
    private const val MAX_COMMENT_ACCESSIBILITY_HEIGHT_PX = 300
    private const val MAX_COMMENT_ACCESSIBILITY_LINE_COUNT = 8
    private const val MAX_COMMENT_ACCESSIBILITY_WIDTH_RATIO = 0.96f
    private const val MAX_UNSOURCED_LONG_TEXT_LENGTH = 70
    private const val MAX_UNSOURCED_LONG_TEXT_HEIGHT_PX = 72
    private const val MAX_VISUAL_SOURCE_HEIGHT_PX = 110
    private const val MAX_VISUAL_SOURCE_AREA_RATIO = 0.08f
    private const val MIN_LARGE_PRECISE_VISUAL_SOURCE_HEIGHT_PX = 108
    private const val MAX_LARGE_PRECISE_VISUAL_SOURCE_HEIGHT_PX = 360
    private const val MAX_LARGE_PRECISE_VISUAL_SOURCE_AREA_RATIO = 0.08f
    private const val MAX_LARGE_PRECISE_VISUAL_TERM_LENGTH = 8
    private const val LARGE_PRECISE_VISUAL_HORIZONTAL_PADDING_PX = 18
    private const val LARGE_PRECISE_VISUAL_VERTICAL_PADDING_PX = 12
    private const val MAX_COMPOSITE_SOURCE_HEIGHT_PX = 132
    private const val MAX_COMPOSITE_SOURCE_AREA_RATIO = 0.06f
    private const val MAX_SEMANTIC_SOURCE_HEIGHT_PX = 140
    private const val MAX_SEMANTIC_SOURCE_WIDTH_RATIO = 0.55f
    private const val MAX_SEMANTIC_SOURCE_AREA_RATIO = 0.04f
    private const val SEMANTIC_BOUNDS_SLOP_PX = 4
    private const val NEAR_DUPLICATE_OVERLAP_RATIO = 0.25f
    private const val PRESERVED_VISUAL_OVERLAP_RATIO = 0.72f
    private const val MIN_SCROLL_TRANSLATION_DELTA_PX = 96
    private const val MAX_SCROLL_TRANSLATION_AXIS_RATIO = 0.25f
    private const val TOP_CONTROL_REGION_RATIO = 0.14f
    private const val TOP_CONTROL_REGION_MAX_PX = 220
    private const val TOP_GENERIC_VISUAL_CONTROL_REGION_RATIO = 0.26f
    private const val TOP_GENERIC_VISUAL_CONTROL_REGION_MAX_PX = 360
    private const val TOP_USER_INPUT_REGION_RATIO = 0.24f
    private const val TOP_USER_INPUT_REGION_MAX_PX = 360
    private const val YOUTUBE_USER_INPUT_AUTHOR_ID = "android-accessibility:youtube_user_input"
    private const val YOUTUBE_INPUT_TEXT_INSET_PX = 8
    private const val YOUTUBE_INPUT_WIDTH_PADDING_PX = 48
    private const val YOUTUBE_INPUT_MIN_MASK_WIDTH_PX = 156
    private const val YOUTUBE_INPUT_MAX_MASK_WIDTH_RATIO = 0.72f
    private const val YOUTUBE_INPUT_LATIN_CHAR_WIDTH_PX = 28
    private const val YOUTUBE_INPUT_KOREAN_CHAR_WIDTH_PX = 38
    private const val TOP_SEARCH_FALLBACK_MAX_TOP_PX = 150
    private const val TOP_SEARCH_FALLBACK_MIN_WIDTH_PX = 180
    private const val TOP_SEARCH_FALLBACK_MAX_WIDTH_PX = 480
    private const val TOP_SEARCH_FALLBACK_MIN_HEIGHT_PX = 36
    private const val TOP_SEARCH_FALLBACK_MAX_HEIGHT_PX = 96
    private const val TOP_SEARCH_FALLBACK_MAX_TEXT_LENGTH = 14
    private const val YOUTUBE_TITLE_ACCESSIBILITY_AUTHOR_ID = "android-accessibility:youtube_title"
    private const val YOUTUBE_SHORTS_TITLE_ACCESSIBILITY_AUTHOR_ID = "android-accessibility:youtube_shorts_title"
    private const val ACCESSIBILITY_COMMENT_PREFIX = "android-accessibility-comment:"
    private const val ACCESSIBILITY_LOOKAHEAD_PREFIX = "android-accessibility-lookahead:"
    private const val MAX_YOUTUBE_TITLE_ACCESSIBILITY_HEIGHT_PX = 148
    private const val MAX_YOUTUBE_TITLE_ACCESSIBILITY_TEXT_LENGTH = 180
    private const val MAX_YOUTUBE_TITLE_ACCESSIBILITY_LINE_COUNT = 4
    private const val MAX_YOUTUBE_TITLE_ACCESSIBILITY_WIDTH_RATIO = 0.90f
    private const val YOUTUBE_TITLE_LATIN_CHAR_WIDTH_PX = 18
    private const val YOUTUBE_TITLE_KOREAN_CHAR_WIDTH_PX = 28

    fun buildSpecs(
        response: AndroidAnalysisResponse?,
        screenWidth: Int,
        screenHeight: Int
    ): List<MaskOverlaySpec> {
        return buildPlan(response, screenWidth, screenHeight).specs
    }

    fun buildPlan(
        response: AndroidAnalysisResponse?,
        screenWidth: Int,
        screenHeight: Int
    ): MaskOverlayPlan {
        if (response == null || screenWidth <= 0 || screenHeight <= 0) {
            return MaskOverlayPlan(
                specs = emptyList(),
                candidateCount = 0,
                skippedUnstableCount = 0,
                suppressedOverlapCount = 0
            )
        }

        var candidateCount = 0
        var skippedUnstableCount = 0
        val rawSpecs = mutableListOf<MaskOverlaySpec>()

        val renderableResults = response.results.filterNot { item ->
            isSupersededYoutubeTitleAccessibility(item, response.results)
        }

        renderableResults
            .asSequence()
            .filter { it.isOffensive && it.evidenceSpans.isNotEmpty() }
            .forEach { item ->
                candidateCount += 1
                val specs = toSpecs(item, screenWidth, screenHeight)
                if (specs.isEmpty()) {
                    skippedUnstableCount += 1
                } else {
                    rawSpecs += specs
                }
            }

        val suppressedSpecs = suppressOverlappingSpecs(rawSpecs)
        val finalSpecs = suppressedSpecs.take(MAX_MASK_COUNT)

        return MaskOverlayPlan(
            specs = finalSpecs,
            candidateCount = candidateCount,
            skippedUnstableCount = skippedUnstableCount,
            suppressedOverlapCount = (rawSpecs.size - finalSpecs.size).coerceAtLeast(0),
            renderedSamples = finalSpecs.mapNotNull { spec ->
                spec.debugSource.takeIf { it.isNotBlank() }
            }.take(6)
        )
    }

    private fun isSupersededYoutubeTitleAccessibility(
        item: AndroidAnalysisResultItem,
        allItems: List<AndroidAnalysisResultItem>
    ): Boolean {
        if (!isYoutubeTitleAccessibilityAuthor(item.authorId)) return false

        val itemKeys = overlaySpanKeys(item)
        if (itemKeys.isEmpty()) return false

        return allItems.any { other ->
            if (other === item) return@any false
            val metadata = VisualTextOcrMetadataCodec.decode(other.authorId) ?: return@any false
            val roiBounds = metadata.roiBoundsInScreen ?: return@any false
            if (metadata.source != "youtube-composite-card" && metadata.source != "youtube-visible-band") {
                return@any false
            }
            if (!containsBounds(roiBounds, item.boundsInScreen)) return@any false

            val otherKeys = overlaySpanKeys(other)
            otherKeys.any { key -> key in itemKeys }
        }
    }

    private fun overlaySpanKeys(item: AndroidAnalysisResultItem): Set<String> {
        return item.evidenceSpans
            .map { span -> visualSizingKey(span.text) }
            .filter { key -> key.isNotBlank() }
            .toSet()
    }

    private fun containsBounds(outer: BoundsRect, inner: BoundsRect): Boolean {
        return inner.left >= outer.left &&
            inner.top >= outer.top &&
            inner.right <= outer.right &&
            inner.bottom <= outer.bottom
    }

    fun signature(specs: List<MaskOverlaySpec>): String {
        return specs.joinToString("|") {
            "${it.left},${it.top},${it.width},${it.height},${it.label},${it.allowScrollTranslation}"
        }
    }

    fun mergeWithPreservedPreciseVisualSpecs(
        newSpecs: List<MaskOverlaySpec>,
        existingSpecs: List<MaskOverlaySpec>,
        screenWidth: Int,
        screenHeight: Int
    ): List<MaskOverlaySpec> {
        if (existingSpecs.isEmpty() || screenWidth <= 0 || screenHeight <= 0) return newSpecs

        val preserved = existingSpecs.filter { spec ->
            isPreservablePreciseVisualSpec(spec) &&
                isPartiallyOnScreen(spec, screenWidth, screenHeight) &&
                newSpecs.none { next -> overlapRatio(spec, next) >= PRESERVED_VISUAL_OVERLAP_RATIO }
        }
        if (preserved.isEmpty()) return newSpecs

        return suppressOverlappingSpecs(newSpecs + preserved).take(MAX_MASK_COUNT)
    }

    fun translateSpecs(
        specs: List<MaskOverlaySpec>,
        deltaX: Int,
        deltaY: Int,
        screenWidth: Int,
        screenHeight: Int
    ): List<MaskOverlaySpec> {
        return translatePlan(
            specs = specs,
            deltaX = deltaX,
            deltaY = deltaY,
            screenWidth = screenWidth,
            screenHeight = screenHeight
        ).specs
    }

    fun translatePlan(
        specs: List<MaskOverlaySpec>,
        deltaX: Int,
        deltaY: Int,
        screenWidth: Int,
        screenHeight: Int
    ): MaskOverlayTranslationPlan {
        if (specs.isEmpty() || screenWidth <= 0 || screenHeight <= 0) {
            return MaskOverlayTranslationPlan(
                status = MaskOverlayTranslationStatus.ALL_OFFSCREEN,
                specs = emptyList()
            )
        }
        if (deltaX == 0 && deltaY == 0) {
            return MaskOverlayTranslationPlan(
                status = MaskOverlayTranslationStatus.UNCHANGED,
                specs = specs
            )
        }
        val maxDeltaX = maxScrollTranslationDeltaPx(screenWidth)
        val maxDeltaY = maxScrollTranslationDeltaPx(screenHeight)
        if (abs(deltaX) > maxDeltaX || abs(deltaY) > maxDeltaY) {
            return MaskOverlayTranslationPlan(
                status = MaskOverlayTranslationStatus.REJECTED_DELTA,
                specs = emptyList()
            )
        }

        val translatableSpecs = specs.filter { spec -> spec.allowScrollTranslation }
        if (translatableSpecs.isEmpty()) {
            return MaskOverlayTranslationPlan(
                status = MaskOverlayTranslationStatus.NO_TRANSLATABLE_MASKS,
                specs = emptyList()
            )
        }

        val translatedSpecs = translatableSpecs.mapNotNull { spec ->
            val nextLeft = spec.left + deltaX
            val nextTop = spec.top + deltaY
            val nextRight = nextLeft + spec.width
            val nextBottom = nextTop + spec.height

            if (
                nextRight <= 0 ||
                nextBottom <= 0 ||
                nextLeft >= screenWidth ||
                nextTop >= screenHeight
            ) {
                null
            } else {
                spec.copy(left = nextLeft, top = nextTop)
            }
        }
        return MaskOverlayTranslationPlan(
            status = if (translatedSpecs.isEmpty()) {
                MaskOverlayTranslationStatus.ALL_OFFSCREEN
            } else {
                MaskOverlayTranslationStatus.TRANSLATED
            },
            specs = translatedSpecs
        )
    }

    private fun toSpecs(
        item: AndroidAnalysisResultItem,
        screenWidth: Int,
        screenHeight: Int
    ): List<MaskOverlaySpec> {
        val fullSpec = toSpec(item.boundsInScreen, screenWidth, screenHeight) ?: return emptyList()
        val originalLength = item.original.codePointCount(0, item.original.length)
        if (originalLength <= 0) return emptyList()
        if (!hasHighConfidenceTextBounds(
                spec = fullSpec,
                originalLength = originalLength,
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                authorId = item.authorId
            )
        ) {
            return emptyList()
        }

        val allowScrollTranslation = shouldAllowScrollTranslation(item.authorId)
        val visualMetadata = if (isPreciseVisualAuthor(item.authorId)) {
            VisualTextOcrMetadataCodec.decode(item.authorId)
        } else {
            null
        }
        val preciseVisualBounds = visualMetadata != null
        val visualTextForSizing = visualMetadata?.visualText
        val debugSource = buildDebugSource(item)
        val spanSpecs = item.evidenceSpans.mapNotNull { span ->
            toSpanSpec(
                fullSpec = fullSpec,
                span = span,
                original = item.original,
                originalLength = originalLength,
                authorId = item.authorId,
                allowScrollTranslation = allowScrollTranslation,
                preciseVisualBounds = preciseVisualBounds,
                visualMetadata = visualMetadata,
                visualTextForSizing = visualTextForSizing,
                screenHeight = screenHeight,
                debugSource = debugSource
            )
        }

        return spanSpecs
    }

    private fun shouldAllowScrollTranslation(authorId: String?): Boolean {
        // Only explicit input fields and exact OCR boxes are stable enough to
        // translate. Coarse accessibility rows still get dropped and reanalyzed.
        val value = authorId ?: return false
        return value == "android-accessibility:user_input" ||
            value == YOUTUBE_USER_INPUT_AUTHOR_ID ||
            value == YOUTUBE_TITLE_ACCESSIBILITY_AUTHOR_ID ||
            value == YOUTUBE_SHORTS_TITLE_ACCESSIBILITY_AUTHOR_ID ||
            isCommentAccessibilityAuthor(value) ||
            isPreciseVisualAuthor(value)
    }

    private fun isPreservablePreciseVisualSpec(spec: MaskOverlaySpec): Boolean {
        return spec.debugSource.startsWith("ocr:youtube-composite-card:") ||
            spec.debugSource.startsWith("ocr:youtube-visible-band:")
    }

    private fun isPartiallyOnScreen(
        spec: MaskOverlaySpec,
        screenWidth: Int,
        screenHeight: Int
    ): Boolean {
        return spec.left + spec.width > 0 &&
            spec.top + spec.height > 0 &&
            spec.left < screenWidth &&
            spec.top < screenHeight
    }

    private fun hasHighConfidenceTextBounds(
        spec: MaskOverlaySpec,
        originalLength: Int,
        screenWidth: Int,
        screenHeight: Int,
        authorId: String?
    ): Boolean {
        if (isLookaheadAccessibilityAuthor(authorId)) {
            return false
        }
        val accessibilityAuthor = isAccessibilityAuthor(authorId)
        val preciseVisualAuthor = isPreciseVisualAuthor(authorId)
        if (originalLength > MAX_HIGH_CONFIDENCE_TEXT_LENGTH && !accessibilityAuthor) return false
        if (spec.height > MAX_HIGH_CONFIDENCE_HEIGHT_PX && !accessibilityAuthor && !preciseVisualAuthor) {
            return false
        }

        val screenArea = (screenWidth * screenHeight).coerceAtLeast(1)
        val specArea = spec.width * spec.height
        val areaRatio = specArea.toFloat() / screenArea.toFloat()

        if (!accessibilityAuthor && areaRatio > MAX_HIGH_CONFIDENCE_AREA_RATIO) {
            return false
        }

        if (isFallbackVisualAuthor(authorId)) {
            return false
        }

        if (isTopControlMask(spec, screenWidth, screenHeight, authorId)) {
            return false
        }

        if (preciseVisualAuthor) {
            if (spec.height <= MAX_VISUAL_SOURCE_HEIGHT_PX &&
                areaRatio <= MAX_VISUAL_SOURCE_AREA_RATIO
            ) {
                return true
            }
            return hasStableLargePreciseVisualGeometry(
                spec = spec,
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                areaRatio = areaRatio,
                authorId = authorId
            )
        }

        if (isSemanticVisualAuthor(authorId)) {
            return false
        }

        if (isCompositeYoutubeAuthor(authorId)) {
            return spec.height <= MAX_COMPOSITE_SOURCE_HEIGHT_PX &&
                areaRatio <= MAX_COMPOSITE_SOURCE_AREA_RATIO
        }

        if (accessibilityAuthor) {
            if (!hasStableAccessibilityGeometry(spec, originalLength, screenWidth, authorId)) {
                return false
            }
            return originalLength <= MAX_ACCESSIBILITY_SOURCE_TEXT_LENGTH &&
                spec.height <= MAX_ACCESSIBILITY_SOURCE_HEIGHT_PX &&
                areaRatio <= MAX_ACCESSIBILITY_SOURCE_AREA_RATIO
        }

        if (originalLength > MAX_UNSOURCED_LONG_TEXT_LENGTH &&
            spec.height > MAX_UNSOURCED_LONG_TEXT_HEIGHT_PX
        ) {
            return false
        }

        return true
    }

    private fun hasStableAccessibilityGeometry(
        spec: MaskOverlaySpec,
        originalLength: Int,
        screenWidth: Int,
        authorId: String?
    ): Boolean {
        if (authorId == "android-accessibility:user_input" ||
            authorId == YOUTUBE_USER_INPUT_AUTHOR_ID
        ) {
            return true
        }
        if (authorId == YOUTUBE_TITLE_ACCESSIBILITY_AUTHOR_ID) {
            return hasStableYoutubeTitleGeometry(spec, originalLength, screenWidth)
        }
        if (authorId == YOUTUBE_SHORTS_TITLE_ACCESSIBILITY_AUTHOR_ID) {
            return hasStableYoutubeTitleGeometry(spec, originalLength, screenWidth)
        }
        if (isAccessibilityRangeAuthor(authorId)) {
            return spec.width <= MAX_ACCESSIBILITY_RANGE_WIDTH_PX &&
                spec.height <= MAX_ACCESSIBILITY_RANGE_HEIGHT_PX
        }
        if (isCommentAccessibilityAuthor(authorId)) {
            return hasStableCommentAccessibilityGeometry(
                spec = spec,
                originalLength = originalLength,
                screenWidth = screenWidth
            )
        }
        if (isBrowserAccessibilityAuthor(authorId)) {
            // Browser accessibility nodes are reliable context, not reliable word geometry.
            // Chrome/Firefox often expose row, snippet, or card bounds as a short text node,
            // which caused floating masks on scroll. Keep these candidates analysis-only until
            // OCR/range projection can provide an exact visual box.
            return false
        }
        if (isGenericScreenAccessibilityAuthor(authorId)) {
            // ScreenTextCandidateExtractor emits this generic source for cross-app
            // context collection. The bounds can be a row/card/container instead
            // of a word box, so rendering it directly creates detached floating
            // masks. Keep it analysis-only unless a precise range/OCR source exists.
            return false
        }

        val estimatedLineCount = estimateLineCount(spec.height, originalLength)
        if (estimatedLineCount > MAX_ESTIMATED_ACCESSIBILITY_LINE_COUNT) {
            return false
        }
        if (
            originalLength > MAX_ESTIMATED_ACCESSIBILITY_TEXT_LENGTH &&
            spec.height > MAX_ESTIMATED_ACCESSIBILITY_HEIGHT_PX
        ) {
            return false
        }
        if (
            spec.width > (screenWidth * MAX_ESTIMATED_ACCESSIBILITY_WIDTH_RATIO).roundToInt() &&
            spec.height > MAX_SPAN_MASK_HEIGHT_PX &&
            originalLength > MAX_UNSOURCED_LONG_TEXT_LENGTH
        ) {
            return false
        }

        return true
    }

    private fun hasStableYoutubeTitleGeometry(
        spec: MaskOverlaySpec,
        originalLength: Int,
        screenWidth: Int
    ): Boolean {
        if (originalLength > MAX_YOUTUBE_TITLE_ACCESSIBILITY_TEXT_LENGTH) return false
        if (spec.height > MAX_YOUTUBE_TITLE_ACCESSIBILITY_HEIGHT_PX) return false
        if (spec.width > (screenWidth * MAX_YOUTUBE_TITLE_ACCESSIBILITY_WIDTH_RATIO).roundToInt()) return false

        val estimatedLineCount = estimateLineCount(spec.height, originalLength)
        return estimatedLineCount <= MAX_YOUTUBE_TITLE_ACCESSIBILITY_LINE_COUNT
    }

    private fun hasStableCommentAccessibilityGeometry(
        spec: MaskOverlaySpec,
        originalLength: Int,
        screenWidth: Int
    ): Boolean {
        if (originalLength > MAX_COMMENT_ACCESSIBILITY_TEXT_LENGTH) return false
        if (spec.height > MAX_COMMENT_ACCESSIBILITY_HEIGHT_PX) return false
        if (spec.width > (screenWidth * MAX_COMMENT_ACCESSIBILITY_WIDTH_RATIO).roundToInt()) return false

        val estimatedLineCount = estimateLineCount(spec.height, originalLength)
        return estimatedLineCount <= MAX_COMMENT_ACCESSIBILITY_LINE_COUNT
    }

    private fun isYoutubeTitleAccessibilityAuthor(authorId: String?): Boolean {
        return authorId == YOUTUBE_TITLE_ACCESSIBILITY_AUTHOR_ID ||
            authorId == YOUTUBE_SHORTS_TITLE_ACCESSIBILITY_AUTHOR_ID
    }

    private fun isPreciseVisualAuthor(authorId: String?): Boolean {
        val value = authorId ?: return false
        return value.startsWith("ocr:youtube-composite-card:") ||
            value.startsWith("ocr:youtube-visible-band:")
    }

    private fun isSemanticVisualAuthor(authorId: String?): Boolean {
        return authorId?.startsWith("ocr:youtube-semantic-card:") == true
    }

    private fun isFallbackVisualAuthor(authorId: String?): Boolean {
        val value = authorId ?: return false
        return (value.startsWith("ocr:") && !isPreciseVisualAuthor(value) && !isSemanticVisualAuthor(value)) ||
            value.startsWith("youtube-visual-range:")
    }

    private fun isGenericVisualAuthor(authorId: String?): Boolean {
        return authorId?.startsWith("ocr:generic-visual-region:") == true
    }

    private fun isCompositeYoutubeAuthor(authorId: String?): Boolean {
        return authorId == "youtube-composite-description"
    }

    private fun isAccessibilityAuthor(authorId: String?): Boolean {
        val value = authorId ?: return false
        return value.startsWith("android-accessibility:") ||
            value.startsWith("android-accessibility-range:") ||
            value.startsWith("android-accessibility-browser:") ||
            value.startsWith(ACCESSIBILITY_COMMENT_PREFIX) ||
            value.startsWith("screen:accessibility_text:")
    }

    private fun isLookaheadAccessibilityAuthor(authorId: String?): Boolean {
        return authorId?.startsWith(ACCESSIBILITY_LOOKAHEAD_PREFIX) == true
    }

    private fun isAccessibilityRangeAuthor(authorId: String?): Boolean {
        return authorId?.startsWith("android-accessibility-range:") == true
    }

    private fun isCommentAccessibilityAuthor(authorId: String?): Boolean {
        return authorId?.startsWith(ACCESSIBILITY_COMMENT_PREFIX) == true
    }

    private fun isBrowserAccessibilityAuthor(authorId: String?): Boolean {
        return authorId?.startsWith("android-accessibility-browser:") == true
    }

    private fun isGenericScreenAccessibilityAuthor(authorId: String?): Boolean {
        return authorId?.startsWith("screen:accessibility_text:") == true
    }

    private fun hasStableSemanticVisualGeometry(
        spec: MaskOverlaySpec,
        screenWidth: Int,
        areaRatio: Float,
        authorId: String?
    ): Boolean {
        val metadata = VisualTextOcrMetadataCodec.decode(authorId) ?: return false
        val roiBounds = metadata.roiBoundsInScreen ?: return false
        if (metadata.source != "youtube-semantic-card") return false
        if (!containsWithSlop(roiBounds, spec, SEMANTIC_BOUNDS_SLOP_PX)) return false

        return spec.height <= MAX_SEMANTIC_SOURCE_HEIGHT_PX &&
            spec.width <= (screenWidth * MAX_SEMANTIC_SOURCE_WIDTH_RATIO).roundToInt() &&
            areaRatio <= MAX_SEMANTIC_SOURCE_AREA_RATIO
    }

    private fun hasStableLargePreciseVisualGeometry(
        spec: MaskOverlaySpec,
        screenWidth: Int,
        screenHeight: Int,
        areaRatio: Float,
        authorId: String?
    ): Boolean {
        val metadata = VisualTextOcrMetadataCodec.decode(authorId) ?: return false
        if (metadata.source != "youtube-composite-card" && metadata.source != "youtube-visible-band") {
            return false
        }
        val visualText = metadata.visualText?.trim().orEmpty()
        val visualKey = visualSizingKey(visualText)
        if (visualKey.length !in 3..MAX_LARGE_PRECISE_VISUAL_TERM_LENGTH) return false

        val roiBounds = metadata.roiBoundsInScreen ?: return false
        if (!containsBounds(roiBounds, BoundsRect(spec.left, spec.top, spec.left + spec.width, spec.top + spec.height))) {
            return false
        }

        val roiWidth = roiBounds.right - roiBounds.left
        val roiHeight = roiBounds.bottom - roiBounds.top
        if (roiWidth < (screenWidth * 0.48f).roundToInt()) return false
        if (roiHeight < MIN_LARGE_PRECISE_VISUAL_SOURCE_HEIGHT_PX) return false

        val maxSourceHeight = min(
            MAX_LARGE_PRECISE_VISUAL_SOURCE_HEIGHT_PX,
            (screenHeight * 0.26f).roundToInt().coerceAtLeast(MIN_LARGE_PRECISE_VISUAL_SOURCE_HEIGHT_PX)
        )
        return spec.height in MIN_LARGE_PRECISE_VISUAL_SOURCE_HEIGHT_PX..maxSourceHeight &&
            areaRatio <= MAX_LARGE_PRECISE_VISUAL_SOURCE_AREA_RATIO
    }

    private fun containsWithSlop(bounds: BoundsRect, spec: MaskOverlaySpec, slopPx: Int): Boolean {
        return spec.left >= bounds.left - slopPx &&
            spec.top >= bounds.top - slopPx &&
            spec.left + spec.width <= bounds.right + slopPx &&
            spec.top + spec.height <= bounds.bottom + slopPx
    }

    private fun isTopControlMask(
        spec: MaskOverlaySpec,
        screenWidth: Int,
        screenHeight: Int,
        authorId: String?
    ): Boolean {
        val value = authorId ?: return false

        val isEstimatedMask = isAccessibilityAuthor(value) ||
            isPreciseVisualAuthor(value) ||
            isSemanticVisualAuthor(value)
        if (!isEstimatedMask) return false

        val trustedVisibleBandOcr = VisualTextGeometryPolicy.isTrustedVisibleBandOcr(
            authorId = value,
            left = spec.left,
            top = spec.top,
            right = spec.left + spec.width,
            bottom = spec.top + spec.height
        )
        val preciseVisualContentNearTop = isPreciseVisualAuthor(value) &&
            (VisualTextGeometryPolicy.isTopHeroYoutubeComposite(value, screenWidth) ||
                trustedVisibleBandOcr)
        if (preciseVisualContentNearTop) {
            return false
        }

        if (value == YOUTUBE_USER_INPUT_AUTHOR_ID) {
            return false
        }

        if (isCommentAccessibilityAuthor(value)) {
            return false
        }

        if (value == "android-accessibility:user_input") {
            val inputCutoff = min(
                TOP_USER_INPUT_REGION_MAX_PX,
                (screenHeight * TOP_USER_INPUT_REGION_RATIO).roundToInt()
            )
            return spec.top < inputCutoff
        }

        if (isGenericVisualAuthor(value)) {
            val genericVisualCutoff = min(
                TOP_GENERIC_VISUAL_CONTROL_REGION_MAX_PX,
                (screenHeight * TOP_GENERIC_VISUAL_CONTROL_REGION_RATIO).roundToInt()
            )
            return spec.top < genericVisualCutoff
        }

        val cutoff = min(TOP_CONTROL_REGION_MAX_PX, (screenHeight * TOP_CONTROL_REGION_RATIO).roundToInt())
        return spec.top < cutoff
    }

    private fun toSpec(bounds: BoundsRect, screenWidth: Int, screenHeight: Int): MaskOverlaySpec? {
        val left = max(0, min(bounds.left, screenWidth))
        val top = max(0, min(bounds.top, screenHeight))
        val right = max(left, min(bounds.right, screenWidth))
        val bottom = max(top, min(bounds.bottom, screenHeight))
        val width = right - left
        val height = bottom - top

        if (width < MIN_WIDTH_PX || height < MIN_HEIGHT_PX) {
            return null
        }
        if (
            width >= (screenWidth * MAX_SCREEN_WIDTH_RATIO).toInt() &&
            height >= (screenHeight * MAX_SCREEN_HEIGHT_RATIO).toInt()
        ) {
            return null
        }

        return MaskOverlaySpec(
            left = left,
            top = top,
            width = width,
            height = height,
            label = MASK_LABEL
        )
    }

    private fun toSpanSpec(
        fullSpec: MaskOverlaySpec,
        span: EvidenceSpan,
        original: String,
        originalLength: Int,
        authorId: String?,
        allowScrollTranslation: Boolean,
        preciseVisualBounds: Boolean,
        visualMetadata: VisualTextOcrMetadata?,
        visualTextForSizing: String?,
        screenHeight: Int,
        debugSource: String
    ): MaskOverlaySpec? {
        val resolvedRange = resolveSpanRange(
            original = original,
            span = span,
            originalLength = originalLength
        ) ?: return null
        val start = resolvedRange.first
        val end = resolvedRange.second
        if (end <= start) return null

        if (isYoutubeTitleAccessibilityAuthor(authorId)) {
            return toYoutubeTitleAccessibilitySpanSpec(
                fullSpec = fullSpec,
                spanText = span.text,
                start = start,
                end = end,
                original = original,
                originalLength = originalLength,
                allowScrollTranslation = allowScrollTranslation,
                debugSource = debugSource
            )
        }

        val lineCount = estimateLineCount(fullSpec.height, originalLength)
        val lineHeight = (fullSpec.height / lineCount).coerceAtLeast(MIN_HEIGHT_PX)

        if (shouldUseYoutubeInputGeometryFallback(
                fullSpec = fullSpec,
                original = original,
                start = start,
                end = end,
                originalLength = originalLength,
                authorId = authorId
            )
        ) {
            return toYoutubeInputSpanSpec(
                fullSpec = fullSpec,
                spanText = span.text,
                lineHeight = lineHeight,
                allowScrollTranslation = allowScrollTranslation,
                debugSource = debugSource
            )
        }

        if (isSemanticVisualAuthor(authorId) && isWholeTextSpan(start, end, originalLength)) {
            return fullSpec.copy(
                allowScrollTranslation = allowScrollTranslation,
                debugSource = debugSource
            )
        }

        if (preciseVisualBounds && isWholeTextSpan(start, end, originalLength)) {
            if (shouldUseLargePreciseVisualGeometry(fullSpec, visualMetadata, span.text, screenHeight)) {
                return toLargePreciseVisualSpanSpec(
                    fullSpec = fullSpec,
                    visualMetadata = visualMetadata,
                    allowScrollTranslation = allowScrollTranslation,
                    debugSource = debugSource
                )
            }

            if (shouldUseTopHeroDisplayGeometry(fullSpec, visualMetadata, span.text, screenHeight)) {
                return toTopHeroDisplayTextSpanSpec(
                    fullSpec = fullSpec,
                    spanText = visualTextSizingOverride(
                        spanText = span.text,
                        visualText = visualTextForSizing
                    ),
                    visualMetadata = visualMetadata,
                    allowScrollTranslation = allowScrollTranslation,
                    screenHeight = screenHeight,
                    debugSource = debugSource
                )
            }

            return toPreciseVisualSpanSpec(
                fullSpec = fullSpec,
                spanText = visualTextSizingOverride(
                    spanText = span.text,
                    visualText = visualTextForSizing
                ),
                lineHeight = lineHeight,
                allowScrollTranslation = allowScrollTranslation,
                debugSource = debugSource
            )
        }

        val charsPerLine = ((originalLength + lineCount - 1) / lineCount).coerceAtLeast(1)
        val lineIndex = (start / charsPerLine).coerceIn(0, lineCount - 1)
        val lineStart = lineIndex * charsPerLine
        val lineEnd = min(originalLength, lineStart + charsPerLine).coerceAtLeast(lineStart + 1)
        val lineLength = (lineEnd - lineStart).coerceAtLeast(1)
        val localStart = (start - lineStart).coerceIn(0, lineLength)
        val localEnd = (end - lineStart).coerceIn(localStart + 1, lineLength)

        val startRatio = localStart.toFloat() / lineLength.toFloat()
        val endRatio = localEnd.toFloat() / lineLength.toFloat()
        val rawLeft = fullSpec.left + (fullSpec.width * startRatio).roundToInt()
        val rawRight = fullSpec.left + (fullSpec.width * endRatio).roundToInt()

        val minWidth = minOf(
            fullSpec.width,
            maxOf(
                MIN_SPAN_MASK_WIDTH_PX,
                span.text.ifBlank { MASK_LABEL }.length * 18
            )
        )
        val center = (rawLeft + rawRight) / 2
        var left = rawLeft - SPAN_HORIZONTAL_PADDING_PX
        var right = rawRight + SPAN_HORIZONTAL_PADDING_PX

        if (right - left < minWidth) {
            left = center - minWidth / 2
            right = left + minWidth
        }

        if (left < fullSpec.left) {
            right += fullSpec.left - left
            left = fullSpec.left
        }
        if (right > fullSpec.left + fullSpec.width) {
            left -= right - (fullSpec.left + fullSpec.width)
            right = fullSpec.left + fullSpec.width
        }
        left = left.coerceAtLeast(fullSpec.left)
        right = right.coerceAtMost(fullSpec.left + fullSpec.width)

        val maxSpanWidth = estimateMaxSpanMaskWidth(
            spanText = span.text,
            fullSpecWidth = fullSpec.width,
            lineHeight = lineHeight
        )
        if (right - left > maxSpanWidth) {
            val anchored = anchorCompactSpanBounds(
                fullSpec = fullSpec,
                rawLeft = rawLeft,
                rawRight = rawRight,
                start = start,
                end = end,
                originalLength = originalLength,
                maxSpanWidth = maxSpanWidth
            )
            left = anchored.first
            right = anchored.second
        }

        val width = right - left
        if (width < MIN_WIDTH_PX) return null

        val height = minOf(lineHeight, MAX_SPAN_MASK_HEIGHT_PX).coerceAtLeast(MIN_HEIGHT_PX)
        val top = fullSpec.top + (lineIndex * lineHeight) + ((lineHeight - height) / 2).coerceAtLeast(0)

        return MaskOverlaySpec(
            left = left,
            top = top,
            width = width,
            height = height,
            label = MASK_LABEL,
            allowScrollTranslation = allowScrollTranslation,
            debugSource = debugSource
        )
    }

    private fun resolveSpanRange(
        original: String,
        span: EvidenceSpan,
        originalLength: Int
    ): Pair<Int, Int>? {
        val clampedStart = span.start.coerceIn(0, originalLength)
        val clampedEnd = span.end.coerceIn(clampedStart, originalLength)
        val spanText = span.text.trim()
        if (spanText.isNotBlank()) {
            val clampedText = codePointSubstring(original, clampedStart, clampedEnd)
            val spanCodePointLength = spanText.codePointCount(0, spanText.length).coerceAtLeast(1)
            val shouldRepair =
                clampedEnd - clampedStart < spanCodePointLength ||
                    !clampedText.equals(spanText, ignoreCase = true)
            if (shouldRepair) {
                val matchedStart = codePointIndexOf(original, spanText)
                if (matchedStart >= 0) {
                    return matchedStart to min(originalLength, matchedStart + spanCodePointLength)
                }
            }
        }

        return if (clampedEnd > clampedStart) {
            clampedStart to clampedEnd
        } else {
            null
        }
    }

    private fun codePointIndexOf(value: String, query: String): Int {
        val charIndex = value.indexOf(query, ignoreCase = true)
        if (charIndex < 0) return -1
        return value.codePointCount(0, charIndex)
    }

    private fun codePointSubstring(value: String, start: Int, end: Int): String {
        val total = value.codePointCount(0, value.length)
        val safeStart = start.coerceIn(0, total)
        val safeEnd = end.coerceIn(safeStart, total)
        val startCharIndex = value.offsetByCodePoints(0, safeStart)
        val endCharIndex = value.offsetByCodePoints(0, safeEnd)
        return value.substring(startCharIndex, endCharIndex)
    }

    private fun isWholeTextSpan(start: Int, end: Int, originalLength: Int): Boolean {
        return start <= LEADING_SPAN_PREFIX_TOLERANCE &&
            end >= originalLength - LEADING_SPAN_PREFIX_TOLERANCE
    }

    private fun shouldUseYoutubeInputGeometryFallback(
        fullSpec: MaskOverlaySpec,
        original: String,
        start: Int,
        end: Int,
        originalLength: Int,
        authorId: String?
    ): Boolean {
        if (!isWholeTextSpan(start, end, originalLength)) return false
        if (authorId == YOUTUBE_USER_INPUT_AUTHOR_ID) return true
        if (!authorId.isNullOrBlank()) return false

        val normalized = original.replace(Regex("\\s+"), " ").trim()
        if (normalized.length !in 2..TOP_SEARCH_FALLBACK_MAX_TEXT_LENGTH) return false
        if (!VisualTextOcrCandidateFilter.shouldAnalyze(normalized)) return false

        return fullSpec.top <= TOP_SEARCH_FALLBACK_MAX_TOP_PX &&
            fullSpec.width in TOP_SEARCH_FALLBACK_MIN_WIDTH_PX..TOP_SEARCH_FALLBACK_MAX_WIDTH_PX &&
            fullSpec.height in TOP_SEARCH_FALLBACK_MIN_HEIGHT_PX..TOP_SEARCH_FALLBACK_MAX_HEIGHT_PX
    }

    private fun toYoutubeInputSpanSpec(
        fullSpec: MaskOverlaySpec,
        spanText: String,
        lineHeight: Int,
        allowScrollTranslation: Boolean,
        debugSource: String
    ): MaskOverlaySpec? {
        val maxWidth = maxOf(
            MIN_WIDTH_PX,
            (fullSpec.width * YOUTUBE_INPUT_MAX_MASK_WIDTH_RATIO).roundToInt()
        )
        val width = estimateYoutubeInputMaskWidth(spanText, maxWidth)
        if (width < MIN_WIDTH_PX) return null

        val left = min(
            fullSpec.left + YOUTUBE_INPUT_TEXT_INSET_PX,
            fullSpec.left + fullSpec.width - width
        ).coerceAtLeast(fullSpec.left)
        val height = minOf(lineHeight, MAX_SPAN_MASK_HEIGHT_PX).coerceAtLeast(MIN_HEIGHT_PX)
        val top = fullSpec.top + ((fullSpec.height - height) / 2).coerceAtLeast(0)

        return MaskOverlaySpec(
            left = left,
            top = top,
            width = width,
            height = height,
            label = MASK_LABEL,
            allowScrollTranslation = allowScrollTranslation,
            debugSource = debugSource
        )
    }

    private fun estimateYoutubeInputMaskWidth(
        spanText: String,
        maxWidth: Int
    ): Int {
        val visibleText = spanText.ifBlank { MASK_LABEL }
        val codePointLength = visibleText.codePointCount(0, visibleText.length).coerceAtLeast(1)
        val hasKorean = visibleText.any { it.code in 0xAC00..0xD7A3 }
        val charWidth = if (hasKorean) {
            YOUTUBE_INPUT_KOREAN_CHAR_WIDTH_PX
        } else {
            YOUTUBE_INPUT_LATIN_CHAR_WIDTH_PX
        }

        return (codePointLength * charWidth + YOUTUBE_INPUT_WIDTH_PADDING_PX)
            .coerceIn(YOUTUBE_INPUT_MIN_MASK_WIDTH_PX, maxOf(YOUTUBE_INPUT_MIN_MASK_WIDTH_PX, maxWidth))
    }

    private fun toYoutubeTitleAccessibilitySpanSpec(
        fullSpec: MaskOverlaySpec,
        spanText: String,
        start: Int,
        end: Int,
        original: String,
        originalLength: Int,
        allowScrollTranslation: Boolean,
        debugSource: String
    ): MaskOverlaySpec? {
        if (originalLength <= 0 || end <= start) return null

        val charsPerLine = estimateYoutubeTitleCharsPerLine(
            text = original,
            width = fullSpec.width
        )
        val lineCount = ((originalLength + charsPerLine - 1) / charsPerLine)
            .coerceAtLeast(1)
            .coerceAtMost(MAX_YOUTUBE_TITLE_ACCESSIBILITY_LINE_COUNT)
        val lineIndex = (start / charsPerLine).coerceIn(0, lineCount - 1)
        val lineStart = lineIndex * charsPerLine
        val localStart = (start - lineStart).coerceIn(0, charsPerLine)
        val localEnd = (end - lineStart).coerceIn(localStart + 1, charsPerLine)

        val rawLeft = fullSpec.left +
            (fullSpec.width * (localStart.toFloat() / charsPerLine.toFloat())).roundToInt()
        val rawRight = fullSpec.left +
            (fullSpec.width * (localEnd.toFloat() / charsPerLine.toFloat())).roundToInt()
        val lineHeight = (fullSpec.height / lineCount).coerceAtLeast(MIN_HEIGHT_PX)
        val minWidth = minOf(
            fullSpec.width,
            maxOf(MIN_SPAN_MASK_WIDTH_PX, spanText.ifBlank { MASK_LABEL }.length * 18)
        )
        val center = (rawLeft + rawRight) / 2
        var left = rawLeft - SPAN_HORIZONTAL_PADDING_PX
        var right = rawRight + SPAN_HORIZONTAL_PADDING_PX

        if (right - left < minWidth) {
            left = center - minWidth / 2
            right = left + minWidth
        }
        if (left < fullSpec.left) {
            right += fullSpec.left - left
            left = fullSpec.left
        }
        if (right > fullSpec.left + fullSpec.width) {
            left -= right - (fullSpec.left + fullSpec.width)
            right = fullSpec.left + fullSpec.width
        }

        val maxSpanWidth = estimateMaxSpanMaskWidth(
            spanText = spanText,
            fullSpecWidth = fullSpec.width,
            lineHeight = lineHeight
        )
        if (right - left > maxSpanWidth) {
            val anchored = anchorCompactSpanBounds(
                fullSpec = fullSpec,
                rawLeft = rawLeft,
                rawRight = rawRight,
                start = start,
                end = end,
                originalLength = originalLength,
                maxSpanWidth = maxSpanWidth
            )
            left = anchored.first
            right = anchored.second
        }

        val width = right - left
        if (width < MIN_WIDTH_PX) return null

        val height = minOf(lineHeight, MAX_SPAN_MASK_HEIGHT_PX).coerceAtLeast(MIN_HEIGHT_PX)
        val top = fullSpec.top + (lineIndex * lineHeight) + ((lineHeight - height) / 2).coerceAtLeast(0)

        return MaskOverlaySpec(
            left = left,
            top = top,
            width = width,
            height = height,
            label = MASK_LABEL,
            allowScrollTranslation = allowScrollTranslation,
            debugSource = debugSource
        )
    }

    private fun estimateYoutubeTitleCharsPerLine(text: String, width: Int): Int {
        val hasKorean = text.any { it.code in 0xAC00..0xD7A3 }
        val charWidth = if (hasKorean) {
            YOUTUBE_TITLE_KOREAN_CHAR_WIDTH_PX
        } else {
            YOUTUBE_TITLE_LATIN_CHAR_WIDTH_PX
        }
        return (width / charWidth).coerceAtLeast(1)
    }

    private fun shouldUseTopHeroDisplayGeometry(
        fullSpec: MaskOverlaySpec,
        visualMetadata: VisualTextOcrMetadata?,
        spanText: String,
        screenHeight: Int
    ): Boolean {
        val roiBounds = visualMetadata?.roiBoundsInScreen ?: return false
        if (visualMetadata.source != "youtube-composite-card") return false
        if (!isStandaloneVisualTerm(visualMetadata.visualText, spanText)) return false

        val roiHeight = roiBounds.bottom - roiBounds.top
        val roiWidth = roiBounds.right - roiBounds.left
        if (roiHeight < 180 || roiWidth < 320) return false
        if (roiWidth < TOP_HERO_DISPLAY_MIN_ROI_WIDTH_PX) return false

        val isNearTopHeroRoi = screenHeight > 0 &&
            roiBounds.top <= (screenHeight * TOP_HERO_DISPLAY_TOP_ROI_MAX_RATIO).roundToInt()
        val hasLargeSourceBox =
            fullSpec.width >= TOP_HERO_DISPLAY_MASK_MIN_SOURCE_WIDTH_PX &&
                fullSpec.height >= TOP_HERO_DISPLAY_MASK_MIN_SOURCE_HEIGHT_PX
        val hasTopHeroSourceBox =
            isNearTopHeroRoi &&
                fullSpec.width >= TOP_HERO_DISPLAY_TOP_ROI_MIN_SOURCE_WIDTH_PX &&
                fullSpec.height >= TOP_HERO_DISPLAY_TOP_ROI_MIN_SOURCE_HEIGHT_PX
        if (!hasLargeSourceBox && !hasTopHeroSourceBox) return false

        val maxTop = roiBounds.top + (roiHeight * TOP_HERO_DISPLAY_MASK_MAX_VERTICAL_RATIO).roundToInt()
        return fullSpec.top in roiBounds.top..maxTop
    }

    private fun shouldUseLargePreciseVisualGeometry(
        fullSpec: MaskOverlaySpec,
        visualMetadata: VisualTextOcrMetadata?,
        spanText: String,
        screenHeight: Int
    ): Boolean {
        val metadata = visualMetadata ?: return false
        val roiBounds = metadata.roiBoundsInScreen ?: return false
        if (metadata.source != "youtube-composite-card" && metadata.source != "youtube-visible-band") return false
        if (!isLikelyStandaloneVisualTerm(metadata.visualText, spanText)) return false
        if (fullSpec.height < MIN_LARGE_PRECISE_VISUAL_SOURCE_HEIGHT_PX) return false
        if (screenHeight > 0 && fullSpec.height > (screenHeight * 0.26f).roundToInt()) return false
        return containsBounds(roiBounds, BoundsRect(fullSpec.left, fullSpec.top, fullSpec.left + fullSpec.width, fullSpec.top + fullSpec.height))
    }

    private fun toLargePreciseVisualSpanSpec(
        fullSpec: MaskOverlaySpec,
        visualMetadata: VisualTextOcrMetadata?,
        allowScrollTranslation: Boolean,
        debugSource: String
    ): MaskOverlaySpec? {
        val roiBounds = visualMetadata?.roiBoundsInScreen ?: return null
        val left = (fullSpec.left - LARGE_PRECISE_VISUAL_HORIZONTAL_PADDING_PX).coerceAtLeast(roiBounds.left)
        val top = (fullSpec.top - LARGE_PRECISE_VISUAL_VERTICAL_PADDING_PX).coerceAtLeast(roiBounds.top)
        val right = (fullSpec.left + fullSpec.width + LARGE_PRECISE_VISUAL_HORIZONTAL_PADDING_PX)
            .coerceAtMost(roiBounds.right)
        val bottom = (fullSpec.top + fullSpec.height + LARGE_PRECISE_VISUAL_VERTICAL_PADDING_PX)
            .coerceAtMost(roiBounds.bottom)
        if (right - left < MIN_WIDTH_PX || bottom - top < MIN_HEIGHT_PX) return null

        return MaskOverlaySpec(
            left = left,
            top = top,
            width = right - left,
            height = bottom - top,
            label = MASK_LABEL,
            allowScrollTranslation = allowScrollTranslation,
            debugSource = debugSource
        )
    }

    private fun isStandaloneVisualTerm(visualText: String?, spanText: String): Boolean {
        val cleanVisualText = visualText?.trim()?.takeIf { it.isNotBlank() } ?: return false
        val visualKey = visualSizingKey(cleanVisualText)
        val spanKey = visualSizingKey(spanText.trim())
        return visualKey.isNotBlank() && visualKey == spanKey
    }

    private fun isLikelyStandaloneVisualTerm(visualText: String?, spanText: String): Boolean {
        val cleanVisualText = visualText?.trim()?.takeIf { it.isNotBlank() } ?: return false
        val visualKey = visualSizingKey(cleanVisualText)
        val spanKey = visualSizingKey(spanText.trim())
        if (visualKey.isBlank() || spanKey.isBlank()) return false
        if (visualKey == spanKey) return true

        val lengthDelta = abs(visualKey.length - spanKey.length)
        return lengthDelta <= 1 && (visualKey.startsWith(spanKey) || spanKey.startsWith(visualKey))
    }

    private fun toTopHeroDisplayTextSpanSpec(
        fullSpec: MaskOverlaySpec,
        spanText: String,
        visualMetadata: VisualTextOcrMetadata?,
        allowScrollTranslation: Boolean,
        screenHeight: Int,
        debugSource: String
    ): MaskOverlaySpec? {
        val roiBounds = visualMetadata?.roiBoundsInScreen ?: return null
        val roiWidth = (roiBounds.right - roiBounds.left).coerceAtLeast(MIN_WIDTH_PX)
        val roiHeight = (roiBounds.bottom - roiBounds.top).coerceAtLeast(MIN_HEIGHT_PX)
        val maxHeight = topHeroDisplayMaskMaxHeight(
            roiBounds = roiBounds,
            roiHeight = roiHeight,
            screenHeight = screenHeight
        )
        val minHeight = min(TOP_HERO_DISPLAY_MASK_MIN_HEIGHT_PX, maxHeight)
        val height = (fullSpec.height * TOP_HERO_DISPLAY_MASK_HEIGHT_MULTIPLIER)
            .roundToInt()
            .coerceIn(minHeight, maxHeight)
        val top = max(
            roiBounds.top,
            fullSpec.top - ((height - fullSpec.height) * TOP_HERO_DISPLAY_MASK_TOP_OFFSET_RATIO).roundToInt()
        )
        val width = estimateTopHeroDisplayMaskWidth(
            spanText = spanText,
            textHeight = height,
            maxWidth = (roiWidth * TOP_HERO_DISPLAY_MAX_WIDTH_RATIO).roundToInt().coerceAtLeast(MIN_WIDTH_PX)
        ).coerceAtLeast(fullSpec.width)
        val left = (fullSpec.left - TOP_HERO_DISPLAY_LEFT_PADDING_PX)
            .coerceIn(roiBounds.left, roiBounds.right - MIN_WIDTH_PX)
        val right = min(roiBounds.right, left + width)
        val bottom = min(roiBounds.bottom, top + height)
        if (right - left < MIN_WIDTH_PX || bottom - top < MIN_HEIGHT_PX) return null

        return MaskOverlaySpec(
            left = left,
            top = top,
            width = right - left,
            height = bottom - top,
            label = MASK_LABEL,
            allowScrollTranslation = allowScrollTranslation,
            debugSource = debugSource
        )
    }

    private fun topHeroDisplayMaskMaxHeight(
        roiBounds: BoundsRect,
        roiHeight: Int,
        screenHeight: Int
    ): Int {
        val clippedTopHeroRoi = screenHeight > 0 &&
            roiBounds.top <= (screenHeight * TOP_HERO_DISPLAY_TOP_ROI_MAX_RATIO).roundToInt() &&
            roiHeight <= TOP_HERO_DISPLAY_CLIPPED_ROI_MAX_HEIGHT_PX
        if (!clippedTopHeroRoi) return TOP_HERO_DISPLAY_MASK_MAX_HEIGHT_PX

        return (roiHeight * TOP_HERO_DISPLAY_CLIPPED_ROI_MAX_HEIGHT_RATIO)
            .roundToInt()
            .coerceIn(TOP_HERO_DISPLAY_MASK_MIN_HEIGHT_PX, TOP_HERO_DISPLAY_MASK_MAX_HEIGHT_PX)
    }

    private fun estimateTopHeroDisplayMaskWidth(
        spanText: String,
        textHeight: Int,
        maxWidth: Int
    ): Int {
        val visibleText = spanText.ifBlank { MASK_LABEL }
        val codePointLength = visibleText.codePointCount(0, visibleText.length).coerceAtLeast(1)
        val hasKorean = visibleText.any { it.code in 0xAC00..0xD7A3 }
        val charWidth = if (hasKorean) {
            max(KOREAN_SPAN_CHAR_WIDTH_PX, (textHeight * TOP_HERO_DISPLAY_KOREAN_WIDTH_RATIO).roundToInt())
        } else {
            max(LATIN_SPAN_CHAR_WIDTH_PX, (textHeight * TOP_HERO_DISPLAY_LATIN_WIDTH_RATIO).roundToInt())
        }

        return (codePointLength * charWidth + TOP_HERO_DISPLAY_WIDTH_PADDING_PX)
            .coerceIn(MIN_SPAN_MASK_WIDTH_PX, maxOf(MIN_SPAN_MASK_WIDTH_PX, maxWidth))
    }

    private fun toPreciseVisualSpanSpec(
        fullSpec: MaskOverlaySpec,
        spanText: String,
        lineHeight: Int,
        allowScrollTranslation: Boolean,
        debugSource: String
    ): MaskOverlaySpec? {
        val maxWidth = estimatePreciseVisualSpanMaxWidth(
            spanText = spanText,
            fullSpecWidth = fullSpec.width,
            lineHeight = lineHeight
        )
        val width = minOf(fullSpec.width, maxWidth).coerceAtLeast(MIN_WIDTH_PX)
        if (width < MIN_WIDTH_PX) return null

        val left = if (width >= fullSpec.width) {
            fullSpec.left
        } else {
            fullSpec.left + ((fullSpec.width - width) / 2).coerceAtLeast(0)
        }
        val height = minOf(lineHeight, MAX_PRECISE_VISUAL_SPAN_MASK_HEIGHT_PX).coerceAtLeast(MIN_HEIGHT_PX)
        val top = fullSpec.top + ((fullSpec.height - height) / 2).coerceAtLeast(0)

        return MaskOverlaySpec(
            left = left,
            top = top,
            width = width,
            height = height,
            label = MASK_LABEL,
            allowScrollTranslation = allowScrollTranslation,
            debugSource = debugSource
        )
    }

    private fun visualTextSizingOverride(spanText: String, visualText: String?): String {
        val cleanVisualText = visualText?.trim()?.takeIf { it.isNotBlank() } ?: return spanText
        val spanKey = visualSizingKey(spanText)
        if (spanKey.isBlank()) return spanText

        return if (visualSizingKey(cleanVisualText) == spanKey) {
            cleanVisualText
        } else {
            spanText
        }
    }

    private fun visualSizingKey(text: String): String {
        return text
            .lowercase()
            .replace(Regex("""[\s"'`.,!?_\-]+"""), "")
            .map { char ->
                when (char) {
                    '|', '!', '1', 'i' -> 'l'
                    'a', 'g' -> 'q'
                    else -> char
                }
            }
            .joinToString("")
    }

    private fun buildDebugSource(item: AndroidAnalysisResultItem): String {
        val bounds = item.boundsInScreen
        val spanText = item.evidenceSpans.firstOrNull()?.text.orEmpty()
        val source = item.authorId.orEmpty().ifBlank { "unsourced" }
        val originalSample = item.original
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(36)
        return "$source span=${spanText.take(16)} rect=${bounds.left},${bounds.top},${bounds.right},${bounds.bottom} text=$originalSample"
    }

    private fun estimatePreciseVisualSpanMaxWidth(
        spanText: String,
        fullSpecWidth: Int,
        lineHeight: Int
    ): Int {
        val visibleText = spanText.ifBlank { MASK_LABEL }
        val codePointLength = visibleText.codePointCount(0, visibleText.length).coerceAtLeast(1)
        val hasKorean = visibleText.any { it.code in 0xAC00..0xD7A3 }
        val charWidth = if (hasKorean) {
            max(KOREAN_SPAN_CHAR_WIDTH_PX, (lineHeight * PRECISE_VISUAL_KOREAN_WIDTH_RATIO).roundToInt())
        } else {
            max(LATIN_SPAN_CHAR_WIDTH_PX, (lineHeight * PRECISE_VISUAL_LATIN_WIDTH_RATIO).roundToInt())
        }

        return minOf(
            fullSpecWidth,
            maxOf(
                MIN_SPAN_MASK_WIDTH_PX,
                codePointLength * charWidth + PRECISE_VISUAL_WIDTH_PADDING_PX
            )
        )
    }

    private fun estimateMaxSpanMaskWidth(
        spanText: String,
        fullSpecWidth: Int,
        lineHeight: Int
    ): Int {
        val visibleText = spanText.ifBlank { MASK_LABEL }
        val codePointLength = visibleText.codePointCount(0, visibleText.length).coerceAtLeast(1)
        val hasKorean = visibleText.any { it.code in 0xAC00..0xD7A3 }
        val scaledCharWidth = if (hasKorean) {
            max(
                KOREAN_SPAN_CHAR_WIDTH_PX,
                min(KOREAN_SPAN_MAX_CHAR_WIDTH_PX, (lineHeight * KOREAN_SPAN_HEIGHT_WIDTH_RATIO).roundToInt())
            )
        } else {
            max(
                LATIN_SPAN_CHAR_WIDTH_PX,
                min(LATIN_SPAN_MAX_CHAR_WIDTH_PX, (lineHeight * LATIN_SPAN_HEIGHT_WIDTH_RATIO).roundToInt())
            )
        }
        val estimatedWidth = codePointLength * scaledCharWidth
        val paddedWidth = estimatedWidth + SPAN_HORIZONTAL_PADDING_PX * 2
        val compactCap = if (codePointLength <= COMPACT_SPAN_CODEPOINT_LIMIT) {
            max(
                if (hasKorean) MAX_COMPACT_KOREAN_SPAN_WIDTH_PX else MAX_COMPACT_LATIN_SPAN_WIDTH_PX,
                paddedWidth
            )
        } else {
            fullSpecWidth
        }

        return minOf(
            fullSpecWidth,
            maxOf(MIN_SPAN_MASK_WIDTH_PX, minOf(paddedWidth, compactCap))
        )
    }

    private fun anchorCompactSpanBounds(
        fullSpec: MaskOverlaySpec,
        rawLeft: Int,
        rawRight: Int,
        start: Int,
        end: Int,
        originalLength: Int,
        maxSpanWidth: Int
    ): Pair<Int, Int> {
        val fullRight = fullSpec.left + fullSpec.width
        var left: Int
        var right: Int

        when {
            start <= LEADING_SPAN_PREFIX_TOLERANCE -> {
                left = (rawLeft - SPAN_HORIZONTAL_PADDING_PX).coerceAtLeast(fullSpec.left)
                right = left + maxSpanWidth
            }
            end >= originalLength -> {
                right = (rawRight + SPAN_HORIZONTAL_PADDING_PX).coerceAtMost(fullRight)
                left = right - maxSpanWidth
            }
            else -> {
                val center = (rawLeft + rawRight) / 2
                left = center - maxSpanWidth / 2
                right = left + maxSpanWidth
            }
        }

        if (right > fullRight) {
            left -= right - fullRight
            right = fullRight
        }
        if (left < fullSpec.left) {
            right += fullSpec.left - left
            left = fullSpec.left
        }

        return left.coerceAtLeast(fullSpec.left) to right.coerceAtMost(fullRight)
    }

    private fun estimateLineCount(height: Int, originalLength: Int): Int {
        if (height <= MAX_SPAN_MASK_HEIGHT_PX || originalLength <= 20) {
            return 1
        }

        return (height / ESTIMATED_LINE_HEIGHT_PX)
            .coerceAtLeast(1)
            .coerceAtMost(8)
    }

    private fun maxScrollTranslationDeltaPx(axisSize: Int): Int {
        return max(
            MIN_SCROLL_TRANSLATION_DELTA_PX,
            (axisSize * MAX_SCROLL_TRANSLATION_AXIS_RATIO).roundToInt()
        )
    }

    private fun suppressOverlappingSpecs(specs: List<MaskOverlaySpec>): List<MaskOverlaySpec> {
        val kept = mutableListOf<MaskOverlaySpec>()
        specs
            .distinctBy { "${it.left}|${it.top}|${it.width}|${it.height}" }
            .sortedWith(
                compareBy<MaskOverlaySpec> { it.top / MAX_SPAN_MASK_HEIGHT_PX }
                    .thenBy { it.width * it.height }
                    .thenBy { it.left }
                    .thenBy { it.top }
            )
            .forEach { spec ->
                val overlapsExisting = kept.any { existing ->
                    isNearDuplicateMask(spec, existing)
                }
                if (!overlapsExisting) {
                    kept += spec
                }
            }
        return kept
    }

    private fun isNearDuplicateMask(left: MaskOverlaySpec, right: MaskOverlaySpec): Boolean {
        if (overlapRatio(left, right) >= NEAR_DUPLICATE_OVERLAP_RATIO) return true

        val horizontalOverlap = min(left.left + left.width, right.left + right.width) -
            max(left.left, right.left)
        if (horizontalOverlap <= 0) return false

        val verticalOverlap = min(left.top + left.height, right.top + right.height) -
            max(left.top, right.top)
        if (verticalOverlap <= 0) return false

        val smallerHeight = min(left.height, right.height).coerceAtLeast(1)
        val smallerWidth = min(left.width, right.width).coerceAtLeast(1)
        val verticalOverlapRatio = verticalOverlap.toFloat() / smallerHeight.toFloat()
        val horizontalOverlapRatio = horizontalOverlap.toFloat() / smallerWidth.toFloat()

        return verticalOverlapRatio >= SAME_LINE_VERTICAL_OVERLAP_RATIO &&
            horizontalOverlapRatio >= SAME_LINE_HORIZONTAL_OVERLAP_RATIO
    }

    private fun overlapRatio(left: MaskOverlaySpec, right: MaskOverlaySpec): Float {
        val overlapLeft = max(left.left, right.left)
        val overlapTop = max(left.top, right.top)
        val overlapRight = min(left.left + left.width, right.left + right.width)
        val overlapBottom = min(left.top + left.height, right.top + right.height)
        val overlapWidth = overlapRight - overlapLeft
        val overlapHeight = overlapBottom - overlapTop
        if (overlapWidth <= 0 || overlapHeight <= 0) return 0f

        val overlapArea = overlapWidth * overlapHeight
        val smallerArea = min(left.width * left.height, right.width * right.height).coerceAtLeast(1)
        return overlapArea.toFloat() / smallerArea.toFloat()
    }

    private const val MASK_LABEL = "***"
    private const val SAME_LINE_VERTICAL_OVERLAP_RATIO = 0.55f
    private const val SAME_LINE_HORIZONTAL_OVERLAP_RATIO = 0.03f
}

class MaskOverlayController(
    private val service: AccessibilityService
) {
    companion object {
        private const val TAG = "MaskOverlayController"
    }

    private val windowManager = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val activeViews = mutableListOf<View>()
    private val activeSpecs = mutableListOf<MaskOverlaySpec>()
    private var lastSignature: String = ""
    private var lastOverlayUpdateAtMs: Long = 0L

    fun render(
        response: AndroidAnalysisResponse?,
        preserveExistingIfEmpty: Boolean = false,
        preserveExistingPreciseVisualMasks: Boolean = false
    ) {
        val metrics = service.resources.displayMetrics
        val plan = AndroidMaskOverlayPlanner.buildPlan(
            response = response,
            screenWidth = metrics.widthPixels,
            screenHeight = metrics.heightPixels
        )
        val specs = if (preserveExistingPreciseVisualMasks) {
            AndroidMaskOverlayPlanner.mergeWithPreservedPreciseVisualSpecs(
                newSpecs = plan.specs,
                existingSpecs = activeSpecs,
                screenWidth = metrics.widthPixels,
                screenHeight = metrics.heightPixels
            )
        } else {
            plan.specs
        }

        if (specs.isEmpty()) {
            if (preserveExistingIfEmpty && activeViews.isNotEmpty()) {
                Log.d(
                    TAG,
                    "render empty plan preserved existing masks candidates=${plan.candidateCount} " +
                        "unstable=${plan.skippedUnstableCount} suppressed=${plan.suppressedOverlapCount}"
                )
                return
            }
            clear()
            Log.d(
                TAG,
                "render skipped candidates=${plan.candidateCount} unstable=${plan.skippedUnstableCount} " +
                    "suppressed=${plan.suppressedOverlapCount}"
            )
            return
        }

        val signature = AndroidMaskOverlayPlanner.signature(specs)
        if (signature == lastSignature && activeViews.isNotEmpty()) {
            return
        }

        try {
            specs.forEachIndexed { index, spec ->
                val existing = activeViews.getOrNull(index)
                if (existing == null) {
                    val maskView = createMaskView()
                    windowManager.addView(maskView, createMaskLayoutParams(spec))
                    activeViews += maskView
                    activeSpecs += spec
                } else {
                    windowManager.updateViewLayout(existing, createMaskLayoutParams(spec))
                    activeSpecs[index] = spec
                }
            }

            while (activeViews.size > specs.size) {
                val view = activeViews.removeAt(activeViews.lastIndex)
                activeSpecs.removeAt(activeSpecs.lastIndex)
                try {
                    windowManager.removeView(view)
                } catch (_: IllegalArgumentException) {
                    // The view may already be detached after a fast window transition.
                }
            }

            Log.d(
                TAG,
                "render maskCount=${specs.size} signature=$signature sources=${
                    specs.mapNotNull { spec -> spec.debugSource.takeIf { it.isNotBlank() } }.take(3)
                }"
            )
            lastSignature = signature
            lastOverlayUpdateAtMs = SystemClock.uptimeMillis()
        } catch (error: RuntimeException) {
            clearViews()
            Log.w(TAG, "render mask overlay failed", error)
        }
    }

    fun translateBy(deltaX: Int = 0, deltaY: Int = 0): MaskOverlayTranslationStatus {
        if (activeViews.isEmpty() || activeSpecs.isEmpty()) {
            return MaskOverlayTranslationStatus.ALL_OFFSCREEN
        }
        if (deltaX == 0 && deltaY == 0) return MaskOverlayTranslationStatus.UNCHANGED

        val metrics = service.resources.displayMetrics
        val translationPlan = AndroidMaskOverlayPlanner.translatePlan(
            specs = activeSpecs,
            deltaX = deltaX,
            deltaY = deltaY,
            screenWidth = metrics.widthPixels,
            screenHeight = metrics.heightPixels
        )
        val translatedSpecs = translationPlan.specs

        if (translatedSpecs.isEmpty()) {
            return translationPlan.status
        }

        return try {
            translatedSpecs.forEachIndexed { index, spec ->
                val existing = activeViews.getOrNull(index)
                if (existing == null) {
                    val maskView = createMaskView()
                    windowManager.addView(maskView, createMaskLayoutParams(spec))
                    activeViews += maskView
                } else {
                    windowManager.updateViewLayout(existing, createMaskLayoutParams(spec))
                }
            }

            while (activeViews.size > translatedSpecs.size) {
                val view = activeViews.removeAt(activeViews.lastIndex)
                try {
                    windowManager.removeView(view)
                } catch (_: IllegalArgumentException) {
                    // The view may already be detached after a fast window transition.
                }
            }

            activeSpecs.clear()
            activeSpecs += translatedSpecs
            lastSignature = AndroidMaskOverlayPlanner.signature(translatedSpecs)
            lastOverlayUpdateAtMs = SystemClock.uptimeMillis()
            translationPlan.status
        } catch (error: RuntimeException) {
            clearViews()
            Log.w(TAG, "translate mask overlay failed", error)
            MaskOverlayTranslationStatus.ALL_OFFSCREEN
        }
    }

    fun clear() {
        clearViews()
        lastSignature = ""
        lastOverlayUpdateAtMs = 0L
    }

    fun hasActiveMasks(): Boolean {
        return activeViews.isNotEmpty()
    }

    fun wasUpdatedWithin(windowMs: Long, nowMs: Long = SystemClock.uptimeMillis()): Boolean {
        if (windowMs <= 0L || lastOverlayUpdateAtMs <= 0L) return false

        val elapsedMs = nowMs - lastOverlayUpdateAtMs
        return elapsedMs in 0..windowMs
    }

    private fun clearViews() {
        if (activeViews.isEmpty()) {
            activeSpecs.clear()
            lastSignature = ""
            return
        }

        val viewsToRemove = activeViews.toList()
        activeViews.clear()
        activeSpecs.clear()
        lastSignature = ""

        viewsToRemove.forEach { view ->
            try {
                windowManager.removeView(view)
            } catch (_: IllegalArgumentException) {
                // The view may already be detached during service shutdown.
            }
        }
    }

    private fun createMaskView(): View {
        return BlurMaskView(service).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
    }

    private fun createMaskLayoutParams(spec: MaskOverlaySpec): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            spec.width,
            spec.height,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = spec.left
            y = spec.top
        }
    }
}

private class BlurMaskView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val radius = 8f * density
    private val rect = RectF()
    private val bandRect = RectF()
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
    }
    private val shadePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(78, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = max(1f, density)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(rect, radius, radius, fillPaint)

        val bandHeight = max(2f, height / 6f)
        shadePaint.color = Color.argb(24, 255, 255, 255)
        bandRect.set(0f, height * 0.18f, width.toFloat(), height * 0.18f + bandHeight)
        canvas.drawRoundRect(bandRect, radius, radius, shadePaint)

        shadePaint.color = Color.argb(20, 0, 0, 0)
        bandRect.set(0f, height * 0.54f, width.toFloat(), height * 0.54f + bandHeight)
        canvas.drawRoundRect(bandRect, radius, radius, shadePaint)

        canvas.drawRoundRect(rect, radius, radius, edgePaint)
    }
}
