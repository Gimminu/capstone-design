package com.capstone.design.youtubeparser

import org.junit.Assert.assertEquals
import org.junit.Test

class AnalysisEndpointStoreTest {

    @Test
    fun resolveAnalyzeUrl_addsDefaultPortAndPathForBareHost() {
        val resolved = AnalysisEndpointStore.resolveAnalyzeUrl("100.95.209.72")

        assertEquals("http://100.95.209.72:8000/analyze_android", resolved)
    }

    @Test
    fun resolveAnalyzeUrl_keepsExplicitPortAndAddsPath() {
        val resolved = AnalysisEndpointStore.resolveAnalyzeUrl("100.95.209.72:9000")

        assertEquals("http://100.95.209.72:9000/analyze_android", resolved)
    }

    @Test
    fun resolveAnalyzeUrl_keepsFullAnalyzeUrl() {
        val resolved = AnalysisEndpointStore.resolveAnalyzeUrl(
            "http://127.0.0.1:8000/analyze_android"
        )

        assertEquals("http://127.0.0.1:8000/analyze_android", resolved)
    }

    @Test
    fun resolveAnalyzeUrl_appendsAnalyzePathToBaseUrl() {
        val resolved = AnalysisEndpointStore.resolveAnalyzeUrl("https://api.example.test")

        assertEquals("https://api.example.test/analyze_android", resolved)
    }

    @Test
    fun resolveAnalyzeUrl_usesDefaultWhenBlank() {
        val resolved = AnalysisEndpointStore.resolveAnalyzeUrl("   ")

        assertEquals("http://100.95.209.72:8000/analyze_android", resolved)
    }
}
