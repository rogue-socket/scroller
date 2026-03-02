package com.scroller.agent.executor

import android.graphics.Bitmap

data class ScreenFrame(
    val bitmap: Bitmap,
    val width: Int,
    val height: Int,
    val timestampMs: Long
)
