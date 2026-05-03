package com.capstone.design.youtubeparser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YoutubeAnalysisTargetExtractorTest {

    @Test
    fun extractTargets_includesVisibleVideoTitleLikeText() {
        val targets = YoutubeAnalysisTargetExtractor.extractTargets(
            listOf(
                node("Share", 20, 600, 120, 650, className = "android.widget.Button"),
                node("TLqkf 또 보여줘야 돼! : 식케이", 10, 350, 700, 430),
                node("Subscribe", 500, 500, 700, 560, className = "android.widget.Button")
            )
        )

        assertEquals(1, targets.size)
        assertEquals("TLqkf 또 보여줘야 돼! : 식케이", targets.single().commentText)
        assertEquals(BoundsRect(10, 350, 700, 430), targets.single().boundsInScreen)
    }

    @Test
    fun extractTargets_allowsLongContentTextEvenWhenYoutubeExposesItAsButton() {
        val targets = YoutubeAnalysisTargetExtractor.extractTargets(
            listOf(
                node("🔥\"TLqkf 또 보여줘야 돼!\" : 식케이", 10, 560, 980, 650, className = "android.widget.Button"),
                node("Subscribe", 800, 700, 1000, 760, className = "android.widget.Button")
            )
        )

        assertEquals(listOf("🔥\"TLqkf 또 보여줘야 돼!\" : 식케이"), targets.map { it.commentText })
    }

    @Test
    fun extractTargets_extractsTitleFromCompositeSearchResultContentDescriptions() {
        val targets = YoutubeAnalysisTargetExtractor.extractTargets(
            listOf(
                contentDescriptionNode(
                    "What is 'Tlqkf'? - Go to channel Contemporary Korean Slang - 40 thousand views - 5 years ago - play video",
                    0,
                    315,
                    1080,
                    1090
                ),
                node("🔥\"TLqkf 또 보여줘야 돼!\" : 식케이", 20, 500, 960, 570)
            )
        )

        assertEquals(
            listOf("🔥\"TLqkf 또 보여줘야 돼!\" : 식케이", "What is 'Tlqkf'?"),
            targets.map { it.commentText }
        )
    }

    @Test
    fun extractTargets_extractsVisibleTitleFromLargeVideoCardDescription() {
        val targets = YoutubeAnalysisTargetExtractor.extractTargets(
            listOf(
                contentDescriptionNode(
                    "🔥\"Tlqkf 또 보여줘야 돼!\" : 식케이 (Sik-K), Lil Moshpit - LOV3 - 4 minutes, 46 seconds - Go to channel 세모플 semo playlist - 866 thousand views - 6 months ago - play video",
                    0,
                    629,
                    1080,
                    1426
                )
            )
        )

        assertEquals(listOf("🔥\"Tlqkf 또 보여줘야 돼!\" : 식케이 (Sik-K), Lil Moshpit - LOV3"), targets.map { it.commentText })
        assertEquals(BoundsRect(160, 1266, 975, 1394), targets.single().boundsInScreen)
    }

    @Test
    fun extractTargets_extractsShortsGridTitleWithoutMaskingWholeCard() {
        val targets = YoutubeAnalysisTargetExtractor.extractTargets(
            listOf(
                contentDescriptionNode(
                    "tlqkf비용 효과 있을까?, 89 thousand views, 1분만, 1 year ago - play Short",
                    32,
                    1069,
                    529,
                    1954
                )
            )
        )

        assertEquals(listOf("tlqkf비용 효과 있을까?"), targets.map { it.commentText })
        assertEquals(BoundsRect(32, 1069, 529, 1145), targets.single().boundsInScreen)
    }

    @Test
    fun extractTargets_rejectsUiCountersAndActions() {
        val targets = YoutubeAnalysisTargetExtractor.extractTargets(
            listOf(
                node("8.4K", 10, 700, 80, 740),
                node("Share", 100, 700, 200, 740),
                node("Subscribe to 세모플 semo playlist.", 100, 760, 500, 800),
                node("Save to playlist", 100, 820, 300, 860),
                node("Next:", 100, 880, 200, 920),
                node("댓글", 10, 760, 120, 800),
                node("filters", 0, 189, 1080, 315),
                node("All", 42, 210, 146, 294),
                node("Unwatched", 357, 210, 599, 294),
                node("Go to channel Contemporary Korean Slang", 32, 932, 127, 1027),
                node("Home", 70, 2293, 147, 2323),
                node("정상 문장입니다", 20, 940, 300, 990)
            )
        )

        assertEquals(listOf("정상 문장입니다"), targets.map { it.commentText })
    }

    @Test
    fun extractTargets_preservesExtractorCommentsAndDeduplicatesByTextAndPosition() {
        val nodes = listOf(
            node("@author", 20, 200, 180, 240),
            node("1일 전", 190, 200, 260, 240),
            node("시발 뭐하는 거야", 20, 250, 520, 310),
            node("시발 뭐하는 거야", 20, 250, 520, 310)
        )

        val targets = YoutubeAnalysisTargetExtractor.extractTargets(nodes)

        assertEquals(1, targets.size)
        assertTrue(targets.all { it.commentText == "시발 뭐하는 거야" })
    }

    private fun node(
        text: String,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        className: String = "android.widget.TextView",
        contentDescription: String? = null
    ): ParsedTextNode {
        return ParsedTextNode(
            packageName = "com.google.android.youtube",
            text = text,
            contentDescription = contentDescription,
            displayText = text.ifBlank { contentDescription },
            className = className,
            viewIdResourceName = null,
            left = left,
            top = top,
            right = right,
            bottom = bottom,
            approxTop = top,
            isVisibleToUser = true
        )
    }

    private fun contentDescriptionNode(
        displayText: String,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ): ParsedTextNode {
        return ParsedTextNode(
            packageName = "com.google.android.youtube",
            text = null,
            contentDescription = displayText,
            displayText = displayText,
            className = "android.view.View",
            viewIdResourceName = null,
            left = left,
            top = top,
            right = right,
            bottom = bottom,
            approxTop = top,
            isVisibleToUser = true
        )
    }
}
