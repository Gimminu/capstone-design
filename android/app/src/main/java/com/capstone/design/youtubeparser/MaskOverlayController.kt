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
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.math.max
import kotlin.math.min

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

        return response.results
            .asSequence()
            .filter { it.isOffensive && it.evidenceSpans.isNotEmpty() }
            .mapNotNull { toSpec(it.boundsInScreen, screenWidth, screenHeight) }
            .distinctBy { "${it.left}|${it.top}|${it.width}|${it.height}" }
            .take(MAX_MASK_COUNT)
            .toList()
    }

    fun signature(specs: List<MaskOverlaySpec>): String {
        return specs.joinToString("|") { "${it.left},${it.top},${it.width},${it.height},${it.label}" }
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
            label = maskLabelFor(width, height)
        )
    }

    private fun maskLabelFor(width: Int, height: Int): String {
        return if (width <= 180 || height <= 90) "•••" else "민감 표현"
    }
}

class MaskOverlayController(
    private val service: AccessibilityService
) {
    companion object {
        private const val TAG = "MaskOverlayController"
    }

    private val windowManager = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var rootView: FrameLayout? = null
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
        if (signature == lastSignature && rootView?.isAttachedToWindow == true) {
            return
        }

        val root = ensureRootView()
        root.removeAllViews()
        specs.forEach { spec ->
            root.addView(createMaskView(spec), createMaskLayoutParams(spec))
        }
        Log.d(TAG, "render maskCount=${specs.size} signature=$signature")
        lastSignature = signature
    }

    fun clear() {
        val root = rootView ?: return
        try {
            windowManager.removeView(root)
        } catch (_: IllegalArgumentException) {
            // The view may already be detached during service shutdown.
        } finally {
            rootView = null
            lastSignature = ""
        }
    }

    private fun ensureRootView(): FrameLayout {
        rootView?.let { return it }

        val root = FrameLayout(service).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setBackgroundColor(Color.TRANSPARENT)
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        windowManager.addView(root, params)
        rootView = root
        return root
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

    private fun createMaskLayoutParams(spec: MaskOverlaySpec): FrameLayout.LayoutParams {
        return FrameLayout.LayoutParams(spec.width, spec.height).apply {
            leftMargin = spec.left
            topMargin = spec.top
        }
    }
}
