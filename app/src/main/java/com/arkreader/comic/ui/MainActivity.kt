package com.arkreader.comic.ui

import android.content.Intent
import android.graphics.Bitmap
import android.hardware.biometrics.BiometricPrompt
import android.os.Bundle
import android.os.CancellationSignal
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.arkreader.comic.ArkReaderApp
import com.arkreader.comic.R
import com.arkreader.comic.data.Bookshelf
import com.arkreader.comic.data.Comic
import com.arkreader.comic.data.DatabaseHelper
import com.arkreader.comic.data.GridSize
import com.arkreader.comic.data.LockType
import com.arkreader.comic.data.SortType
import com.arkreader.comic.databinding.ActivityMainBinding
import com.arkreader.comic.databinding.ItemComicCardBinding
import com.arkreader.comic.databinding.ItemComicListBinding
import com.arkreader.comic.engine.LocalScanner
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val app get() = ArkReaderApp.instance

    private var currentNavMode = NAV_HOME // 0=Home, 1=Bookshelf
    private var homeTabMode = 0 // 0=All, 1=Favorite, 2=History
    private val unlockedShelfIds = mutableSetOf<Long>()
    private val shelfPassTimes = mutableMapOf<Long, Long>()
    private var currentQuery = ""
    private var scanJob: Job? = null

    private val comicAdapter = ComicAdapter(
        onClick = { comic -> openComic(comic) },
        onLongClick = { comic -> showComicQuickMenu(comic) },
        loadCover = { key, iv -> loadCoverAsync(key, iv) }
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupNavigation()
        setupSearchAndFilters()
        setupRecycler()
        setupFab()
    }

    override fun onResume() {
        super.onResume()
        // Sync highlight on nav bar back to current mode after returning from sub-activities
        val selectedId = if (currentNavMode == NAV_HOME) R.id.menu_home else R.id.menu_bookshelf
        binding.navRail?.menu?.findItem(selectedId)?.isChecked = true
        binding.bottomNav?.menu?.findItem(selectedId)?.isChecked = true
        applyGridLayoutManager()
        relockQuickShelvesIfNeeded()
        reloadComics()
    }

    private fun setupNavigation() {
        val navListener: (Int) -> Boolean = { itemId ->
            when (itemId) {
                R.id.menu_home -> {
                    currentNavMode = NAV_HOME
                    binding.chipGroupHome.visibility = View.VISIBLE
                    reloadComics()
                    true
                }
                R.id.menu_bookshelf -> {
                    currentNavMode = NAV_BOOKSHELF
                    binding.chipGroupHome.visibility = View.GONE
                    promptSelectBookshelfOrReload()
                    true
                }
                R.id.menu_warehouse -> {
                    startActivity(Intent(this, WarehouseActivity::class.java))
                    false
                }
                R.id.menu_settings -> {
                    startActivity(Intent(this, SettingsActivity::class.java))
                    false
                }
                else -> false
            }
        }
        binding.navRail?.setOnItemSelectedListener { navListener(it.itemId) }
        binding.bottomNav?.setOnItemSelectedListener { navListener(it.itemId) }
    }

    private fun setupSearchAndFilters() {
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                currentQuery = s?.toString()?.trim().orEmpty()
                reloadComics()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.chipAll.setOnClickListener {
            homeTabMode = 0
            reloadComics()
        }
        binding.chipFavorite.setOnClickListener {
            homeTabMode = 1
            reloadComics()
        }
        binding.chipHistory.setOnClickListener {
            homeTabMode = 2
            reloadComics()
        }

        binding.btnFilterSort.setOnClickListener { showSortAndGridDialog() }
        binding.btnMore.setOnClickListener { anchor -> showMoreMenu(anchor) }
    }

    private fun setupRecycler() {
        applyGridLayoutManager()
        binding.rvComics.adapter = comicAdapter
    }

    private fun applyGridLayoutManager() {
        val prefs = app.prefs
        val isTablet = resources.configuration.smallestScreenWidthDp >= 600
        val grid = prefs.gridSize
        if (grid == GridSize.LIST) {
            binding.rvComics.layoutManager = LinearLayoutManager(this)
        } else {
            val span = when (grid) {
                GridSize.LARGE -> if (isTablet) 4 else 2
                GridSize.MEDIUM -> if (isTablet) 6 else 3
                GridSize.SMALL -> if (isTablet) 8 else 4
                GridSize.LIST -> 1
            }
            binding.rvComics.layoutManager = GridLayoutManager(this, span)
        }
        comicAdapter.updateDisplayPrefs(grid, prefs.doubleLineTitle, prefs.onlyShowCover)
    }

    private fun setupFab() {
        binding.fabContinue.setOnClickListener {
            val recent = app.db.getMostRecentComic()
            if (recent == null) {
                Toast.makeText(this, "暂无阅读记录", Toast.LENGTH_SHORT).show()
            } else {
                openComicReader(recent)
            }
        }
    }

    private fun relockQuickShelvesIfNeeded() {
        val now = System.currentTimeMillis()
        val shelves = app.db.getBookshelves()
        for (shelf in shelves) {
            if (shelf.lockType != LockType.NONE && shelf.quickRelock) {
                val passedAt = shelfPassTimes[shelf.id] ?: 0L
                if (now - passedAt > 2000L) {
                    unlockedShelfIds.remove(shelf.id)
                }
            }
        }
    }

    private fun reloadComics() {
        val activeShelfId = if (currentNavMode == NAV_HOME) 0L else app.prefs.activeBookshelfId
        if (currentNavMode == NAV_HOME) {
            binding.tvSectionTitle.text = when (homeTabMode) {
                1 -> "收藏"
                2 -> "最近"
                else -> "全部"
            }
        } else {
            val shelf = app.db.getBookshelf(activeShelfId)
            binding.tvSectionTitle.text = shelf?.name ?: "书架"
        }

        val list = if (currentNavMode == NAV_BOOKSHELF && activeShelfId > 0L) {
            val shelf = app.db.getBookshelf(activeShelfId)
            if (shelf != null && shelf.lockType != LockType.NONE && !unlockedShelfIds.contains(shelf.id)) {
                emptyList()
            } else {
                app.db.getComicsForHome(0, activeShelfId, unlockedShelfIds, currentQuery, app.prefs.sortType)
            }
        } else {
            app.db.getComicsForHome(homeTabMode, 0L, unlockedShelfIds, currentQuery, app.prefs.sortType)
        }

        binding.tvSectionCount.text = list.size.toString()
        comicAdapter.submitList(list)
        binding.tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        if (list.isEmpty() && app.db.getWarehouses().isEmpty()) {
            binding.tvEmpty.text = "尚未添加仓库，点击左侧或下方「仓库」添加本地文件夹或 WebDAV"
        } else if (list.isEmpty()) {
            binding.tvEmpty.text = "当前分类暂无漫画"
        }
    }

    private fun promptSelectBookshelfOrReload() {
        val shelves = app.db.getBookshelves()
        if (shelves.isEmpty()) {
            startActivity(Intent(this, BookshelfManageActivity::class.java))
            return
        }
        val names = shelves.map {
            val lockMark = if (it.lockType != LockType.NONE) " [已加密]" else ""
            it.name + lockMark
        }.toTypedArray()
        val currentIdx = shelves.indexOfFirst { it.id == app.prefs.activeBookshelfId }.coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle("选择书架")
            .setSingleChoiceItems(names, currentIdx) { dialog, which ->
                val chosen = shelves[which]
                dialog.dismiss()
                selectBookshelf(chosen)
            }
            .setPositiveButton("管理书架") { _, _ ->
                startActivity(Intent(this, BookshelfManageActivity::class.java))
            }
            .setNegativeButton("取消") { _, _ -> reloadComics() }
            .show()
    }

    private fun selectBookshelf(shelf: Bookshelf) {
        if (shelf.lockType == LockType.NONE || unlockedShelfIds.contains(shelf.id)) {
            app.prefs.activeBookshelfId = shelf.id
            reloadComics()
            return
        }
        when (shelf.lockType) {
            LockType.PIN -> showPinUnlockDialog(shelf)
            LockType.BIOMETRIC -> showBiometricUnlock(shelf)
            LockType.NONE -> Unit
        }
    }

    private fun showPinUnlockDialog(shelf: Bookshelf) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "输入书架密码"
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("解锁书架「${shelf.name}」")
            .setView(input)
            .setPositiveButton("确定") { _, _ ->
                val entered = input.text?.toString().orEmpty()
                if (DatabaseHelper.hashPin(entered) == shelf.pinHash) {
                    unlockedShelfIds.add(shelf.id)
                    shelfPassTimes[shelf.id] = System.currentTimeMillis()
                    app.prefs.activeBookshelfId = shelf.id
                    reloadComics()
                } else {
                    Toast.makeText(this, "密码错误", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showBiometricUnlock(shelf: Bookshelf) {
        try {
            val prompt = BiometricPrompt.Builder(this)
                .setTitle("验证身份以访问「${shelf.name}」")
                .setNegativeButton("取消", mainExecutor) { _, _ -> }
                .build()
            prompt.authenticate(
                CancellationSignal(),
                mainExecutor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                        unlockedShelfIds.add(shelf.id)
                        shelfPassTimes[shelf.id] = System.currentTimeMillis()
                        app.prefs.activeBookshelfId = shelf.id
                        reloadComics()
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                        Toast.makeText(this@MainActivity, errString ?: "认证失败", Toast.LENGTH_SHORT).show()
                    }
                }
            )
        } catch (e: Exception) {
            Toast.makeText(this, "当前设备不支持生物识别或未录入指纹: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showSortAndGridDialog() {
        val sorts = SortType.values()
        val sortLabels = sorts.map { it.label }.toTypedArray()
        val curSort = sorts.indexOf(app.prefs.sortType).coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle("排序与视图")
            .setSingleChoiceItems(sortLabels, curSort) { dialog, which ->
                app.prefs.sortType = sorts[which]
                dialog.dismiss()
                reloadComics()
            }
            .setNeutralButton("切换视图密度") { _, _ -> showGridSizeDialog() }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun showGridSizeDialog() {
        val grids = GridSize.values()
        val labels = grids.map { it.label }.toTypedArray()
        val cur = grids.indexOf(app.prefs.gridSize).coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle("列表视图大小")
            .setSingleChoiceItems(labels, cur) { dialog, which ->
                app.prefs.gridSize = grids[which]
                dialog.dismiss()
                applyGridLayoutManager()
            }
            .show()
    }

    private fun showMoreMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, "重新扫描所有仓库")
        popup.menu.add(0, 2, 1, "管理仓库")
        if (currentNavMode == NAV_BOOKSHELF) {
            popup.menu.add(0, 3, 2, "切换/管理书架")
        } else {
            popup.menu.add(0, 4, 2, "书架管理")
        }
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> scanAllWarehouses()
                2 -> startActivity(Intent(this, WarehouseActivity::class.java))
                3 -> promptSelectBookshelfOrReload()
                4 -> startActivity(Intent(this, BookshelfManageActivity::class.java))
            }
            true
        }
        popup.show()
    }

    private fun scanAllWarehouses() {
        val warehouses = app.db.getWarehouses()
        if (warehouses.isEmpty()) {
            Toast.makeText(this, "请先在仓库页面添加本地或 WebDAV 仓库", Toast.LENGTH_SHORT).show()
            return
        }
        if (scanJob?.isActive == true) {
            Toast.makeText(this, "正在扫描中...", Toast.LENGTH_SHORT).show()
            return
        }
        scanJob = lifecycleScope.launch {
            Toast.makeText(this@MainActivity, "开始扫描仓库...", Toast.LENGTH_SHORT).show()
            val scanner = LocalScanner(this@MainActivity, app.db, app.coverCache)
            var total = 0
            withContext(Dispatchers.IO) {
                for (wh in warehouses) {
                    total += scanner.scanWarehouse(wh) {}
                }
            }
            Toast.makeText(this@MainActivity, "扫描完成，共索引 $total 部漫画", Toast.LENGTH_SHORT).show()
            reloadComics()
        }
    }

    private fun openComic(comic: Comic) {
        if (app.prefs.skipDetailPage) {
            openComicReader(comic)
        } else {
            startActivity(Intent(this, ComicDetailActivity::class.java).apply {
                putExtra(ComicDetailActivity.EXTRA_COMIC_ID, comic.id)
            })
        }
    }

    private fun openComicReader(comic: Comic) {
        val chapters = app.db.getChapters(comic.id)
        if (chapters.isEmpty()) {
            Toast.makeText(this, "该漫画没有可读取的章节", Toast.LENGTH_SHORT).show()
            return
        }
        val targetChapter = if (comic.lastReadChapterId > 0L) {
            chapters.find { it.id == comic.lastReadChapterId } ?: chapters.first()
        } else {
            chapters.first()
        }
        val startPage = if (targetChapter.id == comic.lastReadChapterId) comic.lastReadPage else targetChapter.lastReadPage
        startActivity(Intent(this, ComicReaderActivity::class.java).apply {
            putExtra(ComicReaderActivity.EXTRA_COMIC_ID, comic.id)
            putExtra(ComicReaderActivity.EXTRA_CHAPTER_ID, targetChapter.id)
            putExtra(ComicReaderActivity.EXTRA_PAGE_INDEX, startPage)
        })
    }

    private fun showComicQuickMenu(comic: Comic) {
        val favLabel = if (comic.isFavorite) "取消收藏" else "加入收藏"
        val items = arrayOf("继续阅读", "查看详情", favLabel, "加入书架")
        MaterialAlertDialogBuilder(this)
            .setTitle(comic.title)
            .setItems(items) { _, idx ->
                when (idx) {
                    0 -> openComicReader(comic)
                    1 -> startActivity(Intent(this, ComicDetailActivity::class.java).apply {
                        putExtra(ComicDetailActivity.EXTRA_COMIC_ID, comic.id)
                    })
                    2 -> {
                        app.db.setFavorite(comic.id, !comic.isFavorite)
                        reloadComics()
                    }
                    3 -> showAssignBookshelfDialog(comic)
                }
            }
            .show()
    }

    private fun showAssignBookshelfDialog(comic: Comic) {
        val shelves = app.db.getBookshelves()
        if (shelves.isEmpty()) {
            Toast.makeText(this, "请先在书架管理中新建书架", Toast.LENGTH_SHORT).show()
            return
        }
        val currentIds = app.db.getBookshelfIdsForComic(comic.id).toMutableSet()
        val names = shelves.map { it.name }.toTypedArray()
        val checked = BooleanArray(shelves.size) { currentIds.contains(shelves[it].id) }
        MaterialAlertDialogBuilder(this)
            .setTitle("加入书架")
            .setMultiChoiceItems(names, checked) { _, which, isChecked ->
                val id = shelves[which].id
                if (isChecked) currentIds.add(id) else currentIds.remove(id)
            }
            .setPositiveButton("保存") { _, _ ->
                app.db.setComicBookshelves(comic.id, currentIds)
                reloadComics()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun loadCoverAsync(cacheKey: String, imageView: ImageView) {
        imageView.tag = cacheKey
        imageView.setImageResource(R.drawable.ic_koma_logo)
        if (cacheKey.isBlank()) return
        lifecycleScope.launch {
            val bmp: Bitmap? = withContext(Dispatchers.IO) {
                app.coverCache.getCover(cacheKey)
            }
            if (imageView.tag == cacheKey && bmp != null) {
                imageView.setImageBitmap(bmp)
            }
        }
    }

    companion object {
        private const val NAV_HOME = 0
        private const val NAV_BOOKSHELF = 1
    }
}

private class ComicAdapter(
    private val onClick: (Comic) -> Unit,
    private val onLongClick: (Comic) -> Unit,
    private val loadCover: (String, ImageView) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val items = mutableListOf<Comic>()
    private var isListMode = false
    private var doubleLineTitle = false
    private var onlyShowCover = false
    private val dateFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

    fun submitList(newItems: List<Comic>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun updateDisplayPrefs(grid: GridSize, doubleLine: Boolean, onlyCover: Boolean) {
        val nextList = grid == GridSize.LIST
        val changed = nextList != isListMode || doubleLine != doubleLineTitle || onlyCover != onlyShowCover
        isListMode = nextList
        doubleLineTitle = doubleLine
        onlyShowCover = onlyCover
        if (changed) notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int = if (isListMode) 1 else 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == 1) {
            ListVH(ItemComicListBinding.inflate(inflater, parent, false))
        } else {
            GridVH(ItemComicCardBinding.inflate(inflater, parent, false))
        }
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = items[position]
        if (holder is GridVH) {
            val b = holder.binding
            b.tvTitle.visibility = if (onlyShowCover) View.GONE else View.VISIBLE
            b.tvTitle.maxLines = if (doubleLineTitle) 2 else 1
            b.tvTitle.text = item.title
            if (item.totalChapters > 1) {
                b.tvBadge.visibility = View.VISIBLE
                b.tvBadge.text = "${item.totalChapters}章"
            } else if (item.isFavorite) {
                b.tvBadge.visibility = View.VISIBLE
                b.tvBadge.text = "收藏"
            } else {
                b.tvBadge.visibility = View.GONE
            }
            loadCover(item.coverCacheKey, b.ivCover)
            b.root.setOnClickListener { onClick(item) }
            b.root.setOnLongClickListener { onLongClick(item); true }
        } else if (holder is ListVH) {
            val b = holder.binding
            b.tvTitle.text = item.title
            val readInfo = if (item.lastReadAt > 0L) "最近: ${dateFormat.format(Date(item.lastReadAt))}" else "未读"
            b.tvSub.text = "${item.format.name} · ${item.totalChapters} 章节 · $readInfo"
            loadCover(item.coverCacheKey, b.ivCover)
            b.root.setOnClickListener { onClick(item) }
            b.root.setOnLongClickListener { onLongClick(item); true }
        }
    }

    class GridVH(val binding: ItemComicCardBinding) : RecyclerView.ViewHolder(binding.root)
    class ListVH(val binding: ItemComicListBinding) : RecyclerView.ViewHolder(binding.root)
}
