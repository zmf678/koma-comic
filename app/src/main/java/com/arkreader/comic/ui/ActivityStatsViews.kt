package com.arkreader.comic.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.arkreader.comic.R
import com.arkreader.comic.data.TranslationTokens
import com.arkreader.comic.data.TranslationUsage
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.time.LocalDate

internal fun Context.statText(value: String, size: Float, muted: Boolean = false, bold: Boolean = false) =
    TextView(this).apply {
        text = value
        textSize = size
        setTextColor(getColor(if (muted) R.color.text_secondary else R.color.text_primary))
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

internal fun Context.statDp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()

internal fun Context.statSurface(color: Int, radius: Int) = GradientDrawable().apply {
    setColor(color)
    cornerRadius = statDp(radius).toFloat()
}

internal fun Context.weekChart(days: List<LocalDate>, values: List<Long>, selected: LocalDate?,
    describe: (Long) -> String, select: ((LocalDate) -> Unit)? = null): LinearLayout {
    val maximum = values.maxOrNull()?.coerceAtLeast(1L) ?: 1L
    val plotHeight = if (values.any { it > 0L }) 99 else 52
    val accent = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary, 0xFF377BC0.toInt())
    return LinearLayout(this).apply {
        for ((index, day) in days.withIndex()) {
            val value = values[index]
            val active = day == selected
            val cell = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                val pad = statDp(4)
                setPadding(pad, pad, pad, pad)
                if (active) background = statSurface(getColor(R.color.surface_pill), 10)
                contentDescription = "${day.monthValue}月${day.dayOfMonth}日，${describe(value)}"
                if (select != null) {
                    isFocusable = true
                    isSelected = active
                    setOnClickListener { select(day) }
                }
            }
            val track = FrameLayout(context).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
            val height = if (value == 0L) statDp(2) else (statDp(95) * value.toDouble() / maximum).toInt().coerceAtLeast(statDp(4))
            track.addView(View(context).apply {
                background = statSurface(if (value == 0L) getColor(R.color.text_secondary) else accent, 5)
                alpha = when {
                    value == 0L -> 0.28f
                    selected == null || active -> 1f
                    else -> 0.62f
                }
            }, FrameLayout.LayoutParams(statDp(14), height, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
            cell.addView(track, LinearLayout.LayoutParams(-1, statDp(plotHeight)))
            cell.addView(statText("${day.monthValue}/${day.dayOfMonth}", 11f, muted = !active, bold = active).apply {
                gravity = Gravity.CENTER
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(-1, statDp(24)))
            addView(cell, LinearLayout.LayoutParams(0, -2, 1f))
        }
    }
}

internal fun showTranslationUsage(context: Context) {
    val rows = TranslationUsage.recent(context)
    val total = rows.fold(TranslationTokens()) { sum, row -> sum + row.tokens }
    val days = (6 downTo 0).map { LocalDate.now().minusDays(it.toLong()) }
    val daily = rows.groupBy { it.day }.mapValues { (_, entries) -> entries.sumOf { it.tokens.total } }
    val body = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(context.statDp(22), context.statDp(12), context.statDp(22), context.statDp(12))
        addView(context.statText(if (total.reported == 0L) "暂无已报告用量" else "%,d Token".format(total.total), 25f, bold = true))
        addView(context.statText("最近 7 天 · ${total.requests} 次直连请求", 13f, muted = true))
        addView(context.weekChart(days, days.map { daily[it.toString()] ?: 0L }, null, { "%,d Token".format(it) }))
        if (total.reported > 0) {
            addView(context.statText("输入 %,d   输出 %,d".format(total.input, total.output), 15f))
            addView(context.statText("输出中含思考 %,d".format(total.reasoning), 13f, muted = true))
        }
        if (total.requests > total.reported) {
            addView(context.statText("${total.requests - total.reported} 次请求未报告用量", 13f, muted = true))
        }
        for ((model, entries) in rows.groupBy { it.model }.entries.sortedByDescending { entry -> entry.value.sumOf { it.tokens.total } }) {
            val usage = entries.fold(TranslationTokens()) { sum, row -> sum + row.tokens }
            addView(context.statText(model, 15f, bold = true).apply { setPadding(0, context.statDp(14), 0, 0) })
            val amount = if (usage.reported == 0L) "未报告 Token" else "%,d Token".format(usage.total)
            addView(context.statText("$amount · ${usage.requests} 次请求", 13f, muted = true))
        }
    }
    MaterialAlertDialogBuilder(context).setTitle("翻译 Token 用量")
        .setView(ScrollView(context).apply { addView(body) }).setPositiveButton("关闭", null).show()
}
