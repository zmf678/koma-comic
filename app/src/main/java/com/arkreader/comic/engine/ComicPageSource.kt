package com.arkreader.comic.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.documentfile.provider.DocumentFile
import com.arkreader.comic.data.Chapter
import com.arkreader.comic.data.ComicFormat
import com.arkreader.comic.data.Warehouse
import com.arkreader.comic.data.WarehouseType
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

interface ComicPageSource : Closeable {
    val pageCount: Int
    fun loadPageBitmap(index: Int, reqWidth: Int, reqHeight: Int): Bitmap?
    fun getPageSize(index: Int): Pair<Int, Int>? = null
}

object PageSourceFactory {

    fun open(
        context: Context,
        chapter: Chapter,
        warehouse: Warehouse?,
        coverCache: CoverCache,
        pdfScale: Float = 2.0f
    ): ComicPageSource {
        val isWebDav = warehouse?.type == WarehouseType.WEBDAV ||
            chapter.sourceUri.startsWith("http://", true) ||
            chapter.sourceUri.startsWith("https://", true)

        if (isWebDav) {
            val client = WebDavClient(
                warehouse?.uriOrUrl ?: chapter.sourceUri,
                warehouse?.webdavUser ?: "",
                warehouse?.webdavPass ?: ""
            )
            return when (chapter.format) {
                ComicFormat.FOLDER -> WebDavFolderPageSource(client, chapter.sourceUri)
                ComicFormat.ZIP, ComicFormat.EPUB -> {
                    val key = coverCache.makeKey(chapter.sourceUri)
                    val localFile = coverCache.getTempArchiveFile(key, "zip")
                    if (!localFile.exists() || localFile.length() == 0L) {
                        client.downloadToFile(chapter.sourceUri, localFile)
                    }
                    ZipPageSource(localFile, chapter.entryPrefix)
                }
                ComicFormat.PDF -> {
                    val key = coverCache.makeKey(chapter.sourceUri)
                    val localFile = coverCache.getTempArchiveFile(key, "pdf")
                    if (!localFile.exists() || localFile.length() == 0L) {
                        client.downloadToFile(chapter.sourceUri, localFile)
                    }
                    val pfd = ParcelFileDescriptor.open(localFile, ParcelFileDescriptor.MODE_READ_ONLY)
                    PdfPageSource(pfd, pdfScale)
                }
            }
        }

        val uri = Uri.parse(chapter.sourceUri)
        return when (chapter.format) {
            ComicFormat.FOLDER -> FolderPageSource(context, uri)
            ComicFormat.ZIP, ComicFormat.EPUB -> {
                val key = coverCache.makeKey(chapter.sourceUri)
                val localFile = coverCache.getTempArchiveFile(key, "zip")
                if (!localFile.exists() || localFile.length() == 0L) {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(localFile).use { out ->
                            input.copyTo(out, bufferSize = 64 * 1024)
                        }
                    } ?: throw IllegalStateException("无法读取压缩包: ${chapter.sourceUri}")
                }
                ZipPageSource(localFile, chapter.entryPrefix)
            }
            ComicFormat.PDF -> {
                val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                    ?: throw IllegalStateException("无法打开 PDF: ${chapter.sourceUri}")
                PdfPageSource(pfd, pdfScale)
            }
        }
    }
}

class FolderPageSource(
    private val context: Context,
    treeUri: Uri
) : ComicPageSource {
    private val files: List<DocumentFile>

    init {
        val doc = DocumentFile.fromTreeUri(context, treeUri)
            ?: DocumentFile.fromSingleUri(context, treeUri)
        files = doc?.listFiles()
            ?.filter { it.isFile && NaturalSort.isImageName(it.name.orEmpty()) }
            ?.sortedWith { a, b -> NaturalSort.compare(a.name.orEmpty(), b.name.orEmpty()) }
            ?: emptyList()
    }

    override val pageCount: Int get() = files.size

    override fun loadPageBitmap(index: Int, reqWidth: Int, reqHeight: Int): Bitmap? {
        val doc = files.getOrNull(index) ?: return null
        return ImageProcessor.decodeSampledBitmap(
            { context.contentResolver.openInputStream(doc.uri) },
            reqWidth,
            reqHeight
        )
    }

    override fun getPageSize(index: Int): Pair<Int, Int>? {
        val doc = files.getOrNull(index) ?: return null
        return ImageProcessor.decodeImageBounds { context.contentResolver.openInputStream(doc.uri) }
    }

    override fun close() = Unit
}

class ZipPageSource(
    file: File,
    entryPrefix: String
) : ComicPageSource {
    private val zip = ZipFile(file)
    private val entries: List<ZipEntry>

    init {
        val all = mutableListOf<ZipEntry>()
        val enum = zip.entries()
        while (enum.hasMoreElements()) {
            val e = enum.nextElement()
            if (e.isDirectory) continue
            if (entryPrefix.isNotEmpty() && !e.name.startsWith(entryPrefix)) continue
            val shortName = e.name.substringAfterLast('/')
            if (NaturalSort.isImageName(shortName)) {
                all.add(e)
            }
        }
        entries = all.sortedWith { a, b -> NaturalSort.compare(a.name, b.name) }
    }

    override val pageCount: Int get() = entries.size

    override fun loadPageBitmap(index: Int, reqWidth: Int, reqHeight: Int): Bitmap? {
        val entry = entries.getOrNull(index) ?: return null
        return ImageProcessor.decodeSampledBitmap(
            { zip.getInputStream(entry) },
            reqWidth,
            reqHeight
        )
    }

    override fun getPageSize(index: Int): Pair<Int, Int>? {
        val entry = entries.getOrNull(index) ?: return null
        return ImageProcessor.decodeImageBounds { zip.getInputStream(entry) }
    }

    override fun close() {
        runCatching { zip.close() }
    }
}

class PdfPageSource(
    private val pfd: ParcelFileDescriptor,
    private val scale: Float
) : ComicPageSource {
    private val renderer = PdfRenderer(pfd)
    private val lock = Any()

    override val pageCount: Int get() = renderer.pageCount

    override fun loadPageBitmap(index: Int, reqWidth: Int, reqHeight: Int): Bitmap? {
        if (index !in 0 until renderer.pageCount) return null
        synchronized(lock) {
            renderer.openPage(index).use { page ->
                val targetW = (page.width * scale).toInt().coerceIn(300, 3200)
                val targetH = (page.height * scale).toInt().coerceIn(300, 4800)
                val bmp = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                return bmp
            }
        }
    }

    override fun getPageSize(index: Int): Pair<Int, Int>? {
        if (index !in 0 until renderer.pageCount) return null
        synchronized(lock) {
            return renderer.openPage(index).use { it.width to it.height }
        }
    }

    override fun close() {
        synchronized(lock) {
            runCatching { renderer.close() }
            runCatching { pfd.close() }
        }
    }
}

class WebDavFolderPageSource(
    private val client: WebDavClient,
    dirUrl: String
) : ComicPageSource {
    private val imageUrls: List<String> = client.listDirectory(dirUrl)
        .filter { !it.isCollection && NaturalSort.isImageName(it.name) }
        .sortedWith { a, b -> NaturalSort.compare(a.name, b.name) }
        .map { it.href }

    override val pageCount: Int get() = imageUrls.size

    override fun loadPageBitmap(index: Int, reqWidth: Int, reqHeight: Int): Bitmap? {
        val url = imageUrls.getOrNull(index) ?: return null
        return ImageProcessor.decodeSampledBitmap(
            {
                val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                    connectTimeout = 10_000
                    readTimeout = 20_000
                }
                conn.inputStream
            },
            reqWidth,
            reqHeight
        )
    }

    override fun close() = Unit
}
