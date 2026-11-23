package com.example.service

import android.media.Image
import kotlin.math.abs

class MotionDetector {
    private var previousLuma: IntArray? = null
    private var sampleWidth = 0
    private var sampleHeight = 0

    fun reset() {
        previousLuma = null
        sampleWidth = 0
        sampleHeight = 0
    }

    fun computeChangePercent(image: Image): Float {
        val plane = image.planes.firstOrNull() ?: return 0f
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val width = image.width
        val height = image.height
        if (width <= 0 || height <= 0 || pixelStride <= 0) return 0f

        val step = 12
        val cols = (width + step - 1) / step
        val rows = (height + step - 1) / step
        val totalSamples = cols * rows
        if (totalSamples <= 0) return 0f

        val currentLuma = IntArray(totalSamples)
        var idx = 0
        for (y in 0 until height step step) {
            val rowOffset = y * rowStride
            for (x in 0 until width step step) {
                val offset = rowOffset + x * pixelStride
                if (offset + 2 < buffer.limit()) {
                    val r = buffer.get(offset).toInt() and 0xFF
                    val g = buffer.get(offset + 1).toInt() and 0xFF
                    val b = buffer.get(offset + 2).toInt() and 0xFF
                    currentLuma[idx] = (r * 30 + g * 59 + b * 11) / 100
                }
                idx++
            }
        }

        val prev = previousLuma
        previousLuma = currentLuma

        if (prev == null || sampleWidth != cols || sampleHeight != rows || prev.size != currentLuma.size) {
            sampleWidth = cols
            sampleHeight = rows
            return 0f
        }

        var changedCount = 0
        val lumaThreshold = 22
        for (i in currentLuma.indices) {
            if (abs(currentLuma[i] - prev[i]) > lumaThreshold) {
                changedCount++
            }
        }
        return (changedCount * 100f) / totalSamples.toFloat()
    }

    companion object {
        fun thresholdForSensitivity(level: Int): Float = when (level) {
            1 -> 6.0f
            2 -> 2.5f
            else -> 0.8f
        }
    }
}
