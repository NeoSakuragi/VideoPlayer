package com.videoplayer

import android.content.Context
import android.util.Log
import java.util.concurrent.Executors

class DictionaryManager(private val context: Context) {

    companion object {
        private const val TAG = "DictManager"
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val db = DictionaryDatabase.getInstance(context)

    fun ensureReady(onReady: () -> Unit) {
        if (db.isReady()) {
            db.ensureReady()
            onReady()
            return
        }
        executor.execute {
            db.ensureReady()
            Log.d(TAG, "Dictionary ready: ${db.getEntryCount()} entries, ${db.getFrequencyCount()} freq")
            android.os.Handler(android.os.Looper.getMainLooper()).post(onReady)
        }
    }
}
