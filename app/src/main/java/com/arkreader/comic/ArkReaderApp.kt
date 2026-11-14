package com.arkreader.comic

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.arkreader.comic.data.ComicSourceEntity
import com.arkreader.comic.data.DatabaseHelper
import com.arkreader.comic.data.PrefsManager
import com.arkreader.comic.engine.CoverCache
import com.arkreader.comic.engine.VeneraRuntime
import org.json.JSONArray

class ArkReaderApp : Application() {
    lateinit var db: DatabaseHelper
        private set
    lateinit var prefs: PrefsManager
        private set
    lateinit var coverCache: CoverCache
        private set
    lateinit var veneraRuntime: VeneraRuntime
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = PrefsManager(this)
        db = DatabaseHelper(this)
        coverCache = CoverCache(this)
        veneraRuntime = VeneraRuntime(this, db)
        seedBuiltinComicSources()
        applyNightMode(prefs.themeMode)
    }

    private fun seedBuiltinComicSources() {
        runCatching {
            val existing = db.getComicSources().associateBy { it.sourceKey }
            val jsonText = assets.open("sources/builtin_sources.json").bufferedReader().use { it.readText() }
            val arr = JSONArray(jsonText)
            val writable = db.writableDatabase
            writable.beginTransaction()
            try {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val key = obj.optString("key")
                    val name = obj.optString("name", key)
                    val version = obj.optString("version", "1.0.0")
                    val fileName = obj.optString("fileName")
                    if (key.isEmpty() || fileName.isEmpty()) continue
                    val prev = existing[key]
                    if (prev != null && (!prev.builtIn || (prev.version == version && prev.sortOrder == i))) continue
                    val script = assets.open(fileName).bufferedReader().use { it.readText() }
                    db.upsertComicSource(
                        ComicSourceEntity(
                            sourceKey = key,
                            name = name,
                            version = version,
                            script = script,
                            enabled = prev?.enabled ?: true,
                            builtIn = true,
                            sortOrder = i
                        )
                    )
                }
                writable.setTransactionSuccessful()
            } finally {
                writable.endTransaction()
            }
        }
    }

    fun applyNightMode(mode: Int) {
        val delegateMode = when (mode) {
            1 -> AppCompatDelegate.MODE_NIGHT_NO
            2 -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(delegateMode)
    }

    companion object {
        lateinit var instance: ArkReaderApp
            private set
    }
}
