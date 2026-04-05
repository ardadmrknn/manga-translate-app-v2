package com.vibecode.mangaceviriv2

import android.graphics.Rect
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TranslationBlock(
    @SerialName("original") val original: String,
    @SerialName("translated") val translated: String,
    @SerialName("box") val box: List<Int>
) {
    fun toRect(): Rect {
        val safe = normalizedBox()
        val x = safe[0]
        val y = safe[1]
        val w = safe[2]
        val h = safe[3]
        return Rect(x, y, x + w, y + h)
    }

    fun normalizedBox(): List<Int> {
        if (box.size < 4) return listOf(0, 0, 0, 0)
        val x = box[0].coerceAtLeast(0)
        val y = box[1].coerceAtLeast(0)
        val w = box[2].coerceAtLeast(0)
        val h = box[3].coerceAtLeast(0)
        return listOf(x, y, w, h)
    }
}

data class FrameSelection(val left: Int, val top: Int, val width: Int, val height: Int) {
    fun asRect(): Rect = Rect(left, top, left + width, top + height)
}
