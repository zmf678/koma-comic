package com.arkreader.comic.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
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
import kotlinx.coroutines.Dispatchers
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

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.rvChapters.layoutManager = LinearLayoutManager(this)
        binding.rvChapters.adapter = chapterAdapter

        binding.btnRead.setOnClickListener {
            val c = comic
            if (c == null) {
                Toast.makeText(this, "详情仍在加载中…", Toast.LENGTH_SHORT).show()
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
            loadDetail()
        }

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
            val existing = app.db.getComicBySourceUri("venera://$remoteSourceKey/$remoteComicId")
            if (existing != null) {
                comicId = existing.id
            }
        }

        if (comicId > 0L) {
            val loaded = app.db.getComic(comicId)
            if (loaded != null) {
                comic = loaded
                chapters = app.db.getChapters(comicId)
                bindLocalComicUi(loaded)
                if (loaded.format == ComicFormat.ONLINE && !onlineLoaded) {
                    val parsed = VeneraScriptParser.parseVeneraUri(loaded.sourceUri)
                    if (parsed != null) {
                        onlineLoaded = true
                        fetchAndSyncRemoteDetails(parsed.first, parsed.second)
                    }
                }
                return
            }
        }

        if (remoteSourceKey.isNotEmpty() && remoteComicId.isNotEmpty()) {
            binding.toolbar.title = remotePreviewTitle.ifEmpty { "在线漫画" }
            binding.tvTitle.text = remotePreviewTitle.ifEmpty { remoteComicId }
            binding.tvMeta.text = "在线源: ${app.db.getComicSource(remoteSourceKey)?.name ?: remoteSourceKey}" +
                if (remotePreviewSub.isNotEmpty()) " · $remotePreviewSub" else ""
            binding.tvLastRead.text = "正在加载详情与章节..."
            binding.btnRead.text = "加载中"
            if (remotePreviewCover.isNotBlank()) {
                lifecycleScope.launch {
                    val (_, bmp) = withContext(Dispatchers.IO) {
                        app.coverCache.getOrDownloadHttpCover(remotePreviewCover)
                    }
                    if (bmp != null) binding.ivCover.setImageBitmap(bmp)
                }
            }
            if (!onlineLoaded) {
                onlineLoaded = true
                fetchAndSyncRemoteDetails(remoteSourceKey, remoteComicId)
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
            val srcKey = VeneraScriptParser.parseVeneraUri(loaded.sourceUri)?.first.orEmpty()
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
        binding.btnFavorite.text = if (loaded.isFavorite) "已收藏" else "收藏"

        val cfg = app.db.getReaderConfig(loaded.readerConfigId)
        binding.btnReaderConfig.text = "阅读配置: ${cfg.name}"

        if (loaded.coverCacheKey.isNotBlank()) {
            lifecycleScope.launch {
                val bmp = withContext(Dispatchers.IO) { app.coverCache.getCover(loaded.coverCacheKey) }
                if (bmp != null) {
                    binding.ivCover.setImageBitmap(bmp)
                } else {
                    binding.ivCover.setImageResource(R.drawable.ic_koma_logo)
                }
            }
        }
        renderChapters()
    }

    private fun fetchAndSyncRemoteDetails(sourceKey: String, remoteId: String) {
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val details = app.veneraRuntime.loadComicDetails(sourceKey, remoteId)
                    val coverUrl = details.cover.ifEmpty { remotePreviewCover }
                    val (coverKey, _) = if (coverUrl.isNotBlank()) {
                        app.coverCache.getOrDownloadHttpCover(coverUrl, details.headers)
                    } else "" to null
                    val id = app.db.upsertOnlineComic(
                        sourceKey = sourceKey,
                        remoteId = remoteId,
                        title = details.title.ifEmpty { remotePreviewTitle.ifEmpty { remoteId } },
                        subTitle = details.subTitle.ifEmpty { remotePreviewSub },
                        coverKey = coverKey,
                        chapters = details.chapters
                    )
                    details to id
                }
            }.onSuccess { (details, syncedId) ->
                comicId = syncedId
                val updated = app.db.getComic(comicId)
                if (updated != null) {
                    comic = updated
                    chapters = app.db.getChapters(comicId)
                    bindLocalComicUi(updated)
                    if (details.description.isNotBlank()) {
                        binding.tvMeta.text = "${binding.tvMeta.text}\n${details.description.take(160)}"
                    }
                }
            }.onFailure { err ->
                if (comic == null) {
                    binding.tvLastRead.text = "加载失败: ${err.message}"
                    binding.btnRead.text = "重试"
                }
            }
        }
    }

    private fun renderChapters() {
        val shown = if (reversedOrder) chapters.reversed() else chapters
        binding.tvChapterHeader.text = "章节列表 (${shown.size})"
        chapterAdapter.submitList(shown)
    }

    private fun showBookshelfDialog() {
        if (comicId <= 0L) {
            Toast.makeText(this, "请等待详情加载完成后再加入作品集", Toast.LENGTH_SHORT).show()
            return
        }
        val shelves = app.db.getBookshelves()
        if (shelves.isEmpty()) {
            Toast.makeText(this, "请先新建作品集", Toast.LENGTH_SHORT).show()
            return
        }
        val selected = app.db.getBookshelfIdsForComic(comicId).toMutableSet()
        val names = shelves.map { it.name }.toTypedArray()
        val checked = BooleanArray(shelves.size) { selected.contains(shelves[it].id) }
        MaterialAlertDialogBuilder(this)
            .setTitle("加入作品集")
            .setMultiChoiceItems(names, checked) { _, idx, isChecked ->
                val id = shelves[idx].id
                if (isChecked) selected.add(id) else selected.remove(id)
            }
            .setPositiveButton("保存") { _, _ ->
                app.db.setComicBookshelves(comicId, selected)
                Toast.makeText(this, "已更新作品集", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
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
