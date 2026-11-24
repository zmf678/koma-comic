package com.arkreader.comic.ui

import android.content.Intent
import android.os.Bundle
import android.text.format.Formatter
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.arkreader.comic.ArkReaderApp
import com.arkreader.comic.data.GridSize
import com.arkreader.comic.databinding.ActivitySettingsBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private val app get() = ArkReaderApp.instance

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }
        renderSettings()
    }

    private fun renderSettings() {
        val root = binding.settingsContainer
        root.removeAllViews()
        val prefs = app.prefs

        addSectionTitle("外观与主题")
        val modeLabels = arrayOf("跟随系统", "浅色模式", "深色模式")
        addActionRow("明暗主题", modeLabels.getOrElse(prefs.themeMode) { "跟随系统" }) {
            MaterialAlertDialogBuilder(this)
                .setTitle("选择明暗主题")
                .setSingleChoiceItems(modeLabels, prefs.themeMode) { dialog, which ->
                    prefs.themeMode = which
                    app.applyNightMode(which)
                    dialog.dismiss()
                    recreate()
                }
                .show()
        }

        val themeIdx = prefs.themeColorIndex.coerceIn(0, ThemeHelper.THEME_NAMES.lastIndex)
        addActionRow("主题色方案", ThemeHelper.THEME_NAMES[themeIdx]) {
            MaterialAlertDialogBuilder(this)
                .setTitle("选择主题色")
                .setSingleChoiceItems(ThemeHelper.THEME_NAMES, themeIdx) { dialog, which ->
                    prefs.themeColorIndex = which
                    dialog.dismiss()
                    recreate()
                }
                .show()
        }

        addDivider()
        addSectionTitle("列表与交互")
        addActionRow("列表视图大小", prefs.gridSize.label) {
            val grids = GridSize.values()
            MaterialAlertDialogBuilder(this)
                .setTitle("列表视图大小")
                .setSingleChoiceItems(grids.map { it.label }.toTypedArray(), grids.indexOf(prefs.gridSize)) { d, w ->
                    prefs.gridSize = grids[w]
                    d.dismiss()
                    renderSettings()
                }
                .show()
        }
        addSwitchRow("双行显示标题", prefs.doubleLineTitle) { prefs.doubleLineTitle = it }
        addSwitchRow("仅展示封面（隐藏标题）", prefs.onlyShowCover) { prefs.onlyShowCover = it }
        addSwitchRow("点击漫画跳过详情页直接阅读", prefs.skipDetailPage) { prefs.skipDetailPage = it }
        addSwitchRow("翻页触感反馈", prefs.hapticFeedback) { prefs.hapticFeedback = it }

        addDivider()
        addSectionTitle("管理入口")
        addActionRow("作品集管理与加密", "新建、隐藏或为作品集设置 PIN / 指纹锁") {
            startActivity(Intent(this, BookshelfManageActivity::class.java))
        }
        addActionRow("默认与自定义阅读配置", "双页拆分、白边裁剪、点击区与方向") {
            startActivity(Intent(this, ReaderConfigActivity::class.java))
        }
        addActionRow("在线漫画源管理", "内置动漫源、订阅源仓库、导入自定义 JS 源") {
            startActivity(Intent(this, SourceManageActivity::class.java))
        }
        addActionRow("仓库管理", "本地 SAF 目录与 WebDAV") {
            startActivity(Intent(this, WarehouseActivity::class.java))
        }

        addDivider()
        addSectionTitle("缓存与关于")
        val cacheSize = Formatter.formatFileSize(this, app.coverCache.getTotalCacheBytes())
        addActionRow("清理封面与压缩包临时缓存", "当前占用: $cacheSize") {
            app.coverCache.clearCovers()
            app.coverCache.clearArchives()
            Toast.makeText(this, "缓存已清理", Toast.LENGTH_SHORT).show()
            renderSettings()
        }
        val versionName = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "1.5.7"
        }.getOrDefault("1.5.7")
        addActionRow("关于页光 Koma", "开源本地 & WebDAV 漫画阅读器 (v$versionName)") {
            MaterialAlertDialogBuilder(this)
                .setTitle("关于页光 Koma")
                .setMessage("纯本地、无广告、无时限的平板与手机漫画阅读器 (v$versionName)。\n支持文件夹 / ZIP / CBZ / PDF / EPUB 与 WebDAV。")
                .setPositiveButton("确定", null)
                .show()
        }
    }

    private fun addSectionTitle(title: String) {
        val dp = resources.displayMetrics.density
        val tv = TextView(this).apply {
            text = title
            textSize = 13f
            alpha = 0.7f
            setPadding(0, (10 * dp).toInt(), 0, (4 * dp).toInt())
        }
        binding.settingsContainer.addView(tv)
    }

    private fun addActionRow(title: String, subtitle: String, onClick: () -> Unit) {
        val dp = resources.displayMetrics.density
        val tv = TextView(this).apply {
            text = "$title\n$subtitle"
            textSize = 15f
            setPadding(0, (12 * dp).toInt(), 0, (12 * dp).toInt())
            setOnClickListener { onClick() }
        }
        binding.settingsContainer.addView(tv)
    }

    private fun addSwitchRow(title: String, checked: Boolean, onChanged: (Boolean) -> Unit) {
        val dp = resources.displayMetrics.density
        val sw = MaterialSwitch(this).apply {
            text = title
            isChecked = checked
            setPadding(0, (6 * dp).toInt(), 0, (6 * dp).toInt())
            setOnCheckedChangeListener { _, isChecked -> onChanged(isChecked) }
        }
        binding.settingsContainer.addView(sw)
    }

    private fun addDivider() {
        val dp = resources.displayMetrics.density
        val div = View(this).apply {
            setBackgroundColor(0x22888888)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (1 * dp).toInt()
            ).apply {
                topMargin = (6 * dp).toInt()
                bottomMargin = (6 * dp).toInt()
            }
        }
        binding.settingsContainer.addView(div)
    }
}
