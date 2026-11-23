package com.arkreader.comic.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.content.res.ColorStateList
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Build
import android.view.View
import android.widget.ImageView
import androidx.core.graphics.ColorUtils
import com.arkreader.comic.ArkReaderApp
import com.arkreader.comic.R
import com.google.android.material.chip.Chip
import java.io.File

internal fun saveLibraryWallpaper(context: Context, uri: Uri) =
    savePersonalImage(context, uri, "library-wallpaper.jpg", 2560)

internal fun saveProfileAvatar(context: Context, uri: Uri) =
    savePersonalImage(context, uri, "profile-avatar.jpg", 512)

private fun savePersonalImage(context: Context, uri: Uri, name: String, maximum: Int) {
    val resolver = context.contentResolver
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    require(options.outWidth > 0 && options.outHeight > 0) { "无法读取这张图片" }
    options.inJustDecodeBounds = false
    options.inSampleSize = 1
    while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize > maximum) options.inSampleSize *= 2
    val bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        ?: error("无法读取这张图片")
    val wallpaper = File(context.filesDir, name)
    val pending = File.createTempFile("personal-", ".tmp", context.filesDir)
    try {
        pending.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
        check(pending.renameTo(wallpaper)) { "无法保存图片" }
    } finally {
        bitmap.recycle()
        pending.delete()
    }
}

internal fun applyLibraryMaterial(root: View, wallpaper: ImageView, surface: View) {
    val prefs = ArkReaderApp.instance.prefs
    val file = File(root.context.filesDir, "library-wallpaper.jpg")
    val hasWallpaper = prefs.wallpaperEnabled && file.isFile
    if (wallpaper.tag != (if (hasWallpaper) file.lastModified() else null)) {
        wallpaper.tag = if (hasWallpaper) file.lastModified() else null
        wallpaper.setImageBitmap(if (hasWallpaper) BitmapFactory.decodeFile(file.absolutePath) else null)
    }
    val dark = root.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    if (Build.VERSION.SDK_INT >= 31) {
        wallpaper.setRenderEffect(if (prefs.glassMaterial && hasWallpaper) RenderEffect.createBlurEffect(20f, 20f, Shader.TileMode.CLAMP) else null)
    }
    val ink = if (dark) 0xFF0E1525.toInt() else 0xFFF4F7FD.toInt()
    surface.background = if (prefs.glassMaterial) {
        GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(
            ColorUtils.setAlphaComponent(if (dark) 0xFF141D32.toInt() else 0xFFF2F6FE.toInt(), if (hasWallpaper) 204 else 255),
            ColorUtils.setAlphaComponent(if (dark) 0xFF0E1629.toInt() else 0xFFEBF2FC.toInt(), if (hasWallpaper) 194 else 255),
            ColorUtils.setAlphaComponent(if (dark) 0xFF171E35.toInt() else 0xFFF4F0FC.toInt(), if (hasWallpaper) 204 else 255)
        )).apply {
            cornerRadius = 22 * root.resources.displayMetrics.density
            setStroke((root.resources.displayMetrics.density).toInt().coerceAtLeast(1), if (dark) 0x52FFFFFF else 0xE6FFFFFF.toInt())
        }
    } else if (hasWallpaper) GradientDrawable().apply { setColor(ColorUtils.setAlphaComponent(ink, 218)) }
    else null
}

internal fun ImageView.showCoverLoading(loading: Boolean) {
    setImageDrawable(CoverStatusDrawable(context, if (loading) "加载中" else "暂无封面"))
}

internal fun ImageView.finishCover(bitmap: Bitmap?) {
    if (bitmap == null) {
        showCoverLoading(false)
        return
    }
    val transition = drawable is CoverStatusDrawable
    setImageBitmap(bitmap)
    if (transition && android.animation.ValueAnimator.areAnimatorsEnabled()) {
        animate().cancel()
        alpha = 0.64f
        animate().alpha(1f).setDuration(180).start()
    }
}

private class CoverStatusDrawable(context: Context, private val label: String) : Drawable() {
    private val density = context.resources.displayMetrics
    private val surface = Paint().apply { color = context.getColor(R.color.surface_card) }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.text_secondary)
        textAlign = Paint.Align.CENTER
        textSize = 12f * density.scaledDensity
    }

    override fun draw(canvas: Canvas) {
        canvas.drawRect(bounds, surface)
        val size = paint.textSize
        paint.textSize = minOf(size, bounds.width() / 5f)
        canvas.drawText(label, bounds.exactCenterX(), bounds.exactCenterY() - (paint.ascent() + paint.descent()) / 2, paint)
        paint.textSize = size
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter }
    @Deprecated("Android drawable compatibility")
    override fun getOpacity() = PixelFormat.OPAQUE
}

internal fun Chip.applyGlassChip() {
    if (!ArkReaderApp.instance.prefs.glassMaterial) return
    val dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    chipBackgroundColor = ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
        if (dark) intArrayOf(0x9638A3FF.toInt(), 0xB81E2B44.toInt())
        else intArrayOf(0xE0D4E9FF.toInt(), 0xDCFFFFFF.toInt())
    )
    chipStrokeWidth = resources.displayMetrics.density
    chipStrokeColor = ColorStateList.valueOf(if (dark) 0x5EFFFFFF else 0xE6FFFFFF.toInt())
}

internal fun applyFloatingDock(capsule: View, settings: View?) {
    val density = capsule.resources.displayMetrics.density
    val dark = capsule.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    val advanced = ArkReaderApp.instance.prefs.glassMaterial
    fun surface(selected: Boolean = false) = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        when {
            selected && dark -> intArrayOf(0xA642A2FF.toInt(), 0x7A2466C6.toInt())
            selected -> intArrayOf(0xEDE0F1FF.toInt(), 0xD8C2E0FF.toInt())
            dark -> intArrayOf(if (advanced) 0xCC222E46.toInt() else 0xE2222E44.toInt(), if (advanced) 0xB0121A2A.toInt() else 0xD4172033.toInt())
            else -> intArrayOf(if (advanced) 0xE0FFFFFF.toInt() else 0xF4FFFFFF.toInt(), if (advanced) 0xC4F0F6FF.toInt() else 0xE8EEF4FC.toInt())
        }
    ).apply {
        cornerRadius = 34 * density
        setStroke(density.toInt().coerceAtLeast(1), if (dark) 0x70FFFFFF else 0xF5FFFFFF.toInt())
    }
    capsule.background = surface()
    capsule.elevation = 6 * density
    if (settings != null) {
        val plate = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_selected), surface(selected = true))
            addState(intArrayOf(), surface())
        }
        settings.background = RippleDrawable(ColorStateList.valueOf(if (dark) 0x3886BDFF else 0x303888E0), plate, null)
        settings.elevation = 6 * density
    }
}
