package com.arkreader.comic.ui

import android.content.Intent
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.core.content.ContextCompat
import com.arkreader.comic.ArkReaderApp
import com.arkreader.comic.R
import com.arkreader.comic.data.GridSize
import com.arkreader.comic.data.TranslationMode
import com.arkreader.comic.data.TranslationModel
import com.arkreader.comic.databinding.PanelMainSettingsBinding
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.arkreader.comic.engine.MangaTranslation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale

/**
 * Embedded Settings controller displayed in the right pane of MainActivity
 * so the left NavigationRail / bottom bar stays visible.
 */
class MainSettingsPanel(
    private val activity: AppCompatActivity,
    private val binding: PanelMainSettingsBinding,
    private val onGridPrefsChanged: () -> Unit,
    private val onOpenWarehouse: () -> Unit
) {
    private val app get() = ArkReaderApp.instance

    private val wallpaperPicker = activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) activity.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { saveLibraryWallpaper(activity, uri) } }
            result.onSuccess {
                app.prefs.wallpaperEnabled = true
                activity.recreate()
            }.onFailure { Toast.makeText(activity, it.message ?: "无法保存壁纸", Toast.LENGTH_SHORT).show() }
        }
    }

    fun refresh() {
        val root = binding.mainSettingsContainer
        root.removeAllViews()
        val prefs = app.prefs

        addSectionTitle(root, "外观与显示")
        addCardGroup(root) { group ->
            addSwitchRow(group, "液态玻璃", "使用半透明光学材质、光泽边缘与通透壁纸", prefs.glassMaterial) {
                prefs.glassMaterial = it
                activity.recreate()
            }
            addDivider(group)
            addActionRow(group, "背景壁纸", if (prefs.wallpaperEnabled) "已启用自定义壁纸 · 点击更换或关闭" else "选择本地图片（原图清晰呈现）") {
                if (prefs.wallpaperEnabled) {
                    MaterialAlertDialogBuilder(activity)
                        .setTitle("背景壁纸")
                        .setItems(arrayOf("更换图片", "关闭壁纸")) { _, index ->
                            if (index == 0) wallpaperPicker.launch(arrayOf("image/*"))
                            else {
                                prefs.wallpaperEnabled = false
                                activity.recreate()
                            }
                        }.show()
                } else wallpaperPicker.launch(arrayOf("image/*"))
            }
            addDivider(group)
            addThemeModePillRow(group, prefs.themeMode) { which ->
                if (prefs.themeMode != which) {
                    prefs.themeMode = which
                    app.applyNightMode(which)
                    activity.recreate()
                }
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

            addActionRow(group, "漫画翻译", when {
                prefs.translationModel().mode.usesCustomModel -> prefs.translationModel().modelId.ifBlank { "设置模型 API" }
                prefs.translationServer.isBlank() -> "设置翻译服务"
                else -> prefs.translationModel().mode.label
            }) {
                showTranslationSettings()
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
                "漫画源与登录",
                "已启用 $enabledCount / ${allSources.size} 个漫画源（支持仓库订阅与账号登录）"
            ) {
                activity.startActivity(Intent(activity, SourceManageActivity::class.java))
            }
            addDivider(group)

            addActionRow(group, "本地仓库", "管理本地文件夹和 WebDAV 仓库", onOpenWarehouse)
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
            addActionRow(group, "检查并自动更新", "当前版本 v$versionName · 从 GitHub Releases 检测更新") {
                checkAndRunAutoUpdate(versionName)
            }
            addDivider(group)

            addActionRow(group, "关于页光 Koma", "版本 v$versionName · Android 原生高清版") {
                MaterialAlertDialogBuilder(activity)
                    .setTitle("页光 Koma v$versionName")
                    .setMessage("开源本地 / WebDAV 与多源在线漫画阅读器。\n无广告、无过期弹窗、无第三方统计分析。")
                    .setPositiveButton("确定", null)
                    .show()
            }
        }
    }

    private fun compareSemVer(remote: String, local: String): Int {
        fun parse(raw: String) = raw.trim().removePrefix("v").removePrefix("V")
            .substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val a = parse(remote)
        val b = parse(local)
        for (i in 0 until maxOf(a.size, b.size)) {
            val va = a.getOrElse(i) { 0 }
            val vb = b.getOrElse(i) { 0 }
            if (va != vb) return va.compareTo(vb)
        }
        return 0
    }

    private fun checkAndRunAutoUpdate(currentVersion: String) {
        val checking = MaterialAlertDialogBuilder(activity)
            .setTitle("检查更新")
            .setMessage("正在连接 GitHub 获取最新版本…")
            .setNegativeButton("取消", null)
            .show()
        val job = activity.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val conn = (java.net.URL("https://api.github.com/repos/zmf678/koma-comic/releases/latest").openConnection() as java.net.HttpURLConnection).apply {
                        connectTimeout = 15_000
                        readTimeout = 15_000
                        setRequestProperty("User-Agent", "KomaComic-Android/$currentVersion")
                        setRequestProperty("Accept", "application/vnd.github+json")
                    }
                    try {
                        check(conn.responseCode in 200..299) { "HTTP ${conn.responseCode}" }
                        val body = conn.inputStream.bufferedReader().use { it.readText() }
                        val json = org.json.JSONObject(body)
                        val tag = json.optString("tag_name")
                        val notes = json.optString("body")
                        val htmlUrl = json.optString("html_url", "https://github.com/zmf678/koma-comic/releases")
                        var apkUrl = ""
                        var apkSize = 0L
                        val assets = json.optJSONArray("assets")
                        if (assets != null) {
                            for (i in 0 until assets.length()) {
                                val a = assets.optJSONObject(i) ?: continue
                                val name = a.optString("name")
                                if (name.endsWith(".apk", ignoreCase = true)) {
                                    apkUrl = a.optString("browser_download_url")
                                    apkSize = a.optLong("size", 0L)
                                    break
                                }
                            }
                        }
                        arrayOf(tag, notes, htmlUrl, apkUrl, apkSize.toString())
                    } finally {
                        conn.disconnect()
                    }
                }
            }
            if (!checking.isShowing) return@launch
            checking.dismiss()
            result.onFailure { err ->
                Toast.makeText(activity, "检查更新失败：${err.message ?: "网络异常"}", Toast.LENGTH_LONG).show()
            }.onSuccess { info ->
                val tag = info[0]
                val ver = tag.trim().removePrefix("v").removePrefix("V")
                val notes = info[1].ifBlank { "包含最新功能改进与问题修复。" }
                val htmlUrl = info[2]
                val apkUrl = info[3]
                val apkSize = info[4].toLongOrNull() ?: 0L
                if (ver.isBlank() || compareSemVer(ver, currentVersion) <= 0) {
                    Toast.makeText(activity, "当前已是最新版本 (v$currentVersion)", Toast.LENGTH_SHORT).show()
                    return@onSuccess
                }
                val sizeLabel = if (apkSize > 0) String.format(Locale.getDefault(), " · %.2f MB", apkSize / (1024f * 1024f)) else ""
                val msg = "最新版本 v$ver（当前 v$currentVersion）$sizeLabel\n更新仅替换应用本体，书架、阅读记录与配置均会保留。\n\n$notes"
                val builder = MaterialAlertDialogBuilder(activity)
                    .setTitle("发现新版本 v$ver")
                    .setMessage(msg)
                    .setNeutralButton("打开发布页") { _, _ ->
                        runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(htmlUrl))) }
                    }
                    .setNegativeButton("稍后再说", null)
                if (apkUrl.startsWith("https://")) {
                    builder.setPositiveButton("一键下载并自动更新") { _, _ ->
                        downloadAndInstallApk(tag, apkUrl, apkSize)
                    }
                }
                builder.show()
            }
        }
        checking.setOnCancelListener { job.cancel() }
    }

    private fun downloadAndInstallApk(tag: String, apkUrl: String, expectedSize: Long) {
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(14), dp(24), dp(8))
        }
        val statusView = TextView(activity).apply {
            text = "正在连接下载安装包…"
            textSize = 13.5f
            setTextColor(ContextCompat.getColor(activity, R.color.text_primary))
        }
        val progressBar = android.widget.ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            isIndeterminate = expectedSize <= 0
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        }
        box.addView(statusView)
        box.addView(progressBar)

        var downloadJob: kotlinx.coroutines.Job? = null
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle("正在更新 $tag")
            .setView(box)
            .setNegativeButton("取消下载") { _, _ -> downloadJob?.cancel() }
            .setCancelable(false)
            .show()

        downloadJob = activity.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = java.io.File(activity.cacheDir, "updates").apply { mkdirs() }
                    val safeTag = tag.replace(Regex("[^A-Za-z0-9._-]"), "_")
                    val outFile = java.io.File(dir, "KomaComic-$safeTag.apk")
                    val conn = (java.net.URL(apkUrl).openConnection() as java.net.HttpURLConnection).apply {
                        connectTimeout = 20_000
                        readTimeout = 60_000
                        setRequestProperty("User-Agent", "KomaComic-Android")
                    }
                    try {
                        check(conn.responseCode in 200..299) { "HTTP ${conn.responseCode}" }
                        val total = if (conn.contentLengthLong > 0) conn.contentLengthLong else expectedSize
                        conn.inputStream.use { input ->
                            outFile.outputStream().use { output ->
                                val buf = ByteArray(32768)
                                var downloaded = 0L
                                var lastUi = 0L
                                while (true) {
                                    val n = input.read(buf)
                                    if (n <= 0) break
                                    output.write(buf, 0, n)
                                    downloaded += n
                                    val now = android.os.SystemClock.uptimeMillis()
                                    if (now - lastUi >= 100L) {
                                        lastUi = now
                                        val pct = if (total > 0) (downloaded * 100f / total).coerceIn(0f, 100f) else -1f
                                        val msg = if (total > 0) {
                                            String.format(Locale.getDefault(), "正在下载… %.1f%% (%.2f / %.2f MB)", pct, downloaded / 1048576f, total / 1048576f)
                                        } else {
                                            String.format(Locale.getDefault(), "正在下载… %.2f MB", downloaded / 1048576f)
                                        }
                                        withContext(Dispatchers.Main) {
                                            statusView.text = msg
                                            if (pct >= 0f) {
                                                progressBar.isIndeterminate = false
                                                progressBar.progress = (pct * 10f).toInt().coerceIn(0, 1000)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        outFile
                    } finally {
                        conn.disconnect()
                    }
                }
            }
            if (dialog.isShowing) dialog.dismiss()
            result.onFailure { err ->
                if (err !is kotlinx.coroutines.CancellationException) {
                    MaterialAlertDialogBuilder(activity)
                        .setTitle("下载失败")
                        .setMessage(err.message ?: "网络异常")
                        .setPositiveButton("重试") { _, _ -> downloadAndInstallApk(tag, apkUrl, expectedSize) }
                        .setNegativeButton("关闭", null)
                        .show()
                }
            }.onSuccess { apkFile ->
                if (!activity.packageManager.canRequestPackageInstalls()) {
                    MaterialAlertDialogBuilder(activity)
                        .setTitle("需要允许安装未知应用")
                        .setMessage("安装包已下载完成。请先授予「页光 Koma」安装应用权限，返回后再重新点击安装。")
                        .setPositiveButton("前往授权") { _, _ ->
                            val permIntent = Intent(
                                android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                android.net.Uri.parse("package:${activity.packageName}")
                            )
                            runCatching { activity.startActivity(permIntent) }
                        }
                        .setNegativeButton("取消", null)
                        .show()
                    return@onSuccess
                }
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    activity,
                    "${activity.packageName}.fileprovider",
                    apkFile
                )
                val installIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                runCatching { activity.startActivity(installIntent) }
                    .onFailure { Toast.makeText(activity, "无法调起安装器：${it.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun showTranslationSettings() {
        val prefs = app.prefs
        val saved = prefs.translationModel()
        val modes = TranslationMode.entries
        val fields = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        fun field(parent: LinearLayout, label: String, value: String = "", kind: Int = android.text.InputType.TYPE_CLASS_TEXT): EditText {
            parent.addView(TextView(activity).apply { text = label; setPadding(0, dp(10), 0, 0) })
            return EditText(activity).apply {
                inputType = kind
                setSingleLine(true)
                setText(value)
                parent.addView(this)
            }
        }
        val serverFields = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val endpoint = field(serverFields, "漫画翻译服务地址", prefs.translationServer,
            android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI)
        var sessionToken: String? = null
        var sessionServer = ""
        val loginStatus = TextView(activity).apply {
            text = if (prefs.hasTranslationSession) "已保存翻译服务会话" else "自定义模型需登录漫画翻译服务"
        }
        serverFields.addView(loginStatus)
        val loginButton = com.google.android.material.button.MaterialButton(activity).apply {
            text = "登录翻译服务"
            setOnClickListener {
                val server = endpoint.text.toString().trim()
                if (runCatching { MangaTranslation.endpoint(server) }.isFailure) {
                    endpoint.error = "请输入有效的翻译服务地址"
                    return@setOnClickListener
                }
                val accountFields = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(20), dp(5), dp(20), 0)
                }
                val username = field(accountFields, "翻译服务用户名")
                val password = field(accountFields, "密码", kind = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD)
                val login = MaterialAlertDialogBuilder(activity).setTitle("登录翻译服务").setView(accountFields)
                    .setNegativeButton("取消", null).setPositiveButton("登录", null).show()
                val button = login.getButton(android.content.DialogInterface.BUTTON_POSITIVE)
                button.setOnClickListener loginClick@{
                    val user = username.text.toString().trim()
                    val secret = password.text.toString()
                    if (user.isEmpty() || secret.isEmpty()) {
                        password.error = "请填写用户名和密码"
                        return@loginClick
                    }
                    button.isEnabled = false
                    activity.lifecycleScope.launch {
                        val result = withContext(Dispatchers.IO) { runCatching { MangaTranslation.login(server, user, secret) } }
                        if (!login.isShowing) return@launch
                        result.onSuccess {
                            sessionToken = it
                            sessionServer = server
                            loginStatus.text = "登录成功，保存后在阅读器使用"
                            login.dismiss()
                        }.onFailure {
                            button.isEnabled = true
                            password.error = if (it.message == "请先在翻译服务网页修改初始密码") it.message else "登录失败，请检查服务地址和账户"
                        }
                    }
                }
            }
        }
        serverFields.addView(loginButton)
        fields.addView(TextView(activity).apply { text = "模型来源"; setPadding(0, dp(10), 0, 0) })
        val mode = Spinner(activity, Spinner.MODE_DROPDOWN).apply {
            adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, modes.map { it.label })
            minimumHeight = dp(48)
            setSelection(saved.mode.ordinal)
            fields.addView(this)
        }
        fields.addView(serverFields)
        val custom = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            fields.addView(this)
        }
        val base = field(custom, "模型 API 地址（兼容接口 /v1）", saved.apiBase)
        val model = field(custom, "模型 ID", saved.modelId)
        val modelsButton = com.google.android.material.button.MaterialButton(activity).apply {
            text = "获取模型列表"
            custom.addView(this)
        }
        val key = field(custom, "模型 API 密钥", kind = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD)
        key.hint = if (prefs.hasTranslationKey) "已保存，地址不变时留空保留" else "填写服务商提供的 API Key"
        val clear = CheckBox(activity).apply {
            text = "清除已保存的密钥"
            visibility = if (prefs.hasTranslationKey) View.VISIBLE else View.GONE
            custom.addView(this)
        }
        val modelHelp = TextView(activity).apply {
            setPadding(0, dp(8), 0, dp(4))
            custom.addView(this)
        }
        val directOptions = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; custom.addView(this) }
        val sendImage = CheckBox(activity).apply {
            text = "发送漫画页图片"
            isChecked = saved.sendImage
            directOptions.addView(this)
        }
        directOptions.addView(TextView(activity).apply { text = "模型思考强度" })
        val levels = TranslationModel.reasoningLevels.entries.toList()
        val reasoning = Spinner(activity, Spinner.MODE_DROPDOWN).apply {
            adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, levels.map { it.value })
            minimumHeight = dp(48)
            setSelection(levels.indexOfFirst { it.key == saved.reasoningEffort }.coerceAtLeast(0))
            directOptions.addView(this)
        }
        directOptions.addView(com.google.android.material.button.MaterialButton(activity).apply {
            text = "查看 Token 用量"
            setOnClickListener { showTranslationUsage(activity) }
        })
        fun updateHelp() {
            modelHelp.text = if (modes.getOrElse(mode.selectedItemPosition) { saved.mode } == TranslationMode.DIRECT) {
                if (sendImage.isChecked) "选择支持图片输入的模型。漫画页直接发送到所填 API，设备在本机排版。"
                else "阅读时手动输入原文，API 只接收文字。"
            } else "自定义模式需要支持用户模型参数的 manga-translator-ui。"
        }
        sendImage.setOnCheckedChangeListener { _, _ -> updateHelp() }
        fun showMode(selected: TranslationMode) {
            custom.visibility = if (selected.usesCustomModel) View.VISIBLE else View.GONE
            serverFields.visibility = if (selected.usesTranslationServer) View.VISIBLE else View.GONE
            directOptions.visibility = if (selected == TranslationMode.DIRECT) View.VISIBLE else View.GONE
            updateHelp()
        }
        mode.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                showMode(modes[position])
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
        showMode(saved.mode)
        val config = field(fields, "高级翻译配置 JSON", prefs.translationConfig).apply { setSingleLine(false); maxLines = 4 }
        fun selectedModel() = TranslationModel(modes[mode.selectedItemPosition], base.text.toString().trim(),
            model.text.toString().trim(), levels[reasoning.selectedItemPosition].key, sendImage.isChecked)
        val testStatus = TextView(activity).also(fields::addView)
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle("漫画翻译")
            .setView(ScrollView(activity).apply { addView(fields) })
            .setNegativeButton("取消", null)
            .setNeutralButton("测试翻译", null)
            .setPositiveButton("保存", null)
            .show()
        var testJob: kotlinx.coroutines.Job? = null
        var modelsJob: kotlinx.coroutines.Job? = null
        dialog.setOnDismissListener { testJob?.cancel(); modelsJob?.cancel() }
        modelsButton.setOnClickListener {
            val address = base.text.toString().trim()
            val entered = key.text.toString().trim()
            val reuse = !clear.isChecked && address == saved.apiBase
            modelsButton.isEnabled = false
            testStatus.text = "正在获取模型列表…"
            modelsJob = activity.lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) { runCatching {
                    MangaTranslation.listModels(address, if (entered.isBlank() && reuse) prefs.translationApiKey() else entered)
                } }
                modelsButton.isEnabled = true
                if (address != base.text.toString().trim()) { testStatus.text = ""; return@launch }
                result.onSuccess { choices ->
                    testStatus.text = if (choices.isEmpty()) "API 未列出模型，可手动填写模型 ID" else "${choices.size} 个可选模型"
                    if (choices.isNotEmpty()) {
                        val pickerBox = LinearLayout(activity).apply {
                            orientation = LinearLayout.VERTICAL
                            setPadding(dp(16), dp(8), dp(16), 0)
                        }
                        val etFilter = EditText(activity).apply {
                            hint = "搜索模型 ID（共 ${choices.size} 个）"
                            isSingleLine = true
                        }
                        val filtered = choices.toMutableList()
                        val listView = android.widget.ListView(activity).apply {
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                (activity.resources.configuration.screenHeightDp * 0.42f * activity.resources.displayMetrics.density).toInt()
                            )
                            adapter = ArrayAdapter(activity, android.R.layout.simple_list_item_1, filtered)
                        }
                        etFilter.addTextChangedListener(object : android.text.TextWatcher {
                            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                                val q = s?.toString()?.trim()?.lowercase().orEmpty()
                                filtered.clear()
                                if (q.isEmpty()) filtered.addAll(choices)
                                else filtered.addAll(choices.filter { it.lowercase().contains(q) })
                                listView.adapter = ArrayAdapter(activity, android.R.layout.simple_list_item_1, filtered)
                            }
                            override fun afterTextChanged(s: android.text.Editable?) {}
                        })
                        pickerBox.addView(etFilter)
                        pickerBox.addView(listView)
                        val picker = MaterialAlertDialogBuilder(activity)
                            .setTitle("选择模型 (${choices.size})")
                            .setView(pickerBox)
                            .setNegativeButton("取消", null)
                            .show()
                        listView.setOnItemClickListener { _, _, index, _ ->
                            filtered.getOrNull(index)?.let { model.setText(it) }
                            picker.dismiss()
                        }
                    }
                }.onFailure { testStatus.text = "获取模型失败 · ${MangaTranslation.describe(it)}" }
            }
        }
        val testButton = dialog.getButton(android.content.DialogInterface.BUTTON_NEUTRAL)
        testButton.setOnClickListener {
            val server = endpoint.text.toString().trim()
            val selected = selectedModel()
            val settings = config.text.toString()
            val enteredKey = key.text.toString().trim()
            val reuseKey = !clear.isChecked && selected.apiBase == saved.apiBase &&
                (!selected.mode.usesTranslationServer || server == prefs.translationServer)
            val pendingSession = sessionToken?.takeIf { sessionServer == server }
            testStatus.text = "正在测试翻译…"
            testButton.isEnabled = false
            testJob = activity.lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val session = if (!selected.mode.usesTranslationServer) "" else pendingSession
                            ?: prefs.translationSession().takeIf { server == prefs.translationServer }.orEmpty()
                        val apiKey = if (selected.mode.usesCustomModel && enteredKey.isBlank() && reuseKey) prefs.translationApiKey() else enteredKey
                        MangaTranslation.test(server, settings, selected, apiKey, session, activity.applicationContext)
                    }
                }
                testStatus.text = result.fold({ if (selected.acceptsPageImage) "测试成功，已生成测试译图" else "测试成功，已收到文字译文" }, MangaTranslation::describe)
                testButton.isEnabled = true
            }
        }
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            val server = endpoint.text.toString().trim()
            val selected = selectedModel()
            when {
                selected.mode.usesTranslationServer && (selected.mode != TranslationMode.SERVER || server.isNotBlank()) &&
                    runCatching { MangaTranslation.endpoint(server, selected.mode) }.isFailure ->
                    endpoint.error = "请输入有效的翻译服务地址"
                runCatching { MangaTranslation.validateModel(selected) }.isFailure ->
                    if (selected.modelId.isBlank()) model.error = "请输入模型 ID" else base.error = "请输入有效的 API 地址"
                runCatching { JSONObject(config.text.toString()) }.isFailure -> config.error = "请输入 JSON 对象"
                else -> runCatching {
                    prefs.saveTranslationSettings(server, config.text.toString(), selected, key.text.toString(), clear.isChecked,
                        sessionToken?.takeIf { sessionServer == server })
                }.onSuccess {
                    dialog.dismiss()
                    refresh()
                }.onFailure {
                    Toast.makeText(activity, "暂时无法保存模型密钥", Toast.LENGTH_SHORT).show()
                }
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

    private fun addThemeModePillRow(parent: LinearLayout, currentMode: Int, onSelect: (Int) -> Unit) {
        val col = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(14))
        }
        col.addView(TextView(activity).apply {
            text = "外观主题"
            textSize = 15f
            setTextColor(ContextCompat.getColor(activity, R.color.text_primary))
        })
        val pillRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        }
        val items = listOf(2 to "深色模式", 1 to "浅色模式", 0 to "跟随系统")
        items.forEachIndexed { idx, (modeVal, label) ->
            val selected = currentMode == modeVal
            val style = if (selected) com.google.android.material.R.attr.materialButtonStyle
            else com.google.android.material.R.attr.materialButtonOutlinedStyle
            pillRow.addView(com.google.android.material.button.MaterialButton(activity, null, style).apply {
                text = label
                textSize = 12.5f
                minWidth = 0
                setPadding(dp(8), 0, dp(8), 0)
                layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f).apply {
                    if (idx > 0) marginStart = dp(8)
                }
                setOnClickListener { onSelect(modeVal) }
            })
        }
        col.addView(pillRow)
        parent.addView(col)
    }

    private fun addCardGroup(parent: LinearLayout, fill: (LinearLayout) -> Unit) {
        val card = MaterialCardView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
            applyGlassCardStyle(18f)
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
