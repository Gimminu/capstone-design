package com.capstone.design.youtubeparser

import org.junit.Assert.assertEquals
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
        assertEquals("android-accessibility-comment:youtube:@creator", comments.single().authorId)
    }

    @Test
    fun youtubeExtractor_mergesDrawerCommentLinesAndStripsReadMore() {
        val comments = YoutubeCommentExtractor.extractComments(
            listOf(
                node(text = "Comments", top = 700, bottom = 760, left = 40, width = 240),
                node(text = "6 replies", top = 850, bottom = 890, left = 132, width = 160),
                node(text = "@cloudd9619 • 3mo ago (edited)", top = 980, bottom = 1024, left = 132, width = 560),
                node(text = "또 다시 보여줘야돼가", top = 1040, bottom = 1084, left = 132, width = 640),
                node(text = "내가 다시 보여줘야된다 이게 아니라", top = 1092, bottom = 1136, left = 132, width = 760),
                node(text = "하...씨발...또 다시 보여줘야돼? 그래야 믿어?이런거같아서", top = 1144, bottom = 1188, left = 132, width = 860),
                node(text = "이게 존나 야마있네... Read more", top = 1196, bottom = 1240, left = 132, width = 760),
                node(text = "565", top = 1300, bottom = 1340, left = 132, width = 90),
                node(text = "2 replies", top = 1420, bottom = 1460, left = 132, width = 170),
                node(text = "@그랜드슬램1 • 4mo ago", top = 1540, bottom = 1584, left = 132, width = 520),
                node(text = "노래 뒤지게 좋네 ㅋㅋㅋㅋㅋㅋ 이건 진짜 들어도 안질린다", top = 1600, bottom = 1644, left = 132, width = 840),
                node(text = "십", top = 1652, bottom = 1696, left = 132, width = 80),
                node(text = "536", top = 1760, bottom = 1800, left = 132, width = 90)
            )
        )

        assertEquals(2, comments.size)
        assertEquals(
            listOf(
                "또 다시 보여줘야돼가\n내가 다시 보여줘야된다 이게 아니라\n하...씨발...또 다시 보여줘야돼? 그래야 믿어?이런거같아서\n이게 존나 야마있네...",
                "노래 뒤지게 좋네 ㅋㅋㅋㅋㅋㅋ 이건 진짜 들어도 안질린다\n십"
            ),
            comments.map { it.commentText }
        )
        assertEquals(BoundsRect(132, 1040, 992, 1240), comments.first().boundsInScreen)
        assertEquals("android-accessibility-comment:youtube:@cloudd9619", comments.first().authorId)
        assertEquals("android-accessibility-comment:youtube:@그랜드슬램1", comments.last().authorId)
    }

    @Test
    fun youtubeExtractor_keepsBodyTextThatMentionsCommentOrReply() {
        val comments = YoutubeCommentExtractor.extractComments(
            listOf(
                node(text = "@creator • 4mo ago", top = 1000, bottom = 1040),
                node(text = "이 댓글 답글까지 진짜 이상하네", top = 1050, bottom = 1110, width = 720),
                node(text = "2 replies", top = 1180, bottom = 1220)
            )
        )

        assertEquals(1, comments.size)
        assertEquals("이 댓글 답글까지 진짜 이상하네", comments.single().commentText)
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
