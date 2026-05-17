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

        val primaryTargets = targets.primaryTargets()
        assertEquals(1, primaryTargets.size)
        assertEquals("TLqkf 또 보여줘야 돼! : 식케이", primaryTargets.single().commentText)
        assertEquals(BoundsRect(10, 350, 700, 430), primaryTargets.single().boundsInScreen)
    }

    @Test
    fun extractTargets_marksTopYoutubeSearchInputAsRenderableUserInput() {
        val targets = YoutubeAnalysisTargetExtractor.extractTargets(
            listOf(
                node(
                    "tlqkf",
                    92,
                    42,
                    430,
                    100,
                    className = "android.widget.EditText",
                    viewIdResourceName = "com.google.android.youtube:id/search_edit_text"
                ),
                node("All", 22, 120, 88, 168, className = "android.widget.Button")
            )
        )

        assertEquals(1, targets.size)
        assertEquals("tlqkf", targets.single().commentText)
        assertEquals(BoundsRect(92, 42, 430, 100), targets.single().boundsInScreen)
        assertEquals("android-accessibility:youtube_user_input", targets.single().authorId)
    }

    @Test
    fun extractTargets_allowsLongContentTextEvenWhenYoutubeExposesItAsButton() {
        val targets = YoutubeAnalysisTargetExtractor.extractTargets(
            listOf(
                node("🔥\"TLqkf 또 보여줘야 돼!\" : 식케이", 10, 560, 980, 650, className = "android.widget.Button"),
                node("Subscribe", 800, 700, 1000, 760, className = "android.widget.Button")
            )
        )

        assertEquals(listOf("🔥\"TLqkf 또 보여줘야 돼!\" : 식케이"), targets.primaryTargets().map { it.commentText })
    }

    @Test
    fun extractTargets_keepsOnlyExactRangesFromCompositeSearchResultContentDescriptions() {
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

        val primaryTargets = targets.primaryTargets()
        assertEquals(1, primaryTargets.size)
        assertEquals("🔥\"TLqkf 또 보여줘야 돼!\" : 식케이", primaryTargets.single().commentText)
        assertTrue(targets.visualRangeTargets().any { it.commentText == "tlqkf" })
    }

    @Test
    fun extractTargets_extractsLikelyOffensiveTitleFromLargeVideoCardDescription() {
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

        assertTrue(targets.primaryTargets().isEmpty())
        assertEquals(listOf("tlqkf"), targets.visualRangeTargets().map { it.commentText })
    }

    @Test
    fun extractTargets_extractsLikelyOffensiveKoreanTitleFromLargeCardDescription() {
        val targets = YoutubeAnalysisTargetExtractor.extractTargets(
            listOf(
                contentDescriptionNode(
                    "개새끼 - 나무위키:대문, 나무위키, 조회수 12만회, 6일 전 - 동영상 재생",
                    0,
                    400,
                    1080,
                    720
                )
            )
        )

        assertTrue(targets.primaryTargets().isEmpty())
        assertEquals(listOf("개새끼"), targets.visualRangeTargets().map { it.commentText })
    }

    @Test
    fun extractTargets_extractsEnglishRomanizedTitleFromViewMetadataDescription() {
        val targets = YoutubeAnalysisTargetExtractor.extractTargets(
            listOf(
                contentDescriptionNode(
                    "ssibal 뜻, semo playlist, 866 thousand views, 6 months ago - play video",
                    0,
                    420,
                    1080,
                    720
                )
            )
        )

        assertTrue(targets.primaryTargets().isEmpty())
        assertEquals(listOf("ssibal"), targets.visualRangeTargets().map { it.commentText })
    }

    @Test
    fun extractTargets_extractsTitleFromCompactKViewMetadataDescription() {
        val targets = YoutubeAnalysisTargetExtractor.extractTargets(
            listOf(
                contentDescriptionNode(
                    "What is 'Tlqkf'?, Contemporary Korean Slang, 40K views, 5 years ago - play video",
                    0,
                    260,
                    807,
                    620
                )
            )
        )

        assertTrue(targets.primaryTargets().isEmpty())
        assertEquals(listOf("tlqkf"), targets.visualRangeTargets().map { it.commentText })
        assertTrue(targets.visualRangeTargets().single().boundsInScreen.bottom - targets.visualRangeTargets().single().boundsInScreen.top <= 56)
    }

    @Test
    fun extractTargets_estimatesTitleBoundsForLargeAnalyzableDescriptionWithoutMetadataSuffix() {
        val targets = YoutubeAnalysisTargetExtractor.extractTargets(
            listOf(
                contentDescriptionNode(
                    "What is 'Tlqkf'?_Contemporary Korean Slang",
                    0,
                    246,
                    807,
                    708
                )
            )
        )

        assertTrue(targets.primaryTargets().isEmpty())
        assertEquals(listOf("tlqkf"), targets.visualRangeTargets().map { it.commentText })
        assertTrue(targets.visualRangeTargets().single().boundsInScreen.bottom - targets.visualRangeTargets().single().boundsInScreen.top <= 56)
    }

    @Test
    fun extractTargets_extractsLikelyOffensiveShortsGridTitleFromContentDescription() {
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

        val primaryTarget = targets.primaryTargets().single()
        assertEquals("tlqkf비용 효과 있을까?", primaryTarget.commentText)
        assertEquals("android-accessibility:youtube_shorts_title", primaryTarget.authorId)
        assertTrue(primaryTarget.boundsInScreen.left <= 48)
        assertTrue(primaryTarget.boundsInScreen.top in 1645..1665)
        assertTrue(targets.visualRangeTargets().isEmpty())
    }

    @Test
    fun extractTargets_estimatesCompactShortsGridTitleInsideThumbnailBand() {
        val targets = YoutubeAnalysisTargetExtractor.extractTargets(
            listOf(
                contentDescriptionNode(
                    "Tlqkf 공부법, 2.1 million views, 미미미누, 10 months ago - play Short",
                    32,
                    189,
                    529,
                    556
                )
            )
        )

        val primaryTarget = targets.primaryTargets().single()
        assertEquals("Tlqkf 공부법", primaryTarget.commentText)
        assertEquals("android-accessibility:youtube_shorts_title", primaryTarget.authorId)
        assertTrue(primaryTarget.boundsInScreen.top in 350..370)
    }

    @Test
    fun extractTargets_estimatesMediumShortsGridTitleNearTopOverlayBand() {
        val targets = YoutubeAnalysisTargetExtractor.extractTargets(
            listOf(
                contentDescriptionNode(
                    "tlqkf비용 효과 있을까?, 89 thousand views, 1분만, 1 year ago - play Short",
                    551,
                    1571,
                    1048,
                    2211
                )
            )
        )

        val primaryTarget = targets.primaryTargets().single()
        assertEquals("tlqkf비용 효과 있을까?", primaryTarget.commentText)
        assertEquals("android-accessibility:youtube_shorts_title", primaryTarget.authorId)
        assertTrue(primaryTarget.boundsInScreen.top in 1705..1720)
    }

    @Test
    fun extractTargets_estimatesShortsGridTitleWhenCandidateIsNotAtTitlePrefix() {
        val targets = YoutubeAnalysisTargetExtractor.extractTargets(
            listOf(
                contentDescriptionNode(
                    "No, I promised you Tlqkf.., 2.4 million views, 빅토리, 1 year ago - play Short",
                    17,
                    1139,
                    526,
                    1928
                )
            )
        )

        val primaryTarget = targets.primaryTargets().single()
        assertEquals("No, I promised you Tlqkf..", primaryTarget.commentText)
        assertEquals("android-accessibility:youtube_shorts_title", primaryTarget.authorId)
        assertTrue(primaryTarget.boundsInScreen.top in 1655..1675)
        assertTrue(targets.visualRangeTargets().isEmpty())
    }

    @Test
    fun extractTargets_usesPlaylistDescriptionAsStableVisibleTitle() {
        val targets = YoutubeAnalysisTargetExtractor.extractTargets(
            listOf(
                contentDescriptionNode(
                    "Playlist - tlqkf 존나 개 빡칠때 듣는 노래들 - 전보때 - 6 videos",
                    0,
                    1187,
                    1080,
                    1920
                )
            )
        )

        val primaryTarget = targets.primaryTargets().single()
        assertEquals("tlqkf 존나 개 빡칠때 듣는 노래들", primaryTarget.commentText)
        assertEquals("android-accessibility:youtube_title", primaryTarget.authorId)
        assertTrue(primaryTarget.boundsInScreen.left in 145..155)
        assertTrue(primaryTarget.boundsInScreen.top in 1815..1825)
        assertTrue(targets.visualRangeTargets().isEmpty())
    }

    @Test
    fun extractTargets_keepsRomanizedQwertyTitleAsPrimaryAccessibilityTarget() {
        val targets = YoutubeAnalysisTargetExtractor.extractTargets(
            listOf(
                node("What is 'Tlqkf'? Contemporary Korean Slang", 20, 420, 780, 500)
            )
        )

        assertEquals(listOf("What is 'Tlqkf'? Contemporary Korean Slang"), targets.primaryTargets().map { it.commentText })
        assertTrue(targets.visualRangeTargets().isEmpty())
    }

    @Test
    fun extractTargets_marksVisibleYoutubeTitleAsStableAccessibilityTitle() {
        val targets = YoutubeAnalysisTargetExtractor.extractTargets(
            listOf(
                node(
                    "",
                    159,
                    940,
                    943,
                    1045,
                    className = "android.view.ViewGroup",
                    contentDescription = "What is 'Tlqkf'?_Contemporary Korean Slang"
                )
            )
        )

        val target = targets.primaryTargets().single()
        assertEquals("What is 'Tlqkf'?_Contemporary Korean Slang", target.commentText)
        assertEquals("android-accessibility:youtube_title", target.authorId)
    }

    @Test
    fun extractTargets_includesMountedBelowViewportTitleAsLookaheadOnly() {
        val targets = YoutubeAnalysisTargetExtractor.extractTargets(
            nodes = listOf(
                node(
                    "What is 'Tlqkf'?_Contemporary Korean Slang",
                    80,
                    1320,
                    680,
                    1390,
                    isVisibleToUser = false
                )
            ),
            screenHeight = 1280
        )

        val target = targets.primaryTargets().single()
        assertEquals("What is 'Tlqkf'?_Contemporary Korean Slang", target.commentText)
        assertEquals("android-accessibility-lookahead:android-accessibility:youtube_title", target.authorId)
    }

    @Test
    fun extractTargets_rejectsHiddenNodeOverlappingViewportAsLookahead() {
        val targets = YoutubeAnalysisTargetExtractor.extractTargets(
            nodes = listOf(
                node(
                    "What is 'Tlqkf'?_Contemporary Korean Slang",
                    80,
                    420,
                    680,
                    490,
                    isVisibleToUser = false
                )
            ),
            screenHeight = 1280
        )

        assertTrue(targets.isEmpty())
    }

    @Test
    fun extractTargets_keepsKoreanOffensiveTextAsSinglePrimaryAccessibilityTarget() {
        val targets = YoutubeAnalysisTargetExtractor.extractTargets(
            listOf(
                node("개새끼 뭐하는 거야 / 병신아 꺼져", 20, 420, 980, 500)
            )
        )

        assertEquals(listOf("개새끼 뭐하는 거야 / 병신아 꺼져"), targets.primaryTargets().map { it.commentText })
        assertTrue(targets.visualRangeTargets().isEmpty())
    }

    @Test
    fun extractTargets_keepsShortStandaloneOffensiveHeadingsForBackend() {
        val targets = YoutubeAnalysisTargetExtractor.extractTargets(
            listOf(
                node("개새끼", 60, 350, 1020, 430),
                node("씨발", 60, 460, 1020, 540),
                node("정상 제목", 60, 570, 1020, 650)
            )
        )

        assertEquals(listOf("개새끼", "씨발", "정상 제목"), targets.primaryTargets().map { it.commentText })
        assertTrue(targets.visualRangeTargets().isEmpty())
    }

    @Test
    fun extractTargets_keepsAdditionalKeyboardVariantsAsPrimaryAccessibilityTarget() {
        val targets = YoutubeAnalysisTargetExtractor.extractTargets(
            listOf(
                node("wlfkf / whssk / alcls / rjwu 비교 카드", 20, 420, 980, 500)
            )
        )

        assertEquals(listOf("wlfkf / whssk / alcls / rjwu 비교 카드"), targets.primaryTargets().map { it.commentText })
        assertTrue(targets.visualRangeTargets().isEmpty())
    }

    @Test
    fun extractTargets_keepsExactRomanizedCandidateWhenSafeNodesExceedLimit() {
        val safeNodes = (0 until 48).map { index ->
            node("정상 제목 $index", 20, 120 + index * 24, 320, 142 + index * 24)
        }
        val targets = YoutubeAnalysisTargetExtractor.extractTargets(
            safeNodes + node("What is 'Tlqkf'? Contemporary Korean Slang", 20, 1520, 780, 1600)
        )

        assertTrue(targets.any { it.commentText == "What is 'Tlqkf'? Contemporary Korean Slang" })
    }

    @Test
    fun extractTargets_limitsLowPrioritySafeTargetsForFasterForegroundAnalysis() {
        val safeNodes = (0 until 24).map { index ->
            node("정상 제목 $index", 20, 120 + index * 36, 520, 150 + index * 36)
        }
        val offensiveNode = node("개새끼 뭐하는 거야 / 병신아 꺼져", 20, 1040, 620, 1100)

        val targets = YoutubeAnalysisTargetExtractor.extractTargets(safeNodes + offensiveNode)

        assertTrue(targets.size <= 12)
        assertTrue(targets.any { it.commentText == "개새끼 뭐하는 거야 / 병신아 꺼져" })
        assertTrue(targets.visualRangeTargets().isEmpty())
    }

    @Test
    fun extractTargets_rejectsUiCountersAndActions() {
        val targets = YoutubeAnalysisTargetExtractor.extractTargets(
            listOf(
                node("8.4K", 10, 700, 80, 740),
                node("Clear", 596, 63, 722, 189),
                node("Voice search", 722, 63, 848, 189),
                node("Cast. Disconnected", 848, 63, 975, 189),
                node("More options", 975, 63, 1080, 189),
                node("More actions", 455, 337, 518, 400),
                node("Action menu", 455, 337, 518, 400),
                node("Subscriptions: New content is available", 648, 2211, 864, 2337),
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
        assertTrue(targets.visualRangeTargets().isEmpty())
    }

    private fun node(
        text: String,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        className: String = "android.widget.TextView",
        contentDescription: String? = null,
        viewIdResourceName: String? = null,
        isVisibleToUser: Boolean = true
    ): ParsedTextNode {
        return ParsedTextNode(
            packageName = "com.google.android.youtube",
            text = text,
            contentDescription = contentDescription,
            displayText = text.ifBlank { contentDescription },
            className = className,
            viewIdResourceName = viewIdResourceName,
            left = left,
            top = top,
            right = right,
            bottom = bottom,
            approxTop = top,
            isVisibleToUser = isVisibleToUser
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

    private fun List<ParsedComment>.primaryTargets(): List<ParsedComment> {
        return filterNot { it.authorId.orEmpty().startsWith("youtube-visual-range:") }
    }

    private fun List<ParsedComment>.visualRangeTargets(): List<ParsedComment> {
        return filter { it.authorId.orEmpty().startsWith("youtube-visual-range:") }
    }
}
