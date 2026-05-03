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
            // Composite card descriptions do not expose reliable glyph bounds.
            // Masking them creates floating overlays on thumbnails/cards, so keep
            // the Android path limited to nodes with real text bounds.
            return null
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

    private fun isCompositeYoutubeCardDescription(lower: String, width: Int, height: Int): Boolean {
        return (width >= 900 && height >= 180) ||
            lower.contains(" - go to channel ") ||
            lower.endsWith(" - play video") ||
            lower.endsWith(" - play short") ||
            lower.contains(" views,") && lower.contains("play short") ||
            lower.contains(" views - ") ||
            lower.contains("조회수") && lower.contains("전")
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
