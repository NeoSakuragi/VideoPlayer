package com.videoplayer

import android.util.Log
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File as SmbFile
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.EnumSet
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SmbStreamServer {

    companion object {
        private const val TAG = "SmbStream"
    }

    private var serverSocket: ServerSocket? = null
    private val executor = Executors.newCachedThreadPool()

    // Persistent SMB connection
    private var smbClient: SMBClient? = null
    private var diskShare: DiskShare? = null
    private var smbPath = ""
    private var fileSize = 0L

    var port: Int = 0; private set

    fun start(server: String, share: String, path: String, user: String, pass: String): String {
        stop()
        smbPath = path

        // Establish persistent SMB connection
        val config = SmbConfig.builder()
            .withTimeout(60, TimeUnit.SECONDS)
            .withSoTimeout(60, TimeUnit.SECONDS)
            .build()
        smbClient = SMBClient(config)
        val conn = smbClient!!.connect(server)
        val auth = if (user.isNotEmpty()) AuthenticationContext(user, pass.toCharArray(), "")
            else AuthenticationContext.guest()
        val session = conn.authenticate(auth)
        diskShare = session.connectShare(share) as DiskShare

        // Get file size
        val file = openFile()
        fileSize = file.fileInformation.standardInformation.endOfFile
        file.close()
        Log.d(TAG, "SMB file ready: $path, size=$fileSize")

        serverSocket = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        port = serverSocket!!.localPort
        Log.d(TAG, "HTTP server on port $port")

        executor.execute {
            while (true) {
                val sock = try { serverSocket?.accept() } catch (_: Exception) { break } ?: break
                executor.execute { handleConnection(sock) }
            }
        }

        return "http://127.0.0.1:$port/video"
    }

    fun stop() {
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        try { diskShare?.close() } catch (_: Exception) {}
        try { smbClient?.close() } catch (_: Exception) {}
        diskShare = null
        smbClient = null
    }

    private fun openFile(): SmbFile {
        return diskShare!!.openFile(
            smbPath,
            EnumSet.of(AccessMask.GENERIC_READ),
            EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            EnumSet.noneOf(SMB2CreateOptions::class.java)
        )
    }

    private fun handleConnection(sock: Socket) {
        try {
            val input = sock.getInputStream().bufferedReader()
            val requestLine = input.readLine() ?: return

            var rangeStart = 0L
            var rangeEnd = -1L
            while (true) {
                val line = input.readLine() ?: break
                if (line.isEmpty()) break
                if (line.startsWith("Range:", ignoreCase = true)) {
                    val rangeStr = line.substringAfter("bytes=").trim()
                    val parts = rangeStr.split("-")
                    rangeStart = parts[0].toLongOrNull() ?: 0
                    rangeEnd = if (parts.size > 1 && parts[1].isNotEmpty()) parts[1].toLongOrNull() ?: -1 else -1
                }
            }

            val actualEnd = if (rangeEnd < 0) fileSize - 1 else minOf(rangeEnd, fileSize - 1)
            val contentLength = actualEnd - rangeStart + 1

            val out = sock.getOutputStream()

            if (rangeStart > 0) {
                writeResponse(out, 206, contentLength, fileSize, rangeStart, actualEnd)
            } else {
                writeResponse(out, 200, fileSize, fileSize, 0, fileSize - 1)
            }

            // Open a fresh file handle for each request (safe for concurrent range requests)
            val file = openFile()
            val stream = file.inputStream
            if (rangeStart > 0) stream.skip(rangeStart)

            val buf = ByteArray(65536)
            var remaining = contentLength
            while (remaining > 0) {
                val toRead = minOf(buf.size.toLong(), remaining).toInt()
                val read = stream.read(buf, 0, toRead)
                if (read <= 0) break
                try {
                    out.write(buf, 0, read)
                } catch (_: Exception) {
                    break // client closed connection (e.g. seeking)
                }
                remaining -= read
            }

            out.flush()
            stream.close()
            file.close()
        } catch (e: Exception) {
            // Don't log "Connection reset" / "Broken pipe" — normal when mpv seeks
            val msg = e.message ?: ""
            if (!msg.contains("reset") && !msg.contains("Broken pipe")) {
                Log.w(TAG, "Connection error: $msg")
            }
        } finally {
            try { sock.close() } catch (_: Exception) {}
        }
    }

    private fun writeResponse(out: OutputStream, code: Int, contentLength: Long, totalSize: Long, rangeStart: Long, rangeEnd: Long) {
        val status = if (code == 206) "206 Partial Content" else "200 OK"
        val sb = StringBuilder()
        sb.append("HTTP/1.1 $status\r\n")
        sb.append("Content-Type: application/octet-stream\r\n")
        sb.append("Content-Length: $contentLength\r\n")
        sb.append("Accept-Ranges: bytes\r\n")
        if (code == 206) {
            sb.append("Content-Range: bytes $rangeStart-$rangeEnd/$totalSize\r\n")
        }
        sb.append("Connection: close\r\n")
        sb.append("\r\n")
        out.write(sb.toString().toByteArray())
    }
}
