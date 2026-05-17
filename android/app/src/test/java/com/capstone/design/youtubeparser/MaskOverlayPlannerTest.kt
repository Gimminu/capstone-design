package com.capstone.design.youtubeparser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

        assertEquals("specs=$specs", 1, specs.size)
        assertEquals("***", specs.single().label)
        assertTrue(specs.single().width < 150)
        assertTrue(specs.single().height <= 36)
    }

    @Test
    fun buildSpecs_repairsTruncatedBackendSpanWhenEvidenceTextMatchesOriginal() {
        val truncatedResponse = responseOf(
            resultOf(
                offensive = true,
                bounds = BoundsRect(10, 20, 210, 70),
                spans = listOf(EvidenceSpan("시발", 0, 1, 0.98)),
                original = "시발"
            )
        )
        val correctResponse = responseOf(
            resultOf(
                offensive = true,
                bounds = BoundsRect(10, 20, 210, 70),
                spans = listOf(EvidenceSpan("시발", 0, 2, 0.98)),
                original = "시발"
            )
        )

        val truncatedSpecs = AndroidMaskOverlayPlanner.buildSpecs(
            truncatedResponse,
            screenWidth = 320,
            screenHeight = 640
        )
        val correctSpecs = AndroidMaskOverlayPlanner.buildSpecs(
            correctResponse,
            screenWidth = 320,
            screenHeight = 640
        )

        assertEquals(1, truncatedSpecs.size)
        assertEquals(correctSpecs.single().left, truncatedSpecs.single().left)
        assertEquals(correctSpecs.single().width, truncatedSpecs.single().width)
    }

    @Test
    fun buildPlan_recordsRenderedMaskSourceSamples() {
        val response = responseOf(
            resultOf(
                offensive = true,
                bounds = BoundsRect(40, 520, 320, 560),
                spans = listOf(EvidenceSpan("시발", 0, 2, 0.99)),
                original = "시발 뭐하는 거야",
                authorId = "android-accessibility:title"
            )
        )

        val plan = AndroidMaskOverlayPlanner.buildPlan(response, screenWidth = 1080, screenHeight = 2400)

        assertEquals(1, plan.specs.size)
        assertTrue(plan.renderedSamples.single().contains("android-accessibility:title"))
        assertTrue(plan.renderedSamples.single().contains("rect=40,520,320,560"))
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

        assertEquals("specs=$specs", 1, specs.size)
        assertEquals(0, specs.single().left)
        assertTrue(specs.single().top >= 0)
        assertTrue(specs.single().width <= 220)
        assertTrue(specs.single().height <= 36)
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
        assertTrue(specs.single().height <= 36)
    }

    @Test
    fun buildSpecs_doesNotFallbackToWholeBoundsWhenSpanMappingFails() {
        val response = responseOf(
            resultOf(
                offensive = true,
                bounds = BoundsRect(40, 120, 540, 180),
                spans = listOf(EvidenceSpan("욕", 99, 100, 0.99)),
                original = "짧은 문장"
            )
        )

        val specs = AndroidMaskOverlayPlanner.buildSpecs(response, screenWidth = 1080, screenHeight = 2400)

        assertTrue(specs.isEmpty())
    }

    @Test
    fun buildSpecs_rejectsLargeUnstableTextContainers() {
        val response = responseOf(
            resultOf(
                offensive = true,
                bounds = BoundsRect(0, 260, 1040, 520),
                spans = listOf(EvidenceSpan("시발", 0, 2, 0.99)),
                original = "시발 " + "긴 설명 ".repeat(40)
            )
        )

        val specs = AndroidMaskOverlayPlanner.buildSpecs(response, screenWidth = 1080, screenHeight = 2400)

        assertTrue(specs.isEmpty())
    }

    @Test
    fun buildSpecs_rejectsLongAccessibilitySnippetWithoutPreciseGeometry() {
        val original = "설명 ".repeat(24) + "시발 뭐하는 거야 " + "추가 설명 ".repeat(24)
        val spanStart = original.indexOf("시발")
        val response = responseOf(
            resultOf(
                offensive = true,
                bounds = BoundsRect(40, 300, 980, 520),
                spans = listOf(EvidenceSpan("시발", spanStart, spanStart + 2, 0.99)),
                original = original,
                authorId = "android-accessibility:snippet"
            )
        )

        val specs = AndroidMaskOverlayPlanner.buildSpecs(response, screenWidth = 1080, screenHeight = 2400)

        assertTrue(specs.isEmpty())
    }

    @Test
    fun buildSpecs_rejectsWideBrowserAccessibilityEstimatedMasks() {
        val response = responseOf(
            resultOf(
                offensive = true,
                bounds = BoundsRect(20, 900, 680, 970),
                spans = listOf(EvidenceSpan("tlqkf", 0, 5, 0.99)),
                original = "Tlqkf 발음",
                authorId = "android-accessibility-browser:title"
            )
        )

        val specs = AndroidMaskOverlayPlanner.buildSpecs(response, screenWidth = 720, screenHeight = 1280)

        assertTrue(specs.isEmpty())
    }

    @Test
    fun buildSpecs_rejectsMediumWidthBrowserRowsWithoutExactGeometry() {
        val response = responseOf(
            resultOf(
                offensive = true,
                bounds = BoundsRect(24, 1080, 330, 1132),
                spans = listOf(EvidenceSpan("tlqkf", 0, 5, 0.99)),
                original = "Tlqkf 티셔츠",
                authorId = "android-accessibility-browser:title"
            )
        )

        val specs = AndroidMaskOverlayPlanner.buildSpecs(response, screenWidth = 720, screenHeight = 1280)

        assertTrue(specs.isEmpty())
    }

    @Test
    fun buildSpecs_rejectsLooseBrowserSnippetBoundsWithoutExactGeometry() {
        val response = responseOf(
            resultOf(
                offensive = true,
                bounds = BoundsRect(24, 540, 190, 588),
                spans = listOf(EvidenceSpan("시발", 0, 2, 0.99)),
                original = "시발",
                authorId = "android-accessibility-browser:snippet"
            )
        )

        val specs = AndroidMaskOverlayPlanner.buildSpecs(response, screenWidth = 720, screenHeight = 1280)

        assertTrue(specs.isEmpty())
    }

    @Test
    fun buildSpecs_rejectsCompactBrowserAccessibilityTextMasksWithoutExactGeometry() {
        val response = responseOf(
            resultOf(
                offensive = true,
                bounds = BoundsRect(80, 520, 188, 566),
                spans = listOf(EvidenceSpan("개새끼", 0, 3, 0.99)),
                original = "개새끼",
                authorId = "android-accessibility-browser:title"
            )
        )

        val specs = AndroidMaskOverlayPlanner.buildSpecs(response, screenWidth = 720, screenHeight = 1280)

        assertTrue(specs.isEmpty())
    }

    @Test
    fun buildSpecs_rejectsGenericScreenAccessibilityTextWithoutExactGeometry() {
        val response = responseOf(
            resultOf(
                offensive = true,
                bounds = BoundsRect(24, 740, 360, 806),
                spans = listOf(EvidenceSpan("시발", 0, 2, 0.99)),
                original = "시발은 한국어에서 널리 쓰이는 비속어",
                authorId = "screen:accessibility_text:content"
            )
        )

        val specs = AndroidMaskOverlayPlanner.buildSpecs(response, screenWidth = 720, screenHeight = 1280)

        assertTrue(specs.isEmpty())
    }

    @Test
    fun buildSpecs_keepsCompactAccessibilityRangeGeometry() {
        val response = responseOf(
            resultOf(
                offensive = true,
                bounds = BoundsRect(86, 624, 158, 656),
                spans = listOf(EvidenceSpan("tlqkf", 0, 5, 0.99)),
                original = "tlqkf",
                authorId = "android-accessibility-range:Tlqkf"
            )
        )

        val specs = AndroidMaskOverlayPlanner.buildSpecs(response, screenWidth = 720, screenHeight = 1280)

        assertEquals(1, specs.size)
        assertFalse(specs.single().allowScrollTranslation)
        assertTrue("spec=${specs.single()}", specs.single().width <= 84)
    }

    @Test
    fun buildSpecs_rejectsWideAccessibilityRangeGeometry() {
        val response = responseOf(
            resultOf(
                offensive = true,
                bounds = BoundsRect(24, 900, 280, 936),
                spans = listOf(EvidenceSpan("tlqkf", 0, 5, 0.99)),
                original = "tlqkf",
                authorId = "android-accessibility-range:Tlqkf"
            )
        )

        val specs = AndroidMaskOverlayPlanner.buildSpecs(response, screenWidth = 720, screenHeight = 1280)

        assertTrue(specs.isEmpty())
    }

    @Test
    fun buildSpecs_rejectsLongAiOverviewAccessibilityTextWithoutGeometry() {
        val original =
            "'씨발'은 한국어에서 가장 대표적이고 널리 쓰이는 비속어(욕설)로, 영어의 'Fuck'과 " +
                "유사하게 강한 불만, 분노, 당혹감 등을 표현할 때 사용됩니다. 성교를 비하하는 말에서 " +
                "유래된 매우 저속한 표현으로, 상황에 따라 심각한 욕설로 인식됩니다."
        val spanStart = original.indexOf("Fuck")
        val response = responseOf(
            resultOf(
                offensive = true,
                bounds = BoundsRect(12, 514, 698, 748),
                spans = listOf(EvidenceSpan("Fuck", spanStart, spanStart + 4, 0.99)),
                original = original,
                authorId = "android-accessibility:content"
            )
        )

        val specs = AndroidMaskOverlayPlanner.buildSpecs(response, screenWidth = 720, screenHeight = 1280)

        assertTrue(specs.isEmpty())
    }

    @Test
    fun buildPlan_reportsCandidatesRenderedAndSkippedUnstableCounts() {
        val response = responseOf(
            resultOf(
                offensive = true,
                bounds = BoundsRect(40, 100, 240, 150),
                spans = listOf(EvidenceSpan("시발", 0, 2, 0.99)),
                original = "시발 뭐하는 거야"
            ),
            resultOf(
                offensive = true,
                bounds = BoundsRect(0, 260, 1040, 520),
                spans = listOf(EvidenceSpan("시발", 0, 2, 0.99)),
                original = "시발 " + "긴 설명 ".repeat(40)
            )
        )

        val plan = AndroidMaskOverlayPlanner.buildPlan(response, screenWidth = 1080, screenHeight = 2400)

        assertEquals(2, plan.candidateCount)
        assertEquals(1, plan.specs.size)
        assertEquals(1, plan.skippedUnstableCount)
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
    fun mergeWithPreservedPreciseVisualSpecs_keepsTranslatedOcrMasksDuringAccessibilityRefresh() {
        val accessibilitySpec = MaskOverlaySpec(
            left = 155,
            top = 110,
            width = 188,
            height = 32,
            label = "***",
            debugSource = "android-accessibility:youtube_user_input span=tlqkf"
        )
        val translatedVisualSpec = MaskOverlaySpec(
            left = 46,
            top = 612,
            width = 98,
            height = 38,
            label = "***",
            debugSource = "ocr:youtube-composite-card:20,177,541,710:Tlgkf span=tlqkf"
        )

        val merged = AndroidMaskOverlayPlanner.mergeWithPreservedPreciseVisualSpecs(
            newSpecs = listOf(accessibilitySpec),
            existingSpecs = listOf(translatedVisualSpec),
            screenWidth = 1080,
            screenHeight = 2400
        )

        assertEquals(2, merged.size)
        assertTrue(merged.any { it.debugSource.startsWith("android-accessibility:youtube_user_input") })
        assertTrue(merged.any { it.debugSource.startsWith("ocr:youtube-composite-card:") })
    }

    @Test
    fun mergeWithPreservedPreciseVisualSpecs_dropsOverlappingOrOffscreenOcrMasks() {
        val nextSpec = MaskOverlaySpec(
            left = 46,
            top = 612,
            width = 98,
            height = 38,
            label = "***",
            debugSource = "android-accessibility:youtube_shorts_title span=Tlqkf"
        )
        val overlappingVisualSpec = MaskOverlaySpec(
            left = 48,
            top = 614,
            width = 96,
            height = 36,
            label = "***",
            debugSource = "ocr:youtube-composite-card:20,177,541,710:Tlgkf span=tlqkf"
        )
        val offscreenVisualSpec = MaskOverlaySpec(
            left = 80,
            top = -120,
            width = 96,
            height = 36,
            label = "***",
            debugSource = "ocr:youtube-visible-band:0,0,1080,600:tlqkf span=tlqkf"
        )

        val merged = AndroidMaskOverlayPlanner.mergeWithPreservedPreciseVisualSpecs(
            newSpecs = listOf(nextSpec),
            existingSpecs = listOf(overlappingVisualSpec, offscreenVisualSpec),
            screenWidth = 1080,
            screenHeight = 2400
        )

        assertEquals(listOf(nextSpec), merged)
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
    fun buildSpecs_prefersSmallerExactOverlayWhenWideAndExactBoundsOverlap() {
        val response = responseOf(
            resultOf(
                offensive = true,
                bounds = BoundsRect(40, 420, 620, 480),
                spans = listOf(EvidenceSpan("개새끼", 0, 3, 0.99)),
                original = "개새끼 뭐하는 거야"
            ),
            resultOf(
                offensive = true,
                bounds = BoundsRect(42, 422, 120, 470),
                spans = listOf(EvidenceSpan("개새끼", 0, 3, 0.99)),
                original = "개새끼"
            )
        )

        val specs = AndroidMaskOverlayPlanner.buildSpecs(response, screenWidth = 1080, screenHeight = 2400)

        assertEquals(1, specs.size)
        assertTrue(specs.single().left <= 48)
        assertTrue("spec=${specs.single()}", specs.single().width <= 96)
    }

    @Test
    fun buildSpecs_suppressesWideTitleEstimateWhenExactVisualRangeOverlaps() {
        val response = responseOf(
            resultOf(
                offensive = true,
                bounds = BoundsRect(159, 828, 943, 933),
                spans = listOf(EvidenceSpan("Tlqkf", 2, 7, 0.99)),
                original = "🔥\"Tlqkf 또 보여줘야 돼!\" : 식케이 (Sik-K), Lil Moshpit - LOV3"
            ),
            resultOf(
                offensive = true,
                bounds = BoundsRect(222, 828, 349, 863),
                spans = listOf(EvidenceSpan("tlqkf", 0, 5, 0.99)),
                original = "tlqkf"
            )
        )

        val specs = AndroidMaskOverlayPlanner.buildSpecs(response, screenWidth = 1080, screenHeight = 2400)

        assertEquals("specs=$specs", 1, specs.size)
        assertTrue("specs=$specs", specs.single().left in 210..240)
        assertTrue("specs=$specs", specs.single().width <= 130)
    }

    @Test
    fun buildSpecs_suppressesSameLineDuplicateWhenOverlapIsSmallButVisible() {
        val response = responseOf(
            resultOf(
                offensive = true,
                bounds = BoundsRect(147, 814, 237, 862),
                spans = listOf(EvidenceSpan("tlqkf", 0, 5, 0.99)),
                original = "tlqkf"
            ),
            resultOf(
                offensive = true,
                bounds = BoundsRect(222, 828, 348, 863),
                spans = listOf(EvidenceSpan("Tlqkf", 0, 5, 0.99)),
                original = "Tlqkf"
            )
        )

        val specs = AndroidMaskOverlayPlanner.buildSpecs(response, screenWidth = 1080, screenHeight = 2400)

        assertEquals(1, specs.size)
    }

    @Test
    fun buildSpecs_keepsPreciseOcrWordBoundsInsteadOfShrinkingToFixedPill() {
        val response = responseOf(
            resultOf(
                offensive = true,
                bounds = BoundsRect(66, 260, 210, 312),
                spans = listOf(EvidenceSpan("tlqkf", 0, 5, 0.99)),
                original = "tlqkf",
                authorId = "ocr:youtube-composite-card:0,220,720,520:Tlqkf"
            )
        )

        val specs = AndroidMaskOverlayPlanner.buildSpecs(response, screenWidth = 720, screenHeight = 1280)

        assertEquals(1, specs.size)
        assertEquals(66, specs.single().left)
        assertTrue("spec=${specs.single()}", specs.single().width >= 132)
        assertTrue("spec=${specs.single()}", specs.single().height >= 48)
        assertTrue("spec=${specs.single()}", specs.single().height <= 56)
    }

    @Test
    fun buildSpecs_expandsTopHeroDisplayOcrMaskForLargeThumbnailText() {
        val response = responseOf(
            resultOf(
                offensive = true,
                bounds = BoundsRect(54, 388, 287, 444),
                spans = listOf(EvidenceSpan("tlqkf", 0, 5, 0.99)),
                original = "tlqkf",
                authorId = "ocr:youtube-composite-card:0,309,1080,971:Tlqkf"
            )
        )

        val specs = AndroidMaskOverlayPlanner.buildSpecs(response, screenWidth = 1080, screenHeight = 2400)

        assertEquals(1, specs.size)
        assertTrue("spec=${specs.single()}", specs.single().left <= 30)
        assertTrue("spec=${specs.single()}", specs.single().width >= 320)
        assertTrue("spec=${specs.single()}", specs.single().height in 110..132)
        assertTrue("spec=${specs.single()}", specs.single().top <= 360)
    }

    @Test
    fun buildSpecs_expandsTopHeroDisplayOcrMaskWhenMlKitBoxIsShorter() {
        val response = responseOf(
            resultOf(
                offensive = true,
                bounds = BoundsRect(54, 349, 252, 396),
                spans = listOf(EvidenceSpan("tlqkf", 0, 5, 0.99)),
                original = "tlqkf",
                authorId = "ocr:youtube-composite-card:0,309,1080,644:Tlqkf"
            )
        )

        val specs = AndroidMaskOverlayPlanner.buildSpecs(response, screenWidth = 1080, screenHeight = 2400)

        assertEquals(1, specs.size)
        assertTrue("spec=${specs.single()}", specs.single().left <= 30)
        assertTrue("spec=${specs.single()}", specs.single().width >= 280)
        assertTrue("spec=${specs.single()}", specs.single().height in 96..112)
        assertTrue("spec=${specs.single()}", specs.single().top in 318..330)
    }

    @Test
    fun buildSpecs_doesNotExpandTwoColumnShortsCardOcrAsTopHeroText() {
        val response = responseOf(
            resultOf(
                offensive = true,
                bounds = BoundsRect(104, 705, 281, 777),
                spans = listOf(EvidenceSpan("tlqkf", 0, 5, 0.99)),
                original = "tlqkf",
                authorId = "ocr:youtube-composite-card:26,559,535,1456:Tlgkf"
            )
        )

        val specs = AndroidMaskOverlayPlanner.buildSpecs(response, screenWidth = 1080, screenHeight = 2400)

        assertEquals(1, specs.size)
        assertTrue("spec=${specs.single()}", specs.single().left in 90..120)
        assertTrue("spec=${specs.single()}", specs.single().width < 180)
        assertTrue("spec=${specs.single()}", specs.single().height <= 56)
    }

    @Test
    fun buildSpecs_usesVisualOcrTextWidthForSpacedGlyphMasks() {
        val response = responseOf(
            resultOf(
                offensive = true,
                bounds = BoundsRect(66, 260, 210, 286),
                spans = listOf(EvidenceSpan("tlqkf", 0, 5, 0.99)),
                original = "tlqkf",
                authorId = "ocr:youtube-composite-card:0,220,720,520:T l q k f"
            )
        )

        val specs = AndroidMaskOverlayPlanner.buildSpecs(response, screenWidth = 720, screenHeight = 1280)

        assertEquals(1, specs.size)
        assertEquals(66, specs.single().left)
        assertEquals(144, specs.single().width)
    }

    @Test
    fun buildSpecs_usesReadableLabelForLargeTitleRows() {
        val response = responseOf(
            resultOf(
                offensive = true,
                bounds = BoundsRect(160, 1266, 975, 1394),
                spans = listOf(EvidenceSpan("tlqkf", 2, 7, 0.99)),
                original = "🔥\"Tlqkf 또 보여줘야 돼!\" : 식케이",
                authorId = "youtube-composite-description"
            )
        )

        val specs = AndroidMaskOverlayPlanner.buildSpecs(response, screenWidth = 1080, screenHeight = 2400)

        assertEquals(1, specs.size)
        assertEquals("***", specs.single().label)
        assertNotEquals(815, specs.single().width)
        assertTrue(specs.single().height <= 36)
    }

    @Test
    fun buildSpecs_rejectsLongUnsourcedRowsThatAreLikelyCoarseContainers() {
        val response = responseOf(
            resultOf(
                offensive = true,
                bounds = BoundsRect(159, 828, 943, 933),
                spans = listOf(EvidenceSpan("Tlqkf", 2, 7, 0.99)),
                original = "🔥\"Tlqkf 또 보여줘야 돼!\" : 식케이 (Sik-K), Lil Moshpit - LOV3 " +
                    "Feat. Bryan Chase, Okasian"
            )
        )

        val specs = AndroidMaskOverlayPlanner.buildSpecs(response, screenWidth = 1080, screenHeight = 2400)

        assertTrue(specs.isEmpty())
    }

    @Test
    fun buildSpecs_keepsStableYoutubeAccessibilityTitleRowsDuringScrollRecaptureGap() {
        val response = responseOf(
            resultOf(
                offensive = true,
                bounds = BoundsRect(159, 940, 943, 1045),
                spans = listOf(EvidenceSpan("Tlqkf", 9, 14, 0.99)),
                original = "What is 'Tlqkf'?_Contemporary Korean Slang",
                authorId = "android-accessibility:youtube_title"
            )
        )

        val specs = AndroidMaskOverlayPlanner.buildSpecs(response, screenWidth = 1080, screenHeight = 2400)

        assertEquals(1, specs.size)
        assertTrue(specs.single().allowScrollTranslation)
        assertTrue(specs.single().top in 940..980)
        assertTrue("spec=${specs.single()}", specs.single().left in 280..380)
        assertTrue(specs.single().width in 64..140)
    }

    @Test
    fun buildSpecs_rejectsLookaheadAccessibilityTargetsUntilVisibleBoundsAreConfirmed() {
        val response = responseOf(
            resultOf(
                offensive = true,
                bounds = BoundsRect(159, 1320, 943, 1400),
                spans = listOf(EvidenceSpan("Tlqkf", 9, 14, 0.99)),
                original = "What is 'Tlqkf'?_Contemporary Korean Slang",
                authorId = "android-accessibility-lookahead:android-accessibility:youtube_title"
            )
        )

        val specs = AndroidMaskOverlayPlanner.buildSpecs(response, screenWidth = 1080, screenHeight = 2400)

        assertTrue(specs.isEmpty())
    }

    @Test
    fun buildSpecs_prefersPreciseOcrOverYoutubeTitleAccessibilityInsideSameCard() {
        val response = responseOf(
            resultOf(
                offensive = true,
                bounds = BoundsRect(220, 793, 879, 882),
                spans = listOf(EvidenceSpan("Tlqkf", 2, 7, 0.99)),
                original = "🔥\"Tlqkf 또 보여줘야 돼!\" : 식케이 (Sik-K), Lil Moshpit",
                authorId = "android-accessibility:youtube_title"
            ),
            resultOf(
                offensive = true,
                bounds = BoundsRect(230, 730, 336, 774),
                spans = listOf(EvidenceSpan("tlqkf", 0, 5, 0.99)),
                original = "tlqkf",
                authorId = "ocr:youtube-composite-card:80,450,1000,932:Tlgkf"
            )
        )

        val specs = AndroidMaskOverlayPlanner.buildSpecs(response, screenWidth = 1080, screenHeight = 2400)

        assertEquals(1, specs.size)
        assertTrue(specs.single().debugSource.startsWith("ocr:youtube-composite-card:"))
        assertTrue(specs.single().top in 720..780)
    }

    @Test
    fun buildSpecs_keepsEstimatedYoutubeShortsTitleMasksTranslatable() {
        val response = responseOf(
            resultOf(
                offensive = true,
                bounds = BoundsRect(41, 1553, 510, 1609),
                spans = listOf(EvidenceSpan("Tlqkf", 0, 5, 0.99)),
                original = "Tlqkf 공부법",
                authorId = "android-accessibility:youtube_shorts_title"
            )
        )

        val specs = AndroidMaskOverlayPlanner.buildSpecs(response, screenWidth = 1080, screenHeight = 2400)

        assertEquals(1, specs.size)
        assertTrue(specs.single().allowScrollTranslation)
        assertTrue(specs.single().left in 32..56)
        assertTrue(specs.single().width in 64..112)
    }

    @Test
    fun buildSpecs_keepsFallbackVisibleBandOcrLineMasks() {
        val response = responseOf(
            resultOf(
                offensive = true,
                bounds = BoundsRect(90, 420, 210, 468),
                spans = listOf(EvidenceSpan("tlqkf", 0, 5, 0.99)),
                original = "tlqkf",
                authorId = "ocr:youtube-visible-band:0,220,1080,844:tlqkf"
            )
        )

        val specs = AndroidMaskOverlayPlanner.buildSpecs(response, screenWidth = 1080, screenHeight = 2400)

        assertEquals(1, specs.size)
        assertEquals(90, specs.single().left)
        assertTrue(specs.single().width in 90..120)
        assertTrue(specs.single().height >= 44)
        assertTrue(specs.single().height <= 56)
        assertTrue(specs.single().allowScrollTranslation)
    }

    @Test
    fun buildSpecs_keepsVisibleBandOcrLineNearTopWhenInsideFallbackBand() {
        val response = responseOf(
            resultOf(
                offensive = true,
                bounds = BoundsRect(90, 190, 210, 238),
                spans = listOf(EvidenceSpan("tlqkf", 0, 5, 0.99)),
                original = "tlqkf",
                authorId = "ocr:youtube-visible-band:0,180,1080,844:tlqkf"
            )
        )

        val specs = AndroidMaskOverlayPlanner.buildSpecs(response, screenWidth = 1080, screenHeight = 2400)

        assertEquals(1, specs.size)
        assertTrue(specs.single().top < 220)
        assertTrue(specs.single().allowScrollTranslation)
    }

    @Test
    fun buildSpecs_keepsLargeStandaloneThumbnailOcrTerm() {
        val response = responseOf(
            resultOf(
                offensive = true,
                bounds = BoundsRect(286, 646, 706, 946),
                spans = listOf(EvidenceSpan("tlqkf", 0, 5, 0.99)),
                original = "tlqkf",
                authorId = "ocr:youtube-composite-card:0,443,1080,1127:Tlak"
            )
        )

        val specs = AndroidMaskOverlayPlanner.buildSpecs(response, screenWidth = 1080, screenHeight = 2400)

        assertEquals(1, specs.size)
        val spec = specs.single()
        assertTrue("spec=$spec", spec.left <= 286)
        assertTrue("spec=$spec", spec.top <= 646)
        assertTrue("spec=$spec", spec.left + spec.width >= 706)
        assertTrue("spec=$spec", spec.top + spec.height >= 946)
        assertTrue(spec.allowScrollTranslation)
    }

    @Test
    fun buildSpecs_keepsMediumLargeStandaloneThumbnailOcrTerm() {
        val response = responseOf(
            resultOf(
                offensive = true,
                bounds = BoundsRect(47, 1338, 393, 1454),
                spans = listOf(EvidenceSpan("tlqkf", 0, 5, 0.99)),
                original = "tlqkf",
                authorId = "ocr:youtube-composite-card:0,1040,1080,1724:Tlqkf"
            )
        )

        val specs = AndroidMaskOverlayPlanner.buildSpecs(response, screenWidth = 1080, screenHeight = 2400)

        assertEquals(1, specs.size)
        val spec = specs.single()
        assertTrue("spec=$spec", spec.left <= 47)
        assertTrue("spec=$spec", spec.top <= 1338)
        assertTrue("spec=$spec", spec.left + spec.width >= 393)
        assertTrue("spec=$spec", spec.top + spec.height >= 1454)
        assertTrue(spec.allowScrollTranslation)
    }

    @Test
    fun buildSpecs_rejectsUnknownOcrSourcesUntilProjectionIsExplicitlyTrusted() {
        val response = responseOf(
            resultOf(
                offensive = true,
                bounds = BoundsRect(90, 420, 210, 468),
                spans = listOf(EvidenceSpan("tlqkf", 0, 5, 0.99)),
                original = "tlqkf",
                authorId = "ocr:experimental-fullscreen:tlqkf"
            )
        )

        val specs = AndroidMaskOverlayPlanner.buildSpecs(response, screenWidth = 1080, screenHeight = 2400)

        assertTrue(specs.isEmpty())
    }

    @Test
    fun buildSpecs_rejectsSemanticFallbackMasksUntilExactOcrIsAvailable() {
        val response = responseOf(
            resultOf(
                offensive = true,
                bounds = BoundsRect(54, 588, 287, 644),
                spans = listOf(EvidenceSpan("tlqkf", 0, 5, 0.99)),
                original = "tlqkf",
                authorId = "ocr:youtube-semantic-card:0,506,1080,1190:Tlqkf"
            )
        )

        val specs = AndroidMaskOverlayPlanner.buildSpecs(response, screenWidth = 1080, screenHeight = 2400)

        assertTrue(specs.isEmpty())
    }

    @Test
    fun buildSpecs_rejectsOversizedSemanticFallbackMasks() {
        val response = responseOf(
            resultOf(
                offensive = true,
                bounds = BoundsRect(0, 506, 1080, 1190),
                spans = listOf(EvidenceSpan("tlqkf", 0, 5, 0.99)),
                original = "tlqkf",
                authorId = "ocr:youtube-semantic-card:0,506,1080,1190:Tlqkf"
            )
        )

        val specs = AndroidMaskOverlayPlanner.buildSpecs(response, screenWidth = 1080, screenHeight = 2400)

        assertTrue(specs.isEmpty())
    }

    @Test
    fun translateSpecs_keepsPreciseOcrMasksDuringNormalViewportScroll() {
        val response = responseOf(
            resultOf(
                offensive = true,
                bounds = BoundsRect(90, 420, 210, 468),
                spans = listOf(EvidenceSpan("tlqkf", 0, 5, 0.99)),
                original = "tlqkf",
                authorId = "ocr:youtube-composite-card:0,300,1080,900:tlqkf"
            )
        )

        val specs = AndroidMaskOverlayPlanner.buildSpecs(response, screenWidth = 1080, screenHeight = 2400)

        assertEquals(1, specs.size)
        assertTrue(specs.single().allowScrollTranslation)

        val translated = AndroidMaskOverlayPlanner.translateSpecs(
            specs = specs,
            deltaX = 0,
            deltaY = -120,
            screenWidth = 1080,
            screenHeight = 2400
        )

        assertEquals(1, translated.size)
        assertEquals(specs.single().top - 120, translated.single().top)
    }

    @Test
    fun buildSpecs_rejectsYoutubeVisualRangeEstimatedMasksUntilExactOcrIsAvailable() {
        val response = responseOf(
            resultOf(
                offensive = true,
                bounds = BoundsRect(147, 814, 237, 862),
                spans = listOf(EvidenceSpan("tlqkf", 0, 5, 0.99)),
                original = "tlqkf",
                authorId = "youtube-visual-range:tlqkf"
            )
        )

        val specs = AndroidMaskOverlayPlanner.buildSpecs(response, screenWidth = 1080, screenHeight = 2400)

        assertTrue(specs.isEmpty())
    }

    @Test
    fun buildSpecs_keepsAccessibilityCharacterRangeMasksTranslatable() {
        val response = responseOf(
            resultOf(
                offensive = true,
                bounds = BoundsRect(118, 520, 262, 558),
                spans = listOf(EvidenceSpan("tlqkf", 0, 5, 0.99)),
                original = "tlqkf",
                authorId = "android-accessibility-char-range:Tlqkf"
            )
        )

        val specs = AndroidMaskOverlayPlanner.buildSpecs(response, screenWidth = 1080, screenHeight = 2400)

        assertEquals(1, specs.size)
        assertEquals(118, specs.single().left)
        assertEquals(520, specs.single().top)
        assertTrue(specs.single().allowScrollTranslation)
    }

    @Test
    fun translateSpecs_doesNotDragEstimatedAccessibilityRangeMasksDuringScroll() {
        val response = responseOf(
            resultOf(
                offensive = true,
                bounds = BoundsRect(24, 900, 106, 935),
                spans = listOf(EvidenceSpan("tlqkf", 0, 5, 0.99)),
                original = "tlqkf",
                authorId = "android-accessibility-range:Tlqkf"
            )
        )

        val specs = AndroidMaskOverlayPlanner.buildSpecs(response, screenWidth = 720, screenHeight = 1280)

        assertEquals(1, specs.size)
        assertFalse(specs.single().allowScrollTranslation)

        val translated = AndroidMaskOverlayPlanner.translateSpecs(
            specs = specs,
            deltaX = 0,
            deltaY = -24,
            screenWidth = 720,
            screenHeight = 1280
        )

        assertTrue(translated.isEmpty())
    }

    @Test
    fun translateSpecs_keepsPreciseOcrMasksForTinyScrollDeltas() {
        val response = responseOf(
            resultOf(
                offensive = true,
                bounds = BoundsRect(90, 420, 210, 468),
                spans = listOf(EvidenceSpan("tlqkf", 0, 5, 0.99)),
                original = "tlqkf",
                authorId = "ocr:youtube-composite-card:0,300,1080,900:tlqkf"
            )
        )

        val specs = AndroidMaskOverlayPlanner.buildSpecs(response, screenWidth = 1080, screenHeight = 2400)

        val translated = AndroidMaskOverlayPlanner.translateSpecs(
            specs = specs,
            deltaX = 0,
            deltaY = -6,
            screenWidth = 1080,
            screenHeight = 2400
        )

        assertEquals(1, translated.size)
        assertEquals(specs.single().top - 6, translated.single().top)
    }

    @Test
    fun buildSpecs_keepsTopHeroYoutubeCompositeOcrMaskBelowAppBar() {
        val response = responseOf(
            resultOf(
                offensive = true,
                bounds = BoundsRect(32, 150, 360, 194),
                spans = listOf(EvidenceSpan("tlqkf", 0, 5, 0.99)),
                original = "tlqkf",
                authorId = "ocr:youtube-composite-card:0,102,681,484:Tlqkf 또 다시 보여줘야돼!!!"
            )
        )

        val specs = AndroidMaskOverlayPlanner.buildSpecs(response, screenWidth = 681, screenHeight = 1454)

        assertEquals(1, specs.size)
        assertTrue(specs.single().top >= 140)
        assertTrue(specs.single().width in 120..190)
    }

    @Test
    fun buildSpecs_stillRejectsSmallTopYoutubeCompositeOcrToolbarMask() {
        val response = responseOf(
            resultOf(
                offensive = true,
                bounds = BoundsRect(88, 72, 180, 118),
                spans = listOf(EvidenceSpan("tlqkf", 0, 5, 0.99)),
                original = "tlqkf",
                authorId = "ocr:youtube-composite-card:0,57,153,195:tlqkf"
            )
        )

        val specs = AndroidMaskOverlayPlanner.buildSpecs(response, screenWidth = 681, screenHeight = 1454)

        assertTrue(specs.isEmpty())
    }

    @Test
    fun buildSpecs_capsShortExactKoreanWordInsideWideSearchBounds() {
        val response = responseOf(
            resultOf(
                offensive = true,
                bounds = BoundsRect(88, 72, 940, 144),
                spans = listOf(EvidenceSpan("씨발", 0, 2, 0.99)),
                original = "씨발"
            )
        )

        val specs = AndroidMaskOverlayPlanner.buildSpecs(response, screenWidth = 1080, screenHeight = 2400)

        assertEquals(1, specs.size)
        assertEquals(88, specs.single().left)
        assertTrue(specs.single().width in 60..74)
        assertTrue(specs.single().height <= 36)
    }

    @Test
    fun buildSpecs_rejectsTopUserInputMask() {
        val response = responseOf(
            resultOf(
                offensive = true,
                bounds = BoundsRect(88, 72, 940, 144),
                spans = listOf(EvidenceSpan("씨발", 0, 2, 0.99)),
                original = "씨발",
                authorId = "android-accessibility:user_input"
            )
        )

        val specs = AndroidMaskOverlayPlanner.buildSpecs(response, screenWidth = 1080, screenHeight = 2400)

        assertTrue(specs.isEmpty())
    }

    @Test
    fun buildSpecs_keepsTopYoutubeSearchInputMask() {
        val response = responseOf(
            resultOf(
                offensive = true,
                bounds = BoundsRect(92, 42, 430, 100),
                spans = listOf(EvidenceSpan("tlqkf", 0, 5, 0.99)),
                original = "tlqkf",
                authorId = "android-accessibility:youtube_user_input"
            )
        )

        val specs = AndroidMaskOverlayPlanner.buildSpecs(response, screenWidth = 656, screenHeight = 1454)

        assertEquals(1, specs.size)
        assertEquals(100, specs.single().left)
        assertTrue("spec=${specs.single()}", specs.single().width in 180..196)
        assertTrue(specs.single().allowScrollTranslation)
    }

    @Test
    fun buildSpecs_usesWideTopSearchGeometryWhenSourceIsMissing() {
        val response = responseOf(
            resultOf(
                offensive = true,
                bounds = BoundsRect(105, 60, 452, 112),
                spans = listOf(EvidenceSpan("tlqkf", 0, 5, 0.99)),
                original = "tlqkf"
            )
        )

        val specs = AndroidMaskOverlayPlanner.buildSpecs(response, screenWidth = 675, screenHeight = 1478)

        assertEquals(1, specs.size)
        assertEquals(113, specs.single().left)
        assertTrue("spec=${specs.single()}", specs.single().width in 180..196)
    }

    @Test
    fun buildSpecs_rejectsChromeSearchInputMaskBelowToolbarControls() {
        val response = responseOf(
            resultOf(
                offensive = true,
                bounds = BoundsRect(34, 248, 1048, 330),
                spans = listOf(EvidenceSpan("시발", 0, 2, 0.99)),
                original = "시발",
                authorId = "android-accessibility:user_input"
            )
        )

        val specs = AndroidMaskOverlayPlanner.buildSpecs(response, screenWidth = 1080, screenHeight = 2400)

        assertTrue(specs.isEmpty())
    }

    @Test
    fun buildSpecs_rejectsTopChromeControlAccessibilityMasks() {
        val response = responseOf(
            resultOf(
                offensive = true,
                bounds = BoundsRect(0, 96, 160, 138),
                spans = listOf(EvidenceSpan("시발", 0, 2, 0.99)),
                original = "시발",
                authorId = "android-accessibility:title"
            ),
            resultOf(
                offensive = true,
                bounds = BoundsRect(120, 120, 210, 154),
                spans = listOf(EvidenceSpan("tlqkf", 0, 5, 0.99)),
                original = "tlqkf",
                authorId = "android-accessibility-range:Tlqkf"
            )
        )

        val specs = AndroidMaskOverlayPlanner.buildSpecs(response, screenWidth = 1080, screenHeight = 2400)

        assertTrue(specs.isEmpty())
    }

    @Test
    fun buildSpecs_rejectsTopControlVisualOcrMasks() {
        val response = responseOf(
            resultOf(
                offensive = true,
                bounds = BoundsRect(20, 92, 160, 136),
                spans = listOf(EvidenceSpan("tlqkf", 0, 5, 0.99)),
                original = "tlqkf",
                authorId = "ocr:generic-visual-region:0,60,720,180:tlqkf"
            )
        )

        val specs = AndroidMaskOverlayPlanner.buildSpecs(response, screenWidth = 720, screenHeight = 1280)

        assertTrue(specs.isEmpty())
    }

    @Test
    fun buildSpecs_rejectsGenericBrowserVisualMaskBelowExpandedToolbar() {
        val response = responseOf(
            resultOf(
                offensive = true,
                bounds = BoundsRect(88, 268, 176, 304),
                spans = listOf(EvidenceSpan("tlqkf", 0, 5, 0.99)),
                original = "tlqkf",
                authorId = "ocr:generic-visual-region:0,180,720,360:tlqkf"
            )
        )

        val specs = AndroidMaskOverlayPlanner.buildSpecs(response, screenWidth = 720, screenHeight = 1280)

        assertTrue(specs.isEmpty())
    }

    @Test
    fun buildSpecs_rejectsGenericVisualRegionMasksUntilExactProjectionIsAvailable() {
        val response = responseOf(
            resultOf(
                offensive = true,
                bounds = BoundsRect(86, 642, 158, 678),
                spans = listOf(EvidenceSpan("tlqkf", 0, 5, 0.99)),
                original = "tlqkf",
                authorId = "ocr:generic-visual-region:0,500,720,920:tlqkf"
            )
        )

        val specs = AndroidMaskOverlayPlanner.buildSpecs(response, screenWidth = 720, screenHeight = 1280)

        assertTrue(specs.isEmpty())
    }

    @Test
    fun translateSpecs_doesNotDragGenericAccessibilityMasksDuringScroll() {
        val response = responseOf(
            resultOf(
                offensive = true,
                bounds = BoundsRect(40, 540, 940, 620),
                spans = listOf(EvidenceSpan("시발", 0, 2, 0.99)),
                original = "시발 뭐하는 거야",
                authorId = "android-accessibility:title"
            )
        )

        val specs = AndroidMaskOverlayPlanner.buildSpecs(response, screenWidth = 1080, screenHeight = 2400)

        assertEquals(1, specs.size)
        assertFalse(specs.single().allowScrollTranslation)

        val translated = AndroidMaskOverlayPlanner.translateSpecs(
            specs = specs,
            deltaX = 0,
            deltaY = -24,
            screenWidth = 1080,
            screenHeight = 2400
        )

        assertTrue(translated.isEmpty())
    }

    @Test
    fun translateSpecs_keepsPlatformCommentMasksDuringScrollRecaptureGap() {
        val response = responseOf(
            resultOf(
                offensive = true,
                bounds = BoundsRect(40, 540, 940, 620),
                spans = listOf(EvidenceSpan("tlqkf", 0, 5, 0.99)),
                original = "tlqkf 뭐냐 진짜",
                authorId = "android-accessibility-comment:youtube"
            )
        )

        val specs = AndroidMaskOverlayPlanner.buildSpecs(response, screenWidth = 1080, screenHeight = 2400)

        assertEquals(1, specs.size)
        assertTrue(specs.single().allowScrollTranslation)

        val translated = AndroidMaskOverlayPlanner.translateSpecs(
            specs = specs,
            deltaX = 0,
            deltaY = -24,
            screenWidth = 1080,
            screenHeight = 2400
        )

        assertEquals(1, translated.size)
        assertEquals(specs.single().top - 24, translated.single().top)
    }

    @Test
    fun translateSpecs_keepsLowerUserInputMasksDuringScroll() {
        val response = responseOf(
            resultOf(
                offensive = true,
                bounds = BoundsRect(88, 420, 940, 492),
                spans = listOf(EvidenceSpan("씨발", 0, 2, 0.99)),
                original = "씨발",
                authorId = "android-accessibility:user_input"
            )
        )

        val specs = AndroidMaskOverlayPlanner.buildSpecs(response, screenWidth = 1080, screenHeight = 2400)

        assertEquals(1, specs.size)
        assertTrue(specs.single().allowScrollTranslation)

        val translated = AndroidMaskOverlayPlanner.translateSpecs(
            specs = specs,
            deltaX = 0,
            deltaY = -24,
            screenWidth = 1080,
            screenHeight = 2400
        )

        assertEquals(1, translated.size)
        assertEquals(specs.single().top - 24, translated.single().top)
    }

    @Test
    fun buildSpecs_capsShortStandaloneWordInsteadOfCoveringWholeHeading() {
        val response = responseOf(
            resultOf(
                offensive = true,
                bounds = BoundsRect(60, 350, 1020, 430),
                spans = listOf(EvidenceSpan("개새끼", 0, 3, 0.99)),
                original = "개새끼"
            )
        )

        val specs = AndroidMaskOverlayPlanner.buildSpecs(response, screenWidth = 1080, screenHeight = 2400)

        assertEquals(1, specs.size)
        assertEquals(60, specs.single().left)
        assertTrue("spec=${specs.single()}", specs.single().width in 88..104)
    }

    @Test
    fun buildSpecs_handlesNonTlqkfExactVisualTermsWithCompactBounds() {
        val response = responseOf(
            resultOf(
                offensive = true,
                bounds = BoundsRect(40, 100, 540, 150),
                spans = listOf(EvidenceSpan("ssibal", 0, 6, 0.99)),
                original = "ssibal"
            ),
            resultOf(
                offensive = true,
                bounds = BoundsRect(40, 180, 540, 230),
                spans = listOf(EvidenceSpan("qudtls", 0, 6, 0.99)),
                original = "qudtls"
            )
        )

        val specs = AndroidMaskOverlayPlanner.buildSpecs(response, screenWidth = 1080, screenHeight = 2400)

        assertEquals(2, specs.size)
        assertTrue(specs.all { it.width in 104..116 })
    }

    @Test
    fun buildSpecs_capsAdditionalKeyboardVariantMasksInsideWideRows() {
        val response = responseOf(
            resultOf(
                offensive = true,
                bounds = BoundsRect(40, 100, 940, 150),
                spans = listOf(EvidenceSpan("지랄", 0, 2, 0.99)),
                original = "지랄"
            ),
            resultOf(
                offensive = true,
                bounds = BoundsRect(40, 180, 940, 230),
                spans = listOf(EvidenceSpan("존나", 0, 2, 0.99)),
                original = "존나"
            ),
            resultOf(
                offensive = true,
                bounds = BoundsRect(40, 260, 940, 310),
                spans = listOf(EvidenceSpan("미친", 0, 2, 0.99)),
                original = "미친"
            ),
            resultOf(
                offensive = true,
                bounds = BoundsRect(40, 340, 940, 390),
                spans = listOf(EvidenceSpan("꺼져", 0, 2, 0.99)),
                original = "꺼져"
            )
        )

        val specs = AndroidMaskOverlayPlanner.buildSpecs(response, screenWidth = 1080, screenHeight = 2400)

        assertEquals(4, specs.size)
        assertTrue(specs.all { it.left == 40 })
        assertTrue(specs.all { it.width <= 112 })
        assertTrue(specs.all { it.height <= 36 })
    }

    @Test
    fun translateSpecs_movesMasksByScrollDeltaWithoutResizing() {
        val specs = listOf(
            MaskOverlaySpec(left = 80, top = 420, width = 96, height = 34, label = "***")
        )

        val translated = AndroidMaskOverlayPlanner.translateSpecs(
            specs = specs,
            deltaX = 0,
            deltaY = -64,
            screenWidth = 1080,
            screenHeight = 2400
        )

        assertEquals(1, translated.size)
        assertEquals(80, translated.single().left)
        assertEquals(356, translated.single().top)
        assertEquals(96, translated.single().width)
        assertEquals(34, translated.single().height)
    }

    @Test
    fun translateSpecs_dropsAllMasksForExtremeScrollDeltaInsteadOfGuessing() {
        val specs = listOf(
            MaskOverlaySpec(left = 80, top = 1200, width = 96, height = 34, label = "***")
        )

        val translated = AndroidMaskOverlayPlanner.translateSpecs(
            specs = specs,
            deltaX = 0,
            deltaY = -720,
            screenWidth = 1080,
            screenHeight = 2400
        )

        assertTrue(translated.isEmpty())
    }

    @Test
    fun translatePlan_reportsRejectedDeltaWithoutMutatingSession() {
        val specs = listOf(
            MaskOverlaySpec(left = 80, top = 1200, width = 96, height = 34, label = "***")
        )

        val plan = AndroidMaskOverlayPlanner.translatePlan(
            specs = specs,
            deltaX = 0,
            deltaY = -720,
            screenWidth = 1080,
            screenHeight = 2400
        )

        assertEquals(MaskOverlayTranslationStatus.REJECTED_DELTA, plan.status)
        assertTrue(plan.specs.isEmpty())
    }

    @Test
    fun translatePlan_reportsNonTranslatableMasksSeparately() {
        val specs = listOf(
            MaskOverlaySpec(
                left = 80,
                top = 420,
                width = 96,
                height = 34,
                label = "***",
                allowScrollTranslation = false
            )
        )

        val plan = AndroidMaskOverlayPlanner.translatePlan(
            specs = specs,
            deltaX = 0,
            deltaY = -64,
            screenWidth = 1080,
            screenHeight = 2400
        )

        assertEquals(MaskOverlayTranslationStatus.NO_TRANSLATABLE_MASKS, plan.status)
        assertTrue(plan.specs.isEmpty())
    }

    @Test
    fun translateSpecs_dropsMasksThatScrolledFullyOffscreen() {
        val specs = listOf(
            MaskOverlaySpec(left = 80, top = 24, width = 96, height = 34, label = "***"),
            MaskOverlaySpec(left = 80, top = 420, width = 96, height = 34, label = "***")
        )

        val translated = AndroidMaskOverlayPlanner.translateSpecs(
            specs = specs,
            deltaX = 0,
            deltaY = -80,
            screenWidth = 1080,
            screenHeight = 2400
        )

        assertEquals(1, translated.size)
        assertEquals(340, translated.single().top)
    }

    @Test
    fun translateSpecs_preservesPartiallyOffscreenCoordinatesDuringScroll() {
        val specs = listOf(
            MaskOverlaySpec(left = 10, top = 18, width = 96, height = 34, label = "***")
        )

        val translated = AndroidMaskOverlayPlanner.translateSpecs(
            specs = specs,
            deltaX = -24,
            deltaY = -30,
            screenWidth = 1080,
            screenHeight = 2400
        )

        assertEquals(1, translated.size)
        assertEquals(-14, translated.single().left)
        assertEquals(-12, translated.single().top)
    }

    @Test
    fun buildSpecs_placesLateSpansOnEstimatedLaterLines() {
        val original = "초반에는 안전한 설명이 길게 이어지고 뒤쪽 줄에서 시발 같은 표현이 등장하는 긴 제목"
        val spanStart = original.indexOf("시발")
        val response = responseOf(
            resultOf(
                offensive = true,
                bounds = BoundsRect(40, 100, 520, 236),
                spans = listOf(EvidenceSpan("시발", spanStart, spanStart + 2, 0.98)),
                original = original
            )
        )

        val specs = AndroidMaskOverlayPlanner.buildSpecs(response, screenWidth = 1080, screenHeight = 2400)

        assertEquals(1, specs.size)
        assertTrue(specs.single().top > 150)
        assertTrue(specs.single().height <= 36)
        assertTrue(specs.single().width < 480)
    }

    @Test
    fun buildSpecs_usesBackendCodePointOffsetsForEmojiPrefixedText() {
        val response = responseOf(
            resultOf(
                offensive = true,
                bounds = BoundsRect(0, 100, 600, 150),
                spans = listOf(EvidenceSpan("시발", 4, 6, 0.98)),
                original = "😀😀😀😀시발"
            )
        )

        val specs = AndroidMaskOverlayPlanner.buildSpecs(response, screenWidth = 1080, screenHeight = 2400)

        assertEquals(1, specs.size)
        assertTrue(specs.single().left > 320)
        assertTrue(specs.single().left < 540)
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
        original: String = "sample",
        authorId: String? = null
    ): AndroidAnalysisResultItem {
        return AndroidAnalysisResultItem(
            original = original,
            boundsInScreen = bounds,
            authorId = authorId,
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
