package com.example.youtubeparser

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.abs

class YoutubeAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "YTParserService"
        private const val YOUTUBE_PACKAGE = "com.google.android.youtube"
        private const val MIN_UPLOAD_INTERVAL_MS = 1200L
    }

    private var lastSnapshotSignature: String? = null
    private var lastUploadAt: Long = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val packageName = event.packageName?.toString() ?: return
        if (packageName != YOUTUBE_PACKAGE) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                val nodes = extractVisibleTextNodesFromCurrentWindow()
                val comments = YoutubeCommentExtractor.extractComments(nodes)

                Log.d(TAG, "parsed comment count = ${comments.size}")

                comments.forEach { comment ->
                    Log.d(
                        TAG,
                        "COMMENT=${comment.commentText} | BOUNDS=${comment.boundsInScreen}"
                    )
                }

                val snapshot = ParseSnapshot(
                    timestamp = System.currentTimeMillis(),
                    comments = comments
                )

                if (!shouldUpload(snapshot)) {
                    Log.d(TAG, "skip duplicate snapshot upload")
                    return
                }

                val savedFile = JsonFileStore.saveSnapshot(applicationContext, snapshot)

                Thread {
                    ServerUploader.uploadJsonFile(applicationContext, savedFile)
                }.start()
            }
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "service interrupted")
    }

    private fun extractVisibleTextNodesFromCurrentWindow(): List<ParsedTextNode> {
        val root = rootInActiveWindow ?: return emptyList()
        val out = mutableListOf<ParsedTextNode>()

        fun dfs(node: AccessibilityNodeInfo?) {
            if (node == null) return

            val text = node.text?.toString()
            val contentDescription = node.contentDescription?.toString()

            val value = when {
                !text.isNullOrBlank() -> text.trim()
                !contentDescription.isNullOrBlank() -> contentDescription.trim()
                else -> null
            }

            if (!value.isNullOrBlank()) {
                val rect = Rect()
                node.getBoundsInScreen(rect)

                if (rect.width() > 0 && rect.height() > 0 && shouldKeepNode(node, value, rect)) {
                    out += ParsedTextNode(
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
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                dfs(child)
                child?.recycle()
            }
        }

        dfs(root)
        return deduplicateNodes(out)
    }

    private fun shouldKeepNode(
        node: AccessibilityNodeInfo,
        value: String,
        rect: Rect
    ): Boolean {
        val className = node.className?.toString().orEmpty()
        val lower = value.lowercase()

        if (!node.isVisibleToUser) return false
        if (rect.bottom < 900) return false

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
            lower == "close"
        ) return false

        if (lower.endsWith(" likes") || lower.endsWith(" like")) return false
        if (className.contains("Button", ignoreCase = true)) return false

        return true
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
            className.contains("ViewGroup", ignoreCase = true) -> 1
            className.contains("TextView", ignoreCase = true) -> 1
            className.contains("Button", ignoreCase = true) -> 3
            else -> 2
        }
    }

    private fun shouldUpload(snapshot: ParseSnapshot): Boolean {
        if (snapshot.comments.isEmpty()) return false

        val signature = snapshot.comments.joinToString("||") { comment ->
            "${comment.commentText}|${comment.boundsInScreen.top}|${comment.boundsInScreen.left}"
        }
        val now = System.currentTimeMillis()

        if (signature == lastSnapshotSignature) {
            return false
        }

        if (now - lastUploadAt < MIN_UPLOAD_INTERVAL_MS) {
            return false
        }

        lastSnapshotSignature = signature
        lastUploadAt = now
        return true
    }
}
