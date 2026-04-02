package com.videoplayer

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
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
    private lateinit var browseMode: View
    private lateinit var playMode: View
    private lateinit var pauseControls: LinearLayout
    private lateinit var btnBrowse: MaterialButton
    private lateinit var btnSettings: MaterialButton
    private lateinit var btnAudioTrack: MaterialButton
    private lateinit var btnSubtitleTrack: MaterialButton
    private lateinit var seekBar: SeekBar
    private lateinit var tvPosition: TextView
    private lateinit var tvDuration: TextView

    private lateinit var appSettings: AppSettings
    private lateinit var dictDb: DictionaryDatabase
    private lateinit var dictPopup: DictionaryPopup
    private lateinit var dictManager: DictionaryManager

    private var isPlaying = false
    private val smbStreamServer = SmbStreamServer()

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

        browseMode = findViewById(R.id.browseMode)
        playMode = findViewById(R.id.playMode)
        pauseControls = findViewById(R.id.pauseControls)
        playerView = findViewById(R.id.playerView)
        subtitleOverlay = findViewById(R.id.subtitleOverlay)
        btnBrowse = findViewById(R.id.btnBrowse)
        btnSettings = findViewById(R.id.btnSettings)
        btnAudioTrack = findViewById(R.id.btnAudioTrack)
        btnSubtitleTrack = findViewById(R.id.btnSubtitleTrack)
        seekBar = findViewById(R.id.seekBar)
        tvPosition = findViewById(R.id.tvPosition)
        tvDuration = findViewById(R.id.tvDuration)

        // Settings
        appSettings = AppSettings(this)

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

        // Tap on video dismisses popup if visible, otherwise toggles pause
        playerView.onTapInterceptor = {
            if (dictPopup.isVisible) {
                dictPopup.hide()
                playerView.play()
                true
            } else false
        }

        btnBrowse.setOnClickListener {
            browserLauncher.launch(Intent(this, BrowserActivity::class.java))
        }
        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        btnAudioTrack.setOnClickListener { showAudioTrackDialog() }
        btnSubtitleTrack.setOnClickListener { showSubtitleTrackDialog() }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val dur = playerView.duration
                    if (dur > 0) {
                        val pos = dur * progress / 1000.0
                        tvPosition.text = formatTime(pos)
                    }
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {
                val dur = playerView.duration
                if (dur > 0) {
                    playerView.seekTo(dur * sb.progress / 1000.0)
                }
            }
        })

        // Start in browse mode
        showBrowseMode()

        // Handle intent (opened from file manager)
        intent?.data?.let { uri ->
            playFile(uri.toString())
        }
    }

    // ── MpvPlayerView.Listener ───────────────────────────────────────

    override fun onSubtitleTextChanged(text: String) {
        subtitleOverlay.setSubtitleText(text.ifEmpty { null })
    }

    override fun onPauseChanged(paused: Boolean) {
        if (!isPlaying) return
        pauseControls.visibility = if (paused) View.VISIBLE else View.GONE
    }

    override fun onPositionChanged(positionSec: Double, durationSec: Double) {
        tvPosition.text = formatTime(positionSec)
        tvDuration.text = formatTime(durationSec)
        if (durationSec > 0 && !seekBar.isPressed) {
            seekBar.progress = (positionSec / durationSec * 1000).toInt()
        }
    }

    override fun onFileLoaded() {
        Log.d(TAG, "File loaded")
    }

    override fun onFileEnded() {
        Log.d(TAG, "File ended")
        showBrowseMode()
    }

    override fun onTracksChanged() {
        // Tracks available now
    }

    override fun onError(message: String) {
        Log.e(TAG, "Player error: $message")
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        showBrowseMode()
    }

    // ── State management ─────────────────────────────────────────────

    private fun showBrowseMode() {
        isPlaying = false
        playerView.stop()
        smbStreamServer.stop()
        dictPopup.hide()
        exitFullscreen()
        browseMode.visibility = View.VISIBLE
        playMode.visibility = View.GONE
        btnBrowse.isFocusable = true
        btnBrowse.isFocusableInTouchMode = true
        btnBrowse.requestFocus()
    }

    private fun showPlayMode() {
        isPlaying = true
        browseMode.visibility = View.GONE
        playMode.visibility = View.VISIBLE
        dictPopup.hide()
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
        if (isPlaying && event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_BACK -> {
                    if (!playerView.isPaused) {
                        playerView.pause()
                    } else {
                        showBrowseMode()
                    }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    playerView.togglePause()
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
                        runOnUiThread { playerView.loadFile(httpUrl) }
                    } catch (e: Exception) {
                        Log.e(TAG, "SMB connect failed: ${e.message}")
                        runOnUiThread {
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
                if (unique.isNotEmpty()) dictPopup.show(unique, surface)
                else Toast.makeText(this, "No entry for: $baseForm", Toast.LENGTH_SHORT).show()
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
        names.addAll(tracks.map { t ->
            t.title ?: t.lang?.uppercase() ?: "Track ${t.id}"
        })
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
                    which == 0 -> {
                        playerView.disableSubtitles()
                        subtitleOverlay.setSubtitleText(null)
                    }
                    which == names.size - 1 -> {
                        subtitleBrowserLauncher.launch(
                            Intent(this, BrowserActivity::class.java)
                                .putExtra(BrowserActivity.EXTRA_FILE_MODE, "subtitle")
                        )
                    }
                    else -> {
                        playerView.setSubtitleTrack(tracks[which - 1].id)
                    }
                }
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun handleSubtitleResult(data: Intent) {
        val mode = data.getStringExtra(BrowserActivity.RESULT_MODE) ?: return
        when (mode) {
            "local" -> {
                val uriStr = data.getStringExtra(BrowserActivity.RESULT_LOCAL_URI) ?: return
                val path = Uri.parse(uriStr).path ?: return
                Log.d(TAG, "Loading external subtitle: $path")
                try {
                    dev.jdtech.mpv.MPVLib.command(arrayOf("sub-add", path, "select"))
                    Toast.makeText(this, "Subtitle loaded", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "Failed to load subtitle", Toast.LENGTH_SHORT).show()
                }
            }
            "smb" -> {
                // For SMB subtitles, download to cache first
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
                        // Download via SMB
                        val config = com.hierynomus.smbj.SmbConfig.builder().build()
                        val client = com.hierynomus.smbj.SMBClient(config)
                        val conn = client.connect(server)
                        val auth = if (user.isNotEmpty())
                            com.hierynomus.smbj.auth.AuthenticationContext(user, pass.toCharArray(), "")
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
                        Log.e(TAG, "Failed to load SMB subtitle: ${e.message}")
                        runOnUiThread { Toast.makeText(this, "Failed to load subtitle", Toast.LENGTH_SHORT).show() }
                    }
                }.start()
            }
        }
    }

    private fun formatTime(seconds: Double): String {
        val s = seconds.toInt().coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
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
