package com.capstone.design.youtubeparser

object CandidateRoutingPolicy {
    private const val YOUTUBE_PACKAGE = "com.google.android.youtube"
    private const val YOUTUBE_USER_INPUT_AUTHOR_ID = "android-accessibility:youtube_user_input"
    private const val YOUTUBE_TITLE_AUTHOR_ID = "android-accessibility:youtube_title"
    private const val YOUTUBE_SHORTS_TITLE_AUTHOR_ID = "android-accessibility:youtube_shorts_title"
    private const val ACCESSIBILITY_COMMENT_PREFIX = "android-accessibility-comment:"
    private const val ACCESSIBILITY_LOOKAHEAD_PREFIX = "android-accessibility-lookahead:"

    fun routeFor(
        packageName: String,
        sourceId: String?,
        source: CandidateSource,
        role: CandidateRole
    ): CandidateRoute {
        val rawSourceId = sourceId?.trim().orEmpty()
        val baseSourceId = rawSourceId.removePrefix(ACCESSIBILITY_LOOKAHEAD_PREFIX)
        val isLookahead = rawSourceId.startsWith(ACCESSIBILITY_LOOKAHEAD_PREFIX)

        if (isLookahead) {
            return CandidateRoute(
                surface = surfaceFor(packageName, baseSourceId, role),
                geometryPolicy = CandidateGeometryPolicy.ACCESSIBILITY_LOOKAHEAD,
                renderPolicy = CandidateRenderPolicy.CACHE_ONLY,
                reason = "mounted-near-viewport-cache-only"
            )
        }

        if (baseSourceId == YOUTUBE_USER_INPUT_AUTHOR_ID) {
            return CandidateRoute(
                surface = CandidateSurface.YOUTUBE_SEARCH_INPUT,
                geometryPolicy = CandidateGeometryPolicy.ACCESSIBILITY_EXACT,
                renderPolicy = CandidateRenderPolicy.DIRECT_OVERLAY,
                reason = "youtube-search-input-bounds"
            )
        }

        if (baseSourceId == YOUTUBE_TITLE_AUTHOR_ID) {
            return CandidateRoute(
                surface = CandidateSurface.YOUTUBE_TITLE,
                geometryPolicy = CandidateGeometryPolicy.ACCESSIBILITY_EXACT,
                renderPolicy = CandidateRenderPolicy.DIRECT_OVERLAY,
                reason = "youtube-visible-title-bounds"
            )
        }

        if (baseSourceId == YOUTUBE_SHORTS_TITLE_AUTHOR_ID) {
            return CandidateRoute(
                surface = CandidateSurface.YOUTUBE_SHORTS_TITLE,
                geometryPolicy = CandidateGeometryPolicy.ACCESSIBILITY_EXACT,
                renderPolicy = CandidateRenderPolicy.DIRECT_OVERLAY,
                reason = "youtube-shorts-title-bounds"
            )
        }

        if (baseSourceId.startsWith(ACCESSIBILITY_COMMENT_PREFIX)) {
            return CandidateRoute(
                surface = if (packageName == YOUTUBE_PACKAGE) {
                    CandidateSurface.YOUTUBE_COMMENT
                } else {
                    CandidateSurface.GENERIC_TEXT
                },
                geometryPolicy = CandidateGeometryPolicy.ACCESSIBILITY_EXACT,
                renderPolicy = CandidateRenderPolicy.DIRECT_OVERLAY,
                reason = "comment-body-accessibility-bounds"
            )
        }

        if (baseSourceId.startsWith("android-accessibility-char-range:")) {
            return CandidateRoute(
                surface = surfaceFor(packageName, baseSourceId, role),
                geometryPolicy = CandidateGeometryPolicy.ACCESSIBILITY_EXACT,
                renderPolicy = CandidateRenderPolicy.DIRECT_OVERLAY,
                reason = "accessibility-character-location-bounds"
            )
        }

        if (baseSourceId.startsWith("youtube-visual-range:") ||
            baseSourceId == "youtube-composite-description"
        ) {
            return CandidateRoute(
                surface = CandidateSurface.YOUTUBE_VISUAL_TEXT,
                geometryPolicy = CandidateGeometryPolicy.ACCESSIBILITY_ESTIMATED,
                renderPolicy = CandidateRenderPolicy.OCR_REQUIRED,
                reason = "youtube-visual-text-needs-ocr-geometry"
            )
        }

        if (baseSourceId.startsWith("ocr:youtube-composite-card:") ||
            baseSourceId.startsWith("ocr:youtube-visible-band:")
        ) {
            return CandidateRoute(
                surface = CandidateSurface.YOUTUBE_VISUAL_TEXT,
                geometryPolicy = CandidateGeometryPolicy.VISUAL_OCR_EXACT,
                renderPolicy = CandidateRenderPolicy.DIRECT_OVERLAY,
                reason = "youtube-ocr-exact-text-bounds"
            )
        }

        if (baseSourceId.startsWith("ocr:youtube-semantic-card:")) {
            return CandidateRoute(
                surface = CandidateSurface.YOUTUBE_VISUAL_TEXT,
                geometryPolicy = CandidateGeometryPolicy.VISUAL_FALLBACK,
                renderPolicy = CandidateRenderPolicy.DIRECT_OVERLAY,
                reason = "youtube-semantic-visual-fallback"
            )
        }

        if (baseSourceId.startsWith("ocr:")) {
            return CandidateRoute(
                surface = CandidateSurface.GENERIC_TEXT,
                geometryPolicy = CandidateGeometryPolicy.VISUAL_OCR_EXACT,
                renderPolicy = CandidateRenderPolicy.DIRECT_OVERLAY,
                reason = "ocr-exact-text-bounds"
            )
        }

        if (baseSourceId.startsWith("android-accessibility-browser:")) {
            return CandidateRoute(
                surface = CandidateSurface.BROWSER_RESULT,
                geometryPolicy = CandidateGeometryPolicy.ANALYSIS_ONLY,
                renderPolicy = CandidateRenderPolicy.ANALYSIS_ONLY,
                reason = "browser-accessibility-row-context"
            )
        }

        if (baseSourceId.startsWith("android-accessibility-range:")) {
            return CandidateRoute(
                surface = CandidateSurface.GENERIC_TEXT,
                geometryPolicy = CandidateGeometryPolicy.ACCESSIBILITY_ESTIMATED,
                renderPolicy = CandidateRenderPolicy.DIRECT_OVERLAY,
                reason = "compact-accessibility-range"
            )
        }

        if (baseSourceId.startsWith("screen:accessibility_text:")) {
            return CandidateRoute(
                surface = CandidateSurface.GENERIC_TEXT,
                geometryPolicy = CandidateGeometryPolicy.ANALYSIS_ONLY,
                renderPolicy = CandidateRenderPolicy.ANALYSIS_ONLY,
                reason = "generic-screen-context"
            )
        }

        if (source == CandidateSource.ACCESSIBILITY_TEXT_WITH_OCR_GEOMETRY) {
            return CandidateRoute(
                surface = surfaceFor(packageName, baseSourceId, role),
                geometryPolicy = CandidateGeometryPolicy.ACCESSIBILITY_ESTIMATED,
                renderPolicy = CandidateRenderPolicy.OCR_REQUIRED,
                reason = "accessibility-text-needs-visual-geometry"
            )
        }

        if (source == CandidateSource.VISUAL_OCR) {
            return CandidateRoute(
                surface = surfaceFor(packageName, baseSourceId, role),
                geometryPolicy = CandidateGeometryPolicy.VISUAL_OCR_EXACT,
                renderPolicy = CandidateRenderPolicy.DIRECT_OVERLAY,
                reason = "visual-ocr-source"
            )
        }

        return CandidateRoute(
            surface = surfaceFor(packageName, baseSourceId, role),
            geometryPolicy = CandidateGeometryPolicy.ACCESSIBILITY_EXACT,
            renderPolicy = if (role == CandidateRole.BUTTON_OR_NAVIGATION) {
                CandidateRenderPolicy.ANALYSIS_ONLY
            } else {
                CandidateRenderPolicy.DIRECT_OVERLAY
            },
            reason = "accessibility-text-bounds"
        )
    }

    fun summarize(candidates: List<ScreenTextCandidate>, limit: Int = 8): List<String> {
        return candidates
            .groupingBy { it.route.summaryKey() }
            .eachCount()
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(limit)
            .map { (route, count) -> "$route=$count" }
    }

    private fun surfaceFor(
        packageName: String,
        baseSourceId: String,
        role: CandidateRole
    ): CandidateSurface {
        if (packageName == YOUTUBE_PACKAGE) {
            return when {
                baseSourceId == YOUTUBE_USER_INPUT_AUTHOR_ID -> CandidateSurface.YOUTUBE_SEARCH_INPUT
                baseSourceId == YOUTUBE_TITLE_AUTHOR_ID -> CandidateSurface.YOUTUBE_TITLE
                baseSourceId == YOUTUBE_SHORTS_TITLE_AUTHOR_ID -> CandidateSurface.YOUTUBE_SHORTS_TITLE
                baseSourceId.startsWith(ACCESSIBILITY_COMMENT_PREFIX) -> CandidateSurface.YOUTUBE_COMMENT
                baseSourceId.startsWith("android-accessibility-char-range:") -> when (role) {
                    CandidateRole.USER_INPUT -> CandidateSurface.YOUTUBE_SEARCH_INPUT
                    CandidateRole.TITLE -> CandidateSurface.YOUTUBE_TITLE
                    CandidateRole.CONTENT -> CandidateSurface.YOUTUBE_COMMENT
                    else -> CandidateSurface.GENERIC_TEXT
                }
                baseSourceId.startsWith("youtube-visual-range:") ||
                    baseSourceId.startsWith("ocr:") ||
                    baseSourceId == "youtube-composite-description" -> CandidateSurface.YOUTUBE_VISUAL_TEXT
                role == CandidateRole.TITLE -> CandidateSurface.YOUTUBE_TITLE
                else -> CandidateSurface.GENERIC_TEXT
            }
        }

        if (baseSourceId.startsWith("android-accessibility-browser:")) {
            return CandidateSurface.BROWSER_RESULT
        }

        return when (role) {
            CandidateRole.USER_INPUT,
            CandidateRole.TITLE,
            CandidateRole.SNIPPET,
            CandidateRole.CONTENT -> CandidateSurface.GENERIC_TEXT
            CandidateRole.THUMBNAIL_TEXT,
            CandidateRole.VIDEO_FRAME_TEXT -> CandidateSurface.GENERIC_TEXT
            CandidateRole.BUTTON_OR_NAVIGATION -> CandidateSurface.UNKNOWN
        }
    }
}
