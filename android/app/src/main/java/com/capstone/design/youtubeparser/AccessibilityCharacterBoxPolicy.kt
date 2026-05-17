package com.capstone.design.youtubeparser

internal object AccessibilityCharacterBoxPolicy {
    private const val MAX_INPUT_TEXT_LENGTH = 96

    fun shouldRequest(
        rawText: String,
        displayText: String,
        className: String?,
        viewIdResourceName: String?
    ): Boolean {
        if (rawText.isBlank()) return false
        if (rawText != displayText) return false
        if (VisualTextOcrCandidateFilter.shouldAnalyze(rawText)) return true

        val isInputLike =
            className.orEmpty().contains("EditText", ignoreCase = true) ||
                viewIdResourceName.orEmpty().contains("search", ignoreCase = true) ||
                viewIdResourceName.orEmpty().contains("query", ignoreCase = true) ||
                viewIdResourceName.orEmpty().contains("input", ignoreCase = true)

        return isInputLike && rawText.length <= MAX_INPUT_TEXT_LENGTH
    }
}
