package com.capstone.design.youtubeparser

import kotlin.math.max
import kotlin.math.min

object ScreenTextCandidateExtractor {
    private const val YOUTUBE_PACKAGE = "com.google.android.youtube"
    private const val INSTAGRAM_PACKAGE = "com.instagram.android"
    private const val TIKTOK_PACKAGE = "com.zhiliaoapp.musically"
    private const val TIKTOK_ALT_PACKAGE = "com.ss.android.ugc.trill"
    private val BROWSER_PACKAGES = setOf(
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

    private const val MAX_GENERIC_CANDIDATE_COUNT = 72
    private const val MAX_LOW_PRIORITY_GENERIC_COUNT = 28
    private const val MAX_TEXT_LENGTH = 320
    private const val MIN_WIDTH_PX = 16
    private const val MIN_HEIGHT_PX = 12
    private const val RANGE_HORIZONTAL_PADDING_PX = 6
    private const val RANGE_MIN_WIDTH_PX = 28
    private const val RANGE_MAX_COMPACT_WIDTH_PX = 112
    private const val RANGE_ESTIMATED_CHAR_WIDTH_PX = 14
    private const val RANGE_EDGE_TEXT_INSET_PX = 24
    private const val RANGE_TRAILING_ICON_GUARD_PX = 56
    private const val ESTIMATED_LINE_HEIGHT_PX = 34
    private const val TOP_CONTROL_REGION_BOTTOM_PX = 180
    private const val BROWSER_COMPACT_CONTROL_REGION_BOTTOM_PX = 340
    private const val BROWSER_TOP_CONTROL_REGION_BOTTOM_PX = 500
    private const val BROWSER_WIDE_SEARCH_MIN_WIDTH_PX = 720
    private const val MAX_CREDIBLE_RANGE_SOURCE_HEIGHT_PX = 180
    private const val ROOT_LIKE_RANGE_SOURCE_WIDTH_PX = 1000
    private const val ROOT_LIKE_RANGE_SOURCE_HEIGHT_PX = 96
    private const val MAX_SUPPLEMENTAL_RANGE_SOURCE_TEXT_LENGTH = 96
    private const val YOUTUBE_USER_INPUT_AUTHOR_ID = "android-accessibility:youtube_user_input"
    private const val ACCESSIBILITY_COMMENT_PREFIX = "android-accessibility-comment:"
    private const val ACCESSIBILITY_LOOKAHEAD_PREFIX = "android-accessibility-lookahead:"
    private const val ACCESSIBILITY_CHAR_RANGE_PREFIX = "android-accessibility-char-range:"

    fun extractCandidates(
        packageName: String,
        nodes: List<ParsedTextNode>,
        sceneRevision: Long = 0L,
        screenWidth: Int? = null,
        screenHeight: Int? = null
    ): List<ScreenTextCandidate> {
        return when (packageName) {
            YOUTUBE_PACKAGE -> YoutubeAnalysisTargetExtractor.extractTargets(
                nodes = nodes,
                screenHeight = screenHeight
            )
                .map { it.toCandidate(packageName, sceneRevision) }
                .withExactCharRangeCandidates()
            INSTAGRAM_PACKAGE -> InstagramCommentExtractor.extractComments(nodes)
                .map { it.toCandidate(packageName, sceneRevision) }
                .withExactCharRangeCandidates()
            TIKTOK_PACKAGE, TIKTOK_ALT_PACKAGE -> TiktokCommentExtractor.extractComments(nodes)
                .map { it.toCandidate(packageName, sceneRevision) }
                .withExactCharRangeCandidates()
            else -> extractGenericCandidates(packageName, nodes, sceneRevision)
        }
    }

    private fun ParsedComment.toCandidate(
        packageName: String,
        sceneRevision: Long
    ): ScreenTextCandidate {
        val sourceId = platformCommentSourceId(packageName, authorId)
        val baseSourceId = sourceId.lookaheadBaseSourceId()
        val source = when {
            baseSourceId.startsWith("ocr:") -> CandidateSource.VISUAL_OCR
            baseSourceId.startsWith("youtube-visual-range:") -> CandidateSource.ACCESSIBILITY_TEXT_WITH_OCR_GEOMETRY
            baseSourceId == "youtube-composite-description" -> CandidateSource.ACCESSIBILITY_TEXT_WITH_OCR_GEOMETRY
            else -> CandidateSource.ACCESSIBILITY_TEXT
        }
        val role = when {
            baseSourceId == YOUTUBE_USER_INPUT_AUTHOR_ID -> CandidateRole.USER_INPUT
            baseSourceId == "android-accessibility:youtube_title" ||
                baseSourceId == "android-accessibility:youtube_shorts_title" -> CandidateRole.TITLE
            else -> when (source) {
                CandidateSource.VISUAL_OCR -> CandidateRole.THUMBNAIL_TEXT
                CandidateSource.ACCESSIBILITY_TEXT_WITH_OCR_GEOMETRY -> CandidateRole.TITLE
                CandidateSource.ACCESSIBILITY_TEXT -> CandidateRole.CONTENT
            }
        }

        return ScreenTextCandidate(
            id = stableId(packageName, commentText, boundsInScreen),
            packageName = packageName,
            source = source,
            role = role,
            rawText = commentText,
            normalizedVariants = normalizedVariantsFor(commentText),
            screenRect = boundsInScreen,
            charBoxes = charBoxes,
            sceneRevision = sceneRevision,
            backendSourceId = sourceId
        )
    }

    private fun platformCommentSourceId(packageName: String, authorId: String?): String? {
        val value = authorId?.trim().orEmpty()
        if (value.startsWith("ocr:") ||
            value.startsWith("youtube-visual-range:") ||
            value == "youtube-composite-description" ||
            value.startsWith(ACCESSIBILITY_LOOKAHEAD_PREFIX) ||
            value.startsWith(ACCESSIBILITY_COMMENT_PREFIX) ||
            value.startsWith("android-accessibility:")
        ) {
            return value
        }

        return when (packageName) {
            YOUTUBE_PACKAGE -> "$ACCESSIBILITY_COMMENT_PREFIX youtube".compactSourceId()
            INSTAGRAM_PACKAGE -> "$ACCESSIBILITY_COMMENT_PREFIX instagram".compactSourceId()
            TIKTOK_PACKAGE, TIKTOK_ALT_PACKAGE -> {
                val suffix = value.takeIf { it.isNotBlank() } ?: "tiktok"
                "$ACCESSIBILITY_COMMENT_PREFIX tiktok:$suffix".compactSourceId()
            }
            else -> authorId
        }
    }

    private fun String.compactSourceId(): String {
        return replace(Regex("\\s+"), "")
            .replace('|', '_')
            .take(120)
    }

    private fun String?.lookaheadBaseSourceId(): String {
        return this
            ?.removePrefix(ACCESSIBILITY_LOOKAHEAD_PREFIX)
            .orEmpty()
    }

    private fun extractGenericCandidates(
        packageName: String,
        nodes: List<ParsedTextNode>,
        sceneRevision: Long
    ): List<ScreenTextCandidate> {
        val candidates = mutableListOf<ScreenTextCandidate>()

        nodes
            .asSequence()
            .mapNotNull { node -> toGenericCandidate(packageName, node, sceneRevision) }
            .forEach { baseCandidate ->
                candidates += baseCandidate
                candidates += supplementalKeyboardRangeCandidates(baseCandidate)
            }

        return selectGenericCandidates(candidates)
    }

    private fun List<ScreenTextCandidate>.withExactCharRangeCandidates(): List<ScreenTextCandidate> {
        return flatMap { candidate ->
            listOf(candidate) + supplementalExactCharRangeCandidates(candidate)
        }
    }

    private fun toGenericCandidate(
        packageName: String,
        node: ParsedTextNode,
        sceneRevision: Long
    ): ScreenTextCandidate? {
        val text = node.displayText
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?: return null

        val bounds = BoundsRect(node.left, node.top, node.right, node.bottom)
        val role = inferRole(node, text)
        if (isBrowserTopControlCandidate(packageName, bounds, role, text)) return null
        if (!isUsefulGenericText(text, node, role)) return null
        val source = if (needsGeometryRefinement(text, bounds)) {
            CandidateSource.ACCESSIBILITY_TEXT_WITH_OCR_GEOMETRY
        } else {
            CandidateSource.ACCESSIBILITY_TEXT
        }
        val backendSourceId = if (packageName in BROWSER_PACKAGES) {
            "android-accessibility-browser:${role.name.lowercase()}"
        } else {
            "android-accessibility:${role.name.lowercase()}"
        }

        return ScreenTextCandidate(
            id = stableId(packageName, text, bounds),
            packageName = packageName,
            source = source,
            role = role,
            rawText = text,
            normalizedVariants = normalizedVariantsFor(text),
            screenRect = bounds,
            charBoxes = node.charBoxes,
            sceneRevision = sceneRevision,
            backendSourceId = backendSourceId
        )
    }

    private fun supplementalKeyboardRangeCandidates(base: ScreenTextCandidate): List<ScreenTextCandidate> {
        val ranges = VisualTextOcrCandidateFilter.findAnalysisRanges(base.rawText)
            .filter { range -> shouldAddKeyboardRangeCandidate(range) }
        if (ranges.isEmpty()) return emptyList()

        return ranges.mapNotNull { range ->
            supplementalExactCharRangeCandidate(base, range)?.let { exactCandidate ->
                return@mapNotNull exactCandidate
            }
            if (!canBuildEstimatedSupplementalRangeCandidates(base)) return@mapNotNull null

            val rangeBounds = estimateRangeBounds(
                bounds = base.screenRect,
                textLength = base.rawText.length,
                start = range.start,
                end = range.end,
                role = base.role
            ) ?: return@mapNotNull null

            ScreenTextCandidate(
                id = stableId(base.packageName, range.analysisText, rangeBounds),
                packageName = base.packageName,
                source = CandidateSource.ACCESSIBILITY_TEXT_WITH_OCR_GEOMETRY,
                role = base.role,
                rawText = range.analysisText,
                normalizedVariants = listOf(range.analysisText, range.visualText).distinct(),
                screenRect = rangeBounds,
                sceneRevision = base.sceneRevision,
                backendSourceId = "android-accessibility-range:${range.visualText}"
            )
        }
    }

    private fun supplementalExactCharRangeCandidates(base: ScreenTextCandidate): List<ScreenTextCandidate> {
        val ranges = VisualTextOcrCandidateFilter.findAnalysisRanges(base.rawText)
            .filter { range -> shouldAddKeyboardRangeCandidate(range) }
        if (ranges.isEmpty()) return emptyList()

        return ranges.mapNotNull { range ->
            supplementalExactCharRangeCandidate(base, range)
        }
    }

    private fun supplementalExactCharRangeCandidate(
        base: ScreenTextCandidate,
        range: VisualTextOcrCandidateFilter.CandidateRange
    ): ScreenTextCandidate? {
        val bounds = exactCharRangeBounds(
            text = base.rawText,
            charBoxes = base.charBoxes.orEmpty(),
            startCharIndex = range.start,
            endCharIndex = range.end
        ) ?: return null

        return ScreenTextCandidate(
            id = stableId(base.packageName, range.analysisText, bounds),
            packageName = base.packageName,
            source = CandidateSource.ACCESSIBILITY_TEXT,
            role = base.role,
            rawText = range.analysisText,
            normalizedVariants = listOf(range.analysisText, range.visualText).distinct(),
            screenRect = bounds,
            sceneRevision = base.sceneRevision,
            backendSourceId = "$ACCESSIBILITY_CHAR_RANGE_PREFIX${range.visualText}"
        )
    }

    private fun shouldAddKeyboardRangeCandidate(range: VisualTextOcrCandidateFilter.CandidateRange): Boolean {
        val visual = range.visualText.trim()
        if (visual.isBlank()) return false
        val hasLatin = visual.any { it in 'A'..'Z' || it in 'a'..'z' }
        if (hasLatin) return true

        // Keep exact Korean words contextual. Sending only "시발" from
        // "시발 - 위키낱말사전" removes the safe context and causes false positives.
        return !range.analysisText.equals(visual, ignoreCase = true)
    }

    private fun canBuildEstimatedSupplementalRangeCandidates(base: ScreenTextCandidate): Boolean {
        if (isBrowserTopControlCandidate(base.packageName, base.screenRect, base.role, base.rawText)) {
            return false
        }
        if (base.packageName in BROWSER_PACKAGES) {
            // Browser accessibility nodes expose text but not reliable per-word
            // coordinates. Estimated range boxes drift on scroll/reflow, so keep
            // the full context candidate and wait for a real geometry path.
            return false
        }
        if (base.role == CandidateRole.USER_INPUT) return true
        if (base.role == CandidateRole.BUTTON_OR_NAVIGATION) return false
        if (base.rawText.length > MAX_SUPPLEMENTAL_RANGE_SOURCE_TEXT_LENGTH) return false

        val bounds = base.screenRect
        val width = bounds.right - bounds.left
        val height = bounds.bottom - bounds.top

        if (height > MAX_CREDIBLE_RANGE_SOURCE_HEIGHT_PX) return false
        if (width >= ROOT_LIKE_RANGE_SOURCE_WIDTH_PX && height >= ROOT_LIKE_RANGE_SOURCE_HEIGHT_PX) {
            return false
        }
        if (bounds.top < TOP_CONTROL_REGION_BOTTOM_PX) {
            return false
        }

        return true
    }

    private fun exactCharRangeBounds(
        text: String,
        charBoxes: List<CharBox>,
        startCharIndex: Int,
        endCharIndex: Int
    ): BoundsRect? {
        if (charBoxes.isEmpty()) return null
        val codePointStart = text.codePointCount(0, startCharIndex.coerceIn(0, text.length))
        val codePointEnd = text.codePointCount(0, endCharIndex.coerceIn(startCharIndex, text.length))
        if (codePointEnd <= codePointStart) return null

        val boxes = charBoxes.filter { box ->
            box.end > codePointStart && box.start < codePointEnd
        }
        if (boxes.isEmpty()) return null

        val left = boxes.minOf { it.boundsInScreen.left }
        val top = boxes.minOf { it.boundsInScreen.top }
        val right = boxes.maxOf { it.boundsInScreen.right }
        val bottom = boxes.maxOf { it.boundsInScreen.bottom }
        if (right - left < MIN_WIDTH_PX || bottom - top < MIN_HEIGHT_PX) return null

        return BoundsRect(
            left = (left - RANGE_HORIZONTAL_PADDING_PX).coerceAtLeast(0),
            top = top,
            right = right + RANGE_HORIZONTAL_PADDING_PX,
            bottom = bottom
        )
    }

    private fun isBrowserTopControlCandidate(
        packageName: String,
        bounds: BoundsRect,
        role: CandidateRole,
        text: String? = null
    ): Boolean {
        if (packageName !in BROWSER_PACKAGES) return false
        if (bounds.top >= BROWSER_TOP_CONTROL_REGION_BOTTOM_PX) return false

        if (bounds.top < TOP_CONTROL_REGION_BOTTOM_PX) return true
        if (role == CandidateRole.USER_INPUT || role == CandidateRole.BUTTON_OR_NAVIGATION) return true

        val normalizedText = text.orEmpty().trim()
        if (normalizedText.isBlank()) return false

        // Browser accessibility trees sometimes expose the omnibox/search box as
        // a generic TextView/heading. Keep actual page content below the toolbar,
        // but drop short harmful-looking strings in the compact toolbar band.
        // For the lower mobile search box band, only suppress wide search-like
        // bounds so first result cards near the top are not filtered out.
        if (
            normalizedText.length <= 16 &&
            VisualTextOcrCandidateFilter.shouldAnalyze(normalizedText)
        ) {
            val width = bounds.right - bounds.left
            return bounds.top < BROWSER_COMPACT_CONTROL_REGION_BOTTOM_PX ||
                width >= BROWSER_WIDE_SEARCH_MIN_WIDTH_PX
        }

        return false
    }

    private fun selectGenericCandidates(candidates: List<ScreenTextCandidate>): List<ScreenTextCandidate> {
        val selected = mutableListOf<ScreenTextCandidate>()
        var lowPriorityCount = 0

        candidates
            .distinctBy { candidate ->
                val bounds = candidate.screenRect
                "${candidate.rawText.lowercase()}|${candidate.backendSourceId}|${bounds.left}|${bounds.top}"
            }
            .sortedWith(
                compareBy<ScreenTextCandidate> { genericPriority(it) }
                    .thenBy { it.screenRect.top }
                    .thenBy { it.screenRect.left }
            )
            .forEach { candidate ->
                if (selected.size >= MAX_GENERIC_CANDIDATE_COUNT) return@forEach
                val priority = genericPriority(candidate)
                if (priority >= 3) {
                    if (lowPriorityCount >= MAX_LOW_PRIORITY_GENERIC_COUNT) return@forEach
                    lowPriorityCount += 1
                }
                selected += candidate
            }

        return selected.sortedWith(compareBy<ScreenTextCandidate> { it.screenRect.top }.thenBy { it.screenRect.left })
    }

    private fun genericPriority(candidate: ScreenTextCandidate): Int {
        if (VisualTextOcrCandidateFilter.shouldAnalyze(candidate.rawText)) return 0
        return when (candidate.role) {
            CandidateRole.USER_INPUT -> 0
            CandidateRole.TITLE -> 1
            CandidateRole.SNIPPET -> 2
            CandidateRole.CONTENT -> 3
            CandidateRole.BUTTON_OR_NAVIGATION -> 4
            CandidateRole.THUMBNAIL_TEXT,
            CandidateRole.VIDEO_FRAME_TEXT -> 5
        }
    }

    private fun isUsefulGenericText(text: String, node: ParsedTextNode, role: CandidateRole): Boolean {
        val lower = text.lowercase()
        val width = node.right - node.left
        val height = node.bottom - node.top
        val looksHarmful = VisualTextOcrCandidateFilter.shouldAnalyze(text)

        if (!node.isVisibleToUser) return false
        if (width < MIN_WIDTH_PX || height < MIN_HEIGHT_PX) return false
        if (text.length < 2) return false
        if (text.length > MAX_TEXT_LENGTH && !looksHarmful) return false
        if (isUrlLikeText(text, lower)) return false
        if (isPureMetadata(text, lower)) return false
        if (isGenericUiLabel(text, lower) && !looksHarmful) return false

        val className = node.className.orEmpty()
        if (className.contains("Button", ignoreCase = true) && !looksHarmful && text.length < 14) {
            return false
        }

        return true
    }

    private fun isUrlLikeText(text: String, lower: String): Boolean {
        return lower.startsWith("http://") ||
            lower.startsWith("https://") ||
            lower.startsWith("www.") ||
            lower.contains("://") ||
            lower.contains("/search?q=") ||
            lower.contains("?q=") ||
            lower.contains("&q=") ||
            Regex("""^[a-z0-9][a-z0-9.-]*\.[a-z]{2,}([/?#].*)?$""", RegexOption.IGNORE_CASE).matches(text)
    }

    private fun inferRole(node: ParsedTextNode, text: String): CandidateRole {
        val className = node.className.orEmpty()
        val viewId = node.viewIdResourceName.orEmpty().lowercase()
        val width = node.right - node.left
        val height = node.bottom - node.top

        if (
            className.contains("EditText", ignoreCase = true) ||
            viewId.contains("search") ||
            viewId.contains("query") ||
            viewId.contains("input")
        ) {
            return CandidateRole.USER_INPUT
        }
        if (className.contains("Button", ignoreCase = true)) return CandidateRole.BUTTON_OR_NAVIGATION
        if (text.length <= 80 && (height >= 28 || width >= 240)) return CandidateRole.TITLE
        if (text.length >= 40) return CandidateRole.SNIPPET
        return CandidateRole.CONTENT
    }

    private fun needsGeometryRefinement(text: String, bounds: BoundsRect): Boolean {
        val width = bounds.right - bounds.left
        val height = bounds.bottom - bounds.top
        return text.length >= 40 && (width >= 360 || height >= 48)
    }

    private fun isPureMetadata(text: String, lower: String): Boolean {
        return Regex("""^[\d.,]+\s*[kmb]?$""", RegexOption.IGNORE_CASE).matches(text) ||
            Regex("""^\d+(\.\d+)?[천만억]?\s*(회|명|개)?$""").matches(text) ||
            Regex("""^\d+\s*(초|분|시간|일|주|개월|년)\s*전$""").matches(text) ||
            Regex("""^\d+\s*(sec|min|hr|day|week|mo)s?\s*ago$""", RegexOption.IGNORE_CASE).matches(text) ||
            lower.endsWith(" views") ||
            lower.endsWith(" subscribers") ||
            lower == "조회수" ||
            lower.endsWith("조회수")
    }

    private fun isGenericUiLabel(text: String, lower: String): Boolean {
        return lower in GENERIC_UI_LABELS ||
            text in GENERIC_KOREAN_UI_LABELS ||
            lower.startsWith("go to ") ||
            lower.startsWith("open ") ||
            lower.endsWith("button") ||
            lower.contains("more options") ||
            lower.contains("action menu")
    }

    private fun estimateRangeBounds(
        bounds: BoundsRect,
        textLength: Int,
        start: Int,
        end: Int,
        role: CandidateRole
    ): BoundsRect? {
        if (textLength <= 0 || end <= start) return null
        val width = bounds.right - bounds.left
        val height = bounds.bottom - bounds.top
        if (width < RANGE_MIN_WIDTH_PX || height < MIN_HEIGHT_PX) return null

        val contentLeft = if (role != CandidateRole.USER_INPUT && bounds.left <= RANGE_HORIZONTAL_PADDING_PX) {
            min(bounds.right - RANGE_MIN_WIDTH_PX, bounds.left + RANGE_EDGE_TEXT_INSET_PX)
        } else {
            bounds.left
        }
        val contentRight = if (role != CandidateRole.USER_INPUT && width >= 320) {
            max(contentLeft + RANGE_MIN_WIDTH_PX, bounds.right - RANGE_TRAILING_ICON_GUARD_PX)
        } else {
            bounds.right
        }
        val contentWidth = contentRight - contentLeft
        if (contentWidth < RANGE_MIN_WIDTH_PX) return null

        val lineCount = (height / ESTIMATED_LINE_HEIGHT_PX)
            .coerceAtLeast(1)
            .coerceAtMost(4)
        val charsPerLine = ((textLength + lineCount - 1) / lineCount).coerceAtLeast(1)
        val lineIndex = (start / charsPerLine).coerceIn(0, lineCount - 1)
        val lineStart = lineIndex * charsPerLine
        val lineEnd = min(textLength, lineStart + charsPerLine).coerceAtLeast(lineStart + 1)
        val lineLength = (lineEnd - lineStart).coerceAtLeast(1)
        val localStart = (start - lineStart).coerceIn(0, lineLength)
        val localEnd = (end - lineStart).coerceIn(localStart + 1, lineLength)

        val rawLeft = contentLeft + (contentWidth * (localStart.toFloat() / lineLength.toFloat())).toInt()
        val rawRight = contentLeft + (contentWidth * (localEnd.toFloat() / lineLength.toFloat())).toInt()
        val center = (rawLeft + rawRight) / 2
        var left = rawLeft - RANGE_HORIZONTAL_PADDING_PX
        var right = rawRight + RANGE_HORIZONTAL_PADDING_PX
        val estimatedTextWidth = (localEnd - localStart) * RANGE_ESTIMATED_CHAR_WIDTH_PX
        val minWidth = minOf(contentWidth, max(RANGE_MIN_WIDTH_PX, estimatedTextWidth))
        val maxWidth = minOf(
            contentWidth,
            max(
                RANGE_MIN_WIDTH_PX,
                min(RANGE_MAX_COMPACT_WIDTH_PX, estimatedTextWidth + RANGE_HORIZONTAL_PADDING_PX * 2)
            )
        )

        if (right - left < minWidth) { // Keep small pills readable without expanding to the full node.
            left = center - minWidth / 2
            right = left + minWidth
        }
        if (right - left > maxWidth) {
            if (localStart <= 1) {
                right = left + maxWidth
            } else {
                left = center - maxWidth / 2
                right = left + maxWidth
            }
        }
        if (left < contentLeft) {
            right += contentLeft - left
            left = contentLeft
        }
        if (right > contentRight) {
            left -= right - contentRight
            right = contentRight
        }
        left = left.coerceAtLeast(contentLeft)
        right = right.coerceAtMost(contentRight)
        if (right - left < RANGE_MIN_WIDTH_PX) return null

        val lineHeight = (height / lineCount).coerceAtLeast(MIN_HEIGHT_PX)
        val top = bounds.top + lineIndex * lineHeight
        val bottom = min(bounds.bottom, top + lineHeight)
        if (bottom - top < MIN_HEIGHT_PX) return null

        return BoundsRect(left, top, right, bottom)
    }

    private fun normalizedVariantsFor(text: String): List<String> {
        return (listOf(text) + VisualTextOcrCandidateFilter.findAnalysisRanges(text).map { it.analysisText })
            .map { it.replace(Regex("\\s+"), " ").trim() }
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun stableId(packageName: String, text: String, bounds: BoundsRect): String {
        return listOf(
            packageName,
            text.hashCode().toString(16),
            bounds.left,
            bounds.top,
            bounds.right,
            bounds.bottom
        ).joinToString(":")
    }

    private val GENERIC_UI_LABELS = setOf(
        "all",
        "shorts",
        "unwatched",
        "watched",
        "videos",
        "images",
        "news",
        "shopping",
        "more",
        "tools",
        "home",
        "back",
        "close",
        "clear",
        "share",
        "save",
        "subscribe",
        "like",
        "reply",
        "comments",
        "search",
        "voice search",
        "cast",
        "more options",
        "action menu"
    )

    private val GENERIC_KOREAN_UI_LABELS = setOf(
        "전체",
        "이미지",
        "동영상",
        "짧은 동영상",
        "쇼핑",
        "뉴스",
        "더보기",
        "도구",
        "홈",
        "뒤로",
        "닫기",
        "공유",
        "저장",
        "구독",
        "좋아요",
        "답글",
        "댓글",
        "검색"
    )
}
