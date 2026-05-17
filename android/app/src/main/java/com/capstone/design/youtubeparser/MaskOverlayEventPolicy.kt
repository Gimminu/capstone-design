package com.capstone.design.youtubeparser

import android.view.accessibility.AccessibilityEvent

internal data class MaskOverlayScrollDelta(
    val deltaX: Int,
    val deltaY: Int,
    val source: MaskOverlayScrollDeltaSource
)

internal enum class MaskOverlayScrollDeltaSource {
    EXPLICIT_DELTA,
    ABSOLUTE_POSITION
}

internal object MaskOverlayEventPolicy {
    private const val TAKE_SCREENSHOT_INTERVAL_TOO_SHORT_ERROR_CODE = 3
    private const val MIN_SCREENSHOT_REQUEST_INTERVAL_MS = 380L
    private const val SCREENSHOT_RETRY_GRACE_MS = 64L

    fun resolveScrollTranslationDelta(
        eventType: Int,
        hasActiveMasks: Boolean,
        explicitScrollDeltaX: Int,
        explicitScrollDeltaY: Int,
        absoluteScrollX: Int,
        absoluteScrollY: Int,
        lastAbsoluteScrollX: Int?,
        lastAbsoluteScrollY: Int?
    ): MaskOverlayScrollDelta? {
        if (eventType != AccessibilityEvent.TYPE_VIEW_SCROLLED || !hasActiveMasks) {
            return null
        }

        val explicitXAvailable = explicitScrollDeltaX != 0
        val explicitYAvailable = explicitScrollDeltaY != 0
        val deltaX = if (explicitXAvailable) {
            -explicitScrollDeltaX
        } else {
            absoluteOverlayDelta(absoluteScrollX, lastAbsoluteScrollX)
        }
        val deltaY = if (explicitYAvailable) {
            -explicitScrollDeltaY
        } else {
            absoluteOverlayDelta(absoluteScrollY, lastAbsoluteScrollY)
        }

        if (deltaX == 0 && deltaY == 0) return null
        val source = if (explicitXAvailable || explicitYAvailable) {
            MaskOverlayScrollDeltaSource.EXPLICIT_DELTA
        } else {
            MaskOverlayScrollDeltaSource.ABSOLUTE_POSITION
        }
        return MaskOverlayScrollDelta(deltaX = deltaX, deltaY = deltaY, source = source)
    }

    fun knownAbsoluteScroll(value: Int): Int? {
        return value.takeIf { it >= 0 }
    }

    fun shouldPreserveExistingOnEmptyPlan(
        hasActiveMasks: Boolean,
        snapshotOverlayRevision: Long,
        currentOverlayRevision: Long,
        isScrollStabilizing: Boolean,
        hasProvisionalMasks: Boolean = false,
        isProvisionalPlan: Boolean = false,
        allowProvisionalMasksOnEmpty: Boolean = false
    ): Boolean {
        return hasActiveMasks &&
            snapshotOverlayRevision == currentOverlayRevision &&
            !isScrollStabilizing &&
            (!hasProvisionalMasks || allowProvisionalMasksOnEmpty) &&
            !isProvisionalPlan
    }

    fun shouldRetryAfterStaleOverlayResult(
        analysisOk: Boolean,
        snapshotOverlayRevision: Long,
        currentOverlayRevision: Long
    ): Boolean {
        return analysisOk && snapshotOverlayRevision != currentOverlayRevision
    }

    fun shouldPreserveOnScrollContentChange(
        eventType: Int,
        hasActiveMasks: Boolean,
        isScrollStabilizing: Boolean,
        isLikelySelfContentChange: Boolean
    ): Boolean {
        return eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            hasActiveMasks &&
            isScrollStabilizing &&
            !isLikelySelfContentChange
    }

    fun shouldHideOnUnresolvedScrollDelta(
        eventType: Int,
        hasActiveMasks: Boolean,
        hasResolvedScrollDelta: Boolean
    ): Boolean {
        return eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED &&
            hasActiveMasks &&
            !hasResolvedScrollDelta
    }

    fun shouldDeferClearForVisualOnlyAnalysis(
        hasActiveMasks: Boolean,
        hasRenderableVisualRois: Boolean
    ): Boolean {
        return hasActiveMasks && hasRenderableVisualRois
    }

    fun shouldClearAfterVisualAnalysisMiss(
        hasActiveMasks: Boolean,
        hasRenderableVisualRois: Boolean,
        isOverlayStabilizing: Boolean,
        hasPreservedRecentVisualMiss: Boolean
    ): Boolean {
        return !(
            hasActiveMasks &&
                hasRenderableVisualRois &&
                (isOverlayStabilizing || !hasPreservedRecentVisualMiss)
        )
    }

    fun shouldClearAfterAnalysisFailure(
        hasActiveMasks: Boolean,
        hasRenderableVisualRois: Boolean,
        hasProvisionalMasks: Boolean,
        visualAnalysisInFlight: Boolean
    ): Boolean {
        return !(
            hasActiveMasks && (hasRenderableVisualRois || hasProvisionalMasks) ||
                visualAnalysisInFlight && hasRenderableVisualRois
            )
    }

    fun shouldRunVisualRefreshForDuplicateSnapshot(
        hasRenderableVisualRois: Boolean,
        visualAnalysisInFlight: Boolean,
        hasReusableVisualSupplement: Boolean
    ): Boolean {
        return hasRenderableVisualRois &&
            !visualAnalysisInFlight &&
            !hasReusableVisualSupplement
    }

    fun isLikelySelfContentChange(
        eventType: Int,
        hasActiveMasks: Boolean,
        overlayUpdatedRecently: Boolean
    ): Boolean {
        return eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            hasActiveMasks &&
            overlayUpdatedRecently
    }

    fun screenshotRequestThrottleDelay(elapsedSinceLastRequestMs: Long): Long {
        if (elapsedSinceLastRequestMs < 0L) return MIN_SCREENSHOT_REQUEST_INTERVAL_MS
        return (MIN_SCREENSHOT_REQUEST_INTERVAL_MS - elapsedSinceLastRequestMs).coerceAtLeast(0L)
    }

    fun screenshotFailureRetryDelay(
        errorCode: Int,
        elapsedSinceLastRequestMs: Long
    ): Long? {
        if (errorCode != TAKE_SCREENSHOT_INTERVAL_TOO_SHORT_ERROR_CODE) return null
        return screenshotRequestThrottleDelay(elapsedSinceLastRequestMs) + SCREENSHOT_RETRY_GRACE_MS
    }

    private fun absoluteOverlayDelta(currentAbsoluteScroll: Int, lastAbsoluteScroll: Int?): Int {
        if (currentAbsoluteScroll < 0 || lastAbsoluteScroll == null) return 0
        return lastAbsoluteScroll - currentAbsoluteScroll
    }
}
