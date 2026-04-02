package com.videoplayer

import android.app.Activity
import java.io.File
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.button.MaterialButton

class MainActivity : AppCompatActivity(), MpvPlayerView.Listener {

    companion object {
        private const val TAG = "VideoPlayer"
    }

    private lateinit var playerView: MpvPlayerView
    private lateinit var subtitleOverlay: SubtitleOverlay
    private lateinit var playMode: View
    private lateinit var pauseControls: LinearLayout
    private lateinit var btnAudioTrack: MaterialButton
    private lateinit var btnSubtitleTrack: MaterialButton
    private lateinit var btnPlaySettings: MaterialButton
    private lateinit var seekBar: SeekBar
    private lateinit var tvPosition: TextView
    private lateinit var tvDuration: TextView

    // Card creator views
    private lateinit var cardCreator: ScrollView
    private lateinit var ccWord: TextView
    private lateinit var ccReading: TextView
    private lateinit var ccFrequency: TextView
    private lateinit var ccMeaning: TextView
    private lateinit var ccSentence: TextView
    private lateinit var ccScreenshot: ImageView
    private lateinit var ccAudioTiming: TextView
    private lateinit var ccPadBeforeLabel: TextView
    private lateinit var ccPadAfterLabel: TextView

    private lateinit var appSettings: AppSettings
    private lateinit var dictDb: DictionaryDatabase
    private lateinit var dictPopup: DictionaryPopup
    private lateinit var dictManager: DictionaryManager
    private lateinit var mediaCapture: MediaCapture

    private var isPlaying = false
    private var isLoadingSmb = false
    private val smbStreamServer = SmbStreamServer()
    private var currentPlaybackUrl: String? = null
    private var currentCardData: CardData? = null
    private var lastSubtitleText: String = ""

    private val browserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.let { handleBrowserResult(it) }
        }
    }

    private val subtitleBrowserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.let { handleSubtitleResult(it) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        playMode = findViewById(R.id.playMode)
        pauseControls = findViewById(R.id.pauseControls)
        playerView = findViewById(R.id.playerView)
        subtitleOverlay = findViewById(R.id.subtitleOverlay)
        btnAudioTrack = findViewById(R.id.btnAudioTrack)
        btnSubtitleTrack = findViewById(R.id.btnSubtitleTrack)
        btnPlaySettings = findViewById(R.id.btnPlaySettings)
        seekBar = findViewById(R.id.seekBar)
        tvPosition = findViewById(R.id.tvPosition)
        tvDuration = findViewById(R.id.tvDuration)

        // Card creator views
        cardCreator = findViewById(R.id.cardCreator)
        ccWord = findViewById(R.id.ccWord)
        ccReading = findViewById(R.id.ccReading)
        ccFrequency = findViewById(R.id.ccFrequency)
        ccMeaning = findViewById(R.id.ccMeaning)
        ccSentence = findViewById(R.id.ccSentence)
        ccScreenshot = findViewById(R.id.ccScreenshot)
        ccAudioTiming = findViewById(R.id.ccAudioTiming)
        ccPadBeforeLabel = findViewById(R.id.ccPadBeforeLabel)
        ccPadAfterLabel = findViewById(R.id.ccPadAfterLabel)

        // Settings
        appSettings = AppSettings(this)
        mediaCapture = MediaCapture(this)

        // Dictionary setup
        dictDb = DictionaryDatabase.getInstance(this)
        dictPopup = DictionaryPopup(
            findViewById(R.id.dictPopup),
            findViewById(R.id.tvDictTerm),
            findViewById(R.id.tvDictReading),
            findViewById(R.id.tvDictFreq),
            findViewById(R.id.tvDictTags),
            findViewById(R.id.tvDictMeanings)
        )
        dictManager = DictionaryManager(this)
        dictManager.ensureReady { Log.d(TAG, "Dictionary ready") }

        // Player setup
        playerView.setListener(this)
        playerView.initialize()

        // Word tap handler
        subtitleOverlay.setOnWordTapListener { surface, baseForm, _ ->
            lookupAndShow(surface, baseForm)
        }

        // Tap on video: dismiss popup/card creator, or toggle pause
        playerView.onTapInterceptor = {
            when {
                cardCreator.visibility == View.VISIBLE -> {
                    cardCreator.visibility = View.GONE
                    true
                }
                dictPopup.isVisible -> {
                    dictPopup.hide()
                    playerView.play()
                    true
                }
                else -> false
            }
        }

        btnAudioTrack.setOnClickListener { showAudioTrackDialog() }
        btnSubtitleTrack.setOnClickListener { showSubtitleTrackDialog() }
        btnPlaySettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // Add to Anki button in dict popup
        findViewById<MaterialButton>(R.id.btnAddToAnki).setOnClickListener {
            openCardCreator()
        }

        // Card creator buttons
        setupCardCreatorButtons()

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val dur = playerView.duration
                    if (dur > 0) tvPosition.text = formatTime(dur * progress / 1000.0)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {
                val dur = playerView.duration
                if (dur > 0) playerView.seekTo(dur * sb.progress / 1000.0)
            }
        })

        showBrowseMode()
        intent?.data?.let { playFile(it.toString()) }
    }

    // ── Card Creator ─────────────────────────────────────────────────

    private fun setupCardCreatorButtons() {
        findViewById<MaterialButton>(R.id.ccPadBeforeMinus).setOnClickListener { adjustPadBefore(-0.25f) }
        findViewById<MaterialButton>(R.id.ccPadBeforePlus).setOnClickListener { adjustPadBefore(0.25f) }
        findViewById<MaterialButton>(R.id.ccPadAfterMinus).setOnClickListener { adjustPadAfter(-0.25f) }
        findViewById<MaterialButton>(R.id.ccPadAfterPlus).setOnClickListener { adjustPadAfter(0.25f) }
        findViewById<MaterialButton>(R.id.ccBtnCancel).setOnClickListener {
            cardCreator.visibility = View.GONE
        }
        findViewById<MaterialButton>(R.id.ccBtnSend).setOnClickListener {
            sendToAnki()
        }
    }

    private fun openCardCreator() {
        if (!appSettings.ankiEnabled) {
            Toast.makeText(this, "Enable Anki in Settings first", Toast.LENGTH_LONG).show()
            return
        }

        dictPopup.hide()

        // Build card data from current state
        val dictEntries = currentDictEntries ?: return
        val best = dictEntries.first()
        val meanings = dictEntries.flatMap { it.meanings }.filter { it.isNotBlank() }.distinct().take(5)

        val timing = mediaCapture.getSubtitleTiming()

        currentCardData = CardData(
            word = best.term,
            reading = best.reading,
            meaning = meanings.joinToString("\n") { it },
            sentence = lastSubtitleText,
            frequency = best.frequency ?: dictEntries.firstNotNullOfOrNull { it.frequency },
            audioStartSec = timing?.first ?: 0.0,
            audioEndSec = timing?.second ?: 0.0,
            audioPadBefore = appSettings.audioPadBefore,
            audioPadAfter = appSettings.audioPadAfter,
            source = try { dev.jdtech.mpv.MPVLib.getPropertyString("media-title") ?: "" } catch (_: Exception) { "" }
        )

        // Populate UI immediately
        val card = currentCardData!!
        ccWord.text = card.word
        ccReading.text = if (card.reading != card.word) card.reading else ""
        ccFrequency.text = card.frequency?.let { "#$it" } ?: ""
        ccFrequency.visibility = if (card.frequency != null) View.VISIBLE else View.GONE
        ccMeaning.text = card.meaning
        ccSentence.text = card.sentence
        ccScreenshot.setImageDrawable(null)
        updateAudioTimingDisplay()

        cardCreator.visibility = View.VISIBLE
        cardCreator.scrollTo(0, 0)

        // Capture screenshot via PixelCopy (must be on main thread)
        playerView.captureFrame { bitmap ->
            if (bitmap != null) {
                ccScreenshot.setImageBitmap(bitmap)
                Thread {
                    val screenshotFile = mediaCapture.saveBitmap(bitmap)
                    card.screenshotFile = screenshotFile
                    Log.d(TAG, "Screenshot saved: ${screenshotFile?.length()} bytes")

                    // Audio extraction in same background thread
                    val sourceUrl = currentPlaybackUrl
                    if (sourceUrl != null && (card.audioStartSec > 0 || card.audioEndSec > 0)) {
                        card.audioFile = mediaCapture.extractAudio(sourceUrl, card.adjustedStart, card.adjustedEnd)
                        Log.d(TAG, "Audio extracted: ${card.audioFile?.length()} bytes")
                    }
                    runOnUiThread {
                        Toast.makeText(this, "Ready to send", Toast.LENGTH_SHORT).show()
                        findViewById<MaterialButton>(R.id.ccBtnSend).requestFocus()
                    }
                }.start()
            } else {
                Log.w(TAG, "PixelCopy failed")
                Toast.makeText(this, "Screenshot failed", Toast.LENGTH_SHORT).show()
                // Still try audio
                Thread {
                    val sourceUrl = currentPlaybackUrl
                    if (sourceUrl != null && (card.audioStartSec > 0 || card.audioEndSec > 0)) {
                        card.audioFile = mediaCapture.extractAudio(sourceUrl, card.adjustedStart, card.adjustedEnd)
                    }
                    runOnUiThread { findViewById<MaterialButton>(R.id.ccBtnSend).requestFocus() }
                }.start()
            }
        }
    }

    private fun adjustPadBefore(delta: Float) {
        val card = currentCardData ?: return
        card.audioPadBefore = (card.audioPadBefore + delta).coerceIn(0f, 5f)
        appSettings.audioPadBefore = card.audioPadBefore
        updateAudioTimingDisplay()
    }

    private fun adjustPadAfter(delta: Float) {
        val card = currentCardData ?: return
        card.audioPadAfter = (card.audioPadAfter + delta).coerceIn(0f, 5f)
        appSettings.audioPadAfter = card.audioPadAfter
        updateAudioTimingDisplay()
    }

    private fun updateAudioTimingDisplay() {
        val card = currentCardData ?: return
        ccPadBeforeLabel.text = "Before: %.2fs".format(card.audioPadBefore)
        ccPadAfterLabel.text = "After: %.2fs".format(card.audioPadAfter)
        if (card.audioStartSec > 0 || card.audioEndSec > 0) {
            ccAudioTiming.text = "${card.formatTime(card.adjustedStart)} → ${card.formatTime(card.adjustedEnd)}"
        } else {
            ccAudioTiming.text = "No subtitle timing available"
        }
    }

    private fun sendToAnki() {
        val card = currentCardData ?: return
        val settings = appSettings

        Toast.makeText(this, "Creating card...", Toast.LENGTH_SHORT).show()

        Thread {
            try {
                val client = AnkiConnectClient(settings.ankiConnectUrl)

                // Build fields map
                val fields = mutableMapOf<String, String>()
                fun mapField(key: String, value: String) {
                    val fieldName = settings.getFieldMapping(key)
                    if (fieldName.isNotEmpty()) fields[fieldName] = value
                }

                mapField("field_word", card.word)
                mapField("field_word_furigana", card.wordWithFurigana)
                mapField("field_reading", card.reading)
                mapField("field_meaning", card.meaning)
                mapField("field_sentence", card.sentence)
                mapField("field_sentence_furigana", card.sentence) // TODO: add furigana to sentence
                mapField("field_frequency", card.frequency?.toString() ?: "")
                mapField("field_source", card.source)

                val tags = settings.ankiTags.split(" ").filter { it.isNotBlank() }

                val screenshotField = settings.getFieldMapping("field_screenshot")
                val audioField = settings.getFieldMapping("field_audio")

                val result = client.addNote(
                    deckName = settings.ankiDeck,
                    modelName = settings.ankiNoteType,
                    fields = fields,
                    tags = tags,
                    audioFile = card.audioFile,
                    audioFieldName = audioField.ifEmpty { null },
                    imageFile = card.screenshotFile,
                    imageFieldName = screenshotField.ifEmpty { null }
                )

                runOnUiThread {
                    if (result.success) {
                        Toast.makeText(this, "Card added!", Toast.LENGTH_SHORT).show()
                        cardCreator.visibility = View.GONE
                    } else {
                        Toast.makeText(this, "Anki error: ${result.message}", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Send to Anki failed: ${e.message}", e)
                runOnUiThread {
                    Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    // ── MpvPlayerView.Listener ───────────────────────────────────────

    private var currentDictEntries: List<DictionaryDatabase.DictEntry>? = null

    override fun onSubtitleTextChanged(text: String) {
        subtitleOverlay.setSubtitleText(text.ifEmpty { null })
        if (text.isNotEmpty()) lastSubtitleText = text
    }

    override fun onPauseChanged(paused: Boolean) {
        if (!isPlaying) return
        if (cardCreator.visibility != View.VISIBLE) {
            pauseControls.visibility = if (paused) View.VISIBLE else View.GONE
        }
    }

    override fun onPositionChanged(positionSec: Double, durationSec: Double) {
        tvPosition.text = formatTime(positionSec)
        tvDuration.text = formatTime(durationSec)
        if (durationSec > 0 && !seekBar.isPressed) {
            seekBar.progress = (positionSec / durationSec * 1000).toInt()
        }
    }

    override fun onFileLoaded() { Log.d(TAG, "File loaded") }
    override fun onFileEnded() { Log.d(TAG, "File ended"); showBrowseMode() }
    override fun onTracksChanged() {}

    override fun onError(message: String) {
        Log.e(TAG, "Player error: $message")
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        showBrowseMode()
    }

    // ── State management ─────────────────────────────────────────────

    private fun showBrowseMode() {
        isPlaying = false
        isLoadingSmb = false
        playerView.stop()
        smbStreamServer.stop()
        dictPopup.hide()
        cardCreator.visibility = View.GONE
        exitFullscreen()
        playMode.visibility = View.GONE
        // Launch browser
        browserLauncher.launch(Intent(this, BrowserActivity::class.java))
    }

    private fun showPlayMode() {
        isPlaying = true
        playMode.visibility = View.VISIBLE
        dictPopup.hide()
        cardCreator.visibility = View.GONE
        subtitleOverlay.applySettings(appSettings)
        enterFullscreen()
    }

    private fun enterFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).let {
            it.hide(WindowInsetsCompat.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun exitFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, true)
        WindowInsetsControllerCompat(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
    }

    // ── D-pad / remote support ───────────────────────────────────────

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            // Card creator is open - let normal focus navigation work
            if (cardCreator.visibility == View.VISIBLE) {
                if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                    cardCreator.visibility = View.GONE
                    return true
                }
                return super.dispatchKeyEvent(event)
            }

            if (isPlaying) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_BACK -> {
                        if (dictPopup.isVisible) {
                            dictPopup.hide()
                            playerView.play()
                        } else if (!playerView.isPaused) {
                            playerView.pause()
                        } else {
                            showBrowseMode()
                        }
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                        if (dictPopup.isVisible) {
                            dictPopup.hide()
                            playerView.play()
                        } else {
                            playerView.togglePause()
                        }
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        playerView.seekRelative(-10)
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        playerView.seekRelative(10)
                        return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // ── Playback ─────────────────────────────────────────────────────

    private fun handleBrowserResult(data: Intent) {
        val mode = data.getStringExtra(BrowserActivity.RESULT_MODE) ?: return
        when (mode) {
            "local" -> {
                val uriStr = data.getStringExtra(BrowserActivity.RESULT_LOCAL_URI) ?: return
                playFile(uriStr)
            }
            "smb" -> {
                if (isLoadingSmb) return
                isLoadingSmb = true
                val server = data.getStringExtra(BrowserActivity.RESULT_SMB_SERVER) ?: return
                val share = data.getStringExtra(BrowserActivity.RESULT_SMB_SHARE) ?: return
                val path = data.getStringExtra(BrowserActivity.RESULT_SMB_PATH) ?: return
                val user = data.getStringExtra(BrowserActivity.RESULT_SMB_USER) ?: ""
                val pass = data.getStringExtra(BrowserActivity.RESULT_SMB_PASS) ?: ""
                showPlayMode()
                Thread {
                    try {
                        val httpUrl = smbStreamServer.start(server, share, path, user, pass)
                        Log.d(TAG, "SMB proxy: $httpUrl")
                        currentPlaybackUrl = httpUrl
                        runOnUiThread {
                            isLoadingSmb = false
                            playerView.loadFile(httpUrl)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "SMB connect failed: ${e.message}")
                        runOnUiThread {
                            isLoadingSmb = false
                            Toast.makeText(this, "SMB error: ${e.message}", Toast.LENGTH_LONG).show()
                            showBrowseMode()
                        }
                    }
                }.start()
            }
        }
    }

    private fun playFile(path: String) {
        Log.d(TAG, "playFile: $path")
        currentPlaybackUrl = path
        showPlayMode()
        playerView.loadFile(path)
    }

    private fun lookupAndShow(surface: String, baseForm: String) {
        playerView.pause()
        Thread {
            val results = mutableListOf<DictionaryDatabase.DictEntry>()
            results.addAll(dictDb.lookup(baseForm))
            if (baseForm != surface) results.addAll(dictDb.lookup(surface))
            val unique = results.distinctBy { "${it.term}|${it.reading}" }
            runOnUiThread {
                if (unique.isNotEmpty()) {
                    currentDictEntries = unique
                    dictPopup.show(unique, surface)
                } else {
                    Toast.makeText(this, "No entry for: $baseForm", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    // ── Track controls ───────────────────────────────────────────────

    private fun showAudioTrackDialog() {
        val tracks = playerView.getTracks().filter { it.type == "audio" }
        if (tracks.isEmpty()) return
        val currentAid = try { dev.jdtech.mpv.MPVLib.getPropertyInt("aid") } catch (_: Exception) { 0 }
        val names = tracks.map { t ->
            buildString {
                append(t.title ?: t.lang?.uppercase() ?: "Track ${t.id}")
                t.codec?.let { append(" ($it)") }
            }
        }.toTypedArray()
        val selected = tracks.indexOfFirst { it.id == currentAid }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Audio Track")
            .setSingleChoiceItems(names, selected) { dialog, which ->
                playerView.setAudioTrack(tracks[which].id)
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun showSubtitleTrackDialog() {
        val tracks = playerView.getTracks().filter { it.type == "sub" }
        val names = mutableListOf("Off")
        names.addAll(tracks.map { t -> t.title ?: t.lang?.uppercase() ?: "Track ${t.id}" })
        names.add("Load external file...")
        val currentSid = try { dev.jdtech.mpv.MPVLib.getPropertyInt("sid") } catch (_: Exception) { 0 }
        val selected = if (currentSid <= 0) 0 else {
            val idx = tracks.indexOfFirst { it.id == currentSid }
            if (idx >= 0) idx + 1 else 0
        }
        AlertDialog.Builder(this)
            .setTitle("Subtitles")
            .setSingleChoiceItems(names.toTypedArray(), selected) { dialog, which ->
                when {
                    which == 0 -> { playerView.disableSubtitles(); subtitleOverlay.setSubtitleText(null) }
                    which == names.size - 1 -> {
                        subtitleBrowserLauncher.launch(
                            Intent(this, BrowserActivity::class.java)
                                .putExtra(BrowserActivity.EXTRA_FILE_MODE, "subtitle"))
                    }
                    else -> playerView.setSubtitleTrack(tracks[which - 1].id)
                }
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun showFontTypeDialog() {
        val fonts = AppSettings.FONTS.entries.toList()
        val names = fonts.map { it.value.displayName }.toTypedArray()
        val selected = fonts.indexOfFirst { it.key == appSettings.fontKey }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Subtitle Font")
            .setSingleChoiceItems(names, selected) { dialog, which ->
                appSettings.fontKey = fonts[which].key
                subtitleOverlay.applySettings(appSettings)
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun showFontSizeDialog() {
        val sizes = AppSettings.FONT_SIZES
        val names = sizes.map { "${it}sp" }.toTypedArray()
        val selected = sizes.indexOf(appSettings.fontSize).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Subtitle Size")
            .setSingleChoiceItems(names, selected) { dialog, which ->
                appSettings.fontSize = sizes[which]
                subtitleOverlay.applySettings(appSettings)
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun handleSubtitleResult(data: Intent) {
        val mode = data.getStringExtra(BrowserActivity.RESULT_MODE) ?: return
        when (mode) {
            "local" -> {
                val path = Uri.parse(data.getStringExtra(BrowserActivity.RESULT_LOCAL_URI) ?: return).path ?: return
                try {
                    dev.jdtech.mpv.MPVLib.command(arrayOf("sub-add", path, "select"))
                    Toast.makeText(this, "Subtitle loaded", Toast.LENGTH_SHORT).show()
                } catch (_: Exception) {
                    Toast.makeText(this, "Failed to load subtitle", Toast.LENGTH_SHORT).show()
                }
            }
            "smb" -> {
                val server = data.getStringExtra(BrowserActivity.RESULT_SMB_SERVER) ?: return
                val share = data.getStringExtra(BrowserActivity.RESULT_SMB_SHARE) ?: return
                val smbPath = data.getStringExtra(BrowserActivity.RESULT_SMB_PATH) ?: return
                val user = data.getStringExtra(BrowserActivity.RESULT_SMB_USER) ?: ""
                val pass = data.getStringExtra(BrowserActivity.RESULT_SMB_PASS) ?: ""
                Toast.makeText(this, "Loading subtitle...", Toast.LENGTH_SHORT).show()
                Thread {
                    try {
                        val fileName = smbPath.substringAfterLast("\\")
                        val tempFile = java.io.File(cacheDir, "subs_$fileName")
                        val config = com.hierynomus.smbj.SmbConfig.builder().build()
                        val client = com.hierynomus.smbj.SMBClient(config)
                        val conn = client.connect(server)
                        val auth = if (user.isNotEmpty()) com.hierynomus.smbj.auth.AuthenticationContext(user, pass.toCharArray(), "")
                        else com.hierynomus.smbj.auth.AuthenticationContext.guest()
                        val session = conn.authenticate(auth)
                        val ds = session.connectShare(share) as com.hierynomus.smbj.share.DiskShare
                        val file = ds.openFile(smbPath,
                            java.util.EnumSet.of(com.hierynomus.msdtyp.AccessMask.GENERIC_READ),
                            java.util.EnumSet.of(com.hierynomus.msfscc.FileAttributes.FILE_ATTRIBUTE_NORMAL),
                            com.hierynomus.mssmb2.SMB2ShareAccess.ALL,
                            com.hierynomus.mssmb2.SMB2CreateDisposition.FILE_OPEN,
                            java.util.EnumSet.noneOf(com.hierynomus.mssmb2.SMB2CreateOptions::class.java))
                        tempFile.outputStream().use { out -> file.inputStream.copyTo(out) }
                        file.close(); ds.close(); session.close(); conn.close(); client.close()
                        runOnUiThread {
                            dev.jdtech.mpv.MPVLib.command(arrayOf("sub-add", tempFile.absolutePath, "select"))
                            Toast.makeText(this, "Subtitle loaded", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        runOnUiThread { Toast.makeText(this, "Failed to load subtitle", Toast.LENGTH_SHORT).show() }
                    }
                }.start()
            }
        }
    }

    private fun formatTime(seconds: Double): String {
        val s = seconds.toInt().coerceAtLeast(0)
        val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    // ── Lifecycle ────────────────────────────────────────────────────

    override fun onResume() {
        super.onResume()
        subtitleOverlay.applySettings(appSettings)
    }

    override fun onPause() {
        super.onPause()
        if (isPlaying) playerView.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        smbStreamServer.stop()
        playerView.destroy()
    }
}
