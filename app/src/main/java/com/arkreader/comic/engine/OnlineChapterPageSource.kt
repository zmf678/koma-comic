package com.arkreader.comic.engine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.arkreader.comic.ArkReaderApp
import com.arkreader.comic.data.Chapter
import com.arkreader.comic.data.RemotePageImage
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap

class OnlineChapterPageSource(chapter: Chapter) : ComicPageSource {
    private val app = ArkReaderApp.instance
    private val images: List<RemotePageImage>
    private val sizeCache = ConcurrentHashMap<Int, Pair<Int, Int>>()
    private val sourceKey: String
    private val remoteId: String
    private val episodeId: String?

    init {
        val comicUri = app.db.getComic(chapter.comicId)?.sourceUri
            ?: error("未找到在线漫画")
        val (key, id) = VeneraScriptParser.parseVeneraComicUri(comicUri) ?: error("无效的在线漫画地址")
        val chapterPrefix = "$comicUri/"
        require(chapter.sourceUri.startsWith(chapterPrefix)) { "无效的在线章节地址" }
        sourceKey = key
        remoteId = id
        episodeId = chapter.sourceUri.removePrefix(chapterPrefix).ifEmpty { null }
        images = runBlocking { app.veneraRuntime.loadChapterImages(sourceKey, remoteId, episodeId) }
        if (images.isEmpty()) {
            throw IllegalStateException("该章节暂无图片页")
        }
    }

    private fun getPageFile(index: Int): java.io.File? {
        val page = images[index]
        val key = app.coverCache.makeKey("$sourceKey|$remoteId|$episodeId|$index|${page.url}|${page.headers.toSortedMap()}")
        val loadBytes = if (page.useSourceLoader) {
            { runBlocking { app.veneraRuntime.loadPageBytes(sourceKey, remoteId, episodeId, page) } }
        } else null
        return app.coverCache.getOrDownloadOnlinePageFile(key, page.url, page.headers, loadBytes)
    }

    override val pageCount: Int
        get() = images.size

    override fun loadPageBitmap(index: Int, reqWidth: Int, reqHeight: Int): Bitmap? {
        if (index !in images.indices) return null
        val file = getPageFile(index) ?: return null
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, boundsOpts)
        if (boundsOpts.outWidth > 0 && boundsOpts.outHeight > 0) {
            sizeCache[index] = boundsOpts.outWidth to boundsOpts.outHeight
        }
        val sample = computeInSampleSize(boundsOpts.outWidth, boundsOpts.outHeight, reqWidth, reqHeight)
        val decOpts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeFile(file.absolutePath, decOpts)
    }

    override fun getPageSize(index: Int): Pair<Int, Int>? {
        sizeCache[index]?.let { return it }
        if (index !in images.indices) return null
        val file = getPageFile(index) ?: return null
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        if (opts.outWidth > 0 && opts.outHeight > 0) {
            val pair = opts.outWidth to opts.outHeight
            sizeCache[index] = pair
            return pair
        }
        return null
    }

    override fun close() = Unit

    private fun computeInSampleSize(w: Int, h: Int, reqW: Int, reqH: Int): Int {
        if (reqW <= 0 || reqH <= 0 || w <= 0 || h <= 0) return 1
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
}
