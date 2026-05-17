package com.capstone.design.youtubeparser

object YoutubeAnalysisTargetExtractor {
    private const val MAX_TARGET_COUNT = 28
    private const val MAX_LOW_PRIORITY_TARGET_COUNT = 8
    private const val LOOKAHEAD_BAND_BELOW_SCREEN_RATIO = 0.75f
    private const val LOOKAHEAD_BAND_ABOVE_SCREEN_RATIO = 0.25f
    private const val LOOKAHEAD_AUTHOR_PREFIX = "android-accessibility-lookahead:"
    private const val YOUTUBE_SEARCH_INPUT_TOP_MAX_PX = 260
    private const val YOUTUBE_SEARCH_INPUT_MIN_WIDTH_PX = 96
    private const val YOUTUBE_SEARCH_INPUT_MAX_HEIGHT_PX = 128
    private const val COMPOSITE_TITLE_ESTIMATED_HEIGHT_PX = 56
    private const val SHORTS_GRID_TITLE_TOP_RATIO = 0.66f
    private const val SHORTS_GRID_MEDIUM_TITLE_TOP_RATIO = 0.22f
    private const val SHORTS_GRID_MEDIUM_MAX_HEIGHT_PX = 720
    private const val SHORTS_GRID_COMPACT_TITLE_TOP_RATIO = 0.46f
    private const val SHORTS_GRID_COMPACT_MAX_HEIGHT_PX = 480
    private const val PLAYLIST_TITLE_BOTTOM_INSET_PX = 102
    private const val VISUAL_RANGE_MIN_WIDTH_PX = 30
    private const val VISUAL_RANGE_HORIZONTAL_PADDING_PX = 6
    private const val VISUAL_RANGE_LINE_HEIGHT_PX = 34
    private const val SHORT_TEXT_VISUAL_RANGE_LIMIT = 14
    private const val YOUTUBE_USER_INPUT_AUTHOR_ID = "android-accessibility:youtube_user_input"
    private const val YOUTUBE_STABLE_TITLE_AUTHOR_ID = "android-accessibility:youtube_title"
    private const val YOUTUBE_SHORTS_TITLE_AUTHOR_ID = "android-accessibility:youtube_shorts_title"

    fun extractTargets(
        nodes: List<ParsedTextNode>,
        screenHeight: Int? = null
    ): List<ParsedComment> {
        val commentTargets = YoutubeCommentExtractor.extractComments(nodes)
        val searchInputTargets = nodes
            .asSequence()
            .mapNotNull { toYoutubeSearchInputTarget(it) }
            .toList()
        val standaloneTargets = nodes
            .asSequence()
            .flatMap { toStandaloneTargets(it, screenHeight).asSequence() }
            .toList()

        return selectTargetsForAnalysis(searchInputTargets + commentTargets + standaloneTargets)
    }

    private fun selectTargetsForAnalysis(targets: List<ParsedComment>): List<ParsedComment> {
        val selected = mutableListOf<ParsedComment>()
        var lowPriorityCount = 0

        targets
            .distinctBy { target ->
                val normalizedText = target.commentText.replace(Regex("\\s+"), " ").trim()
                "${normalizedText}|${target.boundsInScreen.left}|${target.boundsInScreen.top}"
            }
            .sortedWith(
                compareBy<ParsedComment> { targetSelectionPriority(it) }
                    .thenBy { it.boundsInScreen.top }
                    .thenBy { it.boundsInScreen.left }
            )
            .forEach { target ->
                if (selected.size >= MAX_TARGET_COUNT) return@forEach

                if (targetSelectionPriority(target) >= 2) {
                    if (lowPriorityCount >= MAX_LOW_PRIORITY_TARGET_COUNT) return@forEach
                    lowPriorityCount += 1
                }

                selected += target
            }

        return selected.sortedWith(compareBy<ParsedComment> { it.boundsInScreen.top }.thenBy { it.boundsInScreen.left })
    }

    private fun toStandaloneTargets(
        node: ParsedTextNode,
        screenHeight: Int?
    ): List<ParsedComment> {
        val primary = toStandaloneTarget(node, screenHeight) ?: return emptyList()
        when (baseAuthorId(primary.authorId)) {
            YOUTUBE_SHORTS_TITLE_AUTHOR_ID -> return listOf(primary)
            COMPOSITE_DESCRIPTION_AUTHOR_ID -> {
                return toVisualRangeTargets(
                    primary = primary,
                    allowShortText = true
                )
            }
        }

        return listOf(primary)
    }

    private fun toStandaloneTarget(
        node: ParsedTextNode,
        screenHeight: Int?
    ): ParsedComment? {
        val text = node.displayText.orEmpty().replace(Regex("\\s+"), " ").trim()
        val lower = text.lowercase()
        val width = node.right - node.left
        val height = node.bottom - node.top
        val className = node.className.orEmpty()
        val contentDescriptionOnly = node.text.isNullOrBlank() && !node.contentDescription.isNullOrBlank()
        val visibility = targetVisibility(node, screenHeight)

        if (visibility == TargetVisibility.HIDDEN) return null
        if (width < 24 || height < 16) return null
        if (contentDescriptionOnly && shouldTreatAsCompositeYoutubeCard(text, lower, width, height)) {
            return toCompositeYoutubeTitleTarget(node, text, width, height)
                ?.withVisibility(visibility)
        }
        if (text.length !in 2..220) return null
        if (text.startsWith("@")) return null
        if (lower.startsWith("http://") || lower.startsWith("https://")) return null
        if (lower.startsWith("comments.")) return null
        if (isYoutubeUiLabel(text, lower)) return null
        if (lower == "comments" || lower == "댓글") return null
        if (lower == "share" || lower == "save" || lower == "remix" || lower == "subscribe") return null
        if (lower == "download" || lower == "next:" || lower == "show playlist videos") return null
        if (lower.startsWith("subscribe to ")) return null
        if (lower.startsWith("save to ")) return null
        if (lower.contains(" playlist") && lower.length <= 40) return null
        if (text == "공유" || text == "저장" || text == "구독" || text == "답글") return null
        if (lower == "...more" || lower == "more" || lower == "더보기") return null
        if (lower.contains("like this") || lower.contains("dislike this")) return null
        if (lower.endsWith(" views") || lower.endsWith("view")) return null
        if (text.endsWith("조회수")) return null
        if (looksLikeRelativeTime(text)) return null
        if (Regex("""^[\d.,]+\s*[kmb]?$""", RegexOption.IGNORE_CASE).matches(text)) return null
        if (Regex("""^\d+(\.\d+)?[천만억]?\s*(회|명)?$""").matches(text)) return null
        if (className.contains("Button", ignoreCase = true) && !looksLikeContentText(text)) return null

        return ParsedComment(
            commentText = text,
            boundsInScreen = BoundsRect(
                left = node.left,
                top = node.top,
                right = node.right,
                bottom = node.bottom
            ),
            authorId = stableYoutubeAccessibilityAuthor(node, text, width, height, className),
            charBoxes = node.charBoxes
        ).withVisibility(visibility)
    }

    private fun toYoutubeSearchInputTarget(node: ParsedTextNode): ParsedComment? {
        if (!node.isVisibleToUser) return null
        if (!isLikelyYoutubeSearchInput(node)) return null

        val text = node.displayText
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?: return null
        val lower = text.lowercase()
        if (text.length !in 2..120) return null
        if (isYoutubeUiLabel(text, lower)) return null
        if (lower.startsWith("http://") || lower.startsWith("https://")) return null

        return ParsedComment(
            commentText = text,
            boundsInScreen = BoundsRect(
                left = node.left,
                top = node.top,
                right = node.right,
                bottom = node.bottom
            ),
            authorId = YOUTUBE_USER_INPUT_AUTHOR_ID,
            charBoxes = node.charBoxes
        )
    }

    private fun toVisualRangeTargets(
        primary: ParsedComment,
        allowShortText: Boolean = false
    ): List<ParsedComment> {
        if (!allowShortText && primary.commentText.length <= SHORT_TEXT_VISUAL_RANGE_LIMIT) return emptyList()

        val ranges = VisualTextOcrCandidateFilter.findAnalysisRanges(primary.commentText)
        if (ranges.isEmpty()) return emptyList()

        return ranges.mapNotNull { range ->
            val rangeBounds = estimateVisualRangeBounds(
                bounds = primary.boundsInScreen,
                textLength = primary.commentText.length,
                start = range.start,
                end = range.end
            ) ?: return@mapNotNull null

            ParsedComment(
                commentText = range.analysisText,
                boundsInScreen = rangeBounds,
                authorId = if (isLookaheadAuthor(primary.authorId)) {
                    lookaheadAuthorId("youtube-visual-range:${range.visualText}")
                } else {
                    "youtube-visual-range:${range.visualText}"
                }
            )
        }
    }

    private fun targetSelectionPriority(target: ParsedComment): Int {
        val authorId = baseAuthorId(target.authorId).orEmpty()
        if (authorId == YOUTUBE_USER_INPUT_AUTHOR_ID) {
            return 0
        }
        if (authorId == YOUTUBE_STABLE_TITLE_AUTHOR_ID &&
            VisualTextOcrCandidateFilter.shouldAnalyze(target.commentText)
        ) {
            return 0
        }
        if (authorId == YOUTUBE_SHORTS_TITLE_AUTHOR_ID &&
            VisualTextOcrCandidateFilter.shouldAnalyze(target.commentText)
        ) {
            return 0
        }
        if (authorId.startsWith("youtube-visual-range:") || authorId.startsWith("ocr:")) {
            return 0
        }
        if (VisualTextOcrCandidateFilter.shouldAnalyze(target.commentText)) {
            return 1
        }
        return 2
    }

    private enum class TargetVisibility {
        VISIBLE,
        LOOKAHEAD,
        HIDDEN
    }

    private fun targetVisibility(node: ParsedTextNode, screenHeight: Int?): TargetVisibility {
        if (node.isVisibleToUser) return TargetVisibility.VISIBLE
        val height = screenHeight?.takeIf { it > 0 } ?: return TargetVisibility.HIDDEN
        val nodeHeight = node.bottom - node.top
        if (nodeHeight <= 0) return TargetVisibility.HIDDEN

        val belowLimit = height + (height * LOOKAHEAD_BAND_BELOW_SCREEN_RATIO).toInt()
        val aboveLimit = -(height * LOOKAHEAD_BAND_ABOVE_SCREEN_RATIO).toInt()
        val belowViewport = node.top >= height && node.top <= belowLimit
        val aboveViewport = node.bottom <= 0 && node.bottom >= aboveLimit

        return if (belowViewport || aboveViewport) {
            TargetVisibility.LOOKAHEAD
        } else {
            TargetVisibility.HIDDEN
        }
    }

    private fun ParsedComment.withVisibility(visibility: TargetVisibility): ParsedComment {
        if (visibility != TargetVisibility.LOOKAHEAD) return this
        val baseAuthorId = authorId ?: YOUTUBE_STABLE_TITLE_AUTHOR_ID
        return copy(authorId = lookaheadAuthorId(baseAuthorId))
    }

    private fun lookaheadAuthorId(authorId: String): String {
        return if (authorId.startsWith(LOOKAHEAD_AUTHOR_PREFIX)) {
            authorId
        } else {
            LOOKAHEAD_AUTHOR_PREFIX + authorId
        }
    }

    private fun isLookaheadAuthor(authorId: String?): Boolean {
        return authorId?.startsWith(LOOKAHEAD_AUTHOR_PREFIX) == true
    }

    private fun baseAuthorId(authorId: String?): String? {
        val value = authorId ?: return null
        return value.removePrefix(LOOKAHEAD_AUTHOR_PREFIX)
    }

    private fun estimateVisualRangeBounds(
        bounds: BoundsRect,
        textLength: Int,
        start: Int,
        end: Int
    ): BoundsRect? {
        if (textLength <= 0 || end <= start) return null
        val width = bounds.right - bounds.left
        val height = bounds.bottom - bounds.top
        if (width < VISUAL_RANGE_MIN_WIDTH_PX || height < 16) return null

        val lineCount = estimateVisualRangeLineCount(height, textLength)
        val charsPerLine = ((textLength + lineCount - 1) / lineCount).coerceAtLeast(1)
        val lineIndex = (start / charsPerLine).coerceIn(0, lineCount - 1)
        val lineStart = lineIndex * charsPerLine
        val lineEnd = minOf(textLength, lineStart + charsPerLine).coerceAtLeast(lineStart + 1)
        val lineLength = (lineEnd - lineStart).coerceAtLeast(1)
        val localStart = (start - lineStart).coerceIn(0, lineLength)
        val localEnd = (end - lineStart).coerceIn(localStart + 1, lineLength)

        val rawLeft = bounds.left + (width * (localStart.toFloat() / lineLength.toFloat())).toInt()
        val rawRight = bounds.left + (width * (localEnd.toFloat() / lineLength.toFloat())).toInt()
        val center = (rawLeft + rawRight) / 2
        var left = rawLeft - VISUAL_RANGE_HORIZONTAL_PADDING_PX
        var right = rawRight + VISUAL_RANGE_HORIZONTAL_PADDING_PX
        val minWidth = minOf(
            width,
            maxOf(VISUAL_RANGE_MIN_WIDTH_PX, (localEnd - localStart) * 18)
        )

        if (right - left < minWidth) {
            left = center - minWidth / 2
            right = left + minWidth
        }

        if (left < bounds.left) {
            right += bounds.left - left
            left = bounds.left
        }
        if (right > bounds.right) {
            left -= right - bounds.right
            right = bounds.right
        }

        left = left.coerceAtLeast(bounds.left)
        right = right.coerceAtMost(bounds.right)
        if (right - left < VISUAL_RANGE_MIN_WIDTH_PX) return null

        val lineHeight = (height / lineCount).coerceAtLeast(16)
        val top = bounds.top + lineIndex * lineHeight
        val bottom = minOf(bounds.bottom, top + lineHeight)
        if (bottom - top < 16) return null

        return BoundsRect(left, top, right, bottom)
    }

    private fun estimateVisualRangeLineCount(height: Int, textLength: Int): Int {
        if (height <= COMPOSITE_TITLE_ESTIMATED_HEIGHT_PX || textLength <= 24) return 1
        return (height / VISUAL_RANGE_LINE_HEIGHT_PX)
            .coerceAtLeast(1)
            .coerceAtMost(3)
    }

    private fun toCompositeYoutubeTitleTarget(
        node: ParsedTextNode,
        description: String,
        width: Int,
        height: Int
    ): ParsedComment? {
        if (width < 320 || height < 260) return null

        val title = extractCompositeYoutubeTitle(description) ?: return null
        if (title.length !in 2..180) return null
        if (!VisualTextOcrCandidateFilter.shouldAnalyze(title)) return null

        val shortsTitle = isShortsGridCardDescription(description, width, height)
        val playlistTitle = isPlaylistCardDescription(description, width, height)
        val titleBounds = when {
            shortsTitle -> estimateShortsGridTitleBounds(node, width, height)
            playlistTitle -> estimatePlaylistTitleBounds(node, width, height)
            else -> estimateCompositeYoutubeTitleBounds(node, width, height)
        } ?: return null
        return ParsedComment(
            commentText = title,
            boundsInScreen = titleBounds,
            authorId = when {
                shortsTitle -> YOUTUBE_SHORTS_TITLE_AUTHOR_ID
                playlistTitle -> YOUTUBE_STABLE_TITLE_AUTHOR_ID
                else -> COMPOSITE_DESCRIPTION_AUTHOR_ID
            },
            charBoxes = node.charBoxes
        )
    }

    private fun extractCompositeYoutubeTitle(description: String): String? {
        val normalized = description.replace(Regex("\\s+"), " ").trim()
        if (normalized.isBlank()) return null

        val metadataCandidates = metadataTitlePatterns.mapNotNull { pattern ->
            pattern.find(normalized)?.groupValues?.getOrNull(1)?.trim(' ', '-', ',')
        }
        metadataCandidates.firstOrNull { title ->
            title.isNotBlank() && VisualTextOcrCandidateFilter.shouldAnalyze(title)
        }?.let { return it }
        metadataCandidates.firstOrNull { it.isNotBlank() }?.let { return it }

        val cutIndexes = listOfNotNull(
            Regex("""\s+-\s+\d+\s+(?:second|seconds|minute|minutes|hour|hours)(?:,\s*\d+\s+(?:second|seconds|minute|minutes|hour|hours))?\s+-""")
                .find(normalized)
                ?.range
                ?.first,
            normalized.indexOf(" - Go to channel ").takeIf { it >= 0 },
            normalized.indexOf(" - play video").takeIf { it >= 0 },
            normalized.indexOf(" - play Short").takeIf { it >= 0 },
            normalized.indexOf(" - 동영상 재생").takeIf { it >= 0 },
            normalized.indexOf(" - 쇼츠 재생").takeIf { it >= 0 }
        )

        val title = if (cutIndexes.isEmpty()) {
            normalized
        } else {
            normalized.substring(0, cutIndexes.minOrNull() ?: normalized.length)
        }.trim(' ', '-', ',')

        return title.takeIf { it.isNotBlank() }
    }

    private fun estimateCompositeYoutubeTitleBounds(
        node: ParsedTextNode,
        width: Int,
        height: Int
    ): BoundsRect? {
        val top = node.top + (height * 0.78f).toInt()
        val left = node.left + (width * 0.14f).toInt()
        val right = node.right - (width * 0.10f).toInt()
        val bottom = minOf(node.bottom, top + COMPOSITE_TITLE_ESTIMATED_HEIGHT_PX)

        if (right - left < 80 || bottom - top < 24) return null

        return BoundsRect(
            left = left,
            top = top,
            right = right,
            bottom = bottom
        )
    }

    private fun estimateShortsGridTitleBounds(
        node: ParsedTextNode,
        width: Int,
        height: Int
    ): BoundsRect? {
        val titleTopRatio = if (height <= SHORTS_GRID_COMPACT_MAX_HEIGHT_PX) {
            SHORTS_GRID_COMPACT_TITLE_TOP_RATIO
        } else if (height <= SHORTS_GRID_MEDIUM_MAX_HEIGHT_PX) {
            SHORTS_GRID_MEDIUM_TITLE_TOP_RATIO
        } else {
            SHORTS_GRID_TITLE_TOP_RATIO
        }
        val top = node.top + (height * titleTopRatio).toInt()
        val left = node.left
        val right = node.right - (width * 0.04f).toInt()
        val bottom = minOf(node.bottom, top + COMPOSITE_TITLE_ESTIMATED_HEIGHT_PX)

        if (right - left < 80 || bottom - top < 24) return null

        return BoundsRect(
            left = left,
            top = top,
            right = right,
            bottom = bottom
        )
    }

    private fun estimatePlaylistTitleBounds(
        node: ParsedTextNode,
        width: Int,
        height: Int
    ): BoundsRect? {
        val top = maxOf(node.top, node.bottom - PLAYLIST_TITLE_BOTTOM_INSET_PX)
        val left = node.left + (width * 0.14f).toInt()
        val right = node.right - (width * 0.10f).toInt()
        val bottom = minOf(node.bottom, top + COMPOSITE_TITLE_ESTIMATED_HEIGHT_PX)

        if (right - left < 80 || bottom - top < 24) return null

        return BoundsRect(
            left = left,
            top = top,
            right = right,
            bottom = bottom
        )
    }

    private fun isYoutubeUiLabel(text: String, lower: String): Boolean {
        return lower == "filters" ||
            lower == "clear" ||
            lower == "voice search" ||
            lower == "search with your voice" ||
            lower == "cast. disconnected" ||
            lower == "cast" ||
            lower == "more options" ||
            lower == "more actions" ||
            lower == "action menu" ||
            lower == "all" ||
            lower == "shorts" ||
            lower == "unwatched" ||
            lower == "watched" ||
            lower == "videos" ||
            lower == "recently uploaded" ||
            lower == "home" ||
            lower == "subscriptions" ||
            lower == "you" ||
            lower == "premium controls" ||
            lower == "expand mini player" ||
            lower == "new content available" ||
            lower == "subscriptions: new content is available" ||
            lower == "open navigation menu" ||
            lower == "navigate up" ||
            lower.startsWith("go to channel ") ||
            lower.contains("official artist channel") ||
            lower.endsWith(" subscribers") ||
            lower.startsWith("voice search") ||
            lower.startsWith("cast.") ||
            text == "전체" ||
            text == "동영상" ||
            text == "최근 업로드" ||
            text == "홈" ||
            text == "구독" ||
            text == "나"
    }

    private fun looksLikeContentText(text: String): Boolean {
        if (text.length >= 12 && text.any { it.isWhitespace() }) return true
        if (Regex("""[!?！？:\"“”'’]""").containsMatchIn(text)) return true
        return text.any { it.code in 0xAC00..0xD7A3 } && text.length >= 6
    }

    private fun isLikelyYoutubeSearchInput(node: ParsedTextNode): Boolean {
        val width = node.right - node.left
        val height = node.bottom - node.top
        if (node.top > YOUTUBE_SEARCH_INPUT_TOP_MAX_PX) return false
        if (width < YOUTUBE_SEARCH_INPUT_MIN_WIDTH_PX) return false
        if (height !in 24..YOUTUBE_SEARCH_INPUT_MAX_HEIGHT_PX) return false

        val className = node.className.orEmpty()
        val viewId = node.viewIdResourceName.orEmpty()
        return className.contains("EditText", ignoreCase = true) ||
            viewId.contains("search", ignoreCase = true) ||
            viewId.contains("query", ignoreCase = true) ||
            viewId.contains("input", ignoreCase = true)
    }

    private fun stableYoutubeAccessibilityAuthor(
        node: ParsedTextNode,
        text: String,
        width: Int,
        height: Int,
        className: String
    ): String? {
        if (isLikelyYoutubeSearchInput(node)) return null
        if (text.length !in 2..180) return null
        if (width < 60 || height !in 18..160) return null
        if (height > width) return null

        return if (
            className.contains("TextView", ignoreCase = true) ||
            className.contains("ViewGroup", ignoreCase = true) ||
            className.contains("Button", ignoreCase = true)
        ) {
            YOUTUBE_STABLE_TITLE_AUTHOR_ID
        } else {
            null
        }
    }

    private fun isCompositeYoutubeCardDescription(lower: String, width: Int, height: Int): Boolean {
        return (width >= 900 && height >= 180) ||
            lower.contains(" - go to channel ") ||
            lower.endsWith(" - play video") ||
            lower.endsWith(" - play short") ||
            lower.contains(" views,") && lower.contains("play short") ||
            lower.contains(" views - ") ||
            Regex("""\bviews?\s*[·•]\s*""").containsMatchIn(lower) ||
            Regex("""\b\d+(?:\.\d+)?\s*[kmb]\s+views?\b""").containsMatchIn(lower) ||
            lower.contains("조회수") && lower.contains("전")
    }

    private fun isShortsGridCardDescription(description: String, width: Int, height: Int): Boolean {
        val lower = description.lowercase()
        return lower.contains(" - play short") &&
            width in 320..720 &&
            height >= 260
    }

    private fun isPlaylistCardDescription(description: String, width: Int, height: Int): Boolean {
        val lower = description.lowercase()
        return lower.startsWith("playlist - ") &&
            Regex("""\s+-\s+\d+\s+videos?\b""", RegexOption.IGNORE_CASE).containsMatchIn(description) &&
            width >= 900 &&
            height >= 260
    }

    private fun looksLikeRelativeTime(text: String): Boolean {
        val lower = text.lowercase()
        return lower.endsWith("ago") ||
            text.endsWith("초 전") ||
            text.endsWith("분 전") ||
            text.endsWith("시간 전") ||
            text.endsWith("일 전") ||
            text.endsWith("주 전") ||
            text.endsWith("개월 전") ||
            text.endsWith("년 전")
    }

    private val metadataTitlePatterns = listOf(
        Regex("""^Playlist\s+-\s+(.+?)\s+-\s+[^-]{1,80}\s+-\s+\d+\s+videos?\b""", RegexOption.IGNORE_CASE),
        Regex("""^(.+?),\s*[^,]{1,80},\s*[\d.]+\s*(?:[kmb]|thousand|million|billion)?\s+views\b""", RegexOption.IGNORE_CASE),
        Regex("""^(.+?),\s*[\d.]+\s*(?:[kmb]|thousand|million|billion)?\s+views\b""", RegexOption.IGNORE_CASE),
        Regex("""^(.+?)\s*[·•]\s*[^·•]{1,80}\s*[·•]\s*[\d.]+\s*(?:[kmb]|thousand|million|billion)?\s+views\b""", RegexOption.IGNORE_CASE),
        Regex("""^(.+?),\s*[^,]{1,80},\s*조회수\s*[\d.,]+[천만억]?\s*회?"""),
        Regex("""^(.+?),\s*조회수\s*[\d.,]+[천만억]?\s*회?""")
    )

    private const val COMPOSITE_DESCRIPTION_AUTHOR_ID = "youtube-composite-description"

    private fun shouldTreatAsCompositeYoutubeCard(
        text: String,
        lower: String,
        width: Int,
        height: Int
    ): Boolean {
        if (isCompositeYoutubeCardDescription(lower, width, height)) return true

        // Some YouTube result nodes expose one large contentDescription without a
        // stable "play video" suffix. If it is a large card and contains an
        // analyzable profanity-like token, estimate the title row instead of
        // sending the whole card bounds to the overlay planner.
        return width >= 320 &&
            height >= 180 &&
            VisualTextOcrCandidateFilter.shouldAnalyze(text)
    }
}
