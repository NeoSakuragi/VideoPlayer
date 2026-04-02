package com.videoplayer

import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class AnkiConnectClient(private val baseUrl: String) {

    companion object {
        private const val TAG = "AnkiConnect"
        private const val VERSION = 6
    }

    data class AnkiResult(val success: Boolean, val message: String, val data: Any? = null)

    private fun request(action: String, params: JSONObject? = null): AnkiResult {
        return try {
            val body = JSONObject().apply {
                put("action", action)
                put("version", VERSION)
                if (params != null) put("params", params)
            }

            val url = URL(baseUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = 10000
            conn.readTimeout = 30000
            conn.doOutput = true

            conn.outputStream.use { it.write(body.toString().toByteArray()) }

            val responseCode = conn.responseCode
            val response = conn.inputStream.bufferedReader().readText()
            conn.disconnect()

            Log.d(TAG, "$action response ($responseCode): $response")
            val json = JSONObject(response)
            val error = json.opt("error")
            if (error != null && error != JSONObject.NULL) {
                AnkiResult(false, error.toString())
            } else {
                AnkiResult(true, "OK", json.opt("result"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "$action failed: ${e.message}")
            AnkiResult(false, e.message ?: "Connection failed")
        }
    }

    fun testConnection(): AnkiResult {
        return request("version")
    }

    fun getDeckNames(): List<String> {
        val result = request("deckNames")
        if (!result.success) return emptyList()
        val arr = result.data as? JSONArray ?: return emptyList()
        return (0 until arr.length()).map { arr.getString(it) }
    }

    fun getModelNames(): List<String> {
        val result = request("modelNames")
        if (!result.success) return emptyList()
        val arr = result.data as? JSONArray ?: return emptyList()
        return (0 until arr.length()).map { arr.getString(it) }
    }

    fun getModelFieldNames(modelName: String): List<String> {
        val params = JSONObject().put("modelName", modelName)
        val result = request("modelFieldNames", params)
        if (!result.success) return emptyList()
        val arr = result.data as? JSONArray ?: return emptyList()
        return (0 until arr.length()).map { arr.getString(it) }
    }

    fun storeMediaFile(fileName: String, file: File): AnkiResult {
        val data = Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
        val params = JSONObject().apply {
            put("filename", fileName)
            put("data", data)
        }
        return request("storeMediaFile", params)
    }

    fun addNote(
        deckName: String,
        modelName: String,
        fields: Map<String, String>,
        tags: List<String>,
        audioFile: File? = null,
        audioFieldName: String? = null,
        imageFile: File? = null,
        imageFieldName: String? = null
    ): AnkiResult {
        val fieldsJson = JSONObject()
        for ((key, value) in fields) {
            fieldsJson.put(key, value)
        }

        val noteJson = JSONObject().apply {
            put("deckName", deckName)
            put("modelName", modelName)
            put("fields", fieldsJson)
            put("tags", JSONArray(tags))

            // Audio attachment
            if (audioFile != null && audioFieldName != null && audioFile.exists()) {
                val audioArr = JSONArray()
                audioArr.put(JSONObject().apply {
                    put("filename", audioFile.name)
                    put("data", Base64.encodeToString(audioFile.readBytes(), Base64.NO_WRAP))
                    put("fields", JSONArray().put(audioFieldName))
                })
                put("audio", audioArr)
            }

            // Image attachment
            if (imageFile != null && imageFieldName != null && imageFile.exists()) {
                val imgArr = JSONArray()
                imgArr.put(JSONObject().apply {
                    put("filename", imageFile.name)
                    put("data", Base64.encodeToString(imageFile.readBytes(), Base64.NO_WRAP))
                    put("fields", JSONArray().put(imageFieldName))
                })
                put("picture", imgArr)
            }
        }

        val params = JSONObject().put("note", noteJson)
        return request("addNote", params)
    }
}
