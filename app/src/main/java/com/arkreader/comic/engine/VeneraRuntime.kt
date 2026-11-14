package com.arkreader.comic.engine

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.Toast
import com.arkreader.comic.data.ComicSourceEntity
import com.arkreader.comic.data.DatabaseHelper
import com.arkreader.comic.data.RemoteComicDetails
import com.arkreader.comic.data.RemoteComicItem
import com.arkreader.comic.data.RemotePageImage
import com.arkreader.comic.data.SourceSettingField
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class VeneraRuntime(
    private val context: Context,
    private val db: DatabaseHelper
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var webView: WebView? = null
    private var shimReady = false
    private val loadedScriptVersions = ConcurrentHashMap<String, String>()
    private val pendingCalls = ConcurrentHashMap<String, CompletableDeferred<String>>()
    private val cookieManager: CookieManager by lazy { CookieManager.getInstance() }

    private fun ensureWebViewReady(): WebView {
        var existing = webView
        if (existing != null && shimReady) return existing

        val ready = CompletableDeferred<WebView>()
        mainHandler.post {
            if (webView == null) {
                @SuppressLint("SetJavaScriptEnabled")
                val wv = WebView(context.applicationContext).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    settings.allowFileAccess = false
                    addJavascriptInterface(NativeBridge(), "_veneraNative")
                }
                wv.loadDataWithBaseURL("https://venera.local/", "<!DOCTYPE html><html><body></body></html>", "text/html", "UTF-8", null)
                val shimCode = context.assets.open("venera_shim.js").bufferedReader().use { it.readText() }
                wv.evaluateJavascript(shimCode) {
                    webView = wv
                    shimReady = true
                    ready.complete(wv)
                }
            } else {
                shimReady = true
                ready.complete(webView!!)
            }
        }
        return kotlinx.coroutines.runBlocking {
            withTimeout(10_000) { ready.await() }
        }
    }

    private suspend fun ensureSourceEvaluated(sourceKey: String): ComicSourceEntity = withContext(Dispatchers.IO) {
        val src = db.getComicSource(sourceKey)
            ?: throw IllegalStateException("未找到漫画源: $sourceKey")
        val signature = "${src.version}:${src.script.hashCode()}"
        val wv = ensureWebViewReady()
        if (loadedScriptVersions[sourceKey] == signature) return@withContext src

        val meta = VeneraScriptParser.parseScriptMeta(src.script)
            ?: throw IllegalStateException("脚本格式不支持 (未找到 extends ComicSource): ${src.name}")

        val wrapperJs = """
            (function() {
                ${src.script}
                if (typeof ${meta.className} === 'undefined') {
                    throw new Error('Class ${meta.className} not defined');
                }
                const inst = new ${meta.className}();
                inst.key = inst.key || ${JSONObject.quote(src.sourceKey)};
                window.__veneraSources[${JSONObject.quote(src.sourceKey)}] = inst;
                if (typeof inst.init === 'function') {
                    Promise.resolve(inst.init()).catch(function(){});
                }
                return true;
            })();
        """.trimIndent()

        val done = CompletableDeferred<Unit>()
        mainHandler.post {
            wv.evaluateJavascript(wrapperJs) { result ->
                if (result == "true") {
                    loadedScriptVersions[sourceKey] = signature
                    done.complete(Unit)
                } else {
                    loadedScriptVersions[sourceKey] = signature
                    done.complete(Unit)
                }
            }
        }
        withTimeout(10_000) { done.await() }
        src
    }

    private suspend fun invokeAsyncJs(sourceKey: String, jsBody: String): String = withContext(Dispatchers.IO) {
        ensureSourceEvaluated(sourceKey)
        val wv = ensureWebViewReady()
        val callId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<String>()
        pendingCalls[callId] = deferred

        val wrapped = """
            window.__veneraInvoke(${JSONObject.quote(callId)}, async function() {
                const src = window.__veneraSources[${JSONObject.quote(sourceKey)}];
                if (!src) throw new Error('Source not initialized: ' + ${JSONObject.quote(sourceKey)});
                $jsBody
            });
        """.trimIndent()

        mainHandler.post {
            wv.evaluateJavascript(wrapped, null)
        }

        try {
            withTimeout(30_000) { deferred.await() }
        } finally {
            pendingCalls.remove(callId)
        }
    }

    suspend fun loadExplorePage(sourceKey: String, page: Int = 1): Pair<List<RemoteComicItem>, Int?> {
        val js = """
            const arr = src.explore || [];
            if (!arr.length) return { comics: [], maxPage: 1 };
            const first = arr[0];
            if (typeof first.load === 'function') {
                const r = await first.load($page);
                return r;
            }
            if (typeof first.loadNext === 'function') {
                const key = '_next_' + ($page - 1);
                const token = ($page <= 1) ? null : src[key];
                const r = await first.loadNext(token);
                if (r && r.next !== undefined) src['_next_' + $page] = r.next;
                return { comics: (r && r.comics) || [], maxPage: (r && r.next) ? ($page + 1) : $page };
            }
            return { comics: [], maxPage: 1 };
        """.trimIndent()
        val raw = invokeAsyncJs(sourceKey, js)
        return VeneraScriptParser.parseComicsListResult(sourceKey, raw)
    }

    suspend fun searchComics(sourceKey: String, keyword: String, page: Int = 1): Pair<List<RemoteComicItem>, Int?> {
        val q = JSONObject.quote(keyword)
        val js = """
            if (!src.search || typeof src.search.load !== 'function') {
                return { comics: [], maxPage: 1 };
            }
            const r = await src.search.load($q, [], $page);
            return r;
        """.trimIndent()
        val raw = invokeAsyncJs(sourceKey, js)
        return VeneraScriptParser.parseComicsListResult(sourceKey, raw)
    }

    suspend fun loadComicDetails(sourceKey: String, remoteId: String): RemoteComicDetails {
        val qId = JSONObject.quote(remoteId)
        val js = """
            if (!src.comic || typeof src.comic.loadInfo !== 'function') {
                throw new Error('Source does not implement comic.loadInfo');
            }
            return await src.comic.loadInfo($qId);
        """.trimIndent()
        val raw = invokeAsyncJs(sourceKey, js)
        return VeneraScriptParser.parseComicDetailsResult(sourceKey, remoteId, raw)
    }

    suspend fun loadChapterImages(sourceKey: String, remoteId: String, epId: String): List<RemotePageImage> {
        val qComic = JSONObject.quote(remoteId)
        val qEp = JSONObject.quote(epId)
        val js = """
            if (!src.comic || typeof src.comic.loadEp !== 'function') {
                throw new Error('Source does not implement comic.loadEp');
            }
            const r = await src.comic.loadEp($qComic, $qEp);
            const urls = (r && r.images) || [];
            if (typeof src.comic.onImageLoad === 'function') {
                const enriched = [];
                for (const u of urls) {
                    try {
                        const cfg = await src.comic.onImageLoad(u, $qComic, $qEp);
                        if (cfg && (cfg.url || cfg.headers)) {
                            enriched.push({ url: cfg.url || u, headers: cfg.headers || {} });
                        } else {
                            enriched.push(u);
                        }
                    } catch (_) {
                        enriched.push(u);
                    }
                }
                return { images: enriched };
            }
            return r;
        """.trimIndent()
        val raw = invokeAsyncJs(sourceKey, js)
        return VeneraScriptParser.parseEpImagesResult(raw)
    }

    suspend fun getSettingsSchema(sourceKey: String): List<SourceSettingField> {
        val js = "return src.settings || {};"
        val raw = invokeAsyncJs(sourceKey, js)
        return VeneraScriptParser.parseSettingsSchema(raw)
    }

    fun invalidateSource(sourceKey: String) {
        loadedScriptVersions.remove(sourceKey)
    }

    private inner class NativeBridge {
        @JavascriptInterface
        fun resolve(callId: String, jsonResult: String) {
            pendingCalls.remove(callId)?.complete(jsonResult)
        }

        @JavascriptInterface
        fun reject(callId: String, errorMsg: String) {
            pendingCalls.remove(callId)?.completeExceptionally(RuntimeException(errorMsg))
        }

        @JavascriptInterface
        fun dispatch(jsonStr: String): String? {
            return runCatching {
                val req = JSONObject(jsonStr)
                when (req.optString("method")) {
                    "http" -> handleHttp(req, asBytes = false)
                    "http_bytes" -> handleHttp(req, asBytes = true)
                    "cookie" -> handleCookie(req)
                    "convert" -> handleConvert(req)
                    "storage" -> handleStorage(req)
                    "ui" -> {
                        if (req.optString("function") == "toast") {
                            val msg = req.optString("message", "")
                            if (msg.isNotEmpty()) {
                                mainHandler.post { Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() }
                            }
                        }
                        "null"
                    }
                    else -> "null"
                }
            }.getOrElse { err ->
                JSONObject().put("error", err.message ?: err.toString()).toString()
            }
        }
    }

    private fun handleHttp(req: JSONObject, asBytes: Boolean): String {
        val httpMethod = req.optString("http_method", "GET").uppercase()
        val urlStr = req.optString("url", "")
        val headersObj = req.optJSONObject("headers")
        val dataObj = req.opt("data")

        val bodyBytes: ByteArray? = when (dataObj) {
            null, JSONObject.NULL -> null
            is JSONObject -> {
                if (dataObj.has("__ab__")) {
                    Base64.decode(dataObj.optString("__ab__", ""), Base64.DEFAULT)
                } else {
                    dataObj.toString().toByteArray(Charsets.UTF_8)
                }
            }
            is String -> dataObj.toByteArray(Charsets.UTF_8)
            else -> dataObj.toString().toByteArray(Charsets.UTF_8)
        }

        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = httpMethod
            connectTimeout = 15_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 13; Tablet) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
            )
            val cookieStr = cookieManager.getCookie(urlStr)
            if (!cookieStr.isNullOrBlank()) {
                setRequestProperty("Cookie", cookieStr)
            }
            if (headersObj != null) {
                val keys = headersObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    setRequestProperty(k, headersObj.optString(k, ""))
                }
            }
            if (bodyBytes != null && (httpMethod == "POST" || httpMethod == "PUT" || httpMethod == "PATCH")) {
                doOutput = true
                if (getRequestProperty("Content-Type") == null) {
                    setRequestProperty(
                        "Content-Type",
                        if (dataObj is JSONObject) "application/json; charset=utf-8" else "application/x-www-form-urlencoded"
                    )
                }
                outputStream.use { it.write(bodyBytes) }
            }
        }

        val status = conn.responseCode
        val respHeaders = JSONObject()
        conn.headerFields?.forEach { (k, values) ->
            if (k != null && values.isNotEmpty()) {
                respHeaders.put(k.lowercase(), values.joinToString("; "))
                if (k.equals("Set-Cookie", ignoreCase = true)) {
                    values.forEach { cookieManager.setCookie(urlStr, it) }
                }
            }
        }

        val stream = runCatching {
            if (status >= 400) conn.errorStream ?: conn.inputStream else conn.inputStream
        }.getOrNull()
        val rawBytes = if (stream != null) {
            stream.use { input ->
                val buf = ByteArrayOutputStream()
                val tmp = ByteArray(8192)
                var n: Int
                while (input.read(tmp).also { n = it } != -1) {
                    buf.write(tmp, 0, n)
                }
                buf.toByteArray()
            }
        } else ByteArray(0)

        val res = JSONObject().apply {
            put("status", status)
            put("headers", respHeaders)
            if (asBytes) {
                put("bodyBase64", Base64.encodeToString(rawBytes, Base64.NO_WRAP))
            } else {
                put("body", String(rawBytes, Charsets.UTF_8))
            }
        }
        return res.toString()
    }

    private fun handleCookie(req: JSONObject): String {
        val fn = req.optString("function")
        val url = req.optString("url")
        return when (fn) {
            "set" -> {
                val arr = req.optJSONArray("cookies") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val c = arr.optJSONObject(i) ?: continue
                    val name = c.optString("name", "")
                    val value = c.optString("value", "")
                    val domain = c.optString("domain", "")
                    val path = c.optString("path", "/")
                    if (name.isNotEmpty()) {
                        val sb = StringBuilder("$name=$value; Path=$path")
                        if (domain.isNotEmpty()) sb.append("; Domain=$domain")
                        cookieManager.setCookie(url, sb.toString())
                    }
                }
                cookieManager.flush()
                "null"
            }
            "get" -> {
                val raw = cookieManager.getCookie(url).orEmpty()
                val out = JSONArray()
                if (raw.isNotBlank()) {
                    raw.split(";").forEach { part ->
                        val kv = part.trim().split("=", limit = 2)
                        if (kv.size == 2) {
                            out.put(JSONObject().put("name", kv[0]).put("value", kv[1]))
                        }
                    }
                }
                out.toString()
            }
            "delete" -> {
                cookieManager.removeAllCookies(null)
                cookieManager.flush()
                "null"
            }
            else -> "null"
        }
    }

    private fun handleConvert(req: JSONObject): String {
        val type = req.optString("type")
        val isEncode = req.optBoolean("isEncode", true)
        fun extractBytes(field: Any?): ByteArray {
            return when (field) {
                is JSONObject -> if (field.has("__ab__")) {
                    Base64.decode(field.optString("__ab__", ""), Base64.DEFAULT)
                } else ByteArray(0)
                is String -> field.toByteArray(Charsets.UTF_8)
                else -> ByteArray(0)
            }
        }

        val valObj = req.opt("value")
        val inputBytes = extractBytes(valObj)

        return when (type) {
            "gbk" -> {
                val gbk = Charset.forName("GBK")
                if (isEncode) {
                    val b = (valObj as? String ?: "").toByteArray(gbk)
                    JSONObject().put("__ab__", Base64.encodeToString(b, Base64.NO_WRAP)).toString()
                } else {
                    JSONObject.quote(String(inputBytes, gbk))
                }
            }
            "md5", "sha1", "sha256", "sha512" -> {
                val alg = when (type) {
                    "md5" -> "MD5"
                    "sha1" -> "SHA-1"
                    "sha256" -> "SHA-256"
                    else -> "SHA-512"
                }
                val digest = MessageDigest.getInstance(alg).digest(inputBytes)
                JSONObject().put("__ab__", Base64.encodeToString(digest, Base64.NO_WRAP)).toString()
            }
            "hmac" -> {
                val hash = req.optString("hash", "sha256").lowercase()
                val alg = when (hash) {
                    "md5" -> "HmacMD5"
                    "sha1" -> "HmacSHA1"
                    "sha512" -> "HmacSHA512"
                    else -> "HmacSHA256"
                }
                val keyBytes = extractBytes(req.opt("key"))
                val mac = Mac.getInstance(alg)
                mac.init(SecretKeySpec(keyBytes, alg))
                val out = mac.doFinal(inputBytes)
                if (req.optBoolean("isString", false)) {
                    JSONObject.quote(out.joinToString("") { "%02x".format(it) })
                } else {
                    JSONObject().put("__ab__", Base64.encodeToString(out, Base64.NO_WRAP)).toString()
                }
            }
            "aes-ecb" -> {
                val keyBytes = extractBytes(req.opt("key"))
                val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"))
                val out = cipher.doFinal(inputBytes)
                JSONObject().put("__ab__", Base64.encodeToString(out, Base64.NO_WRAP)).toString()
            }
            "aes-cbc" -> {
                val keyBytes = extractBytes(req.opt("key"))
                val ivBytes = extractBytes(req.opt("iv"))
                val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(ivBytes))
                val out = cipher.doFinal(inputBytes)
                JSONObject().put("__ab__", Base64.encodeToString(out, Base64.NO_WRAP)).toString()
            }
            else -> "null"
        }
    }

    private fun handleStorage(req: JSONObject): String {
        val fn = req.optString("function")
        val sourceKey = req.optString("sourceKey")
        val key = req.optString("key")
        val prefs = context.getSharedPreferences("venera_data_$sourceKey", Context.MODE_PRIVATE)
        return when (fn) {
            "loadSetting" -> {
                val src = db.getComicSource(sourceKey) ?: return "null"
                val obj = runCatching { JSONObject(src.settingsJson) }.getOrNull() ?: return "null"
                if (!obj.has(key)) "null" else JSONObject.wrap(obj.opt(key))?.let {
                    if (it is String) JSONObject.quote(it) else it.toString()
                } ?: "null"
            }
            "saveData" -> {
                val value = req.opt("value")
                prefs.edit().putString(key, value?.toString()).apply()
                "null"
            }
            "loadData" -> {
                val v = prefs.getString(key, null) ?: return "null"
                JSONObject.quote(v)
            }
            "deleteData" -> {
                prefs.edit().remove(key).apply()
                "null"
            }
            else -> "null"
        }
    }
}
