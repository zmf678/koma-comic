package com.arkreader.comic.engine

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

internal object GithubFeedback {
    private val issues = URI("https://api.github.com/repos/zmf678/koma-comic/issues")

    suspend fun submit(title: String, body: String, token: String, address: URI = issues): Long {
        require(token.isNotEmpty() && token.all { it in '!'..'~' }) { "请填写有效的 GitHub Token" }
        val payload = JSONObject().put("title", title).put("body", body).toString().toByteArray(Charsets.UTF_8)
        val connection = (address.toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            instanceFollowRedirects = false
            connectTimeout = 8_000
            readTimeout = 20_000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2026-03-10")
            setRequestProperty("User-Agent", "KomaComic")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setFixedLengthStreamingMode(payload.size)
        }
        return MangaTranslation.exchange(connection, { it.write(payload) }) { response ->
            when (val status = response.responseCode) {
                201 -> runCatching {
                    val json = response.inputStream.use { MangaTranslation.readBounded(it, 128 * 1024) }.toString(Charsets.UTF_8)
                    JSONObject(json).optLong("number").also { check(it > 0) }
                }.getOrDefault(0)
                401 -> error("GitHub Token 无效或已过期")
                403, 404 -> error("GitHub 拒绝提交，请检查 Token 能否向该仓库创建 Issue")
                410 -> error("该仓库已关闭 Issues")
                422 -> error("GitHub 拒绝此反馈，请检查标题和内容")
                429 -> error("GitHub 请求过于频繁，请稍后提交")
                else -> error("GitHub 提交失败（HTTP $status）")
            }
        }
    }

    fun describe(error: Throwable) = when (error) {
        is IOException -> "连接 GitHub 失败，请核对 Issue 列表再重试"
        is IllegalStateException, is IllegalArgumentException -> error.message ?: "提交失败"
        else -> "提交失败，请检查 GitHub 授权"
    }
}
