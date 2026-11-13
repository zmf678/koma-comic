package com.arkreader.comic.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.arkreader.comic.ArkReaderApp
import com.arkreader.comic.data.Warehouse
import com.arkreader.comic.data.WarehouseType
import com.arkreader.comic.databinding.ActivityWebdavConfigBinding
import com.arkreader.comic.engine.LocalScanner
import com.arkreader.comic.engine.WebDavClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WebDavConfigActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWebdavConfigBinding
    private val app get() = ArkReaderApp.instance
    private var editingWarehouse: Warehouse? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityWebdavConfigBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }

        val whId = intent.getLongExtra(EXTRA_WAREHOUSE_ID, 0L)
        if (whId > 0L) {
            editingWarehouse = app.db.getWarehouse(whId)?.also { wh ->
                binding.toolbar.title = "编辑 WebDAV 仓库"
                binding.etName.setText(wh.name)
                binding.etUrl.setText(wh.uriOrUrl)
                binding.etUser.setText(wh.webdavUser)
                binding.etPass.setText(wh.webdavPass)
            }
        }

        binding.btnTest.setOnClickListener { testConnection() }
        binding.btnSave.setOnClickListener { saveAndScan() }
    }

    private fun testConnection() {
        val url = binding.etUrl.text?.toString()?.trim().orEmpty()
        val user = binding.etUser.text?.toString()?.trim().orEmpty()
        val pass = binding.etPass.text?.toString().orEmpty()
        if (url.isBlank()) {
            Toast.makeText(this, "请输入服务器地址", Toast.LENGTH_SHORT).show()
            return
        }
        binding.btnTest.isEnabled = false
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { WebDavClient(url, user, pass).listDirectory(url) }
            }
            binding.btnTest.isEnabled = true
            result.onSuccess { entries ->
                Toast.makeText(this@WebDavConfigActivity, "连接成功，发现 ${entries.size} 个条目", Toast.LENGTH_SHORT).show()
            }.onFailure { err ->
                Toast.makeText(this@WebDavConfigActivity, "连接失败: ${err.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun saveAndScan() {
        val url = binding.etUrl.text?.toString()?.trim().orEmpty()
        if (url.isBlank()) {
            Toast.makeText(this, "请输入服务器地址", Toast.LENGTH_SHORT).show()
            return
        }
        val name = binding.etName.text?.toString()?.trim().takeUnless { it.isNullOrEmpty() } ?: "WebDAV 仓库"
        val user = binding.etUser.text?.toString()?.trim().orEmpty()
        val pass = binding.etPass.text?.toString().orEmpty()

        val wh = (editingWarehouse ?: Warehouse(
            name = name,
            uriOrUrl = url,
            type = WarehouseType.WEBDAV
        )).copy(
            name = name,
            uriOrUrl = url,
            webdavUser = user,
            webdavPass = pass
        )
        val savedId = app.db.upsertWarehouse(wh)
        val saved = app.db.getWarehouse(savedId) ?: return
        binding.btnSave.isEnabled = false
        Toast.makeText(this, "已保存，正在扫描索引...", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch {
            val count = withContext(Dispatchers.IO) {
                runCatching {
                    LocalScanner(this@WebDavConfigActivity, app.db, app.coverCache).scanWarehouse(saved) {}
                }.getOrDefault(0)
            }
            Toast.makeText(this@WebDavConfigActivity, "扫描完成，发现 $count 部漫画", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    companion object {
        const val EXTRA_WAREHOUSE_ID = "extra_warehouse_id"
    }
}
