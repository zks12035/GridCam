package com.uwrlab.gridcam

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

enum class GridMode(val label: String) {
    AUTO("Auto"),
    THIRDS("Rule of thirds"),
    PHI("Golden ratio"),
    SPIRAL("Golden spiral"),
    DIAGONAL("Diagonals"),
    TRIANGLES("Golden triangles"),
    CENTER("Center"),
    SQUARE("4×4 square"),
    ARMATURE("Harmonic armature"),
    SAFE("Video safe zones")
}

/**
 * Draws the composition grid on top of the camera preview, decides which grid
 * suits the detected subject, and shows a red target circle where the subject
 * should go. All subject coordinates are normalized (0..1) to the preview.
 */
class GridOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    interface Listener {
        fun onGuidance(mode: GridMode, reason: String, guidance: String, aligned: Boolean)
    }

    private val d = resources.displayMetrics.density

    var listener: Listener? = null

    var userMode: GridMode = GridMode.AUTO
        set(value) {
            field = value
            recompute()
            invalidate()
        }

    private var activeMode = GridMode.THIRDS
    private var reason = "Point the camera at your subject"
    private var pendingMode = GridMode.THIRDS
    private var pendingCount = 0

    private var subject: RectF? = null
    private var scene: SceneInfo? = null
    private var horizonScreenY = Float.NaN
    private val horizonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0, 229, 255)
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
        pathEffect = DashPathEffect(floatArrayOf(14f, 10f), 0f)
    }
    private var missedFrames = 0
    private var target: PointF? = null
    private var aligned = false

    private var spiralFlipX = false
    private var spiralFlipY = false
    private var trianglesMirrored = false

    // ---------- Pixel-style level state ----------
    private var rollDeg = Float.NaN   // full roll angle of the phone, NaN = hidden
    private var flatMode = false      // phone pointing straight down/up (top-down shot)
    private var bubbleX = 0f
    private var bubbleY = 0f
    private var wasLevel = false
    private var levelSince = 0L

    private val levelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * d
        strokeCap = Paint.Cap.ROUND
    }
    private val levelText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 13f * d
        textAlign = Paint.Align.CENTER
        setShadowLayer(2f * d, 0f, 0f, Color.BLACK)
    }

    private var focusX = 0f
    private var focusY = 0f
    private var focusTime = 0L

    private val red = Color.rgb(255, 82, 82)
    private val green = Color.rgb(0, 230, 118)

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(210, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * d
        setShadowLayer(2f * d, 0f, 0f, Color.argb(160, 0, 0, 0))
    }
    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(110, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 1f * d
        pathEffect = DashPathEffect(floatArrayOf(6f * d, 6f * d), 0f)
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val targetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * d
    }
    private val pulsePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * d
    }
    private val targetFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val subjectPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 255, 214, 0)
        style = Paint.Style.STROKE
        strokeWidth = 2f * d
    }
    private val subjectFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 214, 0)
        style = Paint.Style.FILL
    }
    private val linkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 214, 0)
        style = Paint.Style.STROKE
        strokeWidth = 2f * d
        pathEffect = DashPathEffect(floatArrayOf(8f * d, 6f * d), 0f)
    }
    private val focusBracket = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * d
        setShadowLayer(2f * d, 0f, 0f, Color.BLACK)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 13f * d
        textAlign = Paint.Align.CENTER
        setShadowLayer(3f * d, 0f, 0f, Color.BLACK)
    }
    private val focusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f * d
    }

    // ---------- Golden spiral, pre-computed in normalized space ----------
    private class Arc(val oval: RectF, val start: Float)

    private val spiralArcs = ArrayList<Arc>()
    private val spiralRects = ArrayList<RectF>()
    private var spiralEye = PointF(0.5f, 0.5f)

    init {
        buildSpiral()
    }

    private fun buildSpiral() {
        val g = 0.618f
        var l = 0f
        var t = 0f
        var r = 1f
        var b = 1f
        var dir = 1 // portrait frame: first cut is from the top
        repeat(12) {
            val w = r - l
            val h = b - t
            when (dir) {
                0 -> { // cut from left, arc centered at bottom-right of the piece
                    val cw = w * g
                    spiralRects.add(RectF(l, t, l + cw, b))
                    spiralArcs.add(Arc(RectF(l, t, l + 2 * cw, b + h), 180f))
                    l += cw
                }
                1 -> { // cut from top, arc centered at bottom-left
                    val ch = h * g
                    spiralRects.add(RectF(l, t, r, t + ch))
                    spiralArcs.add(Arc(RectF(l - w, t, l + w, t + 2 * ch), 270f))
                    t += ch
                }
                2 -> { // cut from right, arc centered at top-left
                    val cw = w * g
                    spiralRects.add(RectF(r - cw, t, r, b))
                    spiralArcs.add(Arc(RectF(r - 2 * cw, t - h, r, t + h), 0f))
                    r -= cw
                }
                else -> { // cut from bottom, arc centered at top-right
                    val ch = h * g
                    spiralRects.add(RectF(l, b - ch, r, b))
                    spiralArcs.add(Arc(RectF(l, b - 2 * ch, r + w, b), 90f))
                    b -= ch
                }
            }
            dir = (dir + 1) % 4
        }
        spiralEye = PointF((l + r) / 2f, (t + b) / 2f)
    }

    private fun currentEye() = PointF(
        if (spiralFlipX) 1f - spiralEye.x else spiralEye.x,
        if (spiralFlipY) 1f - spiralEye.y else spiralEye.y
    )

    // ---------- Public API ----------

    /** Called for every analyzed camera frame. [box] is normalized 0..1, or null. */
    fun updateSubject(box: RectF?) {
        if (box == null) {
            missedFrames++
            if (missedFrames > 2) subject = null
        } else {
            missedFrames = 0
            val old = subject
            subject = if (old == null) RectF(box) else RectF(
                lerp(old.left, box.left),
                lerp(old.top, box.top),
                lerp(old.right, box.right),
                lerp(old.bottom, box.bottom)
            )
        }
        recompute()
        invalidate()
    }

    /** Surroundings info from [SceneAnalyzer]; [horizonY] already mapped to screen (0..1) or NaN. */
    fun updateScene(info: SceneInfo?, horizonY: Float) {
        scene = info
        horizonScreenY = horizonY
    }

    /** Clears the selected subject immediately. */
    fun clearSubject() {
        subject = null
        missedFrames = 0
        recompute()
        invalidate()
    }

    fun showFocus(x: Float, y: Float) {
        focusX = x
        focusY = y
        focusTime = SystemClock.uptimeMillis()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        recompute()
    }

    // ---------- Decision logic ----------

    private fun lerp(a: Float, b: Float) = a + (b - a) * 0.4f

    private fun viewW() = if (width > 0) width.toFloat() else 3f
    private fun viewH() = if (height > 0) height.toFloat() else 4f

    private fun recommend(s: RectF?): Pair<GridMode, String> {
        val sc = scene
        val area = if (s != null) s.width() * s.height() else 0f
        if (s != null && area > 0.40f) return GridMode.CENTER to "Large subject: center it for a bold shot."
        if (sc != null && sc.symmetry > 0.72f) {
            return GridMode.CENTER to "Symmetric surroundings: center the subject for a mirror-like shot."
        }
        if (sc != null && sc.diagonal > 0.55f) {
            return if (s != null && area in 0.05f..0.25f)
                GridMode.TRIANGLES to "Strong diagonal lines around it: golden triangles follow them."
            else GridMode.DIAGONAL to "Strong diagonal lines: let them lead the eye to the subject."
        }
        if (sc != null && !sc.horizonY.isNaN()) {
            return GridMode.THIRDS to "Long horizontal line (horizon/edge): keep it on a third line."
        }
        if (s == null) return GridMode.THIRDS to "Tap the object you want to photograph."
        val tallness = (s.height() * viewH()) / (s.width() * viewW()).coerceAtLeast(0.001f)
        return when {
            area < 0.05f -> GridMode.SPIRAL to "Small subject with open space around: the golden spiral uses it."
            tallness > 1.8f -> GridMode.THIRDS to "Tall subject: place it on a vertical third line."
            tallness < 0.55f -> GridMode.DIAGONAL to "Wide subject: diagonals add depth and movement."
            else -> GridMode.PHI to "Balanced surroundings: the golden ratio gives a natural look."
        }
    }

    private fun recompute() {
        val s = subject
        if (userMode == GridMode.AUTO) {
            val (m, why) = recommend(s)
            if (m == activeMode) {
                pendingCount = 0
                reason = why
            } else {
                if (m == pendingMode) pendingCount++ else {
                    pendingMode = m
                    pendingCount = 1
                }
                // wait for ~8 frames before switching so the grid does not flicker
                if (pendingCount >= 8) {
                    activeMode = m
                    reason = why
                    pendingCount = 0
                }
            }
        } else {
            activeMode = userMode
            reason = "Manual grid: ${userMode.label}"
        }

        if (s == null) {
            target = null
            aligned = false
            listener?.onGuidance(
                activeMode, reason,
                "Tap the object you want to photograph" + horizonHint(), false
            )
            return
        }

        val cx = s.centerX()
        val cy = s.centerY()
        val w = viewW()
        val h = viewH()

        val candidates: List<PointF> = when (activeMode) {
            GridMode.TRIANGLES -> {
                val a = trianglePoints(false, w, h)
                val b = trianglePoints(true, w, h)
                trianglesMirrored = nearestDist(b, cx, cy) < nearestDist(a, cx, cy)
                if (trianglesMirrored) b else a
            }
            else -> strongPoints(activeMode, w, h)
        }
        // Prefer the side that leaves empty space in front of the subject
        val open = scene?.openSide ?: 0f
        val sideAware = activeMode in setOf(
            GridMode.THIRDS, GridMode.PHI, GridMode.SPIRAL, GridMode.SQUARE, GridMode.ARMATURE
        ) && abs(open) > 0.15f
        val best = candidates.minByOrNull {
            var score = hypot(it.x - cx, it.y - cy)
            if (sideAware && ((open > 0f && it.x > 0.55f) || (open < 0f && it.x < 0.45f))) score += 0.15f
            score
        } ?: PointF(0.5f, 0.5f)
        var sideNote = ""
        if (sideAware && userMode == GridMode.AUTO) {
            sideNote = if (open > 0f) " More open space on the right: keep the subject left."
            else " More open space on the left: keep the subject right."
        }

        if (activeMode == GridMode.SPIRAL) {
            spiralFlipX = abs(best.x - spiralEye.x) > 0.001f
            spiralFlipY = abs(best.y - spiralEye.y) > 0.001f
        }

        target = best
        val dx = best.x - cx
        val dy = best.y - cy
        val tol = 0.04f
        aligned = abs(dx) < tol && abs(dy) < tol

        val guidance = if (aligned) {
            "Perfect! Tap the shutter"
        } else {
            val parts = ArrayList<String>()
            // subject must move right in the frame -> turn the camera left, etc.
            if (abs(dx) >= tol) parts.add(if (dx > 0) "pan left ←" else "pan right →")
            if (abs(dy) >= tol) parts.add(if (dy > 0) "tilt up ↑" else "tilt down ↓")
            "Bring the yellow dot into the red circle: " + parts.joinToString(", ")
        } + horizonHint()
        listener?.onGuidance(activeMode, reason + sideNote, guidance, aligned)
    }

    private fun horizonHint(): String {
        val hy = horizonScreenY
        if (hy.isNaN()) return ""
        return if (hy in 0.42f..0.58f) "\nHorizon is in the middle: tilt so it sits on a third line" else ""
    }

    private fun nearestDist(pts: List<PointF>, x: Float, y: Float): Float =
        pts.minOf { hypot(it.x - x, it.y - y) }

    private fun grid4(a: Float, b: Float) =
        listOf(PointF(a, a), PointF(b, a), PointF(a, b), PointF(b, b))

    /** Foot of the perpendicular from P to line AB (pixel space), returned normalized. */
    private fun foot(
        px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float, w: Float, h: Float
    ): PointF {
        val dx = bx - ax
        val dy = by - ay
        val t = ((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)
        return PointF((ax + t * dx) / w, (ay + t * dy) / h)
    }

    private fun trianglePoints(mirrored: Boolean, w: Float, h: Float): List<PointF> =
        if (!mirrored) listOf(
            foot(w, 0f, 0f, 0f, w, h, w, h),
            foot(0f, h, 0f, 0f, w, h, w, h)
        ) else listOf(
            foot(0f, 0f, 0f, h, w, 0f, w, h),
            foot(w, h, 0f, h, w, 0f, w, h)
        )

    private fun strongPoints(mode: GridMode, w: Float, h: Float): List<PointF> = when (mode) {
        GridMode.THIRDS -> grid4(1f / 3f, 2f / 3f)
        GridMode.PHI -> grid4(0.382f, 0.618f)
        GridMode.SPIRAL -> listOf(
            PointF(spiralEye.x, spiralEye.y),
            PointF(1f - spiralEye.x, spiralEye.y),
            PointF(spiralEye.x, 1f - spiralEye.y),
            PointF(1f - spiralEye.x, 1f - spiralEye.y)
        )
        GridMode.DIAGONAL -> listOf(
            foot(0f, 0f, 0f, h, w, 0f, w, h),
            foot(w, h, 0f, h, w, 0f, w, h),
            foot(w, 0f, 0f, 0f, w, h, w, h),
            foot(0f, h, 0f, 0f, w, h, w, h),
            PointF(0.5f, 0.5f)
        )
        GridMode.TRIANGLES -> trianglePoints(trianglesMirrored, w, h)
        GridMode.SQUARE -> grid4(0.25f, 0.75f) + PointF(0.5f, 0.5f)
        GridMode.ARMATURE -> grid4(1f / 3f, 2f / 3f) + strongPoints(GridMode.DIAGONAL, w, h)
        GridMode.CENTER, GridMode.SAFE, GridMode.AUTO -> listOf(PointF(0.5f, 0.5f))
    }

    // ---------- Drawing ----------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        when (activeMode) {
            GridMode.THIRDS -> drawGridLines(canvas, 1f / 3f, 2f / 3f, w, h)
            GridMode.PHI -> drawGridLines(canvas, 0.382f, 0.618f, w, h)
            GridMode.SPIRAL -> drawSpiral(canvas, w, h)
            GridMode.DIAGONAL -> drawDiagonals(canvas, w, h)
            GridMode.TRIANGLES -> drawTriangles(canvas, w, h)
            GridMode.CENTER, GridMode.AUTO -> drawCenter(canvas, w, h)
            GridMode.SQUARE -> drawSquare(canvas, w, h)
            GridMode.ARMATURE -> drawArmature(canvas, w, h)
            GridMode.SAFE -> drawSafe(canvas, w, h)
        }
        if (!horizonScreenY.isNaN()) {
            canvas.drawLine(0f, horizonScreenY * h, w, horizonScreenY * h, horizonPaint)
        }
        drawLevel(canvas, w, h)

        val pts = if (activeMode == GridMode.SPIRAL) listOf(currentEye()) else strongPoints(activeMode, w, h)
        for (p in pts) canvas.drawCircle(p.x * w, p.y * h, 4f * d, dotPaint)

        val now = SystemClock.uptimeMillis()

        subject?.let { s ->
            val r = RectF(s.left * w, s.top * h, s.right * w, s.bottom * h)
            canvas.drawRoundRect(r, 10f * d, 10f * d, subjectPaint)
            val t = target
            if (t != null && !aligned) {
                canvas.drawLine(r.centerX(), r.centerY(), t.x * w, t.y * h, linkPaint)
            }
            canvas.drawCircle(r.centerX(), r.centerY(), 7f * d, subjectFill)
            // focus brackets: the camera keeps focus here
            val fx = r.centerX()
            val fy = r.centerY()
            val q = 20f * d
            val l = 7f * d
            for (sx in intArrayOf(-1, 1)) for (sy in intArrayOf(-1, 1)) {
                canvas.drawLine(fx + sx * q, fy + sy * q, fx + sx * (q - l), fy + sy * q, focusBracket)
                canvas.drawLine(fx + sx * q, fy + sy * q, fx + sx * q, fy + sy * (q - l), focusBracket)
            }
            labelPaint.color = Color.WHITE
            canvas.drawText("Focus", fx, fy + q + 16f * d, labelPaint)
        }

        target?.let { t ->
            val tx = t.x * w
            val ty = t.y * h
            val col = if (aligned) green else red
            val base = 26f * d
            if (aligned) {
                targetFill.color = Color.argb(70, Color.red(col), Color.green(col), Color.blue(col))
                canvas.drawCircle(tx, ty, base, targetFill)
            }
            targetPaint.color = col
            canvas.drawCircle(tx, ty, base, targetPaint)
            val pulse = (now % 1200L) / 1200f
            pulsePaint.color = Color.argb(
                (200 * (1f - pulse)).toInt(), Color.red(col), Color.green(col), Color.blue(col)
            )
            canvas.drawCircle(tx, ty, base + pulse * 16f * d, pulsePaint)
            labelPaint.color = col
            val above = ty - base - 10f * d
            canvas.drawText(
                if (aligned) "Perfect" else "Put subject here",
                tx, if (above > 20f * d) above else ty + base + 22f * d, labelPaint
            )
        }

        val focusAge = now - focusTime
        val focusActive = focusTime > 0L && focusAge < 900L
        if (focusActive) {
            val f = focusAge / 900f
            focusPaint.alpha = (255 * (1f - f)).toInt()
            canvas.drawCircle(focusX, focusY, 44f * d - 10f * d * min(1f, f * 3f), focusPaint)
        }

        if (target != null || focusActive) postInvalidateOnAnimation()
    }

    private fun drawGridLines(c: Canvas, a: Float, b: Float, w: Float, h: Float) {
        c.drawLine(a * w, 0f, a * w, h, gridPaint)
        c.drawLine(b * w, 0f, b * w, h, gridPaint)
        c.drawLine(0f, a * h, w, a * h, gridPaint)
        c.drawLine(0f, b * h, w, b * h, gridPaint)
    }

    private fun drawCenter(c: Canvas, w: Float, h: Float) {
        c.drawLine(w / 2f, 0f, w / 2f, h, gridPaint)
        c.drawLine(0f, h / 2f, w, h / 2f, gridPaint)
        c.drawCircle(w / 2f, h / 2f, min(w, h) * 0.18f, guidePaint)
    }

    /** Draws a line from (x0,y0) in direction (dx,dy) until it hits the frame edge. */
    private fun ray(c: Canvas, x0: Float, y0: Float, dx: Float, dy: Float, w: Float, h: Float) {
        var t = Float.MAX_VALUE
        if (dx > 0f) t = min(t, (w - x0) / dx) else if (dx < 0f) t = min(t, -x0 / dx)
        if (dy > 0f) t = min(t, (h - y0) / dy) else if (dy < 0f) t = min(t, -y0 / dy)
        c.drawLine(x0, y0, x0 + dx * t, y0 + dy * t, gridPaint)
    }

    private fun drawDiagonals(c: Canvas, w: Float, h: Float) {
        c.drawLine(0f, 0f, w, h, gridPaint)
        c.drawLine(0f, h, w, 0f, gridPaint)
        ray(c, 0f, 0f, h, w, w, h)
        ray(c, w, h, -h, -w, w, h)
        ray(c, w, 0f, -h, w, w, h)
        ray(c, 0f, h, h, -w, w, h)
    }

    private fun drawTriangles(c: Canvas, w: Float, h: Float) {
        val pts = trianglePoints(trianglesMirrored, w, h)
        if (!trianglesMirrored) {
            c.drawLine(0f, 0f, w, h, gridPaint)
            c.drawLine(w, 0f, pts[0].x * w, pts[0].y * h, gridPaint)
            c.drawLine(0f, h, pts[1].x * w, pts[1].y * h, gridPaint)
        } else {
            c.drawLine(0f, h, w, 0f, gridPaint)
            c.drawLine(0f, 0f, pts[0].x * w, pts[0].y * h, gridPaint)
            c.drawLine(w, h, pts[1].x * w, pts[1].y * h, gridPaint)
        }
    }

    private fun drawSquare(c: Canvas, w: Float, h: Float) {
        for (i in 1..3) {
            val f = i / 4f
            c.drawLine(f * w, 0f, f * w, h, gridPaint)
            c.drawLine(0f, f * h, w, f * h, gridPaint)
        }
    }

    /** 14-line harmonic armature: diagonals, reciprocals, corner-to-midpoint lines, midlines. */
    private fun drawArmature(c: Canvas, w: Float, h: Float) {
        drawDiagonals(c, w, h)
        c.drawLine(w / 2f, 0f, w / 2f, h, guidePaint)
        c.drawLine(0f, h / 2f, w, h / 2f, guidePaint)
        // each corner to the midpoints of the two far sides
        c.drawLine(0f, 0f, w, h / 2f, gridPaint); c.drawLine(0f, 0f, w / 2f, h, gridPaint)
        c.drawLine(w, 0f, 0f, h / 2f, gridPaint); c.drawLine(w, 0f, w / 2f, h, gridPaint)
        c.drawLine(0f, h, w, h / 2f, gridPaint); c.drawLine(0f, h, w / 2f, 0f, gridPaint)
        c.drawLine(w, h, 0f, h / 2f, gridPaint); c.drawLine(w, h, w / 2f, 0f, gridPaint)
    }

    /** Broadcast safe areas: action safe (93%) and title safe (90%/80%). */
    private fun drawSafe(c: Canvas, w: Float, h: Float) {
        val action = 0.035f
        val title = 0.10f
        c.drawRect(w * action, h * action, w * (1 - action), h * (1 - action), gridPaint)
        c.drawRect(w * title, h * title, w * (1 - title), h * (1 - title), guidePaint)
        val m = 14f * d
        c.drawLine(w / 2f - m, h / 2f, w / 2f + m, h / 2f, gridPaint)
        c.drawLine(w / 2f, h / 2f - m, w / 2f, h / 2f + m, gridPaint)
        levelText.color = Color.argb(200, 255, 255, 255)
        c.drawText("action safe", w / 2f, h * action + 16f * d, levelText)
        c.drawText("title safe", w / 2f, h * title + 16f * d, levelText)
    }

    /** Degrees the photo is off level (0 when unknown or top-down). Used for auto-straighten. */
    fun straightenAngle(): Float =
        if (rollDeg.isNaN() || flatMode) 0f else residual()

    /** Roll relative to the nearest way of holding the phone (portrait or landscape). */
    private fun residual(): Float {
        val snap = Math.round(rollDeg / 90f) * 90f
        return rollDeg - snap
    }

    /** Called from the gravity sensor. [roll] in degrees, NaN to hide. Bubble values in m/s². */
    fun setLevel(roll: Float, isFlat: Boolean, bx: Float, by: Float) {
        rollDeg = roll
        flatMode = isFlat
        bubbleX = bx
        bubbleY = by
        val level = when {
            roll.isNaN() && !isFlat -> false
            isFlat -> hypot(bx, by) < 0.5f
            else -> abs(residual()) < 1f
        }
        if (level && !wasLevel) {
            levelSince = SystemClock.uptimeMillis()
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
        wasLevel = level
        invalidate()
    }

    /**
     * Pixel-camera style level. Tilted: white side marks (the phone) + yellow line (real horizon).
     * Level: one solid line, small vibration, then it fades away. Top-down: two crosshairs.
     */
    private fun drawLevel(c: Canvas, w: Float, h: Float) {
        if (rollDeg.isNaN() && !flatMode) return
        val now = SystemClock.uptimeMillis()
        var alpha = 1f
        if (wasLevel) {
            val age = now - levelSince
            if (age > 1500L) alpha = (1f - (age - 1500L) / 400f).coerceAtLeast(0f)
        }
        if (alpha <= 0f) return
        val a = (255 * alpha).toInt()
        val cx = w / 2f
        val cy = h / 2f
        val yellow = Color.argb(a, 255, 214, 0)
        val white = Color.argb(a, 255, 255, 255)

        if (flatMode) {
            val arm = 16f * d
            levelPaint.color = white
            c.drawLine(cx - arm, cy, cx + arm, cy, levelPaint)
            c.drawLine(cx, cy - arm, cx, cy + arm, levelPaint)
            val k = w * 0.035f
            val px = cx + bubbleX * k
            val py = cy - bubbleY * k
            levelPaint.color = if (wasLevel) white else yellow
            c.drawCircle(px, py, arm * 0.9f, levelPaint)
            c.drawLine(px - arm, py, px + arm, py, levelPaint)
            c.drawLine(px, py - arm, px, py + arm, levelPaint)
            return
        }

        val res = residual()
        if (abs(res) > 15f) return
        val snap = rollDeg - res
        val half = w * 0.16f
        c.save()
        c.rotate(-snap, cx, cy)           // frame of the way the phone is held
        if (wasLevel) {
            levelPaint.color = yellow
            c.drawLine(cx - half - 22f * d, cy, cx + half + 22f * d, cy, levelPaint)
        } else {
            levelPaint.color = white
            c.drawLine(cx - half - 22f * d, cy, cx - half - 4f * d, cy, levelPaint)
            c.drawLine(cx + half + 4f * d, cy, cx + half + 22f * d, cy, levelPaint)
            levelPaint.color = yellow
            c.save()
            c.rotate(-res, cx, cy)        // the real horizon
            c.drawLine(cx - half, cy, cx + half, cy, levelPaint)
            c.restore()
            levelText.color = yellow
            c.drawText(String.format("%.1f°", abs(res)), cx, cy - 12f * d, levelText)
        }
        c.restore()
    }

    private fun drawSpiral(c: Canvas, w: Float, h: Float) {
        c.save()
        c.scale(if (spiralFlipX) -1f else 1f, if (spiralFlipY) -1f else 1f, w / 2f, h / 2f)
        for (r in spiralRects) {
            c.drawRect(r.left * w, r.top * h, r.right * w, r.bottom * h, guidePaint)
        }
        for (a in spiralArcs) {
            val o = RectF(a.oval.left * w, a.oval.top * h, a.oval.right * w, a.oval.bottom * h)
            c.drawArc(o, a.start, 90f, false, gridPaint)
        }
        c.restore()
    }
}
