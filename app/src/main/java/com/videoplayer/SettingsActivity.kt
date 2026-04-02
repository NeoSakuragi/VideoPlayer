package com.videoplayer

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    private lateinit var settings: AppSettings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        settings = AppSettings(this)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        setupSubtitleSettings()
        setupPlaybackSettings()
        setupAnkiSettings()
        setupFieldMappings()
    }

    private fun setupSubtitleSettings() {
        val tvFont = findViewById<TextView>(R.id.tvFontValue)
        tvFont.text = settings.fontInfo.displayName
        findViewById<LinearLayout>(R.id.settingFont).setOnClickListener {
            val keys = AppSettings.FONTS.keys.toList()
            val names = AppSettings.FONTS.values.map { it.displayName }.toTypedArray()
            AlertDialog.Builder(this).setTitle("Subtitle font")
                .setSingleChoiceItems(names, keys.indexOf(settings.fontKey).coerceAtLeast(0)) { d, w ->
                    settings.fontKey = keys[w]; tvFont.text = names[w]; d.dismiss()
                }.setNegativeButton("Cancel", null).show()
        }

        val tvBold = findViewById<TextView>(R.id.tvFontBoldValue)
        tvBold.text = if (settings.fontBold) "On" else "Off"
        findViewById<LinearLayout>(R.id.settingFontBold).setOnClickListener {
            settings.fontBold = !settings.fontBold
            tvBold.text = if (settings.fontBold) "On" else "Off"
        }

        val tvSize = findViewById<TextView>(R.id.tvFontSizeValue)
        tvSize.text = "${settings.fontSize}sp"
        findViewById<LinearLayout>(R.id.settingFontSize).setOnClickListener {
            val sizes = AppSettings.FONT_SIZES
            AlertDialog.Builder(this).setTitle("Font size")
                .setSingleChoiceItems(sizes.map { "${it}sp" }.toTypedArray(), sizes.indexOf(settings.fontSize).coerceAtLeast(0)) { d, w ->
                    settings.fontSize = sizes[w]; tvSize.text = "${sizes[w]}sp"; d.dismiss()
                }.setNegativeButton("Cancel", null).show()
        }
    }

    private fun setupPlaybackSettings() {
        val tvHwdec = findViewById<TextView>(R.id.tvHwdecValue)
        tvHwdec.text = if (settings.hardwareDecoding) "On (hardware)" else "Off (software)"
        findViewById<LinearLayout>(R.id.settingHwdec).setOnClickListener {
            settings.hardwareDecoding = !settings.hardwareDecoding
            tvHwdec.text = if (settings.hardwareDecoding) "On (hardware)" else "Off (software)"
            Toast.makeText(this, "Restart app to apply", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupAnkiSettings() {
        // Enable/disable
        val tvAnki = findViewById<TextView>(R.id.tvAnkiValue)
        tvAnki.text = if (settings.ankiEnabled) "Enabled" else "Disabled"
        findViewById<LinearLayout>(R.id.settingAnkiEnabled).setOnClickListener {
            settings.ankiEnabled = !settings.ankiEnabled
            tvAnki.text = if (settings.ankiEnabled) "Enabled" else "Disabled"
        }

        // URL
        val tvUrl = findViewById<TextView>(R.id.tvAnkiUrlValue)
        tvUrl.text = settings.ankiConnectUrl
        findViewById<LinearLayout>(R.id.settingAnkiUrl).setOnClickListener {
            showTextInput("AnkiConnect URL", "http://127.0.0.1:8765", settings.ankiConnectUrl) {
                settings.ankiConnectUrl = it; tvUrl.text = it
            }
        }

        // Test connection
        val tvTest = findViewById<TextView>(R.id.tvAnkiTestValue)
        findViewById<LinearLayout>(R.id.settingAnkiTest).setOnClickListener {
            tvTest.text = "Testing..."
            Thread {
                val client = AnkiConnectClient(settings.ankiConnectUrl)
                val result = client.testConnection()
                runOnUiThread {
                    tvTest.text = if (result.success) "Connected (v${result.data})" else "Failed: ${result.message}"
                }
            }.start()
        }

        // Deck - try to fetch from AnkiConnect, fallback to text input
        val tvDeck = findViewById<TextView>(R.id.tvAnkiDeckValue)
        tvDeck.text = settings.ankiDeck
        findViewById<LinearLayout>(R.id.settingAnkiDeck).setOnClickListener {
            Thread {
                val decks = AnkiConnectClient(settings.ankiConnectUrl).getDeckNames()
                runOnUiThread {
                    if (decks.isNotEmpty()) {
                        val selected = decks.indexOf(settings.ankiDeck).coerceAtLeast(0)
                        AlertDialog.Builder(this).setTitle("Select deck")
                            .setSingleChoiceItems(decks.toTypedArray(), selected) { d, w ->
                                settings.ankiDeck = decks[w]; tvDeck.text = decks[w]; d.dismiss()
                            }.setNegativeButton("Cancel", null).show()
                    } else {
                        showTextInput("Deck name", "Default", settings.ankiDeck) {
                            settings.ankiDeck = it; tvDeck.text = it
                        }
                    }
                }
            }.start()
        }

        // Note type - try to fetch from AnkiConnect
        val tvNoteType = findViewById<TextView>(R.id.tvAnkiNoteTypeValue)
        tvNoteType.text = settings.ankiNoteType
        findViewById<LinearLayout>(R.id.settingAnkiNoteType).setOnClickListener {
            Thread {
                val models = AnkiConnectClient(settings.ankiConnectUrl).getModelNames()
                runOnUiThread {
                    if (models.isNotEmpty()) {
                        val selected = models.indexOf(settings.ankiNoteType).coerceAtLeast(0)
                        AlertDialog.Builder(this).setTitle("Select note type")
                            .setSingleChoiceItems(models.toTypedArray(), selected) { d, w ->
                                settings.ankiNoteType = models[w]; tvNoteType.text = models[w]; d.dismiss()
                                // Refresh field mappings when note type changes
                                setupFieldMappings()
                            }.setNegativeButton("Cancel", null).show()
                    } else {
                        showTextInput("Note type", "Basic", settings.ankiNoteType) {
                            settings.ankiNoteType = it; tvNoteType.text = it
                        }
                    }
                }
            }.start()
        }

        // Tags
        val tvTags = findViewById<TextView>(R.id.tvAnkiTagsValue)
        tvTags.text = settings.ankiTags
        findViewById<LinearLayout>(R.id.settingAnkiTags).setOnClickListener {
            showTextInput("Tags (space-separated)", "janus mining", settings.ankiTags) {
                settings.ankiTags = it; tvTags.text = it
            }
        }
    }

    private fun setupFieldMappings() {
        val container = findViewById<LinearLayout>(R.id.fieldMappingsContainer)
        container.removeAllViews()

        for ((key, label) in AppSettings.ANKI_FIELD_KEYS) {
            val row = layoutInflater.inflate(android.R.layout.simple_list_item_2, container, false)
            val tv1 = row.findViewById<TextView>(android.R.id.text1)
            val tv2 = row.findViewById<TextView>(android.R.id.text2)

            tv1.text = label
            tv1.setTextColor(0xFFEEEEEE.toInt())
            tv1.textSize = 14f
            tv2.text = settings.getFieldMapping(key).ifEmpty { "(not mapped)" }
            tv2.setTextColor(0xFF888888.toInt())
            tv2.textSize = 12f

            row.setBackgroundResource(R.drawable.focus_highlight)
            row.isFocusable = true
            row.isClickable = true
            row.setPadding(32, 24, 32, 24)

            row.setOnClickListener {
                // Try to get field names from AnkiConnect for the current note type
                Thread {
                    val fields = AnkiConnectClient(settings.ankiConnectUrl)
                        .getModelFieldNames(settings.ankiNoteType)
                    runOnUiThread {
                        if (fields.isNotEmpty()) {
                            val options = listOf("(not mapped)") + fields
                            val current = settings.getFieldMapping(key)
                            val selected = if (current.isEmpty()) 0 else {
                                val idx = fields.indexOf(current)
                                if (idx >= 0) idx + 1 else 0
                            }
                            AlertDialog.Builder(this).setTitle("Map: $label")
                                .setSingleChoiceItems(options.toTypedArray(), selected) { d, w ->
                                    val value = if (w == 0) "" else fields[w - 1]
                                    settings.setFieldMapping(key, value)
                                    tv2.text = value.ifEmpty { "(not mapped)" }
                                    d.dismiss()
                                }.setNegativeButton("Cancel", null).show()
                        } else {
                            showTextInput("Field name for: $label", "", settings.getFieldMapping(key)) {
                                settings.setFieldMapping(key, it)
                                tv2.text = it.ifEmpty { "(not mapped)" }
                            }
                        }
                    }
                }.start()
            }

            container.addView(row)
        }
    }

    private fun showTextInput(title: String, hint: String, current: String, onSet: (String) -> Unit) {
        val input = EditText(this).apply {
            this.hint = hint; setText(current); setPadding(48, 32, 48, 32)
        }
        AlertDialog.Builder(this).setTitle(title).setView(input)
            .setPositiveButton("OK") { _, _ -> onSet(input.text.toString().trim()) }
            .setNegativeButton("Cancel", null).show()
    }
}
