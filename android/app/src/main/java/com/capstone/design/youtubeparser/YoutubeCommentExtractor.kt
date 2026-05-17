package com.capstone.design.youtubeparser

import kotlin.math.abs

object YoutubeCommentExtractor {
    private const val YOUTUBE_COMMENT_AUTHOR_PREFIX = "android-accessibility-comment:youtube"
    private const val MAX_AUTHOR_TO_BODY_GAP_PX = 280
    private const val MAX_BODY_LINE_GAP_PX = 72
    private const val MAX_COMMENT_BODY_NODE_COUNT = 8

    fun extractComments(nodes: List<ParsedTextNode>): List<ParsedComment> {
        val sorted = nodes.sortedWith(compareBy<ParsedTextNode> { it.top }.thenBy { it.left })
        val result = mutableListOf<ParsedComment>()

        for (i in sorted.indices) {
            val author = sorted[i]
            val authorText = author.displayText.orEmpty()

            if (!looksLikeAuthor(authorText, sorted, i)) continue

            val bodyNodes = collectCommentBodyNodes(sorted, author, i)
            val bodyText = mergeBodyText(bodyNodes) ?: continue
            val bounds = unionBounds(bodyNodes)

            result += ParsedComment(
                commentText = bodyText,
                boundsInScreen = bounds,
                authorId = stableCommentAuthorId(authorText, author.top)
            )
        }

        return result.distinctBy {
            "${it.authorId}|${it.commentText.replace(Regex("\\s+"), " ").trim()}|${it.boundsInScreen.top}|${it.boundsInScreen.left}"
        }
    }

    private fun collectCommentBodyNodes(
        sorted: List<ParsedTextNode>,
        author: ParsedTextNode,
        authorIndex: Int
    ): List<ParsedTextNode> {
        val bodyNodes = mutableListOf<ParsedTextNode>()

        for (j in authorIndex + 1 until sorted.size) {
            val node = sorted[j]
            val text = node.displayText.orEmpty()

            if (node.top - author.bottom < 0) continue
            if (node.top - author.bottom > MAX_AUTHOR_TO_BODY_GAP_PX) break

            if (looksLikeAuthor(text, sorted, j)) break

            if (isUiJunk(text) || looksLikeTime(text)) {
                if (bodyNodes.isNotEmpty()) break
                continue
            }

            val score = scoreAsCommentBody(author, node, text)
            if (score <= 0) {
                if (bodyNodes.isNotEmpty()) break
                continue
            }

            if (bodyNodes.isEmpty()) {
                bodyNodes += node
                continue
            }

            val previous = bodyNodes.last()
            val lineGap = node.top - previous.bottom
            val alignedWithBody = abs(node.left - previous.left) <= 80
            val alignedWithAuthor = abs(node.left - author.left) <= 100
            if (lineGap in 0..MAX_BODY_LINE_GAP_PX && (alignedWithBody || alignedWithAuthor)) {
                bodyNodes += node
                if (bodyNodes.size >= MAX_COMMENT_BODY_NODE_COUNT) break
            } else {
                break
            }
        }

        return bodyNodes
    }

    private fun mergeBodyText(nodes: List<ParsedTextNode>): String? {
        val lines = nodes
            .mapNotNull { node -> cleanBodyText(node.displayText.orEmpty()) }
            .filter { it.isNotBlank() }

        if (lines.isEmpty()) return null
        return lines.joinToString("\n")
    }

    private fun cleanBodyText(text: String): String? {
        var cleaned = text
            .replace('\u00a0', ' ')
            .trim()

        if (cleaned.isBlank()) return null
        if (isUiJunk(cleaned) || looksLikeTime(cleaned)) return null

        cleaned = cleaned
            .replace(Regex("""\s+Read more$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+더보기$"""), "")
            .trim()

        return cleaned.takeIf { it.isNotBlank() }
    }

    private fun unionBounds(nodes: List<ParsedTextNode>): BoundsRect {
        return BoundsRect(
            left = nodes.minOf { it.left },
            top = nodes.minOf { it.top },
            right = nodes.maxOf { it.right },
            bottom = nodes.maxOf { it.bottom }
        )
    }

    private fun scoreAsCommentBody(
        author: ParsedTextNode,
        node: ParsedTextNode,
        text: String
    ): Int {
        if (text.isBlank()) return 0
        if (looksLikeAuthor(text, emptyList(), -1)) return 0
        if (looksLikeTime(text)) return 0
        if (isUiJunk(text)) return 0

        var score = 0

        val sameColumn = abs(node.left - author.left) <= 60
        if (sameColumn) score += 50

        val verticalGap = node.top - author.bottom
        if (verticalGap in 0..90) score += 40
        else if (verticalGap in 91..180) score += 20

        val width = node.right - node.left
        if (width >= 500) score += 30
        else if (width >= 250) score += 15

        val len = text.length
        if (len >= 3) score += 10
        if (len >= 5) score += 20
        if (len >= 20) score += 15

        if (text.contains("\n")) score += 10

        return score
    }

    private fun looksLikeAuthor(text: String, nodes: List<ParsedTextNode>, index: Int): Boolean {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return false

        if (isUiJunk(trimmed)) return false

        if (trimmed.startsWith("@") && trimmed.length >= 2) {
            if (looksLikeTime(trimmed)) return true
            if (index in nodes.indices) {
                val current = nodes[index]
                for (j in index + 1 until minOf(index + 4, nodes.size)) {
                    val next = nodes[j]
                    if (next.top - current.top > 90) break
                    if (looksLikeTime(next.displayText.orEmpty())) return true
                }
            }
            return false
        }

        if (looksLikeTime(trimmed)) return false

        if (trimmed.length in 2..30 && trimmed.none { it.isDigit() }) {
            if (index in nodes.indices) {
                val current = nodes[index]
                for (j in index + 1 until minOf(index + 4, nodes.size)) {
                    val next = nodes[j]
                    if (next.top - current.top > 80) break
                    if (looksLikeTime(next.displayText.orEmpty())) return true
                }
            }
        }

        return false
    }

    private fun stableCommentAuthorId(authorText: String, authorTop: Int): String {
        val key = authorText
            .trim()
            .substringBefore("•")
            .substringBefore("·")
            .replace(Regex("""[^\p{L}\p{N}@._-]+"""), "_")
            .trim('_')
            .take(48)
            .ifBlank { "row$authorTop" }

        return "$YOUTUBE_COMMENT_AUTHOR_PREFIX:$key"
    }

    private fun looksLikeTime(text: String): Boolean {
        val lower = text.lowercase()
        return lower.contains("ago") ||
            text.contains("초 전") ||
            text.contains("분 전") ||
            text.contains("시간 전") ||
            text.contains("일 전") ||
            text.contains("주 전") ||
            text.contains("개월 전")
    }

    private fun isUiJunk(text: String): Boolean {
        val trimmed = text.trim()
        val lower = trimmed.lowercase()

        if (lower == "comments") return true
        if (lower.startsWith("comments.")) return true
        if (lower == "sort comments") return true
        if (lower == "reply") return true
        if (lower == "reply...") return true
        if (lower == "comment...") return true
        if (lower == "read more") return true
        if (Regex("""^\d+\s+repl(?:y|ies)$""", RegexOption.IGNORE_CASE).matches(trimmed)) return true
        if (Regex("""^[\d,]+$""").matches(trimmed)) return true
        if (lower == "view reply") return true
        if (lower.startsWith("view ") && lower.contains(" total replies")) return true
        if (lower.contains("like this comment")) return true
        if (lower.contains("like this reply")) return true
        if (lower.contains("dislike this comment")) return true
        if (lower.contains("dislike this reply")) return true
        if (lower.contains("action menu")) return true
        if (lower.contains("open camera")) return true
        if (lower.contains("drag handle")) return true
        if (lower.contains("video player")) return true
        if (lower.contains("minutes")) return true
        if (lower.contains("seconds")) return true
        if (lower == "back") return true
        if (lower == "close") return true
        if (lower.endsWith(" likes") || lower.endsWith(" like")) return true
        if (trimmed == "답글") return true
        if (Regex("""^\d+\s*답글""").containsMatchIn(trimmed)) return true
        if (trimmed == "댓글") return true
        if (Regex("""^댓글\s*\d+\s*개$""").matches(trimmed)) return true
        if (trimmed.contains("정렬")) return true
        if (trimmed == "뒤로") return true
        if (trimmed == "닫기") return true
        if (trimmed == "더보기") return true
        if (trimmed.endsWith("좋아요")) return true

        return false
    }
}
