package com.videoplayer

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import java.io.File
import java.io.FileOutputStream

class DictionaryDatabase private constructor(private val context: Context) {

    companion object {
        private const val TAG = "DictDB"
        private const val DB_NAME = "dictionary.db"

        @Volatile
        private var instance: DictionaryDatabase? = null

        fun getInstance(context: Context): DictionaryDatabase {
            return instance ?: synchronized(this) {
                instance ?: DictionaryDatabase(context.applicationContext).also { instance = it }
            }
        }
    }

    private val dbFile = context.getDatabasePath(DB_NAME)
    private var db: SQLiteDatabase? = null

    fun isReady(): Boolean = dbFile.exists() && dbFile.length() > 1000

    fun ensureReady() {
        if (isReady()) {
            openDb()
            return
        }
        Log.d(TAG, "Copying pre-built dictionary from assets...")
        dbFile.parentFile?.mkdirs()
        context.assets.open(DB_NAME).use { input ->
            FileOutputStream(dbFile).use { output ->
                input.copyTo(output, 8192)
            }
        }
        Log.d(TAG, "Dictionary copied: ${dbFile.length() / 1_048_576} MB")
        openDb()
    }

    private fun openDb() {
        if (db == null || !db!!.isOpen) {
            db = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        }
    }

    // ── Query methods ────────────────────────────────────────────────

    data class DictEntry(
        val term: String,
        val reading: String,
        val tags: String,
        val score: Int,
        val meanings: List<String>,
        val frequency: Int?
    )

    fun lookup(term: String): List<DictEntry> {
        val db = db ?: return emptyList()
        // Look up by exact term or reading match
        val cursor = db.rawQuery("""
            SELECT e.term, e.reading, e.tags, e.score, e.meanings,
                   (SELECT f.freq FROM frequencies f WHERE f.term = e.term LIMIT 1) as freq
            FROM dict_entries e
            WHERE e.term = ? OR e.reading = ?
            ORDER BY e.score DESC
            LIMIT 20
        """, arrayOf(term, term))

        val results = mutableListOf<DictEntry>()
        while (cursor.moveToNext()) {
            results.add(DictEntry(
                term = cursor.getString(0),
                reading = cursor.getString(1),
                tags = cursor.getString(2) ?: "",
                score = cursor.getInt(3),
                meanings = cursor.getString(4)?.split("\n")?.filter { it.isNotBlank() } ?: emptyList(),
                frequency = if (cursor.isNull(5)) null else cursor.getInt(5)
            ))
        }
        cursor.close()
        return results
    }

    fun getFrequency(term: String): Int? {
        val db = db ?: return null
        val cursor = db.rawQuery("SELECT freq FROM frequencies WHERE term = ? LIMIT 1", arrayOf(term))
        val freq = if (cursor.moveToFirst()) cursor.getInt(0) else null
        cursor.close()
        return freq
    }

    fun getEntryCount(): Int {
        val db = db ?: return 0
        val cursor = db.rawQuery("SELECT COUNT(*) FROM dict_entries", null)
        val count = if (cursor.moveToFirst()) cursor.getInt(0) else 0
        cursor.close()
        return count
    }

    fun getFrequencyCount(): Int {
        val db = db ?: return 0
        val cursor = db.rawQuery("SELECT COUNT(*) FROM frequencies", null)
        val count = if (cursor.moveToFirst()) cursor.getInt(0) else 0
        cursor.close()
        return count
    }
}
