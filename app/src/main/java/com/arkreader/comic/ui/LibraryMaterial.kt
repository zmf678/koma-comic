package com.arkreader.comic.ui

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
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
import com.google.android.material.card.MaterialCardView
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

internal fun applyLibraryMaterial(root: View, wallpaper: ImageView, vararg surfaces: View) {
    val prefs = ArkReaderApp.instance.prefs
    val file = File(root.context.filesDir, "library-wallpaper.jpg")
    val hasWallpaper = prefs.wallpaperEnabled && file.isFile
    val dark = root.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    val desiredTag: Any = when {
        hasWallpaper -> file.lastModified()
        prefs.glassMaterial -> "ambient-$dark"
        else -> "none"
    }
    if (wallpaper.tag != desiredTag) {
        wallpaper.tag = desiredTag
        when {
            hasWallpaper -> wallpaper.setImageBitmap(BitmapFactory.decodeFile(file.absolutePath))
            prefs.glassMaterial -> wallpaper.setImageDrawable(AmbientGlassCanvasDrawable(dark))
            else -> wallpaper.setImageDrawable(null)
        }
    }
    if (Build.VERSION.SDK_INT >= 31) {
        // Keep custom wallpaper razor-sharp (zero blur); only Liquid Glass surfaces blur & refract locally
        wallpaper.setRenderEffect(null)
    }
    // Synchronize wallpaper opacity with Windows PC (0.62 dark / 0.86 light) for crisp contrast
    wallpaper.alpha = when {
        hasWallpaper -> if (dark) 0.62f else 0.86f
        prefs.glassMaterial -> if (dark) 0.88f else 0.78f
        else -> 1f
    }
    val ink = if (dark) 0xFF0F131A.toInt() else 0xFFF4F6FA.toInt()
    val scrim = when {
        prefs.glassMaterial -> null
        hasWallpaper -> GradientDrawable().apply { setColor(ColorUtils.setAlphaComponent(ink, if (dark) 168 else 184)) }
        else -> null
    }
    surfaces.forEach { surface ->
        surface.background = scrim?.constantState?.newDrawable()?.mutate()
    }
}

/**
 * Applies Windows MakeCardBlock()-equivalent frosted glass or crisp bordered card styling to a MaterialCardView.
 */
internal fun MaterialCardView.applyGlassCardStyle(cornerRadiusDp: Float = 16f) {
    val density = resources.displayMetrics.density
    val dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    val glass = ArkReaderApp.instance.prefs.glassMaterial
    radius = cornerRadiusDp * density
    if (glass) {
        setCardBackgroundColor(
            if (dark) 0xBC182234.toInt() else 0xDAFFFFFF.toInt()
        )
        strokeWidth = (1.1f * density).toInt().coerceAtLeast(1)
        strokeColor = if (dark) 0x52FFFFFF else 0xE8FFFFFF.toInt()
        cardElevation = 4f * density
    } else {
        setCardBackgroundColor(context.getColor(R.color.surface_card))
        strokeWidth = (1f * density).toInt().coerceAtLeast(1)
        strokeColor = context.getColor(R.color.surface_border)
        cardElevation = if (dark) 0f else 1.5f * density
    }
}

/**
 * Applies Windows Brush.BgElevated + Brush.Border gradient surface to any View container.
 */
internal fun View.applyGlassPanelSurface(cornerRadiusDp: Float = 18f) {
    val density = resources.displayMetrics.density
    val dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    val glass = ArkReaderApp.instance.prefs.glassMaterial
    background = if (glass) {
        GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            if (dark) intArrayOf(0xC41E293E.toInt(), 0xAC131B2B.toInt())
            else intArrayOf(0xE4FFFFFF.toInt(), 0xC6F6FAFF.toInt())
        ).apply {
            cornerRadius = cornerRadiusDp * density
            setStroke((1.1f * density).toInt().coerceAtLeast(1), if (dark) 0x58FFFFFF else 0xECFFFFFF.toInt())
        }
    } else {
        GradientDrawable().apply {
            cornerRadius = cornerRadiusDp * density
            setColor(context.getColor(R.color.surface_card))
            setStroke((1f * density).toInt().coerceAtLeast(1), context.getColor(R.color.surface_border))
        }
    }
    elevation = (if (glass) 4f else if (dark) 0f else 1.5f) * density
}

/**
 * Styles an inline action pill button to match Windows LiquidGlassSurface(SubtlePill) or bordered surface pill.
 */
internal fun View.applyGlassActionPill(backdrop: View?, cornerRadiusDp: Float = 16f) {
    val density = resources.displayMetrics.density
    val dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    if (ArkReaderApp.instance.prefs.glassMaterial && backdrop != null) {
        applyLiquidGlass(
            backdropView = backdrop,
            preset = LiquidGlassPreset.SUBTLE_PILL,
            cornerRadiusDp = cornerRadiusDp,
            interactiveElastic = true,
            elevationDp = 4f
        )
        return
    }
    detachLiquidGlass()
    background = GradientDrawable().apply {
        cornerRadius = cornerRadiusDp * density
        setColor(context.getColor(R.color.surface_pill))
        setStroke((1f * density).toInt().coerceAtLeast(1), context.getColor(R.color.surface_border))
    }
    elevation = (if (dark) 0f else 1f) * density
}

internal fun ImageView.showCoverLoading(loading: Boolean) {
    setImageDrawable(CoverStatusDrawable(context, if (loading) "加载中" else "暂无封面"))
}

internal fun ImageView.finishCover(bitmap: Bitmap?, onReady: ((Bitmap?) -> Unit)? = null) {
    if (bitmap == null) {
        showCoverLoading(false)
        onReady?.invoke(null)
        return
    }
    val transition = drawable is CoverStatusDrawable
    setImageBitmap(bitmap)
    onReady?.invoke(bitmap)
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
    val dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    val glass = ArkReaderApp.instance.prefs.glassMaterial
    if (glass) {
        chipBackgroundColor = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            if (dark) intArrayOf(0x9638A3FF.toInt(), 0xB81E2B44.toInt())
            else intArrayOf(0xE0D4E9FF.toInt(), 0xDCFFFFFF.toInt())
        )
        chipStrokeWidth = resources.displayMetrics.density
        chipStrokeColor = ColorStateList.valueOf(if (dark) 0x5EFFFFFF else 0xE6FFFFFF.toInt())
    } else {
        chipBackgroundColor = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            if (dark) intArrayOf(0x3638A3FF, 0xFF1E2533.toInt())
            else intArrayOf(0x241D7FE8, 0xFFFFFFFF.toInt())
        )
        chipStrokeWidth = resources.displayMetrics.density
        chipStrokeColor = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            if (dark) intArrayOf(0xFF38A3FF.toInt(), 0x24FFFFFF)
            else intArrayOf(0xFF1D7FE8.toInt(), 0x200F172A)
        )
    }
}

internal fun applyFloatingDock(capsule: View, settings: View?, backdrop: View? = null) {
    val density = capsule.resources.displayMetrics.density
    val dark = capsule.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    val advanced = ArkReaderApp.instance.prefs.glassMaterial
    if (advanced && backdrop != null) {
        capsule.applyLiquidGlass(backdrop, LiquidGlassPreset.FLOATING_DOCK, 34f, elevationDp = 7f)
        if (settings != null) {
            val orb = settings.applyLiquidGlass(
                backdrop,
                LiquidGlassPreset.FLOATING_DOCK,
                28f,
                interactiveElastic = true,
                elevationDp = 7f
            )
            orb.setSelectedGlow(settings.isSelected)
        }
        return
    }
    capsule.detachLiquidGlass()
    settings?.detachLiquidGlass()
    fun surface(selected: Boolean = false) = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        when {
            selected && dark -> intArrayOf(0xA642A2FF.toInt(), 0x7A2466C6.toInt())
            selected -> intArrayOf(0xEDE0F1FF.toInt(), 0xD8C2E0FF.toInt())
            dark -> intArrayOf(0xE8171D27.toInt(), 0xE2131821.toInt())
            else -> intArrayOf(0xFAFFFFFF.toInt(), 0xF2EFF4FC.toInt())
        }
    ).apply {
        cornerRadius = 34 * density
        setStroke(density.toInt().coerceAtLeast(1), if (dark) 0x38FFFFFF else 0x220F172A)
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

internal fun applyMainGlassChrome(
    backdrop: View,
    searchPill: View?,
    browseBar: View?,
    bottomNav: View?,
    profileButton: View?
) {
    val enabled = ArkReaderApp.instance.prefs.glassMaterial
    val density = backdrop.resources.displayMetrics.density
    val dark = backdrop.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    if (enabled) {
        searchPill?.applyLiquidGlass(backdrop, LiquidGlassPreset.FROSTED_BAR, 22f, elevationDp = 4f)
        browseBar?.applyLiquidGlass(backdrop, LiquidGlassPreset.FROSTED_BAR, 20f, elevationDp = 4f)
        bottomNav?.applyLiquidGlass(backdrop, LiquidGlassPreset.FLOATING_DOCK, 28f, elevationDp = 8f)
        profileButton?.applyLiquidGlass(backdrop, LiquidGlassPreset.FLOATING_DOCK, 24f, interactiveElastic = true, elevationDp = 5f)
    } else {
        searchPill?.apply {
            detachLiquidGlass()
            setBackgroundResource(R.drawable.bg_search_pill)
            elevation = if (dark) 0f else 1.5f * density
        }
        browseBar?.apply {
            detachLiquidGlass()
            background = GradientDrawable().apply {
                cornerRadius = 20f * density
                setColor(context.getColor(R.color.surface_card))
                setStroke((1f * density).toInt().coerceAtLeast(1), context.getColor(R.color.surface_border))
            }
            elevation = if (dark) 0f else 1.5f * density
        }
        bottomNav?.apply {
            detachLiquidGlass()
            background = GradientDrawable().apply {
                cornerRadius = 28f * density
                setColor(context.getColor(R.color.surface_card))
                setStroke((1f * density).toInt().coerceAtLeast(1), context.getColor(R.color.surface_border))
            }
            elevation = 6f * density
        }
        profileButton?.detachLiquidGlass()
    }
}
