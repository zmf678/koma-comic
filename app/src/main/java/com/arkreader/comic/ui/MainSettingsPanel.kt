package com.arkreader.comic.ui

import android.content.Intent
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.arkreader.comic.ArkReaderApp
import com.arkreader.comic.R
import com.arkreader.comic.data.GridSize
import com.arkreader.comic.databinding.PanelMainSettingsBinding
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import java.util.Locale

/**
 * Embedded Settings controller displayed in the right pane of MainActivity
 * so the left NavigationRail / bottom bar stays visible.
 */
class MainSettingsPanel(
    private val activity: AppCompatActivity,
    private val binding: PanelMainSettingsBinding,
    private val onGridPrefsChanged: () -> Unit
) {
    private val app get() = ArkReaderApp.instance

    fun refresh() {
        val root = binding.mainSettingsContainer
        root.removeAllViews()
        val prefs = app.prefs

        addSectionTitle(root, "外观与显示")
        addCardGroup(root) { group ->
            val themeModes = arrayOf("跟随系统", "浅色模式", "深色模式")
            addActionRow(group, "深色模式", themeModes.getOrElse(prefs.themeMode) { "跟随系统" }) {
                MaterialAlertDialogBuilder(activity)
                    .setTitle("选择深色模式")
                    .setSingleChoiceItems(themeModes, prefs.themeMode) { d, which ->
                        prefs.themeMode = which
                        app.applyNightMode(which)
                        d.dismiss()
                        activity.recreate()
                    }
                    .show()
            }
            addDivider(group)

            addActionRow(group, "主题色彩", ThemeHelper.THEME_NAMES.getOrElse(prefs.themeColorIndex) { "赤" }) {
                MaterialAlertDialogBuilder(activity)
                    .setTitle("选择主题色彩")
                    .setSingleChoiceItems(ThemeHelper.THEME_NAMES, prefs.themeColorIndex) { d, which ->
                        prefs.themeColorIndex = which
                        d.dismiss()
                        activity.recreate()
                    }
                    .show()
            }
            addDivider(group)

            val grids = GridSize.values()
            addActionRow(group, "列表视图大小", prefs.gridSize.label) {
                val labels = grids.map { it.label }.toTypedArray()
                val cur = grids.indexOf(prefs.gridSize).coerceAtLeast(0)
                MaterialAlertDialogBuilder(activity)
                    .setTitle("选择视图大小")
                    .setSingleChoiceItems(labels, cur) { d, which ->
                        prefs.gridSize = grids[which]
                        d.dismiss()
                        refresh()
                        onGridPrefsChanged()
                    }
                    .show()
            }
            addDivider(group)

            addSwitchRow(group, "双行显示标题", "漫画卡片标题最多展示两行", prefs.doubleLineTitle) {
                prefs.doubleLineTitle = it
                onGridPrefsChanged()
            }
            addDivider(group)

            addSwitchRow(group, "仅显示封面", "隐藏卡片底部标题文字", prefs.onlyShowCover) {
                prefs.onlyShowCover = it
                onGridPrefsChanged()
            }
        }

        addSectionTitle(root, "阅读与交互")
        addCardGroup(root) { group ->
            addSwitchRow(group, "直接打开阅读", "点击本地漫画跳过详情页直接阅读", prefs.skipDetailPage) {
                prefs.skipDetailPage = it
            }
            addDivider(group)

            addSwitchRow(group, "触觉反馈", "翻页与工具栏交互时提供震动反馈", prefs.hapticFeedback) {
                prefs.hapticFeedback = it
            }
            addDivider(group)

            addActionRow(group, "阅读器默认配置", "配置翻页方式、双页规则与点击区域") {
                activity.startActivity(Intent(activity, ReaderConfigActivity::class.java))
            }
        }

        addSectionTitle(root, "数据源与作品集")
        addCardGroup(root) { group ->
            val allSources = app.db.getComicSources(onlyEnabled = false)
            val enabledCount = allSources.count { it.enabled }
            addActionRow(
                group,
                "在线漫画源管理",
                "已启用 $enabledCount / ${allSources.size} 个漫画源（支持仓库订阅与账号登录）"
            ) {
                activity.startActivity(Intent(activity, SourceManageActivity::class.java))
            }
            addDivider(group)

            val shelfCount = app.db.getBookshelves().size
            addActionRow(group, "作品集与隐私锁", "共 $shelfCount 个作品集，支持 PIN 与指纹锁定") {
                activity.startActivity(Intent(activity, BookshelfManageActivity::class.java))
            }
        }

        addSectionTitle(root, "存储与关于")
        addCardGroup(root) { group ->
            val mb = app.coverCache.getTotalCacheBytes() / (1024f * 1024f)
            addActionRow(group, "清除图片与压缩包缓存", String.format(Locale.getDefault(), "当前占用 %.1f MB", mb)) {
                MaterialAlertDialogBuilder(activity)
                    .setTitle("清除缓存")
                    .setMessage("确认清除封面与在线图片缓存？阅读记录和本地文件不会受到影响。")
                    .setPositiveButton("清除") { _, _ ->
                        app.coverCache.clearCovers()
                        app.coverCache.clearArchives()
                        Toast.makeText(activity, "缓存已清除", Toast.LENGTH_SHORT).show()
                        refresh()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
            addDivider(group)

            val versionName = runCatching {
                activity.packageManager.getPackageInfo(activity.packageName, 0).versionName ?: "1.0"
            }.getOrDefault("1.0")
            addActionRow(group, "关于 漫格漫画 KomaComic", "版本 v$versionName") {
                Toast.makeText(activity, "漫格漫画 KomaComic v$versionName", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun dp(v: Int): Int = (v * activity.resources.displayMetrics.density).toInt()

    private fun addSectionTitle(parent: LinearLayout, title: String) {
        val tv = TextView(activity).apply {
            text = title
            textSize = 13f
            setTextColor(ContextCompat.getColor(activity, R.color.text_secondary))
            setPadding(dp(6), dp(14), dp(6), dp(6))
        }
        parent.addView(tv)
    }

    private fun addCardGroup(parent: LinearLayout, fill: (LinearLayout) -> Unit) {
        val card = MaterialCardView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6) }
            setCardBackgroundColor(ContextCompat.getColor(activity, R.color.surface_card))
            radius = dp(16).toFloat()
            cardElevation = 0f
            strokeWidth = 0
        }
        val inner = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        fill(inner)
        card.addView(inner)
        parent.addView(card)
    }

    private fun addDivider(parent: LinearLayout) {
        val line = View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                1
            ).apply {
                marginStart = dp(18)
                marginEnd = dp(18)
            }
            setBackgroundColor(0x1F888888)
        }
        parent.addView(line)
    }

    private fun addActionRow(
        parent: LinearLayout,
        title: String,
        subtitle: String,
        onClick: () -> Unit
    ) {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
            isClickable = true
            isFocusable = true
            val ta = activity.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
            background = ta.getDrawable(0)
            ta.recycle()
            setOnClickListener { onClick() }
        }
        val tvTitle = TextView(activity).apply {
            text = title
            textSize = 15f
            setTextColor(ContextCompat.getColor(activity, R.color.text_primary))
        }
        val tvSub = TextView(activity).apply {
            text = subtitle
            textSize = 12.5f
            setPadding(0, dp(3), 0, 0)
            setTextColor(ContextCompat.getColor(activity, R.color.text_secondary))
        }
        row.addView(tvTitle)
        row.addView(tvSub)
        parent.addView(row)
    }

    private fun addSwitchRow(
        parent: LinearLayout,
        title: String,
        subtitle: String,
        checked: Boolean,
        onToggle: (Boolean) -> Unit
    ) {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(12))
        }
        val textCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(12)
            }
        }
        textCol.addView(TextView(activity).apply {
            text = title
            textSize = 15f
            setTextColor(ContextCompat.getColor(activity, R.color.text_primary))
        })
        textCol.addView(TextView(activity).apply {
            text = subtitle
            textSize = 12.5f
            setPadding(0, dp(2), 0, 0)
            setTextColor(ContextCompat.getColor(activity, R.color.text_secondary))
        })
        val sw = MaterialSwitch(activity).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, isChecked -> onToggle(isChecked) }
        }
        row.setOnClickListener { sw.toggle() }
        row.addView(textCol)
        row.addView(sw)
        parent.addView(row)
    }
}
