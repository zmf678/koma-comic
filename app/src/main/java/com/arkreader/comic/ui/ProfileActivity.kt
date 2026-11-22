package com.arkreader.comic.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.InputFilter
import android.view.Gravity
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.arkreader.comic.ArkReaderApp
import com.arkreader.comic.R
import com.arkreader.comic.data.ReadingTime
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
    background = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(context.getColor(R.color.surface_card))
    }
    clipToOutline = true
    val file = File(context.filesDir, "profile-avatar.jpg")
    val bitmap = if (file.exists()) BitmapFactory.decodeFile(file.absolutePath) else null
    if (bitmap != null) {
        setPadding(0, 0, 0, 0)
        scaleType = ImageView.ScaleType.CENTER_CROP
        setImageBitmap(bitmap)
    } else {
        val padding = (resources.displayMetrics.density * 8).toInt()
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
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            setBackgroundColor(getColor(R.color.surface_bg))
        }
        val toolbar = MaterialToolbar(this).apply {
            title = "个人"
            setNavigationIcon(R.drawable.ic_arrow_back)
            navigationContentDescription = "返回"
            setNavigationOnClickListener { finish() }
        }
        root.addView(toolbar, LinearLayout.LayoutParams(-1, dp(56)))
        val content = column().apply { setPadding(dp(22), dp(8), dp(22), dp(24)) }
        root.addView(ScrollView(this).apply { addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
        val account = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        avatar = ImageView(this).apply {
            contentDescription = "头像"
            showProfileAvatar()
        }
        account.addView(avatar, LinearLayout.LayoutParams(dp(64), dp(64)).apply { marginEnd = dp(14) })
        account.addView(MaterialButton(this).apply {
            text = "更换头像"
            setOnClickListener { pickPhoto.launch(arrayOf("image/*")) }
        })
        content.addView(account)
        val stats = column()
        content.addView(stats)
        content.addView(MaterialButton(this).apply {
            text = "问题反馈"
            setOnClickListener { showFeedback() }
        })
        setContentView(root)
        lifecycleScope.launch {
            val time = withContext(Dispatchers.IO) { ArkReaderApp.instance.db.readingTime() }
            renderStats(stats, time)
        }
    }

    private fun renderStats(group: LinearLayout, data: List<ReadingTime>) {
        val today = LocalDate.now()
        val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString()
        val week = data.filter { it.day in monday..today.toString() }
        val daily = week.groupingBy { it.day }.fold(0L) { total, row -> total + row.millis }
        heading(group, "阅读时长")
        group.addView(label("今日  ${duration(daily[today.toString()] ?: 0)}     本周  ${duration(week.sumOf { it.millis })}", 16f))
        val maxDay = (daily.values.maxOrNull() ?: 0L).coerceAtLeast(1)
        repeat(7) { offset ->
            val day = LocalDate.parse(monday).plusDays(offset.toLong())
            val millis = daily[day.toString()] ?: 0
            val row = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(3), 0, dp(3))
            }
            row.addView(label("${day.monthValue}/${day.dayOfMonth}"), LinearLayout.LayoutParams(dp(48), -2))
            row.addView(android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 1000
                progress = (1000 * millis / maxDay).toInt()
                contentDescription = "${day.monthValue}月${day.dayOfMonth}日，${duration(millis)}"
            }, LinearLayout.LayoutParams(0, dp(8), 1f))
            row.addView(label(duration(millis)).apply { gravity = Gravity.END }, LinearLayout.LayoutParams(dp(92), -2))
            group.addView(row)
        }
        heading(group, "作品阅读时长")
        val works = data.groupBy { it.comicId }.values.sortedByDescending { rows -> rows.sumOf { it.millis } }
        if (works.isEmpty()) group.addView(label("打开作品阅读后，这里会显示时长。"))
        works.forEach { rows ->
            val recent = rows.filter { it.day in monday..today.toString() }
            group.addView(label(rows.first().title, 16f).apply { setPadding(0, dp(8), 0, 0) })
            group.addView(label("本周 ${duration(recent.sumOf { it.millis })} · 总计 ${duration(rows.sumOf { it.millis })}"))
        }
    }

    private fun showFeedback() {
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
        fields.addView(title)
        fields.addView(body)
        fields.addView(details)
        val dialog = MaterialAlertDialogBuilder(this).setTitle("问题反馈")
            .setView(ScrollView(this).apply { addView(fields) })
            .setNegativeButton("取消", null).setPositiveButton("预览", null).show()
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            val subject = title.text.toString().trim()
            if (subject.isEmpty()) { title.error = "请输入问题标题"; return@setOnClickListener }
            val text = body.text.toString().trim()
            if (text.isEmpty()) { body.error = "请输入问题说明"; return@setOnClickListener }
            val version = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
            val submission = if (details.isChecked) "$text\n\n环境：页光 Koma $version · Android ${android.os.Build.VERSION.RELEASE}" else text
            MaterialAlertDialogBuilder(this).setTitle("将提交到公开的 GitHub Issues")
                .setMessage("$subject\n\n$submission")
                .setNegativeButton("返回修改", null)
                .setPositiveButton("同意并打开 GitHub") { _, _ ->
                    val issue = Uri.parse("https://github.com/zmf678/koma-comic/issues/new").buildUpon()
                        .appendQueryParameter("title", subject).appendQueryParameter("body", submission).build()
                    openComicWebsite(issue.toString())
                }.show()
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun label(value: String, size: Float = 14f) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(getColor(R.color.text_primary))
    }
    private fun heading(group: LinearLayout, value: String) {
        group.addView(label(value, 19f).apply {
            isAccessibilityHeading = true
            setPadding(0, dp(22), 0, dp(8))
        }, ViewGroup.LayoutParams(-1, -2))
    }
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
