package com.scroller.agent.executor

import android.graphics.Bitmap

object ScreenFingerprinting {
    private const val DEFAULT_SIZE = 32

    fun fromBitmap(bitmap: Bitmap, size: Int = DEFAULT_SIZE): ScreenFingerprint {
        val scaled = if (bitmap.width != size || bitmap.height != size) {
            Bitmap.createScaledBitmap(bitmap, size, size, true)
        } else {
            bitmap
        }

        val pixels = IntArray(size * size)
        scaled.getPixels(pixels, 0, size, 0, 0, size, size)

        val luminance = IntArray(pixels.size)
        for (i in pixels.indices) {
            val color = pixels[i]
            val r = (color shr 16) and 0xFF
            val g = (color shr 8) and 0xFF
            val b = color and 0xFF
            luminance[i] = (0.299f * r + 0.587f * g + 0.114f * b).toInt()
        }

        return ScreenFingerprint.fromLuminance(luminance, size)
    }
}
