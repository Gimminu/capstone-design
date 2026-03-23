package com.capstone.design.youtubeparser

import kotlin.math.abs

object TiktokCommentExtractor {

    fun extractComments(nodes: List<ParsedTextNode>): List<ParsedComment> {
        if (nodes.isEmpty()) return emptyList()

        val sorted = nodes.mapNotNull { node ->
            val text = node.displayText?.trim() ?: return@mapNotNull null
            if (text.isBlank()) return@mapNotNull null
            node.copy(displayText = text)
        }.sortedWith(compareBy<ParsedTextNode> { it.top }.thenBy { it.left })

        val results = mutableListOf<ParsedComment>()

        for (i in sorted.indices) {
            val authorNode = sorted[i]
            val authorText = authorNode.displayText ?: continue
            if (!looksLikeStrongUsername(authorText)) continue

            val candidates = mutableListOf<Pair<ParsedTextNode, Int>>()

            for (j in i + 1 until sorted.size) {
                val next = sorted[j]
                val nextText = next.displayText ?: continue

                val gapFromAuthorTop = next.top - authorNode.top
                if (gapFromAuthorTop > 220) break

                if (looksLikeStrongUsername(nextText) && gapFromAuthorTop > 12) break
                if (isDateText(nextText)) continue
                if (isMetaText(nextText)) continue
                if (isCountOnlyText(nextText)) continue
                if (!isLikelyCommentBody(nextText)) continue

                val score = scoreAsCommentBody(authorNode, next, nextText)
                if (score > 0) {
                    candidates += next to score
                }
            }

            val best = candidates.maxByOrNull { it.second }?.first ?: continue
            val bestText = best.displayText ?: continue

            results += ParsedComment(
                commentText = bestText,
                boundsInScreen = BoundsRect(best.left, best.top, best.right, best.bottom),
                authorId = normalizeAuthor(authorText)
            )
        }

        for (node in sorted) {
            val text = node.displayText ?: continue
            if (!isLikelyCommentBody(text)) continue
            if (looksLikeStrongUsername(text)) continue
            if (isDateText(text)) continue
            if (isMetaText(text)) continue
            if (isCountOnlyText(text)) continue

            results += ParsedComment(
                commentText = text,
                boundsInScreen = BoundsRect(node.left, node.top, node.right, node.bottom)
            )
        }

        return results.distinctBy {
            "${it.commentText}|${it.boundsInScreen.top}|${it.boundsInScreen.left}"
        }
    }

    private fun scoreAsCommentBody(
        author: ParsedTextNode,
        bodyNode: ParsedTextNode,
        body: String
    ): Int {
        if (!isLikelyCommentBody(body)) return 0

        var score = 0

        val leftGap = abs(bodyNode.left - author.left)
        val verticalGap = bodyNode.top - author.bottom
        val width = bodyNode.right - bodyNode.left

        if (leftGap <= 80) score += 45
        else if (leftGap <= 140) score += 20

        if (verticalGap in 0..90) score += 40
        else if (verticalGap in 91..170) score += 20
        else if (verticalGap !in 0..220) return 0

        if (width >= 300) score += 20
        else if (width >= 180) score += 10

        if (body.length >= 3) score += 10
        if (body.length >= 8) score += 10
        if (body.contains(" ")) score += 10
        if (body.count { it in '\uAC00'..'\uD7A3' } >= 2) score += 10
        if (body.any { it in listOf('…', '.', ',', '!', '?', 'ㅋ', 'ㅎ', 'ㅠ', 'ㅜ') }) score += 8

        return score
    }

    private fun normalizeAuthor(text: String): String {
        return text.trim().removePrefix("@")
    }

    private fun looksLikeStrongUsername(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return false
        if (trimmed.contains(" ")) return false
        if (trimmed.length !in 2..30) return false
        if (isDateText(trimmed)) return false
        if (isMetaText(trimmed)) return false
        if (isCountOnlyText(trimmed)) return false

        if (trimmed.startsWith("@")) return true

        if (trimmed.any { it == '_' || it == '.' || it.isDigit() }) {
            return trimmed.all {
                it.isLetterOrDigit() || it == '_' || it == '.' || it in '\uAC00'..'\uD7A3'
            }
        }

        return false
    }

    private fun isDateText(text: String): Boolean {
        val lower = text.trim().lowercase()
        return lower.endsWith("초 전") ||
            lower.endsWith("분 전") ||
            lower.endsWith("시간 전") ||
            lower.endsWith("일 전") ||
            lower.endsWith("주 전") ||
            lower.endsWith("개월 전") ||
            Regex("""^\d{2}-\d{2}$""").matches(lower) ||
            Regex("""^\d{1,2}:\d{2}$""").matches(lower) ||
            Regex("""^\d+월\s*\d+일$""").matches(lower) ||
            Regex("""^\d+[smhdw]$""").matches(lower) ||
            Regex("""^\d+\s*(sec|min|hr|day|week|mo)s?\s*ago$""").matches(lower) ||
            lower == "now"
    }

    private fun isCountOnlyText(text: String): Boolean {
        return Regex("""^[\d,]+$""").matches(text.trim())
    }

    private fun isMetaText(text: String): Boolean {
        val trimmed = text.trim()
        val lower = trimmed.lowercase()
        if (lower.isBlank()) return true

        return lower == "reply" ||
            lower == "replies" ||
            lower == "view replies" ||
            lower == "view more replies" ||
            lower == "like" ||
            lower == "likes" ||
            lower == "follow" ||
            lower == "following" ||
            lower == "for you" ||
            lower == "friends" ||
            lower == "share" ||
            lower == "search" ||
            lower == "live" ||
            lower == "profile" ||
            lower == "home" ||
            lower == "inbox" ||
            lower == "comment" ||
            lower == "comments" ||
            lower == "reply..." ||
            lower == "comment..." ||
            lower == "back" ||
            lower == "close" ||
            lower == "see more" ||
            lower == "more" ||
            lower == "original poster" ||
            lower == "creator" ||
            lower == "답글" ||
            lower == "좋아요" ||
            lower == "팔로우" ||
            lower == "팔로잉" ||
            lower == "추천" ||
            lower == "친구" ||
            lower == "공유" ||
            lower == "검색" ||
            lower == "프로필" ||
            lower == "홈" ||
            lower == "받은편지함" ||
            lower == "댓글" ||
            lower == "라이브" ||
            lower == "더 보기" ||
            lower == "닫기" ||
            lower == "뒤로" ||
            lower == "다른 사용자 멘션" ||
            lower == "알림" ||
            lower == "스티커" ||
            lower == "엄지척" ||
            lower == "아래" ||
            lower == "동영상" ||
            lower == "video" ||
            lower == "notification" ||
            lower == "sticker" ||
            lower.contains("멘션") ||
            lower.contains("따듯한 말 한마디 해주세요") ||
            lower.contains("자세히") ||
            lower.contains("reply") ||
            lower.endsWith(" likes") ||
            lower.endsWith(" like") ||
            lower.endsWith("좋아요") ||
            Regex(""".+님의 프로필$""").matches(trimmed) ||
            Regex("""^[\u200E\u200F\u202A-\u202E]*댓글\s*\d+개$""").matches(trimmed)
    }

    private fun isLikelyCommentBody(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.length < 2) return false
        if (isDateText(trimmed)) return false
        if (isMetaText(trimmed)) return false
        if (isCountOnlyText(trimmed)) return false
        if (trimmed.startsWith("#")) return false
        if (trimmed.count { it == '#' } >= 1) return false
        if (hasTooManyHashtags(trimmed)) return false

        val koreanCount = trimmed.count { it in '\uAC00'..'\uD7A3' }
        if (koreanCount >= 2) return true
        if (trimmed.contains(" ")) return true
        if (trimmed.length >= 5) return true
        if (trimmed.any { it in listOf('…', '.', ',', '!', '?', 'ㅋ', 'ㅎ', 'ㅠ', 'ㅜ') }) return true
        if (trimmed.any { Character.getType(it) == Character.OTHER_SYMBOL.toInt() }) return true

        return false
    }

    private fun hasTooManyHashtags(text: String): Boolean {
        return text.count { it == '#' } >= 4
    }
}
