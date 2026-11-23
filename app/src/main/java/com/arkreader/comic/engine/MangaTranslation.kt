package com.arkreader.comic.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.arkreader.comic.data.TranslationModel
import com.arkreader.comic.data.TranslationMode
import com.arkreader.comic.data.TranslationUsage
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URISyntaxException
import java.security.MessageDigest
import java.util.UUID

object MangaTranslation {
    private const val PATH = "/translate/with-form/image"
    private const val STREAM_PATH = "$PATH/stream"
    private const val MAX_IMAGE = 32 * 1024 * 1024

    class LoginRequired : IllegalStateException("请在漫画翻译设置中登录翻译服务")
    internal class TranslationFailure(message: String) : IllegalStateException(message)

    private fun serviceRoot(server: String): String {
        val uri = URI(server.trim())
        require(uri.scheme?.lowercase() in listOf("http", "https") && !uri.host.isNullOrBlank() &&
            uri.userInfo == null && uri.query == null && uri.fragment == null) { "请输入有效的翻译服务地址" }
        return uri.toString().trimEnd('/').removeSuffix("$STREAM_PATH/web")
            .removeSuffix(STREAM_PATH).removeSuffix(PATH)
    }

    fun endpoint(server: String, mode: TranslationMode = TranslationMode.SERVER): URI {
        val path = server.trim().trimEnd('/')
        val streaming = mode != TranslationMode.SERVER || path.endsWith(STREAM_PATH) || path.endsWith("$STREAM_PATH/web")
        return URI(serviceRoot(server) + if (streaming) STREAM_PATH else PATH)
    }

    fun validateModel(model: TranslationModel) {
        if (!model.mode.usesCustomModel) return
        modelApi(model.apiBase)
        require(model.modelId.isNotBlank()) { "请输入模型 ID" }
        require(model.reasoningEffort in TranslationModel.reasoningLevels) { "请选择有效的思考强度" }
    }

    private fun modelApi(address: String): URI {
        val base = URI(address.trim())
        require(base.scheme in listOf("http", "https") && !base.host.isNullOrBlank() &&
            base.userInfo == null && base.query == null && base.fragment == null) { "请输入有效的模型 API 地址" }
        return DirectMangaTranslation.endpoint(base)
    }

    suspend fun listModels(address: String, key: String) = DirectMangaTranslation.models(modelApi(address), key)

    suspend fun translateText(context: Context?, config: String, model: TranslationModel, key: String, source: String): String {
        validateModel(model)
        require(source.isNotBlank() && source.length <= 8000) { "请输入 1 至 8000 字的原文" }
        return DirectMangaTranslation.requestText(modelApi(model.apiBase), model.modelId, key,
            targetLanguage(JSONObject(config)), source, model.reasoningEffort) {
            if (context != null) TranslationUsage.record(context, model.modelId, it)
        }
    }

    private fun targetLanguage(settings: JSONObject) =
        settings.optJSONObject("translator")?.optString("target_lang").orEmpty().ifBlank { "CHS" }

    fun login(server: String, username: String, password: String): String {
        val uri = URI(serviceRoot(server) + "/auth/login")
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
            val result = JSONObject(connection.inputStream.use { readBounded(it, 64 * 1024) }.toString(Charsets.UTF_8))
            check(result.optBoolean("success")) { "翻译服务登录失败" }
            check(!result.optBoolean("must_change_password")) { "请先在翻译服务网页修改初始密码" }
            return result.getString("token").also { check(it.isNotBlank() && it.none { ch -> ch.isISOControl() }) }
        } finally {
            connection.disconnect()
        }
    }

    suspend fun test(server: String, config: String, model: TranslationModel, apiKey: String, session: String, context: Context? = null) {
        if (!model.acceptsPageImage) {
            translateText(context, config, model, apiKey, "Good morning! 今日はいい天気です。")
            return
        }
        val sample = Bitmap.createBitmap(600, 220, Bitmap.Config.ARGB_8888)
        try {
            Canvas(sample).apply {
                drawColor(Color.WHITE)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 46f }
                drawText("Good morning!", 55f, 100f, paint)
                drawText("今日はいい天気です。", 55f, 158f, paint)
            }
            val translated = translate(context, sample, server, config, model, apiKey, session, cacheResult = false)
            if (translated === sample) throw TranslationFailure("API 未在测试图中返回可排版的译文")
            translated.recycle()
        } finally {
            sample.recycle()
        }
    }

    suspend fun translate(context: Context?, bitmap: Bitmap, server: String, config: String, model: TranslationModel, apiKey: String, session: String,
        cacheResult: Boolean = true): Bitmap {
        validateModel(model)
        require(model.acceptsPageImage) { "图片输入已关闭，请输入原文" }
        val direct = model.mode == TranslationMode.DIRECT
        val endpoint = if (direct) DirectMangaTranslation.endpoint(URI(model.apiBase)) else endpoint(server, model.mode)
        val settings = translationConfig(config, model.mode)
        val environment = if (model.mode.translator != null) {
            JSONObject().put("OPENAI_API_BASE", model.apiBase)
                .put("OPENAI_MODEL", model.modelId)
                .put("OPENAI_API_KEY", apiKey.ifBlank { "ollama" }).toString()
        } else null
        val image = encodeJpeg(bitmap)
        if (image.size > MAX_IMAGE) throw TranslationFailure("此页图片过大")
        val cache = context?.takeIf { cacheResult }?.let {
            val digest = MessageDigest.getInstance("SHA-256")
            val profile = when {
                !direct -> environment.orEmpty()
                model.reasoningEffort.isEmpty() -> "direct-v1\n${model.modelId}\n$apiKey\n${bitmap.width}x${bitmap.height}"
                else -> "direct-v2\n${model.modelId}\n${model.reasoningEffort}\n$apiKey\n${bitmap.width}x${bitmap.height}"
            }
            digest.update(("$endpoint\n$settings\n$profile\n").toByteArray())
            val key = digest.digest(image).joinToString("") { "%02x".format(it) }
            File(File(it.cacheDir, "translations"), "$key.image")
        }
        cache?.takeIf { it.isFile }?.let { BitmapFactory.decodeFile(it.absolutePath) }?.let { return it }
        if (direct) {
            val regions = DirectMangaTranslation.request(endpoint, model.modelId, apiKey, targetLanguage(settings),
                DirectMangaTranslation.preview(bitmap, image), model.reasoningEffort) {
                if (context != null) TranslationUsage.record(context, model.modelId, it)
            }
            if (regions.isEmpty()) return bitmap
            val translated = DirectMangaTranslation.render(bitmap, regions)
            saveCache(cache) { pending -> pending.outputStream().use { check(translated.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
            return translated
        }
        val data = requestImage(endpoint, settings, environment, session, image)
        val translated = BitmapFactory.decodeByteArray(data, 0, data.size) ?: throw TranslationFailure("服务未返回可读的译图")
        saveCache(cache) { it.writeBytes(data) }
        return translated
    }

    internal fun encodeJpeg(bitmap: Bitmap): ByteArray = ByteArrayOutputStream().use { bytes ->
        if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 92, bytes)) throw TranslationFailure("无法编码漫画页")
        bytes.toByteArray()
    }

    private fun saveCache(cache: File?, write: (File) -> Unit) {
        if (cache != null) runCatching {
            val folder = cache.parentFile!!.apply { mkdirs() }
            val pending = File.createTempFile("translation-", ".tmp", folder)
            try {
                write(pending)
                pending.renameTo(cache)
            } finally {
                pending.delete()
            }
        }
    }

    internal fun translationConfig(config: String, mode: TranslationMode): JSONObject {
        val settings = JSONObject(config)
        mode.translator?.let { engine ->
            val translator = settings.optJSONObject("translator") ?: JSONObject()
            translator.put("translator", engine)
            if (translator.isNull("target_lang") || translator.optString("target_lang").isBlank()) {
                translator.put("target_lang", "CHS")
            }
            translator.remove("translator_chain")
            translator.remove("selective_translation")
            settings.put("translator", translator)
        }
        return settings
    }

    internal suspend fun requestImage(endpoint: URI, settings: JSONObject, environment: String?, session: String, image: ByteArray): ByteArray {
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
            readTimeout = 300_000
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            if (session.isNotBlank()) setRequestProperty("X-Session-Token", session)
            setFixedLengthStreamingMode(head.size + image.size + end.size)
        }
        return exchange(connection, { it.write(head); it.write(image); it.write(end) }) { response ->
            val status = response.responseCode
            if (status !in 200..299) throw responseError(status)
            response.inputStream.use { stream ->
                if (endpoint.path.endsWith(STREAM_PATH)) readTranslatedFrame(stream) else readBounded(stream, MAX_IMAGE)
            }
        }
    }

    internal suspend fun <T> exchange(connection: HttpURLConnection, send: ((OutputStream) -> Unit)?, receive: (HttpURLConnection) -> T): T =
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { connection.disconnect() }
            val result = runCatching {
                try {
                    if (send != null) connection.outputStream.use(send)
                    receive(connection)
                } finally {
                    connection.disconnect()
                }
            }
            continuation.resumeWith(result)
        }
    internal fun responseError(status: Int, direct: Boolean = false): IllegalStateException = when {
        direct && status == 401 -> TranslationFailure("API Key 无效或已过期（HTTP 401）")
        direct && status in listOf(404, 405) -> TranslationFailure("API 地址或模型 ID 无效（HTTP $status）")
        direct && status in listOf(400, 415, 422) -> TranslationFailure("API 拒绝请求（HTTP $status），请检查图片输入和思考强度")
        else -> legacyResponseError(status)
    }

    private fun legacyResponseError(status: Int): IllegalStateException = when (status) {
        401 -> LoginRequired()
        403 -> TranslationFailure("服务拒绝翻译（HTTP 403），请检查账户权限和模型密钥")
        404, 405 -> TranslationFailure("找不到漫画翻译接口（HTTP $status），请检查服务类型和地址")
        413 -> TranslationFailure("服务拒绝了过大的漫画页（HTTP 413）")
        422 -> TranslationFailure("服务无法接受此翻译配置或图片（HTTP 422）")
        429 -> TranslationFailure("翻译服务请求过多或额度已用完（HTTP 429）")
        in 300..399 -> TranslationFailure("翻译服务要求重定向，请填写最终服务地址（HTTP $status）")
        else -> TranslationFailure("翻译服务返回 HTTP $status，请检查服务日志")
    }

    fun describe(error: Throwable): String = when (error) {
        is LoginRequired, is TranslationFailure -> error.message.orEmpty()
        is SocketTimeoutException -> "翻译请求超时，请检查网络和 API 状态"
        is EOFException -> "翻译服务在返回译图前中断了连接"
        is IOException -> "无法连接翻译服务，请检查地址、网络和服务状态"
        is URISyntaxException -> "请输入有效的翻译服务或模型 API 地址"
        is JSONException -> "高级翻译配置必须是 JSON 对象"
        is IllegalArgumentException -> error.message ?: "翻译设置无效"
        else -> "翻译失败，请检查漫画翻译设置"
    }

    internal fun readBounded(stream: InputStream, limit: Int): ByteArray {
        val result = ByteArrayOutputStream()
        val buffer = ByteArray(16_384)
        var count = stream.read(buffer)
        while (count != -1) {
            require(result.size() + count <= limit) { "翻译服务响应超过大小限制" }
            result.write(buffer, 0, count)
            count = stream.read(buffer)
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
            if (status == 2) throw TranslationFailure("后端处理失败，请检查翻译模型、权限和服务日志")
            require(size <= 256 * 1024) { "翻译服务的进度数据过大" }
            var remaining = size
            while (remaining > 0) {
                val count = minOf(discard.size, remaining)
                input.readFully(discard, 0, count)
                remaining -= count
            }
        }
        throw TranslationFailure("翻译服务返回了过多进度消息")
    }
}
