package com.capstone.design.youtubeparser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityCharacterBoxPolicyTest {

    @Test
    fun shouldRequest_forActionableText() {
        assertTrue(
            AccessibilityCharacterBoxPolicy.shouldRequest(
                rawText = "What is Tlqkf?",
                displayText = "What is Tlqkf?",
                className = "android.widget.TextView",
                viewIdResourceName = null
            )
        )
    }

    @Test
    fun shouldRequest_forInputLikeText() {
        assertTrue(
            AccessibilityCharacterBoxPolicy.shouldRequest(
                rawText = "ordinary query",
                displayText = "ordinary query",
                className = "android.widget.EditText",
                viewIdResourceName = "com.google.android.youtube:id/search_edit_text"
            )
        )
    }

    @Test
    fun shouldSkip_forCleanNonInputText() {
        assertFalse(
            AccessibilityCharacterBoxPolicy.shouldRequest(
                rawText = "Contemporary Korean Slang",
                displayText = "Contemporary Korean Slang",
                className = "android.widget.TextView",
                viewIdResourceName = null
            )
        )
    }

    @Test
    fun shouldSkip_whenDisplayedTextCameFromContentDescription() {
        assertFalse(
            AccessibilityCharacterBoxPolicy.shouldRequest(
                rawText = "clean raw text",
                displayText = "image description",
                className = "android.view.View",
                viewIdResourceName = null
            )
        )
    }
}
