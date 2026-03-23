package com.capstone.design.youtubeparser

import com.google.gson.annotations.SerializedName

data class BoundsRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
)

data class ParsedTextNode(
    val packageName: String,
    val text: String?,
    val contentDescription: String?,
    val displayText: String?,
    val className: String?,
    val viewIdResourceName: String?,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val approxTop: Int,
    val isVisibleToUser: Boolean
)

data class ParsedComment(
    val commentText: String,
    val boundsInScreen: BoundsRect,
    @SerializedName("author_id")
    val authorId: String? = null
)

data class ParseSnapshot(
    val timestamp: Long,
    val comments: List<ParsedComment>
)
