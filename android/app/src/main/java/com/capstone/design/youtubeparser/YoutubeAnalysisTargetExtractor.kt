package com.capstone.design.youtubeparser

object YoutubeAnalysisTargetExtractor {
    private const val MAX_TARGET_COUNT = 40

    fun extractTargets(nodes: List<ParsedTextNode>): List<ParsedComment> {
        val commentTargets = YoutubeCommentExtractor.extractComments(nodes)
        val standaloneTargets = nodes
            .asSequence()
            .mapNotNull { toStandaloneTarget(it) }
            .toList()

        return (standaloneTargets + commentTargets)
            .distinctBy { target ->
                val normalizedText = target.commentText.replace(Regex("\\s+"), " ").trim()
                "${normalizedText}|${target.boundsInScreen.left}|${target.boundsInScreen.top}"
            }
            .sortedWith(compareBy<ParsedComment> { it.boundsInScreen.top }.thenBy { it.boundsInScreen.left })
            .take(MAX_TARGET_COUNT)
    }

    private fun isUsefulStandaloneTarget(node: ParsedTextNode): Boolean {
        return toStandaloneTarget(node) != null
    }

    private fun toStandaloneTarget(node: ParsedTextNode): ParsedComment? {
        val text = node.displayText.orEmpty().replace(Regex("\\s+"), " ").trim()
        val lower = text.lowercase()
        val width = node.right - node.left
        val height = node.bottom - node.top
        val className = node.className.orEmpty()
        val contentDescriptionOnly = node.text.isNullOrBlank() && !node.contentDescription.isNullOrBlank()

        if (!node.isVisibleToUser) return null
        if (width < 24 || height < 16) return null
        if (contentDescriptionOnly && isCompositeYoutubeCardDescription(lower, width, height)) {
            val isShortsGridCard = isShortsGridCardDescription(lower, width, height)
            val title = extractCompositeYoutubeTitle(text) ?: return null
            if (!isPlausibleContentTitle(title)) return null
            return ParsedComment(
                commentText = title,
                boundsInScreen = estimateCompositeTitleBounds(node, isShortsGridCard)
            )
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
            )
        )
    }

    private fun isYoutubeUiLabel(text: String, lower: String): Boolean {
        return lower == "filters" ||
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
            lower.startsWith("go to channel ") ||
            lower.contains("official artist channel") ||
            lower.endsWith(" subscribers") ||
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

    private fun isPlausibleContentTitle(text: String): Boolean {
        val normalized = text.replace(Regex("\\s+"), " ").trim()
        val lower = normalized.lowercase()
        if (normalized.length !in 2..180) return false
        if (isYoutubeUiLabel(normalized, lower)) return false
        if (looksLikeRelativeTime(normalized)) return false
        if (lower.startsWith("http://") || lower.startsWith("https://")) return false
        if (lower.contains(" views") || lower.contains("조회수")) return false
        return true
    }

    private fun extractCompositeYoutubeTitle(text: String): String? {
        val normalized = text.replace(Regex("\\s+"), " ").trim()
        if (normalized.isBlank()) return null

        val metadataStart = Regex(
            pattern = """\s+-\s+(?:(?:\d+\s+hours?(?:,\s*\d+\s+minutes?)?)|(?:\d+\s+minutes?(?:,\s*\d+\s+seconds?)?)|(?:\d+\s+seconds?)|(?:\d{1,2}:\d{2}(?::\d{2})?)|(?:\d+\s*시간)|(?:\d+\s*분(?:\s*\d+\s*초)?)|(?:\d+\s*초))\s+-\s+go to channel\s+""",
            option = RegexOption.IGNORE_CASE
        ).find(normalized)?.range?.first
        val shortsMetadataStart = Regex(
            pattern = """,\s*[\d,.]+\s*(?:thousand|million|billion|[kmb])?\s+views,\s*""",
            option = RegexOption.IGNORE_CASE
        ).find(normalized)?.range?.first

        val rawTitle = when {
            metadataStart != null -> normalized.substring(0, metadataStart)
            shortsMetadataStart != null -> normalized.substring(0, shortsMetadataStart)
            normalized.lowercase().contains(" - go to channel ") ->
                normalized.substringBefore(" - Go to channel ").substringBefore(" - go to channel ")
            normalized.lowercase().endsWith(" - play video") -> normalized.substringBeforeLast(" - ")
            normalized.lowercase().endsWith(" - play short") -> normalized.substringBeforeLast(" - ")
            else -> return null
        }.trim()

        val titleWithoutChannelSuffix = rawTitle.indexOf('_')
            .takeIf { it > 0 }
            ?.let { rawTitle.substring(0, it).trim() }
            ?: rawTitle

        return titleWithoutChannelSuffix
            .removeSuffix("-")
            .trim()
            .ifBlank { null }
    }

    private fun estimateCompositeTitleBounds(node: ParsedTextNode, isShortsGridCard: Boolean): BoundsRect {
        val width = node.right - node.left
        val height = node.bottom - node.top
        val horizontalInsetLeft = minOf(160, maxOf(0, width / 6))
        val horizontalInsetRight = minOf(105, maxOf(0, width / 10))

        if (isShortsGridCard) {
            val titleHeight = minOf(76, maxOf(48, height / 9))
            return BoundsRect(
                left = node.left,
                top = node.top,
                right = node.right,
                bottom = minOf(node.bottom, node.top + titleHeight)
            )
        }

        if (height >= 300) {
            val titleTop = node.top + (height * 0.80f).toInt()
            val titleHeight = minOf(128, maxOf(56, height / 5))
            return BoundsRect(
                left = node.left + horizontalInsetLeft,
                top = titleTop,
                right = node.right - horizontalInsetRight,
                bottom = minOf(node.bottom, titleTop + titleHeight)
            )
        }

        val titleBottom = minOf(node.bottom, node.top + minOf(96, maxOf(48, (height * 0.65f).toInt())))
        return BoundsRect(
            left = node.left + horizontalInsetLeft,
            top = node.top,
            right = node.right - horizontalInsetRight,
            bottom = titleBottom
        )
    }

    private fun isCompositeYoutubeCardDescription(lower: String, width: Int, height: Int): Boolean {
        return (width >= 900 && height >= 180) ||
            lower.contains(" - go to channel ") ||
            lower.endsWith(" - play video") ||
            lower.endsWith(" - play short") ||
            lower.contains(" views,") && lower.contains("play short") ||
            lower.contains(" views - ") ||
            lower.contains("조회수") && lower.contains("전")
    }

    private fun isShortsGridCardDescription(lower: String, width: Int, height: Int): Boolean {
        return height >= 240 &&
            height > width &&
            (lower.endsWith(" - play short") || lower.contains("play short"))
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
}
