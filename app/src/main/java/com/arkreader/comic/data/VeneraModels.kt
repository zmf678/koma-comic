package com.arkreader.comic.data

data class ComicSourceEntity(
    val sourceKey: String,
    val name: String,
    val version: String,
    val url: String = "",
    val script: String,
    val settingsJson: String = "{}",
    val enabled: Boolean = true,
    val builtIn: Boolean = false,
    val sortOrder: Int = 0,
    val updatedAt: Long = System.currentTimeMillis()
)

data class RemoteComicItem(
    val sourceKey: String,
    val id: String,
    val title: String,
    val subTitle: String = "",
    val cover: String = "",
    val tags: List<String> = emptyList(),
    val description: String = "",
    val headers: Map<String, String> = emptyMap()
)

data class OnlineComicMetadata(
    val description: String = "",
    val url: String = "",
    val tags: List<String> = emptyList()
)

data class SourceBrowseTarget(
    val kind: String,
    val title: String,
    val index: Int = 0,
    val category: String = "",
    val param: String = "",
    val keyword: String = "",
    val options: List<String?> = emptyList()
) : java.io.Serializable

data class SourceCategoryGroup(val index: Int, val title: String)

data class SourceBrowseMenu(
    val canSearch: Boolean,
    val channels: List<SourceBrowseTarget>,
    val groups: List<SourceCategoryGroup>,
    val ranking: SourceBrowseTarget?,
    val website: String = "",
    val appWebsite: String = ""
)

data class SourceFilterChoice(val value: String?, val title: String)
data class SourceFilterField(
    val index: Int,
    val title: String,
    val multiple: Boolean,
    val choices: List<SourceFilterChoice>
)
data class SourceBrowseFilters(val values: List<String?>, val fields: List<SourceFilterField>)

data class SourceFeedSection(
    val title: String,
    val comics: List<RemoteComicItem>,
    val target: SourceBrowseTarget?
)
data class SourceBrowsePage(
    val sections: List<SourceFeedSection>,
    val maxPage: Int?,
    val nextCursor: String?
) {
    fun hasNext(page: Int): Boolean =
        nextCursor != null || (maxPage?.let { page < it } ?: sections.any { it.comics.isNotEmpty() })
}

data class RemoteChapter(
    val id: String,
    val title: String,
    val groupName: String = ""
)

data class RemoteComicDetails(
    val sourceKey: String,
    val id: String,
    val title: String,
    val subTitle: String = "",
    val cover: String = "",
    val description: String = "",
    val tags: Map<String, List<String>> = emptyMap(),
    val chapters: List<RemoteChapter> = emptyList(),
    val isFavorite: Boolean = false,
    val subId: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val url: String = ""
)

data class RemotePageImage(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val useSourceLoader: Boolean = false
)

data class RepoSourceItem(
    val name: String,
    val fileName: String,
    val key: String,
    val version: String,
    val url: String
)

data class SourceSettingField(
    val key: String,
    val title: String,
    val type: String, // "input" | "select" | "switch" | "callback"
    val defaultValue: String = "",
    val options: List<Pair<String, String>> = emptyList(),
    val buttonText: String = ""
)

data class SourceAccountCap(
    val hasPasswordLogin: Boolean = false,
    val hasWebViewLogin: Boolean = false,
    val webViewLoginUrl: String = "",
    val cookieFields: List<String> = emptyList(),
    val registerWebsite: String = "",
    val isLoggedIn: Boolean = false
) {
    val supportsAnyLogin: Boolean
        get() = hasPasswordLogin || hasWebViewLogin || cookieFields.isNotEmpty()
}
