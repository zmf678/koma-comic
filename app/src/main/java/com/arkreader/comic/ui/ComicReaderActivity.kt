package com.arkreader.comic.ui

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.util.LruCache
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.PopupMenu
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
import androidx.recyclerview.widget.SimpleItemAnimator
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
import com.arkreader.comic.engine.MangaTranslation
import com.arkreader.comic.engine.PageSlot
import com.arkreader.comic.engine.PageSourceFactory
import com.arkreader.comic.engine.SpreadSlotBuilder
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.math.abs

class ComicReaderActivity : AppCompatActivity() {

    private lateinit var binding: ActivityComicReaderBinding
    private val app get() = ArkReaderApp.instance

    private var comicId: Long = 0L
    private var chapterId: Long = 0L
    private var currentComic: Comic? = null
    private var chapters: List<Chapter> = emptyList()
    private var currentChapter: Chapter? = null
    private lateinit var readerConfig: ReaderConfig

    private var chapterLoad: Job? = null
    private var pageErrorShown = false
    private var pageSource: ComicPageSource? = null
    private var widePageFlags: BooleanArray = BooleanArray(0)
    private var slots: List<PageSlot> = emptyList()
    private var currentSlotIndex: Int = 0
    private var readingClock: Job? = null
    private var readingStart: java.time.Instant? = null
    private var readingTick = 0L
    private var readerForeground = false
    private var translationEnabled = false
    private var translationJob: Job? = null
    private var translationPrefetch: Job? = null
    private data class PageTranslation(val image: Bitmap, val changed: Boolean)
    private val translationTasks = mutableMapOf<Int, Deferred<Result<PageTranslation>>>()
    private val translatedPages = object : LruCache<Int, PageTranslation>(24 * 1024 * 1024) {
        override fun sizeOf(key: Int, value: PageTranslation) = value.image.byteCount
    }
    private var translationRequest = 0
    private var translatingView: View? = null
    private var toolbarVisible = false
    private var lastWheelTurnMs = 0L
    private var prefetchJob: Job? = null
    private var widePageRebuildPending = false
    private val prefetchSemaphore = Semaphore(2)
    private val inFlightSlotJobs = java.util.concurrent.ConcurrentHashMap<Int, Deferred<Bitmap?>>()
    private val pageBindJobs = java.util.WeakHashMap<ZoomableImageView, Job>()
    private val easeOutCubic = PathInterpolator(0.22f, 1f, 0.36f, 1f)
    private val easeInCubic = PathInterpolator(0.4f, 0f, 1f, 1f)

    private val bitmapCache = object : LruCache<Int, Bitmap>(18) {}
    private val vpCallback = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageSelected(position: Int) {
            onSlotSelected(position)
            refreshReaderGlassChrome()
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
                (14 * density).toInt(),
                sys.top + baseTopPad,
                (12 * density).toInt(),
                (16 * density).toInt()
            )
            binding.bottomOverlay.setPadding(
                sidePad,
                0,
                sidePad,
                sys.bottom + baseBottomPad
            )
            val drawerLp = binding.readerSettingsPanel.layoutParams as? FrameLayout.LayoutParams
            if (drawerLp != null) {
                val m = (12 * density).toInt()
                drawerLp.setMargins(m, sys.top + m, m, sys.bottom + m)
                binding.readerSettingsPanel.layoutParams = drawerLp
            }
            insets
        }
        ViewCompat.requestApplyInsets(binding.readerRoot)
    }

    private fun updateBottomCapsuleWidth() {
        val dm = resources.displayMetrics
        val maxPx = (600 * dm.density).toInt()
        val targetW = if (dm.widthPixels > maxPx + (32 * dm.density).toInt()) {
            maxPx + (32 * dm.density).toInt()
        } else {
            ViewGroup.LayoutParams.MATCH_PARENT
        }
        val lp = binding.bottomOverlay.layoutParams as? FrameLayout.LayoutParams
        if (lp != null && lp.width != targetW) {
            lp.width = targetW
            lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            binding.bottomOverlay.layoutParams = lp
        }
        val drawerLp = binding.readerSettingsPanel.layoutParams as? FrameLayout.LayoutParams
        if (drawerLp != null) {
            val desiredDrawerW = (356 * dm.density).toInt().coerceAtMost(dm.widthPixels - (24 * dm.density).toInt())
            if (drawerLp.width != desiredDrawerW) {
                drawerLp.width = desiredDrawerW
                binding.readerSettingsPanel.layoutParams = drawerLp
            }
        }
    }

    override fun onResume() {
        super.onResume()
        readerForeground = true
        startReadingClock()
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
        if (translationEnabled) binding.root.post { translateCurrentPage() }
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
        readerForeground = false
        translationJob?.cancel()
        translationPrefetch?.cancel()
        stopReadingClock()
        super.onPause()
        saveCurrentProgress()
    }

    override fun onDestroy() {
        super.onDestroy()
        binding.vpHorizontal.unregisterOnPageChangeCallback(vpCallback)
        pageSource?.close()
        bitmapCache.evictAll()
    }

    private fun startReadingClock() {
        if (!readerForeground || pageSource == null || currentComic == null || readingStart != null) return
        readingStart = java.time.Instant.now()
        readingTick = android.os.SystemClock.uptimeMillis()
        readingClock = lifecycleScope.launch {
            while (true) {
                kotlinx.coroutines.delay(30_000)
                saveReadingTime()
            }
        }
    }

    private fun saveReadingTime() {
        val start = readingStart ?: return
        val comic = currentComic ?: return
        val now = android.os.SystemClock.uptimeMillis()
        val elapsed = now - readingTick
        if (elapsed <= 0) return
        runCatching { app.db.addReadingTime(comic.id, comic.title, start, elapsed) }.onFailure {
            android.util.Log.w("ComicReader", "Could not save reading duration", it)
        }.onSuccess {
            readingStart = java.time.Instant.now()
            readingTick = now
        }
    }

    private fun stopReadingClock() {
        readingClock?.cancel()
        readingClock = null
        saveReadingTime()
        readingStart = null
    }

    private fun refreshReaderGlassChrome() {
        if (!app.prefs.glassMaterial) return
        binding.root.post {
            if (isDestroyed) return@post
            if (binding.bottomOverlay.visibility == View.VISIBLE) binding.bottomCapsuleCard.refreshLiquidGlassBackdrop()
            if (binding.topOverlay.visibility == View.VISIBLE && binding.btnSpreadMatch.visibility == View.VISIBLE) {
                binding.btnSpreadMatch.refreshLiquidGlassBackdrop()
            }
            if (binding.translationBall.visibility == View.VISIBLE) binding.translationBall.refreshLiquidGlassBackdrop()
            if (binding.tvPageIndicator.visibility == View.VISIBLE) binding.tvPageIndicator.refreshLiquidGlassBackdrop()
            if (binding.tvTranslationState.visibility == View.VISIBLE) binding.tvTranslationState.refreshLiquidGlassBackdrop()
            if (binding.readerSettingsOverlay.visibility == View.VISIBLE) binding.readerSettingsPanel.refreshLiquidGlassBackdrop()
        }
    }

    private fun setupOverlayControls() {
        if (app.prefs.glassMaterial) {
            val readerBackdrop: () -> View? = {
                if (binding.rvVertical.visibility == View.VISIBLE) binding.rvVertical else binding.vpHorizontal
            }
            binding.bottomCapsuleCard.applyLiquidGlass(
                backdropProvider = readerBackdrop,
                preset = LiquidGlassPreset.READER_CAPSULE,
                cornerRadiusDp = 28f,
                forceDark = true,
                elevationDp = 8f
            )
            binding.translationBall.applyLiquidGlass(
                backdropProvider = readerBackdrop,
                preset = LiquidGlassPreset.CLEAR_LENS,
                cornerRadiusDp = 25f,
                forceDark = true,
                interactiveElastic = true,
                elevationDp = 8f
            )
            binding.btnSpreadMatch.applyLiquidGlass(
                backdropProvider = readerBackdrop,
                preset = LiquidGlassPreset.SUBTLE_PILL,
                cornerRadiusDp = 16f,
                forceDark = true,
                interactiveElastic = true,
                elevationDp = 4f
            )
            binding.tvPageIndicator.applyLiquidGlass(
                backdropProvider = readerBackdrop,
                preset = LiquidGlassPreset.SUBTLE_PILL,
                cornerRadiusDp = 14f,
                forceDark = true,
                elevationDp = 3f
            )
            binding.tvTranslationState.applyLiquidGlass(
                backdropProvider = readerBackdrop,
                preset = LiquidGlassPreset.SUBTLE_PILL,
                cornerRadiusDp = 16f,
                forceDark = true,
                elevationDp = 5f
            )
            binding.readerLoadingBox.applyLiquidGlass(
                backdropProvider = readerBackdrop,
                preset = LiquidGlassPreset.SUBTLE_PILL,
                cornerRadiusDp = 16f,
                forceDark = true,
                elevationDp = 6f
            )
            binding.readerSettingsPanel.applyLiquidGlass(
                backdropProvider = readerBackdrop,
                preset = LiquidGlassPreset.READER_DRAWER,
                cornerRadiusDp = 22f,
                forceDark = true,
                elevationDp = 10f
            )
        }
        binding.btnBack.setOnClickListener { finish() }
        binding.btnReaderMore.setOnClickListener { openReaderSettingsPanel() }
        binding.btnReaderFavorite.setOnClickListener { toggleReaderFavorite() }
        binding.btnTranslate.setOnClickListener { toggleTranslation() }
        binding.translationBall.setOnClickListener { anchor ->
            val textOnly = !app.prefs.translationModel().acceptsPageImage
            val popup = PopupMenu(this, anchor)
            popup.menu.add(0, 0, 0, if (textOnly) "输入原文" else "翻译当前页")
            popup.menu.add(0, 1, 1, "显示原文")
            popup.menu.add(0, 2, 2, "翻译设置")
            popup.menu.add(0, 3, 3, "查看 Token 用量")
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    0 -> if (textOnly) showTextTranslation() else {
                        translatedPages.remove(currentSlotIndex)
                        translatingView = null
                        translateCurrentPage()
                    }
                    1 -> toggleTranslation()
                    2 -> showReaderTranslationSettings()
                    3 -> showTranslationUsage(this)
                }
                true
            }
            popup.show()
        }
        binding.tvTranslationState.setOnClickListener {
            if (translationEnabled && app.prefs.translationModel().acceptsPageImage) {
                translatedPages.remove(currentSlotIndex)
                translatingView = null
                translateCurrentPage()
            }
        }
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

    private fun toggleTranslation() {
        val prefs = app.prefs
        val model = prefs.translationModel()
        val setupRequired = if (model.mode.usesTranslationServer) prefs.translationServer.isBlank()
            else model.apiBase.isBlank() || model.modelId.isBlank() || !prefs.hasTranslationKey
        if (!translationEnabled && setupRequired) {
            Toast.makeText(this, "请先在设置中完成漫画翻译配置", Toast.LENGTH_LONG).show()
            return
        }
        translationEnabled = !translationEnabled
        resetTranslations()
        binding.tvTranslateIcon.text = if (translationEnabled) "文" else "译"
        binding.tvTranslateLabel.text = if (translationEnabled) "原文" else "翻译"
        binding.tvTranslationState.visibility = if (translationEnabled) View.VISIBLE else View.GONE
        binding.translationBall.visibility = if (translationEnabled && binding.readerSettingsOverlay.visibility != View.VISIBLE) View.VISIBLE else View.GONE
        if (translationEnabled) {
            binding.tvTranslationState.text = if (model.acceptsPageImage) "等待页面…" else "点击译球输入原文"
            refreshReaderGlassChrome()
            if (model.acceptsPageImage) translateCurrentPage() else showTextTranslation()
        } else activePageRecycler()?.adapter?.notifyDataSetChanged()
    }

    private fun toggleCrossPageMatch() {
        if (!this::readerConfig.isInitialized || pageSource == null) return
        val oldSlot = slots.getOrNull(currentSlotIndex)
        val oldFirst = getCurrentRawPage()
        val oldSecond = (oldSlot as? PageSlot.Merged)?.rightRaw
        val nextDoubleMode = if (readerConfig.doublePageMode == DoublePageMode.NORMAL) {
            DoublePageMode.AUTO_LANDSCAPE
        } else {
            readerConfig.doublePageMode
        }
        val nextFirstSingle = !readerConfig.firstPageSingle
        // Sync with Windows v1.5.3 (fe97933): when toggling odd -> even, anchor on oldSecond so visible spread shifts cleanly
        val anchorPage = if (!nextFirstSingle && oldSecond != null) oldSecond else oldFirst
        readerConfig = readerConfig.copy(
            doublePageMode = nextDoubleMode,
            firstPageSingle = nextFirstSingle
        )
        app.db.upsertReaderConfig(readerConfig)
        bitmapCache.evictAll()
        rebuildSlotsAndRender(anchorPage)

        val effective = effectiveDoublePageMode()
        val matchState = if (nextFirstSingle) "首页单页起" else "默认双页起"
        val newSlot = slots.getOrNull(currentSlotIndex)
        val curWide = oldFirst in widePageFlags.indices && widePageFlags[oldFirst]
        val tip = when {
            effective == DoublePageMode.NORMAL -> "已切换跨页偏移 ($matchState)，横屏双页时生效"
            curWide && newSlot == oldSlot -> "已切换为「$matchState」（当前页为宽图，翻页后生效）"
            newSlot is PageSlot.Merged && newSlot.rightRaw != null -> "已切换为「$matchState」· 第 ${newSlot.leftRaw + 1}-${newSlot.rightRaw + 1} 页"
            else -> "已切换为「$matchState」· 第 ${getCurrentRawPage() + 1} 页"
        }
        Toast.makeText(this, tip, Toast.LENGTH_SHORT).show()
    }

    private fun effectiveDoublePageMode(): DoublePageMode {
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        return SpreadSlotBuilder.resolveEffectiveMode(readerConfig.doublePageMode, isLandscape)
    }

    private fun updateOverlayActionPills() {
        if (!this::readerConfig.isInitialized) return
        val showSpreadPill = readerConfig.scrollMode != ScrollMode.VERTICAL && effectiveDoublePageMode() == DoublePageMode.MERGE
        binding.btnSpreadMatch.visibility = if (showSpreadPill) View.VISIBLE else View.GONE
        binding.btnSpreadMatch.text = if (readerConfig.firstPageSingle) {
            "跨页偏移 · 首页单页"
        } else {
            "跨页偏移 · 默认双页"
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
        syncReaderFavoriteButton()
        setToolbarVisible(readerConfig.showToolbarOnOpen, animate = false)
        applyReaderConfigStyle()
        applyPageTurnTransformer()
        openChapterSource(ch, initialRawPage)
    }

    private fun toggleReaderFavorite() {
        val c = currentComic ?: return
        val next = !c.isFavorite
        app.db.setFavorite(c.id, next)
        currentComic = c.copy(isFavorite = next)
        syncReaderFavoriteButton()
        Toast.makeText(this, if (next) "已加入收藏" else "已取消收藏", Toast.LENGTH_SHORT).show()
    }

    private fun syncReaderFavoriteButton() {
        val fav = currentComic?.isFavorite == true
        val density = resources.displayMetrics.density
        binding.ivReaderFav.setImageResource(if (fav) R.drawable.ic_favorite else R.drawable.ic_favorite_border)
        binding.ivReaderFav.setColorFilter(if (fav) 0xFFFFD466.toInt() else Color.WHITE)
        binding.tvReaderFav.text = if (fav) "已收藏" else "收藏"
        binding.tvReaderFav.setTextColor(if (fav) 0xFFFFD466.toInt() else 0xF0FFFFFF.toInt())
        binding.btnReaderFavorite.background = if (fav) {
            GradientDrawable().apply {
                cornerRadius = 18f * density
                setColor(0x3AF59E0B)
                setStroke((1f * density).toInt().coerceAtLeast(1), 0xA5FBBF24.toInt())
            }
        } else null
        binding.btnReaderFavorite.contentDescription = if (fav) "已收藏，点击取消" else "收藏"
    }

    private fun pageTurnAnimLabel(key: String): String = when (key) {
        "none" -> "无动画"
        "fade" -> "柔和淡入"
        "slide" -> "平滑滑动"
        "cover" -> "覆层覆盖"
        "curl" -> "拟真翻书"
        "depth" -> "景深缩放"
        else -> "平滑滑动"
    }

    private fun applyPageTurnTransformer() {
        val mode = app.prefs.pageTurnAnimation
        binding.vpHorizontal.setPageTransformer(
            when (mode) {
                "none" -> ViewPager2.PageTransformer { page, position ->
                    page.translationX = -position * page.width
                    page.alpha = if (position in -0.5f..0.5f) 1f else 0f
                    page.scaleX = 1f
                    page.scaleY = 1f
                    page.rotationY = 0f
                }
                "fade" -> ViewPager2.PageTransformer { page, position ->
                    page.translationX = -position * page.width
                    page.alpha = (1f - abs(position)).coerceIn(0f, 1f)
                    page.scaleX = 1f
                    page.scaleY = 1f
                    page.rotationY = 0f
                }
                "cover" -> ViewPager2.PageTransformer { page, position ->
                    page.rotationY = 0f
                    when {
                        position <= 0f -> {
                            page.alpha = (1f + position * 0.35f).coerceIn(0.6f, 1f)
                            page.translationX = -position * page.width
                            val scale = (1f + position * 0.05f).coerceIn(0.95f, 1f)
                            page.scaleX = scale
                            page.scaleY = scale
                            page.translationZ = 0f
                        }
                        else -> {
                            page.alpha = 1f
                            page.translationX = 0f
                            page.scaleX = 1f
                            page.scaleY = 1f
                            page.translationZ = 4f
                        }
                    }
                }
                "curl" -> ViewPager2.PageTransformer { page, position ->
                    val isRtl = this::readerConfig.isInitialized && readerConfig.scrollMode == ScrollMode.RTL
                    page.cameraDistance = 18000f
                    page.translationX = -position * page.width
                    page.pivotY = page.height * 0.5f
                    when {
                        position < -1f || position > 1f -> page.alpha = 0f
                        position <= 0f -> {
                            page.pivotX = if (isRtl) page.width.toFloat() else 0f
                            page.rotationY = if (isRtl) -position * 88f else position * 88f
                            page.alpha = (1f + position * 0.65f).coerceIn(0f, 1f)
                            page.scaleX = 1f
                            page.scaleY = 1f
                            page.translationZ = 2f
                        }
                        else -> {
                            page.pivotX = if (isRtl) 0f else page.width.toFloat()
                            page.rotationY = 0f
                            page.alpha = (1f - position * 0.25f).coerceIn(0.75f, 1f)
                            page.scaleX = 1f
                            page.scaleY = 1f
                            page.translationZ = 0f
                        }
                    }
                }
                "depth" -> ViewPager2.PageTransformer { page, position ->
                    page.rotationY = 0f
                    page.translationX = -position * page.width
                    when {
                        position < -1f || position > 1f -> page.alpha = 0f
                        position <= 0f -> {
                            page.alpha = (1f + position).coerceIn(0f, 1f)
                            val scale = (1f + position * 0.12f).coerceIn(0.88f, 1f)
                            page.scaleX = scale
                            page.scaleY = scale
                        }
                        else -> {
                            page.alpha = (1f - position).coerceIn(0f, 1f)
                            val scale = (1f + position * 0.08f).coerceIn(1f, 1.08f)
                            page.scaleX = scale
                            page.scaleY = scale
                        }
                    }
                }
                else -> ViewPager2.PageTransformer { page, _ ->
                    page.alpha = 1f
                    page.translationX = 0f
                    page.scaleX = 1f
                    page.scaleY = 1f
                    page.rotationY = 0f
                    page.translationZ = 0f
                }
            }
        )
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
        val shouldShow = this::readerConfig.isInitialized && readerConfig.showPageNumber
        binding.tvPageIndicator.visibility = if (shouldShow) View.VISIBLE else View.GONE
    }

    private fun openChapterSource(chapter: Chapter, initialRawPage: Int) {
        stopReadingClock()
        val wh = currentComic?.let { app.db.getWarehouse(it.warehouseId) }
        binding.tvLoadingStatus.text = "正在加载章节…"
        binding.readerLoadingBox.visibility = View.VISIBLE
        chapterLoad?.cancel()
        resetTranslations()
        pageErrorShown = false
        pageSource?.close()
        pageSource = null
        widePageFlags = BooleanArray(0)
        widePageRebuildPending = false
        bitmapCache.evictAll()

        chapterLoad = lifecycleScope.launch {
            val result = try {
                withContext(Dispatchers.IO) {
                    val src = PageSourceFactory.open(
                        this@ComicReaderActivity,
                        chapter,
                        wh,
                        app.coverCache,
                        readerConfig.pdfScale
                    )
                    if (src.pageCount <= 0) {
                        src.close()
                        error("该章节暂无可读页面")
                    }
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
                }
            } catch (err: Exception) {
                if (err is CancellationException && err !is TimeoutCancellationException) throw err
                binding.readerLoadingBox.visibility = View.GONE
                val message = err.message.orEmpty().lineSequence().first()
                MaterialAlertDialogBuilder(this@ComicReaderActivity)
                    .setTitle("无法打开章节")
                    .setMessage(message.ifBlank { "请检查漫画源或网络连接。" })
                    .setPositiveButton("重试") { _, _ -> openChapterSource(chapter, initialRawPage) }
                    .setNegativeButton("返回") { _, _ -> finish() }
                    .show()
                return@launch
            }
            binding.readerLoadingBox.visibility = View.GONE
            pageSource = result.first
            startReadingClock()
            widePageFlags = result.second
            app.db.updateChapterPageCount(chapter.id, result.first.pageCount)
            rebuildSlotsAndRender(initialRawPage)
        }
    }

    private fun scheduleWidePageRebuild() {
        if (widePageRebuildPending) return
        widePageRebuildPending = true
        binding.root.post {
            widePageRebuildPending = false
            if (isDestroyed || pageSource == null || !this::readerConfig.isInitialized) return@post
            val effective = effectiveDoublePageMode()
            if (effective == DoublePageMode.MERGE || (effective == DoublePageMode.SPLIT && !app.prefs.splitAllPages)) {
                val anchor = getCurrentRawPage()
                bitmapCache.evictAll()
                rebuildSlotsAndRender(anchor)
            }
        }
    }

    private fun rebuildSlotsAndRender(targetRawPage: Int) {
        val count = pageSource?.pageCount ?: 0
        if (count <= 0) return
        resetTranslations()

        val effectiveMode = if (readerConfig.scrollMode == ScrollMode.VERTICAL) DoublePageMode.NORMAL else effectiveDoublePageMode()
        slots = SpreadSlotBuilder.buildSlots(
            pageCount = count,
            effectiveMode = effectiveMode,
            firstPageSingle = readerConfig.firstPageSingle,
            mergeRightToLeft = readerConfig.mergeRightToLeft,
            isWidePage = { idx -> idx in widePageFlags.indices && widePageFlags[idx] },
            splitAllPages = app.prefs.splitAllPages
        )

        val slotIndex = SpreadSlotBuilder.findSlotForRawPage(slots, targetRawPage)
        binding.seekPage.max = (slots.size - 1).coerceAtLeast(0)
        updateOverlayActionPills()

        if (readerConfig.scrollMode == ScrollMode.VERTICAL) {
            binding.rvVertical.visibility = View.VISIBLE
            binding.vpHorizontal.visibility = View.GONE
            val lm = LinearLayoutManager(this)
            binding.rvVertical.layoutManager = lm
            binding.rvVertical.setItemViewCacheSize(8)
            (binding.rvVertical.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
            binding.rvVertical.attachLiquidGlassScrollbar()
            binding.rvVertical.adapter = PageSlotAdapter(isVertical = true)
            binding.rvVertical.clearOnScrollListeners()
            binding.rvVertical.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                    val pos = lm.findFirstVisibleItemPosition()
                    if (pos != RecyclerView.NO_POSITION && pos != currentSlotIndex) {
                        onSlotSelected(pos)
                    }
                }
                override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                    if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                        refreshReaderGlassChrome()
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
        refreshReaderGlassChrome()
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
        val slot = slots.getOrNull(clamped)
        val rawTotal = pageSource?.pageCount ?: slots.size
        return when (slot) {
            is PageSlot.Merged -> if (slot.rightRaw != null) {
                "${slot.leftRaw + 1}-${slot.rightRaw + 1}/$rawTotal"
            } else {
                "${slot.leftRaw + 1}/$rawTotal"
            }
            is PageSlot.SplitHalf -> "${slot.rawIndex + 1}/$rawTotal · ${if (slot.leftHalf) "左半页" else "右半页"}"
            is PageSlot.Single -> "${slot.rawIndex + 1}/$rawTotal"
            null -> "${clamped + 1}/${slots.size}"
        }
    }

    private fun onSlotSelected(slotIndex: Int) {
        if (slots.isEmpty()) return
        val nextSlot = slotIndex.coerceIn(0, slots.size - 1)
        if (nextSlot != currentSlotIndex) {
            translationJob?.cancel()
            translationPrefetch?.cancel()
            val keep = nextSlot..(nextSlot + app.prefs.translationPrefetchPages)
            translationTasks.filterKeys { it !in keep }.forEach { (position, task) ->
                translationTasks.remove(position)
                task.cancel()
            }
            translatingView = null
            translationRequest++
        }
        currentSlotIndex = nextSlot
        if (translationEnabled) binding.root.post { translateCurrentPage() }
        val shortLabel = formatSeekShort(currentSlotIndex)
        binding.tvPageIndicator.text = shortLabel
        binding.tvSeekLabel.text = shortLabel
        binding.seekPage.progress = currentSlotIndex
        preloadAdjacent(currentSlotIndex)
    }

    private fun activePageRecycler(): RecyclerView? =
        if (binding.rvVertical.visibility == View.VISIBLE) binding.rvVertical
        else binding.vpHorizontal.getChildAt(0) as? RecyclerView

    private fun translateCurrentPage() {
        if (!readerForeground || !app.prefs.translationModel().acceptsPageImage) return
        val view = activePageRecycler()?.findViewHolderForAdapterPosition(currentSlotIndex)?.itemView as? ZoomableImageView ?: return
        val original = bitmapCache.get(currentSlotIndex) ?: return
        translateVisiblePage(view, original, currentSlotIndex)
    }

    private fun translateVisiblePage(view: ZoomableImageView, original: Bitmap, position: Int) {
        if (!translationEnabled || !readerForeground || !app.prefs.translationModel().acceptsPageImage ||
            position != currentSlotIndex || translatingView === view) return
        translatingView = view
        val request = ++translationRequest
        translationJob?.cancel()
        binding.tvTranslationState.text = "翻译中…"
        binding.tvTranslationState.setOnClickListener(null)
        translationJob = lifecycleScope.launch {
            val result = translatedPages.get(position)?.let { Result.success(it) } ?: requestPageTranslation(position, original).await()
            if (request != translationRequest || view.tag != position || !translationEnabled) return@launch
            result.onSuccess {
                view.setImageBitmap(it.image)
                binding.tvTranslationState.text = if (it.changed) "已显示译文" else "未找到可安全排版的译文"
                prefetchTranslations()
            }.onFailure { error ->
                translatingView = null
                binding.tvTranslationState.text = "${MangaTranslation.describe(error)} · 点击重试"
                binding.tvTranslationState.setOnClickListener { translateCurrentPage() }
            }
        }
    }

    private fun requestPageTranslation(position: Int, loaded: Bitmap? = null): Deferred<Result<PageTranslation>> {
        translationTasks[position]?.let { return it }
        val prefs = app.prefs
        val model = prefs.translationModel()
        val server = prefs.translationServer
        val config = prefs.translationConfig
        lateinit var task: Deferred<Result<PageTranslation>>
        task = lifecycleScope.async(start = CoroutineStart.LAZY) {
            try {
                val outcome = withContext(Dispatchers.IO) { runCatching {
                    val bitmap = loaded ?: renderSlotBitmap(position) ?: error("无法加载漫画页")
                    val translated = MangaTranslation.translate(this@ComicReaderActivity, bitmap, server, config, model,
                        if (model.mode.usesCustomModel) prefs.translationApiKey() else "",
                        if (model.mode.usesTranslationServer) prefs.translationSession() else "")
                    PageTranslation(translated, translated !== bitmap)
                } }
                if (translationTasks[position] === task) outcome.onSuccess { translatedPages.put(position, it) }
                outcome
            } finally {
                if (translationTasks[position] === task) translationTasks.remove(position)
            }
        }
        translationTasks[position] = task
        task.start()
        return task
    }

    private fun prefetchTranslations() {
        translationPrefetch?.cancel()
        val count = app.prefs.translationPrefetchPages
        if (!translationEnabled || !readerForeground || count == 0 || !app.prefs.translationModel().acceptsPageImage) return
        val next = currentSlotIndex + 1
        translationPrefetch = lifecycleScope.launch {
            for (position in next..(next + count - 1).coerceAtMost(slots.lastIndex)) {
                if (translatedPages.get(position) != null) continue
                if (requestPageTranslation(position).await().isFailure) break
            }
        }
    }

    private fun resetTranslations() {
        translationJob?.cancel()
        translationPrefetch?.cancel()
        translationTasks.values.toList().forEach { it.cancel() }
        translationTasks.clear()
        translatedPages.evictAll()
        translatingView = null
        translationRequest++
    }

    private fun showReaderTranslationSettings() {
        val settings = arrayOf("关闭", "后续 1 页", "后续 2 页", "后续 3 页", "后续 4 页", "后续 5 页")
        MaterialAlertDialogBuilder(this).setTitle("预翻译")
            .setSingleChoiceItems(settings, app.prefs.translationPrefetchPages) { dialog, index ->
                app.prefs.translationPrefetchPages = index
                translationPrefetch?.cancel()
                translationTasks.filterKeys { it < currentSlotIndex || it > currentSlotIndex + index }.forEach { (position, task) ->
                    translationTasks.remove(position)
                    task.cancel()
                }
                if (translatedPages.get(currentSlotIndex) != null) prefetchTranslations()
                dialog.dismiss()
            }.setNegativeButton("取消", null).show()
    }

    private fun showTextTranslation() {
        val source = android.widget.EditText(this).apply {
            hint = "输入或粘贴原文"
            minLines = 3
            filters = arrayOf(android.text.InputFilter.LengthFilter(8000))
        }
        val resultView = TextView(this).apply { setTextIsSelectable(true) }
        val body = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val padding = this@ComicReaderActivity.statDp(20)
            setPadding(padding, 0, padding, 0)
            addView(source)
            addView(resultView)
        }
        val dialog = MaterialAlertDialogBuilder(this).setTitle("文字翻译")
            .setView(android.widget.ScrollView(this).apply { addView(body) })
            .setNegativeButton("关闭", null).setPositiveButton("翻译", null).show()
        var job: Job? = null
        dialog.setOnDismissListener { job?.cancel() }
        val button = dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)
        button.setOnClickListener {
            val text = source.text.toString().trim()
            if (text.isEmpty()) { source.error = "请输入原文"; return@setOnClickListener }
            button.isEnabled = false
            resultView.text = "正在翻译…"
            job = lifecycleScope.launch {
                val outcome = withContext(Dispatchers.IO) { runCatching {
                    val prefs = app.prefs
                    MangaTranslation.translateText(this@ComicReaderActivity, prefs.translationConfig, prefs.translationModel(), prefs.translationApiKey(), text)
                } }
                resultView.text = outcome.fold({ it }, MangaTranslation::describe)
                button.isEnabled = true
            }
        }
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
        val ahead = app.prefs.pagePrefetchCount
        if (ahead <= 0 || slots.isEmpty()) return
        val sourceRef = pageSource ?: return
        prefetchJob?.cancel()
        prefetchJob = lifecycleScope.launch(Dispatchers.IO) {
            val targets = buildList {
                for (offset in 1..ahead) {
                    val idx = slotIndex + offset
                    if (idx in slots.indices) add(idx)
                }
                if (slotIndex - 1 in slots.indices) add(slotIndex - 1)
            }
            for (idx in targets) {
                if (sourceRef !== pageSource) break
                if (bitmapCache.get(idx) != null) continue
                prefetchSemaphore.withPermit {
                    if (sourceRef === pageSource && bitmapCache.get(idx) == null) {
                        runCatching { renderSlotBitmap(idx) }
                    }
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
        inFlightSlotJobs[slotIndex]?.let { return runCatching { it.await() }.getOrNull() }
        val deferred = lifecycleScope.async(Dispatchers.IO) { renderSlotBitmapCore(slotIndex) }
        val existing = inFlightSlotJobs.putIfAbsent(slotIndex, deferred)
        if (existing != null) {
            deferred.cancel()
            return runCatching { existing.await() }.getOrNull()
        }
        return try {
            deferred.await()
        } finally {
            inFlightSlotJobs.remove(slotIndex, deferred)
        }
    }

    private suspend fun renderSlotBitmapCore(slotIndex: Int): Bitmap? {
        bitmapCache.get(slotIndex)?.let { return it }
        val src = pageSource ?: return null
        val slot = slots.getOrNull(slotIndex) ?: return null
        val qScale = qualityScaleFactor()
        val reqW = (resources.displayMetrics.widthPixels.coerceAtLeast(1080) * qScale).toInt().coerceAtLeast(540)
        val reqH = (resources.displayMetrics.heightPixels.coerceAtLeast(1080) * qScale).toInt().coerceAtLeast(540)

        val result: Bitmap? = when (slot) {
            is PageSlot.Single -> {
                val raw = loadPage(src, slot.rawIndex, reqW, reqH) ?: return null
                detectWidePageDynamic(slot.rawIndex, raw)
                postProcess(raw)
            }
            is PageSlot.SplitHalf -> {
                val raw = loadPage(src, slot.rawIndex, reqW * 2, reqH) ?: return null
                detectWidePageDynamic(slot.rawIndex, raw)
                val half = ImageProcessor.splitWideBitmap(raw, slot.leftHalf)
                if (half !== raw) raw.recycle()
                postProcess(half)
            }
            is PageSlot.Merged -> {
                val halfReqW = (reqW / 2).coerceAtLeast(720)
                val leftRaw = loadPage(src, slot.leftRaw, halfReqW, reqH) ?: return null
                val leftWide = detectWidePageDynamic(slot.leftRaw, leftRaw)
                val leftClean = postProcess(leftRaw) ?: leftRaw
                val rightRaw = slot.rightRaw?.let { loadPage(src, it, halfReqW, reqH) }
                val rightWide = if (rightRaw != null && slot.rightRaw != null) detectWidePageDynamic(slot.rightRaw, rightRaw) else false
                if (leftWide || rightWide) {
                    scheduleWidePageRebuild()
                }
                if (rightRaw == null) {
                    leftClean
                } else {
                    val rightClean = postProcess(rightRaw) ?: rightRaw
                    val gapPx = if (app.prefs.spreadGap) (6 * resources.displayMetrics.density).toInt() else 0
                    if (readerConfig.mergeRightToLeft) {
                        ImageProcessor.mergeSideBySide(rightClean, leftClean, readerConfig.bgColor.colorInt, gapPx)
                    } else {
                        ImageProcessor.mergeSideBySide(leftClean, rightClean, readerConfig.bgColor.colorInt, gapPx)
                    }
                }
            }
        }
        if (result != null && src === pageSource && slots.getOrNull(slotIndex) == slot) {
            bitmapCache.put(slotIndex, result)
        }
        return result
    }

    private fun detectWidePageDynamic(rawIndex: Int, bmp: Bitmap): Boolean {
        if (rawIndex !in widePageFlags.indices) return false
        if (bmp.width > bmp.height * 1.15f && !widePageFlags[rawIndex]) {
            widePageFlags[rawIndex] = true
            if (effectiveDoublePageMode() == DoublePageMode.MERGE ||
                (effectiveDoublePageMode() == DoublePageMode.SPLIT && !app.prefs.splitAllPages)
            ) {
                scheduleWidePageRebuild()
            }
            return true
        }
        return widePageFlags[rawIndex]
    }

    private fun loadPage(source: ComicPageSource, index: Int, width: Int, height: Int): Bitmap? =
        runCatching { source.loadPageBitmap(index, width, height) }.getOrNull()

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
                    binding.vpHorizontal.setCurrentItem(next, app.prefs.pageTurnAnimation != "none")
                }
            }
        }
    }

    override fun onGenericMotionEvent(event: MotionEvent?): Boolean {
        if (event != null &&
            event.action == MotionEvent.ACTION_SCROLL &&
            (event.source and InputDevice.SOURCE_CLASS_POINTER) != 0 &&
            this::readerConfig.isInitialized &&
            readerConfig.scrollMode != ScrollMode.VERTICAL &&
            app.prefs.wheelPageTurn
        ) {
            val vScroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            if (vScroll != 0f) {
                val now = android.os.SystemClock.uptimeMillis()
                if (now - lastWheelTurnMs >= 135L) {
                    lastWheelTurnMs = now
                    stepPage(if (vScroll < 0f) 1 else -1)
                }
                return true
            }
        }
        return super.onGenericMotionEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (this::readerConfig.isInitialized) {
            if (keyCode == KeyEvent.KEYCODE_ESCAPE) {
                if (binding.readerSettingsOverlay.visibility == View.VISIBLE) {
                    closeReaderSettingsPanel()
                } else {
                    finish()
                }
                return true
            }
            if (readerConfig.scrollMode != ScrollMode.VERTICAL) {
                val rtl = readerConfig.scrollMode == ScrollMode.RTL
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        stepPage(if (rtl) -1 else 1)
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        stepPage(if (rtl) 1 else -1)
                        return true
                    }
                    KeyEvent.KEYCODE_SPACE -> {
                        stepPage(1)
                        return true
                    }
                }
            }
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
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun toggleToolbar() {
        setToolbarVisible(!toolbarVisible, animate = true)
    }

    private fun setToolbarVisible(visible: Boolean, animate: Boolean = true) {
        toolbarVisible = visible
        binding.topOverlay.animate().cancel()
        binding.bottomOverlay.animate().cancel()
        val smooth = animate && android.animation.ValueAnimator.areAnimatorsEnabled()
        if (visible) {
            binding.topOverlay.visibility = View.VISIBLE
            binding.bottomOverlay.visibility = View.VISIBLE
            refreshReaderGlassChrome()
            if (smooth) {
                val topH = (if (binding.topOverlay.height > 0) binding.topOverlay.height else (72 * resources.displayMetrics.density).toInt()).toFloat()
                val botH = (if (binding.bottomOverlay.height > 0) binding.bottomOverlay.height else (124 * resources.displayMetrics.density).toInt()).toFloat()
                binding.topOverlay.translationY = -topH
                binding.topOverlay.alpha = 0f
                binding.topOverlay.animate().translationY(0f).alpha(1f).setDuration(260L).setInterpolator(easeOutCubic).start()
                binding.bottomOverlay.translationY = botH
                binding.bottomOverlay.alpha = 0f
                binding.bottomOverlay.animate().translationY(0f).alpha(1f).setDuration(320L).setInterpolator(easeOutCubic).start()
            } else {
                binding.topOverlay.translationY = 0f
                binding.topOverlay.alpha = 1f
                binding.bottomOverlay.translationY = 0f
                binding.bottomOverlay.alpha = 1f
            }
        } else {
            if (smooth && binding.bottomOverlay.visibility == View.VISIBLE) {
                val topH = (if (binding.topOverlay.height > 0) binding.topOverlay.height else (72 * resources.displayMetrics.density).toInt()).toFloat()
                val botH = (if (binding.bottomOverlay.height > 0) binding.bottomOverlay.height else (124 * resources.displayMetrics.density).toInt()).toFloat()
                binding.topOverlay.animate().translationY(-topH).alpha(0f).setDuration(200L).setInterpolator(easeInCubic)
                    .withEndAction { if (!toolbarVisible) binding.topOverlay.visibility = View.GONE }.start()
                binding.bottomOverlay.animate().translationY(botH).alpha(0f).setDuration(230L).setInterpolator(easeInCubic)
                    .withEndAction { if (!toolbarVisible) binding.bottomOverlay.visibility = View.GONE }.start()
            } else {
                binding.topOverlay.visibility = View.GONE
                binding.bottomOverlay.visibility = View.GONE
            }
        }
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
        binding.translationBall.visibility = View.GONE
        val slidePx = 360f * resources.displayMetrics.density
        binding.readerSettingsPanel.translationX = slidePx
        binding.readerSettingsPanel.alpha = 0f
        binding.readerSettingsPanel.animate()
            .translationX(0f)
            .alpha(1f)
            .setDuration(260L)
            .setInterpolator(easeOutCubic)
            .withEndAction { refreshReaderGlassChrome() }
            .start()
    }

    private fun closeReaderSettingsPanel() {
        binding.readerSettingsPanel.animate().cancel()
        val slidePx = 360f * resources.displayMetrics.density
        if (android.animation.ValueAnimator.areAnimatorsEnabled() && binding.readerSettingsOverlay.visibility == View.VISIBLE) {
            binding.readerSettingsPanel.animate()
                .translationX(slidePx)
                .alpha(0f)
                .setDuration(200L)
                .setInterpolator(easeInCubic)
                .withEndAction {
                    binding.readerSettingsOverlay.visibility = View.GONE
                    if (translationEnabled) {
                        binding.translationBall.visibility = View.VISIBLE
                        refreshReaderGlassChrome()
                    }
                }
                .start()
        } else {
            binding.readerSettingsOverlay.visibility = View.GONE
            if (translationEnabled) binding.translationBall.visibility = View.VISIBLE
        }
    }

    private fun setupReaderSettingsDrawer() {
        binding.readerSettingsOverlay.setOnClickListener { closeReaderSettingsPanel() }
        binding.readerSettingsPanel.setOnClickListener { /* consume click */ }
        binding.btnCloseDrawer.setOnClickListener { closeReaderSettingsPanel() }

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
        binding.btnPageSplit.setOnClickListener { selectDoublePage(DoublePageMode.SPLIT) }
        binding.btnPageAuto.setOnClickListener { selectDoublePage(DoublePageMode.AUTO_LANDSCAPE) }

        val selectAnim: (String) -> Unit = { key ->
            app.prefs.pageTurnAnimation = key
            applyPageTurnTransformer()
            syncReaderSettingsPanelState()
        }
        binding.btnAnimNone.setOnClickListener { selectAnim("none") }
        binding.btnAnimFade.setOnClickListener { selectAnim("fade") }
        binding.btnAnimSlide.setOnClickListener { selectAnim("slide") }
        binding.btnAnimCover.setOnClickListener { selectAnim("cover") }
        binding.btnAnimCurl.setOnClickListener { selectAnim("curl") }
        binding.btnAnimDepth.setOnClickListener { selectAnim("depth") }

        val selectPrefetch: (Int) -> Unit = { count ->
            app.prefs.pagePrefetchCount = count
            syncReaderSettingsPanelState()
            preloadAdjacent(currentSlotIndex)
        }
        binding.btnPrefetch0.setOnClickListener { selectPrefetch(0) }
        binding.btnPrefetch2.setOnClickListener { selectPrefetch(2) }
        binding.btnPrefetch4.setOnClickListener { selectPrefetch(4) }
        binding.btnPrefetch6.setOnClickListener { selectPrefetch(6) }
        binding.btnPrefetch8.setOnClickListener { selectPrefetch(8) }

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
        binding.btnReaderTranslationSettings.setOnClickListener { showReaderTranslationSettings() }
    }

    private fun syncBrightnessBadge() {
        val isAuto = app.prefs.readerBrightness < 0
        binding.btnBrightnessAuto.setBackgroundResource(
            if (isAuto) R.drawable.bg_reader_seg_selected else R.drawable.bg_reader_seg_unselected
        )
        binding.btnBrightnessAuto.setTextColor(if (isAuto) 0xFF7DCEFF.toInt() else 0xFFCBD5E1.toInt())
    }

    private fun stylePill(tv: TextView, selected: Boolean) {
        tv.setBackgroundResource(if (selected) R.drawable.bg_reader_seg_selected else R.drawable.bg_reader_seg_unselected)
        tv.setTextColor(if (selected) 0xFF7DCEFF.toInt() else 0xFFCBD5E1.toInt())
    }

    private fun styleModeCard(container: View, icon: ImageView, label: TextView, selected: Boolean) {
        container.setBackgroundResource(if (selected) R.drawable.bg_reader_seg_selected else R.drawable.bg_reader_seg_unselected)
        val tint = if (selected) 0xFF7DCEFF.toInt() else 0xFFCBD5E1.toInt()
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
        stylePill(binding.btnPageSplit, readerConfig.doublePageMode == DoublePageMode.SPLIT)
        stylePill(binding.btnPageAuto, readerConfig.doublePageMode == DoublePageMode.AUTO_LANDSCAPE)

        val anim = app.prefs.pageTurnAnimation
        stylePill(binding.btnAnimNone, anim == "none")
        stylePill(binding.btnAnimFade, anim == "fade")
        stylePill(binding.btnAnimSlide, anim == "slide")
        stylePill(binding.btnAnimCover, anim == "cover")
        stylePill(binding.btnAnimCurl, anim == "curl")
        stylePill(binding.btnAnimDepth, anim == "depth")

        val prefCount = app.prefs.pagePrefetchCount
        stylePill(binding.btnPrefetch0, prefCount == 0)
        stylePill(binding.btnPrefetch2, prefCount == 2)
        stylePill(binding.btnPrefetch4, prefCount == 4)
        stylePill(binding.btnPrefetch6, prefCount == 6)
        stylePill(binding.btnPrefetch8, prefCount == 8)

        stylePill(binding.btnSpreadOdd, readerConfig.firstPageSingle)
        stylePill(binding.btnSpreadEven, !readerConfig.firstPageSingle)

        stylePill(binding.btnBgBlack, readerConfig.bgColor == ReaderBgColor.BLACK)
        stylePill(binding.btnBgDarkGray, readerConfig.bgColor == ReaderBgColor.DARK_GRAY)
        stylePill(binding.btnBgLightGray, readerConfig.bgColor == ReaderBgColor.LIGHT_GRAY)
        stylePill(binding.btnBgWhite, readerConfig.bgColor == ReaderBgColor.WHITE)

        binding.swSpreadGap.setOnCheckedChangeListener(null)
        binding.swSpreadGap.isChecked = app.prefs.spreadGap
        binding.swSpreadGap.setOnCheckedChangeListener { _, isChecked ->
            app.prefs.spreadGap = isChecked
            bitmapCache.evictAll()
            rebuildSlotsAndRender(getCurrentRawPage())
        }

        binding.swSplitAllPages.setOnCheckedChangeListener(null)
        binding.swSplitAllPages.isChecked = app.prefs.splitAllPages
        binding.swSplitAllPages.setOnCheckedChangeListener { _, isChecked ->
            app.prefs.splitAllPages = isChecked
            bitmapCache.evictAll()
            rebuildSlotsAndRender(getCurrentRawPage())
        }

        binding.swWheelPageTurn.setOnCheckedChangeListener(null)
        binding.swWheelPageTurn.isChecked = app.prefs.wheelPageTurn
        binding.swWheelPageTurn.setOnCheckedChangeListener { _, isChecked ->
            app.prefs.wheelPageTurn = isChecked
        }

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

        private val verticalPlaceholderHeight = (resources.displayMetrics.widthPixels * 1.35f).toInt().coerceAtLeast(480)

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

        override fun onViewRecycled(holder: PageVH) {
            super.onViewRecycled(holder)
            pageBindJobs.remove(holder.imageView)?.cancel()
        }

        override fun onBindViewHolder(holder: PageVH, position: Int) {
            val view = holder.imageView
            pageBindJobs.remove(view)?.cancel()
            view.tag = position
            view.doubleTapZoomEnabled = app.prefs.allowDoubleTapZoom
            view.onSingleTapAt = { rx, ry -> handleTapAt(rx, ry) }
            if (isVertical) {
                view.adjustViewBounds = true
                view.scaleType = ImageView.ScaleType.FIT_CENTER
            }

            // Fast synchronous path from memory cache -> zero layout collapse & butter-smooth vertical scroll!
            val translated = if (translationEnabled) translatedPages.get(position)?.image else null
            val cached = translated ?: bitmapCache.get(position)
            if (cached != null && !cached.isRecycled) {
                if (isVertical) view.minimumHeight = 0
                view.setImageBitmap(cached)
                if (!isVertical) view.resetZoom()
                if (translationEnabled && translated == null) {
                    translateVisiblePage(view, cached, position)
                }
                return
            }

            if (isVertical) view.minimumHeight = verticalPlaceholderHeight
            view.setImageDrawable(null)
            val source = pageSource
            pageBindJobs[view] = lifecycleScope.launch {
                val bmp = withContext(Dispatchers.IO) { renderSlotBitmap(position) }
                if (view.tag != position || source !== pageSource) return@launch
                if (bmp != null) {
                    if (isVertical) view.minimumHeight = 0
                    view.setImageBitmap(bmp)
                    translateVisiblePage(view, bmp, position)
                    if (!isVertical) view.resetZoom()
                } else {
                    view.onSingleTapAt = { _, _ -> notifyItemChanged(position) }
                    if (!pageErrorShown && position == currentSlotIndex) {
                        pageErrorShown = true
                        Toast.makeText(this@ComicReaderActivity, "图片加载失败，点击本页重试", Toast.LENGTH_SHORT).show()
                    }
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
