package com.capstone.design.youtubeparser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CommentExtractorTest {

    @Test
    fun youtubeExtractor_picksNearestCommentBody() {
        val comments = YoutubeCommentExtractor.extractComments(
            listOf(
                node(text = "@creator", top = 1000, bottom = 1040),
                node(text = "2시간 전", top = 1048, bottom = 1080),
                node(text = "이 영상 정리 진짜 깔끔하네요", top = 1088, bottom = 1148, width = 720),
                node(text = "답글", top = 1156, bottom = 1184)
            )
        )

        assertEquals(1, comments.size)
        assertEquals("이 영상 정리 진짜 깔끔하네요", comments.single().commentText)
        assertNull(comments.single().authorId)
    }

    @Test
    fun instagramExtractor_supportsCombinedCommentText() {
        val comments = InstagramCommentExtractor.extractComments(
            listOf(
                node(text = "user.name 이 장면 너무 좋네요", top = 1200, bottom = 1260),
                node(text = "답글", top = 1268, bottom = 1296)
            )
        )

        assertEquals(1, comments.size)
        assertEquals("이 장면 너무 좋네요", comments.single().commentText)
    }

    @Test
    fun tiktokExtractor_keepsAuthorId() {
        val comments = TiktokCommentExtractor.extractComments(
            listOf(
                node(text = "@creator_12", top = 1100, bottom = 1140),
                node(text = "2일 전", top = 1148, bottom = 1176),
                node(text = "이 부분 진짜 웃겨요", top = 1184, bottom = 1244, width = 680)
            )
        )

        assertEquals(1, comments.size)
        assertEquals("이 부분 진짜 웃겨요", comments.single().commentText)
        assertEquals("creator_12", comments.single().authorId)
    }

    private fun node(
        text: String,
        top: Int,
        bottom: Int,
        left: Int = 64,
        width: Int = 640
    ): ParsedTextNode {
        return ParsedTextNode(
            packageName = "test.package",
            text = text,
            contentDescription = null,
            displayText = text,
            className = "android.widget.TextView",
            viewIdResourceName = null,
            left = left,
            top = top,
            right = left + width,
            bottom = bottom,
            approxTop = top,
            isVisibleToUser = true
        )
    }
}
