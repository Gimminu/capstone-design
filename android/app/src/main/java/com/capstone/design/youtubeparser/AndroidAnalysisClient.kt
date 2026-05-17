package com.capstone.design.youtubeparser

import android.content.Context
import android.util.Log
import com.google.gson.GsonBuilder
import java.net.HttpURLConnection
import java.net.URL
import java.util.LinkedHashMap

private data class CachedAnalysisResult(
    val result: AndroidAnalysisResultItem,
    val expiresAt: Long
)

private data class PendingComment(
    val originalIndex: Int,
    val comment: ParsedComment
)

private data class AndroidAnalysisRequest(
    val timestamp: Long,
    val sensitivity: Int,
    val comments: List<ParsedComment>
)

object AndroidAnalysisClient {

    private const val TAG = "AndroidAnalysisClient"
    private const val CONNECT_TIMEOUT_MS = 500
    private const val READ_TIMEOUT_MS = 1200
    private const val RESPONSE_CACHE_LIMIT = 512
    private const val RESPONSE_CACHE_TTL_MS = 30_000L
    private const val ACCESSIBILITY_LOOKAHEAD_PREFIX = "android-accessibility-lookahead:"

    private val gson = GsonBuilder().create()
    private val responseCache = object : LinkedHashMap<String, CachedAnalysisResult>(
        RESPONSE_CACHE_LIMIT,
        0.75f,
        true
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedAnalysisResult>?): Boolean {
            return size > RESPONSE_CACHE_LIMIT
        }
    }

    fun clearCache() {
        synchronized(responseCache) {
            responseCache.clear()
        }
    }

    fun analyzeSnapshot(context: Context, snapshot: ParseSnapshot): AndroidAnalysisAttempt {
        val url = AnalysisEndpointStore.resolveAnalyzeUrl(context)
        val sensitivity = AnalysisSensitivityStore.get(context)
        val startedAt = System.currentTimeMillis()
        val commentCount = snapshot.comments.size

        if (commentCount == 0 || sensitivity <= 0) {
            return AndroidAnalysisAttempt(
                ok = true,
                url = url,
                sensitivity = sensitivity,
                latencyMs = 0L,
                commentCount = 0,
                offensiveCount = 0,
                filteredCount = 0,
                response = AndroidAnalysisResponse(
                    timestamp = snapshot.timestamp,
                    filteredCount = 0,
                    results = emptyList()
                )
            )
        }

        val cachedResults = arrayOfNulls<AndroidAnalysisResultItem>(snapshot.comments.size)
        val pendingEntries = mutableListOf<PendingComment>()

        snapshot.comments.forEachIndexed { index, comment ->
            val cached = getCachedResult(comment, startedAt, sensitivity)
            if (cached != null) {
                cachedResults[index] = cached
            } else {
                pendingEntries += PendingComment(index, comment)
            }
        }

        if (pendingEntries.isEmpty()) {
            val response = AndroidAnalysisResponse(
                timestamp = snapshot.timestamp,
                filteredCount = 0,
                results = cachedResults.filterNotNull()
            )
            return AndroidAnalysisAttempt(
                ok = true,
                url = url,
                sensitivity = sensitivity,
                latencyMs = System.currentTimeMillis() - startedAt,
                commentCount = commentCount,
                offensiveCount = countActionableOffensiveResults(response),
                filteredCount = 0,
                response = response,
                actionableSamples = buildActionableSamples(response)
            )
        }

        val requestEntries = pendingEntries.distinctBy { entry ->
            cacheKey(entry.comment, sensitivity)
        }
        val requestSnapshot = AndroidAnalysisRequest(
            timestamp = snapshot.timestamp,
            sensitivity = sensitivity,
            comments = requestEntries.map { it.comment }
        )

        var connection: HttpURLConnection? = null

        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
                doInput = true
                useCaches = false
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
            }

            connection.outputStream.use { output ->
                output.write(gson.toJson(requestSnapshot).toByteArray(Charsets.UTF_8))
                output.flush()
            }

            val responseCode = connection.responseCode
            val responseText = try {
                connection.inputStream.bufferedReader().use { it.readText() }
            } catch (_: Exception) {
                connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            }

            val latencyMs = System.currentTimeMillis() - startedAt
            if (responseCode !in 200..299) {
                Log.w(TAG, "analysis failed url=$url responseCode=$responseCode body=$responseText")
                buildCachedFallbackAttempt(
                    url = url,
                    sensitivity = sensitivity,
                    startedAt = startedAt,
                    commentCount = commentCount,
                    timestamp = snapshot.timestamp,
                    cachedResults = cachedResults,
                    error = "HTTP_$responseCode"
                ) ?: AndroidAnalysisAttempt(
                    ok = false,
                    url = url,
                    sensitivity = sensitivity,
                    latencyMs = latencyMs,
                    commentCount = commentCount,
                    offensiveCount = 0,
                    filteredCount = 0,
                    error = "HTTP_$responseCode"
                )
            } else {
                val response = parseAndroidAnalysisResponse(responseText, requestEntries.size)
                val mergedResponse = mergeCachedAndFreshResults(
                    timestamp = snapshot.timestamp,
                    cachedResults = cachedResults,
                    pendingEntries = pendingEntries,
                    requestEntries = requestEntries,
                    freshResponse = response
                )
                cacheFreshResults(requestEntries, response.results, sensitivity)
                val offensiveCount = countActionableOffensiveResults(mergedResponse)
                val actionableSamples = buildActionableSamples(mergedResponse)
                Log.d(
                    TAG,
                    "analysis ok url=$url comments=$commentCount pending=${pendingEntries.size} " +
                        "requested=${requestEntries.size} cacheHits=${commentCount - pendingEntries.size} actionableOffensive=$offensiveCount " +
                        "latencyMs=$latencyMs"
                )
                AndroidAnalysisAttempt(
                    ok = true,
                    url = url,
                    sensitivity = sensitivity,
                    latencyMs = latencyMs,
                    commentCount = commentCount,
                    offensiveCount = offensiveCount,
                    filteredCount = response.filteredCount,
                    response = mergedResponse,
                    actionableSamples = actionableSamples
                )
            }
        } catch (error: Exception) {
            val latencyMs = System.currentTimeMillis() - startedAt
            val errorCode = error.message?.takeIf { it.isNotBlank() }
                ?: error.javaClass.simpleName
                ?: "REQUEST_FAILED"
            Log.e(TAG, "analysis request failed url=$url", error)
            buildCachedFallbackAttempt(
                url = url,
                sensitivity = sensitivity,
                startedAt = startedAt,
                commentCount = commentCount,
                timestamp = snapshot.timestamp,
                cachedResults = cachedResults,
                error = errorCode
            ) ?: AndroidAnalysisAttempt(
                ok = false,
                url = url,
                sensitivity = sensitivity,
                latencyMs = latencyMs,
                commentCount = commentCount,
                offensiveCount = 0,
                filteredCount = 0,
                error = errorCode
            )
        } finally {
            connection?.disconnect()
        }
    }

    fun analyzeSnapshotFromCache(context: Context, snapshot: ParseSnapshot): AndroidAnalysisAttempt {
        val url = AnalysisEndpointStore.resolveAnalyzeUrl(context)
        val sensitivity = AnalysisSensitivityStore.get(context)
        val startedAt = System.currentTimeMillis()
        val commentCount = snapshot.comments.size

        if (commentCount == 0 || sensitivity <= 0) {
            return AndroidAnalysisAttempt(
                ok = true,
                url = url,
                sensitivity = sensitivity,
                latencyMs = 0L,
                commentCount = 0,
                offensiveCount = 0,
                filteredCount = 0,
                response = AndroidAnalysisResponse(
                    timestamp = snapshot.timestamp,
                    filteredCount = 0,
                    results = emptyList()
                )
            )
        }

        val cachedResults = snapshot.comments.mapNotNull { comment ->
            getCachedResult(comment, startedAt, sensitivity)
        }
        val response = AndroidAnalysisResponse(
            timestamp = snapshot.timestamp,
            filteredCount = 0,
            results = cachedResults
        )

        return AndroidAnalysisAttempt(
            ok = true,
            url = url,
            sensitivity = sensitivity,
            latencyMs = System.currentTimeMillis() - startedAt,
            commentCount = commentCount,
            offensiveCount = countActionableOffensiveResults(response),
            filteredCount = 0,
            response = response,
            actionableSamples = buildActionableSamples(response)
        )
    }

    private fun buildCachedFallbackAttempt(
        url: String,
        sensitivity: Int,
        startedAt: Long,
        commentCount: Int,
        timestamp: Long,
        cachedResults: Array<AndroidAnalysisResultItem?>,
        error: String
    ): AndroidAnalysisAttempt? {
        val cached = cachedResults.filterNotNull()
        if (cached.isEmpty()) return null

        val response = AndroidAnalysisResponse(
            timestamp = timestamp,
            filteredCount = 0,
            results = cached
        )
        val offensiveCount = countActionableOffensiveResults(response)
        Log.w(
            TAG,
            "analysis degraded; using cached fallback url=$url comments=$commentCount " +
                "cached=${cached.size} actionableOffensive=$offensiveCount error=$error"
        )

        return AndroidAnalysisAttempt(
            ok = true,
            url = url,
            sensitivity = sensitivity,
            latencyMs = System.currentTimeMillis() - startedAt,
            commentCount = commentCount,
            offensiveCount = offensiveCount,
            filteredCount = 0,
            response = response,
            actionableSamples = buildActionableSamples(response),
            error = "CACHE_FALLBACK:$error"
        )
    }

    private fun mergeCachedAndFreshResults(
        timestamp: Long,
        cachedResults: Array<AndroidAnalysisResultItem?>,
        pendingEntries: List<PendingComment>,
        requestEntries: List<PendingComment>,
        freshResponse: AndroidAnalysisResponse
    ): AndroidAnalysisResponse {
        val matchedFreshResults = matchFreshResultsToComments(
            comments = requestEntries.map { it.comment },
            results = freshResponse.results
        )
        val freshResultsByKey = requestEntries
            .mapIndexedNotNull { index, entry ->
                val result = matchedFreshResults.getOrNull(index) ?: return@mapIndexedNotNull null
                cacheKey(entry.comment) to result.copy(
                    original = entry.comment.commentText,
                    boundsInScreen = entry.comment.boundsInScreen,
                    authorId = entry.comment.authorId
                )
            }
            .toMap()
        val merged = cachedResults.copyOf()

        pendingEntries.forEach { pending ->
            val comment = pending.comment
            val result = freshResultsByKey[cacheKey(comment)]
            if (result != null) {
                merged[pending.originalIndex] = result
            }
        }

        return AndroidAnalysisResponse(
            timestamp = timestamp,
            filteredCount = freshResponse.filteredCount,
            results = merged.filterNotNull()
        )
    }

    private fun getCachedResult(comment: ParsedComment, now: Long, sensitivity: Int): AndroidAnalysisResultItem? {
        return synchronized(responseCache) {
            for (key in cacheKeysForComment(comment, sensitivity)) {
                val cached = responseCache[key] ?: continue
                if (cached.expiresAt <= now) {
                    responseCache.remove(key)
                    continue
                }
                return@synchronized cached.result.copy(
                    boundsInScreen = comment.boundsInScreen,
                    authorId = comment.authorId
                )
            }
            null
        }
    }

    private fun cacheFreshResults(
        requestEntries: List<PendingComment>,
        results: List<AndroidAnalysisResultItem>,
        sensitivity: Int
    ) {
        val expiresAt = System.currentTimeMillis() + RESPONSE_CACHE_TTL_MS
        val matchedFreshResults = matchFreshResultsToComments(
            comments = requestEntries.map { it.comment },
            results = results
        )
        synchronized(responseCache) {
            requestEntries.forEachIndexed { index, entry ->
                val result = matchedFreshResults.getOrNull(index) ?: return@forEachIndexed
                val comment = entry.comment
                cacheKeysForComment(comment, sensitivity).forEach { key ->
                    responseCache[key] = CachedAnalysisResult(
                        result.copy(
                            original = comment.commentText,
                            boundsInScreen = comment.boundsInScreen,
                            authorId = comment.authorId
                        ),
                        expiresAt
                    )
                }
            }
        }
    }

    internal fun matchFreshResultsToComments(
        comments: List<ParsedComment>,
        results: List<AndroidAnalysisResultItem>
    ): List<AndroidAnalysisResultItem?> {
        if (comments.isEmpty()) return emptyList()
        if (results.isEmpty()) return List(comments.size) { null }

        val exactSourceResults = results.groupBy { result ->
            cacheKey(
                text = result.original,
                sourceKey = normalizeSourceCacheKey(result.authorId)
            )
        }
        val textOnlyResults = results.groupBy { result ->
            cacheKey(text = result.original)
        }

        return comments.map { comment ->
            exactSourceResults[cacheKey(comment)]?.firstOrNull()
                ?: textOnlyResults[cacheKey(comment.commentText)]?.firstOrNull()
        }
    }

    private fun cacheKey(comment: ParsedComment, sensitivity: Int? = null): String {
        return cacheKey(
            text = comment.commentText,
            sensitivity = sensitivity,
            sourceKey = normalizeSourceCacheKey(comment.authorId)
        )
    }

    internal fun cacheKeysForComment(comment: ParsedComment, sensitivity: Int? = null): List<String> {
        val exactKey = cacheKey(comment, sensitivity)
        val textOnlyKey = if (shouldUseTextOnlyCacheAlias(comment)) {
            cacheKey(comment.commentText, sensitivity)
        } else {
            ""
        }

        return listOf(exactKey, textOnlyKey)
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun cacheKey(text: String, sensitivity: Int? = null): String {
        return cacheKey(text = text, sensitivity = sensitivity, sourceKey = "")
    }

    private fun cacheKey(text: String, sensitivity: Int? = null, sourceKey: String): String {
        val normalizedText = text.replace(Regex("\\s+"), " ").trim().lowercase()
        val normalizedSource = sourceKey.replace(Regex("\\s+"), " ").trim().lowercase()
        val body = if (normalizedSource.isBlank()) {
            normalizedText
        } else {
            "$normalizedSource::$normalizedText"
        }
        return if (sensitivity == null) {
            body
        } else {
            "$sensitivity::$body"
        }
    }

    private fun normalizeSourceCacheKey(authorId: String?): String {
        val value = authorId?.trim().orEmpty()
        if (value.isBlank()) return ""
        return value.removePrefix(ACCESSIBILITY_LOOKAHEAD_PREFIX)
    }

    private fun shouldUseTextOnlyCacheAlias(comment: ParsedComment): Boolean {
        val normalizedText = comment.commentText.replace(Regex("\\s+"), " ").trim()
        if (normalizedText.length < 2) return false

        val sourceKey = normalizeSourceCacheKey(comment.authorId)
        if (sourceKey.isBlank()) return false

        return sourceKey == "android-accessibility:youtube_user_input" ||
            sourceKey == "android-accessibility:youtube_title" ||
            sourceKey == "android-accessibility:youtube_shorts_title" ||
            sourceKey == "youtube-composite-description" ||
            sourceKey.startsWith("android-accessibility-comment:youtube") ||
            sourceKey.startsWith("android-accessibility-char-range:") ||
            sourceKey.startsWith("youtube-visual-range:") ||
            sourceKey.startsWith("ocr:youtube-composite-card:") ||
            sourceKey.startsWith("ocr:youtube-visible-band:") ||
            sourceKey.startsWith("ocr:youtube-semantic-card:")
    }

    internal fun parseAndroidAnalysisResponse(
        responseText: String,
        expectedCommentCount: Int
    ): AndroidAnalysisResponse {
        val response = try {
            gson.fromJson(responseText, AndroidAnalysisResponse::class.java)
        } catch (error: Exception) {
            throw IllegalArgumentException("INVALID_RESPONSE_JSON", error)
        } ?: throw IllegalArgumentException("EMPTY_RESPONSE")

        validateAndroidAnalysisResponse(response, expectedCommentCount)
        return response
    }

    internal fun countActionableOffensiveResults(response: AndroidAnalysisResponse): Int {
        return response.results.count { item ->
            item.isOffensive && item.evidenceSpans.isNotEmpty()
        }
    }

    private fun buildActionableSamples(response: AndroidAnalysisResponse): List<String> {
        return response.results
            .asSequence()
            .filter { it.isOffensive && it.evidenceSpans.isNotEmpty() }
            .map { item ->
                val labels = listOfNotNull(
                    "욕설".takeIf { item.isProfane },
                    "공격".takeIf { item.isToxic },
                    "혐오".takeIf { item.isHate }
                ).joinToString("/")
                val evidence = item.evidenceSpans
                    .take(3)
                    .joinToString(", ") { it.text }
                val text = item.original.replace(Regex("\\s+"), " ").take(80)
                "$labels: $evidence :: $text"
            }
            .take(3)
            .toList()
    }

    @Suppress("SENSELESS_COMPARISON")
    private fun validateAndroidAnalysisResponse(
        response: AndroidAnalysisResponse,
        expectedCommentCount: Int
    ) {
        if (response.results == null) {
            throw IllegalArgumentException("INVALID_RESPONSE_RESULTS")
        }

        if (response.filteredCount < 0) {
            throw IllegalArgumentException("INVALID_RESPONSE_FILTERED_COUNT")
        }

        if (response.results.size + response.filteredCount > expectedCommentCount) {
            throw IllegalArgumentException("INVALID_RESPONSE_COUNT")
        }

        response.results.forEachIndexed { index, item ->
            if (item == null) {
                throw IllegalArgumentException("INVALID_RESPONSE_ITEM_$index")
            }
            validateAndroidAnalysisItem(item, index)
        }
    }

    @Suppress("SENSELESS_COMPARISON")
    private fun validateAndroidAnalysisItem(item: AndroidAnalysisResultItem, index: Int) {
        if (item.original == null) {
            throw IllegalArgumentException("INVALID_RESPONSE_ORIGINAL_$index")
        }
        if (item.boundsInScreen == null) {
            throw IllegalArgumentException("INVALID_RESPONSE_BOUNDS_$index")
        }
        if (item.scores == null) {
            throw IllegalArgumentException("INVALID_RESPONSE_SCORES_$index")
        }
        if (item.evidenceSpans == null) {
            throw IllegalArgumentException("INVALID_RESPONSE_SPANS_$index")
        }

        val textLength = item.original.length
        item.evidenceSpans.forEachIndexed { spanIndex, span ->
            if (span == null) {
                throw IllegalArgumentException("INVALID_RESPONSE_SPAN_${index}_$spanIndex")
            }
            if (span.start < 0 || span.end < span.start || span.end > textLength) {
                throw IllegalArgumentException("INVALID_RESPONSE_SPAN_RANGE_${index}_$spanIndex")
            }
        }
    }
}
