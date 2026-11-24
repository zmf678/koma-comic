package com.arkreader.comic.ui

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
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
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.os.BundleCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
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
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
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
import java.util.WeakHashMap

sealed class DisplayComicItem {
    data class Local(val comic: Comic) : DisplayComicItem()
    data class Online(val remote: RemoteComicItem) : DisplayComicItem()
    data class Section(val title: String, val target: SourceBrowseTarget?) : DisplayComicItem()
    data class PageAction(val text: String, val enabled: Boolean) : DisplayComicItem()
}

internal data class SourceFeedSnapshot(
    val query: String,
    val menu: SourceBrowseMenu,
    val target: SourceBrowseTarget?,
    val filterTarget: SourceBrowseTarget?,
    val filters: SourceBrowseFilters?,
    val items: List<DisplayComicItem>,
    val ungroupedIds: Set<String>,
    val page: Int,
    val cursor: String?,
    val hasNext: Boolean,
    val failedNextPage: Boolean,
    val firstVisible: Int,
    val offset: Int
)

class SourceFeedMemory : ViewModel() {
    internal val sources = object : LinkedHashMap<String, SourceFeedSnapshot>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SourceFeedSnapshot>?) = size > 6
    }
}

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val app get() = ArkReaderApp.instance
    private val Int.dp get() = (this * resources.displayMetrics.density + 0.5f).toInt()

    private var currentNavMode = NAV_HOME // 0=Home, 1=Collections, 2=Warehouse, 3=Settings, 4=Favorites
    private var activeHomeFilterTag = ""
    private val unlockedShelfIds = mutableSetOf<Long>()
    private val shelfPassTimes = mutableMapOf<Long, Long>()
    private var lockedShelfPending: Bookshelf? = null
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
    private val categoryGroupCache = mutableMapOf<String, List<SourceBrowseTarget>>()
    private val feedMemory by lazy { ViewModelProvider(this)[SourceFeedMemory::class.java] }
    private var loadedSourceKey: String? = null
    private var displayedSourceKey: String? = null
    private var loadedQuery = ""
    private var onlinePage = 0
    private var onlineCursor: String? = null
    private var onlineHasNext = false
    private var onlineLoading = false
    private var onlinePageFailed = false
    private val onlineItems = mutableListOf<DisplayComicItem>()
    private val ungroupedComicIds = mutableSetOf<String>()

    // Track in-flight cover jobs per ImageView to cancel cleanly on fast scroll / recycle
    private val coverJobs = WeakHashMap<ImageView, Job>()

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
        loadLocalCover = { key, iv, onReady -> loadLocalCoverAsync(key, iv, onReady) },
        loadOnlineCover = { url, headers, iv, onReady -> loadOnlineCoverAsync(url, headers, iv, onReady) },
        cancelCover = { iv -> coverJobs.remove(iv)?.cancel() }
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
        binding.profileButton.setOnClickListener { startActivity(Intent(this, ProfileActivity::class.java)) }
        setupFloatingHeaderInsets()
        setupNavigation()
        setupSearchAndFilters()
        setupRecycler()
        setupFab()
    }

    private fun setupFloatingHeaderInsets() {
        val isPhone = binding.bottomNav != null
        val padH = if (isPhone) 10.dp else 14.dp
        val padBottom = if (isPhone) 104.dp else 88.dp
        binding.floatingHeader.addOnLayoutChangeListener { v, _, top, _, bottom, _, oldTop, _, oldBottom ->
            val h = bottom - top
            if (h > 0 && h != (oldBottom - oldTop) || binding.rvComics.paddingTop != h + 4.dp) {
                binding.rvComics.setPadding(padH, h + 4.dp, padH, padBottom)
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_NAV_MODE, currentNavMode)
        outState.putString(STATE_HOME_FILTER, activeHomeFilterTag)
        outState.putSerializable(STATE_BROWSE_TARGET, browseTarget)
        rememberSourceFeed()
    }

    override fun onPause() {
        rememberSourceFeed()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        refreshAdapterMetadataCache()
        syncNavigationSelection(currentNavMode)
        applyGridLayoutManager()
        applyLibraryMaterial(
            binding.root,
            binding.libraryWallpaper,
            binding.panelComics,
            binding.panelWarehouse.root,
            binding.panelSettings.root
        )
        binding.navRail?.let { rail -> applyFloatingDock(rail, binding.btnRailSettings, binding.libraryWallpaper) }
        applyMainGlassChrome(
            binding.libraryWallpaper,
            binding.searchPill,
            binding.browseBar,
            binding.bottomNav,
            binding.profileButton
        )
        binding.btnTopRefresh.applyGlassActionPill(binding.libraryWallpaper, 18f)
        binding.btnTopGrid.applyGlassActionPill(binding.libraryWallpaper, 18f)
        // Call showProfileAvatar AFTER applyMainGlassChrome so circular avatar sits cleanly inside glass ring
        binding.profileButton.showProfileAvatar()

        relockQuickShelvesIfNeeded()
        applyNavModePanels(currentNavMode)
        when (currentNavMode) {
            NAV_HOME -> {
                rebuildHomeChips()
                showHomeFeed()
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

    private fun refreshAdapterMetadataCache() {
        val favOnline = app.db.getComicsForHome(3, 0L, unlockedShelfIds, "", SortType.NAME_ASC)
            .mapTo(HashSet()) { it.sourceUri }
        val srcNames = app.db.getComicSources(onlyEnabled = false).associate { it.sourceKey to it.name }
        comicAdapter.updateMetadataCache(favOnline, srcNames, hideFavBadge = currentNavMode == NAV_FAVORITES)
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
        (binding.btnRailSettings?.background as? LiquidGlassDrawable)?.setSelectedGlow(settings)
        binding.bottomNav?.menu?.findItem(selected)?.isChecked = true
    }

    private fun applyNavModePanels(mode: Int) {
        val showComics = mode == NAV_HOME || mode == NAV_BOOKSHELF || mode == NAV_FAVORITES
        binding.localShelfHeader.visibility = if (mode == NAV_BOOKSHELF) View.VISIBLE else View.GONE
        binding.chipScroll.visibility = if (mode == NAV_FAVORITES) View.GONE else View.VISIBLE
        binding.tvSectionTitle.visibility = if (mode == NAV_FAVORITES) View.VISIBLE else View.GONE
        binding.panelComics.visibility = if (showComics) View.VISIBLE else View.GONE
        binding.panelWarehouse.root.visibility = if (mode == NAV_WAREHOUSE) View.VISIBLE else View.GONE
        binding.panelSettings.root.visibility = if (mode == NAV_SETTINGS) View.VISIBLE else View.GONE
        binding.fabContinue.visibility = if (showComics) View.VISIBLE else View.GONE
        comicAdapter.setHideFavBadge(mode == NAV_FAVORITES)
    }

    private fun switchToNavMode(mode: Int) {
        val changed = mode != currentNavMode
        if (changed) {
            rememberSourceFeed()
            clearBrowseQuery()
        } else if (mode == NAV_HOME && activeHomeFilterTag.startsWith("src:")) {
            return
        }
        currentNavMode = mode
        lockedShelfPending = null
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
                showHomeFeed()
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
            panel.alpha = 0.88f
            panel.translationY = 6.dp.toFloat()
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

        fun addFilterChip(tag: String, label: String, checkable: Boolean = true): Chip {
            val chip = Chip(this, null, com.google.android.material.R.attr.chipStyle).apply {
                id = View.generateViewId()
                text = label
                ensureAccessibleTouchTarget(40.dp)
                isCheckable = checkable
                isCheckedIconVisible = false
                applyGlassChip()
                this.tag = tag
                isChecked = checkable && tag == activeHomeFilterTag
            }
            group.addView(chip)
            return chip
        }

        val last = enabledSources.find { it.sourceKey == activeHomeFilterTag.removePrefix("src:") }
            ?: enabledSources.find { it.sourceKey == app.prefs.lastSource }
            ?: enabledSources.firstOrNull()
        if (activeHomeFilterTag == TAG_LOCAL_HISTORY) {
            addFilterChip(TAG_LOCAL_HISTORY, "最近阅读")
            if (last != null) {
                addFilterChip("back:${last.sourceKey}", "返回 ${last.name}", checkable = false)
                    .setOnClickListener { selectHomeFilter("src:${last.sourceKey}") }
            }
        } else if (last != null) {
            val activeTag = "src:${last.sourceKey}"
            addFilterChip(activeTag, "${last.name} ▾").setOnClickListener { showQuickSourcePicker() }
        }
        val hasContext = group.childCount != 0
        binding.chipScroll.visibility = if (hasContext) View.VISIBLE else View.GONE
        binding.tvSectionTitle.visibility = if (hasContext) View.GONE else View.VISIBLE
    }

    private fun selectHomeFilter(tag: String) {
        if (activeHomeFilterTag == tag) return
        rememberSourceFeed()
        if (tag.startsWith("src:") || activeHomeFilterTag.startsWith("src:")) clearBrowseQuery()
        activeHomeFilterTag = tag
        if (tag.startsWith("src:")) app.prefs.lastSource = tag.removePrefix("src:")
        sourceLoadJob?.cancel()
        onlineLoading = false
        categoryJob?.cancel()
        browseMenu = null
        browseTarget = null
        filterTarget = null
        rebuildHomeChips()
        showHomeFeed()
    }

    /**
     * Quick source switcher synced with Windows PC: shows all sources (including disabled ones
     * labeled "(已停用)" that auto-enable on pick), version tags, search filter, and Source Manager shortcut.
     */
    private fun showQuickSourcePicker() {
        val allSources = app.db.getComicSources(onlyEnabled = false)
        val dp = resources.displayMetrics.density
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * dp).toInt(), (8 * dp).toInt(), (16 * dp).toInt(), 0)
        }
        val etSearch = EditText(this).apply {
            hint = "搜索漫画源（共 ${allSources.size} 个）"
            isSingleLine = true
        }
        val ordered = allSources.sortedWith(
            compareByDescending<com.arkreader.comic.data.ComicSourceEntity> { "src:${it.sourceKey}" == activeHomeFilterTag }
                .thenByDescending { it.enabled }
                .thenBy { it.name.lowercase() }
        )
        val filtered = ordered.toMutableList()
        val listView = android.widget.ListView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (resources.configuration.screenHeightDp * 0.44f * dp).toInt()
            )
        }
        fun renderList(): android.widget.ArrayAdapter<String> {
            val labels = filtered.map { src ->
                val ver = src.version.takeIf { it.isNotBlank() }?.let { " v$it" }.orEmpty()
                val status = when {
                    "src:${src.sourceKey}" == activeHomeFilterTag -> "（当前）"
                    !src.enabled -> "（已停用 · 点击启用）"
                    else -> ""
                }
                "${src.name}$ver$status"
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
            .setTitle("切换漫画源 (${allSources.size})")
            .setView(box)
            .setNeutralButton("源管理") { _, _ ->
                startActivity(Intent(this, SourceManageActivity::class.java))
            }
            .setNegativeButton("取消", null)
            .show()

        listView.setOnItemClickListener { _, _, position, _ ->
            val chosen = filtered.getOrNull(position) ?: return@setOnItemClickListener
            dialog.dismiss()
            if (!chosen.enabled) {
                app.db.setComicSourceEnabled(chosen.sourceKey, true)
                Toast.makeText(this, "已自动启用「${chosen.name}」", Toast.LENGTH_SHORT).show()
            }
            selectHomeFilter("src:${chosen.sourceKey}")
        }
    }

    private fun rebuildCollectionChips() {
        val shelves = app.db.getBookshelves()
        val currentId = app.prefs.activeBookshelfId
        val activeShelf = shelves.find { it.id == currentId }
        if (activeShelf == null && currentId != 0L) app.prefs.activeBookshelfId = 0L

        val group = binding.chipGroupHome
        group.setOnCheckedStateChangeListener(null)
        group.removeAllViews()
        val allCount = app.db.getComicsForHome(4, 0L, unlockedShelfIds, "", SortType.NAME_ASC).size
        val all = Chip(this, null, com.google.android.material.R.attr.chipStyle).apply {
            id = View.generateViewId()
            text = "全部本地 ($allCount)"
            isCheckable = true
            isCheckedIconVisible = false
            isChecked = activeShelf == null
            applyGlassChip()
            tag = 0L
        }
        group.addView(all)
        shelves.forEach { shelf ->
            val locked = shelf.lockType != LockType.NONE && !unlockedShelfIds.contains(shelf.id)
            val count = if (locked) null else app.db.getComicsForHome(0, shelf.id, unlockedShelfIds, "", SortType.NAME_ASC).size
            val lockPrefix = if (locked) "🔒 " else ""
            val countSuffix = if (count != null) " ($count)" else ""
            val chip = Chip(this, null, com.google.android.material.R.attr.chipStyle).apply {
                id = View.generateViewId()
                text = "$lockPrefix${shelf.name}$countSuffix"
                isCheckable = true
                isCheckedIconVisible = false
                applyGlassChip()
                tag = shelf.id
                isChecked = shelf.id == activeShelf?.id
            }
            group.addView(chip)
        }

        fun addBookshelfActionChip(label: String, onClick: () -> Unit) {
            group.addView(Chip(this, null, com.google.android.material.R.attr.chipStyle).apply {
                id = View.generateViewId()
                text = label
                isCheckable = false
                isCheckedIconVisible = false
                applyGlassChip()
                setOnClickListener { onClick() }
            })
        }
        addBookshelfActionChip("+ 添加本地文件夹") { warehousePanel.pickLocalFolder() }
        addBookshelfActionChip("+ 导入压缩包") { warehousePanel.importLocalArchives() }
        addBookshelfActionChip("↻ 重新扫描") { scanAllWarehouses() }
        addBookshelfActionChip("+ 管理作品集") {
            startActivity(Intent(this@MainActivity, BookshelfManageActivity::class.java))
        }

        group.setOnCheckedStateChangeListener { grp, checkedIds ->
            val checkedId = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            val selectedChip = grp.findViewById<Chip>(checkedId) ?: return@setOnCheckedStateChangeListener
            val shelfId = selectedChip.tag as? Long ?: return@setOnCheckedStateChangeListener
            if (shelfId == 0L) {
                lockedShelfPending = null
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
        binding.btnTopRefresh.setOnClickListener {
            if (currentNavMode == NAV_BOOKSHELF) {
                scanAllWarehouses()
            } else {
                reloadComics()
                Toast.makeText(this, "已刷新", Toast.LENGTH_SHORT).show()
            }
        }
        binding.btnTopGrid.setOnClickListener {
            val values = GridSize.values()
            val next = values[(values.indexOf(app.prefs.gridSize) + 1) % values.size]
            app.prefs.gridSize = next
            applyGridLayoutManager()
            Toast.makeText(this, "视图：${next.label}", Toast.LENGTH_SHORT).show()
        }
        binding.btnTopGrid.setOnLongClickListener {
            showGridSizeDialog()
            true
        }
        binding.btnMore.setOnClickListener { anchor -> showMoreMenu(anchor) }
        binding.btnOnlineRetry.setOnClickListener { reloadComics() }
        binding.btnShelfUnlock.setOnClickListener {
            lockedShelfPending?.let { shelf ->
                when (shelf.lockType) {
                    LockType.PIN -> showPinUnlockDialog(shelf)
                    LockType.BIOMETRIC -> showBiometricUnlock(shelf)
                    LockType.NONE -> reloadComics()
                }
            }
        }
        binding.browseControls.btnCategories.setOnClickListener { showUnifiedCategoryDialog() }
        binding.browseControls.btnSourceFilters.setOnClickListener { showBrowseFilters() }
    }

    private fun setupRecycler() {
        applyGridLayoutManager()
        binding.rvComics.adapter = comicAdapter
        binding.rvComics.setHasFixedSize(true)
        binding.rvComics.setItemViewCacheSize(20)
        (binding.rvComics.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        binding.rvComics.attachLiquidGlassScrollbar()
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

    private fun rememberSourceFeed() {
        if (currentNavMode != NAV_HOME || !activeHomeFilterTag.startsWith("src:")) return
        val key = activeHomeFilterTag.removePrefix("src:")
        val menu = browseMenu ?: return
        if (loadedSourceKey != key || loadedQuery != currentQuery || onlinePage <= 0) return
        val manager = binding.rvComics.layoutManager as? LinearLayoutManager ?: return
        val first = manager.findFirstVisibleItemPosition().coerceAtLeast(0)
        val offset = manager.findViewByPosition(first)?.let { manager.getDecoratedTop(it) - binding.rvComics.paddingTop } ?: 0
        feedMemory.sources[key] = SourceFeedSnapshot(
            currentQuery, menu, browseTarget, filterTarget, browseFilters, onlineItems.toList(),
            ungroupedComicIds.toSet(), onlinePage, onlineCursor, onlineHasNext, onlinePageFailed, first, offset
        )
    }

    private fun showHomeFeed() {
        if (!activeHomeFilterTag.startsWith("src:")) {
            reloadComics()
            return
        }
        val key = activeHomeFilterTag.removePrefix("src:")
        if (loadedSourceKey == key && loadedQuery == currentQuery &&
            displayedSourceKey == key && (onlinePage > 0 || sourceLoadJob?.isActive == true)) return
        val cached = feedMemory.sources[key]
        if (cached == null) {
            reloadComics()
            return
        }
        sourceLoadJob?.cancel()
        searchDebounceJob?.cancel()
        currentQuery = cached.query
        if (binding.etSearch.text.toString() != cached.query) binding.etSearch.setText(cached.query)
        browseMenu = cached.menu
        browseTarget = cached.target
        filterTarget = cached.filterTarget
        browseFilters = cached.filters
        onlineItems.clear()
        onlineItems.addAll(cached.items)
        ungroupedComicIds.clear()
        ungroupedComicIds.addAll(cached.ungroupedIds)
        loadedSourceKey = key
        loadedQuery = cached.query
        onlinePage = cached.page
        onlineCursor = cached.cursor
        onlineHasNext = cached.hasNext
        onlinePageFailed = cached.failedNextPage
        onlineLoading = false
        binding.btnOnlineRetry.visibility = View.GONE
        binding.btnShelfUnlock.visibility = View.GONE
        binding.tvEmpty.visibility = View.GONE
        if (cached.items.isEmpty() && !cached.hasNext) {
            showBrowseStatus(if (cached.query.isEmpty()) "这个栏目暂无漫画" else "没有找到匹配的漫画")
        }
        renderBrowseControls(cached.menu, cached.filterTarget, !cached.filters?.fields.isNullOrEmpty())
        renderOnlineItems(if (cached.failedNextPage) "加载失败，重试" else null)
        (binding.rvComics.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(cached.firstVisible, cached.offset)
    }

    private fun reloadComics() {
        searchDebounceJob?.cancel()
        sourceLoadJob?.cancel()
        onlineLoading = false
        binding.btnOnlineRetry.visibility = View.GONE
        binding.btnShelfUnlock.visibility = View.GONE
        refreshAdapterMetadataCache()
        if (currentNavMode != NAV_HOME && currentNavMode != NAV_BOOKSHELF && currentNavMode != NAV_FAVORITES) return
        if (currentNavMode == NAV_HOME && activeHomeFilterTag.startsWith("src:")) {
            val sourceKey = activeHomeFilterTag.removePrefix("src:")
            loadOnlineSourcePage(sourceKey, reset = true)
            return
        }
        displayedSourceKey = null
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
            binding.tvSectionTitle.text = "★ 在线收藏"
            binding.etSearch.hint = "搜索在线收藏"
        } else if (currentNavMode == NAV_HOME) {
            binding.tvSectionTitle.text = when (localTabMode) {
                1 -> "收藏"
                2 -> "最近阅读"
                else -> "本地全部"
            }
        } else {
            val shelf = app.db.getBookshelf(activeShelfId)
            binding.tvSectionTitle.text = shelf?.name ?: "本地书架"
        }

        if (currentNavMode == NAV_BOOKSHELF && activeShelfId > 0L) {
            val shelf = app.db.getBookshelf(activeShelfId)
            if (shelf != null && shelf.lockType != LockType.NONE && !unlockedShelfIds.contains(shelf.id)) {
                lockedShelfPending = shelf
                comicAdapter.submitList(emptyList())
                binding.tvEmpty.visibility = View.VISIBLE
                binding.tvEmpty.text = "🔒 作品集「${shelf.name}」已设定隐私锁"
                binding.btnShelfUnlock.visibility = View.VISIBLE
                binding.btnShelfUnlock.text = if (shelf.lockType == LockType.BIOMETRIC) "验证生物识别解锁" else "输入 PIN 解锁"
                return
            }
        }
        lockedShelfPending = null

        val list = if (currentNavMode == NAV_BOOKSHELF && activeShelfId > 0L) {
            app.db.getComicsForHome(0, activeShelfId, unlockedShelfIds, currentQuery, app.prefs.sortType)
        } else {
            app.db.getComicsForHome(localTabMode, 0L, unlockedShelfIds, currentQuery, app.prefs.sortType)
        }

        comicAdapter.submitList(list.map { DisplayComicItem.Local(it) })
        if (list.isEmpty()) {
            binding.tvEmpty.visibility = View.VISIBLE
            binding.tvEmpty.text = when {
                currentNavMode == NAV_BOOKSHELF -> "书架暂无漫画。可点击上方「添加本地文件夹」或「导入压缩包」开始阅读。"
                localTabMode == 1 || localTabMode == 3 -> "暂无在线收藏。可长按封面或在详情页收藏。"
                localTabMode == 2 -> "暂无阅读记录"
                app.db.getWarehouses().isEmpty() -> "尚未添加本地仓库，可在「设置 → 本地仓库」中添加本地目录或 WebDAV"
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
            currentNavMode == NAV_FAVORITES -> "搜索在线收藏"
            !online -> getString(R.string.search_hint)
            menu == null -> "正在读取漫画源"
            canSearch -> "搜索当前漫画源"
            else -> "当前源不支持搜索"
        }
        val controls = binding.browseControls
        val channels = if (menu == null) emptyList() else menu.channels + listOfNotNull(menu.ranking)
        val activeSourceName = app.db.getComicSource(activeHomeFilterTag.removePrefix("src:"))?.name.orEmpty()
        val singleDuplicate = channels.size == 1 && channels[0].title.equals(activeSourceName, ignoreCase = true)
        controls.channelTabs.removeAllViews()

        fun makeChannelChip(label: String, checked: Boolean, checkable: Boolean, onTap: () -> Unit): Chip {
            return Chip(this, null, com.google.android.material.R.attr.chipStyle).apply {
                text = label
                isCheckable = checkable
                isChecked = checked
                isCheckedIconVisible = false
                applyGlassChip()
                ensureAccessibleTouchTarget(40.dp)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = 6.dp }
                setOnClickListener { onTap() }
            }
        }

        if (singleDuplicate) {
            if (target?.kind == "category" || target?.kind == "search") {
                controls.channelTabs.addView(makeChannelChip("首页推荐", false, false) {
                    selectBrowseTarget(channels[0])
                })
            }
        } else {
            channels.forEach { channel ->
                controls.channelTabs.addView(makeChannelChip(channel.title, channel == target, true) {
                    selectBrowseTarget(channel)
                })
            }
        }

        // Active dismissible category / search pill synced with Windows PC
        if (target?.kind == "category") {
            controls.channelTabs.addView(makeChannelChip("分类：${target.title}  ✕", true, true) {
                browseTarget = null
                reloadComics()
            })
        } else if (target?.kind == "search" && currentQuery.isNotBlank()) {
            controls.channelTabs.addView(makeChannelChip("搜索：$currentQuery  ✕", true, true) {
                clearBrowseQuery()
                reloadComics()
            })
        }

        controls.btnCategories.visibility = if (!menu?.groups.isNullOrEmpty()) View.VISIBLE else View.GONE
        controls.btnCategories.text = if (target?.kind == "category") "切换分类 ▾" else "分类 ▾"
        controls.btnCategories.contentDescription = "选择分类"
        controls.btnSourceFilters.visibility = if (canFilter) View.VISIBLE else View.GONE
        val hasBrowseContent = controls.channelTabs.childCount > 0 || canFilter || !menu?.groups.isNullOrEmpty()
        controls.root.visibility = if (hasBrowseContent) View.VISIBLE else View.GONE
        binding.browseDivider.visibility = if (online && hasBrowseContent) View.VISIBLE else View.GONE
    }

    private fun clearBrowseQuery() {
        searchDebounceJob?.cancel()
        currentQuery = ""
        if (binding.etSearch.text.isNotEmpty()) binding.etSearch.text.clear()
    }

    private fun selectBrowseTarget(target: SourceBrowseTarget) {
        categoryJob?.cancel()
        clearBrowseQuery()
        browseTarget = target
        reloadComics()
    }

    /**
     * Unified Category Browser Dialog synced with Windows PC:
     * Group tabs at top (with memory cache) + live keyword search + wrapping pill tag grid.
     */
    private fun showUnifiedCategoryDialog() {
        val groups = browseMenu?.groups.orEmpty()
        if (groups.isEmpty()) return
        val sourceKey = activeHomeFilterTag.removePrefix("src:")
        val dp = resources.displayMetrics.density

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * dp).toInt(), (8 * dp).toInt(), (16 * dp).toInt(), (4 * dp).toInt())
        }

        val groupStrip = ChipGroup(this).apply {
            isSingleSelection = true
            isSingleLine = true
        }
        val groupScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            visibility = if (groups.size > 1) View.VISIBLE else View.GONE
            addView(groupStrip)
        }
        root.addView(groupScroll)

        val etFilter = EditText(this).apply {
            hint = "输入关键词快速筛选分类…"
            isSingleLine = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (6 * dp).toInt() }
        }
        root.addView(etFilter)

        val statusView = TextView(this).apply {
            textSize = 13f
            setTextColor(getColor(R.color.text_secondary))
            setPadding(0, (12 * dp).toInt(), 0, (6 * dp).toInt())
            visibility = View.GONE
        }
        root.addView(statusView)

        val tagGroup = ChipGroup(this).apply {
            isSingleLine = false
            chipSpacingHorizontal = (6 * dp).toInt()
            chipSpacingVertical = (4 * dp).toInt()
        }
        val tagScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (resources.configuration.screenHeightDp * 0.44f * dp).toInt()
            ).apply { topMargin = (8 * dp).toInt() }
            addView(tagGroup)
        }
        root.addView(tagScroll)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (groups.size == 1) groups.first().title else "分类浏览")
            .setView(root)
            .setNegativeButton("关闭") { _, _ -> categoryJob?.cancel() }
            .setOnCancelListener { categoryJob?.cancel() }
            .show()

        var currentTargets: List<SourceBrowseTarget> = emptyList()
        var currentGroup: SourceCategoryGroup = groups.first()

        fun renderTags() {
            tagGroup.removeAllViews()
            val q = etFilter.text?.toString()?.trim()?.lowercase().orEmpty()
            val visible = (if (q.isEmpty()) currentTargets else currentTargets.filter {
                it.title.lowercase().contains(q)
            }).take(300)
            if (visible.isEmpty()) {
                statusView.visibility = View.VISIBLE
                statusView.text = if (q.isEmpty()) "这个分组暂无可用分类" else "未找到包含「$q」的分类"
                return
            }
            statusView.visibility = View.GONE
            visible.forEach { target ->
                val chip = Chip(this, null, com.google.android.material.R.attr.chipStyle).apply {
                    text = target.title
                    isCheckable = true
                    isChecked = target == filterTarget
                    isCheckedIconVisible = false
                    applyGlassChip()
                    setOnClickListener {
                        dialog.dismiss()
                        selectBrowseTarget(target)
                    }
                }
                tagGroup.addView(chip)
            }
        }

        fun loadGroup(group: SourceCategoryGroup) {
            currentGroup = group
            categoryJob?.cancel()
            val cacheKey = "$sourceKey#${group.index}"
            categoryGroupCache[cacheKey]?.let { cached ->
                currentTargets = cached
                renderTags()
                return
            }
            tagGroup.removeAllViews()
            statusView.visibility = View.VISIBLE
            statusView.text = "正在加载「${group.title}」…"
            categoryJob = lifecycleScope.launch {
                val targets = try {
                    app.veneraRuntime.getBrowseCategories(sourceKey, group.index)
                } catch (error: Exception) {
                    if (error is CancellationException && error !is TimeoutCancellationException) throw error
                    if (dialog.isShowing) {
                        statusView.visibility = View.VISIBLE
                        statusView.text = "加载失败：${browseError(error)}（点击上方分组重试）"
                    }
                    return@launch
                }
                if (!dialog.isShowing || !coroutineContext.isActive) return@launch
                categoryGroupCache[cacheKey] = targets
                if (currentGroup == group) {
                    currentTargets = targets
                    renderTags()
                }
            }
        }

        groups.forEachIndexed { idx, group ->
            val tabChip = Chip(this, null, com.google.android.material.R.attr.chipStyle).apply {
                id = View.generateViewId()
                text = group.title
                isCheckable = true
                isChecked = idx == 0
                isCheckedIconVisible = false
                applyGlassChip()
                setOnClickListener { loadGroup(group) }
            }
            groupStrip.addView(tabChip)
        }

        etFilter.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { renderTags() }
            override fun afterTextChanged(s: Editable?) {}
        })

        loadGroup(groups.first())
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
        displayedSourceKey = loadedSourceKey
        val items = if (action != null) onlineItems + DisplayComicItem.PageAction(action, enabled) else onlineItems
        comicAdapter.submitList(items)
    }

    private fun loadOnlineSourcePage(sourceKey: String, reset: Boolean) {
        val sourceName = app.db.getComicSource(sourceKey)?.name ?: sourceKey
        val query = currentQuery
        val page = if (reset) 1 else onlinePage + 1
        if (reset) {
            feedMemory.sources.remove(sourceKey)
            loadedSourceKey = sourceKey
            loadedQuery = query
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

    private fun selectBookshelf(shelf: Bookshelf) {
        app.prefs.activeBookshelfId = shelf.id
        if (shelf.lockType == LockType.NONE || unlockedShelfIds.contains(shelf.id)) {
            lockedShelfPending = null
            reloadComics()
            return
        }
        // Show inline locked state immediately and prompt unlock
        reloadComics()
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
                    lockedShelfPending = null
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
                        lockedShelfPending = null
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
            popup.menu.add("源管理").setOnMenuItemClickListener {
                startActivity(Intent(this, SourceManageActivity::class.java)); true
            }
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
            if (currentNavMode == NAV_WAREHOUSE) warehousePanel.refresh()
            else {
                if (currentNavMode == NAV_BOOKSHELF) rebuildCollectionChips()
                reloadComics()
            }
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
            putStringArrayListExtra(ComicDetailActivity.EXTRA_REMOTE_TAGS, ArrayList(remote.tags))
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
                app.db.saveOnlineMetadata(saved.id, remote.description, tags = remote.tags, onlyMissing = true)
                refreshAdapterMetadataCache()
                comicAdapter.notifyDataSetChanged()
                Toast.makeText(this, if (next) "已加入在线收藏" else "已取消在线收藏", Toast.LENGTH_SHORT).show()
                if (remote.cover.isNotBlank()) lifecycleScope.launch(Dispatchers.IO) {
                    app.coverCache.getOrDownloadHttpCover(remote.cover, remote.headers)
                }
            }
            .show()
    }

    private fun showAssignBookshelfDialog(comic: Comic) {
        showComicBookshelves(comic.id) {
            if (currentNavMode == NAV_BOOKSHELF) {
                rebuildCollectionChips()
                reloadComics()
            }
        }
    }

    private fun loadLocalCoverAsync(cacheKey: String, imageView: ImageView, onReady: ((Bitmap?) -> Unit)? = null) {
        coverJobs.remove(imageView)?.cancel()
        val tagVal = "local:$cacheKey"
        imageView.tag = tagVal
        if (cacheKey.isBlank()) {
            imageView.showCoverLoading(false)
            onReady?.invoke(null)
            return
        }
        // Fast path: memory cache hit -> zero coroutine, zero flash, zero jank!
        app.coverCache.getMemoryCover(cacheKey)?.let { cached ->
            imageView.animate().cancel()
            imageView.alpha = 1f
            imageView.setImageBitmap(cached)
            onReady?.invoke(cached)
            return
        }
        imageView.showCoverLoading(true)
        coverJobs[imageView] = lifecycleScope.launch {
            val bmp: Bitmap? = withContext(Dispatchers.IO) {
                app.coverCache.getCover(cacheKey)
            }
            if (imageView.tag == tagVal) imageView.finishCover(bmp, onReady)
        }
    }

    private fun loadOnlineCoverAsync(
        url: String,
        headers: Map<String, String>,
        imageView: ImageView,
        onReady: ((Bitmap?) -> Unit)? = null
    ) {
        coverJobs.remove(imageView)?.cancel()
        val tagVal = "url:$url"
        imageView.tag = tagVal
        if (url.isBlank()) {
            imageView.showCoverLoading(false)
            onReady?.invoke(null)
            return
        }
        // Fast path: memory cache hit -> zero coroutine, zero flash, zero jank!
        app.coverCache.getMemoryHttpCover(url)?.let { cached ->
            imageView.animate().cancel()
            imageView.alpha = 1f
            imageView.setImageBitmap(cached)
            onReady?.invoke(cached)
            return
        }
        imageView.showCoverLoading(true)
        coverJobs[imageView] = lifecycleScope.launch {
            val (_, bmp) = withContext(Dispatchers.IO) {
                app.coverCache.getOrDownloadHttpCover(url, headers)
            }
            if (imageView.tag == tagVal) imageView.finishCover(bmp, onReady)
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
    private val loadLocalCover: (String, ImageView, ((Bitmap?) -> Unit)?) -> Unit,
    private val loadOnlineCover: (String, Map<String, String>, ImageView, ((Bitmap?) -> Unit)?) -> Unit,
    private val cancelCover: (ImageView) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val items = mutableListOf<DisplayComicItem>()
    private var isListMode = false
    private var doubleLineTitle = false
    private var onlyShowCover = false
    private var favoriteOnlineUris: Set<String> = emptySet()
    private var sourceNames: Map<String, String> = emptyMap()
    private var hideFavBadge = false
    private val dateFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

    fun updateMetadataCache(favOnlineUris: Set<String>, srcNames: Map<String, String>, hideFavBadge: Boolean) {
        this.favoriteOnlineUris = favOnlineUris
        this.sourceNames = srcNames
        this.hideFavBadge = hideFavBadge
    }

    fun setHideFavBadge(hide: Boolean) {
        if (hideFavBadge != hide) {
            hideFavBadge = hide
        }
    }

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

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        super.onViewRecycled(holder)
        when (holder) {
            is GridVH -> cancelCover(holder.binding.ivCover)
            is ListVH -> cancelCover(holder.binding.ivCover)
        }
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
            else -> {
                val b = ItemComicCardBinding.inflate(inflater, parent, false)
                val density = context.resources.displayMetrics.density
                // Pre-create reusable drawables once per ViewHolder (zero allocations during scroll)
                b.viewTopGlint.background = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(0x3EFFFFFF, 0x00FFFFFF)
                )
                b.tvFavBadge.background = GradientDrawable().apply {
                    cornerRadius = 10f * density
                    setColor(0xD7F59E0B.toInt())
                    setStroke((1f * density).toInt().coerceAtLeast(1), 0xB9FFFFFF.toInt())
                }
                b.tvBadge.background = GradientDrawable().apply {
                    cornerRadius = 10f * density
                    setColor(0xAC0E1626.toInt())
                    setStroke((1f * density).toInt().coerceAtLeast(1), 0x82FFFFFF.toInt())
                }
                GridVH(b)
            }
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
            val ctx = b.root.context
            val density = ctx.resources.displayMetrics.density
            val dark = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            val glassEnabled = ArkReaderApp.instance.prefs.glassMaterial

            // Configure showcase card chrome (Glass overlay vs Classic below-cover) only when mode changes
            if (holder.boundGlassMode != glassEnabled) {
                holder.boundGlassMode = glassEnabled
                val lp = b.captionBox.layoutParams as ConstraintLayout.LayoutParams
                if (glassEnabled) {
                    b.root.strokeWidth = (1.2f * density).toInt().coerceAtLeast(1)
                    b.root.strokeColor = if (dark) 0x60FFFFFF else 0xECFFFFFF.toInt()
                    b.root.cardElevation = 3f * density
                    b.viewTopGlint.visibility = View.VISIBLE
                    lp.topToBottom = ConstraintLayout.LayoutParams.UNSET
                    lp.bottomToBottom = R.id.ivCover
                    val m = (6 * density).toInt()
                    lp.setMargins(m, 0, m, m)
                    b.captionBox.setPadding((9 * density).toInt(), (6 * density).toInt(), (9 * density).toInt(), (6 * density).toInt())
                } else {
                    b.root.applyGlassCardStyle(16f)
                    b.viewTopGlint.visibility = View.GONE
                    b.captionBox.detachLiquidGlass()
                    b.captionBox.background = null
                    b.captionBox.elevation = 0f
                    lp.bottomToBottom = ConstraintLayout.LayoutParams.UNSET
                    lp.topToBottom = R.id.ivCover
                    lp.setMargins(0, 0, 0, 0)
                    b.captionBox.setPadding((10 * density).toInt(), (8 * density).toInt(), (10 * density).toInt(), (9 * density).toInt())
                }
                b.captionBox.layoutParams = lp
            }

            b.captionBox.visibility = if (onlyShowCover) View.GONE else View.VISIBLE
            b.tvTitle.maxLines = if (doubleLineTitle || glassEnabled) 2 else 1

            val onCoverReady: ((Bitmap?) -> Unit)? = if (glassEnabled && !onlyShowCover) {
                { bmp -> b.captionBox.applyCoverCaptionLiquidGlass(bmp, 12.5f) }
            } else null

            when (wrapped) {
                is DisplayComicItem.Local -> {
                    val item = wrapped.comic
                    b.tvTitle.text = item.title
                    val sub = when {
                        item.totalChapters > 1 -> "${item.totalChapters} 话"
                        item.format != ComicFormat.ONLINE -> item.format.name
                        else -> ""
                    }
                    b.tvSubTitle.text = sub
                    b.tvSubTitle.visibility = if (sub.isNotBlank()) View.VISIBLE else View.GONE
                    b.tvFavBadge.visibility = if (!hideFavBadge && item.isFavorite) View.VISIBLE else View.GONE
                    val badgeText = when {
                        item.format == ComicFormat.ONLINE -> {
                            val srcKey = item.sourceUri.removePrefix("venera://").substringBefore("/")
                            sourceNames[srcKey] ?: srcKey
                        }
                        item.totalChapters > 1 -> "${item.totalChapters}章"
                        else -> item.format.name
                    }
                    if (badgeText.isNotBlank()) {
                        b.tvBadge.visibility = View.VISIBLE
                        b.tvBadge.text = badgeText
                    } else {
                        b.tvBadge.visibility = View.GONE
                    }
                    loadLocalCover(item.coverCacheKey, b.ivCover, onCoverReady)
                }
                is DisplayComicItem.Online -> {
                    val remote = wrapped.remote
                    b.tvTitle.text = remote.title
                    val sub = remote.subTitle.ifBlank { remote.tags.take(2).joinToString(" · ") }
                    b.tvSubTitle.text = sub
                    b.tvSubTitle.visibility = if (sub.isNotBlank()) View.VISIBLE else View.GONE
                    val isFav = "venera://${remote.sourceKey}/${remote.id}" in favoriteOnlineUris
                    b.tvFavBadge.visibility = if (!hideFavBadge && isFav) View.VISIBLE else View.GONE
                    val srcLabel = sourceNames[remote.sourceKey] ?: remote.sourceKey
                    if (srcLabel.isNotBlank()) {
                        b.tvBadge.visibility = View.VISIBLE
                        b.tvBadge.text = srcLabel
                    } else {
                        b.tvBadge.visibility = View.GONE
                    }
                    loadOnlineCover(remote.cover, remote.headers, b.ivCover, onCoverReady)
                }
                else -> return
            }
            b.root.setOnClickListener { onClick(wrapped) }
            b.root.setOnLongClickListener { onLongClick(wrapped); true }
        } else if (holder is ListVH) {
            val b = holder.binding
            b.root.applyGlassCardStyle(14f)
            when (wrapped) {
                is DisplayComicItem.Local -> {
                    val item = wrapped.comic
                    b.tvTitle.text = item.title
                    val readInfo = if (item.lastReadAt > 0L) "最近: ${dateFormat.format(Date(item.lastReadAt))}" else "未读"
                    b.tvSub.text = "${item.format.name} · ${item.totalChapters} 章节 · $readInfo"
                    loadLocalCover(item.coverCacheKey, b.ivCover, null)
                }
                is DisplayComicItem.Online -> {
                    val remote = wrapped.remote
                    b.tvTitle.text = remote.title
                    b.tvSub.text = listOfNotNull(
                        remote.subTitle.takeIf { it.isNotEmpty() },
                        remote.tags.take(3).joinToString(" / ").takeIf { it.isNotEmpty() },
                        remote.description.take(40).takeIf { it.isNotEmpty() }
                    ).joinToString(" · ")
                    loadOnlineCover(remote.cover, remote.headers, b.ivCover, null)
                }
                else -> return
            }
            b.root.setOnClickListener { onClick(wrapped) }
            b.root.setOnLongClickListener { onLongClick(wrapped); true }
        }
    }

    class GridVH(val binding: ItemComicCardBinding) : RecyclerView.ViewHolder(binding.root) {
        var boundGlassMode: Boolean? = null
    }
    class ListVH(val binding: ItemComicListBinding) : RecyclerView.ViewHolder(binding.root)
    class SectionVH(row: View, val title: TextView, val more: MaterialButton) : RecyclerView.ViewHolder(row)
    class PageActionVH(val button: MaterialButton) : RecyclerView.ViewHolder(button)
}
