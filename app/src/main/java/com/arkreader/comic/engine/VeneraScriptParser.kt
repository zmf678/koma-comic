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
    private val keyRegex = Regex("""\bkey\s*=\s*["']([^"']+)["']""")
    private val nameRegex = Regex("""\bname\s*=\s*["']([^"']+)["']""")
    private val versionRegex = Regex("""\bversion\s*=\s*["']([^"']+)["']""")
    private val urlRegex = Regex("""\burl\s*=\s*["']([^"']+)["']""")

    fun parseScriptMeta(script: String): ParsedVeneraSourceMeta? {
        val classMatch = classRegex.find(script) ?: return null
        val className = classMatch.groupValues[1]
        val tail = script.substring(classMatch.range.last + 1).take(1600)
        val key = keyRegex.find(tail)?.groupValues?.getOrNull(1)?.trim().orEmpty()
        if (key.isEmpty()) return null
        val name = nameRegex.find(tail)?.groupValues?.getOrNull(1)?.trim().orEmpty().ifEmpty { key }
        val version = versionRegex.find(tail)?.groupValues?.getOrNull(1)?.trim().orEmpty().ifEmpty { "1.0.0" }
        val url = urlRegex.find(tail)?.groupValues?.getOrNull(1)?.trim().orEmpty()
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
        val obj = JSONObject(jsonText)
        val comicsArr = obj.optJSONArray("comics") ?: JSONArray()
        val list = mutableListOf<RemoteComicItem>()
        for (i in 0 until comicsArr.length()) {
            val item = comicsArr.optJSONObject(i) ?: continue
            val id = item.opt("id")?.toString()?.trim().orEmpty()
            val title = item.optString("title", "").trim()
            if (id.isEmpty() || title.isEmpty()) continue
            val subTitle = item.optString("subTitle", "").trim()
            val cover = item.optString("cover", "").trim()
            val desc = item.optString("description", "").trim()
            val tags = mutableListOf<String>()
            val tagsArr = item.optJSONArray("tags")
            if (tagsArr != null) {
                for (j in 0 until tagsArr.length()) {
                    val t = tagsArr.optString(j, "").trim()
                    if (t.isNotEmpty()) tags.add(t)
                }
            }
            list.add(
                RemoteComicItem(
                    sourceKey = sourceKey,
                    id = id,
                    title = title,
                    subTitle = subTitle,
                    cover = cover,
                    tags = tags,
                    description = desc
                )
            )
        }
        val maxPage = if (obj.has("maxPage") && !obj.isNull("maxPage")) {
            obj.optInt("maxPage", -1).takeIf { it > 0 }
        } else null
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
                        list.add(RemotePageImage(url, itemHeaders))
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
            out.add(SourceSettingField(k, title, type, defaultVal, options))
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
