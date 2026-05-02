package com.capstone.design.youtubeparser

import android.content.Context
import android.util.Log
import com.google.gson.GsonBuilder
import java.net.HttpURLConnection
import java.net.URL

object AndroidAnalysisClient {

    private const val TAG = "AndroidAnalysisClient"
    private const val CONNECT_TIMEOUT_MS = 2500
    private const val READ_TIMEOUT_MS = 7000

    private val gson = GsonBuilder().create()

    fun analyzeSnapshot(context: Context, snapshot: ParseSnapshot): AndroidAnalysisAttempt {
        val url = AnalysisEndpointStore.resolveAnalyzeUrl(context)
        val startedAt = System.currentTimeMillis()
        val commentCount = snapshot.comments.size

        if (commentCount == 0) {
            return AndroidAnalysisAttempt(
                ok = true,
                url = url,
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
                output.write(gson.toJson(snapshot).toByteArray(Charsets.UTF_8))
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
                AndroidAnalysisAttempt(
                    ok = false,
                    url = url,
                    latencyMs = latencyMs,
                    commentCount = commentCount,
                    offensiveCount = 0,
                    filteredCount = 0,
                    error = "HTTP_$responseCode"
                )
            } else {
                val response = parseAndroidAnalysisResponse(responseText, commentCount)
                val offensiveCount = countActionableOffensiveResults(response)
                Log.d(
                    TAG,
                    "analysis ok url=$url comments=$commentCount actionableOffensive=$offensiveCount latencyMs=$latencyMs"
                )
                AndroidAnalysisAttempt(
                    ok = true,
                    url = url,
                    latencyMs = latencyMs,
                    commentCount = commentCount,
                    offensiveCount = offensiveCount,
                    filteredCount = response.filteredCount,
                    response = response
                )
            }
        } catch (error: Exception) {
            val latencyMs = System.currentTimeMillis() - startedAt
            Log.e(TAG, "analysis request failed url=$url", error)
            AndroidAnalysisAttempt(
                ok = false,
                url = url,
                latencyMs = latencyMs,
                commentCount = commentCount,
                offensiveCount = 0,
                filteredCount = 0,
                error = error.message?.takeIf { it.isNotBlank() }
                    ?: error.javaClass.simpleName
                    ?: "REQUEST_FAILED"
            )
        } finally {
            connection?.disconnect()
        }
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
