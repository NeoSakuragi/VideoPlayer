package com.videoplayer

import java.io.File

/**
 * Holds all the data needed to create an Anki card.
 */
data class CardData(
    val word: String,
    val reading: String,
    val meaning: String,
    val sentence: String,
    val frequency: Int?,
    var screenshotFile: File? = null,
    var audioFile: File? = null,
    var audioStartSec: Double = 0.0,
    var audioEndSec: Double = 0.0,
    var audioPadBefore: Float = 0.5f,
    var audioPadAfter: Float = 0.5f,
    val source: String = ""
) {
    val adjustedStart: Double get() = maxOf(0.0, audioStartSec - audioPadBefore)
    val adjustedEnd: Double get() = audioEndSec + audioPadAfter

    /** Word with furigana in Anki format: 漢字[かんじ] */
    val wordWithFurigana: String get() {
        if (reading.isEmpty() || reading == word) return word
        return "$word[$reading]"
    }

    fun formatTime(seconds: Double): String {
        val s = seconds.toInt().coerceAtLeast(0)
        val m = s / 60
        val sec = s % 60
        val ms = ((seconds - s) * 100).toInt()
        return "%d:%02d.%02d".format(m, sec, ms)
    }
}
