package com.arkreader.comic.ui

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.arkreader.comic.ArkReaderApp
import com.arkreader.comic.data.ClickFlipMode
import com.arkreader.comic.data.DoublePageMode
import com.arkreader.comic.data.ReaderBgColor
import com.arkreader.comic.data.ReaderConfig
import com.arkreader.comic.data.ReaderOrientation
import com.arkreader.comic.data.ScrollMode
import com.arkreader.comic.databinding.ActivityReaderConfigBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider

class ReaderConfigActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReaderConfigBinding
    private val app get() = ArkReaderApp.instance
    private var configs: List<ReaderConfig> = emptyList()
    private var currentConfig: ReaderConfig = ReaderConfig()

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityReaderConfigBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }
        reloadConfigs(selectId = 1L)
    }

    private fun reloadConfigs(selectId: Long) {
        configs = app.db.getReaderConfigs()
        currentConfig = configs.find { it.id == selectId } ?: configs.firstOrNull() ?: ReaderConfig()
        renderForm()
    }

    private fun renderForm() {
        val root = binding.configContainer
        root.removeAllViews()

        val dp = resources.displayMetrics.density
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, (12 * dp).toInt())
        }
        val btnSwitch = Button(this).apply {
            text = "配置: ${currentConfig.name}"
            setOnClickListener { showSelectConfigDialog() }
        }
        val btnNew = Button(this).apply {
            text = "+ 新建"
            setOnClickListener { showCreateConfigDialog() }
        }
        val btnReset = Button(this).apply {
            text = "重置"
            setOnClickListener {
                val reset = ReaderConfig(id = currentConfig.id, name = currentConfig.name)
                app.db.upsertReaderConfig(reset)
                reloadConfigs(reset.id)
            }
        }
        headerRow.addView(btnSwitch, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        headerRow.addView(btnNew)
        headerRow.addView(btnReset)
        root.addView(headerRow)

        if (currentConfig.id > 1L) {
            val btnDel = Button(this).apply {
                text = "删除此配置"
                setOnClickListener {
                    app.db.deleteReaderConfig(currentConfig.id)
                    reloadConfigs(1L)
                }
            }
            root.addView(btnDel)
        }

        addChoiceRow("滚动/翻页方向", currentConfig.scrollMode.label) {
            val vals = ScrollMode.values()
            MaterialAlertDialogBuilder(this)
                .setTitle("滚动/翻页方向")
                .setItems(vals.map { it.label }.toTypedArray()) { _, i ->
                    updateAndSave(currentConfig.copy(scrollMode = vals[i]))
                }
                .show()
        }

        addChoiceRow("双页模式", currentConfig.doublePageMode.label) {
            val vals = DoublePageMode.values()
            MaterialAlertDialogBuilder(this)
                .setTitle("双页模式")
                .setItems(vals.map { it.label }.toTypedArray()) { _, i ->
                    updateAndSave(currentConfig.copy(doublePageMode = vals[i]))
                }
                .show()
        }

        addSwitchRow("首页单独显示（封面不拆分/合并）", currentConfig.firstPageSingle) {
            updateAndSave(currentConfig.copy(firstPageSingle = it))
        }
        addSwitchRow("双页合并/拆分按从右到左顺序（日漫）", currentConfig.mergeRightToLeft) {
            updateAndSave(currentConfig.copy(mergeRightToLeft = it))
        }

        addDivider()
        addSwitchRow("智能裁剪白边", currentConfig.trimMargins) {
            updateAndSave(currentConfig.copy(trimMargins = it))
        }
        addSliderRow("去白边强度", currentConfig.trimStrength.toFloat(), 5f, 45f, 1f) { v ->
            updateAndSave(currentConfig.copy(trimStrength = v.toInt()), rerender = false)
        }
        addSwitchRow("去白边忽略两侧（仅去上下或按设置保护）", currentConfig.trimIgnoreSides) {
            updateAndSave(currentConfig.copy(trimIgnoreSides = it))
        }
        addSwitchRow("白边保护（单边最大裁剪 10%）", currentConfig.trimProtect) {
            updateAndSave(currentConfig.copy(trimProtect = it))
        }
        addSwitchRow("跳过空白页标记检测", currentConfig.skipBlankPages) {
            updateAndSave(currentConfig.copy(skipBlankPages = it))
        }

        addDivider()
        addSwitchRow("启用点击翻页", currentConfig.clickFlipEnabled) {
            updateAndSave(currentConfig.copy(clickFlipEnabled = it))
        }
        addChoiceRow("点击翻页区域分布", currentConfig.clickFlipMode.label) {
            val vals = ClickFlipMode.values()
            MaterialAlertDialogBuilder(this)
                .setTitle("点击翻页模式")
                .setItems(vals.map { it.label }.toTypedArray()) { _, i ->
                    updateAndSave(currentConfig.copy(clickFlipMode = vals[i]))
                }
                .show()
        }
        addSwitchRow("互换点击翻页左右方向", currentConfig.swapClickFlip) {
            updateAndSave(currentConfig.copy(swapClickFlip = it))
        }
        addSwitchRow("音量键翻页", currentConfig.volumeKeyFlip) {
            updateAndSave(currentConfig.copy(volumeKeyFlip = it))
        }

        addDivider()
        addChoiceRow("屏幕方向", currentConfig.orientation.label) {
            val vals = ReaderOrientation.values()
            MaterialAlertDialogBuilder(this)
                .setTitle("阅读器屏幕方向")
                .setItems(vals.map { it.label }.toTypedArray()) { _, i ->
                    updateAndSave(currentConfig.copy(orientation = vals[i]))
                }
                .show()
        }
        addChoiceRow("阅读背景色", currentConfig.bgColor.label) {
            val vals = ReaderBgColor.values()
            MaterialAlertDialogBuilder(this)
                .setTitle("阅读背景色")
                .setItems(vals.map { it.label }.toTypedArray()) { _, i ->
                    updateAndSave(currentConfig.copy(bgColor = vals[i]))
                }
                .show()
        }
        addSliderRow("左右留白边距 (dp)", currentConfig.sideMarginDp.toFloat(), 0f, 96f, 4f) { v ->
            updateAndSave(currentConfig.copy(sideMarginDp = v.toInt()), rerender = false)
        }
        addSliderRow("PDF 渲染清晰度倍率", currentConfig.pdfScale, 1.0f, 3.0f, 0.5f) { v ->
            updateAndSave(currentConfig.copy(pdfScale = v), rerender = false)
        }
        addSwitchRow("阅读时保持屏幕常亮", currentConfig.keepScreenOn) {
            updateAndSave(currentConfig.copy(keepScreenOn = it))
        }
        addSwitchRow("显示右上/底部页码指示", currentConfig.showPageNumber) {
            updateAndSave(currentConfig.copy(showPageNumber = it))
        }
        addSwitchRow("打开阅读器时显示工具栏", currentConfig.showToolbarOnOpen) {
            updateAndSave(currentConfig.copy(showToolbarOnOpen = it))
        }
    }

    private fun updateAndSave(next: ReaderConfig, rerender: Boolean = true) {
        currentConfig = next
        app.db.upsertReaderConfig(next)
        if (rerender) renderForm()
    }

    private fun addChoiceRow(title: String, value: String, onClick: () -> Unit) {
        val dp = resources.displayMetrics.density
        val tv = TextView(this).apply {
            text = "$title：$value"
            textSize = 15f
            setPadding((4 * dp).toInt(), (14 * dp).toInt(), (4 * dp).toInt(), (14 * dp).toInt())
            setOnClickListener { onClick() }
        }
        binding.configContainer.addView(tv)
    }

    private fun addSwitchRow(title: String, checked: Boolean, onChanged: (Boolean) -> Unit) {
        val dp = resources.displayMetrics.density
        val sw = MaterialSwitch(this).apply {
            text = title
            isChecked = checked
            setPadding(0, (8 * dp).toInt(), 0, (8 * dp).toInt())
            setOnCheckedChangeListener { _, isChecked -> onChanged(isChecked) }
        }
        binding.configContainer.addView(sw)
    }

    private fun addSliderRow(title: String, initial: Float, min: Float, max: Float, step: Float, onChanged: (Float) -> Unit) {
        val tv = TextView(this).apply {
            text = "$title: $initial"
            textSize = 14f
        }
        val slider = Slider(this).apply {
            valueFrom = min
            valueTo = max
            stepSize = step
            value = initial.coerceIn(min, max)
            addOnChangeListener { _, v, fromUser ->
                tv.text = "$title: $v"
                if (fromUser) onChanged(v)
            }
        }
        binding.configContainer.addView(tv)
        binding.configContainer.addView(slider)
    }

    private fun addDivider() {
        val dp = resources.displayMetrics.density
        val div = View(this).apply {
            setBackgroundColor(0x22888888)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (1 * dp).toInt()
            ).apply {
                topMargin = (8 * dp).toInt()
                bottomMargin = (8 * dp).toInt()
            }
        }
        binding.configContainer.addView(div)
    }

    private fun showSelectConfigDialog() {
        val names = configs.map { it.name }.toTypedArray()
        val idx = configs.indexOfFirst { it.id == currentConfig.id }.coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle("选择配置")
            .setSingleChoiceItems(names, idx) { dialog, which ->
                dialog.dismiss()
                reloadConfigs(configs[which].id)
            }
            .show()
    }

    private fun showCreateConfigDialog() {
        val input = EditText(this).apply { hint = "输入新阅读配置名称（如：日漫竖屏）" }
        MaterialAlertDialogBuilder(this)
            .setTitle("新建阅读配置")
            .setView(input)
            .setPositiveButton("创建") { _, _ ->
                val name = input.text?.toString()?.trim().orEmpty()
                if (name.isBlank()) {
                    Toast.makeText(this, "名称不能为空", Toast.LENGTH_SHORT).show()
                } else {
                    val newId = app.db.upsertReaderConfig(currentConfig.copy(id = 0L, name = name))
                    reloadConfigs(newId)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
