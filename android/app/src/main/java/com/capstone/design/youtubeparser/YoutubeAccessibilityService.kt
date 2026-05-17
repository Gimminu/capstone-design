package com.capstone.design.youtubeparser

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.annotation.RequiresApi
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class YoutubeAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "YTParserService"
        private const val YOUTUBE_PACKAGE = "com.google.android.youtube"
        private const val INSTAGRAM_PACKAGE = "com.instagram.android"
        private const val TIKTOK_PACKAGE = "com.zhiliaoapp.musically"
        private const val TIKTOK_ALT_PACKAGE = "com.ss.android.ugc.trill"
        private const val MIN_UPLOAD_INTERVAL_MS = 1000L
        private const val PARSE_DELAY_TEXT_MS = 12L
        private const val PARSE_DELAY_SCROLL_MS = 32L
        private const val SCROLL_OVERLAY_STABILIZATION_MS = 64L
        private const val CONTENT_OVERLAY_STABILIZATION_MS = 48L
        private const val SCROLL_CONTENT_CHANGE_PRESERVE_MS =
            SCROLL_OVERLAY_STABILIZATION_MS + CONTENT_OVERLAY_STABILIZATION_MS
        private const val OVERLAY_SELF_CONTENT_CHANGE_GRACE_MS = 64L
        private const val PARSE_DELAY_CONTENT_MS = 40L
        private const val PARSE_DELAY_WINDOW_MS = 60L
        private const val RETRY_AFTER_IN_FLIGHT_MS = 16L
        private const val VISUAL_SUPPLEMENT_CACHE_TTL_MS = 1800L
        private const val VISUAL_ANALYSIS_TIMEOUT_MS = 1800L
        private const val MAX_VISUAL_ANALYSIS_CANDIDATES = 16
        private const val MAX_FALLBACK_VISUAL_CANDIDATES = 12
        private const val VISUAL_DUPLICATE_OVERLAP_RATIO = 0.45f
        private const val VISUAL_GEOMETRY_DUPLICATE_OVERLAP_RATIO = 0.72f
        private const val VISUAL_CONTAINED_DUPLICATE_OVERLAP_RATIO = 0.28f
        private const val VISUAL_COARSE_BASE_AREA_MULTIPLIER = 3.0f
        private const val TOP_CONTROL_OCR_EXCLUSION_MAX_PX = 220
        private const val TOP_CONTROL_OCR_EXCLUSION_RATIO = 0.12f
        private const val CACHE_PROMOTION_THROTTLE_MS = 80L
        private val PRECISE_YOUTUBE_VISUAL_SOURCES = setOf("youtube-composite-card", "youtube-visible-band")
        private const val YOUTUBE_SEMANTIC_FALLBACK_SOURCE = "youtube-semantic-card"
    }

    private val handler = Handler(Looper.getMainLooper())
    private var lastSnapshotSignature: String? = null
    private var lastUploadAt: Long = 0L
    private var lastObservedPackage: String? = null
    private val maskOverlayController by lazy { MaskOverlayController(this) }
    @Volatile private var analysisInFlight = false
    @Volatile private var pendingParseAfterAnalysis = false
    @Volatile private var followUpParseRequested = false
    @Volatile private var overlayRevision = 0L
    @Volatile private var visualSceneRevision = 0L
    private var parseScheduled = false
    private var scheduledParseAtMs = 0L
    private var scheduledParseEventType: Int? = null
    private var lastScrollEventAtMs = 0L
    private var lastAbsoluteScrollX: Int? = null
    private var lastAbsoluteScrollY: Int? = null
    private var lastPointerInteractionAtMs = 0L
    private var lastOverlayContentChangeAtMs = 0L
    private var lastCachePromotionAtMs = 0L
    private var lastAppliedSensitivity: Int? = null
    private var visualCaptureState: VisualTextCaptureState =
        VisualTextCaptureSupport.inspect(serviceInfo = null)
    private val visualExecutor = Executors.newSingleThreadExecutor()
    private val visualTextOcrProcessor by lazy { VisualTextOcrProcessor() }
    @Volatile private var visualAnalysisInFlight = false
    @Volatile private var visualAnalysisRunId = 0L
    @Volatile private var lastVisualSupplement: VisualSupplementCache? = null
    private var lastScreenshotRequestAtMs = 0L
    private var preservedRecentVisualMiss = false
    private var preservedRecentAnalysisFailure = false
    private var provisionalVisualMaskActive = false
    private var provisionalAccessibilityMaskActive = false
    private val sensitivityPreferenceListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key != AnalysisSensitivityStore.KEY_ANALYSIS_SENSITIVITY) return@OnSharedPreferenceChangeListener

            handler.post {
                syncSensitivityState()
                if (lastObservedPackage != null) {
                    scheduleParse(0L)
                }
            }
        }

    private data class VisualSupplementCache(
        val packageName: String,
        val sensitivity: Int,
        val visualRoiSignature: String,
        val response: AndroidAnalysisResponse,
        val expiresAtUptimeMs: Long
    )

    private data class AnalysisTextLocation(
        val keys: Set<String>,
        val boundsInScreen: BoundsRect,
        val authorId: String?
    )

    private data class ScrollTranslationResult(
        val status: MaskOverlayTranslationStatus?,
        val hasResolvedScrollDelta: Boolean
    ) {
        val translated: Boolean
            get() = status == MaskOverlayTranslationStatus.TRANSLATED

        val shouldHideUntilRecapture: Boolean
            get() = status == MaskOverlayTranslationStatus.REJECTED_DELTA ||
                status == MaskOverlayTranslationStatus.NO_TRANSLATABLE_MASKS ||
                status == MaskOverlayTranslationStatus.ALL_OFFSCREEN
    }

    private val parseRunnable = Runnable {
        val triggerEventType = scheduledParseEventType
        parseScheduled = false
        scheduledParseAtMs = 0L
        scheduledParseEventType = null
        parseAndUploadCurrentWindow(triggerEventType)
        if (followUpParseRequested && !analysisInFlight) {
            followUpParseRequested = false
            scheduleDeferredFollowUpParse()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        visualCaptureState = VisualTextCaptureSupport.inspect(serviceInfo)
        applicationContext
            .getSharedPreferences(AnalysisSensitivityStore.PREFS_NAME, MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(sensitivityPreferenceListener)
        syncSensitivityState()
        Log.d(TAG, "service connected")
        Log.d(
            TAG,
            "visual text capture supported=${visualCaptureState.supported} " +
                "sdk=${visualCaptureState.sdkInt} reason=${visualCaptureState.reason}"
        )
        if (visualCaptureState.supported) {
            visualExecutor.execute {
                visualTextOcrProcessor.warmUp()
            }
        }
    }

    @SuppressLint("SwitchIntDef")
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val packageName = event.packageName?.toString() ?: return
        if (!shouldObservePackage(packageName)) return

        lastObservedPackage = packageName

        when (event.eventType) {
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_START,
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_END -> {
                lastPointerInteractionAtMs = SystemClock.uptimeMillis()
                if (event.eventType == AccessibilityEvent.TYPE_TOUCH_INTERACTION_END) {
                    scheduleDeferredFollowUpParse(waitForScrollStabilization = true)
                }
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                val hasActiveMasks = maskOverlayController.hasActiveMasks()
                val overlaySelfContentChange = MaskOverlayEventPolicy.isLikelySelfContentChange(
                    eventType = event.eventType,
                    hasActiveMasks = hasActiveMasks,
                    overlayUpdatedRecently = maskOverlayController.wasUpdatedWithin(
                        OVERLAY_SELF_CONTENT_CHANGE_GRACE_MS
                    )
                )
                val contentChangedWithActiveMask =
                    event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
                        hasActiveMasks &&
                        !overlaySelfContentChange
                val visualSceneChanged = shouldInvalidateVisualScene(
                    event.eventType,
                    contentChangedWithActiveMask || overlaySelfContentChange
                )
                if (contentChangedWithActiveMask) {
                    lastOverlayContentChangeAtMs = SystemClock.uptimeMillis()
                }
                if (visualSceneChanged) {
                    markVisualSceneChanged(event.eventType)
                }

                if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
                    lastScrollEventAtMs = SystemClock.uptimeMillis()
                    val scrollTranslation = translateMaskOverlayForScroll(event)
                    if (scrollTranslation.translated) {
                        markOverlayRevisionStale()
                    } else if (
                        MaskOverlayEventPolicy.shouldHideOnUnresolvedScrollDelta(
                            eventType = event.eventType,
                            hasActiveMasks = hasActiveMasks,
                            hasResolvedScrollDelta = scrollTranslation.hasResolvedScrollDelta
                        )
                    ) {
                        Log.d(TAG, "preserve mask overlay until scroll recapture: unresolved delta")
                        markOverlayRevisionStale()
                        scheduleDeferredFollowUpParse()
                    } else if (scrollTranslation.shouldHideUntilRecapture && hasActiveMasks) {
                        Log.d(TAG, "preserve mask overlay until scroll recapture status=${scrollTranslation.status}")
                        markOverlayRevisionStale()
                        scheduleDeferredFollowUpParse(waitForScrollStabilization = true)
                    } else {
                        markOverlayRevisionStale()
                    }
                    promoteCachedMasksForCurrentWindow()
                } else if (overlaySelfContentChange) {
                    Log.d(TAG, "ignore overlay self content change")
                } else if (
                    MaskOverlayEventPolicy.shouldPreserveOnScrollContentChange(
                        eventType = event.eventType,
                        hasActiveMasks = hasActiveMasks,
                        isScrollStabilizing = isInScrollContentChangePreserveWindow(),
                        isLikelySelfContentChange = overlaySelfContentChange
                    )
                ) {
                    Log.d(TAG, "preserve mask overlay during scroll content change")
                    markOverlayRevisionStale()
                    markVisualSceneChanged(event.eventType)
                } else if (
                    event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED &&
                    maskOverlayController.hasActiveMasks()
                ) {
                    clearMaskOverlay()
                } else if (contentChangedWithActiveMask) {
                    Log.d(TAG, "preserve mask overlay until content recapture")
                    markOverlayRevisionStale()
                    markVisualSceneChanged(event.eventType)
                    scheduleDeferredFollowUpParse(waitForScrollStabilization = true)
                } else if (shouldClearOverlayImmediately(event.eventType)) {
                    clearMaskOverlay()
                } else if (event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
                    markOverlayRevisionStale()
                }

                val delayMs = when (event.eventType) {
                    AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> PARSE_DELAY_TEXT_MS
                    AccessibilityEvent.TYPE_VIEW_SCROLLED -> PARSE_DELAY_SCROLL_MS
                    AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                    AccessibilityEvent.TYPE_WINDOWS_CHANGED -> PARSE_DELAY_WINDOW_MS
                    else -> PARSE_DELAY_CONTENT_MS
                }

                scheduleParse(
                    delayMs = delayMs,
                    eventType = event.eventType,
                    replaceExisting = event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED
                )
            }
        }
    }

    override fun onInterrupt() {
        cancelScheduledParse()
        clearMaskOverlay()
        Log.d(TAG, "service interrupted")
    }

    override fun onDestroy() {
        cancelScheduledParse()
        clearMaskOverlay()
        applicationContext
            .getSharedPreferences(AnalysisSensitivityStore.PREFS_NAME, MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(sensitivityPreferenceListener)
        visualExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun scheduleParse(
        delayMs: Long,
        eventType: Int? = null,
        replaceExisting: Boolean = false
    ) {
        val safeDelayMs = delayMs.coerceAtLeast(0L)
        val targetTimeMs = SystemClock.uptimeMillis() + safeDelayMs
        if (eventType != null) {
            scheduledParseEventType = eventType
        }

        if (analysisInFlight) {
            followUpParseRequested = true
            return
        }

        if (parseScheduled) {
            if (replaceExisting || targetTimeMs < scheduledParseAtMs) {
                handler.removeCallbacks(parseRunnable)
            } else {
                followUpParseRequested = true
                return
            }
        }

        parseScheduled = true
        scheduledParseAtMs = targetTimeMs
        handler.postDelayed(parseRunnable, safeDelayMs)
    }

    private fun cancelScheduledParse() {
        handler.removeCallbacks(parseRunnable)
        parseScheduled = false
        scheduledParseAtMs = 0L
        scheduledParseEventType = null
        followUpParseRequested = false
    }

    private fun parseAndUploadCurrentWindow(triggerEventType: Int?) {
        val currentPackage = lastObservedPackage ?: run {
            Log.d(TAG, "lastObservedPackage is null")
            return
        }
        syncSensitivityState()
        val currentSensitivity = AnalysisSensitivityStore.get(applicationContext)
        if (currentSensitivity <= 0) {
            clearMaskOverlay()
            Log.d(TAG, "skip analysis: sensitivity disabled")
            return
        }

        if (shouldDeferAnalysisDuringActiveScroll(triggerEventType)) {
            Log.d(TAG, "defer analysis: scroll stabilization active")
            scheduleDeferredFollowUpParse(waitForScrollStabilization = true)
            return
        }

        val nodes = when (currentPackage) {
            YOUTUBE_PACKAGE -> extractVisibleTextNodesFromYoutubeWindows()
            INSTAGRAM_PACKAGE -> extractVisibleTextNodesFromInstagramWindows()
            else -> extractVisibleTextNodesFromCurrentWindow(currentPackage)
        }

        if (nodes.isEmpty()) {
            Log.d(TAG, "no visible nodes found")
            clearMaskOverlay()
            return
        }

        val visualRoiPlan = buildVisualTextRoiPlan(nodes)
        val metrics = resources.displayMetrics
        val screenCandidates = ScreenTextCandidateExtractor.extractCandidates(
            packageName = currentPackage,
            nodes = nodes,
            sceneRevision = visualSceneRevision,
            screenWidth = metrics.widthPixels,
            screenHeight = metrics.heightPixels
        )
        val lookaheadCandidateCount = screenCandidates.count { candidate ->
            candidate.backendSourceId.orEmpty().startsWith("android-accessibility-lookahead:")
        }
        val candidateRouteSamples = CandidateRoutingPolicy.summarize(screenCandidates)
        val comments = screenCandidates.map { it.toParsedComment() }

        Log.d(
            TAG,
                "package=$currentPackage parsed analysis target count=${comments.size} " +
                "screenCandidates=${screenCandidates.size} " +
                "lookaheadCandidates=$lookaheadCandidateCount " +
                "visualRoiCandidates=${visualRoiPlan.candidateCount} visualRois=${visualRoiPlan.rois.size} " +
                "routes=${candidateRouteSamples.joinToString(";")}"
        )

        if (shouldLogRawNodes() && currentPackage == YOUTUBE_PACKAGE && comments.size <= 3) {
            nodes.take(80).forEachIndexed { index, node ->
                Log.d(
                    TAG,
                    "YT_RAW[$index] text=${node.displayText} | cls=${node.className} | id=${node.viewIdResourceName} " +
                        "| bounds=${node.left},${node.top},${node.right},${node.bottom}"
                )
            }
        }

        if (shouldLogRawNodes() && currentPackage == INSTAGRAM_PACKAGE && comments.isEmpty()) {
            nodes.take(40).forEachIndexed { index, node ->
                Log.d(
                    TAG,
                    "IG_RAW[$index] text=${node.displayText} | cls=${node.className} | id=${node.viewIdResourceName}"
                )
            }
        }

        if (shouldLogRawNodes() && (currentPackage == TIKTOK_PACKAGE || currentPackage == TIKTOK_ALT_PACKAGE) && comments.isEmpty()) {
            nodes.take(40).forEachIndexed { index, node ->
                Log.d(
                    TAG,
                    "TT_RAW[$index] text=${node.displayText} | cls=${node.className} | id=${node.viewIdResourceName}"
                )
            }
        }

        if (comments.isEmpty()) {
            val deferClearForVisualOnlyAnalysis =
                MaskOverlayEventPolicy.shouldDeferClearForVisualOnlyAnalysis(
                    hasActiveMasks = maskOverlayController.hasActiveMasks(),
                    hasRenderableVisualRois = visualRoiPlan.hasRenderableVisualRois()
                )
            if (
                startVisualTextAnalysis(
                    packageName = currentPackage,
                    visualRoiPlan = visualRoiPlan,
                    clearExistingOverlay = !deferClearForVisualOnlyAnalysis,
                    clearExistingOverlayOnMiss = deferClearForVisualOnlyAnalysis
                )
            ) {
                return
            }
            saveVisualOnlyDiagnostics(currentPackage, visualRoiPlan)
            if (deferClearForVisualOnlyAnalysis) {
                markOverlayRevisionStale()
                return
            }
            clearMaskOverlay()
            return
        }

        val signature = buildString {
            append(currentPackage)
            append("||sensitivity=")
            append(currentSensitivity)
            append("||")
            append(
                comments.joinToString("||") {
                    "${it.commentText}|${it.boundsInScreen.top}|${it.boundsInScreen.left}|${it.authorId.orEmpty()}"
                }
            )
            append("||visual=")
            append(visualRoiPlan.signature())
        }
        val now = System.currentTimeMillis()

        if (signature == lastSnapshotSignature) {
            val snapshotOverlayRevision = overlayRevision
            val duplicateBaseResponse = ProvisionalAccessibilityMaskBuilder.buildResponse(
                candidates = screenCandidates,
                timestamp = now
            )
            val reusableVisualResponse = if (visualRoiPlan.canReuseVisualSupplement()) {
                reusableVisualSupplement(
                    packageName = currentPackage,
                    visualRoiSignature = visualRoiPlan.signature()
                )
            } else {
                null
            }
            val duplicateResponse = mergeAnalysisResponses(duplicateBaseResponse, reusableVisualResponse)
                ?: reusableVisualResponse
                ?: duplicateBaseResponse
            if (duplicateResponse != null) {
                val duplicateAnalysis = AndroidAnalysisAttempt(
                    ok = true,
                    packageName = currentPackage,
                    url = if (reusableVisualResponse != null) {
                        "duplicate-snapshot-visual-cache"
                    } else {
                        "duplicate-snapshot-provisional"
                    },
                    sensitivity = currentSensitivity,
                    latencyMs = 0L,
                    commentCount = duplicateResponse.results.size,
                    offensiveCount = duplicateResponse.results.size,
                    filteredCount = duplicateResponse.filteredCount,
                    response = duplicateResponse,
                    candidateRouteSamples = candidateRouteSamples
                ).withOverlayDiagnostics(currentPackage, visualRoiPlan)
                Log.d(
                    TAG,
                    "refresh duplicate snapshot masks results=${duplicateResponse.results.size} " +
                        "visualCached=${reusableVisualResponse != null}"
                )
                updateMaskOverlay(
                    currentPackage = currentPackage,
                    analysis = duplicateAnalysis,
                    snapshotOverlayRevision = snapshotOverlayRevision,
                    visualRoiPlan = visualRoiPlan,
                    isProvisionalAccessibilityMask = reusableVisualResponse == null,
                    allowDuringScrollStabilization = true,
                    preserveExistingPreciseVisualMasks = true
                )
            } else {
                Log.d(TAG, "skip duplicate snapshot without renderable masks")
            }
            if (
                MaskOverlayEventPolicy.shouldRunVisualRefreshForDuplicateSnapshot(
                    hasRenderableVisualRois = visualRoiPlan.hasRenderableVisualRois(),
                    visualAnalysisInFlight = visualAnalysisInFlight,
                    hasReusableVisualSupplement = reusableVisualResponse != null
                )
            ) {
                startVisualTextAnalysis(
                    packageName = currentPackage,
                    visualRoiPlan = visualRoiPlan,
                    clearExistingOverlay = false,
                    baseResponse = duplicateBaseResponse
                )
            }
            return
        }

        if (analysisInFlight) {
            pendingParseAfterAnalysis = true
            Log.d(TAG, "defer snapshot: analysis already in flight")
            return
        }

        val snapshot = ParseSnapshot(
            timestamp = now,
            comments = comments
        )
        val shouldUpload = now - lastUploadAt >= MIN_UPLOAD_INTERVAL_MS
        val savedFile = if (shouldUpload) {
            JsonFileStore.saveSnapshot(applicationContext, snapshot, currentPackage)
        } else {
            null
        }

        lastSnapshotSignature = signature
        if (shouldUpload) {
            lastUploadAt = now
        }
        val snapshotOverlayRevision = overlayRevision
        val snapshotVisualSceneRevision = visualSceneRevision
        renderProvisionalAccessibilityMaskOverlay(
            packageName = currentPackage,
            screenCandidates = screenCandidates,
            candidateRouteSamples = candidateRouteSamples,
            visualRoiPlan = visualRoiPlan,
            snapshotOverlayRevision = snapshotOverlayRevision,
            timestamp = now
        )
        analysisInFlight = true

        Thread {
            var analysisForOverlay: AndroidAnalysisAttempt? = null
            var releasedAnalysisGate = false

            fun releaseAnalysisGate() {
                if (releasedAnalysisGate) return
                releasedAnalysisGate = true
                analysisInFlight = false
                if (pendingParseAfterAnalysis || followUpParseRequested) {
                    handler.post {
                        pendingParseAfterAnalysis = false
                        followUpParseRequested = false
                        scheduleDeferredFollowUpParse()
                    }
                }
            }

            try {
                val rawAnalysis = AndroidAnalysisClient
                    .analyzeSnapshot(applicationContext, snapshot)
                    .copy(
                        packageName = currentPackage,
                        candidateRouteSamples = candidateRouteSamples
                    )
                val currentSensitivity = AnalysisSensitivityStore.get(applicationContext)
                if (rawAnalysis.sensitivity != null && rawAnalysis.sensitivity != currentSensitivity) {
                    Log.d(
                        TAG,
                        "drop analysis: stale sensitivity analysis=${rawAnalysis.sensitivity} current=$currentSensitivity"
                    )
                    return@Thread
                }

                val mergedResponse = mergeAnalysisResponses(
                    baseResponse = rawAnalysis.response,
                    visualResponse = if (visualRoiPlan.canReuseVisualSupplement()) {
                        reusableVisualSupplement(
                            packageName = currentPackage,
                            visualRoiSignature = visualRoiPlan.signature()
                        )
                    } else {
                        null
                    }
                )
                val analysis = rawAnalysis
                    .copy(
                        response = mergedResponse,
                        commentCount = mergedResponse?.results?.size ?: rawAnalysis.commentCount,
                        offensiveCount = countActionableResults(mergedResponse),
                        filteredCount = mergedResponse?.filteredCount ?: rawAnalysis.filteredCount
                    )
                    .withOverlayDiagnostics(currentPackage, visualRoiPlan)
                analysisForOverlay = analysis
                handler.post {
                    updateMaskOverlay(
                        currentPackage = currentPackage,
                        analysis = analysis,
                        snapshotOverlayRevision = snapshotOverlayRevision,
                        visualRoiPlan = visualRoiPlan,
                        preserveExistingPreciseVisualMasks = visualRoiPlan.hasRenderableVisualRois()
                    )
                }
                AnalysisDiagnosticsStore.saveAttempt(applicationContext, analysis)

                analysis.response?.let { response ->
                    JsonFileStore.saveAnalysisResponse(applicationContext, response, currentPackage)
                }

                val shouldStartVisualSupplement =
                    shouldRunVisualTextSupplement(currentPackage, analysis, visualRoiPlan)

                // Masking must not be blocked by the optional upload channel.
                releaseAnalysisGate()
                if (shouldStartVisualSupplement) {
                    handler.post {
                        if (snapshotVisualSceneRevision != visualSceneRevision) {
                            Log.d(
                                TAG,
                                "skip visual OCR start: stale base scene " +
                                    "snapshot=$snapshotVisualSceneRevision current=$visualSceneRevision"
                            )
                            return@post
                        }
                        startVisualTextAnalysis(
                            packageName = currentPackage,
                            visualRoiPlan = visualRoiPlan,
                            clearExistingOverlay = false,
                            baseResponse = analysis.response
                        )
                    }
                }

                val uploadOk = savedFile?.let {
                    ServerUploader.uploadJsonFile(applicationContext, it, currentPackage)
                } ?: false

                Log.d(
                    TAG,
                    "snapshot processed package=$currentPackage uploadOk=$uploadOk " +
                        "uploadSkipped=${savedFile == null} analysisOk=${analysis.ok} " +
                        "comments=${analysis.commentCount} offensive=${analysis.offensiveCount} " +
                        "filtered=${analysis.filteredCount} analysisLatencyMs=${analysis.latencyMs} " +
                        "analysisError=${analysis.error.orEmpty()}"
                )
            } finally {
                if (analysisForOverlay == null) {
                    handler.post {
                        updateMaskOverlay(
                            currentPackage = currentPackage,
                            analysis = null,
                            snapshotOverlayRevision = snapshotOverlayRevision,
                            visualRoiPlan = visualRoiPlan
                        )
                    }
                }
                releaseAnalysisGate()
            }
        }.start()
    }

    private fun renderProvisionalAccessibilityMaskOverlay(
        packageName: String,
        screenCandidates: List<ScreenTextCandidate>,
        candidateRouteSamples: List<String>,
        visualRoiPlan: VisualTextRoiPlan,
        snapshotOverlayRevision: Long,
        timestamp: Long
    ) {
        val response = ProvisionalAccessibilityMaskBuilder.buildResponse(
            candidates = screenCandidates,
            timestamp = timestamp
        ) ?: return

        val analysis = AndroidAnalysisAttempt(
            ok = true,
            packageName = packageName,
            url = "accessibility-provisional",
            sensitivity = AnalysisSensitivityStore.get(applicationContext),
            latencyMs = 0L,
            commentCount = response.results.size,
            offensiveCount = response.results.size,
            filteredCount = response.filteredCount,
            response = response,
            candidateRouteSamples = candidateRouteSamples
        ).withOverlayDiagnostics(packageName, visualRoiPlan)

        Log.d(TAG, "render provisional accessibility masks count=${response.results.size}")
        updateMaskOverlay(
            currentPackage = packageName,
            analysis = analysis,
            snapshotOverlayRevision = snapshotOverlayRevision,
            visualRoiPlan = visualRoiPlan,
            isProvisionalAccessibilityMask = true,
            allowDuringScrollStabilization = true,
            preserveExistingPreciseVisualMasks = true
        )
    }

    private fun updateMaskOverlay(
        currentPackage: String,
        analysis: AndroidAnalysisAttempt?,
        snapshotOverlayRevision: Long,
        visualRoiPlan: VisualTextRoiPlan? = null,
        isProvisionalVisualMask: Boolean = false,
        isProvisionalAccessibilityMask: Boolean = false,
        allowDuringScrollStabilization: Boolean = false,
        preserveExistingPreciseVisualMasks: Boolean = false
    ) {
        if (currentPackage != lastObservedPackage) {
            Log.d(
                TAG,
                "skip mask overlay: stale package current=$currentPackage observed=$lastObservedPackage"
            )
            clearMaskOverlay()
            return
        }

        if (snapshotOverlayRevision != overlayRevision) {
            Log.d(
                TAG,
                "skip mask overlay: stale overlay revision snapshot=$snapshotOverlayRevision current=$overlayRevision"
            )
            if (
                MaskOverlayEventPolicy.shouldRetryAfterStaleOverlayResult(
                    analysisOk = analysis?.ok == true,
                    snapshotOverlayRevision = snapshotOverlayRevision,
                    currentOverlayRevision = overlayRevision
                )
            ) {
                lastSnapshotSignature = null
                scheduleDeferredFollowUpParse(waitForScrollStabilization = true)
            }
            return
        }

        val analysisSensitivity = analysis?.sensitivity
        val currentSensitivity = AnalysisSensitivityStore.get(applicationContext)
        if (currentSensitivity <= 0) {
            Log.d(TAG, "clear mask overlay: sensitivity disabled")
            clearMaskOverlay()
            return
        }
        if (analysisSensitivity != null && analysisSensitivity != currentSensitivity) {
            Log.d(
                TAG,
                "skip mask overlay: stale sensitivity analysis=$analysisSensitivity current=$currentSensitivity"
            )
            return
        }

        if (analysis?.ok == true && isInScrollStabilizationWindow() && !allowDuringScrollStabilization) {
            Log.d(TAG, "defer mask overlay render: scroll stabilization active")
            scheduleDeferredFollowUpParse(waitForScrollStabilization = true)
            return
        }

        if (supportsMaskOverlay(currentPackage) && analysis?.ok == true) {
            val preserveExistingIfEmpty = MaskOverlayEventPolicy.shouldPreserveExistingOnEmptyPlan(
                hasActiveMasks = maskOverlayController.hasActiveMasks(),
                snapshotOverlayRevision = snapshotOverlayRevision,
                currentOverlayRevision = overlayRevision,
                isScrollStabilizing = isInScrollStabilizationWindow(),
                hasProvisionalMasks = provisionalVisualMaskActive || provisionalAccessibilityMaskActive,
                isProvisionalPlan = isProvisionalVisualMask || isProvisionalAccessibilityMask,
                allowProvisionalMasksOnEmpty = provisionalAccessibilityMaskActive && !provisionalVisualMaskActive
            )
            val responseResultCount = analysis.response?.results?.size ?: 0
            Log.d(
                TAG,
                "render mask overlay package=$currentPackage results=$responseResultCount " +
                    "preserveExistingIfEmpty=$preserveExistingIfEmpty " +
                    "provisionalVisual=$isProvisionalVisualMask provisionalAccessibility=$isProvisionalAccessibilityMask"
            )
            maskOverlayController.render(
                response = analysis.response,
                preserveExistingIfEmpty = preserveExistingIfEmpty,
                preserveExistingPreciseVisualMasks = preserveExistingPreciseVisualMasks
            )
            preservedRecentVisualMiss = false
            preservedRecentAnalysisFailure = false
            val hasActiveMasksAfterRender = maskOverlayController.hasActiveMasks()
            val preservedExistingEmptyPlan = preserveExistingIfEmpty && responseResultCount == 0
            provisionalVisualMaskActive =
                if (preservedExistingEmptyPlan) {
                    provisionalVisualMaskActive && hasActiveMasksAfterRender
                } else {
                    isProvisionalVisualMask && hasActiveMasksAfterRender
                }
            provisionalAccessibilityMaskActive =
                if (preservedExistingEmptyPlan) {
                    provisionalAccessibilityMaskActive && hasActiveMasksAfterRender
                } else {
                    isProvisionalAccessibilityMask && hasActiveMasksAfterRender
                }
        } else {
            if (
                supportsMaskOverlay(currentPackage) &&
                !MaskOverlayEventPolicy.shouldClearAfterAnalysisFailure(
                    hasActiveMasks = maskOverlayController.hasActiveMasks(),
                    hasRenderableVisualRois = visualRoiPlan?.hasRenderableVisualRois() == true,
                    hasPreservedRecentAnalysisFailure = preservedRecentAnalysisFailure
                )
            ) {
                Log.d(TAG, "preserve mask overlay after transient analysis failure")
                preservedRecentAnalysisFailure = true
                markOverlayRevisionStale()
                scheduleDeferredFollowUpParse(waitForScrollStabilization = true)
                return
            }
            Log.d(
                TAG,
                "clear mask overlay package=$currentPackage analysisOk=${analysis?.ok}"
            )
            clearMaskOverlay()
        }
    }

    private fun clearMaskOverlay() {
        overlayRevision += 1
        lastSnapshotSignature = null
        preservedRecentVisualMiss = false
        preservedRecentAnalysisFailure = false
        provisionalVisualMaskActive = false
        provisionalAccessibilityMaskActive = false
        invalidateVisualAnalysis(reason = "clear-overlay", requestFollowUp = false)
        maskOverlayController.clear()
        resetAbsoluteScrollPosition()
    }

    private fun markOverlayRevisionStale() {
        overlayRevision += 1
        lastSnapshotSignature = null
    }

    private fun markVisualSceneChanged(eventType: Int) {
        preservedRecentVisualMiss = false
        preservedRecentAnalysisFailure = false
        provisionalAccessibilityMaskActive = false
        invalidateVisualAnalysis(reason = "eventType=$eventType", requestFollowUp = true)
    }

    private fun shouldInvalidateVisualScene(
        eventType: Int,
        contentChangedWithActiveMask: Boolean
    ): Boolean {
        return when (eventType) {
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> !contentChangedWithActiveMask
            else -> true
        }
    }

    private fun invalidateVisualAnalysis(reason: String, requestFollowUp: Boolean) {
        visualSceneRevision += 1
        lastVisualSupplement = null
        lastSnapshotSignature = null

        if (!visualAnalysisInFlight) return

        visualAnalysisRunId += 1L
        visualAnalysisInFlight = false
        if (requestFollowUp) {
            followUpParseRequested = true
        }
        Log.d(
            TAG,
            "invalidate visual OCR reason=$reason sceneRevision=$visualSceneRevision"
        )
        if (requestFollowUp) {
            scheduleFollowUpAfterVisualGate()
        }
    }

    private fun syncSensitivityState() {
        val currentSensitivity = AnalysisSensitivityStore.get(applicationContext)
        val previousSensitivity = lastAppliedSensitivity
        if (previousSensitivity == null) {
            lastAppliedSensitivity = currentSensitivity
            if (currentSensitivity <= 0) {
                AndroidAnalysisClient.clearCache()
                clearMaskOverlay()
            }
            return
        }
        if (previousSensitivity == currentSensitivity) return

        lastAppliedSensitivity = currentSensitivity
        AndroidAnalysisClient.clearCache()
        clearMaskOverlay()
        Log.d(TAG, "analysis sensitivity changed $previousSensitivity->$currentSensitivity; cleared cache and overlay")
    }

    private fun shouldClearOverlayImmediately(eventType: Int): Boolean {
        return eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
    }

    private fun isInScrollStabilizationWindow(): Boolean {
        return isInLastMotionWindow(SCROLL_OVERLAY_STABILIZATION_MS)
    }

    private fun isInScrollContentChangePreserveWindow(): Boolean {
        return isInLastMotionWindow(SCROLL_CONTENT_CHANGE_PRESERVE_MS)
    }

    private fun isInLastMotionWindow(windowMs: Long): Boolean {
        val lastMotionEventAtMs = max(lastScrollEventAtMs, lastPointerInteractionAtMs)
        if (lastMotionEventAtMs <= 0L) return false

        val elapsedMs = SystemClock.uptimeMillis() - lastMotionEventAtMs
        return elapsedMs in 0..windowMs
    }

    private fun shouldDeferAnalysisDuringActiveScroll(triggerEventType: Int?): Boolean {
        if (isInScrollStabilizationWindow()) {
            // Content-change bursts often arrive immediately after scroll. If we
            // let them analyze while geometry is still moving, stale masks get
            // reattached to old coordinates and appear to flicker or drift.
            return triggerEventType != AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED &&
                triggerEventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                triggerEventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED
        }

        if (!maskOverlayController.hasActiveMasks()) return false
        if (!isInOverlayStabilizationWindow()) return false

        return triggerEventType != AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED &&
            triggerEventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            triggerEventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED
    }

    private fun scheduleDeferredFollowUpParse(waitForScrollStabilization: Boolean = false) {
        val remainingScrollDelayMs = if (waitForScrollStabilization) {
            remainingOverlayStabilizationMs()
        } else {
            remainingScrollDebounceMs()
        }
        if (remainingScrollDelayMs > RETRY_AFTER_IN_FLIGHT_MS) {
            scheduleParse(
                delayMs = remainingScrollDelayMs + RETRY_AFTER_IN_FLIGHT_MS,
                eventType = AccessibilityEvent.TYPE_VIEW_SCROLLED,
                replaceExisting = true
            )
        } else {
            scheduleParse(RETRY_AFTER_IN_FLIGHT_MS)
        }
    }

    private fun remainingScrollDebounceMs(): Long {
        val lastMotionEventAtMs = max(lastScrollEventAtMs, lastPointerInteractionAtMs)
        if (lastMotionEventAtMs <= 0L) return 0L

        val elapsedMs = SystemClock.uptimeMillis() - lastMotionEventAtMs
        if (elapsedMs < 0L) return PARSE_DELAY_SCROLL_MS

        return (PARSE_DELAY_SCROLL_MS - elapsedMs).coerceAtLeast(0L)
    }

    private fun remainingScrollStabilizationMs(): Long {
        val lastMotionEventAtMs = max(lastScrollEventAtMs, lastPointerInteractionAtMs)
        if (lastMotionEventAtMs <= 0L) return 0L

        val elapsedMs = SystemClock.uptimeMillis() - lastMotionEventAtMs
        if (elapsedMs < 0L) return SCROLL_OVERLAY_STABILIZATION_MS

        return (SCROLL_OVERLAY_STABILIZATION_MS - elapsedMs).coerceAtLeast(0L)
    }

    private fun isInOverlayStabilizationWindow(): Boolean {
        return isInScrollStabilizationWindow() || remainingOverlayContentStabilizationMs() > 0L
    }

    private fun remainingOverlayStabilizationMs(): Long {
        return max(remainingScrollStabilizationMs(), remainingOverlayContentStabilizationMs())
    }

    private fun remainingOverlayContentStabilizationMs(): Long {
        if (lastOverlayContentChangeAtMs <= 0L) return 0L

        val elapsedMs = SystemClock.uptimeMillis() - lastOverlayContentChangeAtMs
        if (elapsedMs < 0L) return CONTENT_OVERLAY_STABILIZATION_MS

        return (CONTENT_OVERLAY_STABILIZATION_MS - elapsedMs).coerceAtLeast(0L)
    }

    private fun translateMaskOverlayForScroll(event: AccessibilityEvent): ScrollTranslationResult {
        val hasActiveMasks = maskOverlayController.hasActiveMasks()
        val scrollDelta = MaskOverlayEventPolicy.resolveScrollTranslationDelta(
            eventType = event.eventType,
            hasActiveMasks = hasActiveMasks,
            explicitScrollDeltaX = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                event.scrollDeltaX
            } else {
                0
            },
            explicitScrollDeltaY = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                event.scrollDeltaY
            } else {
                0
            },
            absoluteScrollX = event.scrollX,
            absoluteScrollY = event.scrollY,
            lastAbsoluteScrollX = lastAbsoluteScrollX,
            lastAbsoluteScrollY = lastAbsoluteScrollY
        )
        rememberAbsoluteScrollPosition(event)

        if (scrollDelta == null) {
            return ScrollTranslationResult(
                status = null,
                hasResolvedScrollDelta = false
            )
        }

        val translationStatus = maskOverlayController.translateBy(
            deltaX = scrollDelta.deltaX,
            deltaY = scrollDelta.deltaY
        )
        if (translationStatus == MaskOverlayTranslationStatus.TRANSLATED) {
            Log.d(
                TAG,
                "translate mask overlay scroll source=${scrollDelta.source} " +
                    "delta=${scrollDelta.deltaX},${scrollDelta.deltaY}"
            )
        }
        return ScrollTranslationResult(
            status = translationStatus,
            hasResolvedScrollDelta = true
        )
    }

    private fun promoteCachedMasksForCurrentWindow() {
        val now = SystemClock.uptimeMillis()
        if (now - lastCachePromotionAtMs < CACHE_PROMOTION_THROTTLE_MS) return
        lastCachePromotionAtMs = now

        val currentPackage = lastObservedPackage ?: return
        if (currentPackage != YOUTUBE_PACKAGE) return
        if (!supportsMaskOverlay(currentPackage)) return
        if (AnalysisSensitivityStore.get(applicationContext) <= 0) return

        val nodes = extractVisibleTextNodesFromYoutubeWindows()
        if (nodes.isEmpty()) return

        val metrics = resources.displayMetrics
        val comments = ScreenTextCandidateExtractor.extractCandidates(
            packageName = currentPackage,
            nodes = nodes,
            sceneRevision = visualSceneRevision,
            screenWidth = metrics.widthPixels,
            screenHeight = metrics.heightPixels
        ).map { candidate ->
            candidate.toParsedComment()
        }
        if (comments.isEmpty()) return

        val snapshot = ParseSnapshot(
            timestamp = System.currentTimeMillis(),
            comments = comments
        )
        val analysis = AndroidAnalysisClient
            .analyzeSnapshotFromCache(applicationContext, snapshot)
            .copy(packageName = currentPackage)
        if (analysis.offensiveCount <= 0) return

        Log.d(
            TAG,
            "promote cached masks during scroll comments=${analysis.commentCount} " +
                "offensive=${analysis.offensiveCount}"
        )
        updateMaskOverlay(
            currentPackage = currentPackage,
            analysis = analysis,
            snapshotOverlayRevision = overlayRevision,
            visualRoiPlan = buildVisualTextRoiPlan(nodes),
            allowDuringScrollStabilization = true,
            preserveExistingPreciseVisualMasks = true
        )
    }

    private fun rememberAbsoluteScrollPosition(event: AccessibilityEvent) {
        MaskOverlayEventPolicy.knownAbsoluteScroll(event.scrollX)?.let { scrollX ->
            lastAbsoluteScrollX = scrollX
        }
        MaskOverlayEventPolicy.knownAbsoluteScroll(event.scrollY)?.let { scrollY ->
            lastAbsoluteScrollY = scrollY
        }
    }

    private fun resetAbsoluteScrollPosition() {
        lastAbsoluteScrollX = null
        lastAbsoluteScrollY = null
    }

    private fun shouldLogRawNodes(): Boolean {
        return Log.isLoggable(TAG, Log.VERBOSE)
    }

    private fun shouldObservePackage(packageName: String): Boolean {
        if (packageName.isBlank()) return false
        if (packageName == applicationContext.packageName) return false

        return packageName !in setOf(
            "android",
            "com.android.systemui",
            "com.google.android.inputmethod.latin",
            "com.samsung.android.honeyboard",
            "com.sec.android.inputmethod",
            "com.android.launcher",
            "com.google.android.apps.nexuslauncher"
        )
    }

    private fun supportsMaskOverlay(packageName: String): Boolean {
        return shouldObservePackage(packageName)
    }

    private fun buildVisualTextRoiPlan(nodes: List<ParsedTextNode>): VisualTextRoiPlan {
        if (!visualCaptureState.supported) {
            return VisualTextRoiPlan(rois = emptyList(), candidateCount = 0)
        }

        val metrics = resources.displayMetrics
        return VisualTextRoiPlanner.buildPlanFromNodes(
            nodes = nodes,
            screenWidth = metrics.widthPixels,
            screenHeight = metrics.heightPixels
        )
    }

    private fun saveVisualOnlyDiagnostics(
        packageName: String,
        visualRoiPlan: VisualTextRoiPlan,
        visualOcrRawCount: Int = 0,
        visualOcrSelectedCount: Int = 0
    ) {
        if (visualRoiPlan.candidateCount <= 0 && visualRoiPlan.rois.isEmpty()) return

        AnalysisDiagnosticsStore.saveAttempt(
            applicationContext,
            AndroidAnalysisClient
                .analyzeSnapshot(
                    applicationContext,
                    ParseSnapshot(
                        timestamp = System.currentTimeMillis(),
                        comments = emptyList()
                    )
                )
                .copy(
                    packageName = packageName,
                    visualOcrRawCount = visualOcrRawCount,
                    visualOcrSelectedCount = visualOcrSelectedCount,
                    actionableSamples = visualRoiPlan.rois.take(3).map { roi ->
                        "OCR 후보(${roi.source}): ${roi.boundsInScreen.left},${roi.boundsInScreen.top}," +
                            "${roi.boundsInScreen.right},${roi.boundsInScreen.bottom}"
                    }
                )
                .withVisualCaptureDiagnostics(visualRoiPlan)
        )
    }

    private fun shouldRunVisualTextSupplement(
        packageName: String,
        analysis: AndroidAnalysisAttempt,
        visualRoiPlan: VisualTextRoiPlan
    ): Boolean {
        if (
            visualRoiPlan.canReuseVisualSupplement() &&
            reusableVisualSupplement(packageName, visualRoiPlan.signature()) != null
        ) {
            return false
        }

        return analysis.ok &&
            visualRoiPlan.rois.isNotEmpty() &&
            visualRoiPlan.hasRenderableVisualRois() &&
            !visualAnalysisInFlight &&
            (packageName == YOUTUBE_PACKAGE || analysis.offensiveCount == 0)
    }

    private fun startVisualTextAnalysis(
        packageName: String,
        visualRoiPlan: VisualTextRoiPlan,
        clearExistingOverlay: Boolean = true,
        clearExistingOverlayOnMiss: Boolean = false,
        baseResponse: AndroidAnalysisResponse? = null
    ): Boolean {
        if (!supportsMaskOverlay(packageName)) return false
        if (!visualCaptureState.supported) return false
        if (visualRoiPlan.rois.isEmpty()) return false
        if (!visualRoiPlan.hasRenderableVisualRois()) return false
        if (AnalysisSensitivityStore.get(applicationContext) <= 0) return false
        if (visualAnalysisInFlight) {
            followUpParseRequested = true
            return false
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false

        val screenshotThrottleDelayMs = remainingScreenshotThrottleDelayMs()
        if (screenshotThrottleDelayMs > 0L) {
            Log.d(TAG, "defer visual OCR screenshot: throttleDelayMs=$screenshotThrottleDelayMs")
            scheduleParse(
                delayMs = screenshotThrottleDelayMs,
                eventType = AccessibilityEvent.TYPE_VIEW_SCROLLED,
                replaceExisting = true
            )
            return true
        }

        if (clearExistingOverlay) {
            clearMaskOverlay()
        }
        val snapshotOverlayRevision = overlayRevision
        val snapshotVisualSceneRevision = visualSceneRevision
        val visualRunId = visualAnalysisRunId + 1L
        visualAnalysisRunId = visualRunId
        visualAnalysisInFlight = true
        val metrics = resources.displayMetrics
        val semanticFallbackCandidates = VisualTextSemanticFallbackPlanner.selectCandidates(
            visualRoiPlan = visualRoiPlan,
            screenWidth = metrics.widthPixels,
            screenHeight = metrics.heightPixels,
            baseResponse = baseResponse
        )
        val earlyRenderableSemanticFallbackCandidates = semanticFallbackCandidates
            .filterNot { candidate ->
                candidate.visualOcrSource() in setOf(
                    "youtube-visible-band",
                    "youtube-semantic-card"
                )
            }
        if (earlyRenderableSemanticFallbackCandidates.isNotEmpty()) {
            renderProvisionalVisualMaskOverlay(
                packageName = packageName,
                visualRoiPlan = visualRoiPlan,
                selectedOcrCandidates = earlyRenderableSemanticFallbackCandidates,
                baseResponse = baseResponse,
                snapshotOverlayRevision = snapshotOverlayRevision,
                snapshotVisualSceneRevision = snapshotVisualSceneRevision,
                visualRunId = visualRunId
            )
        }
        Log.d(
            TAG,
            "start visual OCR rois=${visualRoiPlan.rois.size} " +
                "semanticFallback=${semanticFallbackCandidates.size} signature=${visualRoiPlan.signature()}"
        )
        handler.postDelayed(
            {
                timeoutVisualAnalysis(
                    visualRunId = visualRunId,
                    packageName = packageName,
                    visualRoiPlan = visualRoiPlan,
                    snapshotVisualSceneRevision = snapshotVisualSceneRevision,
                    clearExistingOverlayOnMiss = clearExistingOverlayOnMiss
                )
            },
            VISUAL_ANALYSIS_TIMEOUT_MS
        )

        try {
            lastScreenshotRequestAtMs = SystemClock.uptimeMillis()
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                visualExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshotResult: ScreenshotResult) {
                        if (isVisualAnalysisStale(visualRunId, snapshotVisualSceneRevision)) {
                            finishVisualAnalysis(visualRunId)
                            return
                        }

                        val screenshot = screenshotResult.toSoftwareBitmap()
                        if (screenshot == null) {
                            saveVisualFailureDiagnostics(
                                packageName = packageName,
                                visualRoiPlan = visualRoiPlan,
                                error = "SCREENSHOT_BITMAP_UNAVAILABLE"
                            )
                            if (clearExistingOverlayOnMiss && semanticFallbackCandidates.isEmpty()) {
                                clearMaskOverlayAfterVisualMiss(
                                    packageName = packageName,
                                    visualRoiPlan = visualRoiPlan,
                                    visualRunId = visualRunId,
                                    snapshotVisualSceneRevision = snapshotVisualSceneRevision
                                )
                            }
                            finishVisualAnalysis(visualRunId)
                            return
                        }

                        val screenshotWidth = screenshot.width
                        val screenshotHeight = screenshot.height
                        visualTextOcrProcessor.recognize(screenshot, visualRoiPlan.rois) { ocrCandidates ->
                            if (!screenshot.isRecycled) {
                                screenshot.recycle()
                            }

                            if (isVisualAnalysisStale(visualRunId, snapshotVisualSceneRevision)) {
                                finishVisualAnalysis(visualRunId)
                                return@recognize
                            }

                            analyzeVisualTextCandidates(
                                packageName = packageName,
                                visualRoiPlan = visualRoiPlan,
                                ocrCandidates = ocrCandidates,
                                screenWidth = screenshotWidth,
                                screenHeight = screenshotHeight,
                                snapshotOverlayRevision = snapshotOverlayRevision,
                                snapshotVisualSceneRevision = snapshotVisualSceneRevision,
                                baseResponse = baseResponse,
                                semanticFallbackCandidates = semanticFallbackCandidates,
                                clearExistingOverlayOnMiss = clearExistingOverlayOnMiss,
                                visualRunId = visualRunId
                            )
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        val retryDelayMs = screenshotFailureRetryDelayMs(errorCode)
                        saveVisualFailureDiagnostics(
                            packageName = packageName,
                            visualRoiPlan = visualRoiPlan,
                            error = "SCREENSHOT_FAILED_$errorCode"
                        )
                        if (clearExistingOverlayOnMiss && semanticFallbackCandidates.isEmpty()) {
                            clearMaskOverlayAfterVisualMiss(
                                packageName = packageName,
                                visualRoiPlan = visualRoiPlan,
                                visualRunId = visualRunId,
                                snapshotVisualSceneRevision = snapshotVisualSceneRevision
                            )
                        }
                        finishVisualAnalysis(visualRunId, retryDelayMs = retryDelayMs)
                    }
                }
            )
        } catch (error: RuntimeException) {
            Log.w(TAG, "visual text screenshot request failed", error)
            saveVisualFailureDiagnostics(
                packageName = packageName,
                visualRoiPlan = visualRoiPlan,
                error = error.javaClass.simpleName.takeIf { it.isNotBlank() }
                    ?: "SCREENSHOT_REQUEST_FAILED"
            )
            if (clearExistingOverlayOnMiss && semanticFallbackCandidates.isEmpty()) {
                clearMaskOverlayAfterVisualMiss(
                    packageName = packageName,
                    visualRoiPlan = visualRoiPlan,
                    visualRunId = visualRunId,
                    snapshotVisualSceneRevision = snapshotVisualSceneRevision
                )
            }
            finishVisualAnalysis(visualRunId)
            return false
        }

        return true
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun ScreenshotResult.toSoftwareBitmap(): Bitmap? {
        val hardwareBuffer = hardwareBuffer
        return try {
            val wrapped = Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)
            wrapped?.copy(Bitmap.Config.ARGB_8888, false)
        } catch (error: RuntimeException) {
            Log.w(TAG, "failed to convert screenshot to bitmap", error)
            null
        } finally {
            hardwareBuffer.close()
        }
    }

    private fun analyzeVisualTextCandidates(
        packageName: String,
        visualRoiPlan: VisualTextRoiPlan,
        ocrCandidates: List<ParsedComment>,
        screenWidth: Int,
        screenHeight: Int,
        snapshotOverlayRevision: Long,
        snapshotVisualSceneRevision: Long,
        baseResponse: AndroidAnalysisResponse?,
        semanticFallbackCandidates: List<ParsedComment> = emptyList(),
        clearExistingOverlayOnMiss: Boolean,
        visualRunId: Long
    ) {
        Thread {
            try {
                if (isVisualAnalysisStale(visualRunId, snapshotVisualSceneRevision)) return@Thread

                val selectedOcrCandidates = selectVisualTextCandidates(
                    ocrCandidates = ocrCandidates,
                    screenWidth = screenWidth,
                    screenHeight = screenHeight,
                    baseResponse = baseResponse
                )
                val selectedVisualCandidates = mergeVisualCandidateSelections(
                    selectedOcrCandidates = selectedOcrCandidates,
                    semanticFallbackCandidates = semanticFallbackCandidates
                )
                if (selectedVisualCandidates.isEmpty()) {
                    Log.d(
                        TAG,
                        "visual OCR candidates selected=0 raw=${ocrCandidates.size} " +
                            "semanticFallback=${semanticFallbackCandidates.size} " +
                            "base=${baseResponse?.results?.size ?: 0}"
                    )
                    saveVisualOnlyDiagnostics(
                        packageName = packageName,
                        visualRoiPlan = visualRoiPlan,
                        visualOcrRawCount = ocrCandidates.size,
                        visualOcrSelectedCount = 0
                    )
                    if (clearExistingOverlayOnMiss) {
                        clearMaskOverlayAfterVisualMiss(
                            packageName = packageName,
                            visualRoiPlan = visualRoiPlan,
                            visualRunId = visualRunId,
                            snapshotVisualSceneRevision = snapshotVisualSceneRevision
                        )
                    }
                    return@Thread
                }
                Log.d(
                    TAG,
                    "visual OCR candidates selected=${selectedVisualCandidates.size} " +
                        "raw=${ocrCandidates.size} semanticFallback=${semanticFallbackCandidates.size}"
                )
                renderProvisionalVisualMaskOverlay(
                    packageName = packageName,
                    visualRoiPlan = visualRoiPlan,
                    selectedOcrCandidates = selectedVisualCandidates,
                    baseResponse = baseResponse,
                    snapshotOverlayRevision = snapshotOverlayRevision,
                    snapshotVisualSceneRevision = snapshotVisualSceneRevision,
                    visualRunId = visualRunId
                )

                val snapshot = ParseSnapshot(
                    timestamp = System.currentTimeMillis(),
                    comments = selectedVisualCandidates
                )
                val rawAnalysis = AndroidAnalysisClient
                    .analyzeSnapshot(applicationContext, snapshot)
                    .copy(packageName = packageName)
                if (isVisualAnalysisStale(visualRunId, snapshotVisualSceneRevision)) return@Thread

                val currentSensitivity = AnalysisSensitivityStore.get(applicationContext)
                if (rawAnalysis.sensitivity != null && rawAnalysis.sensitivity != currentSensitivity) {
                    Log.d(
                        TAG,
                        "drop visual analysis: stale sensitivity analysis=${rawAnalysis.sensitivity} current=$currentSensitivity"
                    )
                    return@Thread
                }

                if (!rawAnalysis.ok) {
                    AnalysisDiagnosticsStore.saveAttempt(
                        applicationContext,
                        rawAnalysis
                            .copy(
                                visualOcrRawCount = ocrCandidates.size,
                                visualOcrSelectedCount = selectedVisualCandidates.size
                            )
                            .withOverlayDiagnostics(packageName, visualRoiPlan)
                    )
                    if (clearExistingOverlayOnMiss) {
                        clearMaskOverlayAfterVisualMiss(
                            packageName = packageName,
                            visualRoiPlan = visualRoiPlan,
                            visualRunId = visualRunId,
                            snapshotVisualSceneRevision = snapshotVisualSceneRevision
                        )
                    }
                    return@Thread
                }

                val mergedBaseResponse = mergeAnalysisResponses(
                    baseResponse = baseResponse,
                    visualResponse = if (visualRoiPlan.canReuseVisualSupplement()) {
                        reusableVisualSupplement(
                            packageName = packageName,
                            visualRoiSignature = visualRoiPlan.signature()
                        )
                    } else {
                        null
                    }
                )
                val visualMaskResponse = buildProvisionalVisualResponse(selectedVisualCandidates)
                val mergedVisualResponse = mergeAnalysisResponses(rawAnalysis.response, visualMaskResponse)
                val mergedResponse = mergeAnalysisResponses(mergedBaseResponse, mergedVisualResponse)
                storeVisualSupplement(
                    packageName = packageName,
                    visualRoiPlan = visualRoiPlan,
                    response = mergedResponse
                )
                val analysis = rawAnalysis
                    .copy(
                        response = mergedResponse,
                        commentCount = mergedResponse?.results?.size ?: rawAnalysis.commentCount,
                        offensiveCount = countActionableResults(mergedResponse),
                        filteredCount = mergedResponse?.filteredCount ?: rawAnalysis.filteredCount,
                        visualOcrRawCount = ocrCandidates.size,
                        visualOcrSelectedCount = selectedVisualCandidates.size
                    )
                    .withOverlayDiagnostics(packageName, visualRoiPlan)

                AnalysisDiagnosticsStore.saveAttempt(applicationContext, analysis)
                handler.post {
                    if (isVisualAnalysisStale(visualRunId, snapshotVisualSceneRevision)) return@post
                    updateMaskOverlay(
                        currentPackage = packageName,
                        analysis = analysis,
                        snapshotOverlayRevision = snapshotOverlayRevision,
                        visualRoiPlan = visualRoiPlan
                    )
                }
            } finally {
                finishVisualAnalysis(visualRunId)
            }
        }.start()
    }

    private fun renderProvisionalVisualMaskOverlay(
        packageName: String,
        visualRoiPlan: VisualTextRoiPlan,
        selectedOcrCandidates: List<ParsedComment>,
        baseResponse: AndroidAnalysisResponse? = null,
        snapshotOverlayRevision: Long,
        snapshotVisualSceneRevision: Long,
        visualRunId: Long
    ) {
        val visualResponse = buildProvisionalVisualResponse(selectedOcrCandidates)
        val response = mergeAnalysisResponses(baseResponse, visualResponse) ?: visualResponse
        val analysis = AndroidAnalysisAttempt(
            ok = true,
            packageName = packageName,
            url = "visual-ocr-provisional",
            sensitivity = AnalysisSensitivityStore.get(applicationContext),
            latencyMs = 0L,
            commentCount = response.results.size,
            offensiveCount = response.results.size,
            filteredCount = response.filteredCount,
            response = response,
            visualOcrSelectedCount = selectedOcrCandidates.size
        ).withOverlayDiagnostics(packageName, visualRoiPlan)

        handler.post {
            if (isVisualAnalysisStale(visualRunId, snapshotVisualSceneRevision)) return@post
            Log.d(TAG, "render provisional visual OCR masks count=${selectedOcrCandidates.size}")
            updateMaskOverlay(
                currentPackage = packageName,
                analysis = analysis,
                snapshotOverlayRevision = snapshotOverlayRevision,
                visualRoiPlan = visualRoiPlan,
                isProvisionalVisualMask = true
            )
        }
    }

    private fun buildProvisionalVisualResponse(
        selectedOcrCandidates: List<ParsedComment>
    ): AndroidAnalysisResponse {
        val results = selectedOcrCandidates.map { candidate ->
            val text = candidate.commentText
            val textLength = text.codePointCount(0, text.length).coerceAtLeast(1)
            AndroidAnalysisResultItem(
                original = text,
                boundsInScreen = candidate.boundsInScreen,
                authorId = candidate.authorId,
                isOffensive = true,
                isProfane = true,
                isToxic = false,
                isHate = false,
                scores = HarmScores(profanity = 1.0, toxicity = 0.0, hate = 0.0),
                evidenceSpans = listOf(
                    EvidenceSpan(
                        text = text,
                        start = 0,
                        end = textLength,
                        score = 1.0
                    )
                )
            )
        }
        return AndroidAnalysisResponse(
            timestamp = System.currentTimeMillis(),
            filteredCount = results.size,
            results = results
        )
    }

    private fun timeoutVisualAnalysis(
        visualRunId: Long,
        packageName: String,
        visualRoiPlan: VisualTextRoiPlan,
        snapshotVisualSceneRevision: Long,
        clearExistingOverlayOnMiss: Boolean
    ) {
        if (visualRunId != visualAnalysisRunId || !visualAnalysisInFlight) return

        Log.w(TAG, "visual OCR timed out runId=$visualRunId")
        visualAnalysisRunId += 1L
        visualAnalysisInFlight = false
        saveVisualFailureDiagnostics(
            packageName = packageName,
            visualRoiPlan = visualRoiPlan,
            error = "VISUAL_OCR_TIMEOUT"
        )
        if (
            clearExistingOverlayOnMiss &&
            packageName == lastObservedPackage &&
            snapshotVisualSceneRevision == visualSceneRevision
        ) {
            handleVisualAnalysisMissOnMain(visualRoiPlan)
        }
        scheduleFollowUpAfterVisualGate()
    }

    private fun finishVisualAnalysis(
        visualRunId: Long,
        retryDelayMs: Long? = null
    ) {
        if (visualRunId != visualAnalysisRunId) return
        visualAnalysisInFlight = false
        if (retryDelayMs != null) {
            followUpParseRequested = false
            Log.d(TAG, "retry visual OCR after screenshot throttle delayMs=$retryDelayMs")
            handler.post {
                scheduleParse(
                    delayMs = retryDelayMs,
                    eventType = AccessibilityEvent.TYPE_VIEW_SCROLLED,
                    replaceExisting = true
                )
            }
            return
        }
        scheduleFollowUpAfterVisualGate()
    }

    private fun remainingScreenshotThrottleDelayMs(): Long {
        val lastRequestAtMs = lastScreenshotRequestAtMs
        if (lastRequestAtMs <= 0L) return 0L

        return MaskOverlayEventPolicy.screenshotRequestThrottleDelay(
            elapsedSinceLastRequestMs = SystemClock.uptimeMillis() - lastRequestAtMs
        )
    }

    private fun screenshotFailureRetryDelayMs(errorCode: Int): Long? {
        val lastRequestAtMs = lastScreenshotRequestAtMs
        if (lastRequestAtMs <= 0L) return null

        return MaskOverlayEventPolicy.screenshotFailureRetryDelay(
            errorCode = errorCode,
            elapsedSinceLastRequestMs = SystemClock.uptimeMillis() - lastRequestAtMs
        )
    }

    private fun clearMaskOverlayAfterVisualMiss(
        packageName: String,
        visualRoiPlan: VisualTextRoiPlan,
        visualRunId: Long,
        snapshotVisualSceneRevision: Long
    ) {
        handler.post {
            if (packageName != lastObservedPackage) return@post
            if (isVisualAnalysisStale(visualRunId, snapshotVisualSceneRevision)) return@post
            handleVisualAnalysisMissOnMain(visualRoiPlan)
        }
    }

    private fun handleVisualAnalysisMissOnMain(visualRoiPlan: VisualTextRoiPlan) {
        if (
            !MaskOverlayEventPolicy.shouldClearAfterVisualAnalysisMiss(
                hasActiveMasks = maskOverlayController.hasActiveMasks(),
                hasRenderableVisualRois = visualRoiPlan.hasRenderableVisualRois(),
                isOverlayStabilizing = isInOverlayStabilizationWindow(),
                hasPreservedRecentVisualMiss = preservedRecentVisualMiss
            )
        ) {
            Log.d(TAG, "preserve mask overlay after transient visual OCR miss")
            preservedRecentVisualMiss = true
            markOverlayRevisionStale()
            scheduleDeferredFollowUpParse(waitForScrollStabilization = true)
            return
        }
        preservedRecentVisualMiss = false
        clearMaskOverlay()
    }

    private fun scheduleFollowUpAfterVisualGate() {
        if (followUpParseRequested && !analysisInFlight) {
            handler.post {
                if (analysisInFlight) return@post
                followUpParseRequested = false
                scheduleDeferredFollowUpParse()
            }
        }
    }

    private fun isVisualAnalysisStale(
        visualRunId: Long,
        snapshotVisualSceneRevision: Long
    ): Boolean {
        return visualRunId != visualAnalysisRunId ||
            snapshotVisualSceneRevision != visualSceneRevision
    }

    private fun mergeVisualCandidateSelections(
        selectedOcrCandidates: List<ParsedComment>,
        semanticFallbackCandidates: List<ParsedComment> = emptyList()
    ): List<ParsedComment> {
        val selected = mutableListOf<ParsedComment>()

        fun appendCandidates(candidates: List<ParsedComment>) {
            candidates
                .sortedWith(
                    compareBy<ParsedComment> { visualCandidateSourceRank(it) }
                        .thenBy { it.boundsInScreen.top }
                        .thenBy { it.boundsInScreen.left }
                )
                .forEach { candidate ->
                    if (selected.size >= MAX_VISUAL_ANALYSIS_CANDIDATES) return@forEach
                    if (selected.none { existing -> isSameVisualCandidate(existing, candidate) }) {
                        selected += candidate
                    }
                }
        }

        appendCandidates(selectedOcrCandidates)
        appendCandidates(semanticFallbackCandidates)
        return selected
    }

    private fun selectVisualTextCandidates(
        ocrCandidates: List<ParsedComment>,
        screenWidth: Int,
        screenHeight: Int,
        baseResponse: AndroidAnalysisResponse?
    ): List<ParsedComment> {
        val baseLocations = baseResponse?.results
            ?.mapNotNull { result ->
                val keys = analysisTextKeys(result.original)
                if (keys.isEmpty()) {
                    null
                } else {
                    AnalysisTextLocation(
                        keys = keys,
                        boundsInScreen = result.boundsInScreen,
                        authorId = result.authorId
                    )
                }
            }
            .orEmpty()

        val sortedCandidates = ocrCandidates
            .asSequence()
            .filter { candidate ->
                val key = normalizeAnalysisTextKey(candidate.commentText)
                key.isNotBlank() &&
                    !isTopControlOcrCandidate(candidate, screenWidth, screenHeight) &&
                    !matchesBaseTextLocation(candidate, baseLocations)
            }
            .sortedWith(
                compareBy<ParsedComment> { visualCandidateSourceRank(it) }
                    .thenBy { it.boundsInScreen.top }
                    .thenBy { it.boundsInScreen.left }
            )
            .toList()

        val distinctCandidates = mutableListOf<ParsedComment>()
        var fallbackCandidateCount = 0
        for (candidate in sortedCandidates) {
            if (distinctCandidates.any { existing -> isSameVisualCandidate(existing, candidate) }) {
                continue
            }

            val isFallbackCandidate = candidate.visualOcrSource() == "youtube-visible-band"
            if (isFallbackCandidate) {
                if (fallbackCandidateCount >= MAX_FALLBACK_VISUAL_CANDIDATES) continue
            }

            distinctCandidates += candidate
            if (isFallbackCandidate) {
                fallbackCandidateCount += 1
            }
            if (distinctCandidates.size >= MAX_VISUAL_ANALYSIS_CANDIDATES) break
        }

        return distinctCandidates
    }

    private fun visualCandidateSourceRank(candidate: ParsedComment): Int {
        return when (candidate.visualOcrSource()) {
            "youtube-visible-band" -> 9
            "youtube-composite-card" -> 0
            "generic-visual-region" -> 1
            else -> 3
        }
    }

    private fun normalizeAnalysisTextKey(text: String): String {
        return text.replace(Regex("\\s+"), " ").trim().lowercase()
    }

    private fun analysisTextKeys(text: String): Set<String> {
        val normalized = normalizeAnalysisTextKey(text)
        val rangeKeys = VisualTextOcrCandidateFilter.findAnalysisRanges(text)
            .map { range -> normalizeAnalysisTextKey(range.analysisText) }
            .filter { key -> key.isNotBlank() }

        return (listOf(normalized) + rangeKeys)
            .filter { key -> key.isNotBlank() }
            .toSet()
    }

    private fun ParsedComment.visualOcrMetadata(): VisualTextOcrMetadata? {
        return VisualTextOcrMetadataCodec.decode(authorId)
    }

    private fun ParsedComment.visualOcrSource(): String? {
        return visualOcrMetadata()?.source
    }

    private fun isTopControlOcrCandidate(
        candidate: ParsedComment,
        screenWidth: Int,
        screenHeight: Int
    ): Boolean {
        if (candidate.visualOcrSource() == null) return false
        if (
            VisualTextGeometryPolicy.isTrustedVisibleBandOcr(
                authorId = candidate.authorId,
                left = candidate.boundsInScreen.left,
                top = candidate.boundsInScreen.top,
                right = candidate.boundsInScreen.right,
                bottom = candidate.boundsInScreen.bottom
            )
        ) {
            return false
        }
        if (VisualTextGeometryPolicy.isTopHeroYoutubeComposite(candidate.authorId, screenWidth)) {
            return false
        }
        val cutoff = min(
            TOP_CONTROL_OCR_EXCLUSION_MAX_PX,
            (screenHeight * TOP_CONTROL_OCR_EXCLUSION_RATIO).toInt()
        )
        return candidate.boundsInScreen.top < cutoff
    }

    private fun matchesBaseTextLocation(
        candidate: ParsedComment,
        baseLocations: List<AnalysisTextLocation>
    ): Boolean {
        val candidateKeys = analysisTextKeys(candidate.commentText)
        if (candidateKeys.isEmpty()) return true
        val visualMetadata = candidate.visualOcrMetadata()

        return baseLocations.any { baseLocation ->
            val overlapRatio = boundsOverlapRatio(candidate.boundsInScreen, baseLocation.boundsInScreen)
            val sameText = candidateKeys.any { key -> key in baseLocation.keys }

            when {
                !sameText -> false
                visualMetadata != null && !baseLocation.isRenderableForOverlay() -> false
                visualMetadata != null &&
                    isCoarseBaseLocation(candidate.boundsInScreen, baseLocation.boundsInScreen) -> false
                overlapRatio >= VISUAL_GEOMETRY_DUPLICATE_OVERLAP_RATIO && visualMetadata != null -> true
                overlapRatio >= VISUAL_DUPLICATE_OVERLAP_RATIO && sameText -> true
                visualMetadata?.source == "youtube-composite-card" &&
                    visualMetadata.roiBoundsInScreen?.contains(baseLocation.boundsInScreen) == true &&
                    overlapRatio >= VISUAL_CONTAINED_DUPLICATE_OVERLAP_RATIO -> true
                else -> false
            }
        }
    }

    private fun AnalysisTextLocation.isRenderableForOverlay(): Boolean {
        val source = authorId ?: return false
        return source == "android-accessibility:user_input" ||
            source == "android-accessibility:youtube_user_input" ||
            source.startsWith("android-accessibility-range:") ||
            source.startsWith("ocr:youtube-composite-card:") ||
            source.startsWith("ocr:youtube-visible-band:")
    }

    private fun isCoarseBaseLocation(candidateBounds: BoundsRect, baseBounds: BoundsRect): Boolean {
        val candidateArea = boundsArea(candidateBounds).coerceAtLeast(1)
        val baseArea = boundsArea(baseBounds).coerceAtLeast(1)
        return baseArea.toFloat() / candidateArea.toFloat() >= VISUAL_COARSE_BASE_AREA_MULTIPLIER
    }

    private fun boundsArea(bounds: BoundsRect): Int {
        return max(0, bounds.right - bounds.left) * max(0, bounds.bottom - bounds.top)
    }

    private fun BoundsRect.contains(inner: BoundsRect): Boolean {
        return inner.left >= left &&
            inner.top >= top &&
            inner.right <= right &&
            inner.bottom <= bottom
    }

    private fun isSameVisualCandidate(left: ParsedComment, right: ParsedComment): Boolean {
        if (isSameVisualCandidateWithinRoi(left, right)) return true

        val overlapRatio = boundsOverlapRatio(left.boundsInScreen, right.boundsInScreen)
        return (
            normalizeAnalysisTextKey(left.commentText) == normalizeAnalysisTextKey(right.commentText) &&
                overlapRatio >= VISUAL_DUPLICATE_OVERLAP_RATIO
            ) || overlapRatio >= VISUAL_GEOMETRY_DUPLICATE_OVERLAP_RATIO
    }

    private fun isSameVisualCandidateWithinRoi(left: ParsedComment, right: ParsedComment): Boolean {
        val leftMetadata = left.visualOcrMetadata() ?: return false
        val rightMetadata = right.visualOcrMetadata() ?: return false
        val leftRoi = leftMetadata.roiBoundsInScreen ?: return false
        val rightRoi = rightMetadata.roiBoundsInScreen ?: return false
        if (leftRoi != rightRoi) return false

        val leftSource = leftMetadata.source
        val rightSource = rightMetadata.source
        val hasPrecise = leftSource in PRECISE_YOUTUBE_VISUAL_SOURCES ||
            rightSource in PRECISE_YOUTUBE_VISUAL_SOURCES
        val hasSemanticFallback = leftSource == YOUTUBE_SEMANTIC_FALLBACK_SOURCE ||
            rightSource == YOUTUBE_SEMANTIC_FALLBACK_SOURCE
        if (!hasPrecise || !hasSemanticFallback) return false

        val leftKeys = analysisTextKeys(left.commentText)
        if (leftKeys.isEmpty()) return false
        val rightKeys = analysisTextKeys(right.commentText)
        return rightKeys.any { key -> key in leftKeys }
    }

    private fun boundsOverlapRatio(left: BoundsRect, right: BoundsRect): Float {
        val intersectionLeft = max(left.left, right.left)
        val intersectionTop = max(left.top, right.top)
        val intersectionRight = min(left.right, right.right)
        val intersectionBottom = min(left.bottom, right.bottom)
        val intersectionWidth = max(0, intersectionRight - intersectionLeft)
        val intersectionHeight = max(0, intersectionBottom - intersectionTop)
        val intersectionArea = intersectionWidth * intersectionHeight
        if (intersectionArea <= 0) return 0f

        val leftArea = max(0, left.right - left.left) * max(0, left.bottom - left.top)
        val rightArea = max(0, right.right - right.left) * max(0, right.bottom - right.top)
        val smallerArea = min(leftArea, rightArea)
        if (smallerArea <= 0) return 0f

        return intersectionArea.toFloat() / smallerArea.toFloat()
    }

    private fun VisualTextRoiPlan.signature(): String {
        return rois.joinToString("|") { roi ->
            val bounds = roi.boundsInScreen
            "${roi.source}:${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}"
        }
    }

    private fun VisualTextRoiPlan.canReuseVisualSupplement(): Boolean {
        return rois.isNotEmpty() && rois.none { roi ->
            roi.source == "youtube-visible-band" ||
                roi.source == "youtube-composite-card"
        }
    }

    private fun VisualTextRoiPlan.hasRenderableVisualRois(): Boolean {
        return rois.any { roi ->
            roi.source == "youtube-composite-card" ||
                roi.source == "youtube-visible-band"
        }
    }

    private fun reusableVisualSupplement(
        packageName: String,
        visualRoiSignature: String? = null
    ): AndroidAnalysisResponse? {
        val cached = lastVisualSupplement ?: return null
        if (cached.packageName != packageName) return null
        val currentSensitivity = AnalysisSensitivityStore.get(applicationContext)
        if (cached.sensitivity != currentSensitivity) return null
        if (visualRoiSignature != null && cached.visualRoiSignature != visualRoiSignature) return null
        if (cached.expiresAtUptimeMs <= SystemClock.uptimeMillis()) {
            lastVisualSupplement = null
            return null
        }
        Log.d(TAG, "reuse visual supplement signature=${cached.visualRoiSignature}")
        return cached.response
    }

    private fun storeVisualSupplement(
        packageName: String,
        visualRoiPlan: VisualTextRoiPlan,
        response: AndroidAnalysisResponse?
    ) {
        if (!visualRoiPlan.canReuseVisualSupplement()) return
        if (response == null || countActionableResults(response) <= 0) return
        lastVisualSupplement = VisualSupplementCache(
            packageName = packageName,
            sensitivity = AnalysisSensitivityStore.get(applicationContext),
            visualRoiSignature = visualRoiPlan.signature(),
            response = response,
            expiresAtUptimeMs = SystemClock.uptimeMillis() + VISUAL_SUPPLEMENT_CACHE_TTL_MS
        )
    }

    private fun mergeAnalysisResponses(
        baseResponse: AndroidAnalysisResponse?,
        visualResponse: AndroidAnalysisResponse?
    ): AndroidAnalysisResponse? {
        if (baseResponse == null) return visualResponse
        if (visualResponse == null) return baseResponse

        val merged = (baseResponse.results + visualResponse.results)
            .distinctBy { result ->
                val bounds = result.boundsInScreen
                "${result.original}|${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}"
            }

        return AndroidAnalysisResponse(
            timestamp = maxOf(baseResponse.timestamp, visualResponse.timestamp),
            filteredCount = baseResponse.filteredCount + visualResponse.filteredCount,
            results = merged
        )
    }

    private fun countActionableResults(response: AndroidAnalysisResponse?): Int {
        return response?.results
            ?.count { result -> result.isOffensive && result.evidenceSpans.isNotEmpty() }
            ?: 0
    }

    private fun saveVisualFailureDiagnostics(
        packageName: String,
        visualRoiPlan: VisualTextRoiPlan,
        error: String
    ) {
        AnalysisDiagnosticsStore.saveAttempt(
            applicationContext,
            AndroidAnalysisClient
                .analyzeSnapshot(
                    applicationContext,
                    ParseSnapshot(
                        timestamp = System.currentTimeMillis(),
                        comments = emptyList()
                    )
                )
                .copy(
                    ok = false,
                    packageName = packageName,
                    error = error
                )
                .withVisualCaptureDiagnostics(visualRoiPlan)
        )
    }

    private fun AndroidAnalysisAttempt.withOverlayDiagnostics(
        packageName: String,
        visualRoiPlan: VisualTextRoiPlan
    ): AndroidAnalysisAttempt {
        if (!supportsMaskOverlay(packageName)) return withVisualCaptureDiagnostics(visualRoiPlan)
        val response = response ?: return withVisualCaptureDiagnostics(visualRoiPlan)
        val metrics = resources.displayMetrics
        val plan = AndroidMaskOverlayPlanner.buildPlan(
            response = response,
            screenWidth = metrics.widthPixels,
            screenHeight = metrics.heightPixels
        )

        return copy(
            overlayCandidateCount = plan.candidateCount,
            overlayRenderedCount = plan.specs.size,
            overlaySkippedUnstableCount = plan.skippedUnstableCount,
            overlayRenderedSamples = plan.renderedSamples,
            visualCaptureSupported = visualCaptureState.supported,
            visualCaptureReason = visualCaptureState.reason,
            visualRoiCandidateCount = visualRoiPlan.candidateCount,
            visualRoiSelectedCount = visualRoiPlan.rois.size
        )
    }

    private fun AndroidAnalysisAttempt.withVisualCaptureDiagnostics(
        visualRoiPlan: VisualTextRoiPlan = VisualTextRoiPlan(rois = emptyList(), candidateCount = 0)
    ): AndroidAnalysisAttempt {
        return copy(
            visualCaptureSupported = visualCaptureState.supported,
            visualCaptureReason = visualCaptureState.reason,
            visualRoiCandidateCount = visualRoiPlan.candidateCount,
            visualRoiSelectedCount = visualRoiPlan.rois.size
        )
    }

    private fun extractVisibleTextNodesFromCurrentWindow(currentPackage: String): List<ParsedTextNode> {
        val root = rootInActiveWindow ?: return emptyList()

        val tiktokMode = currentPackage == TIKTOK_PACKAGE || currentPackage == TIKTOK_ALT_PACKAGE
        return collectFilteredNodesFromRoot(
            root = root,
            instagramMode = false,
            tiktokMode = tiktokMode
        )
    }

    private fun extractVisibleTextNodesFromYoutubeWindows(): List<ParsedTextNode> {
        val out = mutableListOf<ParsedTextNode>()
        val seenRootKeys = mutableSetOf<String>()

        fun addRoot(root: AccessibilityNodeInfo?) {
            if (root == null) return
            if (root.packageName?.toString() != YOUTUBE_PACKAGE) return

            val rect = Rect().also { root.getBoundsInScreen(it) }
            val rootKey = "${rect.left},${rect.top},${rect.right},${rect.bottom},${root.className}"
            if (!seenRootKeys.add(rootKey)) return

            out += collectRawNodesFromRoot(root)
        }

        addRoot(rootInActiveWindow)
        windows?.forEach { window ->
            addRoot(window.root)
        }

        return deduplicateNodes(out)
    }

    private fun extractVisibleTextNodesFromInstagramWindows(): List<ParsedTextNode> {
        val candidates = mutableListOf<WindowCandidate>()

        val activeRoot = rootInActiveWindow
        if (activeRoot != null && activeRoot.packageName?.toString() == INSTAGRAM_PACKAGE) {
            val raw = collectRawNodesFromRoot(activeRoot)
            candidates += WindowCandidate("active", activeRoot, raw, scoreInstagramWindow(raw))
        }

        windows?.forEachIndexed { index, window ->
            val root = window.root ?: return@forEachIndexed
            if (root.packageName?.toString() != INSTAGRAM_PACKAGE) return@forEachIndexed

            val raw = collectRawNodesFromRoot(root)
            candidates += WindowCandidate(
                label = "window-$index-${windowTypeName(window)}",
                root = root,
                rawNodes = raw,
                score = scoreInstagramWindow(raw)
            )
        }

        val best = candidates.maxByOrNull { it.score }
        val pickedRoot = when {
            best != null && best.score > 0 -> best.root
            activeRoot != null && activeRoot.packageName?.toString() == INSTAGRAM_PACKAGE -> activeRoot
            else -> candidates.firstOrNull()?.root
        } ?: return emptyList()

        return collectFilteredNodesFromRoot(
            root = pickedRoot,
            instagramMode = true,
            tiktokMode = false
        )
    }

    private fun collectFilteredNodesFromRoot(
        root: AccessibilityNodeInfo,
        instagramMode: Boolean,
        tiktokMode: Boolean
    ): List<ParsedTextNode> {
        val out = mutableListOf<ParsedTextNode>()

        fun dfs(node: AccessibilityNodeInfo?) {
            if (node == null) return

            val parsed = nodeToParsedTextNode(node) ?: run {
                for (i in 0 until node.childCount) {
                    val child = node.getChild(i)
                    dfs(child)
                }
                return
            }

            val rect = Rect(parsed.left, parsed.top, parsed.right, parsed.bottom)
            if (shouldKeepNode(node, parsed.displayText.orEmpty(), rect, root, instagramMode, tiktokMode)) {
                out += parsed
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                dfs(child)
            }
        }

        dfs(root)
        return deduplicateNodes(out)
    }

    private fun collectRawNodesFromRoot(root: AccessibilityNodeInfo): List<ParsedTextNode> {
        val out = mutableListOf<ParsedTextNode>()

        fun dfs(node: AccessibilityNodeInfo?) {
            if (node == null) return

            val parsed = nodeToParsedTextNode(node)
            if (parsed != null) {
                out += parsed
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                dfs(child)
            }
        }

        dfs(root)
        return deduplicateNodes(out)
    }

    private fun nodeToParsedTextNode(node: AccessibilityNodeInfo): ParsedTextNode? {
        val text = node.text?.toString()
        val contentDescription = node.contentDescription?.toString()
        val value = when {
            !text.isNullOrBlank() -> text.trim()
            !contentDescription.isNullOrBlank() -> contentDescription.trim()
            else -> null
        } ?: return null

        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (rect.width() <= 0 || rect.height() <= 0) {
            return null
        }

        return ParsedTextNode(
            packageName = node.packageName?.toString().orEmpty(),
            text = text,
            contentDescription = contentDescription,
            displayText = value,
            className = node.className?.toString(),
            viewIdResourceName = node.viewIdResourceName,
            left = rect.left,
            top = rect.top,
            right = rect.right,
            bottom = rect.bottom,
            approxTop = rect.top,
            isVisibleToUser = node.isVisibleToUser
        )
    }

    private fun shouldKeepNode(
        node: AccessibilityNodeInfo,
        value: String,
        rect: Rect,
        root: AccessibilityNodeInfo,
        instagramMode: Boolean,
        tiktokMode: Boolean
    ): Boolean {
        val className = node.className?.toString().orEmpty()
        val trimmed = value.trim()
        val lower = trimmed.lowercase()
        val viewId = node.viewIdResourceName.orEmpty()
        val isUserInputLike =
            className.contains("EditText", ignoreCase = true) ||
                viewId.contains("search", ignoreCase = true) ||
                viewId.contains("query", ignoreCase = true) ||
                viewId.contains("input", ignoreCase = true)
        val looksActionable = VisualTextOcrCandidateFilter.shouldAnalyze(trimmed)
        val rootRect = Rect().also { root.getBoundsInScreen(it) }
        val screenHeight = if (rootRect.height() > 0) rootRect.height() else rect.bottom
        val upperCutoff = if (instagramMode) {
            (screenHeight * 0.08f).toInt()
        } else if (!tiktokMode) {
            (screenHeight * 0.12f).toInt()
        } else {
            (screenHeight * 0.28f).toInt()
        }

        if (!node.isVisibleToUser) return false
        if (rect.bottom <= upperCutoff && !isUserInputLike && !looksActionable) return false
        if (trimmed.length == 1 && !trimmed[0].isLetterOrDigit() && trimmed[0] !in listOf('@', '#')) return false
        if (Regex(""".+님의 프로필$""").matches(trimmed)) return false
        if (Regex("""^[\u200E\u200F\u202A-\u202E]*댓글\s*\d+개$""").matches(trimmed)) return false

        if (instagramMode) {
            if (
                viewId.contains("news_tab") ||
                viewId.contains("creation_tab") ||
                viewId.contains("profile_tab") ||
                viewId.contains("comment_composer_left_image_view") ||
                viewId.contains("scrubber") ||
                viewId.contains("clips_author_profile_pic") ||
                viewId.contains("inline_follow_button") ||
                viewId.contains("media_reactions_sheet_recycler_view")
            ) return false

            if (
                trimmed == "활동" ||
                trimmed == "만들기" ||
                trimmed == "프로필" ||
                trimmed == "프로필 사진" ||
                trimmed == "대화 참여하기..." ||
                trimmed == "회원님의 생각을 남겨보세요." ||
                trimmed == "댓글 달기" ||
                trimmed == "저장" ||
                trimmed == "관심 없음" ||
                trimmed == "관심 있음" ||
                trimmed == "숨겨진 댓글 보기" ||
                trimmed == "캡션" ||
                trimmed == "릴스" ||
                trimmed.contains("님에게 댓글 추가") ||
                (trimmed.contains("님 외") && trimmed.contains("좋아합니다")) ||
                trimmed.contains("명이 좋아합니다")
            ) return false
        }

        if (tiktokMode) {
            if (
                lower == "알림" ||
                lower == "스티커" ||
                lower == "엄지척" ||
                lower == "아래" ||
                lower == "동영상" ||
                lower == "댓글" ||
                lower == "video" ||
                lower == "notification" ||
                lower == "sticker" ||
                lower.contains("멘션") ||
                lower.contains("말 한마디 해주세요") ||
                lower.contains("자세히") ||
                lower.startsWith("#") ||
                Regex("""^[\d,]+$""").matches(trimmed) ||
                Regex("""^\d{2}-\d{2}$""").matches(trimmed)
            ) return false

            if (
                viewId.contains("sticker", ignoreCase = true) ||
                viewId.contains("notification", ignoreCase = true)
            ) return false
        }

        if (
            lower.startsWith("comments.") ||
            lower == "sort comments" ||
            lower == "reply..." ||
            lower == "comment..." ||
            lower == "view reply" ||
            (lower.startsWith("view ") && lower.contains(" total replies"))
        ) return false

        if (
            lower.contains("like this comment") ||
            lower.contains("like this reply") ||
            lower.contains("dislike this comment") ||
            lower.contains("dislike this reply") ||
            lower == "reply" ||
            lower.contains("action menu") ||
            lower.contains("open camera") ||
            lower.contains("drag handle") ||
            lower.contains("video player") ||
            lower.contains("minutes") ||
            lower.contains("seconds") ||
            lower == "back" ||
            lower == "close" ||
            trimmed == "답글" ||
            trimmed.contains("정렬") ||
            trimmed == "뒤로" ||
            trimmed == "닫기"
        ) return false

        if (instagramMode) {
            if (lower == "검색 및 탐색하기" || lower == "검색" || lower == "search") return false
            if (lower == "공유" || lower == "share") return false
            if (lower == "리포스트") return false
            if (lower == "좋아요" || lower.endsWith("좋아요")) return false
            if (lower.contains("님이 만든 릴스입니다")) return false
            if (lower.contains("재생하거나 일시 중지하려면")) return false
            if (lower.contains("팔로우") || lower.contains("follow")) return false
            if (lower.contains("님에게 댓글 추가")) return false
            if (lower == "댓글 달기") return false
            if (lower == "저장") return false
            if (lower == "관심 없음") return false
            if (lower == "관심 있음") return false
            if (lower == "숨겨진 댓글 보기") return false
            if (lower == "캡션") return false
            if (lower == "릴스") return false
            if ((lower.contains("님 외") && lower.contains("좋아합니다")) || lower.contains("명이 좋아합니다")) return false
        }

        if (lower.endsWith(" likes") || lower.endsWith(" like")) return false
        if (trimmed.endsWith("좋아요")) return false
        if (className.contains("Button", ignoreCase = true)) return false

        return true
    }

    private fun scoreInstagramWindow(nodes: List<ParsedTextNode>): Int {
        var score = 0

        for (node in nodes) {
            val text = node.displayText.orEmpty().trim()
            val viewId = node.viewIdResourceName.orEmpty()

            if (text.endsWith("님의 프로필로 이동") || text.endsWith("님의 스토리 보기")) score += 3
            if (text.contains("답글") && text.contains("더 보기")) score += 3
            if (looksLikeDate(text)) score += 2
            if (looksLikeUsername(text)) score += 2
            if (looksLikeInstagramCombinedComment(text)) score += 4

            if (viewId.contains("news_tab") || viewId.contains("creation_tab") || viewId.contains("profile_tab")) score -= 6
            if (viewId.contains("comment_composer_left_image_view") || viewId.contains("scrubber")) score -= 6

            if (text == "회원님의 생각을 남겨보세요." || text == "대화 참여하기...") score -= 4
            if (text.contains("님이 만든 릴스입니다") || text.contains("재생하거나 일시 중지하려면")) score -= 4
            if (text == "검색 및 탐색하기") score -= 4
            if (text.contains("님에게 댓글 추가")) score -= 4
            if ((text.contains("님 외") && text.contains("좋아합니다")) || text.contains("명이 좋아합니다")) score -= 4
            if (text == "캡션" || text == "릴스") score -= 3
        }

        return score
    }

    private fun deduplicateNodes(nodes: List<ParsedTextNode>): List<ParsedTextNode> {
        val sorted = nodes.sortedWith(
            compareBy<ParsedTextNode> { it.top }
                .thenBy { it.left }
                .thenBy { priorityOf(it) }
        )

        val result = mutableListOf<ParsedTextNode>()

        for (node in sorted) {
            val index = result.indexOfFirst { existing ->
                existing.displayText == node.displayText &&
                        abs(existing.top - node.top) <= 8 &&
                        abs(existing.left - node.left) <= 120
            }

            if (index == -1) {
                result += node
            } else {
                val existing = result[index]
                if (priorityOf(node) < priorityOf(existing)) {
                    result[index] = node
                }
            }
        }

        return result
    }

    private fun priorityOf(node: ParsedTextNode): Int {
        val className = node.className.orEmpty()

        return when {
            node.text != null -> 0
            className.contains("TextView", ignoreCase = true) -> 1
            className.contains("ViewGroup", ignoreCase = true) -> 2
            className.contains("ImageView", ignoreCase = true) -> 3
            className.contains("Button", ignoreCase = true) -> 4
            else -> 5
        }
    }

    private fun looksLikeDate(text: String): Boolean {
        val trimmed = text.trim()
        return trimmed.endsWith("초 전") ||
            trimmed.endsWith("분 전") ||
            trimmed.endsWith("시간 전") ||
            trimmed.endsWith("일 전") ||
            trimmed.endsWith("주 전") ||
            Regex("""^\d+월\s*\d+일$""").matches(trimmed)
    }

    private fun looksLikeUsername(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.startsWith("@")) return true

        return !trimmed.contains(" ") &&
            trimmed.length in 3..30 &&
            trimmed.all { it.isLetterOrDigit() || it == '_' || it == '.' }
    }

    private fun looksLikeInstagramCombinedComment(text: String): Boolean {
        val trimmed = text.trim()
        val match = Regex("""^([A-Za-z0-9._]{3,30})\s+(.+)$""").find(trimmed) ?: return false
        val body = match.groupValues[2]
        return body.length >= 2 && !looksLikeDate(body)
    }

    private fun windowTypeName(window: AccessibilityWindowInfo): String {
        return when (window.type) {
            AccessibilityWindowInfo.TYPE_APPLICATION -> "app"
            AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "ime"
            AccessibilityWindowInfo.TYPE_SYSTEM -> "system"
            AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "overlay"
            else -> window.type.toString()
        }
    }

    private data class WindowCandidate(
        val label: String,
        val root: AccessibilityNodeInfo,
        val rawNodes: List<ParsedTextNode>,
        val score: Int
    )
}
