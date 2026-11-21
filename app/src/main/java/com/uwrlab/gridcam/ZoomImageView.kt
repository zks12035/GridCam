package com.uwrlab.gridcam

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.max
import kotlin.math.min

/**
 * Image view with pinch-to-zoom, double-tap zoom and drag-to-pan.
 * While zoomed in it keeps the touch, so a ViewPager doesn't change photo.
 */
class ZoomImageView(context: Context) : AppCompatImageView(context) {

    private val m = Matrix()
    private var baseScale = 1f
    private var zoom = 1f
    private val maxZoom = 6f

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val newZoom = (zoom * d.scaleFactor).coerceIn(1f, maxZoom)
            val f = newZoom / zoom
            zoom = newZoom
            m.postScale(f, f, d.focusX, d.focusY)
            fixBounds()
            return true
        }
    })

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            val target = if (zoom > 1.1f) 1f else 2.5f
            val f = target / zoom
            zoom = target
            m.postScale(f, f, e.x, e.y)
            fixBounds()
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (zoom <= 1.01f) return false
            m.postTranslate(-dx, -dy)
            fixBounds()
            return true
        }
    })

    init {
        scaleType = ScaleType.MATRIX
    }

    val isZoomed: Boolean get() = zoom > 1.01f

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        resetZoom()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        resetZoom()
    }

    fun resetZoom() {
        val d = drawable ?: return
        if (width == 0 || height == 0 || d.intrinsicWidth <= 0 || d.intrinsicHeight <= 0) return
        baseScale = min(width.toFloat() / d.intrinsicWidth, height.toFloat() / d.intrinsicHeight)
        zoom = 1f
        m.reset()
        m.postScale(baseScale, baseScale)
        m.postTranslate((width - d.intrinsicWidth * baseScale) / 2f, (height - d.intrinsicHeight * baseScale) / 2f)
        imageMatrix = m
    }

    /** Keeps the photo inside the screen (centred when smaller than the view). */
    private fun fixBounds() {
        val d = drawable ?: return
        val r = RectF(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
        m.mapRect(r)
        var dx = 0f
        var dy = 0f
        if (r.width() <= width) dx = (width - r.width()) / 2f - r.left
        else { if (r.left > 0) dx = -r.left; if (r.right < width) dx = width - r.right }
        if (r.height() <= height) dy = (height - r.height()) / 2f - r.top
        else { if (r.top > 0) dy = -r.top; if (r.bottom < height) dy = height - r.bottom }
        m.postTranslate(dx, dy)
        if (zoom <= 1.001f) resetZoom() else imageMatrix = m
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestures.onTouchEvent(event)
        // keep the gesture while zoomed or pinching, so the pager doesn't swipe away
        val keep = isZoomed || event.pointerCount > 1 || scaleDetector.isInProgress
        parent?.requestDisallowInterceptTouchEvent(keep)
        return true
    }
}
