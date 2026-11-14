package com.arkreader.comic.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView

/**
 * Interactive comic page view supporting pinch-zoom, double-tap zoom, pan, and single-tap callbacks
 * for click-flip navigation zones.
 */
class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    var onSingleTapAt: ((relX: Float, relY: Float) -> Unit)? = null
    var verticalFitWidthMode: Boolean = false
    var doubleTapZoomEnabled: Boolean = true

    private val matrixValues = FloatArray(9)
    private val imageMatrixInternal = Matrix()
    private var currentScale = 1f
    private var baseScale = 1f
    private val minScale = 1f
    private val maxScale = 4f
    private var lastTouch = PointF()
    private var isDragging = false

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val factor = detector.scaleFactor
            val target = (currentScale * factor).coerceIn(minScale, maxScale)
            val actualFactor = target / currentScale
            if (actualFactor != 1f) {
                currentScale = target
                imageMatrixInternal.postScale(actualFactor, actualFactor, detector.focusX, detector.focusY)
                constrainBounds()
                imageMatrix = imageMatrixInternal
                parent?.requestDisallowInterceptTouchEvent(currentScale > 1.02f)
            }
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (width > 0 && height > 0) {
                onSingleTapAt?.invoke(e.x / width.toFloat(), e.y / height.toFloat())
            }
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (!doubleTapZoomEnabled) return false
            val nextScale = if (currentScale > 1.5f) 1f else 2.2f
            val factor = nextScale / currentScale
            currentScale = nextScale
            imageMatrixInternal.postScale(factor, factor, e.x, e.y)
            constrainBounds()
            imageMatrix = imageMatrixInternal
            parent?.requestDisallowInterceptTouchEvent(currentScale > 1.02f)
            return true
        }
    })

    init {
        scaleType = ScaleType.MATRIX
    }

    override fun setImageBitmap(bm: Bitmap?) {
        super.setImageBitmap(bm)
        post { fitBitmapToView() }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fitBitmapToView()
    }

    fun resetZoom() {
        fitBitmapToView()
    }

    private fun fitBitmapToView() {
        val d = drawable ?: return
        val vw = width.toFloat()
        val vh = height.toFloat()
        if (vw <= 0f || vh <= 0f) return
        val dw = d.intrinsicWidth.toFloat()
        val dh = d.intrinsicHeight.toFloat()
        if (dw <= 0f || dh <= 0f) return

        imageMatrixInternal.reset()
        baseScale = if (verticalFitWidthMode) {
            vw / dw
        } else {
            minOf(vw / dw, vh / dh)
        }
        val dx = (vw - dw * baseScale) * 0.5f
        val dy = if (verticalFitWidthMode) 0f else (vh - dh * baseScale) * 0.5f
        imageMatrixInternal.postScale(baseScale, baseScale)
        imageMatrixInternal.postTranslate(dx, dy)
        currentScale = 1f
        imageMatrix = imageMatrixInternal
        parent?.requestDisallowInterceptTouchEvent(false)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        if (currentScale > 1.02f) {
            parent?.requestDisallowInterceptTouchEvent(true)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastTouch.set(event.x, event.y)
                    isDragging = true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isDragging && !scaleDetector.isInProgress) {
                        val dx = event.x - lastTouch.x
                        val dy = event.y - lastTouch.y
                        lastTouch.set(event.x, event.y)
                        imageMatrixInternal.postTranslate(dx, dy)
                        constrainBounds()
                        imageMatrix = imageMatrixInternal
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> isDragging = false
            }
        }
        return true
    }

    private fun constrainBounds() {
        val d = drawable ?: return
        val rect = RectF(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
        imageMatrixInternal.mapRect(rect)

        val vw = width.toFloat()
        val vh = height.toFloat()
        var deltaX = 0f
        var deltaY = 0f

        if (rect.width() <= vw) {
            deltaX = (vw - rect.width()) * 0.5f - rect.left
        } else if (rect.left > 0f) {
            deltaX = -rect.left
        } else if (rect.right < vw) {
            deltaX = vw - rect.right
        }

        if (rect.height() <= vh) {
            deltaY = (vh - rect.height()) * 0.5f - rect.top
        } else if (rect.top > 0f) {
            deltaY = -rect.top
        } else if (rect.bottom < vh) {
            deltaY = vh - rect.bottom
        }

        imageMatrixInternal.postTranslate(deltaX, deltaY)
        imageMatrixInternal.getValues(matrixValues)
    }
}
