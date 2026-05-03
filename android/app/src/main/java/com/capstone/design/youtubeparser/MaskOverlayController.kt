package com.capstone.design.youtubeparser

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class MaskOverlaySpec(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
    val label: String
)

object AndroidMaskOverlayPlanner {
    private const val MIN_WIDTH_PX = 24
    private const val MIN_HEIGHT_PX = 16
    private const val MIN_SPAN_MASK_WIDTH_PX = 30
    private const val SPAN_HORIZONTAL_PADDING_PX = 8
    private const val MAX_SPAN_MASK_HEIGHT_PX = 48
    private const val MAX_MASK_COUNT = 24
    private const val MAX_SCREEN_WIDTH_RATIO = 0.88f
    private const val MAX_SCREEN_HEIGHT_RATIO = 0.22f

    fun buildSpecs(
        response: AndroidAnalysisResponse?,
        screenWidth: Int,
        screenHeight: Int
    ): List<MaskOverlaySpec> {
        if (response == null || screenWidth <= 0 || screenHeight <= 0) {
            return emptyList()
        }

        val rawSpecs = response.results
            .asSequence()
            .filter { it.isOffensive && it.evidenceSpans.isNotEmpty() }
            .flatMap { toSpecs(it, screenWidth, screenHeight).asSequence() }
            .toList()

        return suppressOverlappingSpecs(rawSpecs)
            .take(MAX_MASK_COUNT)
    }

    fun signature(specs: List<MaskOverlaySpec>): String {
        return specs.joinToString("|") { "${it.left},${it.top},${it.width},${it.height},${it.label}" }
    }

    private fun toSpecs(
        item: AndroidAnalysisResultItem,
        screenWidth: Int,
        screenHeight: Int
    ): List<MaskOverlaySpec> {
        val fullSpec = toSpec(item.boundsInScreen, screenWidth, screenHeight) ?: return emptyList()
        val originalLength = item.original.length
        if (originalLength <= 0) return listOf(fullSpec)

        val spanSpecs = item.evidenceSpans.mapNotNull { span ->
            toSpanSpec(
                fullSpec = fullSpec,
                span = span,
                originalLength = originalLength
            )
        }

        return spanSpecs.ifEmpty { listOf(fullSpec) }
    }

    private fun toSpec(bounds: BoundsRect, screenWidth: Int, screenHeight: Int): MaskOverlaySpec? {
        val left = max(0, min(bounds.left, screenWidth))
        val top = max(0, min(bounds.top, screenHeight))
        val right = max(left, min(bounds.right, screenWidth))
        val bottom = max(top, min(bounds.bottom, screenHeight))
        val width = right - left
        val height = bottom - top

        if (width < MIN_WIDTH_PX || height < MIN_HEIGHT_PX) {
            return null
        }
        if (
            width >= (screenWidth * MAX_SCREEN_WIDTH_RATIO).toInt() &&
            height >= (screenHeight * MAX_SCREEN_HEIGHT_RATIO).toInt()
        ) {
            return null
        }

        return MaskOverlaySpec(
            left = left,
            top = top,
            width = width,
            height = height,
            label = MASK_LABEL
        )
    }

    private fun toSpanSpec(
        fullSpec: MaskOverlaySpec,
        span: EvidenceSpan,
        originalLength: Int
    ): MaskOverlaySpec? {
        val start = span.start.coerceIn(0, originalLength)
        val end = span.end.coerceIn(start, originalLength)
        if (end <= start) return null

        val startRatio = start.toFloat() / originalLength.toFloat()
        val endRatio = end.toFloat() / originalLength.toFloat()
        val rawLeft = fullSpec.left + (fullSpec.width * startRatio).roundToInt()
        val rawRight = fullSpec.left + (fullSpec.width * endRatio).roundToInt()

        val minWidth = minOf(
            fullSpec.width,
            maxOf(
                MIN_SPAN_MASK_WIDTH_PX,
                span.text.ifBlank { MASK_LABEL }.length * 18
            )
        )
        val center = (rawLeft + rawRight) / 2
        var left = rawLeft - SPAN_HORIZONTAL_PADDING_PX
        var right = rawRight + SPAN_HORIZONTAL_PADDING_PX

        if (right - left < minWidth) {
            left = center - minWidth / 2
            right = left + minWidth
        }

        if (left < fullSpec.left) {
            right += fullSpec.left - left
            left = fullSpec.left
        }
        if (right > fullSpec.left + fullSpec.width) {
            left -= right - (fullSpec.left + fullSpec.width)
            right = fullSpec.left + fullSpec.width
        }
        left = left.coerceAtLeast(fullSpec.left)
        right = right.coerceAtMost(fullSpec.left + fullSpec.width)

        val width = right - left
        if (width < MIN_WIDTH_PX) return null

        val height = minOf(fullSpec.height, MAX_SPAN_MASK_HEIGHT_PX).coerceAtLeast(MIN_HEIGHT_PX)
        val top = fullSpec.top + ((fullSpec.height - height) / 2).coerceAtLeast(0)

        return MaskOverlaySpec(
            left = left,
            top = top,
            width = width,
            height = height,
            label = MASK_LABEL
        )
    }

    private fun suppressOverlappingSpecs(specs: List<MaskOverlaySpec>): List<MaskOverlaySpec> {
        val kept = mutableListOf<MaskOverlaySpec>()
        specs
            .distinctBy { "${it.left}|${it.top}|${it.width}|${it.height}" }
            .sortedWith(compareBy<MaskOverlaySpec> { it.top }.thenBy { it.left }.thenBy { it.width * it.height })
            .forEach { spec ->
                val overlapsExisting = kept.any { existing ->
                    overlapRatio(spec, existing) >= 0.65f
                }
                if (!overlapsExisting) {
                    kept += spec
                }
            }
        return kept
    }

    private fun overlapRatio(left: MaskOverlaySpec, right: MaskOverlaySpec): Float {
        val overlapLeft = max(left.left, right.left)
        val overlapTop = max(left.top, right.top)
        val overlapRight = min(left.left + left.width, right.left + right.width)
        val overlapBottom = min(left.top + left.height, right.top + right.height)
        val overlapWidth = overlapRight - overlapLeft
        val overlapHeight = overlapBottom - overlapTop
        if (overlapWidth <= 0 || overlapHeight <= 0) return 0f

        val overlapArea = overlapWidth * overlapHeight
        val smallerArea = min(left.width * left.height, right.width * right.height).coerceAtLeast(1)
        return overlapArea.toFloat() / smallerArea.toFloat()
    }

    private const val MASK_LABEL = "***"
}

class MaskOverlayController(
    private val service: AccessibilityService
) {
    companion object {
        private const val TAG = "MaskOverlayController"
    }

    private val windowManager = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val activeViews = mutableListOf<View>()
    private var lastSignature: String = ""

    fun render(response: AndroidAnalysisResponse?) {
        val metrics = service.resources.displayMetrics
        val specs = AndroidMaskOverlayPlanner.buildSpecs(
            response = response,
            screenWidth = metrics.widthPixels,
            screenHeight = metrics.heightPixels
        )

        if (specs.isEmpty()) {
            clear()
            return
        }

        val signature = AndroidMaskOverlayPlanner.signature(specs)
        if (signature == lastSignature && activeViews.isNotEmpty()) {
            return
        }

        clearViews()

        val nextViews = mutableListOf<View>()
        try {
            specs.forEach { spec ->
                val maskView = createMaskView(spec)
                windowManager.addView(maskView, createMaskLayoutParams(spec))
                nextViews += maskView
            }

            activeViews += nextViews
            Log.d(TAG, "render maskCount=${specs.size} signature=$signature")
            lastSignature = signature
        } catch (error: RuntimeException) {
            nextViews.forEach { view ->
                try {
                    windowManager.removeView(view)
                } catch (_: IllegalArgumentException) {
                    // The view may already be detached after a fast window transition.
                }
            }
            activeViews.clear()
            lastSignature = ""
            Log.w(TAG, "render mask overlay failed", error)
        }
    }

    fun clear() {
        clearViews()
        lastSignature = ""
    }

    private fun clearViews() {
        if (activeViews.isEmpty()) return

        val viewsToRemove = activeViews.toList()
        activeViews.clear()

        viewsToRemove.forEach { view ->
            try {
                windowManager.removeView(view)
            } catch (_: IllegalArgumentException) {
                // The view may already be detached during service shutdown.
            }
        }
    }

    private fun createMaskView(spec: MaskOverlaySpec): TextView {
        return TextView(service).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            text = spec.label
            gravity = Gravity.CENTER
            includeFontPadding = false
            setTextColor(Color.WHITE)
            textSize = if (spec.height <= 90) 13f else 14f
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                cornerRadius = 6f * service.resources.displayMetrics.density
                setColor(Color.rgb(18, 18, 18))
            }
        }
    }

    private fun createMaskLayoutParams(spec: MaskOverlaySpec): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            spec.width,
            spec.height,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = spec.left
            y = spec.top
        }
    }
}
