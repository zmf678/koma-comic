package com.arkreader.comic.data

import android.content.Context
import android.content.SharedPreferences

class PrefsManager(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("ark_prefs", Context.MODE_PRIVATE)

    var themeMode: Int
        get() = sp.getInt("theme_mode", 0) // 0 system, 1 light, 2 dark
        set(value) = sp.edit().putInt("theme_mode", value).apply()

    var themeColorIndex: Int
        get() = sp.getInt("theme_color_index", 5) // default Blue (0..7)
        set(value) = sp.edit().putInt("theme_color_index", value).apply()

    var gridSize: GridSize
        get() = runCatching { GridSize.valueOf(sp.getString("grid_size", GridSize.MEDIUM.name)!!) }
            .getOrDefault(GridSize.MEDIUM)
        set(value) = sp.edit().putString("grid_size", value.name).apply()

    var sortType: SortType
        get() = runCatching { SortType.valueOf(sp.getString("sort_type", SortType.NAME_ASC.name)!!) }
            .getOrDefault(SortType.NAME_ASC)
        set(value) = sp.edit().putString("sort_type", value.name).apply()

    var doubleLineTitle: Boolean
        get() = sp.getBoolean("double_line_title", false)
        set(value) = sp.edit().putBoolean("double_line_title", value).apply()

    var onlyShowCover: Boolean
        get() = sp.getBoolean("only_show_cover", false)
        set(value) = sp.edit().putBoolean("only_show_cover", value).apply()

    var skipDetailPage: Boolean
        get() = sp.getBoolean("skip_detail_page", false)
        set(value) = sp.edit().putBoolean("skip_detail_page", value).apply()

    var hapticFeedback: Boolean
        get() = sp.getBoolean("haptic_feedback", true)
        set(value) = sp.edit().putBoolean("haptic_feedback", value).apply()

    var activeBookshelfId: Long
        get() = sp.getLong("active_bookshelf_id", 0L)
        set(value) = sp.edit().putLong("active_bookshelf_id", value).apply()

    var readerBrightness: Int
        get() = sp.getInt("reader_brightness", -1) // -1 follow system, 0..100 custom
        set(value) = sp.edit().putInt("reader_brightness", value).apply()

    var applyReaderModeToAll: Boolean
        get() = sp.getBoolean("apply_reader_mode_to_all", false)
        set(value) = sp.edit().putBoolean("apply_reader_mode_to_all", value).apply()

    var allowDoubleTapZoom: Boolean
        get() = sp.getBoolean("allow_double_tap_zoom", true)
        set(value) = sp.edit().putBoolean("allow_double_tap_zoom", value).apply()

    var readerQuality: Int
        get() = sp.getInt("reader_quality", 0) // 0超清, 1高清, 2流畅
        set(value) = sp.edit().putInt("reader_quality", value.coerceIn(0, 2)).apply()
}
