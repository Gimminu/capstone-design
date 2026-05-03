package com.capstone.design.youtubeparser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MaskOverlayPlannerTest {

    @Test
    fun buildSpecs_masksOnlyOffensiveItemsWithEvidenceSpans() {
        val response = responseOf(
            resultOf(
                offensive = true,
                bounds = BoundsRect(10, 20, 160, 70),
                spans = listOf(EvidenceSpan("시발", 0, 2, 0.98))
            ),
            resultOf(
                offensive = true,
                bounds = BoundsRect(10, 90, 160, 140),
                spans = emptyList()
            ),
            resultOf(
                offensive = false,
                bounds = BoundsRect(10, 160, 160, 210),
                spans = listOf(EvidenceSpan("safe", 0, 4, 0.99))
            )
        )

        val specs = AndroidMaskOverlayPlanner.buildSpecs(response, screenWidth = 320, screenHeight = 640)

        assertEquals(listOf(MaskOverlaySpec(left = 10, top = 20, width = 150, height = 50, label = "•••")), specs)
    }

    @Test
    fun buildSpecs_clampsBoundsToScreen() {
        val response = responseOf(
            resultOf(
                offensive = true,
                bounds = BoundsRect(-20, -10, 220, 70),
                spans = listOf(EvidenceSpan("욕", 0, 1, 0.9))
            )
        )

        val specs = AndroidMaskOverlayPlanner.buildSpecs(response, screenWidth = 320, screenHeight = 640)

        assertEquals(listOf(MaskOverlaySpec(left = 0, top = 0, width = 220, height = 70, label = "•••")), specs)
    }

    @Test
    fun buildSpecs_rejectsTinyOrInvalidBounds() {
        val response = responseOf(
            resultOf(
                offensive = true,
                bounds = BoundsRect(10, 20, 20, 30),
                spans = listOf(EvidenceSpan("욕", 0, 1, 0.9))
            ),
            resultOf(
                offensive = true,
                bounds = BoundsRect(40, 50, 30, 70),
                spans = listOf(EvidenceSpan("욕", 0, 1, 0.9))
            )
        )

        val specs = AndroidMaskOverlayPlanner.buildSpecs(response, screenWidth = 320, screenHeight = 640)

        assertTrue(specs.isEmpty())
    }

    @Test
    fun buildSpecs_rejectsOversizedCompositeCards() {
        val response = responseOf(
            resultOf(
                offensive = true,
                bounds = BoundsRect(0, 315, 1080, 1090),
                spans = listOf(EvidenceSpan("tlqkf", 9, 14, 0.99))
            ),
            resultOf(
                offensive = true,
                bounds = BoundsRect(147, 84, 596, 168),
                spans = listOf(EvidenceSpan("tlqkf", 0, 5, 0.99))
            )
        )

        val specs = AndroidMaskOverlayPlanner.buildSpecs(response, screenWidth = 1080, screenHeight = 2400)

        assertEquals(listOf(MaskOverlaySpec(left = 147, top = 84, width = 449, height = 84, label = "•••")), specs)
    }


    @Test
    fun buildSpecs_deduplicatesSameGeometryAndBuildsStableSignature() {
        val response = responseOf(
            resultOf(
                offensive = true,
                bounds = BoundsRect(10, 20, 160, 70),
                spans = listOf(EvidenceSpan("시발", 0, 2, 0.98))
            ),
            resultOf(
                offensive = true,
                bounds = BoundsRect(10, 20, 160, 70),
                spans = listOf(EvidenceSpan("시발", 0, 2, 0.98))
            )
        )

        val specs = AndroidMaskOverlayPlanner.buildSpecs(response, screenWidth = 320, screenHeight = 640)
        val signature = AndroidMaskOverlayPlanner.signature(specs)

        assertEquals(1, specs.size)
        assertEquals("10,20,150,50,•••", signature)
    }

    @Test
    fun buildSpecs_usesReadableLabelForLargeTitleRows() {
        val response = responseOf(
            resultOf(
                offensive = true,
                bounds = BoundsRect(160, 1266, 975, 1394),
                spans = listOf(EvidenceSpan("tlqkf", 2, 7, 0.99))
            )
        )

        val specs = AndroidMaskOverlayPlanner.buildSpecs(response, screenWidth = 1080, screenHeight = 2400)

        assertEquals(listOf(MaskOverlaySpec(left = 160, top = 1266, width = 815, height = 128, label = "민감 표현")), specs)
    }

    private fun responseOf(vararg results: AndroidAnalysisResultItem): AndroidAnalysisResponse {
        return AndroidAnalysisResponse(
            timestamp = 1710000000000,
            filteredCount = results.count { it.isOffensive && it.evidenceSpans.isNotEmpty() },
            results = results.toList()
        )
    }

    private fun resultOf(
        offensive: Boolean,
        bounds: BoundsRect,
        spans: List<EvidenceSpan>
    ): AndroidAnalysisResultItem {
        return AndroidAnalysisResultItem(
            original = "sample",
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
    }
}
