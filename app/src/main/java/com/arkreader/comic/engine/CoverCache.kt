package com.arkreader.comic.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

class CoverCache(context: Context) {
    private val coverDir = File(context.cacheDir, "covers").apply { mkdirs() }
    private val tempDir = File(context.cacheDir, "archives").apply { mkdirs() }

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

    fun getCover(key: String): Bitmap? {
        if (key.isEmpty()) return null
        memCache.get(key)?.let { return it }
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

    fun getTotalCacheBytes(): Long {
        fun dirSize(d: File): Long = d.listFiles()?.sumOf { if (it.isDirectory) dirSize(it) else it.length() } ?: 0L
        return dirSize(coverDir) + dirSize(tempDir)
    }

    fun clearCovers() {
        memCache.evictAll()
        coverDir.listFiles()?.forEach { it.delete() }
    }

    fun clearArchives() {
        tempDir.listFiles()?.forEach { it.delete() }
    }
}
