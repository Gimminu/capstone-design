package com.capstone.design.youtubeparser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MaskOverlayPlannerTest {

    @Test
    fun buildSpecs_masksOnlyOffensiveItemsWithEvidenceSpans() {
        val response = responseOf(
            resultOf(
                offensive = true,
                bounds = BoundsRect(10, 20, 160, 70),
                spans = listOf(EvidenceSpan("시발", 0, 2, 0.98)),
                original = "시발 뭐하는 거야"
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

        assertEquals(1, specs.size)
        assertEquals("***", specs.single().label)
        assertTrue(specs.single().width < 150)
        assertTrue(specs.single().height <= 48)
    }

    @Test
    fun buildSpecs_clampsBoundsToScreen() {
        val response = responseOf(
            resultOf(
                offensive = true,
                bounds = BoundsRect(-20, -10, 220, 70),
                spans = listOf(EvidenceSpan("욕", 0, 1, 0.9)),
                original = "욕"
            )
        )

        val specs = AndroidMaskOverlayPlanner.buildSpecs(response, screenWidth = 320, screenHeight = 640)

        assertEquals(1, specs.size)
        assertEquals(0, specs.single().left)
        assertTrue(specs.single().top >= 0)
        assertTrue(specs.single().width <= 220)
        assertTrue(specs.single().height <= 48)
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
                spans = listOf(EvidenceSpan("tlqkf", 0, 5, 0.99)),
                original = "tlqkf 또 보여줘야 돼!"
            )
        )

        val specs = AndroidMaskOverlayPlanner.buildSpecs(response, screenWidth = 1080, screenHeight = 2400)

        assertEquals(1, specs.size)
        assertEquals("***", specs.single().label)
        assertTrue(specs.single().width < 449)
        assertTrue(specs.single().height <= 48)
    }


    @Test
    fun buildSpecs_deduplicatesSameGeometryAndBuildsStableSignature() {
        val response = responseOf(
            resultOf(
                offensive = true,
                bounds = BoundsRect(10, 20, 160, 70),
                spans = listOf(EvidenceSpan("시발", 0, 2, 0.98)),
                original = "시발 뭐하는 거야"
            ),
            resultOf(
                offensive = true,
                bounds = BoundsRect(10, 20, 160, 70),
                spans = listOf(EvidenceSpan("시발", 0, 2, 0.98)),
                original = "시발 뭐하는 거야"
            )
        )

        val specs = AndroidMaskOverlayPlanner.buildSpecs(response, screenWidth = 320, screenHeight = 640)
        val specsAgain = AndroidMaskOverlayPlanner.buildSpecs(response, screenWidth = 320, screenHeight = 640)
        val signature = AndroidMaskOverlayPlanner.signature(specs)

        assertEquals(1, specs.size)
        assertEquals(signature, AndroidMaskOverlayPlanner.signature(specsAgain))
    }

    @Test
    fun buildSpecs_suppressesNearDuplicateOverlappingMasks() {
        val response = responseOf(
            resultOf(
                offensive = true,
                bounds = BoundsRect(40, 100, 240, 150),
                spans = listOf(EvidenceSpan("tlqkf", 0, 5, 0.99)),
                original = "tlqkf"
            ),
            resultOf(
                offensive = true,
                bounds = BoundsRect(44, 104, 244, 154),
                spans = listOf(EvidenceSpan("tlqkf", 0, 5, 0.99)),
                original = "tlqkf"
            )
        )

        val specs = AndroidMaskOverlayPlanner.buildSpecs(response, screenWidth = 320, screenHeight = 640)

        assertEquals(1, specs.size)
    }

    @Test
    fun buildSpecs_usesReadableLabelForLargeTitleRows() {
        val response = responseOf(
            resultOf(
                offensive = true,
                bounds = BoundsRect(160, 1266, 975, 1394),
                spans = listOf(EvidenceSpan("tlqkf", 2, 7, 0.99)),
                original = "🔥\"Tlqkf 또 보여줘야 돼!\" : 식케이"
            )
        )

        val specs = AndroidMaskOverlayPlanner.buildSpecs(response, screenWidth = 1080, screenHeight = 2400)

        assertEquals(1, specs.size)
        assertEquals("***", specs.single().label)
        assertNotEquals(815, specs.single().width)
        assertTrue(specs.single().height <= 48)
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
        spans: List<EvidenceSpan>,
        original: String = "sample"
    ): AndroidAnalysisResultItem {
        return AndroidAnalysisResultItem(
            original = original,
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
