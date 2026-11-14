package com.arkreader.comic.engine

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.arkreader.comic.data.ComicSourceEntity
import com.arkreader.comic.data.DatabaseHelper
import com.arkreader.comic.data.RemoteComicDetails
import com.arkreader.comic.data.RemoteComicItem
import com.arkreader.comic.data.RemotePageImage
import com.arkreader.comic.data.SourceAccountCap
import com.arkreader.comic.data.SourceSettingField
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import java.lang.ref.WeakReference
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
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val initMutex = Mutex()
    private var webView: WebView? = null
    @Volatile
    private var shimReady = false
    private val loadedScriptVersions = ConcurrentHashMap<String, String>()
    private val pendingCalls = ConcurrentHashMap<String, CompletableDeferred<String>>()
    private val cookieManager: CookieManager by lazy { CookieManager.getInstance() }
    private var currentActivityRef = WeakReference<Activity>(null)

    init {
        (context.applicationContext as? Application)?.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityCreated(a: Activity, savedInstanceState: Bundle?) {
                    currentActivityRef = WeakReference(a)
                }
                override fun onActivityStarted(a: Activity) {
                    currentActivityRef = WeakReference(a)
                }
                override fun onActivityResumed(a: Activity) {
                    currentActivityRef = WeakReference(a)
                }
                override fun onActivityPaused(a: Activity) {
                    if (currentActivityRef.get() === a) currentActivityRef.clear()
                }
                override fun onActivityStopped(a: Activity) {}
                override fun onActivitySaveInstanceState(a: Activity, outState: Bundle) {}
                override fun onActivityDestroyed(a: Activity) {}
            }
        )
    }

    private suspend fun ensureWebViewReady(): WebView = initMutex.withLock {
        val existing = webView
        if (existing != null && shimReady) return existing

        val ready = CompletableDeferred<WebView>()
        mainHandler.post {
            try {
                @SuppressLint("SetJavaScriptEnabled")
                val wv = existing ?: WebView(context.applicationContext).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    settings.allowFileAccess = false
                    cookieManager.setAcceptCookie(true)
                    cookieManager.setAcceptThirdPartyCookies(this, true)
                    addJavascriptInterface(NativeBridge(), "_veneraNative")
                }
                webView = wv
                val shimCode = context.assets.open("venera_shim.js").bufferedReader().use { it.readText() }
                wv.webViewClient = object : WebViewClient() {
                    private var injected = false
                    override fun onPageFinished(view: WebView?, url: String?) {
                        if (injected) return
                        injected = true
                        wv.evaluateJavascript(shimCode) {
                            shimReady = true
                            if (!ready.isCompleted) ready.complete(wv)
                        }
                    }
                }
                wv.loadDataWithBaseURL(
                    "https://venera.local/",
                    "<!DOCTYPE html><html><head><meta charset=\"utf-8\"></head><body></body></html>",
                    "text/html",
                    "UTF-8",
                    null
                )
            } catch (e: Throwable) {
                if (!ready.isCompleted) ready.completeExceptionally(e)
            }
        }
        withTimeout(10_000) { ready.await() }
    }

    private suspend fun ensureSourceEvaluated(sourceKey: String): ComicSourceEntity = withContext(Dispatchers.IO) {
        val src = db.getComicSource(sourceKey)
            ?: throw IllegalStateException("未找到漫画源: $sourceKey")
        val signature = "${src.version}:${src.script.hashCode()}"
        val wv = ensureWebViewReady()
        if (loadedScriptVersions[sourceKey] == signature) return@withContext src

        val meta = VeneraScriptParser.parseScriptMeta(
            src.script,
            fallbackKey = src.sourceKey,
            fallbackName = src.name,
            fallbackVersion = src.version
        ) ?: throw IllegalStateException("脚本格式不支持 (未找到 extends ComicSource): ${src.name}")

        val qKey = JSONObject.quote(src.sourceKey)
        val wrapperJs = """
            (function() {
                try {
                    window.__currentSourceKey = $qKey;
                    ${src.script}
                    if (typeof ${meta.className} === 'undefined') {
                        return JSON.stringify({ ok: false, error: 'Class ${meta.className} not defined' });
                    }
                    const inst = new ${meta.className}();
                    inst.key = inst.key || $qKey;
                    try {
                        inst.__settingsSchema = inst.settings ? JSON.parse(JSON.stringify(inst.settings)) : {};
                    } catch (_) {
                        inst.__settingsSchema = inst.settings || {};
                    }
                    window.__veneraSources[$qKey] = inst;
                    if (typeof inst.init === 'function') {
                        Promise.resolve(inst.init()).catch(function(){});
                    }
                    return JSON.stringify({ ok: true });
                } catch (e) {
                    return JSON.stringify({ ok: false, error: String(e && e.stack ? e.stack : e) });
                }
            })();
        """.trimIndent()

        val done = CompletableDeferred<Unit>()
        mainHandler.post {
            wv.evaluateJavascript(wrapperJs) { rawResult ->
                val unwrapped = runCatching {
                    if (rawResult.isNullOrBlank() || rawResult == "null") null
                    else JSONTokener(rawResult).nextValue()?.toString()
                }.getOrNull()
                val obj = unwrapped?.let { runCatching { JSONObject(it) }.getOrNull() }
                if (obj != null && obj.optBoolean("ok", false)) {
                    loadedScriptVersions[sourceKey] = signature
                    done.complete(Unit)
                } else {
                    val err = obj?.optString("error").orEmpty().ifEmpty { "脚本初始化失败: ${src.name}" }
                    done.completeExceptionally(IllegalStateException(err))
                }
            }
        }
        withTimeout(10_000) { done.await() }
        src
    }

    private suspend fun invokeAsyncJs(
        sourceKey: String,
        jsBody: String,
        timeoutMs: Long = 35_000
    ): String = withContext(Dispatchers.IO) {
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
            withTimeout(timeoutMs) { deferred.await() }
        } finally {
            pendingCalls.remove(callId)
        }
    }

    suspend fun loadExplorePage(sourceKey: String, page: Int = 1): Pair<List<RemoteComicItem>, Int?> {
        val js = """
            const fallbackHeaders = window.__extractSourceImageHeaders(src);
            const arr = Array.isArray(src.explore) ? src.explore : [];
            let normalized = null;
            if (arr.length > 0) {
                const first = arr[0];
                const isSingle = first.type === 'singlePageWithMultiPart';
                if (isSingle && $page > 1) {
                    return { comics: [], maxPage: 1 };
                }
                if (typeof first.load === 'function') {
                    const r = await first.load($page);
                    normalized = window.__normalizeComicsResult(r, $page, isSingle, fallbackHeaders);
                } else if (typeof first.loadNext === 'function') {
                    const key = '_next_' + ($page - 1);
                    const token = ($page <= 1) ? null : src[key];
                    const r = await first.loadNext(token);
                    if (r && r.next !== undefined) src['_next_' + $page] = r.next;
                    const maxP = (r && r.next) ? ($page + 1) : $page;
                    normalized = window.__normalizeComicsResult({ comics: (r && r.comics) || [], maxPage: maxP }, $page, false, fallbackHeaders);
                }
            }
            if ((!normalized || !normalized.comics || normalized.comics.length === 0) &&
                src.categoryComics && typeof src.categoryComics.load === 'function') {
                let firstCat = "全部";
                let firstParam = "";
                const parts = src.category && Array.isArray(src.category.parts) ? src.category.parts : [];
                if (parts.length > 0) {
                    const p0 = parts[0];
                    if (Array.isArray(p0.categories) && p0.categories.length > 0) firstCat = p0.categories[0];
                    if (Array.isArray(p0.categoryParams) && p0.categoryParams.length > 0) firstParam = p0.categoryParams[0];
                }
                const opts = window.__defaultOptionsFor(src.categoryComics.optionList);
                const cr = await src.categoryComics.load(firstCat, firstParam, opts, $page);
                normalized = window.__normalizeComicsResult(cr, $page, false, fallbackHeaders);
            }
            return normalized || { comics: [], maxPage: 1 };
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
            const opts = window.__defaultOptionsFor(src.search.optionList);
            const r = await src.search.load($q, opts, $page);
            const fallbackHeaders = window.__extractSourceImageHeaders(src);
            return window.__normalizeComicsResult(r, $page, false, fallbackHeaders);
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
            const res = await src.comic.loadInfo($qId);
            if (res && !res.headers) {
                const fh = window.__extractSourceImageHeaders(src);
                if (Object.keys(fh).length > 0) res.headers = fh;
            }
            return res;
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
            const fallbackHeaders = window.__extractSourceImageHeaders(src);
            if (typeof src.comic.onImageLoad === 'function') {
                const enriched = [];
                for (const u of urls) {
                    try {
                        const cfg = await src.comic.onImageLoad(u, $qComic, $qEp);
                        if (cfg && (cfg.url || cfg.headers)) {
                            enriched.push({
                                url: cfg.url || u,
                                headers: Object.assign({}, fallbackHeaders, cfg.headers || {})
                            });
                        } else {
                            enriched.push(Object.keys(fallbackHeaders).length ? { url: u, headers: fallbackHeaders } : u);
                        }
                    } catch (_) {
                        enriched.push(Object.keys(fallbackHeaders).length ? { url: u, headers: fallbackHeaders } : u);
                    }
                }
                return { images: enriched };
            }
            if (r && !r.headers && Object.keys(fallbackHeaders).length > 0) {
                r.headers = fallbackHeaders;
            }
            return r;
        """.trimIndent()
        val raw = invokeAsyncJs(sourceKey, js)
        return VeneraScriptParser.parseEpImagesResult(raw)
    }

    suspend fun getSettingsSchema(sourceKey: String): List<SourceSettingField> {
        val js = "return src.__settingsSchema || src.settings || {};"
        val raw = invokeAsyncJs(sourceKey, js)
        return VeneraScriptParser.parseSettingsSchema(raw)
    }

    suspend fun invokeSettingCallback(sourceKey: String, settingKey: String) {
        val qKey = JSONObject.quote(settingKey)
        val js = """
            const field = src.settings && src.settings[$qKey];
            if (!field || typeof field.callback !== 'function') {
                throw new Error('该设置项不支持点击操作');
            }
            await field.callback();
            return true;
        """.trimIndent()
        invokeAsyncJs(sourceKey, js, timeoutMs = 120_000)
    }

    suspend fun getAccountCapabilities(sourceKey: String): SourceAccountCap {
        val js = """
            const acc = src.account || null;
            const hasPwd = !!(acc && typeof acc.login === 'function');
            let hasWv = false;
            let wvUrl = '';
            if (acc && acc.loginWithWebview) {
                hasWv = true;
                try { wvUrl = String(acc.loginWithWebview.url || ''); } catch (_) {}
            }
            const cookieFields = (acc && acc.loginWithCookies && Array.isArray(acc.loginWithCookies.fields))
                ? acc.loginWithCookies.fields.map(String)
                : [];
            const regUrl = (acc && typeof acc.registerWebsite === 'string') ? acc.registerWebsite : '';
            let logged = false;
            try { logged = !!src.isLogged; } catch (_) {}
            return {
                hasPasswordLogin: hasPwd,
                hasWebViewLogin: hasWv,
                webViewLoginUrl: wvUrl,
                cookieFields: cookieFields,
                registerWebsite: regUrl,
                isLoggedIn: logged
            };
        """.trimIndent()
        val raw = invokeAsyncJs(sourceKey, js)
        val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return SourceAccountCap()
        val arr = obj.optJSONArray("cookieFields")
        val fields = if (arr != null) {
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf(String::isNotEmpty) }
        } else emptyList()
        return SourceAccountCap(
            hasPasswordLogin = obj.optBoolean("hasPasswordLogin", false),
            hasWebViewLogin = obj.optBoolean("hasWebViewLogin", false),
            webViewLoginUrl = obj.optString("webViewLoginUrl", ""),
            cookieFields = fields,
            registerWebsite = obj.optString("registerWebsite", ""),
            isLoggedIn = obj.optBoolean("isLoggedIn", false)
        )
    }

    suspend fun loginWithPassword(sourceKey: String, username: String, password: String): Boolean {
        val qUser = JSONObject.quote(username)
        val qPass = JSONObject.quote(password)
        val js = """
            if (!src.account || typeof src.account.login !== 'function') {
                throw new Error('该漫画源不支持账号密码登录');
            }
            const res = await src.account.login($qUser, $qPass);
            if (res === false) throw new Error('登录失败');
            try {
                src.saveData('__logged_in__', true);
                src.saveData('account', [$qUser, $qPass]);
            } catch (_) {}
            return true;
        """.trimIndent()
        invokeAsyncJs(sourceKey, js, timeoutMs = 90_000)
        return true
    }

    suspend fun loginWithCookies(sourceKey: String, values: List<String>): Boolean {
        val arrJson = JSONArray(values).toString()
        val js = """
            const lwc = src.account && src.account.loginWithCookies;
            if (!lwc || typeof lwc.validate !== 'function') {
                throw new Error('该漫画源不支持 Cookie 登录');
            }
            const ok = await lwc.validate($arrJson);
            if (ok) {
                src.saveData('__logged_in__', true);
            }
            return !!ok;
        """.trimIndent()
        val raw = invokeAsyncJs(sourceKey, js, timeoutMs = 60_000)
        return raw.trim() == "true"
    }

    suspend fun checkWebViewLoginStatus(sourceKey: String, url: String, title: String): Boolean {
        val qUrl = JSONObject.quote(url)
        val qTitle = JSONObject.quote(title)
        val js = """
            const wv = src.account && src.account.loginWithWebview;
            if (!wv || typeof wv.checkStatus !== 'function') return false;
            return !!wv.checkStatus($qUrl, $qTitle);
        """.trimIndent()
        val raw = invokeAsyncJs(sourceKey, js)
        return raw.trim() == "true"
    }

    suspend fun completeWebViewLogin(sourceKey: String): Boolean {
        val js = """
            const wv = src.account && src.account.loginWithWebview;
            if (wv && typeof wv.onLoginSuccess === 'function') {
                await wv.onLoginSuccess();
            }
            src.saveData('__logged_in__', true);
            return true;
        """.trimIndent()
        invokeAsyncJs(sourceKey, js, timeoutMs = 60_000)
        return true
    }

    suspend fun logout(sourceKey: String) {
        val js = """
            if (src.account && typeof src.account.logout === 'function') {
                await src.account.logout();
            }
            src.deleteData('__logged_in__');
            src.deleteData('account');
            return true;
        """.trimIndent()
        runCatching { invokeAsyncJs(sourceKey, js) }
    }

    fun invalidateSource(sourceKey: String) {
        loadedScriptVersions.remove(sourceKey)
    }

    private fun resolveAsyncJs(cbId: String, jsonResult: String) {
        val wv = webView ?: return
        mainHandler.post {
            wv.evaluateJavascript(
                "window.__veneraResolveAsync(${JSONObject.quote(cbId)}, ${JSONObject.quote(jsonResult)})",
                null
            )
        }
    }

    private fun rejectAsyncJs(cbId: String, errorMsg: String) {
        val wv = webView ?: return
        mainHandler.post {
            wv.evaluateJavascript(
                "window.__veneraRejectAsync(${JSONObject.quote(cbId)}, ${JSONObject.quote(errorMsg)})",
                null
            )
        }
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
        fun dispatchAsync(cbId: String, jsonStr: String) {
            val req = runCatching { JSONObject(jsonStr) }.getOrNull()
            if (req == null) {
                rejectAsyncJs(cbId, "Invalid JSON request")
                return
            }
            when (val method = req.optString("method")) {
                "http", "http_bytes" -> {
                    ioScope.launch {
                        runCatching {
                            handleHttp(req, asBytes = method == "http_bytes")
                        }.onSuccess { resultJson ->
                            resolveAsyncJs(cbId, resultJson)
                        }.onFailure { err ->
                            val errObj = JSONObject().put("error", err.message ?: err.toString()).toString()
                            resolveAsyncJs(cbId, errObj)
                        }
                    }
                }
                "ui_dialog" -> showNativeDialog(cbId, req)
                "ui_input" -> showNativeInputDialog(cbId, req)
                "ui_select" -> showNativeSelectDialog(cbId, req)
                else -> resolveAsyncJs(cbId, "null")
            }
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
                        when (req.optString("function")) {
                            "toast", "showLoading" -> {
                                val msg = req.optString("message", "")
                                if (msg.isNotEmpty()) {
                                    mainHandler.post { Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() }
                                }
                            }
                            "launchUrl" -> {
                                val url = req.optString("url", "")
                                if (url.isNotEmpty()) {
                                    mainHandler.post {
                                        runCatching {
                                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            context.startActivity(intent)
                                        }
                                    }
                                }
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

    private fun showNativeDialog(cbId: String, req: JSONObject) {
        mainHandler.post {
            val activity = currentActivityRef.get()
            if (activity == null || activity.isFinishing || activity.isDestroyed) {
                resolveAsyncJs(cbId, JSONObject().put("clickedIndex", -1).toString())
                return@post
            }
            val title = req.optString("title", "提示")
            val message = req.optString("message", "")
            val actionsArr = req.optJSONArray("actions") ?: JSONArray()
            var answered = false
            fun answer(idx: Int) {
                if (answered) return
                answered = true
                resolveAsyncJs(cbId, JSONObject().put("clickedIndex", idx).toString())
            }

            val dp = activity.resources.displayMetrics.density
            val scroll = ScrollView(activity).apply {
                val tv = TextView(activity).apply {
                    text = message
                    textSize = 14f
                    val p = (20 * dp).toInt()
                    setPadding(p, (12 * dp).toInt(), p, (8 * dp).toInt())
                }
                addView(tv)
            }

            val builder = MaterialAlertDialogBuilder(activity)
                .setTitle(title)
                .setView(scroll)
                .setOnCancelListener { answer(-1) }
                .setOnDismissListener { answer(-1) }

            if (actionsArr.length() == 0) {
                builder.setPositiveButton("确定") { _, _ -> answer(-1) }
            } else {
                for (i in 0 until minOf(3, actionsArr.length())) {
                    val act = actionsArr.optJSONObject(i) ?: continue
                    val label = act.optString("text", "确定")
                    when (i) {
                        0 -> builder.setPositiveButton(label) { _, _ -> answer(0) }
                        1 -> builder.setNegativeButton(label) { _, _ -> answer(1) }
                        2 -> builder.setNeutralButton(label) { _, _ -> answer(2) }
                    }
                }
            }
            builder.show()
        }
    }

    private fun showNativeInputDialog(cbId: String, req: JSONObject) {
        mainHandler.post {
            val activity = currentActivityRef.get()
            if (activity == null || activity.isFinishing || activity.isDestroyed) {
                resolveAsyncJs(cbId, JSONObject().put("cancelled", true).toString())
                return@post
            }
            val title = req.optString("title", "输入")
            val dp = activity.resources.displayMetrics.density
            val container = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                val p = (20 * dp).toInt()
                setPadding(p, (10 * dp).toInt(), p, 0)
            }
            val imgObj = req.optJSONObject("image")
            if (imgObj != null && imgObj.has("__ab__")) {
                runCatching {
                    val bytes = Base64.decode(imgObj.optString("__ab__", ""), Base64.DEFAULT)
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bmp != null) {
                        val iv = ImageView(activity).apply {
                            setImageBitmap(bmp)
                            adjustViewBounds = true
                            maxHeight = (120 * dp).toInt()
                            setPadding(0, 0, 0, (10 * dp).toInt())
                        }
                        container.addView(iv)
                    }
                }
            }
            val et = EditText(activity).apply {
                hint = "请输入"
            }
            container.addView(et)

            var answered = false
            fun finish(obj: JSONObject) {
                if (answered) return
                answered = true
                resolveAsyncJs(cbId, obj.toString())
            }

            MaterialAlertDialogBuilder(activity)
                .setTitle(title)
                .setView(container)
                .setPositiveButton("确定") { _, _ ->
                    finish(JSONObject().put("value", et.text?.toString().orEmpty()))
                }
                .setNegativeButton("取消") { _, _ ->
                    finish(JSONObject().put("cancelled", true))
                }
                .setOnCancelListener {
                    finish(JSONObject().put("cancelled", true))
                }
                .show()
        }
    }

    private fun showNativeSelectDialog(cbId: String, req: JSONObject) {
        mainHandler.post {
            val activity = currentActivityRef.get()
            if (activity == null || activity.isFinishing || activity.isDestroyed) {
                resolveAsyncJs(cbId, JSONObject().put("index", JSONObject.NULL).toString())
                return@post
            }
            val title = req.optString("title", "选择")
            val arr = req.optJSONArray("options") ?: JSONArray()
            val items = Array(arr.length()) { idx -> arr.optString(idx, "") }
            val initialIdx = req.optInt("initialIndex", -1)

            var answered = false
            fun finish(index: Int?) {
                if (answered) return
                answered = true
                val out = JSONObject().put("index", index ?: JSONObject.NULL)
                resolveAsyncJs(cbId, out.toString())
            }

            val builder = MaterialAlertDialogBuilder(activity)
                .setTitle(title)
                .setNegativeButton("取消") { _, _ -> finish(null) }
                .setOnCancelListener { finish(null) }

            if (initialIdx in items.indices) {
                builder.setSingleChoiceItems(items, initialIdx) { dialog, which ->
                    dialog.dismiss()
                    finish(which)
                }
            } else {
                builder.setItems(items) { dialog, which ->
                    dialog.dismiss()
                    finish(which)
                }
            }
            builder.show()
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
            is JSONArray -> dataObj.toString().toByteArray(Charsets.UTF_8)
            is String -> dataObj.toByteArray(Charsets.UTF_8)
            else -> dataObj.toString().toByteArray(Charsets.UTF_8)
        }

        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = httpMethod
            connectTimeout = 10_000
            readTimeout = 15_000
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
                        if (dataObj is JSONObject || dataObj is JSONArray) {
                            "application/json; charset=utf-8"
                        } else {
                            "application/x-www-form-urlencoded"
                        }
                    )
                }
                outputStream.use { it.write(bodyBytes) }
            }
        }

        val status = conn.responseCode
        val respHeaders = JSONObject()
        var hadSetCookie = false
        conn.headerFields?.forEach { (k, values) ->
            if (k != null && values.isNotEmpty()) {
                if (k.equals("Set-Cookie", ignoreCase = true)) {
                    hadSetCookie = true
                    respHeaders.put("set-cookie", JSONArray(values))
                    values.forEach { cookieManager.setCookie(urlStr, it) }
                } else {
                    respHeaders.put(k.lowercase(), values.joinToString("; "))
                }
            }
        }
        if (hadSetCookie) {
            cookieManager.flush()
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
                if (!obj.has(key)) "null" else serializeJsonValue(obj.opt(key))
            }
            "saveSetting" -> {
                val src = db.getComicSource(sourceKey) ?: return "null"
                val obj = runCatching { JSONObject(src.settingsJson) }.getOrElse { JSONObject() }
                val value = req.opt("value")
                if (value == null || value === JSONObject.NULL) {
                    obj.remove(key)
                } else {
                    obj.put(key, value)
                }
                db.updateComicSourceSettings(sourceKey, obj.toString())
                "null"
            }
            "saveData" -> {
                val value = req.opt("value")
                if (value == null || value === JSONObject.NULL) {
                    prefs.edit().remove(key).apply()
                } else {
                    val envelope = JSONObject().put("v", value).toString()
                    prefs.edit().putString(key, envelope).apply()
                }
                "null"
            }
            "loadData" -> {
                val raw = prefs.getString(key, null) ?: return "null"
                val obj = runCatching { JSONObject(raw) }.getOrNull()
                if (obj != null && obj.has("v")) {
                    serializeJsonValue(obj.opt("v"))
                } else {
                    JSONObject.quote(raw)
                }
            }
            "deleteData" -> {
                prefs.edit().remove(key).apply()
                "null"
            }
            else -> "null"
        }
    }

    private fun serializeJsonValue(v: Any?): String {
        return when (v) {
            null, JSONObject.NULL -> "null"
            is String -> JSONObject.quote(v)
            is Boolean, is Number, is JSONObject, is JSONArray -> v.toString()
            else -> JSONObject.quote(v.toString())
        }
    }
}
