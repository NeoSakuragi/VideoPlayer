package com.videoplayer

import android.os.Bundle
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    private lateinit var settings: AppSettings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        settings = AppSettings(this)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        // Font
        val tvFontValue = findViewById<TextView>(R.id.tvFontValue)
        tvFontValue.text = settings.fontInfo.displayName
        findViewById<LinearLayout>(R.id.settingFont).setOnClickListener { showFontPicker(tvFontValue) }

        // Font bold
        val tvFontBoldValue = findViewById<TextView>(R.id.tvFontBoldValue)
        tvFontBoldValue.text = if (settings.fontBold) "On" else "Off"
        findViewById<LinearLayout>(R.id.settingFontBold).setOnClickListener {
            settings.fontBold = !settings.fontBold
            tvFontBoldValue.text = if (settings.fontBold) "On" else "Off"
        }

        // Font size
        val tvFontSizeValue = findViewById<TextView>(R.id.tvFontSizeValue)
        tvFontSizeValue.text = "${settings.fontSize}sp"
        findViewById<LinearLayout>(R.id.settingFontSize).setOnClickListener { showFontSizePicker(tvFontSizeValue) }

        // Hardware decoding
        val tvHwdecValue = findViewById<TextView>(R.id.tvHwdecValue)
        tvHwdecValue.text = if (settings.hardwareDecoding) "On (hardware)" else "Off (software)"
        findViewById<LinearLayout>(R.id.settingHwdec).setOnClickListener {
            settings.hardwareDecoding = !settings.hardwareDecoding
            tvHwdecValue.text = if (settings.hardwareDecoding) "On (hardware)" else "Off (software)"
        }

        // Anki enabled
        val tvAnkiValue = findViewById<TextView>(R.id.tvAnkiValue)
        tvAnkiValue.text = if (settings.ankiEnabled) "Enabled" else "Not configured"
        findViewById<LinearLayout>(R.id.settingAnkiEnabled).setOnClickListener {
            settings.ankiEnabled = !settings.ankiEnabled
            tvAnkiValue.text = if (settings.ankiEnabled) "Enabled" else "Not configured"
        }

        // Anki deck
        val tvAnkiDeckValue = findViewById<TextView>(R.id.tvAnkiDeckValue)
        tvAnkiDeckValue.text = settings.ankiDeck.ifEmpty { "Not set" }
        findViewById<LinearLayout>(R.id.settingAnkiDeck).setOnClickListener {
            showTextInput("Target deck", "e.g. Japanese::Mining", settings.ankiDeck) { value ->
                settings.ankiDeck = value
                tvAnkiDeckValue.text = value.ifEmpty { "Not set" }
            }
        }

        // Anki note type
        val tvAnkiNoteTypeValue = findViewById<TextView>(R.id.tvAnkiNoteTypeValue)
        tvAnkiNoteTypeValue.text = settings.ankiNoteType.ifEmpty { "Not set" }
        findViewById<LinearLayout>(R.id.settingAnkiNoteType).setOnClickListener {
            showTextInput("Note type", "e.g. Basic, Japanese", settings.ankiNoteType) { value ->
                settings.ankiNoteType = value
                tvAnkiNoteTypeValue.text = value.ifEmpty { "Not set" }
            }
        }
    }

    private fun showFontPicker(tvValue: TextView) {
        val keys = AppSettings.FONTS.keys.toList()
        val names = AppSettings.FONTS.values.map { it.displayName }.toTypedArray()
        val selected = keys.indexOf(settings.fontKey).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Subtitle font")
            .setSingleChoiceItems(names, selected) { dialog, which ->
                settings.fontKey = keys[which]
                tvValue.text = names[which]
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun showFontSizePicker(tvValue: TextView) {
        val sizes = AppSettings.FONT_SIZES
        val names = sizes.map { "${it}sp" }.toTypedArray()
        val selected = sizes.indexOf(settings.fontSize).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Font size")
            .setSingleChoiceItems(names, selected) { dialog, which ->
                settings.fontSize = sizes[which]
                tvValue.text = names[which]
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun showTextInput(title: String, hint: String, current: String, onSet: (String) -> Unit) {
        val input = EditText(this).apply {
            this.hint = hint
            setText(current)
            setPadding(48, 32, 48, 32)
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setPositiveButton("OK") { _, _ -> onSet(input.text.toString().trim()) }
            .setNegativeButton("Cancel", null).show()
    }
}
