package com.arkreader.comic.data

import android.content.Context
import android.content.SharedPreferences

class PrefsManager(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("ark_prefs", Context.MODE_PRIVATE)

    var lastSource: String
        get() = sp.getString("last_source", "").orEmpty()
        set(value) = sp.edit().putString("last_source", value).apply()

    var glassMaterial: Boolean
        get() = sp.getBoolean("glass_material", false)
        set(value) = sp.edit().putBoolean("glass_material", value).apply()

    var wallpaperEnabled: Boolean
        get() = sp.getBoolean("wallpaper_enabled", false)
        set(value) = sp.edit().putBoolean("wallpaper_enabled", value).apply()

    var translationServer: String
        get() = sp.getString("translation_server", "").orEmpty()
        set(value) = sp.edit().putString("translation_server", value).apply()

    var translationConfig: String
        get() = sp.getString("translation_config", "").orEmpty().ifBlank { """{"translator":{"target_lang":"CHS"}}""" }
        set(value) = sp.edit().putString("translation_config", value).apply()

    fun translationModel() = TranslationModel(
        TranslationMode.entries.find { it.name == sp.getString("translation_mode", "") } ?: TranslationMode.SERVER,
        sp.getString("translation_api_base", "").orEmpty(),
        sp.getString("translation_model_id", "").orEmpty()
    )

    val hasTranslationKey: Boolean get() = !sp.getString("translation_api_key", "").isNullOrEmpty()

    fun translationApiKey(): String {
        if (translationModel().mode == TranslationMode.SERVER) return ""
        return runCatching { TranslationKeyStore.decrypt(sp.getString("translation_api_key", "").orEmpty()) }
            .getOrElse { error("请在漫画翻译设置中重新填写模型密钥") }
    }

    val hasTranslationSession: Boolean get() = !sp.getString("translation_session", "").isNullOrEmpty()

    fun translationSession(): String = runCatching {
        TranslationKeyStore.decrypt(sp.getString("translation_session", "").orEmpty())
    }.getOrDefault("")

    fun saveTranslationSettings(server: String, config: String, model: TranslationModel, apiKey: String, clearKey: Boolean, session: String?) {
        val encrypted = when {
            model.mode != TranslationMode.SERVER && apiKey.isNotBlank() -> TranslationKeyStore.encrypt(apiKey.trim())
            clearKey || model.apiBase != translationModel().apiBase || server != translationServer -> ""
            else -> sp.getString("translation_api_key", "").orEmpty()
        }
        val sessionValue = when {
            session != null -> TranslationKeyStore.encrypt(session)
            server != translationServer -> ""
            else -> sp.getString("translation_session", "").orEmpty()
        }
        sp.edit()
            .putString("translation_server", server)
            .putString("translation_config", config)
            .putString("translation_mode", model.mode.name)
            .putString("translation_api_base", model.apiBase)
            .putString("translation_model_id", model.modelId)
            .putString("translation_api_key", encrypted)
            .putString("translation_session", sessionValue)
            .apply()
    }

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
