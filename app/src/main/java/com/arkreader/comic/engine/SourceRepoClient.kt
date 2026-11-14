package com.arkreader.comic.engine

import com.arkreader.comic.data.ComicSourceEntity
import com.arkreader.comic.data.RepoSourceItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

object SourceRepoClient {
    const val DEFAULT_REPO_PAGE_URL = "https://github.com/zmf678/venera-sources"
    const val DEFAULT_REPO_URL = "https://cdn.jsdelivr.net/gh/zmf678/venera-sources@main/index.json"
    const val FALLBACK_REPO_URL = "https://raw.githubusercontent.com/zmf678/venera-sources/main/index.json"

    private val githubRepoRegex = Regex("""^https?://github\.com/([^/]+)/([^/]+?)(?:\.git)?(?:/(?:tree|blob)/([^/]+)(?:/(.*))?)?/?$""")
    private val githubRawRegex = Regex("""^https?://raw\.githubusercontent\.com/([^/]+)/([^/]+)/([^/]+)/(.+)$""")
    private val jsDelivrGhRegex = Regex("""^https?://cdn\.jsdelivr\.net/gh/([^/]+)/([^/@]+)@([^/]+)/(.+)$""")

    fun resolveIndexCandidateUrls(inputUrl: String): List<String> {
        val trimmed = inputUrl.trim()
        if (trimmed.isEmpty()) return listOf(DEFAULT_REPO_URL, FALLBACK_REPO_URL)

        val candidates = LinkedHashSet<String>()
        val ghMatch = githubRepoRegex.matchEntire(trimmed)
        if (ghMatch != null) {
            val owner = ghMatch.groupValues[1]
            val repo = ghMatch.groupValues[2]
            val branch = ghMatch.groupValues[3].ifEmpty { "main" }
            val subPath = ghMatch.groupValues[4].trimStart('/').ifEmpty { "index.json" }
            val jsonPath = if (subPath.endsWith(".json", ignoreCase = true)) subPath else "${subPath.trimEnd('/')}/index.json"
            candidates.add("https://cdn.jsdelivr.net/gh/$owner/$repo@$branch/$jsonPath")
            candidates.add("https://raw.githubusercontent.com/$owner/$repo/$branch/$jsonPath")
            return candidates.toList()
        }

        val normalized = if (!trimmed.endsWith(".json", ignoreCase = true) && !trimmed.contains("?")) {
            "${trimmed.trimEnd('/')}/index.json"
        } else {
            trimmed
        }
        candidates.addAll(expandMirrorUrls(normalized))
        return candidates.toList()
    }

    fun resolveScriptCandidateUrls(scriptUrl: String): List<String> {
        val trimmed = scriptUrl.trim()
        if (trimmed.isEmpty()) return emptyList()
        val ghMatch = githubRepoRegex.matchEntire(trimmed)
        if (ghMatch != null) {
            val owner = ghMatch.groupValues[1]
            val repo = ghMatch.groupValues[2]
            val branch = ghMatch.groupValues[3].ifEmpty { "main" }
            val subPath = ghMatch.groupValues[4].trimStart('/')
            if (subPath.isNotEmpty()) {
                return listOf(
                    "https://cdn.jsdelivr.net/gh/$owner/$repo@$branch/$subPath",
                    "https://raw.githubusercontent.com/$owner/$repo/$branch/$subPath"
                )
            }
        }
        return expandMirrorUrls(trimmed)
    }

    private fun expandMirrorUrls(url: String): List<String> {
        val list = LinkedHashSet<String>()
        list.add(url)
        val rawMatch = githubRawRegex.matchEntire(url)
        if (rawMatch != null) {
            val (owner, repo, branch, path) = rawMatch.destructured
            list.add("https://cdn.jsdelivr.net/gh/$owner/$repo@$branch/$path")
            return list.toList()
        }
        val cdnMatch = jsDelivrGhRegex.matchEntire(url)
        if (cdnMatch != null) {
            val (owner, repo, branch, path) = cdnMatch.destructured
            list.add("https://raw.githubusercontent.com/$owner/$repo/$branch/$path")
        }
        return list.toList()
    }

    suspend fun fetchRepoIndex(indexUrl: String = DEFAULT_REPO_URL): List<RepoSourceItem> = withContext(Dispatchers.IO) {
        val candidates = resolveIndexCandidateUrls(indexUrl)
        var lastError: Throwable? = null
        for (candidate in candidates) {
            try {
                val body = httpGetText(candidate)
                val parsed = VeneraScriptParser.parseRepoIndexJson(body, candidate)
                if (parsed.isNotEmpty()) return@withContext parsed
            } catch (e: Throwable) {
                lastError = e
            }
        }
        throw (lastError ?: IllegalStateException("无法解析源仓库索引: $indexUrl"))
    }

    suspend fun downloadAndBuildEntity(
        scriptUrl: String,
        fallbackKey: String = "",
        fallbackName: String = "",
        fallbackVersion: String = "1.0.0",
        builtIn: Boolean = false
    ): ComicSourceEntity = withContext(Dispatchers.IO) {
        val candidates = resolveScriptCandidateUrls(scriptUrl)
        var lastError: Throwable? = null
        for (candidate in candidates) {
            try {
                val script = httpGetText(candidate)
                return@withContext buildEntityFromScript(script, candidate, fallbackKey, fallbackName, fallbackVersion, builtIn)
            } catch (e: Throwable) {
                lastError = e
            }
        }
        throw (lastError ?: IllegalStateException("下载漫画源失败: $scriptUrl"))
    }

    fun buildEntityFromScript(
        script: String,
        sourceUrl: String = "",
        fallbackKey: String = "",
        fallbackName: String = "",
        fallbackVersion: String = "1.0.0",
        builtIn: Boolean = false
    ): ComicSourceEntity {
        val meta = VeneraScriptParser.parseScriptMeta(script, fallbackKey, fallbackName, fallbackVersion)
            ?: throw IllegalArgumentException("无法识别脚本中的 ComicSource 定义")
        return ComicSourceEntity(
            sourceKey = meta.key,
            name = meta.name,
            version = meta.version,
            url = sourceUrl.ifEmpty { meta.url },
            script = script,
            enabled = true,
            builtIn = builtIn,
            updatedAt = System.currentTimeMillis()
        )
    }

    private fun httpGetText(urlStr: String): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 13; Tablet) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
            )
        }
        val code = conn.responseCode
        if (code !in 200..299) {
            throw IllegalStateException("HTTP $code: $urlStr")
        }
        return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
}
