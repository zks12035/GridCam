package com.uwrlab.gridcam

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Google-Photos-style crop: the photo with a draggable frame (corners, edges, move),
 * rule-of-thirds grid inside, darkened outside, optional fixed aspect ratio.
 * [crop] is in 0..1 coordinates of [bitmap].
 */
class CropView(context: Context) : View(context) {

    var bitmap: Bitmap? = null
        set(value) { field = value; invalidate() }

    val crop = RectF(0f, 0f, 1f, 1f)

    /** Width/height ratio in pixels; 0 = free. */
    var aspect = 0f
        private set

    private val d = resources.displayMetrics.density
    private val img = RectF()
    private val dim = Paint().apply { color = Color.argb(150, 0, 0, 0) }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2f * d
    }
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 255, 255, 255); style = Paint.Style.STROKE; strokeWidth = 1.2f * d
    }
    private val corner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 4f * d; strokeCap = Paint.Cap.SQUARE
    }
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    private var handle = 0   // 1 TL, 2 TR, 3 BL, 4 BR, 5 L, 6 R, 7 T, 8 B, 9 move
    private var lastX = 0f
    private var lastY = 0f
    private val minSize = 0.1f

    fun setCrop(l: Float, t: Float, r: Float, b: Float) {
        crop.set(l, t, r, b)
        invalidate()
    }

    /** Fixes the frame to [ratio] (w/h in pixels), as large as possible around the current centre. */
    fun setAspect(ratio: Float) {
        aspect = ratio
        val bmp = bitmap ?: return
        if (ratio <= 0f) { invalidate(); return }
        val bw = bmp.width.toFloat(); val bh = bmp.height.toFloat()
        var w = 1f
        var h = (w * bw / ratio) / bh
        if (h > 1f) { h = 1f; w = (h * bh * ratio) / bw }
        val cx = crop.centerX().coerceIn(w / 2, 1 - w / 2)
        val cy = crop.centerY().coerceIn(h / 2, 1 - h / 2)
        crop.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
        invalidate()
    }

    private fun layoutImage() {
        val bmp = bitmap ?: return
        val pad = 24f * d
        val aw = width - 2 * pad
        val ah = height - 2 * pad
        val s = min(aw / bmp.width, ah / bmp.height)
        val w = bmp.width * s
        val h = bmp.height * s
        img.set((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = bitmap ?: return
        layoutImage()
        canvas.drawBitmap(bmp, null, img, bmpPaint)
        val c = RectF(
            img.left + crop.left * img.width(), img.top + crop.top * img.height(),
            img.left + crop.right * img.width(), img.top + crop.bottom * img.height()
        )
        canvas.drawRect(img.left, img.top, img.right, c.top, dim)
        canvas.drawRect(img.left, c.bottom, img.right, img.bottom, dim)
        canvas.drawRect(img.left, c.top, c.left, c.bottom, dim)
        canvas.drawRect(c.right, c.top, img.right, c.bottom, dim)
        for (k in 1..2) {
            val x = c.left + c.width() * k / 3f
            val y = c.top + c.height() * k / 3f
            canvas.drawLine(x, c.top, x, c.bottom, grid)
            canvas.drawLine(c.left, y, c.right, y, grid)
        }
        canvas.drawRect(c, border)
        val len = 22f * d
        val o = 6f * d
        // corner brackets just outside the frame
        canvas.drawLine(c.left - o, c.top - o, c.left - o + len, c.top - o, corner)
        canvas.drawLine(c.left - o, c.top - o, c.left - o, c.top - o + len, corner)
        canvas.drawLine(c.right + o, c.top - o, c.right + o - len, c.top - o, corner)
        canvas.drawLine(c.right + o, c.top - o, c.right + o, c.top - o + len, corner)
        canvas.drawLine(c.left - o, c.bottom + o, c.left - o + len, c.bottom + o, corner)
        canvas.drawLine(c.left - o, c.bottom + o, c.left - o, c.bottom + o - len, corner)
        canvas.drawLine(c.right + o, c.bottom + o, c.right + o - len, c.bottom + o, corner)
        canvas.drawLine(c.right + o, c.bottom + o, c.right + o, c.bottom + o - len, corner)
    }

    private fun hit(x: Float, y: Float): Int {
        val c = RectF(
            img.left + crop.left * img.width(), img.top + crop.top * img.height(),
            img.left + crop.right * img.width(), img.top + crop.bottom * img.height()
        )
        val r = 36f * d
        return when {
            hypot(x - c.left, y - c.top) < r -> 1
            hypot(x - c.right, y - c.top) < r -> 2
            hypot(x - c.left, y - c.bottom) < r -> 3
            hypot(x - c.right, y - c.bottom) < r -> 4
            abs(x - c.left) < r && y in c.top..c.bottom -> 5
            abs(x - c.right) < r && y in c.top..c.bottom -> 6
            abs(y - c.top) < r && x in c.left..c.right -> 7
            abs(y - c.bottom) < r && x in c.left..c.right -> 8
            c.contains(x, y) -> 9
            else -> 0
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (bitmap == null || img.width() <= 0f) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                handle = hit(e.x, e.y); lastX = e.x; lastY = e.y
                return handle != 0
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (e.x - lastX) / img.width()
                val dy = (e.y - lastY) / img.height()
                lastX = e.x; lastY = e.y
                drag(dx, dy)
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> handle = 0
        }
        return true
    }

    private fun drag(dx: Float, dy: Float) {
        val c = RectF(crop)
        when (handle) {
            9 -> {
                val mx = dx.coerceIn(-c.left, 1f - c.right)
                val my = dy.coerceIn(-c.top, 1f - c.bottom)
                c.offset(mx, my)
            }
            1 -> { c.left += dx; c.top += dy }
            2 -> { c.right += dx; c.top += dy }
            3 -> { c.left += dx; c.bottom += dy }
            4 -> { c.right += dx; c.bottom += dy }
            5 -> c.left += dx
            6 -> c.right += dx
            7 -> c.top += dy
            8 -> c.bottom += dy
        }
        c.left = c.left.coerceIn(0f, c.right - minSize)
        c.right = c.right.coerceIn(c.left + minSize, 1f)
        c.top = c.top.coerceIn(0f, c.bottom - minSize)
        c.bottom = c.bottom.coerceIn(c.top + minSize, 1f)
        if (aspect > 0f && handle in 1..8) keepAspect(c)
        crop.set(c)
    }

    /** Adjusts the height (from the fixed side) so the frame keeps the chosen aspect ratio. */
    private fun keepAspect(c: RectF) {
        val bmp = bitmap ?: return
        val bw = bmp.width.toFloat(); val bh = bmp.height.toFloat()
        var h = (c.width() * bw / aspect) / bh
        val anchorTop = handle == 3 || handle == 4 || handle == 8 || handle == 5 || handle == 6
        val maxH = if (anchorTop) 1f - c.top else c.bottom
        if (h > maxH) {
            h = maxH
            val w = (h * bh * aspect) / bw
            if (handle == 1 || handle == 3 || handle == 5) c.left = c.right - w else c.right = c.left + w
        }
        if (anchorTop) c.bottom = c.top + h else c.top = c.bottom - h
    }

    /**
     * Auto Frame: finds where the detail/subject is and frames it with the subject on a
     * rule-of-thirds point (keeps the chosen aspect, or the photo's own shape).
     */
    fun autoFrame() {
        val bmp = bitmap ?: return
        val sw = 64
        val sh = max(1, (64f * bmp.height / bmp.width).toInt())
        val small = Bitmap.createScaledBitmap(bmp, sw, sh, true)
        val px = IntArray(sw * sh)
        small.getPixels(px, 0, sw, 0, 0, sw, sh)
        val g = FloatArray(sw * sh) { (Color.red(px[it]) * 0.3f + Color.green(px[it]) * 0.59f + Color.blue(px[it]) * 0.11f) }
        val e = FloatArray(sw * sh)
        for (y in 1 until sh - 1) for (x in 1 until sw - 1) {
            val i = y * sw + x
            e[i] = abs(4 * g[i] - g[i - 1] - g[i + 1] - g[i - sw] - g[i + sw])
        }
        val sorted = e.sortedArray()
        val thr = sorted[(sorted.size * 0.8f).toInt().coerceIn(0, sorted.size - 1)]
        var sx = 0f; var sy = 0f; var sw8 = 0f
        for (y in 0 until sh) for (x in 0 until sw) {
            val v = e[y * sw + x]
            if (v >= thr) { sx += x * v; sy += y * v; sw8 += v }
        }
        val cx = if (sw8 > 0) sx / sw8 / sw else 0.5f
        val cy = if (sw8 > 0) sy / sw8 / sh else 0.5f
        val ratio = if (aspect > 0f) aspect else bmp.width.toFloat() / bmp.height
        val bw = bmp.width.toFloat(); val bh = bmp.height.toFloat()
        var w = 0.8f
        var h = (w * bw / ratio) / bh
        if (h > 0.9f) { h = 0.9f; w = (h * bh * ratio) / bw }
        // put the subject on the nearest third point of the new frame
        val fx = if (cx < 0.5f) 1f / 3f else 2f / 3f
        val fy = if (cy < 0.5f) 1f / 3f else 2f / 3f
        val left = (cx - fx * w).coerceIn(0f, 1f - w)
        val top = (cy - fy * h).coerceIn(0f, 1f - h)
        crop.set(left, top, left + w, top + h)
        invalidate()
    }
}
