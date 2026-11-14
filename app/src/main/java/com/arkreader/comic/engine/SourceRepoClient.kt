package com.arkreader.comic.engine

import com.arkreader.comic.data.ComicSourceEntity
import com.arkreader.comic.data.RepoSourceItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

object SourceRepoClient {
    const val DEFAULT_REPO_URL = "https://cdn.jsdelivr.net/gh/venera-app/venera-configs@main/index.json"

    suspend fun fetchRepoIndex(indexUrl: String = DEFAULT_REPO_URL): List<RepoSourceItem> = withContext(Dispatchers.IO) {
        val body = httpGetText(indexUrl)
        VeneraScriptParser.parseRepoIndexJson(body, indexUrl)
    }

    suspend fun downloadAndBuildEntity(
        scriptUrl: String,
        fallbackKey: String = "",
        fallbackName: String = "",
        fallbackVersion: String = "1.0.0",
        builtIn: Boolean = false
    ): ComicSourceEntity = withContext(Dispatchers.IO) {
        val script = httpGetText(scriptUrl)
        buildEntityFromScript(script, scriptUrl, fallbackKey, fallbackName, fallbackVersion, builtIn)
    }

    fun buildEntityFromScript(
        script: String,
        sourceUrl: String = "",
        fallbackKey: String = "",
        fallbackName: String = "",
        fallbackVersion: String = "1.0.0",
        builtIn: Boolean = false
    ): ComicSourceEntity {
        val meta = VeneraScriptParser.parseScriptMeta(script)
            ?: throw IllegalArgumentException("无法识别脚本中的 ComicSource 定义")
        return ComicSourceEntity(
            sourceKey = meta.key.ifEmpty { fallbackKey },
            name = meta.name.ifEmpty { fallbackName.ifEmpty { meta.key } },
            version = meta.version.ifEmpty { fallbackVersion },
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
