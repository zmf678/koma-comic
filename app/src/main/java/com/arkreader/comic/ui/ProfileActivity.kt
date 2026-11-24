package com.arkreader.comic.ui

import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputFilter
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
import androidx.lifecycle.lifecycleScope
import com.arkreader.comic.ArkReaderApp
import com.arkreader.comic.R
import com.arkreader.comic.data.ReadingTime
import com.arkreader.comic.engine.GithubFeedback
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

internal fun ImageView.showProfileAvatar() {
    val density = resources.displayMetrics.density
    val keepGlassRing = background is LiquidGlassDrawable
    if (!keepGlassRing) {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(context.getColor(R.color.surface_card))
            setStroke((1f * density).toInt().coerceAtLeast(1), context.getColor(R.color.surface_border))
        }
    }
    clipToOutline = true
    val file = File(context.filesDir, "profile-avatar.jpg")
    val bitmap = if (file.exists()) BitmapFactory.decodeFile(file.absolutePath) else null
    if (bitmap != null) {
        // Leave a 3dp circular Liquid Glass ring around the avatar when glass is active (matching Windows PC)
        val ringPad = if (keepGlassRing) (3f * density).toInt() else 0
        setPadding(ringPad, ringPad, ringPad, ringPad)
        scaleType = ImageView.ScaleType.CENTER_CROP
        if (keepGlassRing) {
            val rounded = RoundedBitmapDrawableFactory.create(resources, bitmap).apply {
                isCircular = true
                setAntiAlias(true)
            }
            setImageDrawable(rounded)
        } else {
            setImageBitmap(bitmap)
        }
    } else {
        val padding = (density * 9f).toInt()
        setPadding(padding, padding, padding, padding)
        scaleType = ImageView.ScaleType.FIT_CENTER
        setImageResource(R.drawable.ic_profile)
    }
}

class ProfileActivity : AppCompatActivity() {
    private lateinit var avatar: ImageView
    private val pickPhoto = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) lifecycleScope.launch {
            withContext(Dispatchers.IO) { runCatching { saveProfileAvatar(this@ProfileActivity, uri) } }
                .onSuccess { avatar.showProfileAvatar() }
                .onFailure { Toast.makeText(this@ProfileActivity, "无法保存头像", Toast.LENGTH_SHORT).show() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyTheme(this)
        super.onCreate(savedInstanceState)

        val frameRoot = FrameLayout(this).apply {
            fitsSystemWindows = true
            setBackgroundColor(getColor(R.color.surface_bg))
        }
        val wallpaper = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        frameRoot.addView(wallpaper, FrameLayout.LayoutParams(-1, -1))

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        frameRoot.addView(root, FrameLayout.LayoutParams(-1, -1))
        applyLibraryMaterial(frameRoot, wallpaper, root)

        val toolbar = MaterialToolbar(this).apply {
            title = "个人"
            setNavigationIcon(R.drawable.ic_arrow_back)
            navigationContentDescription = "返回"
            setNavigationOnClickListener { finish() }
        }
        root.addView(toolbar, LinearLayout.LayoutParams(-1, dp(56)))
        val content = column().apply { setPadding(dp(20), dp(8), dp(20), dp(28)) }
        root.addView(ScrollView(this).apply {
            addView(content, FrameLayout.LayoutParams(
                resources.displayMetrics.widthPixels.coerceAtMost(dp(760)), -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        }, LinearLayout.LayoutParams(-1, 0, 1f))

        val account = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        avatar = ImageView(this).apply {
            contentDescription = "头像"
            if (ArkReaderApp.instance.prefs.glassMaterial) {
                applyLiquidGlass(wallpaper, LiquidGlassPreset.FLOATING_DOCK, 25f, interactiveElastic = true, elevationDp = 5f)
            }
            showProfileAvatar()
        }
        account.addView(avatar, LinearLayout.LayoutParams(dp(50), dp(50)).apply { marginEnd = dp(14) })
        account.addView(statText("我的阅读", 23f, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        account.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "更换头像"
            setOnClickListener { pickPhoto.launch(arrayOf("image/*")) }
        })
        content.addView(account)
        val stats = column()
        content.addView(stats)
        content.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "问题反馈"
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(16) }
            setOnClickListener { showFeedback() }
        })
        setContentView(frameRoot)
        lifecycleScope.launch {
            val time = withContext(Dispatchers.IO) { ArkReaderApp.instance.db.readingTime() }
            renderStats(stats, time)
        }
    }

    private fun renderStats(group: LinearLayout, data: List<ReadingTime>, selected: LocalDate? = null, all: Boolean = false) {
        group.removeAllViews()
        val today = LocalDate.now()
        val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val dates = (6 downTo 0).map { today.minusDays(it.toLong()) }
        val week = data.filter { it.day in dates.first().toString()..today.toString() }
        val calendarWeek = week.filter { it.day >= monday.toString() }.sumOf { it.millis }
        val daily = week.groupingBy { it.day }.fold(0L) { total, row -> total + row.millis }
        val scope = when {
            all -> data
            selected != null -> week.filter { it.day == selected.toString() }
            else -> week
        }
        val chart = column().apply {
            applyGlassPanelSurface(18f)
            setPadding(dp(18), dp(16), dp(18), dp(14))
        }
        group.addView(chart, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        chart.addView(statText("阅读时长", 17f, bold = true).apply { isAccessibilityHeading = true })
        val summary = LinearLayout(this).apply { gravity = Gravity.BOTTOM; setPadding(0, dp(8), 0, dp(8)) }
        val main = column().apply {
            addView(statText(when {
                all -> "累计"
                selected != null -> "${selected.monthValue} 月 ${selected.dayOfMonth} 日"
                else -> "近 7 天"
            }, 13f, muted = true))
            addView(statText(duration(scope.sumOf { it.millis }), 32f, bold = true))
        }
        summary.addView(main, LinearLayout.LayoutParams(0, -2, 1f))
        summary.addView(column().apply {
            addView(statText("今日 ${duration(daily[today.toString()] ?: 0L)}", 12.5f, muted = true))
            addView(statText("本周 ${duration(calendarWeek)}", 12.5f, muted = true))
        })
        chart.addView(summary)
        chart.addView(weekChart(dates, dates.map { daily[it.toString()] ?: 0L }, selected, ::duration) { date ->
            if (date <= today) renderStats(group, data, if (date == selected) null else date)
        })

        // Works breakdown card with inline filter pills (synced with Windows PC)
        val worksCard = column().apply {
            applyGlassPanelSurface(18f)
            setPadding(dp(18), dp(14), dp(18), dp(14))
        }
        group.addView(worksCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })

        val headerRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(8))
        }
        headerRow.addView(statText("作品明细", 18f, bold = true).apply { isAccessibilityHeading = true }, LinearLayout.LayoutParams(0, -2, 1f))
        listOf(false to "近 7 天", true to "全部").forEachIndexed { idx, (showAll, title) ->
            val active = selected == null && showAll == all
            val style = if (active) com.google.android.material.R.attr.materialButtonStyle
                else com.google.android.material.R.attr.materialButtonOutlinedStyle
            headerRow.addView(MaterialButton(this, null, style).apply {
                text = title
                isSelected = active
                layoutParams = LinearLayout.LayoutParams(-2, -2).apply {
                    if (idx > 0) marginStart = dp(8)
                }
                setOnClickListener { if (!active) renderStats(group, data, all = showAll) }
            })
        }
        worksCard.addView(headerRow)

        val works = scope.groupBy { it.comicId }.values.sortedByDescending { rows -> rows.sumOf { it.millis } }
        if (works.isEmpty()) worksCard.addView(statText("这段时间没有阅读记录。", 14f, muted = true))
        works.forEachIndexed { i, rows ->
            val workTime = rows.sumOf { it.millis }
            val line = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(46)
                addView(statText(rows.maxBy { it.day }.title, 15f, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
                addView(statText(duration(workTime), 14f, muted = true).apply { setPadding(dp(12), 0, 0, 0) })
            }
            worksCard.addView(line)
            if (i < works.lastIndex) {
                worksCard.addView(View(this).apply { setBackgroundColor(getColor(R.color.surface_border)) }, LinearLayout.LayoutParams(-1, dp(1)))
            }
        }
    }

    private fun showFeedback() {
        val prefs = ArkReaderApp.instance.prefs
        val fields = column().apply { setPadding(dp(20), 0, dp(20), 0) }
        val title = EditText(this).apply {
            hint = "问题标题"
            isSingleLine = true
            filters = arrayOf(InputFilter.LengthFilter(100))
        }
        val body = EditText(this).apply {
            hint = "说明问题、复现步骤和预期结果"
            gravity = Gravity.TOP
            minLines = 4
            filters = arrayOf(InputFilter.LengthFilter(600))
        }
        val details = CheckBox(this).apply { text = "附上应用版本和 Android 版本" }
        val token = EditText(this).apply {
            hint = if (prefs.hasFeedbackToken) "GitHub Token 已保存，留空继续使用" else "GitHub Token（可创建 Issue）"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSingleLine = true
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }
        val remember = CheckBox(this).apply { text = "在本机加密保存 Token"; isChecked = prefs.hasFeedbackToken }
        val status = statText("", 14f, muted = true)
        fields.addView(statText("提交后将公开发布到 zmf678/koma-comic。", 13f, muted = true))
        fields.addView(title)
        fields.addView(body)
        fields.addView(details)
        fields.addView(token)
        fields.addView(remember)
        fields.addView(status)
        val dialog = MaterialAlertDialogBuilder(this).setTitle("问题反馈")
            .setView(ScrollView(this).apply { addView(fields) })
            .setNegativeButton("关闭", null).setPositiveButton("提交 Issue", null).show()
        val confirm = dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)
        val close = dialog.getButton(android.content.DialogInterface.BUTTON_NEGATIVE)
        confirm.setOnClickListener {
            val subject = title.text.toString().trim()
            if (subject.isEmpty()) { title.error = "请输入问题标题"; return@setOnClickListener }
            val text = body.text.toString().trim()
            if (text.isEmpty()) { body.error = "请输入问题说明"; return@setOnClickListener }
            val version = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
            val submission = if (details.isChecked) "$text\n\n环境 · 页光 Koma $version · Android ${android.os.Build.VERSION.RELEASE}" else text
            val typedToken = token.text.toString().trim()
            val save = remember.isChecked
            confirm.isEnabled = false
            close.isEnabled = false
            dialog.setCancelable(false)
            status.text = "正在提交…"
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) { runCatching {
                    val secret = typedToken.ifBlank { prefs.feedbackToken() }
                    val number = GithubFeedback.submit(subject, submission, secret)
                    val saved = runCatching { prefs.saveFeedbackToken(if (save) secret else "") }.isSuccess
                    number to saved
                } }
                dialog.setCancelable(true)
                close.isEnabled = true
                result.onSuccess { (number, saved) ->
                    status.text = (if (number > 0) "已提交 Issue #$number" else "GitHub 已接收反馈") +
                        if (saved) "" else "。Token 设置未保存"
                    confirm.visibility = View.GONE
                    token.text?.clear()
                    listOf(title, body, details, token, remember).forEach { it.isEnabled = false }
                }.onFailure {
                    confirm.isEnabled = true
                    status.text = GithubFeedback.describe(it)
                }
            }
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun duration(millis: Long): String {
        val minutes = millis / 60_000
        return when {
            minutes >= 60 -> "${minutes / 60} 小时 ${minutes % 60} 分"
            minutes > 0 -> "$minutes 分"
            millis > 0 -> "不足 1 分"
            else -> "0 分"
        }
    }
}
