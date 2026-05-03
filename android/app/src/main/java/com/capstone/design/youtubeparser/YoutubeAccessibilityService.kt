package com.capstone.design.youtubeparser

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlin.math.abs

class YoutubeAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "YTParserService"
        private const val YOUTUBE_PACKAGE = "com.google.android.youtube"
        private const val INSTAGRAM_PACKAGE = "com.instagram.android"
        private const val TIKTOK_PACKAGE = "com.zhiliaoapp.musically"
        private const val TIKTOK_ALT_PACKAGE = "com.ss.android.ugc.trill"
        private const val MIN_UPLOAD_INTERVAL_MS = 1500L
        private const val PARSE_DELAY_SCROLL_MS = 120L
        private const val PARSE_DELAY_CONTENT_MS = 160L
        private const val PARSE_DELAY_WINDOW_MS = 240L
        private const val RETRY_AFTER_IN_FLIGHT_MS = 90L
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
    private var parseScheduled = false

    private val parseRunnable = Runnable {
        parseScheduled = false
        parseAndUploadCurrentWindow()
        if (followUpParseRequested && !analysisInFlight) {
            followUpParseRequested = false
            scheduleParse(RETRY_AFTER_IN_FLIGHT_MS)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val packageName = event.packageName?.toString() ?: return
        if (
            packageName != YOUTUBE_PACKAGE &&
            packageName != INSTAGRAM_PACKAGE &&
            packageName != TIKTOK_PACKAGE &&
            packageName != TIKTOK_ALT_PACKAGE
        ) return

        lastObservedPackage = packageName

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                if (shouldClearOverlayImmediately(event.eventType)) {
                    clearMaskOverlay()
                }

                val delayMs = when (event.eventType) {
                    AccessibilityEvent.TYPE_VIEW_SCROLLED -> PARSE_DELAY_SCROLL_MS
                    AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                    AccessibilityEvent.TYPE_WINDOWS_CHANGED -> PARSE_DELAY_WINDOW_MS
                    else -> PARSE_DELAY_CONTENT_MS
                }

                scheduleParse(delayMs)
            }
        }
    }

    override fun onInterrupt() {
        cancelScheduledParse()
        maskOverlayController.clear()
        Log.d(TAG, "service interrupted")
    }

    override fun onDestroy() {
        cancelScheduledParse()
        maskOverlayController.clear()
        super.onDestroy()
    }

    private fun scheduleParse(delayMs: Long) {
        if (parseScheduled) {
            followUpParseRequested = true
            return
        }

        parseScheduled = true
        handler.postDelayed(parseRunnable, delayMs)
    }

    private fun cancelScheduledParse() {
        handler.removeCallbacks(parseRunnable)
        parseScheduled = false
        followUpParseRequested = false
    }

    private fun parseAndUploadCurrentWindow() {
        val currentPackage = lastObservedPackage ?: run {
            Log.d(TAG, "lastObservedPackage is null")
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

        val comments = when (currentPackage) {
            YOUTUBE_PACKAGE -> YoutubeAnalysisTargetExtractor.extractTargets(nodes)
            INSTAGRAM_PACKAGE -> InstagramCommentExtractor.extractComments(nodes)
            TIKTOK_PACKAGE, TIKTOK_ALT_PACKAGE -> TiktokCommentExtractor.extractComments(nodes)
            else -> emptyList()
        }

        Log.d(TAG, "package=$currentPackage parsed analysis target count=${comments.size}")

        if (currentPackage == YOUTUBE_PACKAGE && comments.size <= 3) {
            nodes.take(80).forEachIndexed { index, node ->
                Log.d(
                    TAG,
                    "YT_RAW[$index] text=${node.displayText} | cls=${node.className} | id=${node.viewIdResourceName} " +
                        "| bounds=${node.left},${node.top},${node.right},${node.bottom}"
                )
            }
        }

        if (currentPackage == INSTAGRAM_PACKAGE && comments.isEmpty()) {
            nodes.take(40).forEachIndexed { index, node ->
                Log.d(
                    TAG,
                    "IG_RAW[$index] text=${node.displayText} | cls=${node.className} | id=${node.viewIdResourceName}"
                )
            }
        }

        if ((currentPackage == TIKTOK_PACKAGE || currentPackage == TIKTOK_ALT_PACKAGE) && comments.isEmpty()) {
            nodes.take(40).forEachIndexed { index, node ->
                Log.d(
                    TAG,
                    "TT_RAW[$index] text=${node.displayText} | cls=${node.className} | id=${node.viewIdResourceName}"
                )
            }
        }

        if (comments.isEmpty()) {
            clearMaskOverlay()
            return
        }

        val signature = buildString {
            append(currentPackage)
            append("||")
            append(
                comments.joinToString("||") {
                    "${it.commentText}|${it.boundsInScreen.top}|${it.boundsInScreen.left}|${it.authorId.orEmpty()}"
                }
            )
        }
        val now = System.currentTimeMillis()

        if (signature == lastSnapshotSignature) {
            Log.d(TAG, "skip duplicate snapshot")
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
                        scheduleParse(RETRY_AFTER_IN_FLIGHT_MS)
                    }
                }
            }

            try {
                val analysis = AndroidAnalysisClient
                    .analyzeSnapshot(applicationContext, snapshot)
                    .copy(packageName = currentPackage)
                analysisForOverlay = analysis
                handler.post {
                    updateMaskOverlay(currentPackage, analysis, snapshotOverlayRevision)
                }
                AnalysisDiagnosticsStore.saveAttempt(applicationContext, analysis)

                analysis.response?.let { response ->
                    JsonFileStore.saveAnalysisResponse(applicationContext, response, currentPackage)
                }

                // Masking must not be blocked by the optional upload channel.
                releaseAnalysisGate()

                val uploadOk = savedFile?.let {
                    ServerUploader.uploadJsonFile(applicationContext, it)
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
                        updateMaskOverlay(currentPackage, null, snapshotOverlayRevision)
                    }
                }
                releaseAnalysisGate()
            }
        }.start()
    }

    private fun updateMaskOverlay(
        currentPackage: String,
        analysis: AndroidAnalysisAttempt?,
        snapshotOverlayRevision: Long
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
            maskOverlayController.clear()
            return
        }

        if (currentPackage == YOUTUBE_PACKAGE && analysis?.ok == true) {
            Log.d(
                TAG,
                "render mask overlay package=$currentPackage results=${analysis.response?.results?.size ?: 0}"
            )
            maskOverlayController.render(analysis.response)
        } else {
            Log.d(
                TAG,
                "clear mask overlay package=$currentPackage analysisOk=${analysis?.ok}"
            )
            maskOverlayController.clear()
        }
    }

    private fun clearMaskOverlay() {
        overlayRevision += 1
        lastSnapshotSignature = null
        maskOverlayController.clear()
    }

    private fun shouldClearOverlayImmediately(eventType: Int): Boolean {
        return eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED ||
            eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
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
        if (rect.bottom <= upperCutoff) return false
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
