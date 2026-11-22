package com.arkreader.comic.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.arkreader.comic.data.TranslationModel
import com.arkreader.comic.data.TranslationMode
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.UUID

object MangaTranslation {
    private const val PATH = "/translate/with-form/image"
    private const val MAX_IMAGE = 32 * 1024 * 1024

    class LoginRequired : IllegalStateException("请在漫画翻译设置中登录翻译服务")

    fun endpoint(server: String): URI {
        val uri = URI(server.trim())
        require(uri.scheme in listOf("http", "https") && !uri.host.isNullOrBlank() &&
            uri.userInfo == null && uri.query == null && uri.fragment == null) { "请输入有效的服务地址" }
        val url = uri.toString().trimEnd('/')
        return URI(if (url.endsWith(PATH)) url else url + PATH)
    }

    fun validateModel(model: TranslationModel) {
        if (model.mode == TranslationMode.SERVER) return
        val base = URI(model.apiBase)
        require(base.scheme in listOf("http", "https") && !base.host.isNullOrBlank() &&
            base.userInfo == null && base.query == null && base.fragment == null) { "请输入有效的模型 API 地址" }
        require(model.modelId.isNotBlank()) { "请输入模型 ID" }
    }

    fun login(server: String, username: String, password: String): String {
        val uri = URI(endpoint(server).toString().removeSuffix(PATH) + "/auth/login")
        val body = JSONObject().put("username", username).put("password", password).toString().toByteArray()
        val connection = (uri.toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            instanceFollowRedirects = false
            connectTimeout = 8_000
            readTimeout = 15_000
            setRequestProperty("Content-Type", "application/json")
            setFixedLengthStreamingMode(body.size)
        }
        try {
            connection.outputStream.use { it.write(body) }
            check(connection.responseCode == 200) { "翻译服务登录失败" }
            val result = JSONObject(connection.inputStream.use { it.readBounded(64 * 1024) }.toString(Charsets.UTF_8))
            check(result.optBoolean("success")) { "翻译服务登录失败" }
            check(!result.optBoolean("must_change_password")) { "请先在翻译服务网页修改初始密码" }
            return result.getString("token").also { check(it.isNotBlank() && it.none { ch -> ch.isISOControl() }) }
        } finally {
            connection.disconnect()
        }
    }

    fun translate(context: Context, bitmap: Bitmap, server: String, config: String, model: TranslationModel, apiKey: String, session: String): Bitmap {
        val baseEndpoint = endpoint(server)
        validateModel(model)
        val settings = JSONObject(config)
        val engine = model.mode.translator
        val endpoint = if (engine == null) baseEndpoint else URI("$baseEndpoint/stream")
        val environment = engine?.let {
            val translator = settings.optJSONObject("translator") ?: JSONObject()
            translator.put("translator", it)
            translator.remove("translator_chain")
            translator.remove("selective_translation")
            settings.put("translator", translator)
            JSONObject().put("OPENAI_API_BASE", model.apiBase)
                .put("OPENAI_MODEL", model.modelId)
                .put("OPENAI_API_KEY", apiKey.ifBlank { "ollama" }).toString()
        }
        val image = ByteArrayOutputStream().use { bytes ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 92, bytes)) { "无法编码漫画页" }
            bytes.toByteArray()
        }
        require(image.size <= MAX_IMAGE) { "此页图片过大" }
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update((endpoint.toString() + "\n" + settings + "\n" + environment.orEmpty() + "\n").toByteArray())
        val key = digest.digest(image).joinToString("") { "%02x".format(it) }
        val folder = File(context.cacheDir, "translations").apply { mkdirs() }
        val cache = File(folder, "$key.image")
        BitmapFactory.decodeFile(cache.absolutePath)?.let { return it }
        if (environment != null && session.isBlank()) throw LoginRequired()

        val boundary = "Koma" + UUID.randomUUID().toString().replace("-", "")
        val modelPart = environment?.let { "--$boundary\r\nContent-Disposition: form-data; name=\"user_env_vars\"\r\n\r\n$it\r\n" }.orEmpty()
        val head = ("--$boundary\r\nContent-Disposition: form-data; name=\"config\"\r\n\r\n$settings\r\n" + modelPart +
            "--$boundary\r\nContent-Disposition: form-data; name=\"image\"; filename=\"page.jpg\"\r\n" +
            "Content-Type: image/jpeg\r\n\r\n").toByteArray()
        val end = "\r\n--$boundary--\r\n".toByteArray()
        val connection = (endpoint.toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            instanceFollowRedirects = false
            connectTimeout = 8_000
            readTimeout = 120_000
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            if (session.isNotBlank()) setRequestProperty("X-Session-Token", session)
            setFixedLengthStreamingMode(head.size + image.size + end.size)
        }
        val data = try {
            connection.outputStream.use { it.write(head); it.write(image); it.write(end) }
            val status = connection.responseCode
            if (status == HttpURLConnection.HTTP_UNAUTHORIZED) throw LoginRequired()
            check(status in 200..299) { "翻译服务返回 HTTP $status" }
            connection.inputStream.use { stream ->
                if (environment != null) readTranslatedFrame(stream) else stream.readBounded(MAX_IMAGE)
            }
        } finally {
            connection.disconnect()
        }
        val translated = BitmapFactory.decodeByteArray(data, 0, data.size) ?: error("服务未返回可读的译图")
        runCatching {
            val pending = File.createTempFile("translation-", ".tmp", folder)
            try {
                pending.writeBytes(data)
                pending.renameTo(cache)
            } finally {
                pending.delete()
            }
        }
        return translated
    }

    private fun InputStream.readBounded(limit: Int): ByteArray {
        val result = ByteArrayOutputStream()
        val buffer = ByteArray(16_384)
        var count = read(buffer)
        while (count != -1) {
            require(result.size() + count <= limit) { "翻译服务响应超过大小限制" }
            result.write(buffer, 0, count)
            count = read(buffer)
        }
        return result.toByteArray()
    }

    internal fun readTranslatedFrame(stream: InputStream): ByteArray {
        val input = DataInputStream(stream)
        val discard = ByteArray(4096)
        repeat(4096) {
            val status = input.readUnsignedByte()
            val size = input.readInt()
            require(status in 0..4 && size in 0..MAX_IMAGE) { "翻译服务的响应格式不正确" }
            if (status == 0) return ByteArray(size).also(input::readFully)
            if (status == 2) error("翻译服务未能使用自定义模型，请检查接口地址、模型和密钥")
            require(size <= 256 * 1024) { "翻译服务的进度数据过大" }
            var remaining = size
            while (remaining > 0) {
                val count = minOf(discard.size, remaining)
                input.readFully(discard, 0, count)
                remaining -= count
            }
        }
        error("翻译服务返回了过多进度消息")
    }
}
