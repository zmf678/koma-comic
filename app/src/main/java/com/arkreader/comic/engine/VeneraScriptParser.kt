package com.arkreader.comic.engine

import com.arkreader.comic.data.RemoteChapter
import com.arkreader.comic.data.RemoteComicDetails
import com.arkreader.comic.data.RemoteComicItem
import com.arkreader.comic.data.RemotePageImage
import com.arkreader.comic.data.RepoSourceItem
import com.arkreader.comic.data.SourceSettingField
import org.json.JSONArray
import org.json.JSONObject

data class ParsedVeneraSourceMeta(
    val className: String,
    val key: String,
    val name: String,
    val version: String,
    val url: String
)

object VeneraScriptParser {
    private val classRegex = Regex("""class\s+([A-Za-z_$][A-Za-z0-9_$]*)\s+extends\s+ComicSource\b""")
    private val keyRegex = Regex("""(?:\bkey\s*[:=]|["']key["']\s*:)\s*["']([^"']+)["']""")
    private val nameRegex = Regex("""(?:\bname\s*[:=]|["']name["']\s*:)\s*["']([^"']+)["']""")
    private val versionRegex = Regex("""(?:\bversion\s*[:=]|["']version["']\s*:)\s*["']([^"']+)["']""")
    private val urlRegex = Regex("""(?:\b(?:url|updateUrl)\s*[:=]|["'](?:url|updateUrl)["']\s*:)\s*["']([^"']+)["']""")

    fun parseScriptMeta(
        script: String,
        fallbackKey: String = "",
        fallbackName: String = "",
        fallbackVersion: String = "1.0.0"
    ): ParsedVeneraSourceMeta? {
        val classMatch = classRegex.find(script) ?: return null
        val className = classMatch.groupValues[1]
        val tail = script.substring(classMatch.range.last + 1).take(3600)
        val head = script.substring(0, classMatch.range.first).takeLast(3600)
        fun findField(regex: Regex): String =
            regex.find(tail)?.groupValues?.getOrNull(1)?.trim().orEmpty()
                .ifEmpty { regex.find(head)?.groupValues?.getOrNull(1)?.trim().orEmpty() }

        val key = findField(keyRegex).ifEmpty { fallbackKey.trim() }
        if (key.isEmpty()) return null
        val name = findField(nameRegex).ifEmpty { fallbackName.trim().ifEmpty { key } }
        val version = findField(versionRegex).ifEmpty { fallbackVersion.trim().ifEmpty { "1.0.0" } }
        val url = findField(urlRegex)
        return ParsedVeneraSourceMeta(className, key, name, version, url)
    }

    /**
     * Parses "venera://<sourceKey>/<remoteId>" or "venera://<sourceKey>/<remoteId>/<epId>".
     */
    fun parseVeneraUri(uri: String): Triple<String, String, String>? {
        if (!uri.startsWith("venera://")) return null
        val rest = uri.removePrefix("venera://")
        val parts = rest.split("/", limit = 3)
        if (parts.size < 2) return null
        val sourceKey = parts[0]
        val remoteId = parts[1]
        val epId = parts.getOrNull(2).orEmpty()
        if (sourceKey.isEmpty() || remoteId.isEmpty()) return null
        return Triple(sourceKey, remoteId, epId)
    }

    fun parseVeneraComicUri(uri: String): Pair<String, String>? {
        if (!uri.startsWith("venera://")) return null
        val parts = uri.removePrefix("venera://").split("/", limit = 2)
        if (parts.size != 2 || parts.any(String::isEmpty)) return null
        return parts[0] to parts[1]
    }

    fun parseRepoIndexJson(jsonText: String, repoIndexUrl: String): List<RepoSourceItem> {
        val baseUrl = repoIndexUrl.substringBeforeLast("/", "")
        val arr = JSONArray(jsonText.trim())
        val list = mutableListOf<RepoSourceItem>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val name = obj.optString("name", "").trim()
            val fileName = obj.optString("fileName", "").trim()
            val key = obj.optString("key", "").trim()
            val version = obj.optString("version", "1.0.0").trim()
            if (fileName.isEmpty() || key.isEmpty()) continue
            val fullUrl = if (fileName.startsWith("http://") || fileName.startsWith("https://")) {
                fileName
            } else if (baseUrl.isNotEmpty()) {
                "$baseUrl/${fileName.trimStart('/')}"
            } else {
                fileName
            }
            list.add(
                RepoSourceItem(
                    name = name.ifEmpty { key },
                    fileName = fileName,
                    key = key,
                    version = version,
                    url = fullUrl
                )
            )
        }
        return list
    }

    fun parseComicsListResult(sourceKey: String, jsonText: String): Pair<List<RemoteComicItem>, Int?> {
        val trimmed = jsonText.trim()
        if (trimmed.isEmpty() || trimmed == "null") return emptyList<RemoteComicItem>() to null

        val list = mutableListOf<RemoteComicItem>()
        val seenIds = HashSet<String>()
        var maxPage: Int? = null

        fun parseItem(item: JSONObject, groupLabel: String, defaultHeaders: Map<String, String>) {
            val id = listOf("id", "comic_id", "path_word").firstNotNullOfOrNull { k ->
                item.opt(k)?.toString()?.trim()?.takeIf { it.isNotEmpty() && it != "null" && it != "0" }
            } ?: item.opt("id")?.toString()?.trim()?.takeIf { it.isNotEmpty() && it != "null" }.orEmpty()
            val title = listOf("title", "name").firstNotNullOfOrNull { k ->
                item.optString(k, "").trim().takeIf { it.isNotEmpty() && it != "null" }
            }.orEmpty()
            if (id.isEmpty() || title.isEmpty() || !seenIds.add(id)) return

            var subTitle = listOf("subTitle", "subtitle", "authors").firstNotNullOfOrNull { k ->
                item.optString(k, "").trim().takeIf { it.isNotEmpty() && it != "null" }
            }.orEmpty()
            if (subTitle.isEmpty()) {
                val authorAny = item.opt("author")
                subTitle = when (authorAny) {
                    is JSONArray -> (0 until authorAny.length()).mapNotNull { idx ->
                        val elem = authorAny.opt(idx)
                        when (elem) {
                            is JSONObject -> elem.optString("name", "").trim().takeIf { it.isNotEmpty() }
                            is String -> elem.trim().takeIf { it.isNotEmpty() }
                            else -> null
                        }
                    }.joinToString(", ")
                    is String -> authorAny.trim()
                    else -> ""
                }
            }
            if (subTitle.isEmpty() && groupLabel.isNotEmpty()) {
                subTitle = groupLabel
            }

            val cover = listOf("cover", "pic", "img", "image").firstNotNullOfOrNull { k ->
                item.optString(k, "").trim().takeIf { it.isNotEmpty() && it != "null" }
            }.orEmpty()
            val desc = listOf("description", "desc", "last_update_chapter_name", "last_name").firstNotNullOfOrNull { k ->
                item.optString(k, "").trim().takeIf { it.isNotEmpty() && it != "null" }
            }.orEmpty()

            val tags = mutableListOf<String>()
            val tagsArr = item.optJSONArray("tags")
            if (tagsArr != null) {
                for (j in 0 until tagsArr.length()) {
                    val elem = tagsArr.opt(j)
                    val t = when (elem) {
                        is JSONObject -> elem.optString("name", "").trim()
                        else -> elem?.toString()?.trim().orEmpty()
                    }
                    if (t.isNotEmpty() && t != "null") tags.add(t)
                }
            }
            val itemHeaders = defaultHeaders + jsonToStringMap(item.optJSONObject("headers"))
            list.add(
                RemoteComicItem(
                    sourceKey = sourceKey,
                    id = id,
                    title = title,
                    subTitle = subTitle,
                    cover = cover,
                    tags = tags,
                    description = desc,
                    headers = itemHeaders
                )
            )
        }

        fun collectArray(arr: JSONArray, groupLabel: String, defaultHeaders: Map<String, String>) {
            for (i in 0 until arr.length()) {
                val elem = arr.optJSONObject(i) ?: continue
                if (elem.has("comics") && elem.opt("comics") is JSONArray) {
                    val partTitle = elem.optString("title", groupLabel).trim()
                    val partHeaders = defaultHeaders + jsonToStringMap(elem.optJSONObject("headers"))
                    collectArray(elem.optJSONArray("comics")!!, partTitle, partHeaders)
                } else {
                    parseItem(elem, groupLabel, defaultHeaders)
                }
            }
        }

        fun collectObject(obj: JSONObject, groupLabel: String, defaultHeaders: Map<String, String>) {
            val objHeaders = defaultHeaders + jsonToStringMap(obj.optJSONObject("headers"))
            if (maxPage == null) {
                val mp = when {
                    obj.has("maxPage") && !obj.isNull("maxPage") -> obj.optInt("maxPage", -1)
                    obj.has("max_page") && !obj.isNull("max_page") -> obj.optInt("max_page", -1)
                    else -> -1
                }
                if (mp > 0) maxPage = mp
            }

            val directArr = obj.optJSONArray("comics") ?: obj.optJSONArray("list") ?: obj.optJSONArray("data")
            if (directArr != null) {
                collectArray(directArr, groupLabel, objHeaders)
                return
            }

            val partsArr = obj.optJSONArray("parts") ?: obj.optJSONArray("sections")
            if (partsArr != null) {
                collectArray(partsArr, groupLabel, objHeaders)
                return
            }

            val mapEntries = obj.optJSONArray("__mapEntries__")
            if (mapEntries != null) {
                for (i in 0 until mapEntries.length()) {
                    val pair = mapEntries.optJSONArray(i) ?: continue
                    val partKey = pair.optString(0, "").trim()
                    when (val v = pair.opt(1)) {
                        is JSONArray -> collectArray(v, partKey, objHeaders)
                        is JSONObject -> collectObject(v, partKey, objHeaders)
                    }
                }
                return
            }

            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                if (k == "headers" || k == "maxPage" || k == "max_page" || k == "next") continue
                when (val v = obj.opt(k)) {
                    is JSONArray -> collectArray(v, k, objHeaders)
                    is JSONObject -> collectObject(v, k, objHeaders)
                }
            }
        }

        if (trimmed.startsWith("[")) {
            collectArray(JSONArray(trimmed), "", emptyMap())
        } else {
            collectObject(JSONObject(trimmed), "", emptyMap())
        }
        return list to maxPage
    }

    fun parseComicDetailsResult(sourceKey: String, remoteId: String, jsonText: String): RemoteComicDetails {
        val obj = JSONObject(jsonText)
        val title = obj.optString("title", "").ifEmpty { remoteId }
        val subTitle = obj.optString("subTitle", "")
        val cover = obj.optString("cover", "")
        val desc = obj.optString("description", "")
        val url = obj.optString("url", "")
        val headers = jsonToStringMap(obj.optJSONObject("headers"))

        val tags = linkedMapOf<String, List<String>>()
        val tagsObj = obj.optJSONObject("tags")
        if (tagsObj != null) {
            // Handle serialized Map or plain object
            val mapEntries = tagsObj.optJSONArray("__mapEntries__")
            if (mapEntries != null) {
                for (i in 0 until mapEntries.length()) {
                    val pair = mapEntries.optJSONArray(i) ?: continue
                    val k = pair.optString(0, "")
                    val vArr = pair.optJSONArray(1) ?: continue
                    val vals = (0 until vArr.length()).mapNotNull { vArr.optString(it).takeIf(String::isNotEmpty) }
                    if (k.isNotEmpty() && vals.isNotEmpty()) tags[k] = vals
                }
            } else {
                val keys = tagsObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val vArr = tagsObj.optJSONArray(k) ?: continue
                    val vals = (0 until vArr.length()).mapNotNull { vArr.optString(it).takeIf(String::isNotEmpty) }
                    if (vals.isNotEmpty()) tags[k] = vals
                }
            }
        }

        val chapters = mutableListOf<RemoteChapter>()
        val chObj = obj.optJSONObject("chapters")
        if (chObj != null) {
            extractChaptersRecursive(chObj, defaultGroup = "", out = chapters)
        } else if (obj.optBoolean("wholeBook")) {
            chapters.add(RemoteChapter(id = "", title = "全本"))
        }

        return RemoteComicDetails(
            sourceKey = sourceKey,
            id = remoteId,
            title = title,
            subTitle = subTitle,
            cover = cover,
            description = desc,
            tags = tags,
            chapters = chapters,
            isFavorite = obj.optBoolean("isFavorite", false),
            subId = obj.optString("subId", "").takeIf { it.isNotEmpty() },
            headers = headers,
            url = url
        )
    }

    private fun extractChaptersRecursive(node: JSONObject, defaultGroup: String, out: MutableList<RemoteChapter>) {
        val entries = node.optJSONArray("__mapEntries__")
        if (entries != null) {
            for (i in 0 until entries.length()) {
                val pair = entries.optJSONArray(i) ?: continue
                val key = pair.opt(0)?.toString().orEmpty()
                val value = pair.opt(1)
                if (value is JSONObject) {
                    val groupLabel = if (defaultGroup.isEmpty()) key else "$defaultGroup · $key"
                    extractChaptersRecursive(value, groupLabel, out)
                } else {
                    val chTitle = value?.toString().orEmpty().ifEmpty { key }
                    val displayTitle = if (defaultGroup.isNotEmpty() && defaultGroup != "默认") {
                        "$defaultGroup - $chTitle"
                    } else chTitle
                    if (key.isNotEmpty()) {
                        out.add(RemoteChapter(id = key, title = displayTitle, groupName = defaultGroup))
                    }
                }
            }
            return
        }

        val keys = node.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val v = node.opt(k)
            if (v is JSONObject) {
                val groupLabel = if (defaultGroup.isEmpty()) k else "$defaultGroup · $k"
                extractChaptersRecursive(v, groupLabel, out)
            } else {
                val chTitle = v?.toString().orEmpty().ifEmpty { k }
                val displayTitle = if (defaultGroup.isNotEmpty() && defaultGroup != "默认") {
                    "$defaultGroup - $chTitle"
                } else chTitle
                if (k.isNotEmpty()) {
                    out.add(RemoteChapter(id = k, title = displayTitle, groupName = defaultGroup))
                }
            }
        }
    }

    fun parseEpImagesResult(jsonText: String): List<RemotePageImage> {
        val obj = JSONObject(jsonText)
        val arr = obj.optJSONArray("images") ?: return emptyList()
        val globalHeaders = jsonToStringMap(obj.optJSONObject("headers"))
        val list = mutableListOf<RemotePageImage>()
        for (i in 0 until arr.length()) {
            val elem = arr.opt(i) ?: continue
            when (elem) {
                is String -> if (elem.isNotBlank()) list.add(RemotePageImage(elem, globalHeaders))
                is JSONObject -> {
                    val url = elem.optString("url", "")
                    if (url.isNotBlank()) {
                        val itemHeaders = globalHeaders + jsonToStringMap(elem.optJSONObject("headers"))
                        list.add(RemotePageImage(url, itemHeaders, elem.optBoolean("useSourceLoader")))
                    }
                }
            }
        }
        return list
    }

    fun parseSettingsSchema(jsonText: String): List<SourceSettingField> {
        if (jsonText.isBlank() || jsonText == "null") return emptyList()
        val obj = JSONObject(jsonText)
        val out = mutableListOf<SourceSettingField>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val item = obj.optJSONObject(k) ?: continue
            val title = item.optString("title", k)
            val type = item.optString("type", "input")
            val defaultVal = item.opt("default")?.toString().orEmpty()
            val buttonText = item.optString("buttonText", "")
            val options = mutableListOf<Pair<String, String>>()
            val optsArr = item.optJSONArray("options")
            if (optsArr != null) {
                for (i in 0 until optsArr.length()) {
                    val opt = optsArr.optJSONObject(i) ?: continue
                    val valStr = opt.optString("value", "")
                    val textStr = opt.optString("text", valStr)
                    if (valStr.isNotEmpty()) options.add(valStr to textStr)
                }
            }
            out.add(SourceSettingField(k, title, type, defaultVal, options, buttonText))
        }
        return out
    }

    private fun jsonToStringMap(obj: JSONObject?): Map<String, String> {
        if (obj == null) return emptyMap()
        val map = linkedMapOf<String, String>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            map[k] = obj.optString(k, "")
        }
        return map
    }
}
