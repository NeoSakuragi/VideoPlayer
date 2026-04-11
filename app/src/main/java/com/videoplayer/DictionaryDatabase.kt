package com.videoplayer

import android.content.ContentValues
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
            ensureExtraTables()
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
        ensureExtraTables()
    }

    private fun openDb() {
        if (db == null || !db!!.isOpen) {
            db = SQLiteDatabase.openDatabase(dbFile.absolutePath, null,
                SQLiteDatabase.OPEN_READWRITE)
        }
    }

    private fun ensureExtraTables() {
        val db = db ?: return

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS installed_dicts (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                type TEXT NOT NULL,
                entry_count INTEGER DEFAULT 0,
                installed_at INTEGER DEFAULT 0
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS pitch_accents (
                term TEXT NOT NULL,
                reading TEXT,
                pitch_data TEXT NOT NULL,
                source TEXT NOT NULL
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS kanji_entries (
                character TEXT NOT NULL,
                onyomi TEXT,
                kunyomi TEXT,
                tags TEXT,
                meanings TEXT,
                stats TEXT,
                source TEXT NOT NULL
            )
        """)

        // Add source column to existing tables if not present
        try {
            db.execSQL("ALTER TABLE dict_entries ADD COLUMN source TEXT DEFAULT 'bundled'")
        } catch (_: Exception) {} // column already exists

        try {
            db.execSQL("ALTER TABLE frequencies ADD COLUMN source TEXT DEFAULT 'bundled'")
        } catch (_: Exception) {}

        // Create indexes for new tables if they don't exist
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_pitch_term ON pitch_accents(term)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_kanji_char ON kanji_entries(character)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_dict_entries_source ON dict_entries(source)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_freq_source ON frequencies(source)")
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

    data class PitchAccent(
        val term: String,
        val reading: String?,
        val pitchData: String
    )

    data class KanjiEntry(
        val character: String,
        val onyomi: String,
        val kunyomi: String,
        val tags: String,
        val meanings: List<String>,
        val stats: String
    )

    fun lookup(term: String): List<DictEntry> {
        val db = db ?: return emptyList()
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

    fun lookupPitchAccent(term: String): List<PitchAccent> {
        val db = db ?: return emptyList()
        val cursor = db.rawQuery(
            "SELECT term, reading, pitch_data FROM pitch_accents WHERE term = ?",
            arrayOf(term)
        )
        val results = mutableListOf<PitchAccent>()
        while (cursor.moveToNext()) {
            results.add(PitchAccent(
                term = cursor.getString(0),
                reading = cursor.getString(1),
                pitchData = cursor.getString(2)
            ))
        }
        cursor.close()
        return results
    }

    fun lookupKanji(character: String): KanjiEntry? {
        val db = db ?: return null
        val cursor = db.rawQuery(
            "SELECT character, onyomi, kunyomi, tags, meanings, stats FROM kanji_entries WHERE character = ? LIMIT 1",
            arrayOf(character)
        )
        val entry = if (cursor.moveToFirst()) {
            KanjiEntry(
                character = cursor.getString(0),
                onyomi = cursor.getString(1) ?: "",
                kunyomi = cursor.getString(2) ?: "",
                tags = cursor.getString(3) ?: "",
                meanings = cursor.getString(4)?.split("\n")?.filter { it.isNotBlank() } ?: emptyList(),
                stats = cursor.getString(5) ?: ""
            )
        } else null
        cursor.close()
        return entry
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

    // ── Import methods (called by DictionaryDownloader) ──────────────

    fun getWritableDb(): SQLiteDatabase? {
        openDb()
        ensureExtraTables()
        return db
    }

    fun markInstalled(id: String, name: String, type: String, entryCount: Int) {
        val db = db ?: return
        val cv = ContentValues().apply {
            put("id", id)
            put("name", name)
            put("type", type)
            put("entry_count", entryCount)
            put("installed_at", System.currentTimeMillis())
        }
        db.insertWithOnConflict("installed_dicts", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getInstalledDicts(): Set<String> {
        val db = db ?: return emptySet()
        val cursor = db.rawQuery("SELECT id FROM installed_dicts", null)
        val ids = mutableSetOf<String>()
        while (cursor.moveToNext()) ids.add(cursor.getString(0))
        cursor.close()
        return ids
    }

    fun deleteDictionary(dictId: String) {
        val db = db ?: return
        db.beginTransaction()
        try {
            db.delete("dict_entries", "source = ?", arrayOf(dictId))
            db.delete("frequencies", "source = ?", arrayOf(dictId))
            db.delete("pitch_accents", "source = ?", arrayOf(dictId))
            db.delete("kanji_entries", "source = ?", arrayOf(dictId))
            db.delete("installed_dicts", "id = ?", arrayOf(dictId))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        Log.d(TAG, "Deleted dictionary: $dictId")
    }
}
