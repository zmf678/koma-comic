package com.arkreader.comic.ui

import android.content.Intent
import android.graphics.Bitmap
import android.hardware.biometrics.BiometricPrompt
import android.os.Bundle
import android.os.CancellationSignal
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.os.BundleCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.arkreader.comic.ArkReaderApp
import com.arkreader.comic.R
import com.arkreader.comic.data.Bookshelf
import com.arkreader.comic.data.Comic
import com.arkreader.comic.data.ComicFormat
import com.arkreader.comic.data.DatabaseHelper
import com.arkreader.comic.data.GridSize
import com.arkreader.comic.data.LockType
import com.arkreader.comic.data.RemoteComicItem
import com.arkreader.comic.data.SortType
import com.arkreader.comic.data.SourceBrowseFilters
import com.arkreader.comic.data.SourceBrowseMenu
import com.arkreader.comic.data.SourceBrowseTarget
import com.arkreader.comic.data.SourceCategoryGroup
import com.arkreader.comic.data.SourceFilterField
import com.arkreader.comic.databinding.ActivityMainBinding
import com.arkreader.comic.databinding.ItemComicCardBinding
import com.arkreader.comic.databinding.ItemComicListBinding
import com.arkreader.comic.engine.LocalScanner
import com.google.android.material.chip.Chip
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed class DisplayComicItem {
    data class Local(val comic: Comic) : DisplayComicItem()
    data class Online(val remote: RemoteComicItem) : DisplayComicItem()
    data class Section(val title: String, val target: SourceBrowseTarget?) : DisplayComicItem()
    data class PageAction(val text: String, val enabled: Boolean) : DisplayComicItem()
}

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val app get() = ArkReaderApp.instance
    private val Int.dp get() = (this * resources.displayMetrics.density + 0.5f).toInt()

    private var currentNavMode = NAV_HOME // 0=Home, 1=Collections, 2=Warehouse, 3=Settings
    private var activeHomeFilterTag = ""
    private val unlockedShelfIds = mutableSetOf<Long>()
    private val shelfPassTimes = mutableMapOf<Long, Long>()
    private var currentQuery = ""
    private var scanJob: Job? = null
    private var sourceLoadJob: Job? = null
    private var searchDebounceJob: Job? = null
    private lateinit var warehousePanel: MainWarehousePanel
    private lateinit var settingsPanel: MainSettingsPanel

    private var browseMenu: SourceBrowseMenu? = null
    private var browseTarget: SourceBrowseTarget? = null
    private var filterTarget: SourceBrowseTarget? = null
    private var browseFilters: SourceBrowseFilters? = null
    private var categoryJob: Job? = null
    private var onlinePage = 0
    private var onlineCursor: String? = null
    private var onlineHasNext = false
    private var onlineLoading = false
    private var onlinePageFailed = false
    private val onlineItems = mutableListOf<DisplayComicItem>()
    private val ungroupedComicIds = mutableSetOf<String>()

    private val comicAdapter = ComicAdapter(
        onClick = { item ->
            when (item) {
                is DisplayComicItem.Local -> openComic(item.comic)
                is DisplayComicItem.Online -> openRemoteComic(item.remote)
                is DisplayComicItem.Section -> item.target?.let(::selectBrowseTarget)
                is DisplayComicItem.PageAction -> requestNextOnlinePage()
            }
        },
        onLongClick = { item ->
            when (item) {
                is DisplayComicItem.Local -> showComicQuickMenu(item.comic)
                is DisplayComicItem.Online -> showRemoteQuickMenu(item.remote)
                else -> Unit
            }
        },
        loadLocalCover = { key, iv -> loadLocalCoverAsync(key, iv) },
        loadOnlineCover = { url, headers, iv -> loadOnlineCoverAsync(url, headers, iv) }
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (savedInstanceState != null) {
            currentNavMode = savedInstanceState.getInt(STATE_NAV_MODE, NAV_HOME)
            activeHomeFilterTag = savedInstanceState.getString(STATE_HOME_FILTER, "")
            browseTarget = BundleCompat.getSerializable(savedInstanceState, STATE_BROWSE_TARGET, SourceBrowseTarget::class.java)
        }

        warehousePanel = MainWarehousePanel(
            activity = this,
            binding = binding.panelWarehouse,
            onComicsChanged = {
                if (currentNavMode == NAV_HOME || currentNavMode == NAV_BOOKSHELF) reloadComics()
            },
            onScanAllRequested = { scanAllWarehouses() }
        )
        settingsPanel = MainSettingsPanel(
            activity = this,
            binding = binding.panelSettings,
            onGridPrefsChanged = { applyGridLayoutManager() },
            onOpenWarehouse = { switchToNavMode(NAV_WAREHOUSE) }
        )

        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (currentNavMode == NAV_WAREHOUSE) switchToNavMode(NAV_SETTINGS)
                else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })
        setupNavigation()
        setupSearchAndFilters()
        setupRecycler()
        setupFab()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_NAV_MODE, currentNavMode)
        outState.putString(STATE_HOME_FILTER, activeHomeFilterTag)
        outState.putSerializable(STATE_BROWSE_TARGET, browseTarget)
    }

    override fun onResume() {
        super.onResume()
        syncNavigationSelection(currentNavMode)
        applyGridLayoutManager()
        applyLibraryMaterial(binding.root, binding.libraryWallpaper, binding.panelComics)
        binding.navRail?.let { rail -> applyFloatingDock(rail, binding.btnRailSettings) }
        relockQuickShelvesIfNeeded()
        applyNavModePanels(currentNavMode)
        when (currentNavMode) {
            NAV_HOME -> {
                browseMenu = null
                rebuildHomeChips()
                reloadComics()
            }
            NAV_BOOKSHELF -> {
                rebuildCollectionChips()
                reloadComics()
            }
            NAV_FAVORITES -> reloadComics()
            NAV_WAREHOUSE -> warehousePanel.refresh()
            NAV_SETTINGS -> settingsPanel.refresh()
        }
    }

    private fun selectedMenuIdForMode(mode: Int): Int = when (mode) {
        NAV_BOOKSHELF -> R.id.menu_bookshelf
        NAV_FAVORITES -> R.id.menu_online_favorites
        NAV_WAREHOUSE -> R.id.menu_settings
        NAV_SETTINGS -> R.id.menu_settings
        else -> R.id.menu_home
    }

    private fun syncNavigationSelection(mode: Int) {
        val selected = selectedMenuIdForMode(mode)
        val settings = selected == R.id.menu_settings
        binding.navRail?.setItemActiveIndicatorEnabled(!settings)
        if (!settings) binding.navRail?.menu?.findItem(selected)?.isChecked = true
        binding.btnRailSettings?.isSelected = settings
        binding.bottomNav?.menu?.findItem(selected)?.isChecked = true
    }

    private fun applyNavModePanels(mode: Int) {
        val showComics = mode == NAV_HOME || mode == NAV_BOOKSHELF || mode == NAV_FAVORITES
        binding.chipScroll.visibility = if (mode == NAV_FAVORITES) View.GONE else View.VISIBLE
        binding.tvSectionTitle.visibility = if (mode == NAV_FAVORITES) View.VISIBLE else View.GONE
        binding.panelComics.visibility = if (showComics) View.VISIBLE else View.GONE
        binding.panelWarehouse.root.visibility = if (mode == NAV_WAREHOUSE) View.VISIBLE else View.GONE
        binding.panelSettings.root.visibility = if (mode == NAV_SETTINGS) View.VISIBLE else View.GONE
        binding.fabContinue.visibility = if (showComics) View.VISIBLE else View.GONE
    }

    private fun switchToNavMode(mode: Int) {
        val changed = mode != currentNavMode
        if (changed) clearBrowseQuery()
        currentNavMode = mode
        syncNavigationSelection(mode)
        sourceLoadJob?.cancel()
        categoryJob?.cancel()
        searchDebounceJob?.cancel()
        onlineLoading = false
        applyNavModePanels(mode)
        when (mode) {
            NAV_HOME -> {
                binding.chipScroll.visibility = View.VISIBLE
                binding.chipGroupHome.visibility = View.VISIBLE
                rebuildHomeChips()
                reloadComics()
            }
            NAV_BOOKSHELF -> {
                binding.chipScroll.visibility = View.VISIBLE
                binding.chipGroupHome.visibility = View.VISIBLE
                rebuildCollectionChips()
                reloadComics()
            }
            NAV_FAVORITES -> reloadComics()
            NAV_WAREHOUSE -> warehousePanel.refresh()
            NAV_SETTINGS -> settingsPanel.refresh()
        }
        if (changed && android.animation.ValueAnimator.areAnimatorsEnabled()) {
            val panel = when (mode) {
                NAV_SETTINGS -> binding.panelSettings.root
                NAV_WAREHOUSE -> binding.panelWarehouse.root
                else -> binding.panelComics
            }
            panel.animate().cancel()
            panel.alpha = 0.86f
            panel.translationY = 7.dp.toFloat()
            panel.animate().alpha(1f).translationY(0f).setDuration(180)
                .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        }
    }

    private fun setupNavigation() {
        val navListener: (Int) -> Boolean = { itemId ->
            when (itemId) {
                R.id.menu_home -> {
                    switchToNavMode(NAV_HOME)
                    true
                }
                R.id.menu_bookshelf -> {
                    switchToNavMode(NAV_BOOKSHELF)
                    true
                }
                R.id.menu_online_favorites -> {
                    switchToNavMode(NAV_FAVORITES)
                    true
                }
                R.id.menu_settings -> {
                    switchToNavMode(NAV_SETTINGS)
                    true
                }
                else -> false
            }
        }
        binding.navRail?.menu?.removeItem(R.id.menu_settings)
        binding.btnRailSettings?.setOnClickListener { switchToNavMode(NAV_SETTINGS) }
        binding.navRail?.setOnItemSelectedListener { navListener(it.itemId) }
        binding.navRail?.setOnItemReselectedListener {
            if (currentNavMode == NAV_SETTINGS || currentNavMode == NAV_WAREHOUSE) navListener(it.itemId)
        }
        binding.bottomNav?.setOnItemSelectedListener { navListener(it.itemId) }
    }

    private fun rebuildHomeChips() {
        val enabledSources = app.db.getComicSources(onlyEnabled = true)
        if (activeHomeFilterTag.isEmpty() ||
            (activeHomeFilterTag.startsWith("src:") && enabledSources.none { "src:${it.sourceKey}" == activeHomeFilterTag })
        ) {
            val previous = enabledSources.find { it.sourceKey == app.prefs.lastSource }
            activeHomeFilterTag = (previous ?: enabledSources.firstOrNull())?.let { "src:${it.sourceKey}" } ?: TAG_LOCAL_ALL
            browseMenu = null
            browseTarget = null
            filterTarget = null
        }

        val group = binding.chipGroupHome
        group.setOnCheckedStateChangeListener(null)
        group.removeAllViews()

        fun addFilterChip(tag: String, label: String): Chip {
            val chip = Chip(this, null, com.google.android.material.R.attr.chipStyle).apply {
                id = View.generateViewId()
                text = label
                ensureAccessibleTouchTarget(48.dp)
                isCheckable = true
                isCheckedIconVisible = false
                applyGlassChip()
                this.tag = tag
                isChecked = tag == activeHomeFilterTag
            }
            group.addView(chip)
            return chip
        }

        val last = enabledSources.find { it.sourceKey == activeHomeFilterTag.removePrefix("src:") }
            ?: enabledSources.find { it.sourceKey == app.prefs.lastSource }
            ?: enabledSources.firstOrNull()
        if (last != null) {
            val activeTag = "src:${last.sourceKey}"
            addFilterChip(activeTag, last.name).setOnClickListener { selectHomeFilter(activeTag) }
            val picker = Chip(this).apply {
                text = "切换源"
                applyGlassChip()
                ensureAccessibleTouchTarget(48.dp)
                setOnClickListener { showQuickSourcePicker(enabledSources) }
            }
            group.addView(picker)
        }
        if (activeHomeFilterTag == TAG_LOCAL_HISTORY) addFilterChip(TAG_LOCAL_HISTORY, "最近阅读")
        val hasContext = group.childCount != 0
        binding.chipScroll.visibility = if (hasContext) View.VISIBLE else View.GONE
        binding.tvSectionTitle.visibility = if (hasContext) View.GONE else View.VISIBLE
    }

    private fun selectHomeFilter(tag: String) {
        if (activeHomeFilterTag == tag) return
        if (tag.startsWith("src:") || activeHomeFilterTag.startsWith("src:")) clearBrowseQuery()
        activeHomeFilterTag = tag
        if (tag.startsWith("src:")) app.prefs.lastSource = tag.removePrefix("src:")
        categoryJob?.cancel()
        browseMenu = null
        browseTarget = null
        filterTarget = null
        rebuildHomeChips()
        reloadComics()
    }

    private fun showQuickSourcePicker(sources: List<com.arkreader.comic.data.ComicSourceEntity>) {
        val dp = resources.displayMetrics.density
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding((16 * dp).toInt(), (8 * dp).toInt(), (16 * dp).toInt(), 0)
        }
        val etSearch = EditText(this).apply {
            hint = "搜索已启用漫画源（共 ${sources.size} 个）"
            isSingleLine = true
        }
        val ordered = sources.sortedByDescending { "src:${it.sourceKey}" == activeHomeFilterTag }
        val filtered = ordered.toMutableList()
        val listView = android.widget.ListView(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                (resources.configuration.screenHeightDp * 0.42f * dp).toInt()
            )
        }
        fun renderList(): android.widget.ArrayAdapter<String> {
            val labels = filtered.map {
                if ("src:${it.sourceKey}" == activeHomeFilterTag) "${it.name}（当前）"
                else "${it.name} · ${it.sourceKey}"
            }
            return android.widget.ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
        }
        listView.adapter = renderList()
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val q = s?.toString()?.trim()?.lowercase().orEmpty()
                filtered.clear()
                if (q.isEmpty()) {
                    filtered.addAll(ordered)
                } else {
                    filtered.addAll(ordered.filter {
                        it.name.lowercase().contains(q) || it.sourceKey.lowercase().contains(q)
                    })
                }
                listView.adapter = renderList()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        box.addView(etSearch)
        box.addView(listView)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("快速切换漫画源 (${sources.size})")
            .setView(box)
            .setNegativeButton("取消", null)
            .show()

        listView.setOnItemClickListener { _, _, position, _ ->
            val chosen = filtered.getOrNull(position) ?: return@setOnItemClickListener
            dialog.dismiss()
            selectHomeFilter("src:${chosen.sourceKey}")
        }
    }

    private fun rebuildCollectionChips() {
        val shelves = app.db.getBookshelves()
        val currentId = app.prefs.activeBookshelfId
        val activeShelf = shelves.find {
            it.id == currentId && (it.lockType == LockType.NONE || unlockedShelfIds.contains(it.id))
        }
        if (activeShelf == null) app.prefs.activeBookshelfId = 0L

        val group = binding.chipGroupHome
        group.setOnCheckedStateChangeListener(null)
        group.removeAllViews()
        val all = Chip(this).apply {
            id = View.generateViewId()
            text = "全部本地"
            isCheckable = true
            isChecked = activeShelf == null
            tag = 0L
        }
        group.addView(all)
        shelves.forEach { shelf ->
            val lockMark = if (shelf.lockType != LockType.NONE && !unlockedShelfIds.contains(shelf.id)) " [锁]" else ""
            val chip = Chip(this, null, com.google.android.material.R.attr.chipStyle).apply {
                id = View.generateViewId()
                text = shelf.name + lockMark
                isCheckable = true
                isCheckedIconVisible = false
                tag = shelf.id
                isChecked = shelf.id == activeShelf?.id
            }
            group.addView(chip)
        }

        val manageChip = Chip(this, null, com.google.android.material.R.attr.chipStyle).apply {
            id = View.generateViewId()
            text = "+ 管理作品集"
            isCheckable = false
            setOnClickListener {
                startActivity(Intent(this@MainActivity, BookshelfManageActivity::class.java))
            }
        }
        group.addView(manageChip)

        group.setOnCheckedStateChangeListener { grp, checkedIds ->
            val checkedId = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            val selectedChip = grp.findViewById<Chip>(checkedId) ?: return@setOnCheckedStateChangeListener
            val shelfId = selectedChip.tag as? Long ?: return@setOnCheckedStateChangeListener
            if (shelfId == 0L) {
                app.prefs.activeBookshelfId = 0L
                reloadComics()
                return@setOnCheckedStateChangeListener
            }
            val chosen = app.db.getBookshelf(shelfId) ?: return@setOnCheckedStateChangeListener
            selectBookshelf(chosen)
        }
    }

    private fun setupSearchAndFilters() {
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val next = s?.toString()?.trim().orEmpty()
                if (next == currentQuery) return
                currentQuery = next
                searchDebounceJob?.cancel()
                if (currentNavMode == NAV_HOME && activeHomeFilterTag.startsWith("src:")) {
                    searchDebounceJob = lifecycleScope.launch {
                        delay(420)
                        reloadComics()
                    }
                } else {
                    reloadComics()
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                searchDebounceJob?.cancel()
                reloadComics()
                true
            } else false
        }

        binding.btnFilterSort.setOnClickListener { showSortAndGridDialog() }
        binding.btnMore.setOnClickListener { anchor -> showMoreMenu(anchor) }
        binding.btnOnlineRetry.setOnClickListener { reloadComics() }
        binding.browseControls.btnCategories.setOnClickListener { showBrowseCategoryGroups() }
        binding.browseControls.btnSourceFilters.setOnClickListener { showBrowseFilters() }
    }

    private fun setupRecycler() {
        applyGridLayoutManager()
        binding.rvComics.adapter = comicAdapter
        binding.rvComics.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (currentNavMode != NAV_HOME || !activeHomeFilterTag.startsWith("src:")) return
                if (!onlineHasNext || onlineLoading || onlinePageFailed || searchDebounceJob?.isActive == true) return

                val lm = recyclerView.layoutManager ?: return
                val lastVisible = when (lm) {
                    is GridLayoutManager -> lm.findLastVisibleItemPosition()
                    is LinearLayoutManager -> lm.findLastVisibleItemPosition()
                    else -> return
                }
                if (lastVisible >= comicAdapter.itemCount - 6) {
                    recyclerView.post { if (!onlinePageFailed) requestNextOnlinePage() }
                }
            }
        })
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
            binding.rvComics.layoutManager = GridLayoutManager(this, span).apply {
                spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                    override fun getSpanSize(position: Int): Int =
                        if (comicAdapter.isFullWidth(position)) span else 1
                }
            }
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
        searchDebounceJob?.cancel()
        sourceLoadJob?.cancel()
        onlineLoading = false
        binding.btnOnlineRetry.visibility = View.GONE
        if (currentNavMode != NAV_HOME && currentNavMode != NAV_BOOKSHELF && currentNavMode != NAV_FAVORITES) return
        if (currentNavMode == NAV_HOME && activeHomeFilterTag.startsWith("src:")) {
            val sourceKey = activeHomeFilterTag.removePrefix("src:")
            loadOnlineSourcePage(sourceKey, reset = true)
            return
        }
        renderBrowseControls(null, null, false)

        val activeShelfId = if (currentNavMode == NAV_HOME) 0L else app.prefs.activeBookshelfId
        val localTabMode = when (currentNavMode) {
            NAV_FAVORITES -> 3
            NAV_BOOKSHELF -> 4
            else -> when (activeHomeFilterTag) {
                TAG_LOCAL_FAV -> 1
                TAG_LOCAL_HISTORY -> 2
                else -> 0
            }
        }
        if (currentNavMode == NAV_FAVORITES) {
            binding.tvSectionTitle.text = "在线收藏"
            binding.etSearch.hint = "搜索在线收藏"
        } else if (currentNavMode == NAV_HOME) {
            binding.tvSectionTitle.text = when (localTabMode) {
                1 -> "收藏"
                2 -> "最近"
                else -> "本地全部"
            }
        } else {
            val shelf = app.db.getBookshelf(activeShelfId)
            binding.tvSectionTitle.text = shelf?.name ?: "本地书架"
        }

        val list = if (currentNavMode == NAV_BOOKSHELF && activeShelfId > 0L) {
            val shelf = app.db.getBookshelf(activeShelfId)
            if (shelf != null && shelf.lockType != LockType.NONE && !unlockedShelfIds.contains(shelf.id)) {
                emptyList()
            } else {
                app.db.getComicsForHome(0, activeShelfId, unlockedShelfIds, currentQuery, app.prefs.sortType)
            }
        } else {
            app.db.getComicsForHome(localTabMode, 0L, unlockedShelfIds, currentQuery, app.prefs.sortType)
        }

        comicAdapter.submitList(list.map { DisplayComicItem.Local(it) })
        if (list.isEmpty()) {
            binding.tvEmpty.visibility = View.VISIBLE
            binding.tvEmpty.text = when {
                currentNavMode == NAV_BOOKSHELF -> "书架暂无漫画。可长按漫画封面或在详情页管理书架。"
                localTabMode == 1 || localTabMode == 3 -> "暂无在线收藏。可长按封面或在详情页收藏。"
                localTabMode == 2 -> "暂无阅读记录"
                app.db.getWarehouses().isEmpty() -> "尚未添加本地仓库，可在「仓库」中添加本地目录或 WebDAV"
                else -> "当前分类暂无漫画"
            }
        } else {
            binding.tvEmpty.visibility = View.GONE
        }
    }

    private fun renderBrowseControls(menu: SourceBrowseMenu?, target: SourceBrowseTarget?, canFilter: Boolean) {
        val online = currentNavMode == NAV_HOME && activeHomeFilterTag.startsWith("src:")
        val canSearch = !online || menu?.canSearch == true
        binding.etSearch.isEnabled = canSearch
        binding.etSearch.hint = when {
            !online -> getString(R.string.search_hint)
            menu == null -> "正在读取漫画源"
            canSearch -> "搜索当前漫画源"
            else -> "当前源不支持搜索"
        }
        val controls = binding.browseControls
        val channels = if (menu == null) emptyList() else menu.channels + listOfNotNull(menu.ranking)
        controls.channelTabs.removeAllViews()
        channels.forEach { channel ->
            controls.channelTabs.addView(Chip(this).apply {
                text = channel.title
                isCheckable = true
                isChecked = channel == target
                applyGlassChip()
                isCheckedIconVisible = false
                ensureAccessibleTouchTarget(48.dp)
                setOnClickListener { selectBrowseTarget(channel) }
            })
        }
        controls.btnCategories.visibility = if (!menu?.groups.isNullOrEmpty()) View.VISIBLE else View.GONE
        controls.btnCategories.text = if (target?.kind == "category") target.title else "分类"
        controls.btnCategories.contentDescription = "选择分类"
        controls.btnSourceFilters.visibility = if (canFilter) View.VISIBLE else View.GONE
        controls.root.visibility = if (channels.isNotEmpty() || canFilter || !menu?.groups.isNullOrEmpty()) View.VISIBLE else View.GONE
    }

    private fun clearBrowseQuery() {
        searchDebounceJob?.cancel()
        currentQuery = ""
        binding.etSearch.text.clear()
    }

    private fun selectBrowseTarget(target: SourceBrowseTarget) {
        categoryJob?.cancel()
        clearBrowseQuery()
        browseTarget = target
        reloadComics()
    }

    private fun showBrowseCategoryGroups() {
        val groups = browseMenu?.groups.orEmpty()
        if (groups.size == 1) {
            openBrowseCategoryGroup(groups.single())
        } else if (groups.isNotEmpty()) {
            MaterialAlertDialogBuilder(this)
                .setTitle("分类")
                .setItems(groups.map { it.title }.toTypedArray()) { _, index -> openBrowseCategoryGroup(groups[index]) }
                .setNegativeButton("关闭", null)
                .show()
        }
    }

    private fun openBrowseCategoryGroup(group: SourceCategoryGroup) {
        categoryJob?.cancel()
        val sourceKey = activeHomeFilterTag.removePrefix("src:")
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(group.title)
            .setMessage("正在加载分类…")
            .setNegativeButton("关闭", null)
            .setPositiveButton("重试") { _, _ -> openBrowseCategoryGroup(group) }
            .show()
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).visibility = View.GONE
        categoryJob = lifecycleScope.launch {
            val targets = try {
                app.veneraRuntime.getBrowseCategories(sourceKey, group.index)
            } catch (error: Exception) {
                if (error is CancellationException && error !is TimeoutCancellationException) throw error
                dialog.setMessage("加载分类失败\n${browseError(error)}")
                dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).visibility = View.VISIBLE
                return@launch
            }
            if (!dialog.isShowing || !coroutineContext.isActive) return@launch
            dialog.dismiss()
            val builder = MaterialAlertDialogBuilder(this@MainActivity).setTitle(group.title).setNegativeButton("关闭", null)
            if (targets.isEmpty()) builder.setMessage("这个分组暂无可用分类")
            else builder.setSingleChoiceItems(targets.map { it.title }.toTypedArray(), targets.indexOf(filterTarget)) { picker, index ->
                picker.dismiss()
                selectBrowseTarget(targets[index])
            }
            builder.show()
        }
    }

    private fun showBrowseFilters() {
        val filters = browseFilters ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle("筛选")
            .setItems(filters.fields.map { field ->
                val value = filters.values.getOrNull(field.index)
                val selected = selectedFilterValues(field, value)
                val labels = field.choices.filter { it.value in selected }.joinToString("、") { it.title }
                if (labels.isBlank()) field.title else "${field.title} · $labels"
            }.toTypedArray()) { _, index ->
                showBrowseFilterField(filters, filters.fields[index])
            }
            .setNeutralButton("恢复默认") { _, _ ->
                browseFilters = null
                reloadComics()
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun selectedFilterValues(field: SourceFilterField, value: String?): Set<String?> {
        if (!field.multiple) return setOf(value)
        return runCatching {
            val array = JSONArray(value ?: "[]")
            (0 until array.length()).map { array.getString(it) }.toSet()
        }.getOrDefault(emptySet())
    }

    private fun showBrowseFilterField(filters: SourceBrowseFilters, field: SourceFilterField) {
        val selected = selectedFilterValues(field, filters.values.getOrNull(field.index)).toMutableSet()
        val labels = field.choices.map { it.title }.toTypedArray()
        val dialog = MaterialAlertDialogBuilder(this).setTitle(field.title).setNegativeButton("取消", null)
        fun apply(value: String?) {
            browseFilters = filters.copy(values = filters.values.toMutableList().apply { set(field.index, value) })
            reloadComics()
        }
        if (field.multiple) {
            val checks = field.choices.map { it.value in selected }.toBooleanArray()
            dialog.setMultiChoiceItems(labels, checks) { _, index, checked ->
                val value = field.choices[index].value
                if (checked) selected.add(value) else selected.remove(value)
            }.setPositiveButton("应用") { _, _ -> apply(JSONArray(selected.toList()).toString()) }
        } else {
            dialog.setSingleChoiceItems(labels, field.choices.indexOfFirst { it.value in selected }) { picker, index ->
                picker.dismiss()
                apply(field.choices[index].value)
            }
        }
        dialog.show()
    }

    private fun requestNextOnlinePage() {
        if (currentNavMode == NAV_HOME && activeHomeFilterTag.startsWith("src:") &&
            onlineHasNext && !onlineLoading && searchDebounceJob?.isActive != true) {
            loadOnlineSourcePage(activeHomeFilterTag.removePrefix("src:"), reset = false)
        }
    }

    private fun showBrowseStatus(message: String, retry: Boolean = false) {
        binding.tvEmpty.text = message
        binding.tvEmpty.visibility = View.VISIBLE
        binding.btnOnlineRetry.visibility = if (retry) View.VISIBLE else View.GONE
    }

    private fun browseError(error: Exception): String =
        if (error is TimeoutCancellationException) "请求超时"
        else error.message.orEmpty().lineSequence().firstOrNull().orEmpty().take(160).ifBlank { "请求失败" }

    private fun renderOnlineItems(action: String? = null, enabled: Boolean = true) {
        val items = if (action != null) onlineItems + DisplayComicItem.PageAction(action, enabled) else onlineItems
        comicAdapter.submitList(items)
    }

    private fun loadOnlineSourcePage(sourceKey: String, reset: Boolean) {
        val sourceName = app.db.getComicSource(sourceKey)?.name ?: sourceKey
        val query = currentQuery
        val page = if (reset) 1 else onlinePage + 1
        if (reset) {
            onlineItems.clear()
            ungroupedComicIds.clear()
            onlinePage = 0
            onlineCursor = null
            onlineHasNext = false
            onlinePageFailed = false
            binding.rvComics.scrollToPosition(0)
            binding.tvSectionTitle.text = sourceName

            showBrowseStatus("正在加载…")
            renderOnlineItems()
            renderBrowseControls(browseMenu, null, false)
        } else renderOnlineItems("正在加载…", enabled = false)

        onlineLoading = true
        sourceLoadJob = lifecycleScope.launch {
            try {
                val runtime = app.veneraRuntime
                val menu = browseMenu ?: runtime.getBrowseMenu(sourceKey)
                if (currentQuery != query) return@launch
                browseMenu = menu
                val target = when {
                    query.isNotEmpty() && menu.canSearch -> SourceBrowseTarget("search", "搜索结果", keyword = query)
                    else -> browseTarget ?: menu.channels.firstOrNull() ?: menu.ranking
                }
                renderBrowseControls(menu, target, false)
                if (target == null) {
                    showBrowseStatus(when {
                        menu.groups.isNotEmpty() -> "选择一个分类开始浏览"
                        menu.canSearch -> "输入关键词搜索此漫画源"
                        else -> "此漫画源暂无可用的浏览或搜索入口"
                    })
                    return@launch
                }
                binding.tvSectionTitle.text = target.title.ifBlank { sourceName }
                if (filterTarget != target) browseFilters = null
                filterTarget = target
                val filters = browseFilters ?: runtime.getBrowseFilters(sourceKey, target)
                if (currentQuery != query) return@launch
                browseFilters = filters
                renderBrowseControls(menu, target, filters.fields.isNotEmpty())

                val result = runtime.loadBrowsePage(sourceKey, target, filters.values, page, onlineCursor)
                if (currentQuery != query) return@launch
                val previousCount = onlineItems.size
                for (section in result.sections) {
                    val grouped = section.title.isNotBlank() || section.target != null
                    if (grouped) onlineItems.add(DisplayComicItem.Section(section.title, section.target))
                    section.comics.filter { grouped || ungroupedComicIds.add(it.id) }
                        .forEach { onlineItems.add(DisplayComicItem.Online(it)) }
                }
                onlinePage = page
                onlineCursor = result.nextCursor
                onlineHasNext = result.hasNext(page) && (onlineItems.size > previousCount || page == 1)
                onlinePageFailed = false

                binding.tvEmpty.visibility = View.GONE
                if (onlineItems.isEmpty() && !onlineHasNext) {
                    showBrowseStatus(if (target.kind == "search") "没有找到匹配的漫画" else "这个栏目暂无漫画")
                }
                renderOnlineItems()
            } catch (error: Exception) {
                if (error is CancellationException && error !is TimeoutCancellationException) throw error
                if (currentQuery != query) return@launch
                if (reset) {
                    showBrowseStatus("加载失败\n${browseError(error)}", retry = true)
                } else {
                    onlinePageFailed = true
                    renderOnlineItems("加载失败，重试")
                }
            } finally {
                if (coroutineContext.isActive) {
                    onlineLoading = false
                    binding.rvComics.post {
                        if (onlineHasNext && !onlinePageFailed && !binding.rvComics.canScrollVertically(1)) requestNextOnlinePage()
                    }
                }
            }
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
            .setTitle("选择作品集")
            .setSingleChoiceItems(names, currentIdx) { dialog, which ->
                val chosen = shelves[which]
                dialog.dismiss()
                selectBookshelf(chosen)
            }
            .setPositiveButton("管理作品集") { _, _ ->
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
            hint = "输入作品集密码"
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("解锁作品集「${shelf.name}」")
            .setView(input)
            .setPositiveButton("确定") { _, _ ->
                val entered = input.text?.toString().orEmpty()
                if (DatabaseHelper.hashPin(entered) == shelf.pinHash) {
                    unlockedShelfIds.add(shelf.id)
                    shelfPassTimes[shelf.id] = System.currentTimeMillis()
                    app.prefs.activeBookshelfId = shelf.id
                    rebuildCollectionChips()
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
                        rebuildCollectionChips()
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
        popup.menu.add("刷新当前内容").setOnMenuItemClickListener { reloadComics(); true }
        if (currentNavMode == NAV_HOME) {
            popup.menu.add("最近阅读").setOnMenuItemClickListener { selectHomeFilter(TAG_LOCAL_HISTORY); true }
            if (currentQuery.isNotEmpty()) popup.menu.add("清除搜索").setOnMenuItemClickListener { clearBrowseQuery(); reloadComics(); true }
            browseMenu?.website?.takeIf { it.isNotBlank() }?.let { url ->
                val title = if (url == browseMenu?.appWebsite) "官网 / 官方 App" else "官方网站"
                popup.menu.add(title).setOnMenuItemClickListener { openComicWebsite(url); true }
            }
            browseMenu?.appWebsite?.takeIf { it.isNotBlank() && it != browseMenu?.website }?.let { url ->
                popup.menu.add("官方 App").setOnMenuItemClickListener { openComicWebsite(url); true }
            }
        }
        if (currentNavMode == NAV_BOOKSHELF) {
            popup.menu.add("管理本地书架").setOnMenuItemClickListener {
                startActivity(Intent(this, BookshelfManageActivity::class.java)); true
            }
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
            if (currentNavMode == NAV_WAREHOUSE) warehousePanel.refresh() else reloadComics()
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

    private fun openRemoteComic(remote: RemoteComicItem) {
        startActivity(Intent(this, ComicDetailActivity::class.java).apply {
            putExtra(ComicDetailActivity.EXTRA_SOURCE_KEY, remote.sourceKey)
            putExtra(ComicDetailActivity.EXTRA_REMOTE_ID, remote.id)
            putExtra(ComicDetailActivity.EXTRA_REMOTE_TITLE, remote.title)
            putExtra(ComicDetailActivity.EXTRA_REMOTE_COVER, remote.cover)
            putExtra(ComicDetailActivity.EXTRA_REMOTE_SUB, remote.subTitle)
            putExtra(ComicDetailActivity.EXTRA_REMOTE_DESCRIPTION, remote.description)
        })
    }

    private fun openComicReader(comic: Comic) {
        val chapters = app.db.getChapters(comic.id)
        if (chapters.isEmpty() && comic.format == ComicFormat.ONLINE) {
            startActivity(Intent(this, ComicDetailActivity::class.java).apply {
                putExtra(ComicDetailActivity.EXTRA_COMIC_ID, comic.id)
            })
            return
        }
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
        val items = if (comic.format == ComicFormat.ONLINE) arrayOf("继续阅读", "查看详情", favLabel)
            else arrayOf("继续阅读", "查看详情", favLabel, "管理本地书架")
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

    private fun showRemoteQuickMenu(remote: RemoteComicItem) {
        val favorite = app.db.getOnlineComic(remote.sourceKey, remote.id)?.isFavorite == true
        val items = arrayOf("查看详情", if (favorite) "取消在线收藏" else "加入在线收藏")
        MaterialAlertDialogBuilder(this)
            .setTitle(remote.title)
            .setItems(items) { _, idx ->
                if (idx == 0) {
                    openRemoteComic(remote)
                    return@setItems
                }
                val coverKey = if (remote.cover.isBlank()) "" else app.coverCache.makeKey("url:${remote.cover}")
                val saved = app.db.saveOnlinePreview(remote.sourceKey, remote.id, remote.title, coverKey)
                val next = !favorite
                app.db.setFavorite(saved.id, next)
                app.db.saveOnlineMetadata(saved.id, remote.description)
                Toast.makeText(this, if (next) "已加入在线收藏" else "已取消在线收藏", Toast.LENGTH_SHORT).show()
                if (remote.cover.isNotBlank()) lifecycleScope.launch(Dispatchers.IO) {
                    app.coverCache.getOrDownloadHttpCover(remote.cover, remote.headers)
                }
            }
            .show()
    }

    private fun showAssignBookshelfDialog(comic: Comic) {
        showComicBookshelves(comic.id) {
            if (currentNavMode == NAV_BOOKSHELF) reloadComics()
        }
    }

    private fun loadLocalCoverAsync(cacheKey: String, imageView: ImageView) {
        imageView.tag = "local:$cacheKey"
        imageView.showCoverLoading(cacheKey.isNotBlank())
        if (cacheKey.isBlank()) return
        lifecycleScope.launch {
            val bmp: Bitmap? = withContext(Dispatchers.IO) {
                app.coverCache.getCover(cacheKey)
            }
            if (imageView.tag == "local:$cacheKey") imageView.finishCover(bmp)
        }
    }

    private fun loadOnlineCoverAsync(url: String, headers: Map<String, String>, imageView: ImageView) {
        imageView.tag = "url:$url"
        imageView.showCoverLoading(url.isNotBlank())
        if (url.isBlank()) return
        lifecycleScope.launch {
            val (_, bmp) = withContext(Dispatchers.IO) {
                app.coverCache.getOrDownloadHttpCover(url, headers)
            }
            if (imageView.tag == "url:$url") imageView.finishCover(bmp)
        }
    }

    companion object {
        private const val NAV_HOME = 0
        private const val NAV_BOOKSHELF = 1
        private const val NAV_WAREHOUSE = 2
        private const val NAV_SETTINGS = 3
        private const val NAV_FAVORITES = 4
        private const val STATE_NAV_MODE = "state_nav_mode"
        private const val STATE_HOME_FILTER = "state_home_filter"
        private const val STATE_BROWSE_TARGET = "state_browse_target"
        private const val TAG_LOCAL_ALL = "local:all"
        private const val TAG_LOCAL_FAV = "local:fav"
        private const val TAG_LOCAL_HISTORY = "local:history"
    }
}

private class ComicAdapter(
    private val onClick: (DisplayComicItem) -> Unit,
    private val onLongClick: (DisplayComicItem) -> Unit,
    private val loadLocalCover: (String, ImageView) -> Unit,
    private val loadOnlineCover: (String, Map<String, String>, ImageView) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val items = mutableListOf<DisplayComicItem>()
    private var isListMode = false
    private var doubleLineTitle = false
    private var onlyShowCover = false
    private val dateFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

    fun submitList(newItems: List<DisplayComicItem>) {
        var shared = 0
        while (shared < items.size && shared < newItems.size && items[shared] == newItems[shared]) shared++
        val removed = items.size - shared
        val added = newItems.size - shared
        if (removed > 0) {
            items.subList(shared, items.size).clear()
            notifyItemRangeRemoved(shared, removed)
        }
        if (added > 0) {
            items.addAll(newItems.subList(shared, newItems.size))
            notifyItemRangeInserted(shared, added)
        }
    }

    fun updateDisplayPrefs(grid: GridSize, doubleLine: Boolean, onlyCover: Boolean) {
        val nextList = grid == GridSize.LIST
        val changed = nextList != isListMode || doubleLine != doubleLineTitle || onlyCover != onlyShowCover
        isListMode = nextList
        doubleLineTitle = doubleLine
        onlyShowCover = onlyCover
        if (changed) notifyDataSetChanged()
    }

    fun isFullWidth(position: Int): Boolean =
        items.getOrNull(position).let { it is DisplayComicItem.Section || it is DisplayComicItem.PageAction }

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is DisplayComicItem.Section -> 2
        is DisplayComicItem.PageAction -> 3
        else -> if (isListMode) 1 else 0
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val context = parent.context
        val touchSize = (48 * context.resources.displayMetrics.density).toInt()
        val inset = (8 * context.resources.displayMetrics.density).toInt()
        return when (viewType) {
            1 -> ListVH(ItemComicListBinding.inflate(inflater, parent, false))
            2 -> {
                val row = LinearLayout(context).apply {
                    layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = touchSize
                    setPaddingRelative(inset, inset, inset, 0)
                }
                val title = TextView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
                    ellipsize = TextUtils.TruncateAt.END
                    maxLines = 2
                    isAccessibilityHeading = true
                }
                val more = MaterialButton(context, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
                    text = "查看更多"
                    minHeight = touchSize
                }
                row.addView(title)
                row.addView(more)
                SectionVH(row, title, more)
            }
            3 -> PageActionVH(MaterialButton(context, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                minHeight = touchSize
            })
            else -> GridVH(ItemComicCardBinding.inflate(inflater, parent, false))
        }
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val wrapped = items[position]
        if (holder is SectionVH && wrapped is DisplayComicItem.Section) {
            holder.title.text = wrapped.title
            holder.more.visibility = if (wrapped.target != null) View.VISIBLE else View.GONE
            holder.more.contentDescription = if (wrapped.title.isBlank()) "查看更多" else "查看更多：${wrapped.title}"
            holder.more.setOnClickListener { onClick(wrapped) }
        } else if (holder is PageActionVH && wrapped is DisplayComicItem.PageAction) {
            holder.button.text = wrapped.text
            holder.button.isEnabled = wrapped.enabled
            holder.button.setOnClickListener { onClick(wrapped) }
        } else if (holder is GridVH) {
            val b = holder.binding
            b.tvTitle.visibility = if (onlyShowCover) View.GONE else View.VISIBLE
            b.tvTitle.maxLines = if (doubleLineTitle) 2 else 1
            when (wrapped) {
                is DisplayComicItem.Local -> {
                    val item = wrapped.comic
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
                    loadLocalCover(item.coverCacheKey, b.ivCover)
                }
                is DisplayComicItem.Online -> {
                    val remote = wrapped.remote
                    b.tvTitle.text = remote.title
                    b.tvBadge.visibility = View.GONE
                    loadOnlineCover(remote.cover, remote.headers, b.ivCover)
                }
                else -> return
            }
            b.root.setOnClickListener { onClick(wrapped) }
            b.root.setOnLongClickListener { onLongClick(wrapped); true }
        } else if (holder is ListVH) {
            val b = holder.binding
            when (wrapped) {
                is DisplayComicItem.Local -> {
                    val item = wrapped.comic
                    b.tvTitle.text = item.title
                    val readInfo = if (item.lastReadAt > 0L) "最近: ${dateFormat.format(Date(item.lastReadAt))}" else "未读"
                    b.tvSub.text = "${item.format.name} · ${item.totalChapters} 章节 · $readInfo"
                    loadLocalCover(item.coverCacheKey, b.ivCover)
                }
                is DisplayComicItem.Online -> {
                    val remote = wrapped.remote
                    b.tvTitle.text = remote.title
                    b.tvSub.text = listOfNotNull(
                        remote.subTitle.takeIf { it.isNotEmpty() },
                        remote.tags.take(3).joinToString(" / ").takeIf { it.isNotEmpty() },
                        remote.description.take(40).takeIf { it.isNotEmpty() }
                    ).joinToString(" · ")
                    loadOnlineCover(remote.cover, remote.headers, b.ivCover)
                }
                else -> return
            }
            b.root.setOnClickListener { onClick(wrapped) }
            b.root.setOnLongClickListener { onLongClick(wrapped); true }
        }
    }

    class GridVH(val binding: ItemComicCardBinding) : RecyclerView.ViewHolder(binding.root)
    class ListVH(val binding: ItemComicListBinding) : RecyclerView.ViewHolder(binding.root)
    class SectionVH(row: View, val title: TextView, val more: MaterialButton) : RecyclerView.ViewHolder(row)
    class PageActionVH(val button: MaterialButton) : RecyclerView.ViewHolder(button)
}
