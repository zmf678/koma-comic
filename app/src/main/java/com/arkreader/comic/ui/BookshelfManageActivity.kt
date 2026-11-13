package com.arkreader.comic.ui

import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.arkreader.comic.ArkReaderApp
import com.arkreader.comic.data.Bookshelf
import com.arkreader.comic.data.DatabaseHelper
import com.arkreader.comic.data.LockType
import com.arkreader.comic.databinding.ActivityBookshelfManageBinding
import com.arkreader.comic.databinding.ItemBookshelfBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class BookshelfManageActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBookshelfManageBinding
    private val app get() = ArkReaderApp.instance

    private val adapter = BookshelfAdapter(
        onLock = { shelf -> showShelfConfigDialog(shelf) },
        onDelete = { shelf -> confirmDeleteShelf(shelf) }
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityBookshelfManageBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.rvBookshelves.layoutManager = LinearLayoutManager(this)
        binding.rvBookshelves.adapter = adapter

        binding.btnAddShelf.setOnClickListener { showCreateShelfDialog() }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        adapter.submitList(app.db.getBookshelves())
    }

    private fun showCreateShelfDialog() {
        val input = EditText(this).apply { hint = "输入新书架名称" }
        MaterialAlertDialogBuilder(this)
            .setTitle("新建书架")
            .setView(input)
            .setPositiveButton("创建") { _, _ ->
                val name = input.text?.toString()?.trim().orEmpty()
                if (name.isBlank()) {
                    Toast.makeText(this, "书架名称不能为空", Toast.LENGTH_SHORT).show()
                } else {
                    val existing = app.db.getBookshelves()
                    app.db.upsertBookshelf(Bookshelf(name = name, sortOrder = existing.size))
                    reload()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showShelfConfigDialog(shelf: Bookshelf) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
        }
        val etName = EditText(this).apply {
            hint = "书架名称"
            setText(shelf.name)
        }
        val cbHidden = CheckBox(this).apply {
            text = "从首页「全部」中隐藏此书架的漫画"
            isChecked = shelf.isHiddenFromAll
        }
        val cbQuickRelock = CheckBox(this).apply {
            text = "切出后台超过 2 秒自动重新上锁"
            isChecked = shelf.quickRelock
        }

        val rgLock = RadioGroup(this)
        val rbNone = RadioButton(this).apply { text = "无加密" }
        val rbPin = RadioButton(this).apply { text = "数字 PIN 密码" }
        val rbBio = RadioButton(this).apply { text = "指纹 / 生物识别" }
        rgLock.addView(rbNone)
        rgLock.addView(rbPin)
        rgLock.addView(rbBio)
        when (shelf.lockType) {
            LockType.NONE -> rbNone.isChecked = true
            LockType.PIN -> rbPin.isChecked = true
            LockType.BIOMETRIC -> rbBio.isChecked = true
        }

        val etPin = EditText(this).apply {
            hint = if (shelf.pinHash.isNotEmpty()) "留空保持原密码，或输入新 PIN" else "设置数字 PIN 密码"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }

        container.addView(etName)
        container.addView(cbHidden)
        container.addView(cbQuickRelock)
        container.addView(rgLock)
        container.addView(etPin)

        MaterialAlertDialogBuilder(this)
            .setTitle("书架设置与加密")
            .setView(container)
            .setPositiveButton("保存") { _, _ ->
                val newLock = when {
                    rbPin.isChecked -> LockType.PIN
                    rbBio.isChecked -> LockType.BIOMETRIC
                    else -> LockType.NONE
                }
                val newPinRaw = etPin.text?.toString().orEmpty()
                val pinHash = when {
                    newLock != LockType.PIN -> ""
                    newPinRaw.isNotEmpty() -> DatabaseHelper.hashPin(newPinRaw)
                    shelf.pinHash.isNotEmpty() -> shelf.pinHash
                    else -> {
                        Toast.makeText(this, "请设置 PIN 密码", Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                }
                app.db.upsertBookshelf(
                    shelf.copy(
                        name = etName.text?.toString()?.trim().takeUnless { it.isNullOrEmpty() } ?: shelf.name,
                        isHiddenFromAll = cbHidden.isChecked,
                        quickRelock = cbQuickRelock.isChecked,
                        lockType = newLock,
                        pinHash = pinHash
                    )
                )
                reload()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmDeleteShelf(shelf: Bookshelf) {
        if (shelf.id == 1L) {
            Toast.makeText(this, "默认书架不可删除", Toast.LENGTH_SHORT).show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("删除书架")
            .setMessage("确定删除书架「${shelf.name}」吗？（不会删除漫画本身）")
            .setPositiveButton("删除") { _, _ ->
                app.db.deleteBookshelf(shelf.id)
                if (app.prefs.activeBookshelfId == shelf.id) {
                    app.prefs.activeBookshelfId = 1L
                }
                reload()
            }
            .setNegativeButton("取消", null)
            .show()
    }
}

private class BookshelfAdapter(
    private val onLock: (Bookshelf) -> Unit,
    private val onDelete: (Bookshelf) -> Unit
) : RecyclerView.Adapter<BookshelfAdapter.VH>() {

    private val items = mutableListOf<Bookshelf>()

    fun submitList(newItems: List<Bookshelf>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemBookshelfBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val s = items[position]
        holder.binding.tvShelfName.text = s.name
        val lockStr = when (s.lockType) {
            LockType.NONE -> "未加密"
            LockType.PIN -> "PIN 加密"
            LockType.BIOMETRIC -> "生物识别加密"
        }
        val hideStr = if (s.isHiddenFromAll) " · 首页隐藏" else ""
        holder.binding.tvShelfMeta.text = lockStr + hideStr
        holder.binding.btnShelfLock.setOnClickListener { onLock(s) }
        holder.binding.btnShelfDelete.setOnClickListener { onDelete(s) }
    }

    class VH(val binding: ItemBookshelfBinding) : RecyclerView.ViewHolder(binding.root)
}
