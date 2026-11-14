package com.arkreader.comic.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.CheckBox
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
import com.arkreader.comic.databinding.ActivityWarehouseBinding
import com.arkreader.comic.databinding.ItemWarehouseBinding
import com.arkreader.comic.engine.LocalScanner
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class WarehouseActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWarehouseBinding
    private val app get() = ArkReaderApp.instance
    private var scanningId: Long = -1L

    private val adapter = WarehouseAdapter(
        onScan = { wh -> scanWarehouse(wh) },
        onConfig = { wh -> showConfigDialog(wh) },
        onDelete = { wh -> confirmDelete(wh) }
    )

    private val pickFolderLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        try {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            contentResolver.takePersistableUriPermission(uri, flags)
        } catch (_: SecurityException) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
            }
        }
        val doc = DocumentFile.fromTreeUri(this, uri)
        val displayName = doc?.name?.takeIf { it.isNotBlank() } ?: "本地漫画目录"
        val id = app.db.upsertWarehouse(
            Warehouse(
                name = displayName,
                uriOrUrl = uri.toString(),
                type = WarehouseType.LOCAL_SAF
            )
        )
        loadWarehouses()
        app.db.getWarehouse(id)?.let { scanWarehouse(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityWarehouseBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.rvWarehouses.layoutManager = LinearLayoutManager(this)
        binding.rvWarehouses.adapter = adapter

        binding.btnAddLocal.setOnClickListener {
            pickFolderLauncher.launch(null)
        }
        binding.btnAddWebDav.setOnClickListener {
            startActivity(Intent(this, WebDavConfigActivity::class.java))
        }
        binding.btnManageSources.setOnClickListener {
            startActivity(Intent(this, SourceManageActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        loadWarehouses()
    }

    private fun loadWarehouses() {
        adapter.submitList(app.db.getWarehouses())
    }

    private fun scanWarehouse(wh: Warehouse) {
        if (scanningId == wh.id) return
        scanningId = wh.id
        Toast.makeText(this, "开始扫描「${wh.name}」...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val count = withContext(Dispatchers.IO) {
                LocalScanner(this@WarehouseActivity, app.db, app.coverCache).scanWarehouse(wh) {}
            }
            scanningId = -1L
            loadWarehouses()
            Toast.makeText(this@WarehouseActivity, "「${wh.name}」扫描完成，共 $count 部漫画", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showConfigDialog(wh: Warehouse) {
        if (wh.type == WarehouseType.WEBDAV) {
            startActivity(Intent(this, WebDavConfigActivity::class.java).apply {
                putExtra(WebDavConfigActivity.EXTRA_WAREHOUSE_ID, wh.id)
            })
            return
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
        }
        val etName = EditText(this).apply {
            hint = "仓库名称"
            setText(wh.name)
        }
        val etCover = EditText(this).apply {
            hint = "自定义封面文件名（如 cover，不含扩展名）"
            setText(wh.coverName)
        }
        val cbHideGallery = CheckBox(this).apply {
            text = "在本地全部中隐藏（仅通过特定作品集或仓库访问）"
            isChecked = wh.hideInGallery
        }
        val cbNomedia = CheckBox(this).apply {
            text = "在目录创建 .nomedia（防止系统相册扫描）"
            isChecked = false
        }
        container.addView(etName)
        container.addView(etCover)
        container.addView(cbHideGallery)
        container.addView(cbNomedia)

        MaterialAlertDialogBuilder(this)
            .setTitle("仓库配置")
            .setView(container)
            .setPositiveButton("保存") { _, _ ->
                val updated = wh.copy(
                    name = etName.text?.toString()?.trim().takeUnless { it.isNullOrEmpty() } ?: wh.name,
                    coverName = etCover.text?.toString()?.trim().takeUnless { it.isNullOrEmpty() } ?: "cover",
                    hideInGallery = cbHideGallery.isChecked
                )
                app.db.upsertWarehouse(updated)
                if (cbNomedia.isChecked) {
                    createNomediaIfPossible(wh.uriOrUrl)
                }
                loadWarehouses()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun createNomediaIfPossible(uriStr: String) {
        try {
            val tree = DocumentFile.fromTreeUri(this, Uri.parse(uriStr)) ?: return
            if (tree.findFile(".nomedia") == null) {
                tree.createFile("application/octet-stream", ".nomedia")
            }
            Toast.makeText(this, "已创建 .nomedia", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "创建 .nomedia 失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmDelete(wh: Warehouse) {
        MaterialAlertDialogBuilder(this)
            .setTitle("删除仓库")
            .setMessage("确定移除仓库「${wh.name}」及其索引数据吗？（不会删除原始文件）")
            .setPositiveButton("移除") { _, _ ->
                app.db.deleteWarehouse(wh.id)
                loadWarehouses()
            }
            .setNegativeButton("取消", null)
            .show()
    }
}

private class WarehouseAdapter(
    private val onScan: (Warehouse) -> Unit,
    private val onConfig: (Warehouse) -> Unit,
    private val onDelete: (Warehouse) -> Unit
) : RecyclerView.Adapter<WarehouseAdapter.VH>() {

    private val items = mutableListOf<Warehouse>()
    private val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    fun submitList(newItems: List<Warehouse>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemWarehouseBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val wh = items[position]
        val typeText = if (wh.type == WarehouseType.WEBDAV) "WebDAV" else "本地目录 (SAF)"
        val scanText = if (wh.lastScannedAt > 0L) "上次扫描: ${sdf.format(Date(wh.lastScannedAt))}" else "尚未扫描"
        holder.binding.tvWhName.text = wh.name
        holder.binding.tvWhType.text = "$typeText · $scanText"
        holder.binding.btnWhScan.setOnClickListener { onScan(wh) }
        holder.binding.btnWhConfig.setOnClickListener { onConfig(wh) }
        holder.binding.btnWhDelete.setOnClickListener { onDelete(wh) }
    }

    class VH(val binding: ItemWarehouseBinding) : RecyclerView.ViewHolder(binding.root)
}
