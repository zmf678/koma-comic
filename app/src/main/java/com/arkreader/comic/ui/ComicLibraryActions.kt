package com.arkreader.comic.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import com.arkreader.comic.ArkReaderApp
import com.arkreader.comic.data.Bookshelf
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText

internal fun FragmentActivity.openComicWebsite(url: String) {
    val uri = Uri.parse(url)
    if (uri.scheme !in setOf("https", "http") || uri.host.isNullOrBlank()) {
        Toast.makeText(this, "这个漫画源未提供官方网站", Toast.LENGTH_SHORT).show()
        return
    }
    try {
        startActivity(Intent(Intent.ACTION_VIEW, uri))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(this, "找不到可以打开网址的应用", Toast.LENGTH_SHORT).show()
    }
}

internal fun FragmentActivity.showComicBookshelves(comicId: Long, onSaved: () -> Unit) {
    val db = ArkReaderApp.instance.db
    val shelves = db.getBookshelves()
    val selected = db.getBookshelfIdsForComic(comicId).toMutableSet()
    MaterialAlertDialogBuilder(this)
        .setTitle("管理书架")
        .setMultiChoiceItems(shelves.map { it.name }.toTypedArray(), BooleanArray(shelves.size) { shelves[it].id in selected }) {
            _, index, checked ->
            val id = shelves[index].id
            if (checked) selected.add(id) else selected.remove(id)
        }
        .setPositiveButton("保存") { _, _ ->
            db.setComicBookshelves(comicId, selected)
            Toast.makeText(this, "已更新书架", Toast.LENGTH_SHORT).show()
            onSaved()
        }
        .setNeutralButton("新建书架") { _, _ ->
            val name = TextInputEditText(this)
            val dialog = MaterialAlertDialogBuilder(this)
                .setTitle("新建书架")
                .setView(name)
                .setNegativeButton("取消", null)
                .setPositiveButton("新建并加入", null)
                .show()
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val label = name.text?.toString()?.trim().orEmpty()
                if (label.isEmpty()) {
                    name.error = "请输入书架名称"
                } else {
                    selected.add(db.upsertBookshelf(Bookshelf(name = label, sortOrder = shelves.size)))
                    db.setComicBookshelves(comicId, selected)
                    Toast.makeText(this, "已加入「$label」", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                    onSaved()
                }
            }
        }
        .setNegativeButton("取消", null)
        .show()
}
