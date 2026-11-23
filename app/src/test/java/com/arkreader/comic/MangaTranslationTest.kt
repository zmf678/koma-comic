package com.arkreader.comic

import com.arkreader.comic.data.TranslationMode
import com.arkreader.comic.data.TranslationTokens
import com.arkreader.comic.engine.DirectMangaTranslation
import com.arkreader.comic.engine.LightBalloonFinder
import com.arkreader.comic.engine.GithubFeedback
import com.arkreader.comic.engine.MangaTranslation
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.json.JSONException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class MangaTranslationTest {
    @Test
    fun normalizesImageEndpointsAndPreservesProxyPrefix() {
        val base = "https://example.com/manga"
        val image = "$base/translate/with-form/image"
        assertEquals(image, MangaTranslation.endpoint("$base/").toString())
        assertEquals("$image/stream", MangaTranslation.endpoint(image, TranslationMode.VISION).toString())
        assertEquals("$image/stream", MangaTranslation.endpoint("$image/stream/").toString())
        assertEquals("$image/stream", MangaTranslation.endpoint("$image/stream/web", TranslationMode.VISION).toString())
    }

    @Test
    fun customConfigUsesSelectedEngineAndKeepsTargetLanguage() {
        val config = """{"translator":{"target_lang":"ENG","translator_chain":"sugoi:ENG"},"detector":{"detector":"ctd"}}"""
        val custom = MangaTranslation.translationConfig(config, TranslationMode.VISION)
        val translator = custom.getJSONObject("translator")
        assertEquals("openai_hq", translator.getString("translator"))
        assertEquals("ENG", translator.getString("target_lang"))
        assertFalse(translator.has("translator_chain"))
        assertEquals("ctd", custom.getJSONObject("detector").getString("detector"))
        val defaults = MangaTranslation.translationConfig("{}", TranslationMode.VISION)
        assertEquals("CHS", defaults.getJSONObject("translator").getString("target_lang"))
    }

    @Test
    fun acceptsRawAndFramedImagesAndSendsModelFieldsOnlyWhenRequested() = runBlocking {
        val image = byteArrayOf(1, 2, 3)
        val recorded = AtomicReference<Pair<Map<String, String>, String>>()
        ServerSocket().use { server ->
            server.bind(InetSocketAddress("127.0.0.1", 0))
            val worker = FutureTask {
                repeat(7) {
                    server.accept().use { client ->
                        val input = DataInputStream(client.getInputStream().buffered())
                        val path = input.httpLine().split(' ')[1]
                        val headers = generateSequence { input.httpLine().takeIf(String::isNotEmpty) }
                            .associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
                        val body = ByteArray(headers["content-length"]?.toInt() ?: 0).also(input::readFully)
                        recorded.set(headers to body.toString(Charsets.UTF_8))
                        val data = when {
                            path == "/manga/models" -> """{"data":[{"id":"vision-fixture"},{"id":"text-fixture"},{"id":"vision-fixture"}]}""".toByteArray()
                            path == "/issues" -> """{"number":42}""".toByteArray()
                            path == "/manga/chat/completions" -> {
                                val message = JSONObject(body.toString(Charsets.UTF_8)).getJSONArray("messages").getJSONObject(0)
                                if (message.get("content") is String) completion("早上好").toByteArray()
                                else completion(validRegions).toByteArray()
                            }
                            path.endsWith("/stream") -> framedImage(image)
                            else -> image
                        }
                        client.getOutputStream().apply {
                            val status = if (path == "/issues") "201 Created" else "200 OK"
                            write("HTTP/1.1 $status\r\nContent-Length: ${data.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            write(data)
                        }
                    }
                }
            }
            Thread(worker).apply { isDaemon = true; start() }
            val base = "http://127.0.0.1:${server.localPort}/manga"
            assertArrayEquals(image, MangaTranslation.requestImage(MangaTranslation.endpoint(base), JSONObject(), null, "", image))
            val streaming = MangaTranslation.endpoint("$base/translate/with-form/image/stream")
            assertArrayEquals(image, MangaTranslation.requestImage(streaming, JSONObject(), null, "", image))
            assertEquals(null, recorded.get().first["x-session-token"])
            assertFalse(recorded.get().second.contains("user_env_vars"))
            assertArrayEquals(image, MangaTranslation.requestImage(streaming, JSONObject(), """{"OPENAI_MODEL":"test"}""", "session", image))
            assertEquals("session", recorded.get().first["x-session-token"])
            assertTrue(recorded.get().second.contains("name=\"user_env_vars\""))
            val direct = DirectMangaTranslation.endpoint(URI(base))
            var usage = TranslationTokens()
            val regions = DirectMangaTranslation.request(direct, "vision-fixture", "fixture-key", "CHS", image, "high") {
                usage = it
            }
            assertEquals(listOf(DirectMangaTranslation.Region(100, 110, 600, 500, "早上好")), regions)
            assertEquals(TranslationTokens(1, 1, 105, 57, 19), usage)
            assertEquals(162L, usage.total)
            val (headers, payload) = recorded.get()
            assertEquals("Bearer fixture-key", headers["authorization"])
            assertFalse(headers.containsKey("x-session-token"))
            val body = JSONObject(payload)
            assertEquals("vision-fixture", body.getString("model"))
            assertEquals("high", body.getString("reasoning_effort"))
            val content = body.getJSONArray("messages").getJSONObject(0).getJSONArray("content")
            assertTrue(content.getJSONObject(0).getString("text").contains("Simplified Chinese"))
            assertEquals("data:image/jpeg;base64,AQID",
                content.getJSONObject(1).getJSONObject("image_url").getString("url"))
            assertFalse(payload.contains("fixture-key"))
            assertEquals(listOf("text-fixture", "vision-fixture"), DirectMangaTranslation.models(direct, "fixture-key"))
            assertEquals("Bearer fixture-key", recorded.get().first["authorization"])
            assertTrue(recorded.get().second.isEmpty())
            assertEquals("早上好", DirectMangaTranslation.requestText(direct, "text-fixture", "fixture-key", "CHS", "Good morning", "") {})
            val textBody = JSONObject(recorded.get().second)
            assertFalse(textBody.has("reasoning_effort"))
            assertTrue(textBody.getJSONArray("messages").getJSONObject(0).getString("content").contains("Good morning"))
            assertFalse(recorded.get().second.contains("image_url"))
            assertEquals(42L, GithubFeedback.submit("测试反馈", "模拟内容", "fixture-github", URI("http://127.0.0.1:${server.localPort}/issues")))
            val (feedbackHeaders, feedbackPayload) = recorded.get()
            assertEquals("Bearer fixture-github", feedbackHeaders["authorization"])
            assertEquals("测试反馈", JSONObject(feedbackPayload).getString("title"))
            assertEquals("模拟内容", JSONObject(feedbackPayload).getString("body"))
            assertFalse(feedbackPayload.contains("fixture-github"))
            worker.get(3, TimeUnit.SECONDS)
            Unit
        }
    }

    @Test
    fun directEndpointAndRegionsHandleSupportedInput() {
        val base = "https://example.com"
        assertEquals("$base/v1/chat/completions", DirectMangaTranslation.endpoint(URI(base)).toString())
        assertEquals("$base/v1/chat/completions", DirectMangaTranslation.endpoint(URI("$base/v1/")).toString())
        assertEquals("$base/v1/chat/completions", DirectMangaTranslation.endpoint(URI("$base/v1/chat/completions")).toString())
        assertTrue(DirectMangaTranslation.parseResponse(completion("""{"regions":[]}""")).isEmpty())
        assertEquals(TranslationTokens(requests = 1), DirectMangaTranslation.tokens(JSONObject()))
        assertThrows(MangaTranslation.TranslationFailure::class.java) {
            DirectMangaTranslation.parseResponse(completion(validRegions.replace("600", "1001")))
        }
    }

    @Test
    fun compactRegionsKeepOverlayDataAndRememberPagesWithoutText() {
        val named = DirectMangaTranslation.parseResponse(completion(validRegions))
        val compact = DirectMangaTranslation.parseResponse(completion("""{"regions":[[100,110,600,500,"早上好"]]}"""))
        assertEquals(named, compact)
        assertEquals(named, DirectMangaTranslation.decodeRegions(DirectMangaTranslation.encodeRegions(compact)))
        assertEquals(emptyList<DirectMangaTranslation.Region>(),
            DirectMangaTranslation.decodeRegions(DirectMangaTranslation.encodeRegions(emptyList())))
        assertThrows(JSONException::class.java) {
            DirectMangaTranslation.decodeRegions("""{"regions":[[100,110,1001,500,"早上好"]]}""")
        }
    }

    @Test
    fun balloonMaskStaysInsideAnOvalEvenWhenTheModelBoxSpillsOut() {
        val width = 220
        val height = 260
        fun inside(x: Int, y: Int) = (x - 110).toDouble().let { it * it / 3600 } +
            (y - 130).toDouble().let { it * it / 7225 } <= 1.0
        val pixels = IntArray(width * height) { i ->
            val x = i % width
            val y = i / width
            when {
                x in 91..106 && y in 85..162 -> 0xFF141414.toInt()
                inside(x, y) -> 0xFFFFFFFF.toInt()
                else -> 0xFF848484.toInt()
            }
        }
        val result = requireNotNull(LightBalloonFinder(width, height, pixels).find(130, 130, 870, 900))
        val shape = result.bounds
        for (i in result.safe.indices) {
            if (result.safe[i]) assertTrue(inside(shape.x + i % shape.width, shape.y + i / shape.width))
        }
        val box = result.textArea
        assertTrue(box.width > 50 && box.height > 50)
        for (y in box.y until box.y + box.height) for (x in box.x until box.x + box.width) {
            assertTrue(result.safe[(y - shape.y) * shape.width + (x - shape.x)])
        }
        val unbounded = IntArray(width * height) { x ->
            if (x % width in 75..81 && x / width in 90..155) 0xFF101010.toInt() else 0xFFFFFFFF.toInt()
        }
        assertEquals(null, LightBalloonFinder(width, height, unbounded).find(180, 230, 790, 820))
    }

    private val validRegions = """{"regions":[{"left":100,"top":110,"right":600,"bottom":500,"text":"早上好"}]}"""

    private fun completion(content: String): String = JSONObject().put("choices", org.json.JSONArray().put(
        JSONObject().put("message", JSONObject().put("content", if (content.startsWith('{')) "```json\n$content\n```" else content))
    )).put("usage", JSONObject("""{"prompt_tokens":105,"completion_tokens":57,"completion_tokens_details":{"reasoning_tokens":19}}""")).toString()

    private fun InputStream.httpLine(): String = buildString {
        var next = read()
        while (next >= 0 && next != '\n'.code) {
            if (next != '\r'.code) append(next.toChar())
            next = read()
        }
    }

    private fun framedImage(image: ByteArray): ByteArray = ByteArrayOutputStream().apply {
        DataOutputStream(this).use {
            it.writeByte(3)
            it.writeInt(1)
            it.writeByte(1)
            it.writeByte(0)
            it.writeInt(image.size)
            it.write(image)
        }
    }.toByteArray()
}
