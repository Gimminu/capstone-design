package com.capstone.design.youtubeparser

import android.view.accessibility.AccessibilityEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MaskOverlayEventPolicyTest {

    @Test
    fun resolveScrollTranslationDelta_prefersExplicitScrollDeltaWhenAvailable() {
        val delta = MaskOverlayEventPolicy.resolveScrollTranslationDelta(
                eventType = AccessibilityEvent.TYPE_VIEW_SCROLLED,
                hasActiveMasks = true,
            explicitScrollDeltaX = 0,
            explicitScrollDeltaY = 12,
            absoluteScrollX = 0,
            absoluteScrollY = 400,
            lastAbsoluteScrollX = 0,
            lastAbsoluteScrollY = 200
        )

        assertTrue(delta != null)
        assertEquals(MaskOverlayScrollDeltaSource.EXPLICIT_DELTA, delta?.source)
        assertEquals(-12, delta?.deltaY)
    }

    @Test
    fun resolveScrollTranslationDelta_usesAbsoluteScrollPositionFallback() {
        val delta = MaskOverlayEventPolicy.resolveScrollTranslationDelta(
            eventType = AccessibilityEvent.TYPE_VIEW_SCROLLED,
            hasActiveMasks = true,
            explicitScrollDeltaX = 0,
            explicitScrollDeltaY = 0,
            absoluteScrollX = 0,
            absoluteScrollY = 460,
            lastAbsoluteScrollX = 0,
            lastAbsoluteScrollY = 400
        )

        assertTrue(delta != null)
        assertEquals(MaskOverlayScrollDeltaSource.ABSOLUTE_POSITION, delta?.source)
        assertEquals(-60, delta?.deltaY)
    }

    @Test
    fun resolveScrollTranslationDelta_rejectsUnknownOrUnsafeInputs() {
        assertFalse(
            MaskOverlayEventPolicy.resolveScrollTranslationDelta(
                eventType = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                hasActiveMasks = true,
                explicitScrollDeltaX = 0,
                explicitScrollDeltaY = 12,
                absoluteScrollX = 0,
                absoluteScrollY = 460,
                lastAbsoluteScrollX = 0,
                lastAbsoluteScrollY = 400
            ) != null
        )
        assertFalse(
            MaskOverlayEventPolicy.resolveScrollTranslationDelta(
                eventType = AccessibilityEvent.TYPE_VIEW_SCROLLED,
                hasActiveMasks = false,
                explicitScrollDeltaX = 0,
                explicitScrollDeltaY = 12,
                absoluteScrollX = 0,
                absoluteScrollY = 460,
                lastAbsoluteScrollX = 0,
                lastAbsoluteScrollY = 400
            ) != null
        )
        assertFalse(
            MaskOverlayEventPolicy.resolveScrollTranslationDelta(
                eventType = AccessibilityEvent.TYPE_VIEW_SCROLLED,
                hasActiveMasks = true,
                explicitScrollDeltaX = 0,
                explicitScrollDeltaY = 0,
                absoluteScrollX = -1,
                absoluteScrollY = -1,
                lastAbsoluteScrollX = null,
                lastAbsoluteScrollY = null
            ) != null
        )
    }

    @Test
    fun shouldPreserveExistingOnEmptyPlan_keepsMasksOnlyForSameStableViewport() {
        assertTrue(
            MaskOverlayEventPolicy.shouldPreserveExistingOnEmptyPlan(
                hasActiveMasks = true,
                snapshotOverlayRevision = 7L,
                currentOverlayRevision = 7L,
                isScrollStabilizing = false
            )
        )
        assertFalse(
            MaskOverlayEventPolicy.shouldPreserveExistingOnEmptyPlan(
                hasActiveMasks = true,
                snapshotOverlayRevision = 7L,
                currentOverlayRevision = 8L,
                isScrollStabilizing = false
            )
        )
        assertFalse(
            MaskOverlayEventPolicy.shouldPreserveExistingOnEmptyPlan(
                hasActiveMasks = true,
                snapshotOverlayRevision = 7L,
                currentOverlayRevision = 7L,
                isScrollStabilizing = true
            )
        )
        assertFalse(
            MaskOverlayEventPolicy.shouldPreserveExistingOnEmptyPlan(
                hasActiveMasks = false,
                snapshotOverlayRevision = 7L,
                currentOverlayRevision = 7L,
                isScrollStabilizing = false
            )
        )
    }

    @Test
    fun shouldPreserveExistingOnEmptyPlan_rejectsProvisionalVisualMasks() {
        assertFalse(
            MaskOverlayEventPolicy.shouldPreserveExistingOnEmptyPlan(
                hasActiveMasks = true,
                snapshotOverlayRevision = 7L,
                currentOverlayRevision = 7L,
                isScrollStabilizing = false,
                hasProvisionalMasks = true,
                isProvisionalPlan = false
            )
        )
        assertFalse(
            MaskOverlayEventPolicy.shouldPreserveExistingOnEmptyPlan(
                hasActiveMasks = true,
                snapshotOverlayRevision = 7L,
                currentOverlayRevision = 7L,
                isScrollStabilizing = false,
                hasProvisionalMasks = false,
                isProvisionalPlan = true
            )
        )
    }

    @Test
    fun shouldPreserveExistingOnEmptyPlan_canKeepExactAccessibilityProvisionalMasks() {
        assertTrue(
            MaskOverlayEventPolicy.shouldPreserveExistingOnEmptyPlan(
                hasActiveMasks = true,
                snapshotOverlayRevision = 7L,
                currentOverlayRevision = 7L,
                isScrollStabilizing = false,
                hasProvisionalMasks = true,
                isProvisionalPlan = false,
                allowProvisionalMasksOnEmpty = true
            )
        )
    }

    @Test
    fun shouldRetryAfterStaleOverlayResult_retriesOnlyForOkStaleAnalysis() {
        assertTrue(
            MaskOverlayEventPolicy.shouldRetryAfterStaleOverlayResult(
                analysisOk = true,
                snapshotOverlayRevision = 7L,
                currentOverlayRevision = 8L
            )
        )
        assertFalse(
            MaskOverlayEventPolicy.shouldRetryAfterStaleOverlayResult(
                analysisOk = true,
                snapshotOverlayRevision = 7L,
                currentOverlayRevision = 7L
            )
        )
        assertFalse(
            MaskOverlayEventPolicy.shouldRetryAfterStaleOverlayResult(
                analysisOk = false,
                snapshotOverlayRevision = 7L,
                currentOverlayRevision = 8L
            )
        )
    }

    @Test
    fun shouldPreserveOnScrollContentChange_keepsTranslatedMasksDuringLayoutBursts() {
        assertTrue(
            MaskOverlayEventPolicy.shouldPreserveOnScrollContentChange(
                eventType = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                hasActiveMasks = true,
                isScrollStabilizing = true,
                isLikelySelfContentChange = false
            )
        )
        assertFalse(
            MaskOverlayEventPolicy.shouldPreserveOnScrollContentChange(
                eventType = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                hasActiveMasks = true,
                isScrollStabilizing = false,
                isLikelySelfContentChange = false
            )
        )
        assertFalse(
            MaskOverlayEventPolicy.shouldPreserveOnScrollContentChange(
                eventType = AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
                hasActiveMasks = true,
                isScrollStabilizing = true,
                isLikelySelfContentChange = false
            )
        )
        assertFalse(
            MaskOverlayEventPolicy.shouldPreserveOnScrollContentChange(
                eventType = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                hasActiveMasks = true,
                isScrollStabilizing = true,
                isLikelySelfContentChange = true
            )
        )
    }

    @Test
    fun shouldHideOnUnresolvedScrollDelta_recapturesWhenScrollPositionIsUnknown() {
        assertTrue(
            MaskOverlayEventPolicy.shouldHideOnUnresolvedScrollDelta(
                eventType = AccessibilityEvent.TYPE_VIEW_SCROLLED,
                hasActiveMasks = true,
                hasResolvedScrollDelta = false
            )
        )
        assertFalse(
            MaskOverlayEventPolicy.shouldHideOnUnresolvedScrollDelta(
                eventType = AccessibilityEvent.TYPE_VIEW_SCROLLED,
                hasActiveMasks = true,
                hasResolvedScrollDelta = true
            )
        )
        assertFalse(
            MaskOverlayEventPolicy.shouldHideOnUnresolvedScrollDelta(
                eventType = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                hasActiveMasks = true,
                hasResolvedScrollDelta = false
            )
        )
        assertFalse(
            MaskOverlayEventPolicy.shouldHideOnUnresolvedScrollDelta(
                eventType = AccessibilityEvent.TYPE_VIEW_SCROLLED,
                hasActiveMasks = false,
                hasResolvedScrollDelta = false
            )
        )
    }

    @Test
    fun shouldDeferClearForVisualOnlyAnalysis_keepsMasksWhileVisualOcrCanReplaceThem() {
        assertTrue(
            MaskOverlayEventPolicy.shouldDeferClearForVisualOnlyAnalysis(
                hasActiveMasks = true,
                hasRenderableVisualRois = true
            )
        )
        assertFalse(
            MaskOverlayEventPolicy.shouldDeferClearForVisualOnlyAnalysis(
                hasActiveMasks = false,
                hasRenderableVisualRois = true
            )
        )
        assertFalse(
            MaskOverlayEventPolicy.shouldDeferClearForVisualOnlyAnalysis(
                hasActiveMasks = true,
                hasRenderableVisualRois = false
            )
        )
    }

    @Test
    fun shouldClearAfterVisualAnalysisMiss_preservesMasksDuringOverlayStabilization() {
        assertFalse(
            MaskOverlayEventPolicy.shouldClearAfterVisualAnalysisMiss(
                hasActiveMasks = true,
                hasRenderableVisualRois = true,
                isOverlayStabilizing = true,
                hasPreservedRecentVisualMiss = false
            )
        )
        assertTrue(
            MaskOverlayEventPolicy.shouldClearAfterVisualAnalysisMiss(
                hasActiveMasks = true,
                hasRenderableVisualRois = true,
                isOverlayStabilizing = false,
                hasPreservedRecentVisualMiss = true
            )
        )
        assertTrue(
            MaskOverlayEventPolicy.shouldClearAfterVisualAnalysisMiss(
                hasActiveMasks = false,
                hasRenderableVisualRois = true,
                isOverlayStabilizing = true,
                hasPreservedRecentVisualMiss = false
            )
        )
        assertTrue(
            MaskOverlayEventPolicy.shouldClearAfterVisualAnalysisMiss(
                hasActiveMasks = true,
                hasRenderableVisualRois = false,
                isOverlayStabilizing = true,
                hasPreservedRecentVisualMiss = false
            )
        )
    }

    @Test
    fun shouldClearAfterVisualAnalysisMiss_preservesOneTransientMissForRetry() {
        assertFalse(
            MaskOverlayEventPolicy.shouldClearAfterVisualAnalysisMiss(
                hasActiveMasks = true,
                hasRenderableVisualRois = true,
                isOverlayStabilizing = false,
                hasPreservedRecentVisualMiss = false
            )
        )
        assertTrue(
            MaskOverlayEventPolicy.shouldClearAfterVisualAnalysisMiss(
                hasActiveMasks = true,
                hasRenderableVisualRois = true,
                isOverlayStabilizing = false,
                hasPreservedRecentVisualMiss = true
            )
        )
    }

    @Test
    fun shouldClearAfterAnalysisFailure_preservesFallbackMasksUntilNextScreenEvent() {
        assertFalse(
            MaskOverlayEventPolicy.shouldClearAfterAnalysisFailure(
                hasActiveMasks = true,
                hasRenderableVisualRois = true,
                hasProvisionalMasks = false,
                visualAnalysisInFlight = false
            )
        )
        assertFalse(
            MaskOverlayEventPolicy.shouldClearAfterAnalysisFailure(
                hasActiveMasks = true,
                hasRenderableVisualRois = false,
                hasProvisionalMasks = true,
                visualAnalysisInFlight = false
            )
        )
        assertFalse(
            MaskOverlayEventPolicy.shouldClearAfterAnalysisFailure(
                hasActiveMasks = false,
                hasRenderableVisualRois = true,
                hasProvisionalMasks = false,
                visualAnalysisInFlight = true
            )
        )
        assertTrue(
            MaskOverlayEventPolicy.shouldClearAfterAnalysisFailure(
                hasActiveMasks = false,
                hasRenderableVisualRois = true,
                hasProvisionalMasks = true,
                visualAnalysisInFlight = false
            )
        )
        assertTrue(
            MaskOverlayEventPolicy.shouldClearAfterAnalysisFailure(
                hasActiveMasks = true,
                hasRenderableVisualRois = false,
                hasProvisionalMasks = false,
                visualAnalysisInFlight = true
            )
        )
    }

    @Test
    fun shouldDeferVisualInvalidationForContentChange_ignoresImmediateLayoutNoise() {
        assertTrue(
            MaskOverlayEventPolicy.shouldDeferVisualInvalidationForContentChange(
                eventType = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                visualAnalysisInFlight = true,
                elapsedSinceVisualAnalysisStartMs = 120L
            )
        )
        assertFalse(
            MaskOverlayEventPolicy.shouldDeferVisualInvalidationForContentChange(
                eventType = AccessibilityEvent.TYPE_VIEW_SCROLLED,
                visualAnalysisInFlight = true,
                elapsedSinceVisualAnalysisStartMs = 32L
            )
        )
        assertFalse(
            MaskOverlayEventPolicy.shouldDeferVisualInvalidationForContentChange(
                eventType = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                visualAnalysisInFlight = false,
                elapsedSinceVisualAnalysisStartMs = 32L
            )
        )
        assertFalse(
            MaskOverlayEventPolicy.shouldDeferVisualInvalidationForContentChange(
                eventType = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                visualAnalysisInFlight = true,
                elapsedSinceVisualAnalysisStartMs = 250L
            )
        )
    }

    @Test
    fun shouldRunVisualRefreshForDuplicateSnapshot_runsOnlyWhenVisualWorkIsMissing() {
        assertTrue(
            MaskOverlayEventPolicy.shouldRunVisualRefreshForDuplicateSnapshot(
                hasRenderableVisualRois = true,
                visualAnalysisInFlight = false,
                hasReusableVisualSupplement = false
            )
        )
        assertFalse(
            MaskOverlayEventPolicy.shouldRunVisualRefreshForDuplicateSnapshot(
                hasRenderableVisualRois = true,
                visualAnalysisInFlight = true,
                hasReusableVisualSupplement = false
            )
        )
        assertFalse(
            MaskOverlayEventPolicy.shouldRunVisualRefreshForDuplicateSnapshot(
                hasRenderableVisualRois = true,
                visualAnalysisInFlight = false,
                hasReusableVisualSupplement = true
            )
        )
        assertFalse(
            MaskOverlayEventPolicy.shouldRunVisualRefreshForDuplicateSnapshot(
                hasRenderableVisualRois = false,
                visualAnalysisInFlight = false,
                hasReusableVisualSupplement = false
            )
        )
    }

    @Test
    fun isLikelySelfContentChange_keepsFreshOverlayFromClearingItself() {
        assertTrue(
            MaskOverlayEventPolicy.isLikelySelfContentChange(
                eventType = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                hasActiveMasks = true,
                overlayUpdatedRecently = true
            )
        )
    }

    @Test
    fun isLikelySelfContentChange_doesNotHideRealContentChanges() {
        assertFalse(
            MaskOverlayEventPolicy.isLikelySelfContentChange(
                eventType = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                hasActiveMasks = true,
                overlayUpdatedRecently = false
            )
        )
        assertFalse(
            MaskOverlayEventPolicy.isLikelySelfContentChange(
                eventType = AccessibilityEvent.TYPE_VIEW_SCROLLED,
                hasActiveMasks = true,
                overlayUpdatedRecently = true
            )
        )
    }

    @Test
    fun screenshotRequestThrottleDelay_waitsUntilScreenshotCooldownPasses() {
        assertEquals(380L, MaskOverlayEventPolicy.screenshotRequestThrottleDelay(0L))
        assertEquals(180L, MaskOverlayEventPolicy.screenshotRequestThrottleDelay(200L))
        assertEquals(0L, MaskOverlayEventPolicy.screenshotRequestThrottleDelay(380L))
        assertEquals(0L, MaskOverlayEventPolicy.screenshotRequestThrottleDelay(500L))
    }

    @Test
    fun screenshotFailureRetryDelay_retriesOnlyIntervalTooShortFailures() {
        assertEquals(444L, MaskOverlayEventPolicy.screenshotFailureRetryDelay(3, 0L))
        assertEquals(144L, MaskOverlayEventPolicy.screenshotFailureRetryDelay(3, 300L))
        assertEquals(null, MaskOverlayEventPolicy.screenshotFailureRetryDelay(1, 0L))
    }
}
