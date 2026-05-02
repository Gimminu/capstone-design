package com.capstone.design.youtubeparser

import android.content.Context
import android.util.Log
import com.google.gson.GsonBuilder
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object JsonFileStore {

    private const val TAG = "JsonFileStore"
    private const val YOUTUBE_PACKAGE = "com.google.android.youtube"
    private const val INSTAGRAM_PACKAGE = "com.instagram.android"
    private const val TIKTOK_PACKAGE = "com.zhiliaoapp.musically"
    private const val TIKTOK_ALT_PACKAGE = "com.ss.android.ugc.trill"

    private val gson = GsonBuilder()
        .setPrettyPrinting()
        .create()

    fun saveSnapshot(
        context: Context,
        snapshot: ParseSnapshot,
        sourcePackage: String
    ): File {
        val normalizedSnapshot = snapshot.copy(
            comments = snapshot.comments
                .map { normalizeCommentForSave(it) }
                .filter { it.commentText.isNotBlank() }
        )

        val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
        val dir = File(baseDir, "parse_results")
        if (!dir.exists()) {
            dir.mkdirs()
        }

        val stamp = formatStamp(snapshot.timestamp)
        val prefix = when (sourcePackage) {
            YOUTUBE_PACKAGE -> "youtube_comments"
            INSTAGRAM_PACKAGE -> "instagram_comments"
            TIKTOK_PACKAGE, TIKTOK_ALT_PACKAGE -> "tiktok_comments"
            else -> "comments"
        }
        val file = File(dir, "${prefix}_$stamp.json")

        file.writeText(gson.toJson(normalizedSnapshot), Charsets.UTF_8)
        Log.d(TAG, "saved file = ${file.absolutePath}")

        return file
    }

    fun saveAnalysisResponse(
        context: Context,
        response: AndroidAnalysisResponse,
        sourcePackage: String
    ): File {
        val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
        val dir = File(baseDir, "analysis_results")
        if (!dir.exists()) {
            dir.mkdirs()
        }

        val stamp = formatStamp(response.timestamp)
        val prefix = when (sourcePackage) {
            YOUTUBE_PACKAGE -> "youtube_analysis"
            INSTAGRAM_PACKAGE -> "instagram_analysis"
            TIKTOK_PACKAGE, TIKTOK_ALT_PACKAGE -> "tiktok_analysis"
            else -> "analysis"
        }
        val file = File(dir, "${prefix}_$stamp.json")

        file.writeText(gson.toJson(response), Charsets.UTF_8)
        Log.d(TAG, "saved analysis file = ${file.absolutePath}")

        return file
    }

    private fun formatStamp(timestampMillis: Long): String {
        val safeTimestamp = timestampMillis.takeIf { it > 0L } ?: System.currentTimeMillis()
        return SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date(safeTimestamp))
    }

    private fun normalizeCommentForSave(comment: ParsedComment): ParsedComment {
        val original = comment.commentText.trim()
        val fullPattern = Regex("""^(.+?)님이\s*(.*?)\s*댓글을 달았습니다$""")
        val match = fullPattern.find(original)

        if (match != null) {
            val authorId = match.groupValues[1].trim()
            val cleanedComment = match.groupValues[2].trim()

            return comment.copy(
                commentText = cleanedComment,
                authorId = authorId.ifBlank { null }
            )
        }

        if (original.endsWith("댓글을 달았습니다")) {
            return comment.copy(
                commentText = original.removeSuffix("댓글을 달았습니다").trim()
            )
        }

        return comment
    }
}
