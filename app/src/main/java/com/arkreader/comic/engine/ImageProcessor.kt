package com.arkreader.comic.engine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import java.io.InputStream
import kotlin.math.max
import kotlin.math.min

object ImageProcessor {

    fun decodeImageBounds(openStream: () -> InputStream?): Pair<Int, Int>? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openStream()?.use { BitmapFactory.decodeStream(it, null, bounds) }
        return if (bounds.outWidth > 0 && bounds.outHeight > 0) {
            bounds.outWidth to bounds.outHeight
        } else {
            null
        }
    }

    fun decodeSampledBitmap(
        openStream: () -> InputStream?,
        reqWidth: Int,
        reqHeight: Int
    ): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openStream()?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val sampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, reqWidth, reqHeight)
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return openStream()?.use { BitmapFactory.decodeStream(it, null, opts) }
    }

    fun calculateInSampleSize(rawW: Int, rawH: Int, reqW: Int, reqH: Int): Int {
        var inSampleSize = 1
        if (rawH > reqH || rawW > reqW) {
            val halfH = rawH / 2
            val halfW = rawW / 2
            while ((halfH / inSampleSize) >= reqH && (halfW / inSampleSize) >= reqW) {
                inSampleSize *= 2
            }
        }
        return max(1, inSampleSize)
    }

    /**
     * Trims bright white borders around a comic page.
     * strength: 0..50 tolerance below pure white (255).
     */
    fun trimWhiteMargins(
        src: Bitmap,
        strength: Int = 18,
        ignoreSides: Boolean = true,
        protect10Percent: Boolean = true
    ): Bitmap {
        val w = src.width
        val h = src.height
        if (w < 16 || h < 16) return src

        val threshold = (255 - strength.coerceIn(2, 60)).coerceAtLeast(180)
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)

        fun isWhitePixel(px: Int): Boolean {
            val r = (px shr 16) and 0xFF
            val g = (px shr 8) and 0xFF
            val b = px and 0xFF
            return r >= threshold && g >= threshold && b >= threshold
        }

        val maxTop = if (protect10Percent) (h * 0.10f).toInt() else h / 3
        val maxBottom = if (protect10Percent) (h * 0.10f).toInt() else h / 3
        val maxLeft = if (ignoreSides) 0 else if (protect10Percent) (w * 0.10f).toInt() else w / 3
        val maxRight = if (ignoreSides) 0 else if (protect10Percent) (w * 0.10f).toInt() else w / 3

        val stepX = max(1, w / 80)
        val stepY = max(1, h / 80)

        var top = 0
        while (top < maxTop) {
            var allWhite = true
            var x = 0
            while (x < w) {
                if (!isWhitePixel(pixels[top * w + x])) {
                    allWhite = false
                    break
                }
                x += stepX
            }
            if (!allWhite) break
            top++
        }

        var bottom = h - 1
        val minBottomY = h - 1 - maxBottom
        while (bottom > minBottomY && bottom > top + 8) {
            var allWhite = true
            var x = 0
            while (x < w) {
                if (!isWhitePixel(pixels[bottom * w + x])) {
                    allWhite = false
                    break
                }
                x += stepX
            }
            if (!allWhite) break
            bottom--
        }

        var left = 0
        while (left < maxLeft) {
            var allWhite = true
            var y = top
            while (y <= bottom) {
                if (!isWhitePixel(pixels[y * w + left])) {
                    allWhite = false
                    break
                }
                y += stepY
            }
            if (!allWhite) break
            left++
        }

        var right = w - 1
        val minRightX = w - 1 - maxRight
        while (right > minRightX && right > left + 8) {
            var allWhite = true
            var y = top
            while (y <= bottom) {
                if (!isWhitePixel(pixels[y * w + right])) {
                    allWhite = false
                    break
                }
                y += stepY
            }
            if (!allWhite) break
            right--
        }

        val newW = right - left + 1
        val newH = bottom - top + 1
        if (newW >= w && newH >= h) return src
        if (newW < 16 || newH < 16) return src

        return Bitmap.createBitmap(src, left, top, newW, newH)
    }

    fun isBlankPage(src: Bitmap): Boolean {
        val w = src.width
        val h = src.height
        if (w <= 0 || h <= 0) return true
        val stepX = max(1, w / 24)
        val stepY = max(1, h / 24)
        val first = src.getPixel(w / 2, h / 2)
        val r0 = Color.red(first)
        val g0 = Color.green(first)
        val b0 = Color.blue(first)
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val px = src.getPixel(x, y)
                if (kotlin.math.abs(Color.red(px) - r0) > 12 ||
                    kotlin.math.abs(Color.green(px) - g0) > 12 ||
                    kotlin.math.abs(Color.blue(px) - b0) > 12
                ) {
                    return false
                }
                x += stepX
            }
            y += stepY
        }
        return true
    }

    fun splitWideBitmap(src: Bitmap, leftHalf: Boolean): Bitmap {
        val halfW = src.width / 2
        if (halfW < 8) return src
        val x = if (leftHalf) 0 else halfW
        return Bitmap.createBitmap(src, x, 0, halfW, src.height)
    }

    fun mergeSideBySide(left: Bitmap, right: Bitmap, bgColor: Int): Bitmap {
        val targetH = max(left.height, right.height)
        val leftScaledW = (left.width * (targetH.toFloat() / left.height)).toInt().coerceAtLeast(1)
        val rightScaledW = (right.width * (targetH.toFloat() / right.height)).toInt().coerceAtLeast(1)
        val totalW = leftScaledW + rightScaledW
        val out = Bitmap.createBitmap(totalW, targetH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(bgColor)
        val lBmp = if (left.height != targetH) Bitmap.createScaledBitmap(left, leftScaledW, targetH, true) else left
        val rBmp = if (right.height != targetH) Bitmap.createScaledBitmap(right, rightScaledW, targetH, true) else right
        canvas.drawBitmap(lBmp, 0f, 0f, null)
        canvas.drawBitmap(rBmp, leftScaledW.toFloat(), 0f, null)
        if (lBmp !== left) lBmp.recycle()
        if (rBmp !== right) rBmp.recycle()
        return out
    }
}
