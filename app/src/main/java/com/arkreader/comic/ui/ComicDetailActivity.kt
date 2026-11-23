package com.arkreader.comic.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.arkreader.comic.ArkReaderApp
import com.arkreader.comic.R
import com.arkreader.comic.data.Chapter
import com.arkreader.comic.data.Comic
import com.arkreader.comic.data.ComicFormat
import com.arkreader.comic.databinding.ActivityComicDetailBinding
import com.arkreader.comic.databinding.ItemChapterBinding
import com.arkreader.comic.engine.VeneraScriptParser
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ComicDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityComicDetailBinding
    private val app get() = ArkReaderApp.instance

    private var comicId: Long = 0L
    private var remoteSourceKey: String = ""
    private var remoteComicId: String = ""
    private var remotePreviewTitle: String = ""
    private var remotePreviewCover: String = ""
    private var remotePreviewSub: String = ""
    private var onlineLoaded = false
    private var onlineLoading = false
    private var onlineError = ""
    private var officialUrl = ""
    private var comic: Comic? = null
    private var chapters: List<Chapter> = emptyList()
    private var reversedOrder = false

    private val chapterAdapter = ChapterAdapter { chapter ->
        openReader(chapter, chapter.lastReadPage)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityComicDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        comicId = intent.getLongExtra(EXTRA_COMIC_ID, 0L)
        remoteSourceKey = intent.getStringExtra(EXTRA_SOURCE_KEY).orEmpty()
        remoteComicId = intent.getStringExtra(EXTRA_REMOTE_ID).orEmpty()
        remotePreviewTitle = intent.getStringExtra(EXTRA_REMOTE_TITLE).orEmpty()
        remotePreviewCover = intent.getStringExtra(EXTRA_REMOTE_COVER).orEmpty()
        remotePreviewSub = intent.getStringExtra(EXTRA_REMOTE_SUB).orEmpty()

        binding.ivCover.showCoverLoading(true)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.rvChapters.layoutManager = LinearLayoutManager(this)
        binding.rvChapters.adapter = chapterAdapter

        binding.btnRead.setOnClickListener {
            val c = comic ?: return@setOnClickListener
            if (chapters.isEmpty() && c.format == ComicFormat.ONLINE && !onlineLoading && !onlineLoaded) {
                VeneraScriptParser.parseVeneraComicUri(c.sourceUri)?.let { (key, id) -> fetchAndSyncRemoteDetails(key, id) }
                return@setOnClickListener
            }
            val target = chapters.find { it.id == c.lastReadChapterId } ?: chapters.firstOrNull()
            if (target == null) {
                Toast.makeText(this, "暂无章节", Toast.LENGTH_SHORT).show()
            } else {
                val startPage = if (target.id == c.lastReadChapterId) c.lastReadPage else target.lastReadPage
                openReader(target, startPage)
            }
        }

        binding.btnFavorite.setOnClickListener {
            val c = comic
            if (c == null) {
                Toast.makeText(this, "详情仍在加载中…", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val next = !c.isFavorite
            app.db.setFavorite(c.id, next)
            comic = c.copy(isFavorite = next)
            binding.btnFavorite.text = if (next) "已收藏" else "收藏"
            binding.btnFavorite.isSelected = next
            Toast.makeText(this, if (next) "已加入收藏" else "已取消收藏", Toast.LENGTH_SHORT).show()
        }

        binding.btnOfficialWork.setOnClickListener { openComicWebsite(officialUrl) }
        binding.btnBookshelf.setOnClickListener { showBookshelfDialog() }
        binding.btnReaderConfig.setOnClickListener { showReaderConfigDialog() }
        binding.btnReverseOrder.setOnClickListener {
            reversedOrder = !reversedOrder
            renderChapters()
        }
    }

    override fun onResume() {
        super.onResume()
        loadDetail()
    }

    private fun loadDetail() {
        if (comicId <= 0L && remoteSourceKey.isNotEmpty() && remoteComicId.isNotEmpty()) {
            val coverKey = if (remotePreviewCover.isBlank()) "" else app.coverCache.makeKey("url:$remotePreviewCover")
            comicId = app.db.saveOnlinePreview(remoteSourceKey, remoteComicId, remotePreviewTitle, coverKey).id
            app.db.saveOnlineMetadata(comicId, intent.getStringExtra(EXTRA_REMOTE_DESCRIPTION).orEmpty(),
                tags = intent.getStringArrayListExtra(EXTRA_REMOTE_TAGS).orEmpty(), onlyMissing = true)
        }

        val loaded = app.db.getComic(comicId)
        if (loaded != null) {
            comic = loaded
            chapters = app.db.getChapters(comicId)
            bindLocalComicUi(loaded)
            if (loaded.format == ComicFormat.ONLINE && !onlineLoaded && !onlineLoading && onlineError.isEmpty()) {
                VeneraScriptParser.parseVeneraComicUri(loaded.sourceUri)?.let { (key, id) -> fetchAndSyncRemoteDetails(key, id) }
            }
            return
        }

        Toast.makeText(this, "未找到漫画信息", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun bindLocalComicUi(loaded: Comic) {
        binding.toolbar.title = loaded.title
        binding.tvTitle.text = loaded.title
        val sourceLabel = if (loaded.format == ComicFormat.ONLINE) {
            val srcKey = VeneraScriptParser.parseVeneraComicUri(loaded.sourceUri)?.first.orEmpty()
            val srcName = app.db.getComicSource(srcKey)?.name ?: srcKey
            "在线源: $srcName"
        } else {
            val wh = app.db.getWarehouse(loaded.warehouseId)
            "仓库: ${wh?.name ?: "未知"}"
        }
        binding.tvMeta.text = "格式: ${loaded.format.name} · 章节: ${chapters.size} · $sourceLabel"

        val lastChap = chapters.find { it.id == loaded.lastReadChapterId }
        binding.tvLastRead.text = if (lastChap != null) {
            "上次阅读: ${lastChap.title} (第 ${loaded.lastReadPage + 1} 页)"
        } else {
            "尚未开始阅读"
        }
        binding.btnRead.text = if (lastChap != null) "继续阅读" else "开始阅读"
        binding.btnRead.isEnabled = true
        binding.btnFavorite.text = if (loaded.isFavorite) "已收藏" else "收藏"
        binding.btnFavorite.isSelected = loaded.isFavorite
        binding.btnBookshelf.visibility = if (loaded.format == ComicFormat.ONLINE) View.GONE else View.VISIBLE
        val shelfCount = app.db.getBookshelfIdsForComic(loaded.id).size
        binding.btnBookshelf.text = if (shelfCount > 0) "书架 ($shelfCount)" else "加入本地书架"
        if (loaded.format == ComicFormat.ONLINE) {
            val (description, url, tags) = app.db.getOnlineMetadata(loaded.id)
            binding.tvTags.visibility = if (tags.isEmpty()) View.GONE else View.VISIBLE
            binding.tvTags.text = tags.joinToString(" · ")
            binding.tvTags.setOnClickListener {
                MaterialAlertDialogBuilder(this).setTitle("作品标签").setMessage(tags.joinToString(" · "))
                    .setPositiveButton("关闭", null).show()
            }
            binding.tvDescription.visibility = if (description.isBlank()) View.GONE else View.VISIBLE
            binding.tvDescription.text = description
            binding.tvDescription.setOnClickListener {
                MaterialAlertDialogBuilder(this).setTitle("简介").setMessage(description)
                    .setPositiveButton("关闭", null).show()
            }
            officialUrl = url
            binding.btnOfficialWork.visibility = if (url.isBlank()) View.GONE else View.VISIBLE
        }
        if (loaded.format == ComicFormat.ONLINE && chapters.isEmpty()) {
            binding.btnRead.isEnabled = !onlineLoading
            binding.btnRead.text = when {
                onlineLoading -> "加载中"
                onlineError.isNotEmpty() -> "重试"
                else -> "开始阅读"
            }
            if (onlineLoading) binding.tvLastRead.text = "正在加载详情与章节…"
            if (onlineError.isNotEmpty()) binding.tvLastRead.text = "加载失败：$onlineError"
        }

        val cfg = app.db.getReaderConfig(loaded.readerConfigId)
        binding.btnReaderConfig.text = "阅读配置: ${cfg.name}"

        if (loaded.coverCacheKey.isNotBlank()) {
            lifecycleScope.launch {
                val bmp = withContext(Dispatchers.IO) { app.coverCache.getCover(loaded.coverCacheKey) }
                if (bmp != null) {
                    binding.ivCover.finishCover(bmp)
                } else {
                    if (!onlineLoading) binding.ivCover.showCoverLoading(false)
                }
            }
        } else binding.ivCover.showCoverLoading(false)
        renderChapters()
    }

    private fun fetchAndSyncRemoteDetails(sourceKey: String, remoteId: String) {
        onlineLoading = true
        onlineError = ""
        comic?.let(::bindLocalComicUi)
        lifecycleScope.launch {
            val details = try {
                val loaded = app.veneraRuntime.loadComicDetails(sourceKey, remoteId)
                val coverUrl = loaded.cover.ifEmpty { remotePreviewCover }
                val coverKey = if (coverUrl.isBlank()) "" else app.coverCache.makeKey("url:$coverUrl")
                withContext(Dispatchers.IO) {
                    val id = app.db.upsertOnlineComic(sourceKey, remoteId, loaded.title, coverKey, loaded.chapters)
                    app.db.saveOnlineMetadata(id, loaded.description, loaded.url, loaded.tags.values.flatten().distinct().take(64))
                }
                if (coverUrl.isNotBlank()) launch {
                    val (_, bitmap) = withContext(Dispatchers.IO) { app.coverCache.getOrDownloadHttpCover(coverUrl, loaded.headers) }
                    binding.ivCover.finishCover(bitmap)
                }
                loaded
            } catch (error: Exception) {
                if (error is CancellationException && error !is TimeoutCancellationException) throw error
                onlineError = error.message.orEmpty().lineSequence().first().ifBlank { "无法获取详情" }
                null
            }
            onlineLoading = false
            onlineLoaded = details != null
            loadDetail()
        }
    }

    private fun renderChapters() {
        val shown = if (reversedOrder) chapters.reversed() else chapters
        binding.tvChapterHeader.text = "章节列表 (${shown.size})"
        chapterAdapter.submitList(shown)
    }

    private fun showBookshelfDialog() {
        if (comicId > 0L) showComicBookshelves(comicId, ::loadDetail)
    }

    private fun showReaderConfigDialog() {
        if (comicId <= 0L) return
        val configs = app.db.getReaderConfigs()
        val c = comic ?: return
        val names = configs.map { it.name }.toTypedArray()
        val cur = configs.indexOfFirst { it.id == c.readerConfigId }.coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle("选择该漫画的阅读配置")
            .setSingleChoiceItems(names, cur) { dialog, which ->
                app.db.setComicReaderConfig(comicId, configs[which].id)
                dialog.dismiss()
                loadDetail()
            }
            .setPositiveButton("编辑配置") { _, _ ->
                startActivity(Intent(this, ReaderConfigActivity::class.java))
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun openReader(chapter: Chapter, pageIndex: Int) {
        startActivity(Intent(this, ComicReaderActivity::class.java).apply {
            putExtra(ComicReaderActivity.EXTRA_COMIC_ID, comicId)
            putExtra(ComicReaderActivity.EXTRA_CHAPTER_ID, chapter.id)
            putExtra(ComicReaderActivity.EXTRA_PAGE_INDEX, pageIndex)
        })
    }

    companion object {
        const val EXTRA_COMIC_ID = "extra_comic_id"
        const val EXTRA_SOURCE_KEY = "extra_source_key"
        const val EXTRA_REMOTE_ID = "extra_remote_id"
        const val EXTRA_REMOTE_TITLE = "extra_remote_title"
        const val EXTRA_REMOTE_COVER = "extra_remote_cover"
        const val EXTRA_REMOTE_DESCRIPTION = "extra_remote_description"
        const val EXTRA_REMOTE_TAGS = "extra_remote_tags"
        const val EXTRA_REMOTE_SUB = "extra_remote_sub"
    }
}

private class ChapterAdapter(
    private val onClick: (Chapter) -> Unit
) : RecyclerView.Adapter<ChapterAdapter.VH>() {

    private val items = mutableListOf<Chapter>()

    fun submitList(newItems: List<Chapter>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemChapterBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val ch = items[position]
        holder.binding.tvChapterTitle.text = ch.title
        holder.binding.tvChapterProgress.text = when {
            ch.pageCount > 0 && ch.lastReadPage > 0 -> "${ch.lastReadPage + 1}/${ch.pageCount}"
            ch.pageCount > 0 -> "${ch.pageCount}页"
            else -> ""
        }
        holder.binding.root.setOnClickListener { onClick(ch) }
    }

    class VH(val binding: ItemChapterBinding) : RecyclerView.ViewHolder(binding.root)
}
