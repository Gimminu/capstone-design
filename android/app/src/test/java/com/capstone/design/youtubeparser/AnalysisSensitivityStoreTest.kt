package com.capstone.design.youtubeparser

import org.junit.Assert.assertEquals
import org.junit.Test

class AnalysisSensitivityStoreTest {

    @Test
    fun clamp_keepsSensitivityInsideProductRange() {
        assertEquals(0, AnalysisSensitivityStore.clamp(-10))
        assertEquals(0, AnalysisSensitivityStore.clamp(0))
        assertEquals(60, AnalysisSensitivityStore.clamp(60))
        assertEquals(100, AnalysisSensitivityStore.clamp(140))
    }
}
