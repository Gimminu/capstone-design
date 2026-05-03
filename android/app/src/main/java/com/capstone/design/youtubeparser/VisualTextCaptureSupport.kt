package com.capstone.design.youtubeparser

import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Build

data class VisualTextCaptureState(
    val supported: Boolean,
    val sdkInt: Int,
    val hasScreenshotCapability: Boolean,
    val reason: String
)

object VisualTextCaptureSupport {
    const val REASON_READY = "ready"
    const val REASON_API_BELOW_30 = "api_below_30"
    const val REASON_SERVICE_NOT_CONNECTED = "service_not_connected"
    const val REASON_SCREENSHOT_CAPABILITY_MISSING = "screenshot_capability_missing"

    fun inspect(
        serviceInfo: AccessibilityServiceInfo?,
        sdkInt: Int = Build.VERSION.SDK_INT
    ): VisualTextCaptureState {
        if (sdkInt < Build.VERSION_CODES.R) {
            return VisualTextCaptureState(
                supported = false,
                sdkInt = sdkInt,
                hasScreenshotCapability = false,
                reason = REASON_API_BELOW_30
            )
        }

        if (serviceInfo == null) {
            return VisualTextCaptureState(
                supported = false,
                sdkInt = sdkInt,
                hasScreenshotCapability = false,
                reason = REASON_SERVICE_NOT_CONNECTED
            )
        }

        return inspect(
            sdkInt = sdkInt,
            capabilities = serviceInfo.capabilities
        )
    }

    fun inspect(
        sdkInt: Int,
        capabilities: Int
    ): VisualTextCaptureState {
        if (sdkInt < Build.VERSION_CODES.R) {
            return VisualTextCaptureState(
                supported = false,
                sdkInt = sdkInt,
                hasScreenshotCapability = false,
                reason = REASON_API_BELOW_30
            )
        }

        val hasScreenshotCapability =
            capabilities and AccessibilityServiceInfo.CAPABILITY_CAN_TAKE_SCREENSHOT != 0

        return VisualTextCaptureState(
            supported = hasScreenshotCapability,
            sdkInt = sdkInt,
            hasScreenshotCapability = hasScreenshotCapability,
            reason = if (hasScreenshotCapability) {
                REASON_READY
            } else {
                REASON_SCREENSHOT_CAPABILITY_MISSING
            }
        )
    }
}
