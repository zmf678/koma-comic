package com.arkreader.comic

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.arkreader.comic.data.DatabaseHelper
import com.arkreader.comic.data.PrefsManager
import com.arkreader.comic.engine.CoverCache

class ArkReaderApp : Application() {
    lateinit var db: DatabaseHelper
        private set
    lateinit var prefs: PrefsManager
        private set
    lateinit var coverCache: CoverCache
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = PrefsManager(this)
        db = DatabaseHelper(this)
        coverCache = CoverCache(this)
        applyNightMode(prefs.themeMode)
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
