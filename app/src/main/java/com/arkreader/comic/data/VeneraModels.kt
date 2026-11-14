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
    val headers: Map<String, String> = emptyMap()
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
    val type: String, // "input" | "select" | "switch"
    val defaultValue: String = "",
    val options: List<Pair<String, String>> = emptyList()
)
