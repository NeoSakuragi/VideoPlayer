package com.videoplayer

import android.content.Context
import android.content.SharedPreferences

class AppSettings(context: Context) {

    companion object {
        private const val PREFS = "videoplayer_prefs"
        private const val KEY_FONT = "subtitle_font"
        private const val KEY_FONT_SIZE = "subtitle_font_size"
        private const val KEY_FONT_BOLD = "subtitle_font_bold"
        private const val KEY_HWDEC = "hardware_decoding"
        private const val KEY_ANKI_ENABLED = "anki_enabled"
        private const val KEY_ANKI_DECK = "anki_deck"
        private const val KEY_ANKI_NOTE_TYPE = "anki_note_type"

        val FONTS = linkedMapOf(
            "noto_sans" to FontInfo("Noto Sans JP", "fonts/NotoSansJP-Regular.ttf"),
            "noto_serif" to FontInfo("Noto Serif JP", "fonts/NotoSerifJP-Regular.ttf"),
            "kosugi_maru" to FontInfo("Kosugi Maru", "fonts/KosugiMaru-Regular.ttf"),
            "shippori_mincho" to FontInfo("Shippori Mincho", "fonts/ShipporiMincho-Regular.ttf")
        )

        val FONT_SIZES = listOf(16, 18, 20, 22, 24, 28, 32, 38, 44)
    }

    data class FontInfo(val displayName: String, val assetPath: String)

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var fontKey: String
        get() = prefs.getString(KEY_FONT, "noto_sans") ?: "noto_sans"
        set(value) = prefs.edit().putString(KEY_FONT, value).apply()

    val fontInfo: FontInfo get() = FONTS[fontKey] ?: FONTS["noto_sans"]!!

    var fontSize: Int
        get() = prefs.getInt(KEY_FONT_SIZE, 20)
        set(value) = prefs.edit().putInt(KEY_FONT_SIZE, value).apply()

    var fontBold: Boolean
        get() = prefs.getBoolean(KEY_FONT_BOLD, false)
        set(value) = prefs.edit().putBoolean(KEY_FONT_BOLD, value).apply()

    var hardwareDecoding: Boolean
        get() = prefs.getBoolean(KEY_HWDEC, false)
        set(value) = prefs.edit().putBoolean(KEY_HWDEC, value).apply()

    var ankiEnabled: Boolean
        get() = prefs.getBoolean(KEY_ANKI_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ANKI_ENABLED, value).apply()

    var ankiDeck: String
        get() = prefs.getString(KEY_ANKI_DECK, "") ?: ""
        set(value) = prefs.edit().putString(KEY_ANKI_DECK, value).apply()

    var ankiNoteType: String
        get() = prefs.getString(KEY_ANKI_NOTE_TYPE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_ANKI_NOTE_TYPE, value).apply()
}
