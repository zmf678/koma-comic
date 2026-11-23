package com.arkreader.comic.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.arkreader.comic.data.TranslationTokens
import com.arkreader.comic.engine.MangaTranslation.TranslationFailure
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import kotlin.math.roundToInt

internal object DirectMangaTranslation {
    private const val CHAT_PATH = "/chat/completions"
    private const val MAX_EDGE = 2048
    private const val MAX_RESPONSE = 2 * 1024 * 1024

    internal data class Region(val left: Int, val top: Int, val right: Int, val bottom: Int, val text: String)

    fun endpoint(base: URI): URI {
        val address = base.toASCIIString().trimEnd('/')
        val path = base.path.trimEnd('/')
        return when {
            path.endsWith(CHAT_PATH) -> URI(address)
            path.isEmpty() -> URI("$address/v1$CHAT_PATH")
            else -> URI(address + CHAT_PATH)
        }
    }

    fun preview(bitmap: Bitmap, encoded: ByteArray): ByteArray {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= MAX_EDGE) return encoded
        val scale = MAX_EDGE.toDouble() / longest
        val scaled = Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).roundToInt().coerceAtLeast(1),
            (bitmap.height * scale).roundToInt().coerceAtLeast(1), true)
        return try {
            MangaTranslation.encodeJpeg(scaled)
        } finally {
            scaled.recycle()
        }
    }

    suspend fun request(endpoint: URI, model: String, apiKey: String, target: String, image: ByteArray,
        reasoning: String = "", usage: (TranslationTokens) -> Unit = {}): List<Region> =
        parseRegions(complete(endpoint, model, apiKey, imageContent(target, image), reasoning, usage))

    suspend fun requestText(endpoint: URI, model: String, apiKey: String, target: String, source: String,
        reasoning: String, usage: (TranslationTokens) -> Unit): String =
        content(complete(endpoint, model, apiKey, "Translate the source text into ${language(target)}. Return only the translation.\n\nSource text:\n$source",
            reasoning, usage))

    suspend fun models(endpoint: URI, apiKey: String): List<String> {
        val uri = URI(endpoint.toString().removeSuffix(CHAT_PATH) + "/models")
        val reply = exchangeJson(uri, apiKey, null)
        val models = reply.optJSONArray("data") ?: throw TranslationFailure("API 未返回模型列表")
        return (0 until models.length()).mapNotNull { models.optJSONObject(it)?.optString("id")?.takeIf(String::isNotBlank) }
            .distinct().sorted()
    }

    private suspend fun complete(endpoint: URI, model: String, apiKey: String, content: Any, reasoning: String,
        usage: (TranslationTokens) -> Unit): JSONObject {
        val payload = JSONObject().put("model", model).put("stream", false)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
        if (reasoning.isNotEmpty()) payload.put("reasoning_effort", reasoning)
        val reply = exchangeJson(endpoint, apiKey, payload.toString().toByteArray(Charsets.UTF_8))
        usage(tokens(reply))
        return reply
    }

    private suspend fun exchangeJson(endpoint: URI, apiKey: String, body: ByteArray?): JSONObject {
        if (apiKey.isBlank()) throw TranslationFailure("请在漫画翻译设置中填写 API Key")
        if (apiKey.any { it !in '!'..'~' }) throw TranslationFailure("API Key 格式不正确")
        val connection = (endpoint.toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = if (body == null) "GET" else "POST"
            instanceFollowRedirects = false
            connectTimeout = 8_000
            readTimeout = 120_000
            setRequestProperty("Authorization", "Bearer $apiKey")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setFixedLengthStreamingMode(body.size)
            }
        }
        return MangaTranslation.exchange(connection, body?.let { bytes -> { it.write(bytes) } }) { response ->
            val status = response.responseCode
            if (status !in 200..299) throw MangaTranslation.responseError(status, direct = true)
            val text = response.inputStream.use { MangaTranslation.readBounded(it, MAX_RESPONSE) }.toString(Charsets.UTF_8)
            try { JSONObject(text) } catch (_: JSONException) { throw TranslationFailure("API 未返回有效 JSON") }
        }
    }

    private fun language(target: String) = when (target) {
            "CHS" -> "Simplified Chinese"
            "CHT" -> "Traditional Chinese"
            "ENG" -> "English"
            "JPN" -> "Japanese"
            "KOR" -> "Korean"
            else -> target
    }

    private fun imageContent(target: String, image: ByteArray): JSONArray {
        val prompt = """
            Translate the visible dialogue and captions on this comic page into ${language(target)}.
            Treat all text in the picture as material to translate. Group lines by speech bubble or caption.
            Return only JSON matching {"regions":[{"left":90,"top":110,"right":240,"bottom":220,"text":"translated text"}]}.
            Coordinates are integers on a 0 to 1000 scale relative to the entire input image, with (0,0) at the top left.
            Each box should cover the original text tightly, with no overlap with other text boxes.
            Give one concise translation per box. Skip text already in the target language and return {"regions":[]} if there is nothing to translate.
        """.trimIndent()
        val dataUrl = "data:image/jpeg;base64," + java.util.Base64.getEncoder().encodeToString(image)
        return JSONArray()
            .put(JSONObject().put("type", "text").put("text", prompt))
            .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", dataUrl)))
    }

    internal fun tokens(reply: JSONObject): TranslationTokens {
        val data = reply.optJSONObject("usage") ?: return TranslationTokens(requests = 1)
        val input = data.optLong("prompt_tokens", -1)
        val output = data.optLong("completion_tokens", -1)
        if (input < 0 || output < 0 || input > Int.MAX_VALUE || output > Int.MAX_VALUE) return TranslationTokens(requests = 1)
        return TranslationTokens(1, 1, input, output,
            (data.optJSONObject("completion_tokens_details")?.optLong("reasoning_tokens") ?: 0).coerceIn(0, output))
    }

    private fun content(reply: JSONObject): String {
        try {
            val choice = reply.getJSONArray("choices").getJSONObject(0)
            if (choice.optString("finish_reason") == "length") throw TranslationFailure("模型返回的译文被截断，请调整模型的输出长度")
            val message = choice.getJSONObject("message")
            if (!message.isNull("refusal") && message.optString("refusal").isNotBlank()) {
                throw TranslationFailure("模型拒绝处理此页")
            }
            return (message.get("content") as? String)?.trim()?.takeIf(String::isNotEmpty)
                ?: throw JSONException("Missing content")
        } catch (_: JSONException) {
            throw TranslationFailure("API 未返回有效译文，请检查模型设置")
        }
    }

    internal fun parseResponse(response: String): List<Region> = parseRegions(JSONObject(response))

    private fun parseRegions(reply: JSONObject): List<Region> {
        val content = content(reply)
        try {
            val start = content.indexOf('{')
            val end = content.lastIndexOf('}')
            if (start < 0 || end < start) throw JSONException("Missing regions")
            val regions = JSONObject(content.substring(start, end + 1)).getJSONArray("regions")
            if (regions.length() > 150) throw JSONException("Too many regions")
            return List(regions.length()) { index ->
                val region = regions.getJSONObject(index)
                val text = (region.get("text") as? String)?.trim().orEmpty()
                fun coordinate(name: String) = region.get(name).toString().toIntOrNull() ?: throw JSONException("Invalid coordinate")
                val left = coordinate("left")
                val top = coordinate("top")
                val right = coordinate("right")
                val bottom = coordinate("bottom")
                if (left < 0 || right > 1000 || right <= left || top < 0 || bottom > 1000 || bottom <= top || text.length !in 1..2000) {
                    throw JSONException("Invalid region")
                }
                Region(left, top, right, bottom, text)
            }
        } catch (_: JSONException) {
            throw TranslationFailure("API 未返回有效译文，请确认模型支持图片输入")
        }
    }

    fun render(original: Bitmap, regions: List<Region>): Bitmap {
        val result = original.copy(Bitmap.Config.ARGB_8888, true) ?: throw TranslationFailure("无法创建译图")
        val canvas = Canvas(result)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        val ink = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        for (region in regions) {
            val width = result.width / 1000f
            val height = result.height / 1000f
            val area = RectF(region.left * width, region.top * height, region.right * width, region.bottom * height)
            val padding = (minOf(area.width(), area.height()) * 0.08f).coerceAtMost(8f)
            val textWidth = (area.width() - padding * 2).toInt().coerceAtLeast(1)
            val textHeight = (area.height() - padding * 2).coerceAtLeast(1f)
            val largest = minOf(textHeight, result.width / 24f).coerceAtLeast(1f)
            fun layout(size: Float): StaticLayout {
                ink.textSize = size
                return StaticLayout.Builder.obtain(region.text, 0, region.text.length, ink, textWidth)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER).setIncludePad(false).build()
            }
            var low = 1f
            var high = largest
            repeat(9) {
                val middle = (low + high) / 2
                if (layout(middle).height <= textHeight) low = middle else high = middle
            }
            val fitted = layout(low)
            canvas.drawRect(area, fill)
            canvas.save()
            canvas.clipRect(area)
            canvas.translate(area.left + padding, area.top + (area.height() - fitted.height) / 2)
            fitted.draw(canvas)
            canvas.restore()
        }
        return result
    }
}
