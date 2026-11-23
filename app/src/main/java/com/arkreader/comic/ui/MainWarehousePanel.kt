package com.arkreader.comic.ui

import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.arkreader.comic.ArkReaderApp
import com.arkreader.comic.data.Warehouse
import com.arkreader.comic.data.WarehouseType
import com.arkreader.comic.databinding.ItemWarehouseBinding
import com.arkreader.comic.databinding.PanelMainWarehouseBinding
import com.arkreader.comic.engine.LocalScanner
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Embedded Warehouse management controller displayed in the right pane of MainActivity
 * so the left NavigationRail / bottom bar stays visible while managing repositories.
 */
class MainWarehousePanel(
    private val activity: AppCompatActivity,
    private val binding: PanelMainWarehouseBinding,
    private val onComicsChanged: () -> Unit,
    private val onScanAllRequested: () -> Unit
) {
    private val app get() = ArkReaderApp.instance

    private val adapter = WarehouseItemAdapter(
        onScan = { scanSingleWarehouse(it) },
        onConfig = { openWarehouseConfig(it) },
        onDelete = { confirmDeleteWarehouse(it) }
    )

    private val pickFolderLauncher = activity.registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) addLocalWarehouseFromUri(uri)
    }

    private val importArchiveLauncher = activity.registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) importArchivesFromUris(uris)
    }

    fun pickLocalFolder() {
        pickFolderLauncher.launch(null)
    }

    fun importLocalArchives() {
        importArchiveLauncher.launch(arrayOf("application/zip", "application/epub+zip", "application/pdf", "application/x-cbz", "*/*"))
    }

    private fun importArchivesFromUris(uris: List<Uri>) {
        activity.lifecycleScope.launch {
            val importDir = java.io.File(activity.filesDir, "imported_archives").apply { mkdirs() }
            var copied = 0
            withContext(Dispatchers.IO) {
                for (uri in uris) {
                    val doc = DocumentFile.fromSingleUri(activity, uri)
                    val rawName = doc?.name?.ifBlank { null } ?: "comic_${System.currentTimeMillis()}.cbz"
                    val safeName = rawName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
                    val target = java.io.File(importDir, safeName)
                    runCatching {
                        activity.contentResolver.openInputStream(uri)?.use { input ->
                            target.outputStream().use { output -> input.copyTo(output) }
                        }
                        copied++
                    }
                }
            }
            if (copied == 0) {
                Toast.makeText(activity, "未导入任何压缩包", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val dirUri = Uri.fromFile(importDir).toString()
            val existing = app.db.getWarehouses().find { it.uriOrUrl == dirUri }
            val wh = existing ?: run {
                val created = Warehouse(name = "本地导入压缩包", uriOrUrl = dirUri, type = WarehouseType.LOCAL_SAF)
                val id = app.db.upsertWarehouse(created)
                app.db.getWarehouse(id) ?: created.copy(id = id)
            }
            refresh()
            scanSingleWarehouse(wh)
        }
    }

    init {
        binding.rvMainWarehouses.layoutManager = LinearLayoutManager(activity)
        binding.rvMainWarehouses.adapter = adapter

        binding.btnWhAddLocal.setOnClickListener { pickFolderLauncher.launch(null) }
        binding.btnWhAddWebDav.setOnClickListener {
            activity.startActivity(Intent(activity, WebDavConfigActivity::class.java))
        }
        binding.btnWhManageSources.setOnClickListener {
            activity.startActivity(Intent(activity, SourceManageActivity::class.java))
        }
        binding.btnWhRescanAll.setOnClickListener { onScanAllRequested() }
    }

    fun refresh() {
        val list = app.db.getWarehouses()
        adapter.submitList(list)
        binding.tvWarehouseEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun addLocalWarehouseFromUri(uri: Uri) {
        runCatching {
            activity.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        val doc = DocumentFile.fromTreeUri(activity, uri)
        val name = doc?.name?.ifBlank { null } ?: uri.lastPathSegment ?: "本地仓库"
        val warehouse = Warehouse(
            name = name,
            uriOrUrl = uri.toString(),
            type = WarehouseType.LOCAL_SAF
        )
        val id = app.db.upsertWarehouse(warehouse)
        val saved = app.db.getWarehouse(id) ?: warehouse.copy(id = id)
        refresh()
        scanSingleWarehouse(saved)
    }

    private fun scanSingleWarehouse(wh: Warehouse) {
        Toast.makeText(activity, "正在扫描「${wh.name}」...", Toast.LENGTH_SHORT).show()
        activity.lifecycleScope.launch {
            val count = withContext(Dispatchers.IO) {
                runCatching {
                    LocalScanner(activity, app.db, app.coverCache).scanWarehouse(wh) {}
                }.getOrDefault(0)
            }
            refresh()
            onComicsChanged()
            Toast.makeText(activity, "「${wh.name}」扫描完成，索引 $count 部漫画", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openWarehouseConfig(wh: Warehouse) {
        if (wh.type == WarehouseType.WEBDAV) {
            activity.startActivity(Intent(activity, WebDavConfigActivity::class.java).apply {
                putExtra(WebDavConfigActivity.EXTRA_WAREHOUSE_ID, wh.id)
            })
            return
        }
        val pad = (20 * activity.resources.displayMetrics.density).toInt()
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val etName = EditText(activity).apply {
            hint = "仓库名称"
            setText(wh.name)
        }
        val etCover = EditText(activity).apply {
            hint = "封面关键词 (默认 cover)"
            setText(wh.coverName)
        }
        val swHide = MaterialSwitch(activity).apply {
            text = "在系统相册隐藏 (.nomedia)"
            isChecked = wh.hideInGallery
        }
        box.addView(etName)
        box.addView(etCover)
        box.addView(swHide)

        val shelves = app.db.getBookshelves()
        var chosenShelfId = wh.defaultBookshelfId
        if (shelves.isNotEmpty()) {
            val btnShelf = com.google.android.material.button.MaterialButton(
                activity,
                null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle
            ).apply {
                fun updateLabel() {
                    val shelfName = shelves.find { it.id == chosenShelfId }?.name ?: "不自动加入作品集"
                    text = "默认作品集: $shelfName"
                }
                updateLabel()
                setOnClickListener {
                    val names = (listOf("不自动加入作品集") + shelves.map { it.name }).toTypedArray()
                    val cur = (shelves.indexOfFirst { it.id == chosenShelfId } + 1).coerceAtLeast(0)
                    MaterialAlertDialogBuilder(activity)
                        .setTitle("选择默认作品集")
                        .setSingleChoiceItems(names, cur) { d, which ->
                            chosenShelfId = if (which == 0) 0L else shelves[which - 1].id
                            updateLabel()
                            d.dismiss()
                        }
                        .show()
                }
            }
            box.addView(btnShelf)
        }

        MaterialAlertDialogBuilder(activity)
            .setTitle("配置仓库「${wh.name}」")
            .setView(box)
            .setPositiveButton("保存") { _, _ ->
                val updated = wh.copy(
                    name = etName.text?.toString()?.trim()?.ifEmpty { wh.name } ?: wh.name,
                    coverName = etCover.text?.toString()?.trim()?.ifEmpty { "cover" } ?: "cover",
                    hideInGallery = swHide.isChecked,
                    defaultBookshelfId = chosenShelfId
                )
                app.db.upsertWarehouse(updated)
                syncNoMedia(updated)
                refresh()
                onComicsChanged()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun syncNoMedia(wh: Warehouse) {
        if (wh.type != WarehouseType.LOCAL_SAF) return
        activity.lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                val root = DocumentFile.fromTreeUri(activity, Uri.parse(wh.uriOrUrl)) ?: return@runCatching
                val existing = root.findFile(".nomedia")
                if (wh.hideInGallery && existing == null) {
                    root.createFile("application/octet-stream", ".nomedia")
                } else if (!wh.hideInGallery && existing != null) {
                    existing.delete()
                }
            }
        }
    }

    private fun confirmDeleteWarehouse(wh: Warehouse) {
        MaterialAlertDialogBuilder(activity)
            .setTitle("删除仓库")
            .setMessage("确认移除「${wh.name}」及其索引记录？本地原始漫画文件不会被删除。")
            .setPositiveButton("移除") { _, _ ->
                app.db.deleteWarehouse(wh.id)
                refresh()
                onComicsChanged()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private class WarehouseItemAdapter(
        private val onScan: (Warehouse) -> Unit,
        private val onConfig: (Warehouse) -> Unit,
        private val onDelete: (Warehouse) -> Unit
    ) : RecyclerView.Adapter<WarehouseItemAdapter.VH>() {

        private val items = mutableListOf<Warehouse>()
        private val dateFmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

        fun submitList(list: List<Warehouse>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val binding = ItemWarehouseBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(binding)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            val b = holder.binding
            b.tvWhName.text = item.name
            val typeLabel = if (item.type == WarehouseType.WEBDAV) "WebDAV" else "本地目录"
            val scanTime = if (item.lastScannedAt > 0L) " · 上次扫描 ${dateFmt.format(Date(item.lastScannedAt))}" else ""
            b.tvWhType.text = "$typeLabel$scanTime · ${item.uriOrUrl}"
            b.btnWhScan.setOnClickListener { onScan(item) }
            b.btnWhConfig.setOnClickListener { onConfig(item) }
            b.btnWhDelete.setOnClickListener { onDelete(item) }
        }

        class VH(val binding: ItemWarehouseBinding) : RecyclerView.ViewHolder(binding.root)
    }
}
