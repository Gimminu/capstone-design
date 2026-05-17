package com.capstone.design.youtubeparser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualTextSemanticFallbackPlannerTest {
    @Test
    fun selectCandidates_doesNotInventLeadingHeroMasksWithoutOcrGeometry() {
        val roi = VisualTextRoi(
            boundsInScreen = BoundsRect(0, 166, 656, 545),
            source = "youtube-composite-card",
            priority = 0,
            reason = "content-description-only",
            sourceText = "Play video \"Tlqkf 또 다시 보여줘야 돼!!!\""
        )

        val candidates = VisualTextSemanticFallbackPlanner.selectCandidates(
            visualRoiPlan = VisualTextRoiPlan(rois = listOf(roi), candidateCount = 1),
            screenWidth = 656,
            screenHeight = 1454
        )

        assertTrue(candidates.isEmpty())
    }

    @Test
    fun selectCandidates_doesNotInventYoutubePrefixHeroMasksWithoutOcrGeometry() {
        val roi = VisualTextRoi(
            boundsInScreen = BoundsRect(0, 184, 656, 562),
            source = "youtube-composite-card",
            priority = 0,
            reason = "content-description-only",
            sourceText = "Play video from semoplaylist \"Tlqkf 또 다시 보여줘야 돼!!!\""
        )

        val candidates = VisualTextSemanticFallbackPlanner.selectCandidates(
            visualRoiPlan = VisualTextRoiPlan(rois = listOf(roi), candidateCount = 1),
            screenWidth = 656,
            screenHeight = 1454
        )

        assertTrue(candidates.isEmpty())
    }

    @Test
    fun selectCandidates_doesNotProjectBaseTitleIntoCompositeHeroMasks() {
        val roi = VisualTextRoi(
            boundsInScreen = BoundsRect(0, 309, 1080, 993),
            source = "youtube-composite-card",
            priority = 0,
            reason = "content-description-only",
            sourceText = "semo playlist 917K views 7 months ago"
        )
        val baseResponse = responseOf(
            resultOf(
                original = "🔥\"Tlqkf 또 보여줘야 돼!\" : 식케이 (Sik-K), Lil Moshpit",
                bounds = BoundsRect(96, 596, 608, 664),
                authorId = "android-accessibility:title"
            )
        )

        val candidates = VisualTextSemanticFallbackPlanner.selectCandidates(
            visualRoiPlan = VisualTextRoiPlan(rois = listOf(roi), candidateCount = 1),
            screenWidth = 1080,
            screenHeight = 2400,
            baseResponse = baseResponse
        )

        assertTrue(candidates.isEmpty())
    }

    @Test
    fun selectCandidates_doesNotProjectBaseTitleIntoVisibleBandMasks() {
        val roi = VisualTextRoi(
            boundsInScreen = BoundsRect(0, 203, 675, 582),
            source = "youtube-visible-band",
            priority = 9,
            reason = "fallback-first-viewport-band"
        )
        val baseResponse = responseOf(
            resultOf(
                original = "tlqkf",
                bounds = BoundsRect(118, 62, 250, 94),
                authorId = "android-accessibility:youtube_user_input"
            ),
            resultOf(
                original = "🔥\"Tlqkf 또 보여줘야 돼!\" : 식케이 (Sik-K), Lil Moshpit",
                bounds = BoundsRect(96, 596, 608, 664),
                authorId = "android-accessibility:title"
            )
        )

        val candidates = VisualTextSemanticFallbackPlanner.selectCandidates(
            visualRoiPlan = VisualTextRoiPlan(rois = listOf(roi), candidateCount = 1),
            screenWidth = 675,
            screenHeight = 1478,
            baseResponse = baseResponse
        )

        assertTrue(candidates.isEmpty())
    }

    @Test
    fun selectCandidates_addsCenteredThumbnailTermMaskForFullWidthNonLeadingTitleHit() {
        val roi = VisualTextRoi(
            boundsInScreen = BoundsRect(0, 972, 1080, 1656),
            source = "youtube-composite-card",
            priority = 0,
            reason = "content-description-only",
            sourceText = "What is 'Tlqkf'?_Contemporary Korean Slang - 46 seconds - " +
                "Go to channel Contemporary Korean Slang - 40 thousand views - 5 years ago - play video"
        )

        val candidates = VisualTextSemanticFallbackPlanner.selectCandidates(
            visualRoiPlan = VisualTextRoiPlan(rois = listOf(roi), candidateCount = 1),
            screenWidth = 1080,
            screenHeight = 2400
        )

        val thumbnailMask = candidates.single()
        assertEquals("tlqkf", thumbnailMask.commentText)
        assertTrue(thumbnailMask.boundsInScreen.left in 280..380)
        assertTrue(thumbnailMask.boundsInScreen.top in 1220..1280)
        assertTrue(thumbnailMask.boundsInScreen.right - thumbnailMask.boundsInScreen.left >= 360)
        assertTrue(thumbnailMask.authorId.orEmpty().startsWith("ocr:youtube-semantic-card:"))
    }

    @Test
    fun selectCandidates_doesNotAddThumbnailTermMaskForLatePartiallyVisibleCard() {
        val roi = VisualTextRoi(
            boundsInScreen = BoundsRect(0, 1687, 1080, 2217),
            source = "youtube-composite-card",
            priority = 0,
            reason = "content-description-only",
            sourceText = "What is 'Tlqkf'?_Late card - 46 seconds - Go to channel Example - " +
                "40 thousand views - 5 years ago - play video"
        )

        val candidates = VisualTextSemanticFallbackPlanner.selectCandidates(
            visualRoiPlan = VisualTextRoiPlan(rois = listOf(roi), candidateCount = 1),
            screenWidth = 1080,
            screenHeight = 2400
        )

        assertTrue(candidates.isEmpty())
    }

    @Test
    fun selectCandidates_doesNotAddCenteredFallbackForPlaylistCards() {
        val roi = VisualTextRoi(
            boundsInScreen = BoundsRect(0, 605, 1080, 1289),
            source = "youtube-composite-card",
            priority = 0,
            reason = "content-description-only",
            sourceText = "Playlist - tlqkf 존나 개 빡칠때 듣는 노래들 - 전보때 - 6 videos"
        )

        val candidates = VisualTextSemanticFallbackPlanner.selectCandidates(
            visualRoiPlan = VisualTextRoiPlan(rois = listOf(roi), candidateCount = 1),
            screenWidth = 1080,
            screenHeight = 2400
        )

        assertTrue(candidates.isEmpty())
    }

    @Test
    fun selectCandidates_doesNotInventVisibleBandHeroMasksFromSearchInputOnly() {
        val roi = VisualTextRoi(
            boundsInScreen = BoundsRect(0, 321, 1080, 945),
            source = "youtube-visible-band",
            priority = 9,
            reason = "fallback-first-viewport-band"
        )
        val baseResponse = responseOf(
            resultOf(
                original = "tlqkf",
                bounds = BoundsRect(118, 62, 250, 94),
                authorId = "android-accessibility:youtube_user_input"
            )
        )

        val candidates = VisualTextSemanticFallbackPlanner.selectCandidates(
            visualRoiPlan = VisualTextRoiPlan(rois = listOf(roi), candidateCount = 1),
            screenWidth = 1080,
            screenHeight = 2400,
            baseResponse = baseResponse
        )

        assertTrue(candidates.isEmpty())
    }

    @Test
    fun selectCandidates_skipsSmallOrLateCompositeHits() {
        val smallCard = VisualTextRoi(
            boundsInScreen = BoundsRect(20, 790, 330, 1255),
            source = "youtube-composite-card",
            priority = 0,
            reason = "content-description-only",
            sourceText = "Tlqkf 공부법"
        )
        val lateHit = VisualTextRoi(
            boundsInScreen = BoundsRect(0, 166, 656, 545),
            source = "youtube-composite-card",
            priority = 0,
            reason = "content-description-only",
            sourceText = "semo playlist 917K views 7 months ago Tlqkf"
        )

        val candidates = VisualTextSemanticFallbackPlanner.selectCandidates(
            visualRoiPlan = VisualTextRoiPlan(rois = listOf(smallCard, lateHit), candidateCount = 2),
            screenWidth = 656,
            screenHeight = 1454
        )

        assertTrue(candidates.isEmpty())
    }

    private fun responseOf(vararg results: AndroidAnalysisResultItem): AndroidAnalysisResponse {
        return AndroidAnalysisResponse(
            timestamp = 1710000000000,
            filteredCount = 0,
            results = results.toList()
        )
    }

    private fun resultOf(
        original: String,
        bounds: BoundsRect,
        authorId: String
    ): AndroidAnalysisResultItem {
        val range = VisualTextOcrCandidateFilter.findAnalysisRanges(original).first()
        return AndroidAnalysisResultItem(
            original = original,
            boundsInScreen = bounds,
            authorId = authorId,
            isOffensive = true,
            isProfane = true,
            isToxic = false,
            isHate = false,
            scores = HarmScores(profanity = 1.0, toxicity = 0.0, hate = 0.0),
            evidenceSpans = listOf(
                EvidenceSpan(
                    text = range.analysisText,
                    start = range.start,
                    end = range.end,
                    score = 1.0
                )
            )
        )
    }
}
