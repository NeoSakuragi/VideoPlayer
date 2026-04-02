package com.videoplayer

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import dev.jdtech.mpv.MPVLib
import java.io.File
import java.nio.ByteBuffer

/**
 * Captures screenshots and audio clips from the currently playing video.
 */
class MediaCapture(private val context: Context) {

    companion object {
        private const val TAG = "MediaCapture"
    }

    /**
     * Capture a screenshot of the current video frame via mpv.
     * Returns the file path, or null on failure.
     */
    fun captureScreenshot(): File? {
        return try {
            val file = File(context.cacheDir, "anki_screenshot_${System.currentTimeMillis()}.jpg")
            MPVLib.command(arrayOf("screenshot-to-file", file.absolutePath, "video"))
            if (file.exists() && file.length() > 0) {
                Log.d(TAG, "Screenshot saved: ${file.absolutePath} (${file.length()} bytes)")
                file
            } else {
                Log.w(TAG, "Screenshot file empty or missing")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Screenshot failed: ${e.message}")
            null
        }
    }

    /**
     * Get current subtitle timing from mpv.
     * Returns (startSec, endSec) or null if not available.
     */
    fun getSubtitleTiming(): Pair<Double, Double>? {
        return try {
            val start = MPVLib.getPropertyDouble("sub-start") ?: return null
            val end = MPVLib.getPropertyDouble("sub-end") ?: return null
            if (start >= 0 && end > start) Pair(start, end) else null
        } catch (e: Exception) {
            Log.w(TAG, "Cannot get subtitle timing: ${e.message}")
            null
        }
    }

    /**
     * Extract an audio clip from a media file between startSec and endSec.
     * Uses Android's MediaExtractor + MediaMuxer.
     * @param sourceUrl The URL or file path of the source media
     * @param startSec Start time in seconds
     * @param endSec End time in seconds
     * @return The output audio file, or null on failure
     */
    fun extractAudio(sourceUrl: String, startSec: Double, endSec: Double): File? {
        val outFile = File(context.cacheDir, "anki_audio_${System.currentTimeMillis()}.mp3")

        return try {
            val extractor = MediaExtractor()
            extractor.setDataSource(sourceUrl)

            // Find audio track
            var audioTrackIndex = -1
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    break
                }
            }

            if (audioTrackIndex < 0) {
                Log.w(TAG, "No audio track found")
                extractor.release()
                return null
            }

            extractor.selectTrack(audioTrackIndex)
            val audioFormat = extractor.getTrackFormat(audioTrackIndex)

            val startUs = (startSec * 1_000_000).toLong()
            val endUs = (endSec * 1_000_000).toLong()

            // Seek to start
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

            val muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val muxerTrackIndex = muxer.addTrack(audioFormat)
            muxer.start()

            val buffer = ByteBuffer.allocate(1024 * 1024)
            val bufferInfo = MediaCodec.BufferInfo()

            while (true) {
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break

                val sampleTime = extractor.sampleTime
                if (sampleTime > endUs) break

                if (sampleTime >= startUs) {
                    bufferInfo.offset = 0
                    bufferInfo.size = sampleSize
                    bufferInfo.presentationTimeUs = sampleTime - startUs
                    bufferInfo.flags = extractor.sampleFlags
                    muxer.writeSampleData(muxerTrackIndex, buffer, bufferInfo)
                }

                extractor.advance()
            }

            muxer.stop()
            muxer.release()
            extractor.release()

            if (outFile.exists() && outFile.length() > 0) {
                Log.d(TAG, "Audio extracted: ${outFile.absolutePath} (${outFile.length()} bytes)")
                // Rename to .mp4 since MediaMuxer outputs MPEG4 container
                val mp4File = File(outFile.absolutePath.replace(".mp3", ".mp4"))
                outFile.renameTo(mp4File)
                mp4File
            } else {
                Log.w(TAG, "Audio extraction produced empty file")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Audio extraction failed: ${e.message}", e)
            null
        }
    }
}
