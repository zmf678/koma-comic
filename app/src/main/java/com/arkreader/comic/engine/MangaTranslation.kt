package com.arkreader.comic.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.UUID

object MangaTranslation {
    private const val PATH = "/translate/with-form/image"
    private const val MAX_IMAGE = 32 * 1024 * 1024

    fun endpoint(server: String): URI {
        val uri = URI(server.trim())
        require(uri.scheme in listOf("http", "https") && !uri.host.isNullOrBlank() &&
            uri.userInfo == null && uri.query == null && uri.fragment == null) { "请输入有效的服务地址" }
        val url = uri.toString().trimEnd('/')
        return URI(if (url.endsWith(PATH)) url else url + PATH)
    }

    fun translate(context: Context, bitmap: Bitmap, server: String, config: String): Bitmap {
        val endpoint = endpoint(server)
        val settings = JSONObject(config).toString()
        val image = ByteArrayOutputStream().use { bytes ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 92, bytes)) { "无法编码漫画页" }
            bytes.toByteArray()
        }
        require(image.size <= MAX_IMAGE) { "此页图片过大" }
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update((endpoint.toString() + "\n" + settings + "\n").toByteArray())
        val key = digest.digest(image).joinToString("") { "%02x".format(it) }
        val folder = File(context.cacheDir, "translations").apply { mkdirs() }
        val cache = File(folder, "$key.image")
        BitmapFactory.decodeFile(cache.absolutePath)?.let { return it }

        val boundary = "Koma" + UUID.randomUUID().toString().replace("-", "")
        val head = ("--$boundary\r\nContent-Disposition: form-data; name=\"config\"\r\n\r\n$settings\r\n" +
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
            setFixedLengthStreamingMode(head.size + image.size + end.size)
        }
        val data = try {
            connection.outputStream.use { it.write(head); it.write(image); it.write(end) }
            val status = connection.responseCode
            check(status in 200..299) { "翻译服务返回 HTTP $status" }
            connection.inputStream.use { stream ->
                val result = ByteArrayOutputStream()
                val buffer = ByteArray(16_384)
                var count = stream.read(buffer)
                while (count != -1) {
                    require(result.size() + count <= MAX_IMAGE) { "译图超过大小限制" }
                    result.write(buffer, 0, count)
                    count = stream.read(buffer)
                }
                result.toByteArray()
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
}
