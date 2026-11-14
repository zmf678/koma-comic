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
import android.widget.Toast
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
        setupOverlayControls()
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
        binding.btnReaderMore.setOnClickListener { showQuickReaderDialog() }
        binding.btnBookmark.setOnClickListener { showBookmarkDialog() }
        binding.btnReaderSettings.setOnClickListener { showQuickReaderDialog() }
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
        updatePageIndicatorVisibility()

        val marginPx = (readerConfig.sideMarginDp * resources.displayMetrics.density).toInt()
        binding.rvVertical.setPadding(marginPx, 0, marginPx, 0)
        binding.vpHorizontal.setPadding(marginPx, 0, marginPx, 0)
        updateOverlayActionPills()
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

    private suspend fun renderSlotBitmap(slotIndex: Int): Bitmap? {
        bitmapCache.get(slotIndex)?.let { return it }
        val src = pageSource ?: return null
        val slot = slots.getOrNull(slotIndex) ?: return null
        val reqW = resources.displayMetrics.widthPixels.coerceAtLeast(1080)
        val reqH = resources.displayMetrics.heightPixels.coerceAtLeast(1080)

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

    private fun showQuickReaderDialog() {
        val options = arrayOf(
            "翻页方式: ${readerConfig.scrollMode.label}",
            "单双页模式: ${readerConfig.doublePageMode.label}",
            "合并顺序: ${if (readerConfig.mergeRightToLeft) "右→左 (日漫)" else "左→右"}",
            "更改跨页匹配: ${if (readerConfig.firstPageSingle) "首页单页起" else "成对跨页起"}",
            "去白边: ${if (readerConfig.trimMargins) "开" else "关"}",
            "背景色: ${readerConfig.bgColor.label}",
            "管理与自定义更多配置..."
        )
        MaterialAlertDialogBuilder(this)
            .setTitle("阅读设置 (${readerConfig.name})")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        val modes = ScrollMode.values()
                        readerConfig = readerConfig.copy(scrollMode = modes[(readerConfig.scrollMode.ordinal + 1) % modes.size])
                        app.db.upsertReaderConfig(readerConfig)
                        rebuildSlotsAndRender(getCurrentRawPage())
                    }
                    1 -> {
                        val modes = DoublePageMode.values()
                        readerConfig = readerConfig.copy(doublePageMode = modes[(readerConfig.doublePageMode.ordinal + 1) % modes.size])
                        app.db.upsertReaderConfig(readerConfig)
                        bitmapCache.evictAll()
                        rebuildSlotsAndRender(getCurrentRawPage())
                    }
                    2 -> {
                        readerConfig = readerConfig.copy(mergeRightToLeft = !readerConfig.mergeRightToLeft)
                        app.db.upsertReaderConfig(readerConfig)
                        bitmapCache.evictAll()
                        rebuildSlotsAndRender(getCurrentRawPage())
                    }
                    3 -> toggleCrossPageMatch()
                    4 -> {
                        readerConfig = readerConfig.copy(trimMargins = !readerConfig.trimMargins)
                        app.db.upsertReaderConfig(readerConfig)
                        bitmapCache.evictAll()
                        rebuildSlotsAndRender(getCurrentRawPage())
                    }
                    5 -> {
                        val bgs = ReaderBgColor.values()
                        readerConfig = readerConfig.copy(bgColor = bgs[(readerConfig.bgColor.ordinal + 1) % bgs.size])
                        app.db.upsertReaderConfig(readerConfig)
                        applyReaderConfigStyle()
                    }
                    6 -> startActivity(Intent(this, ReaderConfigActivity::class.java))
                }
            }
            .show()
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
