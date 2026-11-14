package com.arkreader.comic.ui

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.arkreader.comic.ArkReaderApp
import com.arkreader.comic.data.ComicSourceEntity
import com.arkreader.comic.data.RepoSourceItem
import com.arkreader.comic.databinding.ActivitySourceManageBinding
import com.arkreader.comic.databinding.ItemComicSourceBinding
import com.arkreader.comic.engine.SourceRepoClient
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
        val input = EditText(this).apply {
            setText(SourceRepoClient.DEFAULT_REPO_URL)
            hint = "Venera 源索引 index.json 链接"
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("订阅 Venera 动漫源仓库")
            .setMessage("兼容 Venera / Venera-Next 标准 index.json 源列表")
            .setView(input)
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
        val labels = repoItems.map { item ->
            val mark = if (installed.containsKey(item.key)) " [已安装]" else ""
            "${item.name} (${item.version})$mark"
        }.toTypedArray()

        MaterialAlertDialogBuilder(this)
            .setTitle("点击安装 / 更新动漫源 (${repoItems.size})")
            .setItems(labels) { _, idx ->
                installRepoItem(repoItems[idx])
            }
            .setNegativeButton("关闭", null)
            .show()
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
                    else -> {
                        val et = EditText(this@SourceManageActivity).apply {
                            setText(existingVal.orEmpty())
                        }
                        container.addView(et)
                        readers.add { f.key to et.text.toString() }
                    }
                }
            }

            MaterialAlertDialogBuilder(this@SourceManageActivity)
                .setTitle("${src.name} 设置")
                .setView(container)
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
        b.btnSourceConfig.setOnClickListener { onConfig(item) }
        b.btnSourceDelete.setOnClickListener { onDelete(item) }
    }

    class VH(val binding: ItemComicSourceBinding) : RecyclerView.ViewHolder(binding.root)
}
