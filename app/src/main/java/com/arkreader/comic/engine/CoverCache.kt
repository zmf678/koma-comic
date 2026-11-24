package com.arkreader.comic.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.webkit.CookieManager
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

class CoverCache(context: Context) {
    private val coverDir = File(context.cacheDir, "covers").apply { mkdirs() }
    private val tempDir = File(context.cacheDir, "archives").apply { mkdirs() }
    private val pageCacheDir = File(context.cacheDir, "online_pages").apply { mkdirs() }
    private val inFlightKeys = ConcurrentHashMap<String, Any>()

    private val memCache: LruCache<String, Bitmap>

    init {
        val maxKb = (Runtime.getRuntime().maxMemory() / 1024L).toInt() / 8
        memCache = object : LruCache<String, Bitmap>(maxKb.coerceAtLeast(8 * 1024)) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
        }
    }

    fun makeKey(raw: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(32)
    }

    fun getMemoryCover(key: String): Bitmap? {
        if (key.isEmpty()) return null
        return memCache.get(key)?.takeIf { !it.isRecycled }
    }

    fun getMemoryHttpCover(url: String): Bitmap? {
        if (url.isBlank()) return null
        val key = makeKey("url:$url")
        return memCache.get(key)?.takeIf { !it.isRecycled }
    }

    fun getCover(key: String): Bitmap? {
        if (key.isEmpty()) return null
        memCache.get(key)?.takeIf { !it.isRecycled }?.let { return it }
        val file = File(coverDir, "$key.jpg")
        if (!file.exists()) return null
        val bmp = BitmapFactory.decodeFile(file.absolutePath) ?: return null
        memCache.put(key, bmp)
        return bmp
    }

    fun putCover(key: String, bitmap: Bitmap) {
        memCache.put(key, bitmap)
        val file = File(coverDir, "$key.jpg")
        runCatching {
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }
        }
    }

    fun hasCover(key: String): Boolean {
        if (key.isEmpty()) return false
        return memCache.get(key) != null || File(coverDir, "$key.jpg").exists()
    }

    fun getTempArchiveFile(key: String, ext: String): File = File(tempDir, "$key.$ext")

    fun getOrDownloadHttpCover(url: String, headers: Map<String, String> = emptyMap()): Pair<String, Bitmap?> {
        if (url.isBlank()) return "" to null
        val key = makeKey("url:$url")
        getCover(key)?.let { return key to it }

        val lock = inFlightKeys.getOrPut(key) { Any() }
        synchronized(lock) {
            getCover(key)?.let { return key to it }
            val bytes = fetchHttpBytes(url, headers) ?: return key to null
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            val sample = calculateInSampleSize(opts.outWidth, opts.outHeight, 360, 480)
            val decOpts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decOpts) ?: return key to null
            putCover(key, bmp)
            return key to bmp
        }
    }

    fun getOrDownloadOnlinePageFile(
        key: String,
        url: String,
        headers: Map<String, String>,
        loadBytes: (() -> ByteArray?)? = null
    ): File? {
        val file = File(pageCacheDir, "$key.img")
        if (file.exists() && file.length() > 0) return file
        val lock = inFlightKeys.getOrPut("p:$key") { Any() }
        synchronized(lock) {
            if (file.exists() && file.length() > 0) return file
            val bytes = if (loadBytes != null) loadBytes() else fetchHttpBytes(url, headers)
            if (bytes == null || bytes.isEmpty()) return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val tmp = File(pageCacheDir, "$key.tmp")
            FileOutputStream(tmp).use { it.write(bytes) }
            return if (tmp.renameTo(file)) file else null
        }
    }

    private fun fetchHttpBytes(urlStr: String, headers: Map<String, String>): ByteArray? {
        return runCatching {
            val url = URL(urlStr)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 20000
                instanceFollowRedirects = true
                setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 13; Tablet) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
                )
                val cookies = runCatching { CookieManager.getInstance().getCookie(urlStr) }.getOrNull()
                if (!cookies.isNullOrBlank()) {
                    setRequestProperty("Cookie", cookies)
                }
                val hasReferer = headers.keys.any { it.equals("Referer", ignoreCase = true) }
                if (!hasReferer && url.host.isNotBlank()) {
                    setRequestProperty("Referer", "${url.protocol}://${url.host}/")
                }
                headers.forEach { (k, v) ->
                    if (!k.equals("Accept-Encoding", ignoreCase = true)) setRequestProperty(k, v)
                }
            }
            val code = conn.responseCode
            if (code in 200..299) {
                conn.inputStream.use { it.readBytes() }
            } else {
                null
            }
        }.getOrNull()
    }

    private fun calculateInSampleSize(w: Int, h: Int, reqW: Int, reqH: Int): Int {
        var inSampleSize = 1
        if (h > reqH || w > reqW) {
            val halfH = h / 2
            val halfW = w / 2
            while (halfH / inSampleSize >= reqH && halfW / inSampleSize >= reqW) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    fun getTotalCacheBytes(): Long {
        fun dirSize(d: File): Long = d.listFiles()?.sumOf { if (it.isDirectory) dirSize(it) else it.length() } ?: 0L
        return dirSize(coverDir) + dirSize(tempDir) + dirSize(pageCacheDir)
    }

    fun clearCovers() {
        memCache.evictAll()
        coverDir.listFiles()?.forEach { it.delete() }
    }

    fun clearArchives() {
        tempDir.listFiles()?.forEach { it.delete() }
        pageCacheDir.listFiles()?.forEach { it.delete() }
    }
}
