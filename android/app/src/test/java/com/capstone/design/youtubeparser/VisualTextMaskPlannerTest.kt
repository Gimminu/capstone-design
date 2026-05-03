package com.capstone.design.youtubeparser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualTextMaskPlannerTest {

    @Test
    fun buildPlan_masksOnlySpanInsideOcrTextBounds() {
        val analyzed = analyzedVisualText(
            text = "Tlqkf 공부법",
            bounds = BoundsRect(60, 240, 330, 290),
            spans = listOf(EvidenceSpan("Tlqkf", 0, 5, 0.98))
        )

        val plan = VisualTextMaskPlanner.buildPlan(
            analyzedCandidates = listOf(analyzed),
            screenWidth = 720,
            screenHeight = 1280
        )

        assertEquals(1, plan.candidateCount)
        assertEquals(1, plan.specs.size)
        val spec = plan.specs.single()
        assertEquals("***", spec.label)
        assertTrue(spec.left >= 60)
        assertTrue(spec.left + spec.width <= 330)
        assertEquals(240, spec.top)
        assertEquals(50, spec.height)
        assertTrue(spec.width < 270)
    }

    @Test
    fun buildPlan_rejectsLargeThumbnailSizedCandidate() {
        val analyzed = analyzedVisualText(
            text = "Tlqkf 공부법",
            bounds = BoundsRect(0, 220, 680, 780),
            spans = listOf(EvidenceSpan("Tlqkf", 0, 5, 0.98))
        )

        val plan = VisualTextMaskPlanner.buildPlan(
            analyzedCandidates = listOf(analyzed),
            screenWidth = 720,
            screenHeight = 1280
        )

        assertEquals(1, plan.candidateCount)
        assertTrue(plan.specs.isEmpty())
        assertEquals(1, plan.skippedUnstableCount)
    }

    @Test
    fun buildPlan_ignoresSafeOrNoSpanVisualText() {
        val safe = analyzedVisualText(
            text = "warp theme",
            bounds = BoundsRect(20, 120, 260, 160),
            offensive = false,
            spans = emptyList()
        )
        val noSpan = analyzedVisualText(
            text = "모호한 문장",
            bounds = BoundsRect(20, 180, 260, 220),
            offensive = true,
            spans = emptyList()
        )

        val plan = VisualTextMaskPlanner.buildPlan(
            analyzedCandidates = listOf(safe, noSpan),
            screenWidth = 720,
            screenHeight = 1280
        )

        assertEquals(0, plan.candidateCount)
        assertTrue(plan.specs.isEmpty())
    }

    @Test
    fun visualTextCandidateConvertsToBackendParsedCommentInput() {
        val candidate = VisualTextCandidate(
            text = "시발 자동자",
            boundsInScreen = BoundsRect(120, 300, 420, 340),
            confidence = 0.88f,
            source = "ocr-thumbnail"
        )

        val parsed = candidate.toParsedComment()

        assertEquals("시발 자동자", parsed.commentText)
        assertEquals(BoundsRect(120, 300, 420, 340), parsed.boundsInScreen)
        assertEquals("ocr-thumbnail", parsed.authorId)
    }

    private fun analyzedVisualText(
        text: String,
        bounds: BoundsRect,
        offensive: Boolean = true,
        spans: List<EvidenceSpan>
    ): VisualTextAnalysisResult {
        val candidate = VisualTextCandidate(
            text = text,
            boundsInScreen = bounds,
            confidence = 0.9f,
            source = "ocr-thumbnail"
        )
        return VisualTextAnalysisResult(
            candidate = candidate,
            result = AndroidAnalysisResultItem(
                original = text,
                boundsInScreen = bounds,
                isOffensive = offensive,
                isProfane = offensive,
                isToxic = offensive,
                isHate = false,
                scores = HarmScores(
                    profanity = if (offensive) 0.95 else 0.01,
                    toxicity = if (offensive) 0.8 else 0.01,
                    hate = 0.01
                ),
                evidenceSpans = spans
            )
        )
    }
}
