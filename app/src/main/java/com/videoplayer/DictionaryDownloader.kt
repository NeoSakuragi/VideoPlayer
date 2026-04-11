package com.videoplayer

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipFile

class DictionaryDownloader(private val context: Context) {

    companion object {
        private const val TAG = "DictDownloader"
    }

    interface ProgressListener {
        fun onProgress(phase: String, percent: Int)
        fun onComplete(success: Boolean, message: String)
    }

    fun download(entry: DictCatalogEntry, listener: ProgressListener) {
        Thread {
            try {
                listener.onProgress("Downloading...", 0)
                val zipFile = downloadZip(entry.url, entry.id) { percent ->
                    listener.onProgress("Downloading...", percent)
                }

                listener.onProgress("Importing...", 0)
                val count = importZip(zipFile, entry) { percent ->
                    listener.onProgress("Importing...", percent)
                }

                zipFile.delete()

                val db = DictionaryDatabase.getInstance(context)
                db.markInstalled(entry.id, entry.name, entry.type.name, count)

                listener.onComplete(true, "$count entries imported")
            } catch (e: Exception) {
                Log.e(TAG, "Download failed: ${e.message}", e)
                listener.onComplete(false, e.message ?: "Unknown error")
            }
        }.start()
    }

    private fun downloadZip(urlStr: String, id: String, onProgress: (Int) -> Unit): File {
        val tempFile = File(context.cacheDir, "dict_${id}.zip")
        var connection: HttpURLConnection? = null
        try {
            var currentUrl = urlStr
            var redirects = 0
            while (redirects < 5) {
                connection = URL(currentUrl).openConnection() as HttpURLConnection
                connection.connectTimeout = 30_000
                connection.readTimeout = 60_000
                connection.instanceFollowRedirects = false
                connection.connect()

                val code = connection.responseCode
                if (code in 301..303 || code == 307 || code == 308) {
                    currentUrl = connection.getHeaderField("Location") ?: throw Exception("Redirect with no Location")
                    connection.disconnect()
                    redirects++
                    continue
                }
                if (code != 200) throw Exception("HTTP $code")
                break
            }

            val conn = connection ?: throw Exception("No connection")
            val totalBytes = conn.contentLength.toLong()
            var downloaded = 0L

            BufferedInputStream(conn.inputStream, 8192).use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(8192)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (totalBytes > 0) {
                            onProgress((downloaded * 100 / totalBytes).toInt())
                        }
                    }
                }
            }
        } finally {
            connection?.disconnect()
        }

        Log.d(TAG, "Downloaded: ${tempFile.length() / 1024}KB")
        return tempFile
    }

    private fun importZip(zipFile: File, entry: DictCatalogEntry, onProgress: (Int) -> Unit): Int {
        val db = DictionaryDatabase.getInstance(context).getWritableDb()
            ?: throw Exception("Database not available")

        val zip = ZipFile(zipFile)
        val allEntries = zip.entries().toList()

        // Find relevant bank files
        val bankFiles = allEntries.filter { ze ->
            val name = ze.name.substringAfterLast("/")
            name.startsWith("term_bank_") || name.startsWith("term_meta_bank_") || name.startsWith("kanji_bank_")
        }.sortedBy { it.name }

        if (bankFiles.isEmpty()) {
            zip.close()
            throw Exception("No data files found in zip")
        }

        var totalCount = 0
        db.beginTransaction()
        try {
            bankFiles.forEachIndexed { index, ze ->
                val name = ze.name.substringAfterLast("/")
                val jsonStr = zip.getInputStream(ze).bufferedReader().readText()
                val arr = JSONArray(jsonStr)

                when {
                    name.startsWith("term_bank_") -> {
                        totalCount += importTermBank(db, arr, entry.id)
                    }
                    name.startsWith("term_meta_bank_") -> {
                        totalCount += importTermMetaBank(db, arr, entry.id, entry.type)
                    }
                    name.startsWith("kanji_bank_") -> {
                        totalCount += importKanjiBank(db, arr, entry.id)
                    }
                }

                onProgress(((index + 1) * 100) / bankFiles.size)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        zip.close()

        Log.d(TAG, "Imported $totalCount entries for ${entry.id}")
        return totalCount
    }

    private fun importTermBank(db: SQLiteDatabase, arr: JSONArray, source: String): Int {
        var count = 0
        for (i in 0 until arr.length()) {
            val e = arr.getJSONArray(i)
            val term = e.getString(0)
            val reading = e.optString(1, term)
            val tags = e.optString(2, "")
            val score = e.optInt(4, 0)
            val meanings = extractMeanings(e.opt(5))
            val sequence = e.optInt(6, 0)

            val cv = ContentValues().apply {
                put("term", term)
                put("reading", if (reading.isEmpty()) term else reading)
                put("tags", tags)
                put("score", score)
                put("meanings", meanings)
                put("sequence", sequence)
                put("source", source)
            }
            db.insert("dict_entries", null, cv)
            count++
        }
        return count
    }

    private fun importTermMetaBank(db: SQLiteDatabase, arr: JSONArray, source: String, type: DictType): Int {
        var count = 0
        for (i in 0 until arr.length()) {
            val e = arr.getJSONArray(i)
            val term = e.getString(0)
            val mode = e.getString(1)

            when (mode) {
                "freq" -> {
                    val freqData = e.opt(2)
                    val freq = extractFrequency(freqData)
                    val reading = if (freqData is JSONObject) freqData.optString("reading") else null
                    if (freq > 0) {
                        val cv = ContentValues().apply {
                            put("term", term)
                            put("reading", reading)
                            put("freq", freq)
                            put("source", source)
                        }
                        db.insert("frequencies", null, cv)
                        count++
                    }
                }
                "pitch" -> {
                    val pitchData = e.opt(2)
                    val reading = if (pitchData is JSONObject) pitchData.optString("reading") else null
                    val cv = ContentValues().apply {
                        put("term", term)
                        put("reading", reading)
                        put("pitch_data", pitchData.toString())
                        put("source", source)
                    }
                    db.insert("pitch_accents", null, cv)
                    count++
                }
            }
        }
        return count
    }

    private fun importKanjiBank(db: SQLiteDatabase, arr: JSONArray, source: String): Int {
        var count = 0
        for (i in 0 until arr.length()) {
            val e = arr.getJSONArray(i)
            val character = e.getString(0)
            val onyomi = e.optString(1, "")
            val kunyomi = e.optString(2, "")
            val tags = e.optString(3, "")
            val meanings = extractKanjiMeanings(e.opt(4))
            val stats = e.opt(5)?.toString() ?: ""

            val cv = ContentValues().apply {
                put("character", character)
                put("onyomi", onyomi)
                put("kunyomi", kunyomi)
                put("tags", tags)
                put("meanings", meanings)
                put("stats", stats)
                put("source", source)
            }
            db.insert("kanji_entries", null, cv)
            count++
        }
        return count
    }

    private fun extractFrequency(data: Any?): Int {
        return when (data) {
            is Int -> data
            is Long -> data.toInt()
            is Double -> data.toInt()
            is String -> data.toIntOrNull() ?: 0
            is JSONObject -> {
                val fv = data.opt("frequency") ?: data.opt("value") ?: return 0
                when (fv) {
                    is Int -> fv
                    is Long -> fv.toInt()
                    is Double -> fv.toInt()
                    is JSONObject -> fv.optInt("value", 0)
                    is String -> fv.toIntOrNull() ?: 0
                    else -> 0
                }
            }
            else -> 0
        }
    }

    private fun extractMeanings(data: Any?): String {
        if (data == null) return ""
        if (data is String) return data
        if (data is JSONArray) {
            val parts = mutableListOf<String>()
            for (i in 0 until data.length()) {
                val item = data.opt(i)
                when (item) {
                    is String -> parts.add(item)
                    is JSONObject -> {
                        val type = item.optString("type", "")
                        if (type == "text") {
                            parts.add(item.optString("text", ""))
                        } else if (type == "structured-content") {
                            parts.add(extractStructuredContent(item.opt("content")))
                        }
                    }
                }
            }
            return parts.filter { it.isNotBlank() }.joinToString("\n")
        }
        return data.toString()
    }

    private fun extractStructuredContent(content: Any?): String {
        if (content == null) return ""
        if (content is String) return content
        if (content is JSONArray) {
            val sb = StringBuilder()
            for (i in 0 until content.length()) {
                sb.append(extractStructuredContent(content.opt(i)))
            }
            return sb.toString()
        }
        if (content is JSONObject) {
            val tag = content.optString("tag", "")
            if (tag == "br") return "\n"
            if (tag == "li") return "- ${extractStructuredContent(content.opt("content"))}\n"
            if (tag == "rt" || tag == "rp") return ""
            return extractStructuredContent(content.opt("content"))
        }
        return ""
    }

    private fun extractKanjiMeanings(data: Any?): String {
        if (data == null) return ""
        if (data is String) return data
        if (data is JSONArray) {
            val parts = mutableListOf<String>()
            for (i in 0 until data.length()) {
                val item = data.opt(i)
                if (item is String) parts.add(item)
            }
            return parts.joinToString("\n")
        }
        return data.toString()
    }
}
