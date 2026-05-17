package com.capstone.design.youtubeparser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualTextOcrCandidateFilterTest {

    @Test
    fun shouldAnalyze_keepsLikelyOffensiveVisualText() {
        assertTrue(VisualTextOcrCandidateFilter.shouldAnalyze("Tlqkf 공부법"))
        assertTrue(VisualTextOcrCandidateFilter.shouldAnalyze("TIqkf 또 다시 보여줘야돼!!!"))
        assertTrue(VisualTextOcrCandidateFilter.shouldAnalyze("T|qkf 자동자"))
        assertTrue(VisualTextOcrCandidateFilter.shouldAnalyze("Tlakf 또 보여줘야 돼"))
        assertTrue(VisualTextOcrCandidateFilter.shouldAnalyze("Tlgkf 공부법"))
        assertTrue(VisualTextOcrCandidateFilter.shouldAnalyze("11KT 또 보여줘야 돼"))
        assertTrue(VisualTextOcrCandidateFilter.shouldAnalyze("C발 비용 효과 있을까?"))
        assertTrue(VisualTextOcrCandidateFilter.shouldAnalyze("ssibal 뜻"))
        assertTrue(VisualTextOcrCandidateFilter.shouldAnalyze("병신아 꺼져"))
        assertTrue(VisualTextOcrCandidateFilter.shouldAnalyze("개새끼 뭐하는 거야"))
    }

    @Test
    fun shouldAnalyze_dropsDecorativeOrSafeVisualText() {
        assertFalse(VisualTextOcrCandidateFilter.shouldAnalyze("Contemporary Korean Slang"))
        assertFalse(VisualTextOcrCandidateFilter.shouldAnalyze("Love Love Love"))
        assertFalse(VisualTextOcrCandidateFilter.shouldAnalyze("조회수 2.1M views"))
        assertFalse(VisualTextOcrCandidateFilter.shouldAnalyze("warp theme"))
    }

    @Test
    fun isUsefulOcrLineText_keepsLongYoutubeTitleForCandidateExtraction() {
        val title = "🔥\"Tlqkf 또 보여줘야 돼!\" : 식케이 (Sik-K), Lil Moshpit - LOV3 " +
            "(Feat. Bryan Chase, Okasian) 공식 라이브 무대 다시 보기와 한국 힙합 밈 설명 모음 " +
            "검색 결과 제목 전체가 길게 이어지는 OCR 라인"

        assertTrue(title.length > 120)
        assertTrue(VisualTextOcrCandidateFilter.isUsefulOcrLineText(title))
        assertEquals(listOf("tlqkf"), VisualTextOcrCandidateFilter.findAnalysisRanges(title).map { it.analysisText })
    }

    @Test
    fun isUsefulOcrLineText_dropsCommonUiAndNumericLines() {
        assertFalse(VisualTextOcrCandidateFilter.isUsefulOcrLineText("Shorts"))
        assertFalse(VisualTextOcrCandidateFilter.isUsefulOcrLineText("2.1M"))
        assertFalse(VisualTextOcrCandidateFilter.isUsefulOcrLineText("https://youtube.com/watch?v=test"))
    }

    @Test
    fun findAnalysisRanges_returnsOnlyLikelyHarmfulSubstrings() {
        val ranges = VisualTextOcrCandidateFilter.findAnalysisRanges("What is 'Tlakf'? Contemporary Korean")

        assertEquals(listOf("tlqkf"), ranges.map { it.analysisText })
        assertEquals(listOf("Tlakf"), ranges.map { it.visualText })
        assertEquals("Tlakf", "What is 'Tlakf'? Contemporary Korean".substring(ranges.single().start, ranges.single().end))
    }

    @Test
    fun findAnalysisRanges_keepsMultipleVisualHits() {
        val ranges = VisualTextOcrCandidateFilter.findAnalysisRanges("병신아 꺼져 / Tlqkf 자동자")

        assertEquals(listOf("병신", "꺼져", "tlqkf"), ranges.map { it.analysisText })
    }

    @Test
    fun findAnalysisRanges_keepsFullKoreanCompoundProfanityForBackendDictionary() {
        val ranges = VisualTextOcrCandidateFilter.findAnalysisRanges("개새끼 뭐하는 거야")

        assertEquals(listOf("개새끼"), ranges.map { it.analysisText })
        assertEquals(listOf("개새끼"), ranges.map { it.visualText })
    }

    @Test
    fun findAnalysisRanges_canonicalizesCommonOcrMisreadsForBackend() {
        val samples = listOf(
            "Tlakf",
            "Tlgkf",
            "TIqkf",
            "T|qkf",
            "Tlkf",
            "TIkf",
            "TIokt",
            "Tlolkf",
            "IIakt",
            "11KT"
        )

        samples.forEach { sample ->
            val ranges = VisualTextOcrCandidateFilter.findAnalysisRanges("$sample 또 보여줘야 돼")

            assertEquals(sample, "tlqkf", ranges.single().analysisText)
        }
    }

    @Test
    fun findAnalysisRanges_canonicalizesSpacedLatinOcrGlyphsForBackend() {
        val samples = listOf("T l q k f", "T l a k f", "T | q k f")

        samples.forEach { sample ->
            val ranges = VisualTextOcrCandidateFilter.findAnalysisRanges("$sample 또 보여줘야 돼")

            assertEquals(sample, "tlqkf", ranges.single().analysisText)
            assertEquals(sample, ranges.single().visualText)
        }
    }

    @Test
    fun findAnalysisRanges_canonicalizesSpacedSibalOcrGlyphsForBackend() {
        val samples = listOf("s s i b a l", "s i b a l", "ssibal")

        samples.forEach { sample ->
            val ranges = VisualTextOcrCandidateFilter.findAnalysisRanges("$sample 뜻")

            assertEquals(sample, listOf("ssibal"), ranges.map { it.analysisText })
            assertEquals(sample, listOf(sample), ranges.map { it.visualText })
        }
    }

    @Test
    fun findAnalysisRanges_keepsMixedLatinKoreanSibalVariant() {
        val ranges = VisualTextOcrCandidateFilter.findAnalysisRanges("C발 비용 효과 있을까?")

        assertEquals(listOf("C발"), ranges.map { it.analysisText })
        assertEquals(listOf("C발"), ranges.map { it.visualText })
    }

    @Test
    fun findAnalysisRanges_canonicalizesAdditionalKeyboardVariantsForBackend() {
        val ranges = VisualTextOcrCandidateFilter.findAnalysisRanges("wlfkf / whssk / alcls / rjwu")

        assertEquals(listOf("지랄", "존나", "미친", "꺼져"), ranges.map { it.analysisText })
    }

    @Test
    fun findAnalysisRanges_keepsAdditionalKoreanAndChosungCandidates() {
        val ranges = VisualTextOcrCandidateFilter.findAnalysisRanges("ㅈㄴ 지랄 ㅁㅊ ㄷㅊ ㄲㅈ 엿먹")

        assertEquals(listOf("ㅈㄴ", "지랄", "ㅁㅊ", "ㄷㅊ", "ㄲㅈ", "엿먹"), ranges.map { it.analysisText })
    }

    @Test
    fun visualTextOcrMetadataCodec_keepsSourceAndRoiBounds() {
        val encoded = VisualTextOcrMetadataCodec.encode(
            source = "youtube-composite-card",
            roiBoundsInScreen = BoundsRect(left = 0, top = 309, right = 1080, bottom = 993),
            visualText = "Tlqkf 공부법"
        )
        val decoded = VisualTextOcrMetadataCodec.decode(encoded)

        assertEquals("youtube-composite-card", decoded?.source)
        assertEquals(BoundsRect(left = 0, top = 309, right = 1080, bottom = 993), decoded?.roiBoundsInScreen)
        assertEquals("Tlqkf 공부법", decoded?.visualText)
    }

    @Test
    fun visualTextOcrMetadataCodec_supportsLegacySourceOnlyAuthorId() {
        val decoded = VisualTextOcrMetadataCodec.decode("ocr:youtube-visible-band:시발")

        assertEquals("youtube-visible-band", decoded?.source)
        assertNull(decoded?.roiBoundsInScreen)
        assertEquals("시발", decoded?.visualText)
    }

    @Test
    fun visualTextGeometryPolicy_keepsTopHeroYoutubeCompositeSeparateFromToolbarControls() {
        val heroAuthorId = VisualTextOcrMetadataCodec.encode(
            source = "youtube-composite-card",
            roiBoundsInScreen = BoundsRect(left = 0, top = 102, right = 681, bottom = 484),
            visualText = "Tlqkf 또 다시 보여줘야돼!!!"
        )
        val toolbarAuthorId = VisualTextOcrMetadataCodec.encode(
            source = "youtube-composite-card",
            roiBoundsInScreen = BoundsRect(left = 0, top = 57, right = 153, bottom = 195),
            visualText = "tlqkf"
        )

        assertTrue(VisualTextGeometryPolicy.isTopHeroYoutubeComposite(heroAuthorId, screenWidth = 681))
        assertFalse(VisualTextGeometryPolicy.isTopHeroYoutubeComposite(toolbarAuthorId, screenWidth = 681))
    }

    @Test
    fun visualTextGeometryPolicy_trustsVisibleBandOcrOnlyInsideRoi() {
        val authorId = VisualTextOcrMetadataCodec.encode(
            source = "youtube-visible-band",
            roiBoundsInScreen = BoundsRect(left = 0, top = 180, right = 1080, bottom = 844),
            visualText = "tlqkf"
        )

        assertTrue(
            VisualTextGeometryPolicy.isTrustedVisibleBandOcr(
                authorId = authorId,
                left = 90,
                top = 190,
                right = 210,
                bottom = 238
            )
        )
        assertFalse(
            VisualTextGeometryPolicy.isTrustedVisibleBandOcr(
                authorId = authorId,
                left = 90,
                top = 120,
                right = 210,
                bottom = 168
            )
        )
    }
}
