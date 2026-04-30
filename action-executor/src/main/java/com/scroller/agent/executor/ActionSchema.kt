package com.scroller.agent.executor

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = false)
data class ActionSchema(
    val action: String,
    val x: Int? = null,
    val y: Int? = null,
    val direction: String? = null,
    val text: String? = null,
    val packageName: String? = null
)

enum class ActionField {
    X,
    Y,
    DIRECTION,
    TEXT,
    PACKAGE_NAME
}
