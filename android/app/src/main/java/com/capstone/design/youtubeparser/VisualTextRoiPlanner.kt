package com.capstone.design.youtubeparser

import kotlin.math.max
import kotlin.math.min

data class VisualTextRoi(
    val boundsInScreen: BoundsRect,
    val source: String,
    val priority: Int,
    val reason: String,
    val sourceText: String = ""
)

data class VisualTextRoiPlan(
    val rois: List<VisualTextRoi>,
    val candidateCount: Int
)

object VisualTextRoiPlanner {
    private const val MAX_ROI_COUNT = 6
    private const val MIN_WIDTH_PX = 120
    private const val MIN_HEIGHT_PX = 60
    private const val SCREEN_EDGE_PADDING_PX = 6
    private const val MAX_SOURCE_TEXT_LENGTH = 260
    private const val MAX_ROI_AREA_RATIO = 0.28f
    private const val MAX_FULL_WIDTH_RATIO = 0.92f
    private const val MAX_VISIBLE_TOP_RATIO = 0.9f
    private const val OVERLAP_SUPPRESSION_RATIO = 0.72f
    private const val FALLBACK_BAND_HEIGHT_RATIO = 0.26f
    private const val FALLBACK_BAND_OVERLAP_PX = 32
    private const val MAX_FALLBACK_BAND_COUNT = 3
    private const val TOP_CONTROL_REGION_RATIO = 0.14f
    private const val TOP_CONTROL_REGION_MAX_PX = 230
    private const val TOP_HERO_MEDIA_MIN_HEIGHT_PX = 180
    private const val TOP_HERO_MEDIA_MIN_WIDTH_RATIO = 0.48f
    private const val TOP_SHORTS_CARD_MIN_WIDTH_RATIO = 0.34f
    private const val TOP_SHORTS_CARD_MIN_HEIGHT_PX = 180
    private const val CLIPPED_TOP_COMPOSITE_MAX_HEIGHT_PX = 59
    private const val SHORT_COMPOSITE_EXPAND_MAX_HEIGHT_PX = 260
    private const val SHORT_COMPOSITE_TITLE_GAP_MAX_PX = 180
    private const val SHORT_COMPOSITE_EXPANDED_MAX_HEIGHT_PX = 420
    private const val SHORT_COMPOSITE_TITLE_OVERLAP_RATIO = 0.55f
    private const val SHORTS_THUMBNAIL_CARD_MIN_WIDTH_RATIO = 0.34f
    private const val SHORTS_THUMBNAIL_CARD_MAX_WIDTH_RATIO = 0.55f
    private const val SHORTS_THUMBNAIL_CARD_MIN_HEIGHT_PX = 360
    private const val SHORTS_THUMBNAIL_HEIGHT_RATIO = 0.82f

    fun planFromNodes(
        nodes: List<ParsedTextNode>,
        screenWidth: Int,
        screenHeight: Int
    ): List<VisualTextRoi> {
        return buildPlanFromNodes(nodes, screenWidth, screenHeight).rois
    }

    fun buildPlanFromNodes(
        nodes: List<ParsedTextNode>,
        screenWidth: Int,
        screenHeight: Int
    ): VisualTextRoiPlan {
        if (screenWidth <= 0 || screenHeight <= 0 || nodes.isEmpty()) {
            return VisualTextRoiPlan(rois = emptyList(), candidateCount = 0)
        }

        val rawCandidates = nodes.mapNotNull { node ->
            toCandidate(node, screenWidth, screenHeight)
        }
        val fallbackCandidates =
            buildYoutubeExpandedShortCompositeRois(nodes, screenWidth, screenHeight, rawCandidates) +
                buildYoutubeShortCardThumbnailRois(rawCandidates, screenWidth, screenHeight) +
                buildYoutubeClippedTopCompositeRois(nodes, screenWidth, screenHeight, rawCandidates) +
                buildYoutubeFallbackRois(nodes, screenWidth, screenHeight, rawCandidates)
        val selectableRawCandidates = if (fallbackCandidates.isNotEmpty()) {
            rawCandidates.filterNot { candidate -> candidate.source == "generic-visual-region" }
        } else {
            rawCandidates
        }

        val selected = mutableListOf<VisualTextRoi>()
        (selectableRawCandidates + fallbackCandidates)
            .sortedWith(
                compareBy<VisualTextRoi> { it.priority }
                    .thenBy { it.boundsInScreen.top }
                    .thenBy { it.boundsInScreen.left }
            )
            .forEach { candidate ->
                if (selected.size >= MAX_ROI_COUNT) return@forEach
                if (selected.none { overlapsTooMuch(it.boundsInScreen, candidate.boundsInScreen) }) {
                    selected += candidate
                }
            }

        return VisualTextRoiPlan(
            rois = selected,
            candidateCount = rawCandidates.size + fallbackCandidates.size
        )
    }

    private fun toCandidate(
        node: ParsedTextNode,
        screenWidth: Int,
        screenHeight: Int
    ): VisualTextRoi? {
        if (!node.isVisibleToUser) return null

        val sourceText = node.displayText
            ?: node.contentDescription
            ?: node.text
            ?: return null
        val normalized = sourceText.replace(Regex("\\s+"), " ").trim()
        if (!isUsefulSourceText(normalized)) return null
        if (node.packageName in ACCESSIBILITY_FIRST_PACKAGES) return null

        val clamped = clampBounds(
            BoundsRect(node.left, node.top, node.right, node.bottom),
            screenWidth,
            screenHeight
        ) ?: return null
        val contentDescriptionOnly = node.text.isNullOrBlank() && !node.contentDescription.isNullOrBlank()
        val className = node.className.orEmpty()
        val isImageLike = className.contains("Image", ignoreCase = true)
        val isYoutubeComposite = contentDescriptionOnly &&
            (isMediaCardDescription(normalized) || isLargeAnalyzableVisualCard(normalized, clamped))
        val isGenericVisual = contentDescriptionOnly && (isImageLike || looksLikeVisualCard(className, normalized))
        if (!isYoutubeComposite && !isGenericVisual) return null

        if (!isNearCurrentViewport(clamped, screenHeight)) return null
        if (looksLikeRootOrSystemRegion(clamped, screenWidth, screenHeight)) return null
        if (
            looksLikeTopControlRegion(clamped, screenHeight) &&
            !isTopVisibleMediaRegion(isYoutubeComposite, clamped, screenWidth)
        ) {
            return null
        }

        val roiBounds = normalizeRoiBounds(clamped, screenWidth, screenHeight) ?: return null

        return VisualTextRoi(
            boundsInScreen = roiBounds,
            source = if (isYoutubeComposite) "youtube-composite-card" else "generic-visual-region",
            priority = if (isYoutubeComposite) 0 else 1,
            reason = if (contentDescriptionOnly) "content-description-only" else "visual-node",
            sourceText = normalized
        )
    }

    private fun isUsefulSourceText(text: String): Boolean {
        if (text.length !in 4..MAX_SOURCE_TEXT_LENGTH) return false
        val lower = text.lowercase()
        if (lower.startsWith("http://") || lower.startsWith("https://")) return false
        if (lower == "more options" || lower == "action menu") return false
        if (lower == "all" || lower == "shorts" || lower == "videos") return false
        return text.any { it.isLetterOrDigit() || it.code in 0xAC00..0xD7A3 }
    }

    private fun isMediaCardDescription(text: String): Boolean {
        val lower = text.lowercase()
        return lower.contains("play video") ||
            lower.contains("play short") ||
            lower.contains("views") ||
            lower.contains("조회수") ||
            lower.contains("go to channel") ||
            lower.contains("동영상 재생")
    }

    private fun isLargeAnalyzableVisualCard(text: String, bounds: BoundsRect): Boolean {
        val width = bounds.right - bounds.left
        val height = bounds.bottom - bounds.top
        return width >= 320 &&
            height >= 180 &&
            VisualTextOcrCandidateFilter.shouldAnalyze(text)
    }

    private fun looksLikeVisualCard(className: String, text: String): Boolean {
        if (className.contains("Button", ignoreCase = true)) return false
        if (className.contains("RecyclerView", ignoreCase = true)) return false
        return text.length >= 12 && text.any { it.isWhitespace() }
    }

    private fun clampBounds(
        bounds: BoundsRect,
        screenWidth: Int,
        screenHeight: Int
    ): BoundsRect? {
        val left = bounds.left.coerceIn(0, screenWidth)
        val top = bounds.top.coerceIn(0, screenHeight)
        val right = bounds.right.coerceIn(left, screenWidth)
        val bottom = bounds.bottom.coerceIn(top, screenHeight)
        if (right - left < MIN_WIDTH_PX || bottom - top < MIN_HEIGHT_PX) return null
        return BoundsRect(left, top, right, bottom)
    }

    private fun isNearCurrentViewport(bounds: BoundsRect, screenHeight: Int): Boolean {
        return bounds.top < (screenHeight * MAX_VISIBLE_TOP_RATIO).toInt()
    }

    private fun looksLikeRootOrSystemRegion(
        bounds: BoundsRect,
        screenWidth: Int,
        screenHeight: Int
    ): Boolean {
        val width = bounds.right - bounds.left
        val height = bounds.bottom - bounds.top
        val widthRatio = width.toFloat() / screenWidth.toFloat()
        val heightRatio = height.toFloat() / screenHeight.toFloat()

        return (widthRatio >= MAX_FULL_WIDTH_RATIO && heightRatio >= 0.55f) ||
            (bounds.top <= SCREEN_EDGE_PADDING_PX && heightRatio >= 0.35f)
    }

    private fun looksLikeTopControlRegion(bounds: BoundsRect, screenHeight: Int): Boolean {
        val cutoff = min(TOP_CONTROL_REGION_MAX_PX, (screenHeight * TOP_CONTROL_REGION_RATIO).toInt())
        return bounds.top < cutoff
    }

    private fun isTopVisibleMediaRegion(
        isYoutubeComposite: Boolean,
        bounds: BoundsRect,
        screenWidth: Int
    ): Boolean {
        if (!isYoutubeComposite) return false

        val width = bounds.right - bounds.left
        val height = bounds.bottom - bounds.top
        val isHero = height >= TOP_HERO_MEDIA_MIN_HEIGHT_PX &&
            width >= (screenWidth * TOP_HERO_MEDIA_MIN_WIDTH_RATIO).toInt()
        val isShortsGridCard = height >= TOP_SHORTS_CARD_MIN_HEIGHT_PX &&
            width >= (screenWidth * TOP_SHORTS_CARD_MIN_WIDTH_RATIO).toInt()

        return isHero || isShortsGridCard
    }

    private fun normalizeRoiBounds(
        bounds: BoundsRect,
        screenWidth: Int,
        screenHeight: Int
    ): BoundsRect? {
        val screenArea = max(1, screenWidth * screenHeight)
        val width = bounds.right - bounds.left
        val height = bounds.bottom - bounds.top
        val areaRatio = (width * height).toFloat() / screenArea.toFloat()
        if (areaRatio <= MAX_ROI_AREA_RATIO) {
            return padAndClamp(bounds, screenWidth, screenHeight)
        }

        val maxArea = (screenArea * MAX_ROI_AREA_RATIO).toInt().coerceAtLeast(MIN_WIDTH_PX * MIN_HEIGHT_PX)
        val maxHeightForWidth = (maxArea / max(1, width)).coerceAtLeast(MIN_HEIGHT_PX)
        val croppedHeight = max(
            MIN_HEIGHT_PX,
            min(height, min((screenHeight * 0.32f).toInt(), maxHeightForWidth))
        )
        val cropped = BoundsRect(
            left = bounds.left,
            top = bounds.top,
            right = bounds.right,
            bottom = min(bounds.bottom, bounds.top + croppedHeight)
        )
        val croppedWidth = cropped.right - cropped.left
        val croppedAreaRatio = (croppedWidth * (cropped.bottom - cropped.top)).toFloat() / screenArea.toFloat()
        if (croppedAreaRatio > MAX_ROI_AREA_RATIO) return null

        return padAndClamp(cropped, screenWidth, screenHeight)
    }

    private fun padAndClamp(
        bounds: BoundsRect,
        screenWidth: Int,
        screenHeight: Int
    ): BoundsRect? {
        val left = max(0, bounds.left - SCREEN_EDGE_PADDING_PX)
        val top = max(0, bounds.top - SCREEN_EDGE_PADDING_PX)
        val right = min(screenWidth, bounds.right + SCREEN_EDGE_PADDING_PX)
        val bottom = min(screenHeight, bounds.bottom + SCREEN_EDGE_PADDING_PX)
        if (right - left < MIN_WIDTH_PX || bottom - top < MIN_HEIGHT_PX) return null
        return BoundsRect(left, top, right, bottom)
    }

    private fun overlapsTooMuch(first: BoundsRect, second: BoundsRect): Boolean {
        val overlapLeft = max(first.left, second.left)
        val overlapTop = max(first.top, second.top)
        val overlapRight = min(first.right, second.right)
        val overlapBottom = min(first.bottom, second.bottom)
        if (overlapRight <= overlapLeft || overlapBottom <= overlapTop) return false

        val overlapArea = (overlapRight - overlapLeft) * (overlapBottom - overlapTop)
        val smallerArea = min(
            (first.right - first.left) * (first.bottom - first.top),
            (second.right - second.left) * (second.bottom - second.top)
        ).coerceAtLeast(1)

        return overlapArea.toFloat() / smallerArea.toFloat() >= OVERLAP_SUPPRESSION_RATIO
    }

    private fun buildYoutubeFallbackRois(
        nodes: List<ParsedTextNode>,
        screenWidth: Int,
        screenHeight: Int,
        rawCandidates: List<VisualTextRoi>
    ): List<VisualTextRoi> {
        if (nodes.none { it.packageName == YOUTUBE_PACKAGE }) return emptyList()
        if (rawCandidates.any { it.source == "youtube-composite-card" }) return emptyList()

        val topControlBottom = nodes
            .asSequence()
            .filter { node ->
                val height = node.bottom - node.top
                node.top in 0..(screenHeight * TOP_CONTROL_REGION_RATIO).toInt() &&
                    height in MIN_HEIGHT_PX..TOP_CONTROL_REGION_MAX_PX
            }
            .map { it.bottom }
            .maxOrNull()

        val filterBottom = nodes
            .asSequence()
            .filter { node ->
                val text = node.displayText.orEmpty().trim().lowercase()
                text in YOUTUBE_FILTER_LABELS && node.top in 0..(screenHeight * 0.28f).toInt()
            }
            .map { it.bottom }
            .maxOrNull()
            ?: topControlBottom
            ?: return emptyList()

        val firstTop = filterBottom + SCREEN_EDGE_PADDING_PX
        val bandHeight = (screenHeight * FALLBACK_BAND_HEIGHT_RATIO).toInt().coerceAtLeast(MIN_HEIGHT_PX)
        val maxVisibleTop = (screenHeight * MAX_VISIBLE_TOP_RATIO).toInt()
        val bandStep = (bandHeight - FALLBACK_BAND_OVERLAP_PX).coerceAtLeast(MIN_HEIGHT_PX)
        val fallbackRois = mutableListOf<VisualTextRoi>()
        var top = firstTop

        while (fallbackRois.size < MAX_FALLBACK_BAND_COUNT && top < maxVisibleTop) {
            val bottom = min(screenHeight, top + bandHeight)
            if (bottom - top < MIN_HEIGHT_PX) break
            fallbackRois += VisualTextRoi(
                boundsInScreen = BoundsRect(
                    left = 0,
                    top = top,
                    right = screenWidth,
                    bottom = bottom
                ),
                source = "youtube-visible-band",
                priority = 9,
                reason = "fallback-first-viewport-band"
            )
            top += bandStep
        }

        return fallbackRois
    }

    private fun buildYoutubeClippedTopCompositeRois(
        nodes: List<ParsedTextNode>,
        screenWidth: Int,
        screenHeight: Int,
        rawCandidates: List<VisualTextRoi>
    ): List<VisualTextRoi> {
        if (nodes.none { it.packageName == YOUTUBE_PACKAGE }) return emptyList()
        val firstCompositeTop = rawCandidates
            .asSequence()
            .filter { it.source == "youtube-composite-card" }
            .map { it.boundsInScreen.top }
            .minOrNull()
            ?: return emptyList()

        val filterBottom = nodes
            .asSequence()
            .filter { node ->
                val text = node.displayText.orEmpty().trim().lowercase()
                text in YOUTUBE_FILTER_LABELS && node.top in 0..(screenHeight * 0.28f).toInt()
            }
            .map { it.bottom }
            .maxOrNull()
            ?: return emptyList()

        val clippedTopNode = nodes
            .asSequence()
            .filter { node ->
                if (!node.isVisibleToUser) return@filter false
                val text = node.displayText.orEmpty().replace(Regex("\\s+"), " ").trim()
                val width = node.right - node.left
                val height = node.bottom - node.top
                val contentDescriptionOnly = node.text.isNullOrBlank() && !node.contentDescription.isNullOrBlank()

                contentDescriptionOnly &&
                    node.top >= filterBottom &&
                    node.top < firstCompositeTop &&
                    width >= (screenWidth * MAX_FULL_WIDTH_RATIO).toInt() &&
                    height in MIN_HEIGHT_PX / 2..CLIPPED_TOP_COMPOSITE_MAX_HEIGHT_PX &&
                    isUsefulSourceText(text) &&
                    isMediaCardDescription(text)
            }
            .minByOrNull { it.top }
            ?: return emptyList()

        val top = clippedTopNode.top.coerceIn(0, screenHeight)
        val bottom = min(
            screenHeight,
            max(
                firstCompositeTop + FALLBACK_BAND_OVERLAP_PX,
                top + TOP_HERO_MEDIA_MIN_HEIGHT_PX
            )
        )
        if (bottom - top < MIN_HEIGHT_PX) return emptyList()

        return listOf(
            VisualTextRoi(
                boundsInScreen = BoundsRect(
                    left = 0,
                    top = top,
                    right = screenWidth,
                    bottom = bottom
                ),
                source = "youtube-visible-band",
                priority = -1,
                reason = "fallback-clipped-top-composite"
            )
        )
    }

    private fun buildYoutubeExpandedShortCompositeRois(
        nodes: List<ParsedTextNode>,
        screenWidth: Int,
        screenHeight: Int,
        rawCandidates: List<VisualTextRoi>
    ): List<VisualTextRoi> {
        if (nodes.none { it.packageName == YOUTUBE_PACKAGE }) return emptyList()

        return rawCandidates
            .asSequence()
            .filter { candidate ->
                candidate.source == "youtube-composite-card" &&
                    candidate.boundsInScreen.bottom - candidate.boundsInScreen.top <= SHORT_COMPOSITE_EXPAND_MAX_HEIGHT_PX
            }
            .mapNotNull { candidate ->
                val titleNode = findVisibleTitleNodeBelowComposite(candidate, nodes) ?: return@mapNotNull null
                val expandedBottom = min(
                    screenHeight,
                    max(candidate.boundsInScreen.bottom, titleNode.bottom + SCREEN_EDGE_PADDING_PX)
                )
                val expandedHeight = expandedBottom - candidate.boundsInScreen.top
                if (expandedHeight > SHORT_COMPOSITE_EXPANDED_MAX_HEIGHT_PX) return@mapNotNull null

                VisualTextRoi(
                    boundsInScreen = BoundsRect(
                        left = candidate.boundsInScreen.left,
                        top = candidate.boundsInScreen.top,
                        right = candidate.boundsInScreen.right,
                        bottom = expandedBottom
                    ),
                    source = candidate.source,
                    priority = -1,
                    reason = "expanded-short-composite-title",
                    sourceText = candidate.sourceText
                )
            }
            .toList()
    }

    private fun buildYoutubeShortCardThumbnailRois(
        rawCandidates: List<VisualTextRoi>,
        screenWidth: Int,
        screenHeight: Int
    ): List<VisualTextRoi> {
        return rawCandidates
            .asSequence()
            .filter { candidate ->
                candidate.source == "youtube-composite-card" &&
                    candidate.sourceText.lowercase().contains("play short")
            }
            .mapNotNull { candidate ->
                val bounds = candidate.boundsInScreen
                val width = bounds.right - bounds.left
                val height = bounds.bottom - bounds.top
                val widthRatio = width.toFloat() / screenWidth.toFloat()

                if (
                    widthRatio < SHORTS_THUMBNAIL_CARD_MIN_WIDTH_RATIO ||
                    widthRatio > SHORTS_THUMBNAIL_CARD_MAX_WIDTH_RATIO ||
                    height < SHORTS_THUMBNAIL_CARD_MIN_HEIGHT_PX
                ) {
                    return@mapNotNull null
                }

                val thumbnailBottom = min(
                    screenHeight,
                    bounds.top + (height * SHORTS_THUMBNAIL_HEIGHT_RATIO).toInt()
                )
                if (thumbnailBottom - bounds.top < MIN_HEIGHT_PX) return@mapNotNull null

                val roiBounds = padAndClamp(
                    BoundsRect(
                        left = bounds.left,
                        top = bounds.top,
                        right = bounds.right,
                        bottom = thumbnailBottom
                    ),
                    screenWidth,
                    screenHeight
                ) ?: return@mapNotNull null

                VisualTextRoi(
                    boundsInScreen = roiBounds,
                    source = candidate.source,
                    priority = -2,
                    reason = "short-card-thumbnail-segment",
                    sourceText = candidate.sourceText
                )
            }
            .toList()
    }

    private fun findVisibleTitleNodeBelowComposite(
        candidate: VisualTextRoi,
        nodes: List<ParsedTextNode>
    ): ParsedTextNode? {
        val candidateBounds = candidate.boundsInScreen
        val candidateSourceKey = compactCardText(candidate.sourceText)
        if (candidateSourceKey.isBlank()) return null

        return nodes
            .asSequence()
            .filter { node ->
                if (!node.isVisibleToUser || node.packageName != YOUTUBE_PACKAGE) return@filter false
                val text = node.displayText.orEmpty().replace(Regex("\\s+"), " ").trim()
                val textKey = compactCardText(text)
                val width = node.right - node.left
                val height = node.bottom - node.top
                val verticalGap = node.top - candidateBounds.bottom
                val contentDescriptionOnly = node.text.isNullOrBlank() && !node.contentDescription.isNullOrBlank()

                !contentDescriptionOnly &&
                    textKey.isNotBlank() &&
                    textKey.length >= 4 &&
                    candidateSourceKey.contains(textKey) &&
                    width >= MIN_WIDTH_PX &&
                    height in MIN_HEIGHT_PX / 2..TOP_CONTROL_REGION_MAX_PX &&
                    verticalGap in -SCREEN_EDGE_PADDING_PX..SHORT_COMPOSITE_TITLE_GAP_MAX_PX &&
                    horizontalOverlapRatio(candidateBounds, BoundsRect(node.left, node.top, node.right, node.bottom)) >=
                    SHORT_COMPOSITE_TITLE_OVERLAP_RATIO
            }
            .minByOrNull { it.top }
    }

    private fun compactCardText(text: String): String {
        return text
            .lowercase()
            .replace(Regex("""[\s"'`“”‘’.,!?_\-\[\]\(\):|#]+"""), "")
    }

    private fun horizontalOverlapRatio(first: BoundsRect, second: BoundsRect): Float {
        val overlapLeft = max(first.left, second.left)
        val overlapRight = min(first.right, second.right)
        if (overlapRight <= overlapLeft) return 0f

        val smallerWidth = min(first.right - first.left, second.right - second.left).coerceAtLeast(1)
        return (overlapRight - overlapLeft).toFloat() / smallerWidth.toFloat()
    }

    private const val YOUTUBE_PACKAGE = "com.google.android.youtube"

    private val ACCESSIBILITY_FIRST_PACKAGES = setOf(
        "com.android.chrome",
        "com.chrome.beta",
        "com.chrome.dev",
        "com.chrome.canary",
        "com.google.android.googlequicksearchbox",
        "com.google.android.apps.searchlite",
        "com.android.browser",
        "org.mozilla.firefox",
        "com.microsoft.emmx",
        "com.sec.android.app.sbrowser"
    )

    private val YOUTUBE_FILTER_LABELS = setOf(
        "all",
        "shorts",
        "unwatched",
        "watched",
        "videos",
        "전체",
        "쇼츠",
        "동영상"
    )
}
