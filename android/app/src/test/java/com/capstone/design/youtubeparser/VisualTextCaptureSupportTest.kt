package com.capstone.design.youtubeparser

import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualTextCaptureSupportTest {

    @Test
    fun inspect_rejectsDevicesBelowAndroid11() {
        val state = VisualTextCaptureSupport.inspect(
            sdkInt = Build.VERSION_CODES.Q,
            capabilities = AccessibilityServiceInfo.CAPABILITY_CAN_TAKE_SCREENSHOT
        )

        assertFalse(state.supported)
        assertFalse(state.hasScreenshotCapability)
        assertEquals(VisualTextCaptureSupport.REASON_API_BELOW_30, state.reason)
    }

    @Test
    fun inspect_acceptsAndroid11PlusWithScreenshotCapability() {
        val state = VisualTextCaptureSupport.inspect(
            sdkInt = Build.VERSION_CODES.R,
            capabilities = AccessibilityServiceInfo.CAPABILITY_CAN_TAKE_SCREENSHOT
        )

        assertTrue(state.supported)
        assertTrue(state.hasScreenshotCapability)
        assertEquals(VisualTextCaptureSupport.REASON_READY, state.reason)
    }

    @Test
    fun inspect_rejectsAndroid11PlusWithoutScreenshotCapability() {
        val state = VisualTextCaptureSupport.inspect(
            sdkInt = Build.VERSION_CODES.R,
            capabilities = AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT
        )

        assertFalse(state.supported)
        assertFalse(state.hasScreenshotCapability)
        assertEquals(VisualTextCaptureSupport.REASON_SCREENSHOT_CAPABILITY_MISSING, state.reason)
    }
}
