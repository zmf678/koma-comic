package com.arkreader.comic.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.arkreader.comic.data.ComicFormat
import com.arkreader.comic.data.DatabaseHelper
import com.arkreader.comic.data.Warehouse
import com.arkreader.comic.data.WarehouseType
import java.io.FileOutputStream
import java.util.zip.ZipFile

class LocalScanner(
    private val context: Context,
    private val db: DatabaseHelper,
    private val coverCache: CoverCache
) {

    fun scanWarehouse(
        warehouse: Warehouse,
        onProgress: ((String) -> Unit)? = null
    ): Int {
        val scanned = when (warehouse.type) {
            WarehouseType.LOCAL_SAF -> scanSafWarehouse(warehouse, onProgress)
            WarehouseType.WEBDAV -> scanWebDavWarehouse(warehouse, onProgress)
        }
        db.replaceWarehouseComics(warehouse.id, warehouse.defaultBookshelfId, scanned)
        return scanned.size
    }

    private fun scanSafWarehouse(
        warehouse: Warehouse,
        onProgress: ((String) -> Unit)?
    ): List<DatabaseHelper.ScannedComic> {
        val rootUri = Uri.parse(warehouse.uriOrUrl)
        val rootDoc = DocumentFile.fromTreeUri(context, rootUri)
            ?: throw IllegalStateException("无法访问目录，请重新授权仓库目录")

        if (warehouse.hideInGallery) {
            ensureNoMedia(rootDoc)
        }

        val results = mutableListOf<DatabaseHelper.ScannedComic>()
        val children = rootDoc.listFiles()
            .filter { !(it.name.orEmpty().startsWith(".")) }
            .sortedWith { a, b -> NaturalSort.compare(a.name.orEmpty(), b.name.orEmpty()) }

        for (item in children) {
            val name = item.name.orEmpty()
            if (name.isBlank()) continue
            onProgress?.invoke("正在扫描: $name")

            if (item.isDirectory) {
                val comic = scanDirectoryComic(item, name, warehouse.coverName)
                if (comic != null) results.add(comic)
            } else if (NaturalSort.isArchiveName(name)) {
                val comic = scanSingleArchiveComic(item, name)
                if (comic != null) results.add(comic)
            } else if (NaturalSort.isPdfName(name)) {
                val comic = scanSinglePdfComic(item, name)
                if (comic != null) results.add(comic)
            }
        }
        return results
    }

    private fun scanDirectoryComic(
        dir: DocumentFile,
        title: String,
        preferredCoverName: String
    ): DatabaseHelper.ScannedComic? {
        val children = dir.listFiles()
            .filter { !(it.name.orEmpty().startsWith(".")) }
            .sortedWith { a, b -> NaturalSort.compare(a.name.orEmpty(), b.name.orEmpty()) }

        val images = children.filter { it.isFile && NaturalSort.isImageName(it.name.orEmpty()) }
        val subDirs = children.filter { it.isDirectory }
        val archives = children.filter { it.isFile && NaturalSort.isArchiveName(it.name.orEmpty()) }
        val pdfs = children.filter { it.isFile && NaturalSort.isPdfName(it.name.orEmpty()) }

        val chapters = mutableListOf<DatabaseHelper.ScannedChapter>()

        // Subdirectories as chapters
        for (sub in subDirs) {
            val subImages = sub.listFiles().count { it.isFile && NaturalSort.isImageName(it.name.orEmpty()) }
            if (subImages > 0) {
                chapters.add(
                    DatabaseHelper.ScannedChapter(
                        title = sub.name ?: "章节",
                        sourceUri = sub.uri.toString(),
                        format = ComicFormat.FOLDER,
                        pageCount = subImages
                    )
                )
            }
        }

        // Archives & PDFs inside comic folder as chapters
        for (arc in archives) {
            val arcName = arc.name.orEmpty().substringBeforeLast('.')
            val fmt = if (arc.name.orEmpty().endsWith(".epub", true)) ComicFormat.EPUB else ComicFormat.ZIP
            chapters.add(
                DatabaseHelper.ScannedChapter(
                    title = arcName,
                    sourceUri = arc.uri.toString(),
                    format = fmt,
                    pageCount = 0
                )
            )
        }
        for (pdf in pdfs) {
            chapters.add(
                DatabaseHelper.ScannedChapter(
                    title = pdf.name.orEmpty().substringBeforeLast('.'),
                    sourceUri = pdf.uri.toString(),
                    format = ComicFormat.PDF,
                    pageCount = 0
                )
            )
        }

        // If no sub-chapters, treat root images directly as single chapter
        if (chapters.isEmpty() && images.isNotEmpty()) {
            chapters.add(
                DatabaseHelper.ScannedChapter(
                    title = "单行本",
                    sourceUri = dir.uri.toString(),
                    format = ComicFormat.FOLDER,
                    pageCount = images.size
                )
            )
        }

        if (chapters.isEmpty()) return null

        val coverKey = coverCache.makeKey(dir.uri.toString())
        if (!coverCache.hasCover(coverKey)) {
            val explicitCover = images.firstOrNull {
                it.name.orEmpty().substringBeforeLast('.').equals(preferredCoverName, ignoreCase = true)
            }
            if (explicitCover != null) {
                saveCoverFromUri(explicitCover.uri, coverKey)
            } else if (images.isNotEmpty()) {
                saveCoverFromUri(images.first().uri, coverKey)
            } else {
                generateCoverFromChapter(chapters.first(), coverKey)
            }
        }

        return DatabaseHelper.ScannedComic(
            title = title,
            sourceUri = dir.uri.toString(),
            format = ComicFormat.FOLDER,
            coverCacheKey = coverKey,
            chapters = chapters.sortedWith { a, b -> NaturalSort.compare(a.title, b.title) }
        )
    }

    private fun scanSingleArchiveComic(
        file: DocumentFile,
        fileName: String
    ): DatabaseHelper.ScannedComic? {
        val uriStr = file.uri.toString()
        val fmt = if (fileName.endsWith(".epub", true)) ComicFormat.EPUB else ComicFormat.ZIP
        val coverKey = coverCache.makeKey(uriStr)
        val localZip = coverCache.getTempArchiveFile(coverKey, "zip")

        runCatching {
            if (!localZip.exists() || localZip.length() == 0L) {
                context.contentResolver.openInputStream(file.uri)?.use { input ->
                    FileOutputStream(localZip).use { out -> input.copyTo(out, 64 * 1024) }
                }
            }
            ZipFile(localZip).use { zip ->
                val imageEntries = mutableListOf<String>()
                val enum = zip.entries()
                while (enum.hasMoreElements()) {
                    val e = enum.nextElement()
                    if (!e.isDirectory && NaturalSort.isImageName(e.name.substringAfterLast('/'))) {
                        imageEntries.add(e.name)
                    }
                }
                if (imageEntries.isEmpty()) return null
                imageEntries.sortWith { a, b -> NaturalSort.compare(a, b) }

                if (!coverCache.hasCover(coverKey)) {
                    val firstEntry = zip.getEntry(imageEntries.first())
                    if (firstEntry != null) {
                        ImageProcessor.decodeSampledBitmap({ zip.getInputStream(firstEntry) }, 420, 600)?.let {
                            coverCache.putCover(coverKey, it)
                        }
                    }
                }

                // Group by top-level folder inside ZIP if multiple sub-folders contain images
                val grouped = imageEntries.groupBy { path ->
                    if ('/' in path) path.substringBefore('/') else ""
                }
                val chapters = if (grouped.size > 1 && "" !in grouped) {
                    grouped.keys.sortedWith { a, b -> NaturalSort.compare(a, b) }.map { prefix ->
                        DatabaseHelper.ScannedChapter(
                            title = prefix,
                            sourceUri = uriStr,
                            format = fmt,
                            entryPrefix = "$prefix/",
                            pageCount = grouped[prefix]?.size ?: 0
                        )
                    }
                } else {
                    listOf(
                        DatabaseHelper.ScannedChapter(
                            title = "全一卷",
                            sourceUri = uriStr,
                            format = fmt,
                            entryPrefix = "",
                            pageCount = imageEntries.size
                        )
                    )
                }

                return DatabaseHelper.ScannedComic(
                    title = fileName.substringBeforeLast('.'),
                    sourceUri = uriStr,
                    format = fmt,
                    coverCacheKey = coverKey,
                    chapters = chapters
                )
            }
        }
        return null
    }

    private fun scanSinglePdfComic(
        file: DocumentFile,
        fileName: String
    ): DatabaseHelper.ScannedComic? {
        val uriStr = file.uri.toString()
        val coverKey = coverCache.makeKey(uriStr)
        var pageCount = 0
        runCatching {
            context.contentResolver.openFileDescriptor(file.uri, "r")?.use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    pageCount = renderer.pageCount
                    if (pageCount > 0 && !coverCache.hasCover(coverKey)) {
                        renderer.openPage(0).use { page ->
                            val w = 420
                            val h = (page.height * (420f / page.width)).toInt().coerceIn(200, 800)
                            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                            bmp.eraseColor(Color.WHITE)
                            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            coverCache.putCover(coverKey, bmp)
                        }
                    }
                }
            }
        }
        if (pageCount <= 0) return null
        return DatabaseHelper.ScannedComic(
            title = fileName.substringBeforeLast('.'),
            sourceUri = uriStr,
            format = ComicFormat.PDF,
            coverCacheKey = coverKey,
            chapters = listOf(
                DatabaseHelper.ScannedChapter(
                    title = "PDF 全文",
                    sourceUri = uriStr,
                    format = ComicFormat.PDF,
                    pageCount = pageCount
                )
            )
        )
    }

    private fun scanWebDavWarehouse(
        warehouse: Warehouse,
        onProgress: ((String) -> Unit)?
    ): List<DatabaseHelper.ScannedComic> {
        val client = WebDavClient(warehouse.uriOrUrl, warehouse.webdavUser, warehouse.webdavPass)
        val items = client.listDirectory(warehouse.uriOrUrl)
            .sortedWith { a, b -> NaturalSort.compare(a.name, b.name) }

        val results = mutableListOf<DatabaseHelper.ScannedComic>()
        for (entry in items) {
            if (entry.name.startsWith(".")) continue
            onProgress?.invoke("WebDAV 扫描: ${entry.name}")
            if (entry.isCollection) {
                val subItems = runCatching { client.listDirectory(entry.href) }.getOrDefault(emptyList())
                    .sortedWith { a, b -> NaturalSort.compare(a.name, b.name) }
                val chapters = mutableListOf<DatabaseHelper.ScannedChapter>()
                for (sub in subItems) {
                    when {
                        sub.isCollection -> chapters.add(
                            DatabaseHelper.ScannedChapter(sub.name, sub.href, ComicFormat.FOLDER)
                        )
                        NaturalSort.isArchiveName(sub.name) -> chapters.add(
                            DatabaseHelper.ScannedChapter(
                                sub.name.substringBeforeLast('.'),
                                sub.href,
                                if (sub.name.endsWith(".epub", true)) ComicFormat.EPUB else ComicFormat.ZIP
                            )
                        )
                        NaturalSort.isPdfName(sub.name) -> chapters.add(
                            DatabaseHelper.ScannedChapter(sub.name.substringBeforeLast('.'), sub.href, ComicFormat.PDF)
                        )
                    }
                }
                if (chapters.isEmpty() && subItems.any { !it.isCollection && NaturalSort.isImageName(it.name) }) {
                    chapters.add(DatabaseHelper.ScannedChapter("单行本", entry.href, ComicFormat.FOLDER))
                }
                if (chapters.isNotEmpty()) {
                    results.add(
                        DatabaseHelper.ScannedComic(
                            title = entry.name,
                            sourceUri = entry.href,
                            format = ComicFormat.FOLDER,
                            coverCacheKey = coverCache.makeKey(entry.href),
                            chapters = chapters
                        )
                    )
                }
            } else if (NaturalSort.isArchiveName(entry.name) || NaturalSort.isPdfName(entry.name)) {
                val fmt = when {
                    NaturalSort.isPdfName(entry.name) -> ComicFormat.PDF
                    entry.name.endsWith(".epub", true) -> ComicFormat.EPUB
                    else -> ComicFormat.ZIP
                }
                results.add(
                    DatabaseHelper.ScannedComic(
                        title = entry.name.substringBeforeLast('.'),
                        sourceUri = entry.href,
                        format = fmt,
                        coverCacheKey = coverCache.makeKey(entry.href),
                        chapters = listOf(
                            DatabaseHelper.ScannedChapter("全一卷", entry.href, fmt)
                        )
                    )
                )
            }
        }
        return results
    }

    private fun saveCoverFromUri(uri: Uri, coverKey: String) {
        ImageProcessor.decodeSampledBitmap({ context.contentResolver.openInputStream(uri) }, 420, 600)?.let {
            coverCache.putCover(coverKey, it)
        }
    }

    private fun generateCoverFromChapter(chapter: DatabaseHelper.ScannedChapter, coverKey: String) {
        runCatching {
            val ch = com.arkreader.comic.data.Chapter(
                comicId = 0,
                title = chapter.title,
                sourceUri = chapter.sourceUri,
                format = chapter.format,
                entryPrefix = chapter.entryPrefix
            )
            PageSourceFactory.open(context, ch, null, coverCache, 1.5f).use { src ->
                if (src.pageCount > 0) {
                    src.loadPageBitmap(0, 420, 600)?.let { coverCache.putCover(coverKey, it) }
                }
            }
        }
    }

    private fun ensureNoMedia(rootDir: DocumentFile) {
        runCatching {
            if (rootDir.findFile(".nomedia") == null) {
                rootDir.createFile("application/octet-stream", ".nomedia")
            }
        }
    }
}
