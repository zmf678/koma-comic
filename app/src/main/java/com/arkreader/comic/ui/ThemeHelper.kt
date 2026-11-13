package com.arkreader.comic.ui

import android.app.Activity
import com.arkreader.comic.ArkReaderApp
import com.arkreader.comic.R

object ThemeHelper {
    val THEME_NAMES = arrayOf("赤", "橙", "黄", "绿", "青", "蓝", "紫", "粉")

    fun applyTheme(activity: Activity) {
        val idx = ArkReaderApp.instance.prefs.themeColorIndex
        val styleId = when (idx) {
            0 -> R.style.Theme_ArkReader_Red
            1 -> R.style.Theme_ArkReader_Orange
            2 -> R.style.Theme_ArkReader_Yellow
            3 -> R.style.Theme_ArkReader_Green
            4 -> R.style.Theme_ArkReader_Cyan
            5 -> R.style.Theme_ArkReader_Blue
            6 -> R.style.Theme_ArkReader_Purple
            7 -> R.style.Theme_ArkReader_Pink
            else -> R.style.Theme_ArkReader
        }
        activity.setTheme(styleId)
    }
}
