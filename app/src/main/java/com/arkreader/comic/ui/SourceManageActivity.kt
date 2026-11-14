package com.arkreader.comic.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.arkreader.comic.ArkReaderApp
import com.arkreader.comic.R
import com.arkreader.comic.data.ComicSourceEntity
import com.arkreader.comic.data.RepoSourceItem
import com.arkreader.comic.data.SourceAccountCap
import com.arkreader.comic.databinding.ActivitySourceManageBinding
import com.arkreader.comic.databinding.ItemComicSourceBinding
import com.arkreader.comic.engine.SourceRepoClient
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class SourceManageActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySourceManageBinding
    private val app get() = ArkReaderApp.instance

    private val adapter = SourceAdapter(
        onToggle = { src, enabled ->
            app.db.setComicSourceEnabled(src.sourceKey, enabled)
            reload()
        },
        onAccount = { src -> openSourceAccountDialog(src) },
        onConfig = { src -> openSourceSettings(src) },
        onDelete = { src -> confirmDeleteSource(src) }
    )

    private val pickJsFileLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            runCatching {
                val script = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                        ?: throw IllegalStateException("无法读取文件")
                }
                val entity = SourceRepoClient.buildEntityFromScript(script)
                app.db.upsertComicSource(entity)
                app.veneraRuntime.invalidateSource(entity.sourceKey)
                entity
            }.onSuccess { entity ->
                Toast.makeText(this@SourceManageActivity, "已导入漫画源「${entity.name}」", Toast.LENGTH_SHORT).show()
                reload()
            }.onFailure { err ->
                Toast.makeText(this@SourceManageActivity, "导入失败: ${err.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivitySourceManageBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.rvSources.layoutManager = LinearLayoutManager(this)
        binding.rvSources.adapter = adapter

        binding.btnRepoSubscribe.setOnClickListener { showRepoSubscribeDialog() }
        binding.btnImportUrl.setOnClickListener { showImportUrlDialog() }
        binding.btnImportFile.setOnClickListener {
            pickJsFileLauncher.launch(arrayOf("*/*"))
        }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        adapter.submitList(app.db.getComicSources())
    }

    private fun showRepoSubscribeDialog() {
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val input = EditText(this).apply {
            setText(SourceRepoClient.DEFAULT_REPO_PAGE_URL)
            hint = "GitHub 仓库链接或 index.json 链接"
        }
        box.addView(input)
        MaterialAlertDialogBuilder(this)
            .setTitle("订阅 Venera 动漫源仓库")
            .setMessage("支持 GitHub 仓库链接（默认 zmf678/venera-sources）或标准 index.json 列表")
            .setView(box)
            .setPositiveButton("获取列表") { _, _ ->
                val repoUrl = input.text?.toString()?.trim().orEmpty()
                if (repoUrl.isNotEmpty()) fetchRepoList(repoUrl)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun fetchRepoList(repoUrl: String) {
        Toast.makeText(this, "正在获取源仓库列表...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            runCatching {
                SourceRepoClient.fetchRepoIndex(repoUrl)
            }.onSuccess { repoItems ->
                if (repoItems.isEmpty()) {
                    Toast.makeText(this@SourceManageActivity, "仓库暂无可用源", Toast.LENGTH_SHORT).show()
                } else {
                    showRepoPickerDialog(repoItems)
                }
            }.onFailure { err ->
                Toast.makeText(this@SourceManageActivity, "获取失败: ${err.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showRepoPickerDialog(repoItems: List<RepoSourceItem>) {
        val installed = app.db.getComicSources().associateBy { it.sourceKey }
        val selectedKeys = linkedSetOf<String>()
        val filtered = repoItems.toMutableList()
        val dp = resources.displayMetrics.density

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * dp).toInt(), (8 * dp).toInt(), (16 * dp).toInt(), 0)
        }
        val etFilter = EditText(this).apply {
            hint = "搜索名称或 key（共 ${repoItems.size} 个）"
            isSingleLine = true
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (4 * dp).toInt() }
        }
        val tvCount = TextView(this).apply {
            text = "已选 0 个"
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@SourceManageActivity, R.color.text_secondary))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val btnSelectAll = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "全选可见"
        }
        val btnClearAll = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "清空"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = (6 * dp).toInt() }
        }
        bar.addView(tvCount)
        bar.addView(btnSelectAll)
        bar.addView(btnClearAll)

        val rv = RecyclerView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (360 * dp).toInt()
            ).apply { topMargin = (6 * dp).toInt() }
            layoutManager = LinearLayoutManager(this@SourceManageActivity)
        }

        fun updateCountLabel() {
            tvCount.text = "已选 ${selectedKeys.size} 个"
        }

        val pickerAdapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val row = LinearLayout(parent.context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    layoutParams = RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    setPadding((4 * dp).toInt(), (6 * dp).toInt(), (4 * dp).toInt(), (6 * dp).toInt())
                }
                val cb = CheckBox(parent.context)
                val info = LinearLayout(parent.context).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginStart = (8 * dp).toInt()
                    }
                }
                val tvTitle = TextView(parent.context).apply {
                    textSize = 14.5f
                    setTextColor(ContextCompat.getColor(this@SourceManageActivity, R.color.text_primary))
                }
                val tvSub = TextView(parent.context).apply {
                    textSize = 12f
                    setTextColor(ContextCompat.getColor(this@SourceManageActivity, R.color.text_secondary))
                }
                info.addView(tvTitle)
                info.addView(tvSub)
                row.addView(cb)
                row.addView(info)
                return object : RecyclerView.ViewHolder(row) {}
            }

            override fun getItemCount(): Int = filtered.size

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val item = filtered[position]
                val row = holder.itemView as LinearLayout
                val cb = row.getChildAt(0) as CheckBox
                val info = row.getChildAt(1) as LinearLayout
                val tvTitle = info.getChildAt(0) as TextView
                val tvSub = info.getChildAt(1) as TextView

                val existing = installed[item.key]
                val status = when {
                    existing == null -> "未安装"
                    existing.version != item.version && item.version.isNotEmpty() -> "可更新 (${existing.version} -> ${item.version})"
                    else -> "已安装"
                }
                tvTitle.text = "${item.name} (${item.version})"
                tvSub.text = "${item.key} · $status"

                val idKey = item.key.ifEmpty { item.url }
                cb.setOnCheckedChangeListener(null)
                cb.isChecked = selectedKeys.contains(idKey)
                val toggle = {
                    if (selectedKeys.contains(idKey)) selectedKeys.remove(idKey) else selectedKeys.add(idKey)
                    cb.isChecked = selectedKeys.contains(idKey)
                    updateCountLabel()
                }
                cb.setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) selectedKeys.add(idKey) else selectedKeys.remove(idKey)
                    updateCountLabel()
                }
                row.setOnClickListener { toggle() }
            }
        }
        rv.adapter = pickerAdapter

        etFilter.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val q = s?.toString()?.trim()?.lowercase().orEmpty()
                filtered.clear()
                if (q.isEmpty()) {
                    filtered.addAll(repoItems)
                } else {
                    filtered.addAll(repoItems.filter {
                        it.name.lowercase().contains(q) ||
                            it.key.lowercase().contains(q) ||
                            it.fileName.lowercase().contains(q)
                    })
                }
                pickerAdapter.notifyDataSetChanged()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        btnSelectAll.setOnClickListener {
            filtered.forEach { selectedKeys.add(it.key.ifEmpty { it.url }) }
            pickerAdapter.notifyDataSetChanged()
            updateCountLabel()
        }
        btnClearAll.setOnClickListener {
            selectedKeys.clear()
            pickerAdapter.notifyDataSetChanged()
            updateCountLabel()
        }

        root.addView(etFilter)
        root.addView(bar)
        root.addView(rv)

        MaterialAlertDialogBuilder(this)
            .setTitle("选择要添加的漫画源 (${repoItems.size})")
            .setView(root)
            .setPositiveButton("安装所选") { _, _ ->
                val chosen = repoItems.filter { selectedKeys.contains(it.key.ifEmpty { it.url }) }
                if (chosen.isEmpty()) {
                    Toast.makeText(this, "未选择任何漫画源", Toast.LENGTH_SHORT).show()
                } else {
                    installRepoItems(chosen)
                }
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun installRepoItems(items: List<RepoSourceItem>) {
        if (items.size == 1) {
            installRepoItem(items.first())
            return
        }
        Toast.makeText(this, "开始批量安装 ${items.size} 个漫画源...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            var okCount = 0
            var lastErr: String? = null
            for (item in items) {
                runCatching {
                    val entity = SourceRepoClient.downloadAndBuildEntity(
                        scriptUrl = item.url,
                        fallbackKey = item.key,
                        fallbackName = item.name,
                        fallbackVersion = item.version,
                        builtIn = false
                    )
                    app.db.upsertComicSource(entity)
                    app.veneraRuntime.invalidateSource(entity.sourceKey)
                }.onSuccess {
                    okCount++
                }.onFailure { err ->
                    lastErr = "${item.name}: ${err.message}"
                }
            }
            reload()
            val msg = if (okCount == items.size) {
                "全部 $okCount 个漫画源安装完成"
            } else {
                "成功安装 $okCount/${items.size} 个${lastErr?.let { "（$it）" }.orEmpty()}"
            }
            Toast.makeText(this@SourceManageActivity, msg, Toast.LENGTH_LONG).show()
        }
    }

    private fun installRepoItem(item: RepoSourceItem) {
        Toast.makeText(this, "正在安装「${item.name}」...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            runCatching {
                val entity = SourceRepoClient.downloadAndBuildEntity(
                    scriptUrl = item.url,
                    fallbackKey = item.key,
                    fallbackName = item.name,
                    fallbackVersion = item.version,
                    builtIn = false
                )
                app.db.upsertComicSource(entity)
                app.veneraRuntime.invalidateSource(entity.sourceKey)
                entity
            }.onSuccess { entity ->
                Toast.makeText(this@SourceManageActivity, "已安装「${entity.name}」", Toast.LENGTH_SHORT).show()
                reload()
            }.onFailure { err ->
                Toast.makeText(this@SourceManageActivity, "安装失败: ${err.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun openSourceAccountDialog(src: ComicSourceEntity) {
        lifecycleScope.launch {
            val cap = runCatching {
                app.veneraRuntime.getAccountCapabilities(src.sourceKey)
            }.getOrElse { err ->
                Toast.makeText(this@SourceManageActivity, "读取账号接口失败: ${err.message}", Toast.LENGTH_LONG).show()
                return@launch
            }

            if (!cap.supportsAnyLogin && cap.registerWebsite.isBlank()) {
                Toast.makeText(this@SourceManageActivity, "「${src.name}」未提供账号登录功能", Toast.LENGTH_SHORT).show()
                return@launch
            }

            val dp = resources.displayMetrics.density
            val scroll = ScrollView(this@SourceManageActivity)
            val container = LinearLayout(this@SourceManageActivity).apply {
                orientation = LinearLayout.VERTICAL
                val pad = (20 * dp).toInt()
                setPadding(pad, pad / 2, pad, 0)
            }
            scroll.addView(container)

            val tvStatus = TextView(this@SourceManageActivity).apply {
                text = if (cap.isLoggedIn) "当前状态：已登录" else "当前状态：未登录"
                textSize = 14f
                setTextColor(ContextCompat.getColor(this@SourceManageActivity, R.color.text_secondary))
                setPadding(0, 0, 0, (10 * dp).toInt())
            }
            container.addView(tvStatus)

            var dialogRef: androidx.appcompat.app.AlertDialog? = null

            if (cap.hasPasswordLogin) {
                container.addView(TextView(this@SourceManageActivity).apply {
                    text = "账号密码登录"
                    textSize = 14f
                    setTextColor(ContextCompat.getColor(this@SourceManageActivity, R.color.text_primary))
                })
                val etUser = EditText(this@SourceManageActivity).apply {
                    hint = "用户名 / 邮箱"
                    isSingleLine = true
                }
                val etPass = EditText(this@SourceManageActivity).apply {
                    hint = "密码"
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    isSingleLine = true
                }
                val btnPwdLogin = MaterialButton(this@SourceManageActivity).apply {
                    text = "使用账号密码登录"
                    setOnClickListener {
                        val u = etUser.text?.toString()?.trim().orEmpty()
                        val p = etPass.text?.toString().orEmpty()
                        if (u.isEmpty() || p.isEmpty()) {
                            Toast.makeText(this@SourceManageActivity, "请输入账号与密码", Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }
                        isEnabled = false
                        lifecycleScope.launch {
                            runCatching {
                                app.veneraRuntime.loginWithPassword(src.sourceKey, u, p)
                            }.onSuccess { ok ->
                                isEnabled = true
                                if (ok) {
                                    Toast.makeText(this@SourceManageActivity, "「${src.name}」登录成功", Toast.LENGTH_SHORT).show()
                                    dialogRef?.dismiss()
                                    reload()
                                } else {
                                    Toast.makeText(this@SourceManageActivity, "登录失败，请检查账号密码", Toast.LENGTH_LONG).show()
                                }
                            }.onFailure { err ->
                                isEnabled = true
                                Toast.makeText(this@SourceManageActivity, "登录出错: ${err.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }
                container.addView(etUser)
                container.addView(etPass)
                container.addView(btnPwdLogin)
            }

            if (cap.hasWebViewLogin) {
                val btnWebLogin = MaterialButton(
                    this@SourceManageActivity,
                    null,
                    if (cap.hasPasswordLogin) com.google.android.material.R.attr.materialButtonOutlinedStyle else com.google.android.material.R.attr.materialButtonStyle
                ).apply {
                    text = "通过内置网页登录 (WebView)"
                    setOnClickListener {
                        dialogRef?.dismiss()
                        showWebViewLoginDialog(src, cap)
                    }
                }
                container.addView(btnWebLogin)
            }

            if (cap.cookieFields.isNotEmpty()) {
                container.addView(TextView(this@SourceManageActivity).apply {
                    text = "Cookie 字段登录"
                    textSize = 14f
                    setTextColor(ContextCompat.getColor(this@SourceManageActivity, R.color.text_primary))
                    setPadding(0, (10 * dp).toInt(), 0, 4)
                })
                val cookieInputs = cap.cookieFields.map { fieldName ->
                    EditText(this@SourceManageActivity).apply {
                        hint = fieldName
                    }.also { container.addView(it) }
                }
                val btnCookieLogin = MaterialButton(
                    this@SourceManageActivity,
                    null,
                    com.google.android.material.R.attr.materialButtonOutlinedStyle
                ).apply {
                    text = "使用 Cookie 登录"
                    setOnClickListener {
                        val values = cookieInputs.map { it.text?.toString()?.trim().orEmpty() }
                        isEnabled = false
                        lifecycleScope.launch {
                            runCatching {
                                app.veneraRuntime.loginWithCookies(src.sourceKey, values)
                            }.onSuccess { ok ->
                                isEnabled = true
                                if (ok) {
                                    Toast.makeText(this@SourceManageActivity, "Cookie 登录成功", Toast.LENGTH_SHORT).show()
                                    dialogRef?.dismiss()
                                    reload()
                                } else {
                                    Toast.makeText(this@SourceManageActivity, "Cookie 无效或登录失败", Toast.LENGTH_LONG).show()
                                }
                            }.onFailure { err ->
                                isEnabled = true
                                Toast.makeText(this@SourceManageActivity, "登录出错: ${err.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }
                container.addView(btnCookieLogin)
            }

            if (cap.registerWebsite.isNotBlank()) {
                val btnReg = MaterialButton(
                    this@SourceManageActivity,
                    null,
                    com.google.android.material.R.attr.materialButtonOutlinedStyle
                ).apply {
                    text = "前往官网注册"
                    setOnClickListener {
                        runCatching {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(cap.registerWebsite)))
                        }
                    }
                }
                container.addView(btnReg)
            }

            val builder = MaterialAlertDialogBuilder(this@SourceManageActivity)
                .setTitle("${src.name} 账号")
                .setView(scroll)
                .setNegativeButton("关闭", null)
            if (cap.isLoggedIn) {
                builder.setNeutralButton("退出登录") { _, _ ->
                    lifecycleScope.launch {
                        runCatching { app.veneraRuntime.logout(src.sourceKey) }
                        Toast.makeText(this@SourceManageActivity, "已退出登录", Toast.LENGTH_SHORT).show()
                        reload()
                    }
                }
            }
            dialogRef = builder.show()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun showWebViewLoginDialog(src: ComicSourceEntity, cap: SourceAccountCap) {
        val url = cap.webViewLoginUrl
        if (url.isBlank()) {
            Toast.makeText(this, "该源未配置网页登录地址", Toast.LENGTH_SHORT).show()
            return
        }
        val dp = resources.displayMetrics.density
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val tvUrl = TextView(this).apply {
            text = "正在加载: $url"
            textSize = 12f
            maxLines = 1
            setPadding((16 * dp).toInt(), (10 * dp).toInt(), (16 * dp).toInt(), (6 * dp).toInt())
            setTextColor(ContextCompat.getColor(this@SourceManageActivity, R.color.text_secondary))
        }
        var loginDialog: androidx.appcompat.app.AlertDialog? = null
        var completed = false

        val webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (440 * dp).toInt()
            )
            isFocusable = true
            isFocusableInTouchMode = true
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    return false
                }

                override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                    val curUrl = loadedUrl.orEmpty()
                    tvUrl.text = curUrl
                    CookieManager.getInstance().flush()
                    if (!completed) {
                        val title = view?.title.orEmpty()
                        lifecycleScope.launch {
                            val ok = runCatching {
                                app.veneraRuntime.checkWebViewLoginStatus(src.sourceKey, curUrl, title)
                            }.getOrDefault(false)
                            if (ok && !completed) {
                                completed = true
                                runCatching { app.veneraRuntime.completeWebViewLogin(src.sourceKey) }
                                Toast.makeText(
                                    this@SourceManageActivity,
                                    "「${src.name}」网页登录成功，Cookie 已同步",
                                    Toast.LENGTH_SHORT
                                ).show()
                                loginDialog?.dismiss()
                                reload()
                            }
                        }
                    }
                }
            }
            loadUrl(url)
        }
        box.addView(tvUrl)
        box.addView(webView)

        loginDialog = MaterialAlertDialogBuilder(this)
            .setTitle("${src.name} 网页登录")
            .setView(box)
            .setPositiveButton("我已完成登录") { _, _ ->
                completed = true
                CookieManager.getInstance().flush()
                lifecycleScope.launch {
                    val ok = runCatching {
                        app.veneraRuntime.completeWebViewLogin(src.sourceKey)
                    }.getOrDefault(false)
                    Toast.makeText(
                        this@SourceManageActivity,
                        if (ok) "已同步登录状态" else "已同步页面 Cookie",
                        Toast.LENGTH_SHORT
                    ).show()
                    reload()
                }
            }
            .setNegativeButton("取消", null)
            .setOnDismissListener {
                runCatching {
                    webView.stopLoading()
                    webView.destroy()
                }
            }
            .create()
        loginDialog.show()
        loginDialog.window?.clearFlags(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
        )
    }

    private fun showImportUrlDialog() {
        val input = EditText(this).apply {
            hint = "输入 .js 漫画源直链"
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("导入 Venera JS 源链接")
            .setView(input)
            .setPositiveButton("下载导入") { _, _ ->
                val url = input.text?.toString()?.trim().orEmpty()
                if (url.isNotEmpty()) {
                    installRepoItem(RepoSourceItem(name = "自定义源", fileName = "", key = "", version = "1.0.0", url = url))
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun openSourceSettings(src: ComicSourceEntity) {
        lifecycleScope.launch {
            val fields = runCatching { app.veneraRuntime.getSettingsSchema(src.sourceKey) }.getOrNull().orEmpty()
            if (fields.isEmpty()) {
                Toast.makeText(this@SourceManageActivity, "「${src.name}」暂无可配置项", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val currentJson = runCatching { JSONObject(src.settingsJson) }.getOrElse { JSONObject() }
            val container = LinearLayout(this@SourceManageActivity).apply {
                orientation = LinearLayout.VERTICAL
                val pad = (20 * resources.displayMetrics.density).toInt()
                setPadding(pad, pad / 2, pad, 0)
            }
            val readers = mutableListOf<() -> Pair<String, Any>>()

            for (f in fields) {
                val label = TextView(this@SourceManageActivity).apply {
                    text = f.title
                    textSize = 14f
                    setPadding(0, 12, 0, 4)
                }
                container.addView(label)
                val existingVal = if (currentJson.has(f.key)) currentJson.opt(f.key)?.toString() else f.defaultValue
                when (f.type) {
                    "switch" -> {
                        val cb = CheckBox(this@SourceManageActivity).apply {
                            text = "启用"
                            isChecked = existingVal == "true" || existingVal == "1"
                        }
                        container.addView(cb)
                        readers.add { f.key to cb.isChecked }
                    }
                    "select" -> {
                        val rg = RadioGroup(this@SourceManageActivity)
                        f.options.forEach { (optVal, optText) ->
                            val rb = RadioButton(this@SourceManageActivity).apply {
                                text = optText
                                tag = optVal
                                isChecked = optVal == existingVal
                            }
                            rg.addView(rb)
                        }
                        container.addView(rg)
                        readers.add {
                            val checkedId = rg.checkedRadioButtonId
                            val selected = rg.findViewById<RadioButton>(checkedId)?.tag?.toString() ?: existingVal.orEmpty()
                            f.key to selected
                        }
                    }
                    "callback" -> {
                        val btn = MaterialButton(
                            this@SourceManageActivity,
                            null,
                            com.google.android.material.R.attr.materialButtonOutlinedStyle
                        ).apply {
                            text = f.buttonText.ifEmpty { f.title }
                            setOnClickListener {
                                isEnabled = false
                                lifecycleScope.launch {
                                    runCatching {
                                        app.veneraRuntime.invokeSettingCallback(src.sourceKey, f.key)
                                    }.onSuccess {
                                        Toast.makeText(this@SourceManageActivity, "已执行「${f.title}」", Toast.LENGTH_SHORT).show()
                                    }.onFailure { err ->
                                        Toast.makeText(this@SourceManageActivity, "操作失败: ${err.message}", Toast.LENGTH_LONG).show()
                                    }
                                    isEnabled = true
                                }
                            }
                        }
                        container.addView(btn)
                    }
                    else -> {
                        val et = EditText(this@SourceManageActivity).apply {
                            setText(existingVal.orEmpty())
                        }
                        container.addView(et)
                        readers.add { f.key to et.text.toString() }
                    }
                }
            }

            val scroll = ScrollView(this@SourceManageActivity).apply {
                addView(container)
            }

            MaterialAlertDialogBuilder(this@SourceManageActivity)
                .setTitle("${src.name} 设置")
                .setView(scroll)
                .setPositiveButton("保存") { _, _ ->
                    val nextJson = JSONObject(src.settingsJson.ifBlank { "{}" })
                    readers.forEach { fn ->
                        val (k, v) = fn()
                        nextJson.put(k, v)
                    }
                    app.db.updateComicSourceSettings(src.sourceKey, nextJson.toString())
                    app.veneraRuntime.invalidateSource(src.sourceKey)
                    reload()
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun confirmDeleteSource(src: ComicSourceEntity) {
        val msg = if (src.builtIn) {
            "「${src.name}」为内置漫画源，建议直接关闭开关即可。确定仍要移除吗？"
        } else {
            "确定删除漫画源「${src.name}」吗？"
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("移除漫画源")
            .setMessage(msg)
            .setPositiveButton("删除") { _, _ ->
                app.db.deleteComicSource(src.sourceKey)
                app.veneraRuntime.invalidateSource(src.sourceKey)
                reload()
            }
            .setNegativeButton("取消", null)
            .show()
    }
}

private class SourceAdapter(
    private val onToggle: (ComicSourceEntity, Boolean) -> Unit,
    private val onAccount: (ComicSourceEntity) -> Unit,
    private val onConfig: (ComicSourceEntity) -> Unit,
    private val onDelete: (ComicSourceEntity) -> Unit
) : RecyclerView.Adapter<SourceAdapter.VH>() {

    private val items = mutableListOf<ComicSourceEntity>()

    fun submitList(newItems: List<ComicSourceEntity>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemComicSourceBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val b = holder.binding
        b.tvSourceName.text = "${item.name} (${item.version})"
        val originLabel = if (item.builtIn) "内置开源漫画源" else item.url.ifEmpty { "本地导入脚本" }
        b.tvSourceMeta.text = "key: ${item.sourceKey} · $originLabel"
        b.swEnabled.setOnCheckedChangeListener(null)
        b.swEnabled.isChecked = item.enabled
        b.swEnabled.setOnCheckedChangeListener { _, isChecked -> onToggle(item, isChecked) }
        b.btnSourceAccount.setOnClickListener { onAccount(item) }
        b.btnSourceConfig.setOnClickListener { onConfig(item) }
        b.btnSourceDelete.setOnClickListener { onDelete(item) }
    }

    class VH(val binding: ItemComicSourceBinding) : RecyclerView.ViewHolder(binding.root)
}
