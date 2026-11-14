package com.arkreader.comic.ui

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.LruCache
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.arkreader.comic.ArkReaderApp
import com.arkreader.comic.R
import com.arkreader.comic.data.Bookmark
import com.arkreader.comic.data.Chapter
import com.arkreader.comic.data.ClickFlipMode
import com.arkreader.comic.data.Comic
import com.arkreader.comic.data.ComicFormat
import com.arkreader.comic.data.DoublePageMode
import com.arkreader.comic.data.ReaderBgColor
import com.arkreader.comic.data.ReaderConfig
import com.arkreader.comic.data.ReaderOrientation
import com.arkreader.comic.data.ScrollMode
import com.arkreader.comic.databinding.ActivityComicReaderBinding
import com.arkreader.comic.engine.ComicPageSource
import com.arkreader.comic.engine.ImageProcessor
import com.arkreader.comic.engine.PageSlot
import com.arkreader.comic.engine.PageSourceFactory
import com.arkreader.comic.engine.SpreadSlotBuilder
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ComicReaderActivity : AppCompatActivity() {

    private lateinit var binding: ActivityComicReaderBinding
    private val app get() = ArkReaderApp.instance

    private var comicId: Long = 0L
    private var chapterId: Long = 0L
    private var currentComic: Comic? = null
    private var chapters: List<Chapter> = emptyList()
    private var currentChapter: Chapter? = null
    private lateinit var readerConfig: ReaderConfig

    private var pageSource: ComicPageSource? = null
    private var widePageFlags: BooleanArray = BooleanArray(0)
    private var slots: List<PageSlot> = emptyList()
    private var currentSlotIndex: Int = 0
    private var toolbarVisible = false

    private val bitmapCache = object : LruCache<Int, Bitmap>(12) {}
    private val vpCallback = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageSelected(position: Int) {
            onSlotSelected(position)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityComicReaderBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupEdgeToEdgeSystemBars()
        updateBottomCapsuleWidth()

        comicId = intent.getLongExtra(EXTRA_COMIC_ID, 0L)
        chapterId = intent.getLongExtra(EXTRA_CHAPTER_ID, 0L)
        val initialRawPage = intent.getIntExtra(EXTRA_PAGE_INDEX, 0)

        binding.vpHorizontal.registerOnPageChangeCallback(vpCallback)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.readerSettingsOverlay.visibility == View.VISIBLE) {
                    closeReaderSettingsPanel()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
        setupOverlayControls()
        setupReaderSettingsDrawer()
        loadComicAndConfig(initialRawPage)
    }

    @Suppress("DEPRECATION")
    private fun setupEdgeToEdgeSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        val controller = WindowCompat.getInsetsController(window, binding.root)
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false

        val density = resources.displayMetrics.density
        val baseTopPad = (10 * density).toInt()
        val baseBottomPad = (16 * density).toInt()
        val sidePad = (16 * density).toInt()

        ViewCompat.setOnApplyWindowInsetsListener(binding.readerRoot) { _, insets ->
            val sys = insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars())
            binding.topOverlay.setPadding(
                sidePad / 2,
                sys.top + baseTopPad,
                sidePad / 2,
                (16 * density).toInt()
            )
            binding.bottomOverlay.setPadding(
                sidePad,
                0,
                sidePad,
                sys.bottom + baseBottomPad
            )
            insets
        }
        ViewCompat.requestApplyInsets(binding.readerRoot)
    }

    private fun updateBottomCapsuleWidth() {
        val dm = resources.displayMetrics
        val maxPx = (560 * dm.density).toInt()
        val targetW = if (dm.widthPixels > maxPx + (32 * dm.density).toInt()) {
            maxPx + (32 * dm.density).toInt()
        } else {
            ViewGroup.LayoutParams.MATCH_PARENT
        }
        val lp = binding.bottomOverlay.layoutParams as? FrameLayout.LayoutParams ?: return
        if (lp.width != targetW) {
            lp.width = targetW
            lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            binding.bottomOverlay.layoutParams = lp
        }
    }

    override fun onResume() {
        super.onResume()
        currentComic?.let { c ->
            val updated = app.db.getReaderConfig(app.db.getComic(c.id)?.readerConfigId ?: 1L)
            if (this::readerConfig.isInitialized && updated != readerConfig) {
                readerConfig = updated
                applyReaderConfigStyle()
                bitmapCache.evictAll()
                rebuildSlotsAndRender(getCurrentRawPage())
            }
        }
        applySystemBarsForToolbarState(toolbarVisible)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateBottomCapsuleWidth()
        if (this::readerConfig.isInitialized && pageSource != null) {
            val rawPage = getCurrentRawPage()
            bitmapCache.evictAll()
            rebuildSlotsAndRender(rawPage)
        }
    }

    override fun onPause() {
        super.onPause()
        saveCurrentProgress()
    }

    override fun onDestroy() {
        super.onDestroy()
        binding.vpHorizontal.unregisterOnPageChangeCallback(vpCallback)
        pageSource?.close()
        bitmapCache.evictAll()
    }

    private fun setupOverlayControls() {
        binding.btnBack.setOnClickListener { finish() }
        binding.btnReaderMore.setOnClickListener { openReaderSettingsPanel() }
        binding.btnBookmark.setOnClickListener { showBookmarkDialog() }
        binding.btnReaderSettings.setOnClickListener { openReaderSettingsPanel() }
        binding.btnChapterList.setOnClickListener { showChapterPickerDialog() }
        binding.btnPrevChapter.setOnClickListener { switchChapter(-1) }
        binding.btnNextChapter.setOnClickListener { switchChapter(1) }

        binding.btnSpreadMatch.setOnClickListener {
            toggleCrossPageMatch()
        }

        binding.seekPage.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && slots.isNotEmpty()) {
                    binding.tvSeekLabel.text = formatSeekShort(progress)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val target = seekBar?.progress ?: return
                jumpToSlot(target)
            }
        })
    }

    private fun toggleCrossPageMatch() {
        if (!this::readerConfig.isInitialized || pageSource == null) return
        val rawPage = getCurrentRawPage()
        val nextDoubleMode = if (readerConfig.doublePageMode == DoublePageMode.NORMAL) {
            DoublePageMode.AUTO_LANDSCAPE
        } else {
            readerConfig.doublePageMode
        }
        val nextFirstSingle = !readerConfig.firstPageSingle
        readerConfig = readerConfig.copy(
            doublePageMode = nextDoubleMode,
            firstPageSingle = nextFirstSingle
        )
        app.db.upsertReaderConfig(readerConfig)
        bitmapCache.evictAll()
        rebuildSlotsAndRender(rawPage)

        val effective = effectiveDoublePageMode()
        val matchState = if (nextFirstSingle) "首页单页起" else "成对跨页起"
        val tip = if (effective == DoublePageMode.NORMAL) {
            "已切换跨页匹配 ($matchState)，横屏双页时生效"
        } else {
            "已切换跨页匹配 ($matchState)"
        }
        Toast.makeText(this, tip, Toast.LENGTH_SHORT).show()
    }

    private fun effectiveDoublePageMode(): DoublePageMode {
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        return SpreadSlotBuilder.resolveEffectiveMode(readerConfig.doublePageMode, isLandscape)
    }

    private fun updateOverlayActionPills() {
        if (!this::readerConfig.isInitialized) return
        binding.btnSpreadMatch.text = if (readerConfig.firstPageSingle) {
            "更改跨页匹配(单起)"
        } else {
            "更改跨页匹配(对起)"
        }
    }

    private fun loadComicAndConfig(initialRawPage: Int) {
        val c = app.db.getComic(comicId)
        chapters = app.db.getChapters(comicId)
        val ch = chapters.find { it.id == chapterId } ?: chapters.firstOrNull()
        if (c == null || ch == null) {
            Toast.makeText(this, "无法加载漫画章节", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        currentComic = c
        currentChapter = ch
        chapterId = ch.id
        readerConfig = app.db.getReaderConfig(c.readerConfigId)

        binding.tvReaderComicTitle.text = c.title
        binding.tvReaderChapterTitle.text = ch.title
        setToolbarVisible(readerConfig.showToolbarOnOpen)
        applyReaderConfigStyle()
        openChapterSource(ch, initialRawPage)
    }

    private fun applyReaderConfigStyle() {
        binding.readerRoot.setBackgroundColor(readerConfig.bgColor.colorInt)
        if (readerConfig.keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        requestedOrientation = when (readerConfig.orientation) {
            ReaderOrientation.SYSTEM -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            ReaderOrientation.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            ReaderOrientation.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        applyBrightness()
        updatePageIndicatorVisibility()

        val marginPx = (readerConfig.sideMarginDp * resources.displayMetrics.density).toInt()
        binding.rvVertical.setPadding(marginPx, 0, marginPx, 0)
        binding.vpHorizontal.setPadding(marginPx, 0, marginPx, 0)
        updateOverlayActionPills()
        if (binding.readerSettingsOverlay.visibility == View.VISIBLE) {
            syncReaderSettingsPanelState()
        }
    }

    private fun updatePageIndicatorVisibility() {
        val shouldShow = this::readerConfig.isInitialized && readerConfig.showPageNumber && !toolbarVisible
        binding.tvPageIndicator.visibility = if (shouldShow) View.VISIBLE else View.GONE
    }

    private fun openChapterSource(chapter: Chapter, initialRawPage: Int) {
        val wh = currentComic?.let { app.db.getWarehouse(it.warehouseId) }
        binding.progressBar.visibility = View.VISIBLE
        pageSource?.close()
        pageSource = null
        widePageFlags = BooleanArray(0)
        bitmapCache.evictAll()

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val src = PageSourceFactory.open(
                        this@ComicReaderActivity,
                        chapter,
                        wh,
                        app.coverCache,
                        readerConfig.pdfScale
                    )
                    val flags = BooleanArray(src.pageCount)
                    if (chapter.format != ComicFormat.ONLINE && src.pageCount <= 400) {
                        for (i in 0 until src.pageCount) {
                            val size = src.getPageSize(i)
                            if (size != null && size.first > size.second * 1.15f) {
                                flags[i] = true
                            }
                        }
                    }
                    src to flags
                } catch (_: Exception) {
                    null
                }
            }
            binding.progressBar.visibility = View.GONE
            if (result == null || result.first.pageCount <= 0) {
                Toast.makeText(this@ComicReaderActivity, "无法打开章节内容或章节为空", Toast.LENGTH_SHORT).show()
                return@launch
            }
            pageSource = result.first
            widePageFlags = result.second
            app.db.updateChapterPageCount(chapter.id, result.first.pageCount)
            rebuildSlotsAndRender(initialRawPage)
        }
    }

    private fun rebuildSlotsAndRender(targetRawPage: Int) {
        val count = pageSource?.pageCount ?: 0
        if (count <= 0) return

        val effectiveMode = effectiveDoublePageMode()
        slots = SpreadSlotBuilder.buildSlots(
            pageCount = count,
            effectiveMode = effectiveMode,
            firstPageSingle = readerConfig.firstPageSingle,
            mergeRightToLeft = readerConfig.mergeRightToLeft,
            isWidePage = { idx -> idx in widePageFlags.indices && widePageFlags[idx] }
        )

        val slotIndex = SpreadSlotBuilder.findSlotForRawPage(slots, targetRawPage)
        binding.seekPage.max = (slots.size - 1).coerceAtLeast(0)
        updateOverlayActionPills()

        if (readerConfig.scrollMode == ScrollMode.VERTICAL) {
            binding.rvVertical.visibility = View.VISIBLE
            binding.vpHorizontal.visibility = View.GONE
            val lm = LinearLayoutManager(this)
            binding.rvVertical.layoutManager = lm
            binding.rvVertical.adapter = PageSlotAdapter(isVertical = true)
            binding.rvVertical.clearOnScrollListeners()
            binding.rvVertical.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                    val pos = lm.findFirstVisibleItemPosition()
                    if (pos != RecyclerView.NO_POSITION && pos != currentSlotIndex) {
                        onSlotSelected(pos)
                    }
                }
            })
            jumpToSlot(slotIndex)
        } else {
            binding.rvVertical.visibility = View.GONE
            binding.vpHorizontal.visibility = View.VISIBLE
            binding.vpHorizontal.layoutDirection =
                if (readerConfig.scrollMode == ScrollMode.RTL) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
            binding.vpHorizontal.adapter = PageSlotAdapter(isVertical = false)
            jumpToSlot(slotIndex)
        }
        onSlotSelected(slotIndex)
    }

    private fun jumpToSlot(slotIndex: Int) {
        if (slots.isEmpty()) return
        val clamped = slotIndex.coerceIn(0, slots.size - 1)
        if (readerConfig.scrollMode == ScrollMode.VERTICAL) {
            binding.rvVertical.scrollToPosition(clamped)
        } else {
            binding.vpHorizontal.setCurrentItem(clamped, false)
        }
        onSlotSelected(clamped)
    }

    private fun formatProgressText(slotIndex: Int): String {
        val clamped = slotIndex.coerceIn(0, (slots.size - 1).coerceAtLeast(0))
        val slot = slots.getOrNull(clamped)
        val rawTotal = pageSource?.pageCount ?: slots.size
        val rawPart = when (slot) {
            is PageSlot.Merged -> if (slot.rightRaw != null) {
                "${slot.leftRaw + 1}-${slot.rightRaw + 1}P / ${rawTotal}P"
            } else {
                "${slot.leftRaw + 1}P / ${rawTotal}P"
            }
            is PageSlot.SplitHalf -> "${slot.rawIndex + 1}${if (slot.leftHalf) "L" else "R"} / ${rawTotal}P"
            is PageSlot.Single -> "${slot.rawIndex + 1}P / ${rawTotal}P"
            null -> ""
        }
        return "${clamped + 1}/${slots.size} ($rawPart)"
    }

    private fun formatSeekShort(slotIndex: Int): String {
        val clamped = slotIndex.coerceIn(0, (slots.size - 1).coerceAtLeast(0))
        return "${clamped + 1}/${slots.size}"
    }

    private fun onSlotSelected(slotIndex: Int) {
        if (slots.isEmpty()) return
        currentSlotIndex = slotIndex.coerceIn(0, slots.size - 1)
        val displayText = formatProgressText(currentSlotIndex)
        binding.tvPageIndicator.text = "${currentChapter?.title.orEmpty()} · $displayText"
        binding.tvSeekLabel.text = formatSeekShort(currentSlotIndex)
        binding.seekPage.progress = currentSlotIndex
        preloadAdjacent(currentSlotIndex)
    }

    private fun getCurrentRawPage(): Int {
        val slot = slots.getOrNull(currentSlotIndex) ?: return 0
        return when (slot) {
            is PageSlot.Single -> slot.rawIndex
            is PageSlot.SplitHalf -> slot.rawIndex
            is PageSlot.Merged -> slot.leftRaw
        }
    }

    private fun preloadAdjacent(slotIndex: Int) {
        val range = ((slotIndex - 1).coerceAtLeast(0))..((slotIndex + 2).coerceAtMost(slots.size - 1))
        for (i in range) {
            if (bitmapCache.get(i) == null) {
                lifecycleScope.launch(Dispatchers.IO) {
                    renderSlotBitmap(i)
                }
            }
        }
    }

    private fun qualityScaleFactor(): Float = when (app.prefs.readerQuality) {
        1 -> 0.72f
        2 -> 0.50f
        else -> 1.0f
    }

    private suspend fun renderSlotBitmap(slotIndex: Int): Bitmap? {
        bitmapCache.get(slotIndex)?.let { return it }
        val src = pageSource ?: return null
        val slot = slots.getOrNull(slotIndex) ?: return null
        val qScale = qualityScaleFactor()
        val reqW = (resources.displayMetrics.widthPixels.coerceAtLeast(1080) * qScale).toInt().coerceAtLeast(540)
        val reqH = (resources.displayMetrics.heightPixels.coerceAtLeast(1080) * qScale).toInt().coerceAtLeast(540)

        val result: Bitmap? = when (slot) {
            is PageSlot.Single -> {
                val raw = src.loadPageBitmap(slot.rawIndex, reqW, reqH) ?: return null
                postProcess(raw)
            }
            is PageSlot.SplitHalf -> {
                val raw = src.loadPageBitmap(slot.rawIndex, reqW * 2, reqH) ?: return null
                val half = ImageProcessor.splitWideBitmap(raw, slot.leftHalf)
                if (half !== raw) raw.recycle()
                postProcess(half)
            }
            is PageSlot.Merged -> {
                val halfReqW = (reqW / 2).coerceAtLeast(720)
                val leftRaw = src.loadPageBitmap(slot.leftRaw, halfReqW, reqH) ?: return null
                val leftClean = postProcess(leftRaw) ?: leftRaw
                val rightRaw = slot.rightRaw?.let { src.loadPageBitmap(it, halfReqW, reqH) }
                if (rightRaw == null) {
                    leftClean
                } else {
                    val rightClean = postProcess(rightRaw) ?: rightRaw
                    if (readerConfig.mergeRightToLeft) {
                        ImageProcessor.mergeSideBySide(rightClean, leftClean, readerConfig.bgColor.colorInt)
                    } else {
                        ImageProcessor.mergeSideBySide(leftClean, rightClean, readerConfig.bgColor.colorInt)
                    }
                }
            }
        }
        if (result != null) {
            bitmapCache.put(slotIndex, result)
        }
        return result
    }

    private fun postProcess(bmp: Bitmap): Bitmap? {
        if (readerConfig.skipBlankPages && ImageProcessor.isBlankPage(bmp)) {
            return bmp
        }
        return if (readerConfig.trimMargins) {
            ImageProcessor.trimWhiteMargins(
                bmp,
                strength = readerConfig.trimStrength,
                ignoreSides = readerConfig.trimIgnoreSides,
                protect10Percent = readerConfig.trimProtect
            )
        } else {
            bmp
        }
    }

    private fun handleTapAt(relX: Float, relY: Float) {
        if (!readerConfig.clickFlipEnabled) {
            toggleToolbar()
            return
        }
        if (relX in 0.36f..0.64f && relY in 0.32f..0.68f) {
            toggleToolbar()
            return
        }

        var goNext = when (readerConfig.clickFlipMode) {
            ClickFlipMode.LEFT_RIGHT -> relX >= 0.5f
            ClickFlipMode.DIAGONAL_BL_TR -> (relX + relY) >= 1.0f
            ClickFlipMode.DIAGONAL_TL_BR -> (relX - relY) >= 0f
        }
        if (readerConfig.swapClickFlip) goNext = !goNext
        if (readerConfig.scrollMode == ScrollMode.RTL) goNext = !goNext

        if (app.prefs.hapticFeedback) {
            binding.readerRoot.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
        stepPage(if (goNext) 1 else -1)
    }

    private fun stepPage(delta: Int) {
        val next = currentSlotIndex + delta
        when {
            next < 0 -> switchChapter(-1)
            next >= slots.size -> switchChapter(1)
            else -> {
                if (readerConfig.scrollMode == ScrollMode.VERTICAL) {
                    binding.rvVertical.smoothScrollToPosition(next)
                    onSlotSelected(next)
                } else {
                    binding.vpHorizontal.setCurrentItem(next, true)
                }
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (readerConfig.volumeKeyFlip) {
            if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                stepPage(1)
                return true
            }
            if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                stepPage(-1)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun toggleToolbar() {
        setToolbarVisible(!toolbarVisible)
    }

    private fun setToolbarVisible(visible: Boolean) {
        toolbarVisible = visible
        val vis = if (visible) View.VISIBLE else View.GONE
        binding.topOverlay.visibility = vis
        binding.bottomOverlay.visibility = vis
        applySystemBarsForToolbarState(visible)
        updatePageIndicatorVisibility()
    }

    @Suppress("DEPRECATION")
    private fun applySystemBarsForToolbarState(visible: Boolean) {
        val controller = WindowCompat.getInsetsController(window, binding.root)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false
        if (visible) {
            controller.show(WindowInsetsCompat.Type.statusBars())
        } else {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun switchChapter(offset: Int) {
        val idx = chapters.indexOfFirst { it.id == chapterId }
        if (idx < 0) return
        val nextIdx = idx + offset
        if (nextIdx !in chapters.indices) {
            Toast.makeText(this, if (offset < 0) "已经是第一章" else "已经是最后一章", Toast.LENGTH_SHORT).show()
            return
        }
        saveCurrentProgress()
        val nextChap = chapters[nextIdx]
        chapterId = nextChap.id
        currentChapter = nextChap
        binding.tvReaderChapterTitle.text = nextChap.title
        openChapterSource(nextChap, 0)
    }

    private fun saveCurrentProgress() {
        val ch = currentChapter ?: return
        app.db.updateReadingProgress(comicId, ch.id, getCurrentRawPage())
    }

    private fun showChapterPickerDialog() {
        if (chapters.isEmpty()) return
        val names = chapters.map { it.title }.toTypedArray()
        val currentIdx = chapters.indexOfFirst { it.id == chapterId }.coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle("章节目录 (${chapters.size})")
            .setSingleChoiceItems(names, currentIdx) { dialog, which ->
                dialog.dismiss()
                if (which != currentIdx) {
                    saveCurrentProgress()
                    val chosen = chapters[which]
                    chapterId = chosen.id
                    currentChapter = chosen
                    binding.tvReaderChapterTitle.text = chosen.title
                    openChapterSource(chosen, 0)
                }
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun showBookmarkDialog() {
        val ch = currentChapter ?: return
        val bookmarks = app.db.getBookmarks(comicId)
        val items = mutableListOf("+ 添加当前页为书签")
        bookmarks.forEach { b -> items.add("${b.chapterTitle} · 第 ${b.pageIndex + 1} 页") }

        MaterialAlertDialogBuilder(this)
            .setTitle("书签")
            .setItems(items.toTypedArray()) { _, which ->
                if (which == 0) {
                    app.db.addBookmark(
                        Bookmark(
                            comicId = comicId,
                            chapterId = ch.id,
                            chapterTitle = ch.title,
                            pageIndex = getCurrentRawPage()
                        )
                    )
                    Toast.makeText(this, "已添加书签", Toast.LENGTH_SHORT).show()
                } else {
                    val b = bookmarks[which - 1]
                    val targetCh = chapters.find { it.id == b.chapterId } ?: ch
                    if (targetCh.id != chapterId) {
                        chapterId = targetCh.id
                        currentChapter = targetCh
                        binding.tvReaderChapterTitle.text = targetCh.title
                        openChapterSource(targetCh, b.pageIndex)
                    } else {
                        rebuildSlotsAndRender(b.pageIndex)
                    }
                }
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun applyBrightness() {
        val lp = window.attributes
        val b = app.prefs.readerBrightness
        lp.screenBrightness = if (b < 0) {
            WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        } else {
            b.coerceIn(5, 100) / 100f
        }
        window.attributes = lp
    }

    private fun persistReaderConfig(updated: ReaderConfig, syncGlobalMode: Boolean = false) {
        readerConfig = updated
        app.db.upsertReaderConfig(readerConfig)
        if (syncGlobalMode && app.prefs.applyReaderModeToAll) {
            val defaultCfg = app.db.getReaderConfigs().firstOrNull()
            if (defaultCfg != null && defaultCfg.id != readerConfig.id) {
                app.db.upsertReaderConfig(
                    defaultCfg.copy(
                        scrollMode = readerConfig.scrollMode,
                        doublePageMode = readerConfig.doublePageMode,
                        mergeRightToLeft = readerConfig.mergeRightToLeft
                    )
                )
            }
        }
    }

    private fun openReaderSettingsPanel() {
        if (!this::readerConfig.isInitialized) return
        setToolbarVisible(false)
        syncReaderSettingsPanelState()
        binding.readerSettingsOverlay.visibility = View.VISIBLE
        binding.readerSettingsPanel.translationX = 80f
        binding.readerSettingsPanel.alpha = 0f
        binding.readerSettingsPanel.animate()
            .translationX(0f)
            .alpha(1f)
            .setDuration(180)
            .start()
    }

    private fun closeReaderSettingsPanel() {
        binding.readerSettingsOverlay.visibility = View.GONE
    }

    private fun setupReaderSettingsDrawer() {
        binding.readerSettingsOverlay.setOnClickListener { closeReaderSettingsPanel() }
        binding.readerSettingsPanel.setOnClickListener { /* consume click */ }

        binding.btnBrightnessAuto.setOnClickListener {
            app.prefs.readerBrightness = -1
            applyBrightness()
            syncReaderSettingsPanelState()
        }
        binding.ivBrightnessLow.setOnClickListener {
            val cur = if (app.prefs.readerBrightness < 0) 50 else app.prefs.readerBrightness
            app.prefs.readerBrightness = (cur - 15).coerceIn(5, 100)
            applyBrightness()
            syncReaderSettingsPanelState()
        }
        binding.ivBrightnessHigh.setOnClickListener {
            val cur = if (app.prefs.readerBrightness < 0) 50 else app.prefs.readerBrightness
            app.prefs.readerBrightness = (cur + 15).coerceIn(5, 100)
            applyBrightness()
            syncReaderSettingsPanelState()
        }
        binding.sbBrightness.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    app.prefs.readerBrightness = progress.coerceIn(5, 100)
                    applyBrightness()
                    syncBrightnessBadge()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.btnModeLtr.setOnClickListener {
            if (!this::readerConfig.isInitialized) return@setOnClickListener
            persistReaderConfig(
                readerConfig.copy(scrollMode = ScrollMode.LTR, mergeRightToLeft = false),
                syncGlobalMode = true
            )
            bitmapCache.evictAll()
            rebuildSlotsAndRender(getCurrentRawPage())
            syncReaderSettingsPanelState()
        }
        binding.btnModeRtl.setOnClickListener {
            if (!this::readerConfig.isInitialized) return@setOnClickListener
            persistReaderConfig(
                readerConfig.copy(scrollMode = ScrollMode.RTL, mergeRightToLeft = true),
                syncGlobalMode = true
            )
            bitmapCache.evictAll()
            rebuildSlotsAndRender(getCurrentRawPage())
            syncReaderSettingsPanelState()
        }
        binding.btnModeVertical.setOnClickListener {
            if (!this::readerConfig.isInitialized) return@setOnClickListener
            persistReaderConfig(
                readerConfig.copy(scrollMode = ScrollMode.VERTICAL),
                syncGlobalMode = true
            )
            bitmapCache.evictAll()
            rebuildSlotsAndRender(getCurrentRawPage())
            syncReaderSettingsPanelState()
        }

        val selectQuality: (Int) -> Unit = { q ->
            if (app.prefs.readerQuality != q) {
                app.prefs.readerQuality = q
                bitmapCache.evictAll()
                binding.rvVertical.adapter?.notifyDataSetChanged()
                binding.vpHorizontal.adapter?.notifyDataSetChanged()
                syncReaderSettingsPanelState()
            }
        }
        binding.btnQualityUltra.setOnClickListener { selectQuality(0) }
        binding.btnQualityHigh.setOnClickListener { selectQuality(1) }
        binding.btnQualitySmooth.setOnClickListener { selectQuality(2) }

        val selectDoublePage: (DoublePageMode) -> Unit = { mode ->
            if (this::readerConfig.isInitialized && readerConfig.doublePageMode != mode) {
                persistReaderConfig(readerConfig.copy(doublePageMode = mode), syncGlobalMode = true)
                bitmapCache.evictAll()
                rebuildSlotsAndRender(getCurrentRawPage())
                syncReaderSettingsPanelState()
            }
        }
        binding.btnPageSingle.setOnClickListener { selectDoublePage(DoublePageMode.NORMAL) }
        binding.btnPageDouble.setOnClickListener { selectDoublePage(DoublePageMode.MERGE) }
        binding.btnPageAuto.setOnClickListener { selectDoublePage(DoublePageMode.AUTO_LANDSCAPE) }

        val selectSpreadOdd: (Boolean) -> Unit = { firstSingle ->
            if (this::readerConfig.isInitialized && readerConfig.firstPageSingle != firstSingle) {
                val targetDouble = if (readerConfig.doublePageMode == DoublePageMode.NORMAL) {
                    DoublePageMode.AUTO_LANDSCAPE
                } else {
                    readerConfig.doublePageMode
                }
                persistReaderConfig(readerConfig.copy(doublePageMode = targetDouble, firstPageSingle = firstSingle))
                bitmapCache.evictAll()
                rebuildSlotsAndRender(getCurrentRawPage())
                syncReaderSettingsPanelState()
            }
        }
        binding.btnSpreadOdd.setOnClickListener { selectSpreadOdd(true) }
        binding.btnSpreadEven.setOnClickListener { selectSpreadOdd(false) }

        val selectBg: (ReaderBgColor) -> Unit = { bg ->
            if (this::readerConfig.isInitialized && readerConfig.bgColor != bg) {
                persistReaderConfig(readerConfig.copy(bgColor = bg))
                bitmapCache.evictAll()
                applyReaderConfigStyle()
                rebuildSlotsAndRender(getCurrentRawPage())
                syncReaderSettingsPanelState()
            }
        }
        binding.btnBgBlack.setOnClickListener { selectBg(ReaderBgColor.BLACK) }
        binding.btnBgDarkGray.setOnClickListener { selectBg(ReaderBgColor.DARK_GRAY) }
        binding.btnBgLightGray.setOnClickListener { selectBg(ReaderBgColor.LIGHT_GRAY) }
        binding.btnBgWhite.setOnClickListener { selectBg(ReaderBgColor.WHITE) }

        binding.btnOpenMoreReaderConfig.setOnClickListener {
            closeReaderSettingsPanel()
            startActivity(Intent(this, ReaderConfigActivity::class.java))
        }
    }

    private fun syncBrightnessBadge() {
        val isAuto = app.prefs.readerBrightness < 0
        binding.btnBrightnessAuto.setBackgroundResource(
            if (isAuto) R.drawable.bg_reader_seg_selected else R.drawable.bg_reader_seg_unselected
        )
        binding.btnBrightnessAuto.setTextColor(if (isAuto) 0xFF3898FF.toInt() else 0xFFB8C0D0.toInt())
    }

    private fun stylePill(tv: TextView, selected: Boolean) {
        tv.setBackgroundResource(if (selected) R.drawable.bg_reader_seg_selected else R.drawable.bg_reader_seg_unselected)
        tv.setTextColor(if (selected) 0xFF3898FF.toInt() else 0xFFB8C0D0.toInt())
    }

    private fun styleModeCard(container: View, icon: ImageView, label: TextView, selected: Boolean) {
        container.setBackgroundResource(if (selected) R.drawable.bg_reader_seg_selected else R.drawable.bg_reader_seg_unselected)
        val tint = if (selected) 0xFF3898FF.toInt() else 0xFFB8C0D0.toInt()
        icon.setColorFilter(tint)
        label.setTextColor(tint)
    }

    private fun syncReaderSettingsPanelState() {
        if (!this::readerConfig.isInitialized) return
        val b = app.prefs.readerBrightness
        binding.sbBrightness.progress = if (b < 0) 65 else b.coerceIn(5, 100)
        syncBrightnessBadge()

        styleModeCard(binding.btnModeLtr, binding.ivModeLtr, binding.tvModeLtr, readerConfig.scrollMode == ScrollMode.LTR)
        styleModeCard(binding.btnModeRtl, binding.ivModeRtl, binding.tvModeRtl, readerConfig.scrollMode == ScrollMode.RTL)
        styleModeCard(
            binding.btnModeVertical,
            binding.ivModeVertical,
            binding.tvModeVertical,
            readerConfig.scrollMode == ScrollMode.VERTICAL
        )

        binding.swApplyModeAll.setOnCheckedChangeListener(null)
        binding.swApplyModeAll.isChecked = app.prefs.applyReaderModeToAll
        binding.swApplyModeAll.setOnCheckedChangeListener { _, isChecked ->
            app.prefs.applyReaderModeToAll = isChecked
            if (isChecked) persistReaderConfig(readerConfig, syncGlobalMode = true)
        }

        val q = app.prefs.readerQuality
        stylePill(binding.btnQualityUltra, q == 0)
        stylePill(binding.btnQualityHigh, q == 1)
        stylePill(binding.btnQualitySmooth, q == 2)

        stylePill(binding.btnPageSingle, readerConfig.doublePageMode == DoublePageMode.NORMAL)
        stylePill(binding.btnPageDouble, readerConfig.doublePageMode == DoublePageMode.MERGE)
        stylePill(
            binding.btnPageAuto,
            readerConfig.doublePageMode == DoublePageMode.AUTO_LANDSCAPE || readerConfig.doublePageMode == DoublePageMode.SPLIT
        )

        stylePill(binding.btnSpreadOdd, readerConfig.firstPageSingle)
        stylePill(binding.btnSpreadEven, !readerConfig.firstPageSingle)

        stylePill(binding.btnBgBlack, readerConfig.bgColor == ReaderBgColor.BLACK)
        stylePill(binding.btnBgDarkGray, readerConfig.bgColor == ReaderBgColor.DARK_GRAY)
        stylePill(binding.btnBgLightGray, readerConfig.bgColor == ReaderBgColor.LIGHT_GRAY)
        stylePill(binding.btnBgWhite, readerConfig.bgColor == ReaderBgColor.WHITE)

        binding.swDoubleTapZoom.setOnCheckedChangeListener(null)
        binding.swDoubleTapZoom.isChecked = app.prefs.allowDoubleTapZoom
        binding.swDoubleTapZoom.setOnCheckedChangeListener { _, isChecked ->
            app.prefs.allowDoubleTapZoom = isChecked
            binding.rvVertical.adapter?.notifyDataSetChanged()
            binding.vpHorizontal.adapter?.notifyDataSetChanged()
        }

        binding.swTrimMargins.setOnCheckedChangeListener(null)
        binding.swTrimMargins.isChecked = readerConfig.trimMargins
        binding.swTrimMargins.setOnCheckedChangeListener { _, isChecked ->
            persistReaderConfig(readerConfig.copy(trimMargins = isChecked))
            bitmapCache.evictAll()
            rebuildSlotsAndRender(getCurrentRawPage())
        }

        binding.swShowPageNumber.setOnCheckedChangeListener(null)
        binding.swShowPageNumber.isChecked = readerConfig.showPageNumber
        binding.swShowPageNumber.setOnCheckedChangeListener { _, isChecked ->
            persistReaderConfig(readerConfig.copy(showPageNumber = isChecked))
            updatePageIndicatorVisibility()
        }
    }

    private inner class PageSlotAdapter(
        private val isVertical: Boolean
    ) : RecyclerView.Adapter<PageSlotAdapter.PageVH>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageVH {
            val view = ZoomableImageView(parent.context).apply {
                verticalFitWidthMode = isVertical
                layoutParams = if (isVertical) {
                    ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
                        adjustViewBounds = true
                    }
                } else {
                    ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                }
            }
            return PageVH(view)
        }

        override fun getItemCount(): Int = slots.size

        override fun onBindViewHolder(holder: PageVH, position: Int) {
            val view = holder.imageView
            view.tag = position
            view.doubleTapZoomEnabled = app.prefs.allowDoubleTapZoom
            view.setImageDrawable(null)
            view.onSingleTapAt = { rx, ry -> handleTapAt(rx, ry) }
            if (isVertical) {
                view.adjustViewBounds = true
                view.scaleType = ImageView.ScaleType.FIT_CENTER
            }
            lifecycleScope.launch {
                val bmp = renderSlotBitmap(position)
                if (view.tag == position && bmp != null) {
                    view.setImageBitmap(bmp)
                    if (!isVertical) view.resetZoom()
                }
            }
        }

        inner class PageVH(val imageView: ZoomableImageView) : RecyclerView.ViewHolder(imageView)
    }

    companion object {
        const val EXTRA_COMIC_ID = "extra_comic_id"
        const val EXTRA_CHAPTER_ID = "extra_chapter_id"
        const val EXTRA_PAGE_INDEX = "extra_page_index"
    }
}
