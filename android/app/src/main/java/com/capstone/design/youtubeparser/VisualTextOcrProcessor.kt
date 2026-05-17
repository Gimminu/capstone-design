package com.capstone.design.youtubeparser

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import androidx.core.graphics.createBitmap
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class VisualTextOcrProcessor {
    @Volatile private var warmUpStarted = false

    private data class RecognizerSpec(
        val name: String,
        val recognizer: TextRecognizer
    )

    private enum class OcrImageVariant {
        RAW,
        HIGH_CONTRAST
    }

    private data class OcrWorkItem(
        val roi: VisualTextRoi,
        val recognizer: RecognizerSpec,
        val variant: OcrImageVariant
    )

    private val recognizers = listOf(
        RecognizerSpec(
            name = "korean",
            recognizer = TextRecognition.getClient(
                KoreanTextRecognizerOptions.Builder().build()
            )
        ),
        RecognizerSpec(
            name = "latin",
            recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        )
    )

    fun warmUp() {
        if (warmUpStarted) return
        warmUpStarted = true

        val bitmap = createBitmap(WARM_UP_BITMAP_SIZE_PX, WARM_UP_BITMAP_SIZE_PX)
        bitmap.eraseColor(Color.TRANSPARENT)
        val image = InputImage.fromBitmap(bitmap, 0)
        val pendingCount = AtomicInteger(recognizers.size)

        recognizers.forEach { recognizerSpec ->
            recognizerSpec.recognizer
                .process(image)
                .addOnCompleteListener {
                    if (pendingCount.decrementAndGet() == 0 && !bitmap.isRecycled) {
                        bitmap.recycle()
                    }
                }
        }
    }

    fun recognize(
        screenshot: Bitmap,
        rois: List<VisualTextRoi>,
        onComplete: (List<ParsedComment>) -> Unit
    ) {
        val selectedRois = rois.take(MAX_ROIS_PER_PASS)
        if (selectedRois.isEmpty() || screenshot.isRecycled) {
            onComplete(emptyList())
            return
        }

        val workItems = selectedRois.flatMap { roi ->
            recognizers.flatMap { recognizer ->
                imageVariantsFor(roi).map { variant ->
                    OcrWorkItem(
                        roi = roi,
                        recognizer = recognizer,
                        variant = variant
                    )
                }
            }
        }
        val pendingCount = AtomicInteger(workItems.size)
        val candidates = Collections.synchronizedList(mutableListOf<ParsedComment>())

        fun finishOne() {
            if (pendingCount.decrementAndGet() == 0) {
                onComplete(deduplicate(candidates))
            }
        }

        workItems.forEach { workItem ->
            val roi = workItem.roi
            val crop = cropBitmap(screenshot, roi.boundsInScreen)
            if (crop == null) {
                finishOne()
                return@forEach
            }
            val processBitmap = bitmapForVariant(crop, workItem.variant)

            val image = InputImage.fromBitmap(processBitmap, 0)
            workItem.recognizer.recognizer
                .process(image)
                .addOnSuccessListener { text ->
                    candidates += text.toParsedComments(
                        roiBounds = roi.boundsInScreen,
                        roiSource = roi.source
                    )
                }
                .addOnFailureListener {
                    // OCR is a best-effort visual supplement; accessibility text analysis remains primary.
                }
                .addOnCompleteListener {
                    if (processBitmap !== crop && !processBitmap.isRecycled) {
                        processBitmap.recycle()
                    }
                    if (!crop.isRecycled) {
                        crop.recycle()
                    }
                    finishOne()
                }
        }
    }

    private fun imageVariantsFor(roi: VisualTextRoi): List<OcrImageVariant> {
        return if (roi.source == "youtube-composite-card" || roi.source == "youtube-visible-band") {
            listOf(OcrImageVariant.RAW, OcrImageVariant.HIGH_CONTRAST)
        } else {
            listOf(OcrImageVariant.RAW)
        }
    }

    private fun bitmapForVariant(crop: Bitmap, variant: OcrImageVariant): Bitmap {
        return when (variant) {
            OcrImageVariant.RAW -> crop
            OcrImageVariant.HIGH_CONTRAST -> crop.toHighContrastOcrBitmap() ?: crop
        }
    }

    private fun Bitmap.toHighContrastOcrBitmap(): Bitmap? {
        if (isRecycled || width <= 0 || height <= 0) return null

        return runCatching {
            val output = createBitmap(width, height)
            val pixels = IntArray(width * height)
            getPixels(pixels, 0, width, 0, 0, width, height)
            for (index in pixels.indices) {
                val pixel = pixels[index]
                val luminance = (
                    Color.red(pixel) * 30 +
                        Color.green(pixel) * 59 +
                        Color.blue(pixel) * 11
                    ) / 100
                val boosted = ((luminance - OCR_CONTRAST_BLACK_POINT) * 255 /
                    (OCR_CONTRAST_WHITE_POINT - OCR_CONTRAST_BLACK_POINT))
                    .coerceIn(0, 255)
                pixels[index] = Color.argb(Color.alpha(pixel), boosted, boosted, boosted)
            }
            output.setPixels(pixels, 0, width, 0, 0, width, height)
            output
        }.getOrNull()
    }

    private fun cropBitmap(
        screenshot: Bitmap,
        bounds: BoundsRect
    ): Bitmap? {
        val left = bounds.left.coerceIn(0, screenshot.width)
        val top = bounds.top.coerceIn(0, screenshot.height)
        val right = bounds.right.coerceIn(left, screenshot.width)
        val bottom = bounds.bottom.coerceIn(top, screenshot.height)
        val width = right - left
        val height = bottom - top

        if (width < MIN_CROP_WIDTH_PX || height < MIN_CROP_HEIGHT_PX) return null

        return runCatching {
            Bitmap.createBitmap(screenshot, left, top, width, height)
        }.getOrNull()
    }

    private fun Text.toParsedComments(
        roiBounds: BoundsRect,
        roiSource: String
    ): List<ParsedComment> {
        return textBlocks
            .flatMap { block -> block.lines }
            .flatMap { line ->
                line.toParsedComments(
                    roiBounds = roiBounds,
                    roiSource = roiSource
                )
            }
    }

    private fun Text.Line.toParsedComments(
        roiBounds: BoundsRect,
        roiSource: String
    ): List<ParsedComment> {
        val lineText = text.replace(Regex("\\s+"), " ").trim()
        if (!VisualTextOcrCandidateFilter.isUsefulOcrLineText(lineText)) return emptyList()
        val candidateRanges = VisualTextOcrCandidateFilter.findAnalysisRanges(lineText)
        if (candidateRanges.isEmpty()) {
            if (VisualTextOcrCandidateFilter.looksDebugRelevant(lineText)) {
                Log.d(TAG, "OCR line filtered text=$lineText")
            }
            return emptyList()
        }

        return candidateRanges.mapNotNull { range ->
            val narrowedBounds = boundsForCandidateRange(
                line = this,
                lineText = lineText,
                range = range,
                roiBounds = roiBounds
            ) ?: return@mapNotNull null

            Log.d(TAG, "OCR line candidate text=$lineText bounds=$narrowedBounds ranges=$candidateRanges")

            ParsedComment(
                commentText = range.analysisText,
                boundsInScreen = narrowedBounds,
                authorId = VisualTextOcrMetadataCodec.encode(
                    source = roiSource,
                    roiBoundsInScreen = roiBounds,
                    visualText = range.visualText
                )
            )
        }
    }

    private fun boundsForCandidateRange(
        line: Text.Line,
        lineText: String,
        range: VisualTextOcrCandidateFilter.CandidateRange,
        roiBounds: BoundsRect
    ): BoundsRect? {
        val elementBounds = line.elementBoundsForRange(
            lineText = lineText,
            range = range,
            roiBounds = roiBounds
        )
        if (elementBounds != null) return elementBounds

        val lineBounds = line.boundingBox?.let { box -> translateBounds(box, roiBounds) } ?: return null
        if (!canUseLineBoundsFallback(lineText, range, lineBounds)) {
            Log.d(
                TAG,
                "drop OCR range without element geometry text=$lineText range=${range.visualText} bounds=$lineBounds"
            )
            return null
        }

        return narrowBoundsToTextRange(
            bounds = lineBounds,
            clipBounds = roiBounds,
            textLength = lineText.length,
            start = range.start,
            end = range.end,
            visualText = range.visualText
        )
    }

    private fun canUseLineBoundsFallback(
        lineText: String,
        range: VisualTextOcrCandidateFilter.CandidateRange,
        lineBounds: BoundsRect
    ): Boolean {
        val normalizedLine = lineText.replace(Regex("\\s+"), " ").trim()
        val normalizedVisualText = range.visualText.replace(Regex("\\s+"), " ").trim()
        if (normalizedLine.isBlank() || normalizedVisualText.isBlank()) return false

        val isWholeShortLine =
            normalizedLine.equals(normalizedVisualText, ignoreCase = true) &&
                normalizedLine.codePointCount(0, normalizedLine.length) <= MAX_LINE_FALLBACK_CODEPOINTS
        if (!isWholeShortLine) return false

        val width = lineBounds.right - lineBounds.left
        val height = lineBounds.bottom - lineBounds.top
        return width in MIN_TEXT_WIDTH_PX..MAX_LINE_FALLBACK_WIDTH_PX &&
            height in MIN_TEXT_HEIGHT_PX..MAX_LINE_FALLBACK_HEIGHT_PX
    }

    private fun Text.Line.elementBoundsForRange(
        lineText: String,
        range: VisualTextOcrCandidateFilter.CandidateRange,
        roiBounds: BoundsRect
    ): BoundsRect? {
        val mappedElements = mapElementsToLineText(lineText, roiBounds)
        if (mappedElements.isEmpty()) return null

        val matchedBounds = mappedElements.mapNotNull { mapped ->
            val overlapStart = max(mapped.start, range.start)
            val overlapEnd = min(mapped.end, range.end)
            if (overlapEnd <= overlapStart) return@mapNotNull null

            val translated = mapped.bounds ?: return@mapNotNull null
            if (range.start >= mapped.start && range.end <= mapped.end) {
                narrowBoundsToTextRange(
                    bounds = translated,
                    clipBounds = roiBounds,
                    textLength = (mapped.end - mapped.start).coerceAtLeast(1),
                    start = range.start - mapped.start,
                    end = range.end - mapped.start,
                    visualText = range.visualText
                )
            } else {
                translated
            }
        }

        return unionBounds(matchedBounds)
    }

    private fun Text.Line.mapElementsToLineText(
        lineText: String,
        roiBounds: BoundsRect
    ): List<MappedTextElement> {
        val mapped = mutableListOf<MappedTextElement>()
        var searchStart = 0

        elements.forEach { element ->
            val elementText = element.text.replace(Regex("\\s+"), " ").trim()
            if (elementText.isBlank()) return@forEach

            val start = lineText.indexOf(elementText, startIndex = searchStart)
            if (start < 0) return@forEach

            val end = start + elementText.length
            mapped += MappedTextElement(
                start = start,
                end = end,
                bounds = element.boundingBox?.let { box -> translateBounds(box, roiBounds) }
            )
            searchStart = end
        }

        return mapped
    }

    private data class MappedTextElement(
        val start: Int,
        val end: Int,
        val bounds: BoundsRect?
    )

    private fun unionBounds(bounds: List<BoundsRect>): BoundsRect? {
        if (bounds.isEmpty()) return null
        return BoundsRect(
            left = bounds.minOf { it.left },
            top = bounds.minOf { it.top },
            right = bounds.maxOf { it.right },
            bottom = bounds.maxOf { it.bottom }
        )
    }

    private fun narrowBoundsToTextRange(
        bounds: BoundsRect,
        clipBounds: BoundsRect,
        textLength: Int,
        start: Int,
        end: Int,
        visualText: String
    ): BoundsRect? {
        if (textLength <= 0 || end <= start) return null

        val width = bounds.right - bounds.left
        if (width < MIN_TEXT_WIDTH_PX) return null

        val startRatio = start.coerceIn(0, textLength).toFloat() / textLength.toFloat()
        val endRatio = end.coerceIn(start + 1, textLength).toFloat() / textLength.toFloat()
        val rawLeft = bounds.left + (width * startRatio).roundToInt()
        val rawRight = bounds.left + (width * endRatio).roundToInt()
        var left = rawLeft
        var right = rawRight

        val minWidth = minOf(width, MIN_TEXT_WIDTH_PX)
        if (right - left < minWidth) {
            val center = (left + right) / 2
            left = center - minWidth / 2
            right = left + minWidth
        }

        if (left < bounds.left) {
            right += bounds.left - left
            left = bounds.left
        }
        if (right > bounds.right) {
            left -= right - bounds.right
            right = bounds.right
        }

        left = left.coerceAtLeast(bounds.left)
        right = right.coerceAtMost(bounds.right)
        if (right - left < MIN_TEXT_WIDTH_PX) return null

        val maxWidth = estimateCompactTextWidth(
            visualText = visualText,
            fullWidth = width,
            textHeight = bounds.bottom - bounds.top
        )
        if (right - left > maxWidth) {
            val compactBounds = anchorCompactTextBounds(
                bounds = bounds,
                rawLeft = rawLeft,
                rawRight = rawRight,
                start = start,
                end = end,
                textLength = textLength,
                maxWidth = maxWidth
            )
            left = compactBounds.first
            right = compactBounds.second
        }

        return expandOcrCandidateBounds(
            bounds = BoundsRect(
                left = left,
                top = bounds.top,
                right = right,
                bottom = bounds.bottom
            ),
            clipBounds = clipBounds
        )
    }

    private fun expandOcrCandidateBounds(
        bounds: BoundsRect,
        clipBounds: BoundsRect
    ): BoundsRect? {
        val height = bounds.bottom - bounds.top
        val horizontalPadding = max(
            OCR_TEXT_HORIZONTAL_PADDING_PX,
            (height * OCR_TEXT_HORIZONTAL_PADDING_HEIGHT_RATIO).roundToInt()
        )
        val left = max(clipBounds.left, bounds.left - horizontalPadding)
        val right = min(clipBounds.right, bounds.right + horizontalPadding)
        if (right - left < MIN_TEXT_WIDTH_PX) return null

        return BoundsRect(
            left = left,
            top = bounds.top,
            right = right,
            bottom = bounds.bottom
        )
    }

    private fun estimateCompactTextWidth(
        visualText: String,
        fullWidth: Int,
        textHeight: Int
    ): Int {
        val visibleText = visualText.ifBlank { "***" }
        val codePointLength = visibleText.codePointCount(0, visibleText.length).coerceAtLeast(1)
        val hasKorean = visibleText.any { it.code in 0xAC00..0xD7A3 }
        val charWidth = if (hasKorean) {
            max(KOREAN_TEXT_CHAR_WIDTH_PX, (textHeight * KOREAN_TEXT_HEIGHT_WIDTH_RATIO).roundToInt())
        } else {
            max(LATIN_TEXT_CHAR_WIDTH_PX, (textHeight * LATIN_TEXT_HEIGHT_WIDTH_RATIO).roundToInt())
        }
        val estimatedWidth = codePointLength * charWidth + COMPACT_TEXT_HORIZONTAL_PADDING_PX * 2
        val compactCap = max(
            if (hasKorean) MAX_COMPACT_KOREAN_TEXT_WIDTH_PX else MAX_COMPACT_LATIN_TEXT_WIDTH_PX,
            estimatedWidth
        )

        return minOf(
            fullWidth,
            maxOf(MIN_TEXT_WIDTH_PX, minOf(estimatedWidth, compactCap))
        )
    }

    private fun anchorCompactTextBounds(
        bounds: BoundsRect,
        rawLeft: Int,
        rawRight: Int,
        start: Int,
        end: Int,
        textLength: Int,
        maxWidth: Int
    ): Pair<Int, Int> {
        var left: Int
        var right: Int

        when {
            start <= 0 -> {
                left = (rawLeft - COMPACT_TEXT_HORIZONTAL_PADDING_PX).coerceAtLeast(bounds.left)
                right = left + maxWidth
            }
            end >= textLength -> {
                right = (rawRight + COMPACT_TEXT_HORIZONTAL_PADDING_PX).coerceAtMost(bounds.right)
                left = right - maxWidth
            }
            else -> {
                val center = (rawLeft + rawRight) / 2
                left = center - maxWidth / 2
                right = left + maxWidth
            }
        }

        if (right > bounds.right) {
            left -= right - bounds.right
            right = bounds.right
        }
        if (left < bounds.left) {
            right += bounds.left - left
            left = bounds.left
        }

        return left.coerceAtLeast(bounds.left) to right.coerceAtMost(bounds.right)
    }

    private fun translateBounds(
        box: Rect,
        roiBounds: BoundsRect
    ): BoundsRect? {
        val left = roiBounds.left + box.left
        val top = roiBounds.top + box.top
        val right = roiBounds.left + box.right
        val bottom = roiBounds.top + box.bottom

        if (right - left < MIN_TEXT_WIDTH_PX || bottom - top < MIN_TEXT_HEIGHT_PX) {
            return null
        }

        return BoundsRect(
            left = max(roiBounds.left, left),
            top = max(roiBounds.top, top),
            right = min(roiBounds.right, right),
            bottom = min(roiBounds.bottom, bottom)
        )
    }

    private fun deduplicate(items: List<ParsedComment>): List<ParsedComment> {
        return items
            .distinctBy { item ->
                val bounds = item.boundsInScreen
                val textKey = item.commentText.lowercase()
                val roundedLeft = bounds.left / 8
                val roundedTop = bounds.top / 8
                "$textKey|$roundedLeft|$roundedTop"
            }
            .sortedWith(compareBy<ParsedComment> { it.boundsInScreen.top }.thenBy { it.boundsInScreen.left })
            .take(MAX_OCR_TEXT_CANDIDATES)
    }

    companion object {
        private const val TAG = "VisualTextOcrProcessor"
        private const val MAX_ROIS_PER_PASS = 6
        private const val MAX_OCR_TEXT_CANDIDATES = 24
        private const val WARM_UP_BITMAP_SIZE_PX = 16
        private const val MIN_CROP_WIDTH_PX = 80
        private const val MIN_CROP_HEIGHT_PX = 40
        private const val MIN_TEXT_WIDTH_PX = 20
        private const val MIN_TEXT_HEIGHT_PX = 12
        private const val KOREAN_TEXT_CHAR_WIDTH_PX = 28
        private const val LATIN_TEXT_CHAR_WIDTH_PX = 14
        private const val KOREAN_TEXT_HEIGHT_WIDTH_RATIO = 0.72f
        private const val LATIN_TEXT_HEIGHT_WIDTH_RATIO = 0.58f
        private const val COMPACT_TEXT_HORIZONTAL_PADDING_PX = 4
        private const val OCR_TEXT_HORIZONTAL_PADDING_PX = 8
        private const val OCR_TEXT_HORIZONTAL_PADDING_HEIGHT_RATIO = 0.18f
        private const val OCR_CONTRAST_BLACK_POINT = 36
        private const val OCR_CONTRAST_WHITE_POINT = 210
        private const val MAX_COMPACT_KOREAN_TEXT_WIDTH_PX = 112
        private const val MAX_COMPACT_LATIN_TEXT_WIDTH_PX = 84
        private const val MAX_LINE_FALLBACK_CODEPOINTS = 8
        private const val MAX_LINE_FALLBACK_WIDTH_PX = 180
        private const val MAX_LINE_FALLBACK_HEIGHT_PX = 72

    }
}

internal data class VisualTextOcrMetadata(
    val source: String,
    val roiBoundsInScreen: BoundsRect?,
    val visualText: String?
)

internal object VisualTextOcrMetadataCodec {
    private val supportedSources = setOf(
        "youtube-composite-card",
        "generic-visual-region",
        "youtube-visible-band",
        "youtube-semantic-card"
    )

    fun encode(
        source: String,
        roiBoundsInScreen: BoundsRect,
        visualText: String
    ): String {
        val boundsToken = listOf(
            roiBoundsInScreen.left,
            roiBoundsInScreen.top,
            roiBoundsInScreen.right,
            roiBoundsInScreen.bottom
        ).joinToString(",")

        return "ocr:$source:$boundsToken:$visualText"
    }

    fun decode(authorId: String?): VisualTextOcrMetadata? {
        val value = authorId ?: return null
        if (!value.startsWith("ocr:")) return null

        val payload = value.removePrefix("ocr:")
        val source = payload.substringBefore(":")
        if (source !in supportedSources) return null

        val rest = payload.substringAfter(":", missingDelimiterValue = "")
        val boundsToken = rest.substringBefore(":", missingDelimiterValue = "")
        val bounds = parseBounds(boundsToken)
        val visualText = if (bounds == null) {
            rest
        } else {
            rest.substringAfter(":", missingDelimiterValue = "")
        }.takeIf { it.isNotBlank() }

        return VisualTextOcrMetadata(
            source = source,
            roiBoundsInScreen = bounds,
            visualText = visualText
        )
    }

    private fun parseBounds(value: String): BoundsRect? {
        val parts = value.split(",")
        if (parts.size != 4) return null

        val numbers = parts.map { part -> part.toIntOrNull() }
        if (numbers.any { it == null }) return null

        val left = numbers[0] ?: return null
        val top = numbers[1] ?: return null
        val right = numbers[2] ?: return null
        val bottom = numbers[3] ?: return null
        if (right <= left || bottom <= top) return null

        return BoundsRect(
            left = left,
            top = top,
            right = right,
            bottom = bottom
        )
    }
}

internal object VisualTextGeometryPolicy {
    private const val TOP_HERO_MEDIA_MIN_HEIGHT_PX = 180
    private const val TOP_HERO_MEDIA_MIN_WIDTH_RATIO = 0.48f
    private const val VISIBLE_BAND_BOUNDS_SLOP_PX = 4

    fun isTopHeroYoutubeComposite(authorId: String?, screenWidth: Int): Boolean {
        if (screenWidth <= 0) return false

        val metadata = VisualTextOcrMetadataCodec.decode(authorId) ?: return false
        val bounds = metadata.roiBoundsInScreen ?: return false
        if (metadata.source != "youtube-composite-card") return false

        val width = bounds.right - bounds.left
        val height = bounds.bottom - bounds.top
        return height >= TOP_HERO_MEDIA_MIN_HEIGHT_PX &&
            width >= (screenWidth * TOP_HERO_MEDIA_MIN_WIDTH_RATIO).toInt()
    }

    fun isTrustedVisibleBandOcr(
        authorId: String?,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ): Boolean {
        val metadata = VisualTextOcrMetadataCodec.decode(authorId) ?: return false
        if (metadata.source != "youtube-visible-band") return false

        val roiBounds = metadata.roiBoundsInScreen ?: return false
        return left >= roiBounds.left - VISIBLE_BAND_BOUNDS_SLOP_PX &&
            top >= roiBounds.top - VISIBLE_BAND_BOUNDS_SLOP_PX &&
            right <= roiBounds.right + VISIBLE_BAND_BOUNDS_SLOP_PX &&
            bottom <= roiBounds.bottom + VISIBLE_BAND_BOUNDS_SLOP_PX
    }
}

internal object VisualTextOcrCandidateFilter {
    data class CandidateRange(
        val analysisText: String,
        val visualText: String,
        val start: Int,
        val end: Int
    )

    private data class NormalizedText(
        val value: String,
        val indexToOriginal: List<Int>
    )

    private val likelyHarmfulPattern = Regex(
        pattern = listOf(
            "시\\s*발",
            "씨\\s*발",
            "c\\s*발",
            "ㅅ\\s*ㅂ",
            "ㅆ\\s*ㅂ",
            "병\\s*신",
            "ㅂ\\s*ㅅ",
            "개\\s*새\\s*끼",
            "개\\s*새",
            "새\\s*끼",
            "존\\s*나",
            "ㅈ\\s*ㄴ",
            "지\\s*랄",
            "ㅈ\\s*ㄹ",
            "좆",
            "ㅈ\\s*같",
            "미\\s*친",
            "ㅁ\\s*ㅊ",
            "뒤\\s*져",
            "뒈\\s*져",
            "죽\\s*어",
            "닥\\s*쳐",
            "ㄷ\\s*ㅊ",
            "꺼\\s*져",
            "ㄲ\\s*ㅈ",
            "엿\\s*먹",
            "t\\s*[i1l]\\s*q\\s*k\\s*[fq]?",
            "t\\s*[i1l]\\s*q\\s*k\\s*q",
            "t\\s*[i1l]\\s*[a4gq]\\s*k\\s*[fq]?",
            "t\\s*[i1l]\\s*k\\s*f",
            "t\\s*[i1l]\\s*[o0]\\s*k\\s*[tf]",
            "t\\s*[i1l]\\s*[o0]\\s*[i1l]\\s*k\\s*f",
            "[i1l]{2}\\s*[a4o0]\\s*k\\s*t",
            "11\\s*k?t",
            "s\\s*s?\\s*i\\s*b\\s*a\\s*l",
            "qudtls",
            "wlfkf",
            "whssk",
            "alcls",
            "rjwu",
            "fuck",
            "shit",
            "bitch"
        ).joinToString("|"),
        options = setOf(RegexOption.IGNORE_CASE)
    )

    fun shouldAnalyze(text: String): Boolean {
        return findAnalysisRanges(text).isNotEmpty()
    }

    fun isUsefulOcrLineText(text: String): Boolean {
        if (text.length !in MIN_ANALYSIS_TEXT_LENGTH..MAX_OCR_LINE_TEXT_LENGTH) return false
        val lower = text.lowercase()
        if (lower.startsWith("http://") || lower.startsWith("https://")) return false
        if (lower in COMMON_UI_LABELS) return false
        if (Regex("""^[\d.,]+\s*[kmb]?$""", RegexOption.IGNORE_CASE).matches(text)) return false
        return text.any { it.isLetterOrDigit() || it.code in 0xAC00..0xD7A3 }
    }

    fun findAnalysisRanges(text: String): List<CandidateRange> {
        val normalized = normalizeForMatching(text)
        if (normalized.value.trim().length < MIN_ANALYSIS_TEXT_LENGTH) return emptyList()

        // OCR runs on visual-only regions such as thumbnails. This is only a backend
        // payload filter; the backend still makes the final offensive/safe decision.
        return likelyHarmfulPattern.findAll(normalized.value)
            .mapNotNull { match ->
                val normalizedStart = match.range.first
                val normalizedEndExclusive = match.range.last + 1
                val originalStart = normalized.indexToOriginal.getOrNull(normalizedStart) ?: return@mapNotNull null
                val originalEndExclusive =
                    (normalized.indexToOriginal.getOrNull(normalizedEndExclusive - 1) ?: return@mapNotNull null) + 1
                val trimmed = trimOriginalRange(text, originalStart, originalEndExclusive)
                if (trimmed.end <= trimmed.start) return@mapNotNull null

                val visualText = text.substring(trimmed.start, trimmed.end)
                CandidateRange(
                    analysisText = canonicalizeVisualHit(visualText),
                    visualText = visualText,
                    start = trimmed.start,
                    end = trimmed.end
                )
            }
            .distinctBy { "${it.start}|${it.end}|${it.analysisText.lowercase()}" }
            .toList()
    }

    fun looksDebugRelevant(text: String): Boolean {
        val normalized = text.lowercase()
        return normalized.contains("t") &&
            (normalized.contains("q") || normalized.contains("l") || normalized.contains("i")) &&
            (normalized.contains("k") || normalized.contains("f"))
    }

    private const val MIN_ANALYSIS_TEXT_LENGTH = 2
    private const val MAX_OCR_LINE_TEXT_LENGTH = 260

    private val COMMON_UI_LABELS = setOf(
        "all",
        "shorts",
        "videos",
        "watched",
        "unwatched",
        "home",
        "subscriptions",
        "share",
        "save",
        "download",
        "전체",
        "동영상",
        "홈",
        "구독"
    )

    private fun normalizeForMatching(text: String): NormalizedText {
        val value = StringBuilder(text.length)
        val indexToOriginal = mutableListOf<Int>()

        text.forEachIndexed { index, char ->
            value.append(
                when (char) {
                    '|', '!' -> 'l'
                    else -> char
                }
            )
            indexToOriginal += index
        }

        return NormalizedText(value = value.toString(), indexToOriginal = indexToOriginal)
    }

    private fun canonicalizeVisualHit(text: String): String {
        val rawCompact = text
            .lowercase()
            .replace(Regex("""[\s"'`.,!?_\-]+"""), "")
        val compact = rawCompact
            .map { char ->
                when (char) {
                    '|', '!', '1', 'i' -> 'l'
                    'a', 'g', 'o', '0' -> 'q'
                    else -> char
                }
            }
            .joinToString("")

        return when {
            rawCompact.matches(Regex("""ss?ibal""")) -> "ssibal"
            compact.matches(Regex("""(?:t|l){1,2}l?qkf?""")) -> "tlqkf"
            compact.matches(Regex("""tlkf""")) -> "tlqkf"
            compact.matches(Regex("""tlqk[fq]?""")) -> "tlqkf"
            compact.matches(Regex("""tlqkt""")) -> "tlqkf"
            compact.matches(Regex("""tlqlkf""")) -> "tlqkf"
            compact.matches(Regex("""tlqkq""")) -> "tlqkf"
            compact.matches(Regex("""tlqkf""")) -> "tlqkf"
            compact.matches(Regex("""tlq?kf""")) -> "tlqkf"
            compact.matches(Regex("""tlqkf.*""")) -> "tlqkf"
            compact.matches(Regex("""llqk[ft]""")) -> "tlqkf"
            compact.matches(Regex("""llkt""")) -> "tlqkf"
            compact.matches(Regex("""ss?ibal""")) -> "ssibal"
            compact == "sibal" -> "ssibal"
            compact == "qudtls" -> "qudtls"
            rawCompact == "wlfkf" -> "지랄"
            rawCompact == "whssk" -> "존나"
            rawCompact == "alcls" -> "미친"
            rawCompact == "rjwu" -> "꺼져"
            else -> text
        }
    }

    private fun trimOriginalRange(text: String, start: Int, end: Int): IntRangeExclusive {
        var nextStart = start.coerceIn(0, text.length)
        var nextEnd = end.coerceIn(nextStart, text.length)

        while (nextStart < nextEnd && text[nextStart].isWhitespace()) {
            nextStart += 1
        }
        while (nextEnd > nextStart && text[nextEnd - 1].isWhitespace()) {
            nextEnd -= 1
        }

        return IntRangeExclusive(nextStart, nextEnd)
    }

    private data class IntRangeExclusive(
        val start: Int,
        val end: Int
    )
}
