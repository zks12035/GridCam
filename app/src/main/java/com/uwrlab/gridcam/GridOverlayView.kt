package com.uwrlab.gridcam

import android.content.Context
import android.graphics.Bitmap
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
    SAFE("Video safe zones"),
    LINES("Scene lines"),
    FRAME("Frame in frame"),
    VANISHING("Vanishing point"),
    SYMMETRY("Symmetry"),
    NEGATIVE("Negative space"),
    LAYERS("Layering")
}

/** A real line found in the scene. kind: 0 horizon (flat), 1 vertical, 2 leading (diagonal). */
class SceneLine(var x1: Float, var y1: Float, var x2: Float, var y2: Float, val kind: Int, var ttl: Int = 3)

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
    // ---- Pro overlays & helpers ----
    /** Pro mode shows histogram + peaking; Simple mode keeps the screen clean. */
    var proUi = false
    /** 64-bin brightness histogram (0..1 heights), or null. */
    var histogram: FloatArray? = null
    /** Focus-peaking mask (red where sharp), drawn over the preview, or null. */
    var peaking: Bitmap? = null
    var peakingZoom = 1f
    /** AE/AF lock badge. */
    var aeAfLocked = false
    /** Subject is a face: guidance puts the eyes on the upper third. */
    var faceMode = false
    /** Live composition score 0..100, -1 = no subject. */
    var score = -1
        private set
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(120, 0, 0, 0) }
    private val histPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(220, 255, 255, 255) }
    private val badgeText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    fun updateSubject(box: RectF?, isFace: Boolean) {
        faceMode = isFace && box != null
        updateSubject(box)
    }

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

    // ---------- Scene lines (horizon, leading lines, triangle) ----------

    private val lines = ArrayList<SceneLine>()

    // ---------- Colour harmony (colour wheel badge) ----------

    var colourWheel = false
        set(v) { field = v; invalidate() }
    private var hueHist = FloatArray(36)
    private var harmonyHues: List<Float> = emptyList()
    private var harmonyName = ""
    private var harmonyGood = false

    /** New hue histogram from the camera; finds the main colours and how they relate on the wheel. */
    fun updateHues(h: FloatArray) {
        for (i in 0 until 36) hueHist[i] = hueHist[i] * 0.5f + h[i] * 0.5f
        // smooth around the circle
        val sm = FloatArray(36) { (hueHist[(it + 35) % 36] + 2 * hueHist[it] + hueHist[(it + 1) % 36]) / 4f }
        val total = sm.sum()
        if (total < 1.5f) {
            harmonyHues = emptyList(); harmonyName = "Few colours (muted / B&W look)"; harmonyGood = true
            invalidate(); return
        }
        // peaks: local maxima holding at least 10% of the colour, at least 40° apart
        val peaks = (0 until 36).filter { sm[it] >= sm[(it + 35) % 36] && sm[it] >= sm[(it + 1) % 36] && sm[it] > total * 0.035f }
            .sortedByDescending { sm[it] }
        val chosen = ArrayList<Int>()
        for (p in peaks) {
            if (chosen.size >= 3) break
            val share = (-2..2).sumOf { sm[(p + it + 36) % 36].toDouble() }.toFloat() / total
            if (share < 0.1f) continue
            if (chosen.all { hueDist(it * 10f, p * 10f) >= 40f }) chosen.add(p)
        }
        harmonyHues = chosen.map { it * 10f + 5f }
        val hs = harmonyHues
        when {
            hs.size <= 1 -> {
                harmonyName = if (hs.isEmpty()) "Few colours" else "One colour: ${hueName(hs[0])} (calm)"
                harmonyGood = true
            }
            hs.size == 2 && hueDist(hs[0], hs[1]) >= 150f -> {
                harmonyName = "Complementary: ${hueName(hs[0])} & ${hueName(hs[1])} ✓"; harmonyGood = true
            }
            hs.size == 3 && listOf(hueDist(hs[0], hs[1]), hueDist(hs[1], hs[2]), hueDist(hs[0], hs[2])).all { it in 85f..155f } -> {
                harmonyName = "Triadic colours ✓"; harmonyGood = true
            }
            spread(hs) <= 75f -> {
                harmonyName = "Analogous: ${hs.joinToString(" · ") { hueName(it) }} ✓"; harmonyGood = true
            }
            hs.size == 2 -> {
                val opp = (hs[0] + 180f) % 360f
                harmonyName = "Add ${hueName(opp)} for contrast with ${hueName(hs[0])}"; harmonyGood = false
            }
            else -> { harmonyName = "Mixed colours: simplify the background"; harmonyGood = false }
        }
        invalidate()
    }

    private fun hueDist(a: Float, b: Float): Float { val d = abs(a - b) % 360f; return if (d > 180f) 360f - d else d }

    /** Largest angle between any two hues (small = analogous). */
    private fun spread(hs: List<Float>): Float {
        var m = 0f
        for (i in hs.indices) for (j in i + 1 until hs.size) m = maxOf(m, hueDist(hs[i], hs[j]))
        return m
    }

    private fun hueName(h: Float): String = when (((h % 360f) + 360f) % 360f) {
        in 0f..15f -> "red"; in 15f..40f -> "orange"; in 40f..70f -> "yellow"; in 70f..100f -> "lime"
        in 100f..160f -> "green"; in 160f..200f -> "teal"; in 200f..250f -> "blue"; in 250f..290f -> "purple"
        in 290f..335f -> "magenta"; else -> "red"
    }

    private val wheelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val wheelDot = Paint(Paint.ANTI_ALIAS_FLAG)

    /** Small colour wheel (like the reel): dots on the main colours, joined to the centre. */
    private fun drawColourWheel(c: Canvas, w: Float, h: Float) {
        val r = 30f * d
        val cx = 14f * d + r
        val cy = h - 70f * d - r
        val ring = 9f * d
        wheelPaint.strokeWidth = ring
        val oval = RectF(cx - r, cy - r, cx + r, cy + r)
        for (i in 0 until 12) {
            wheelPaint.color = Color.HSVToColor(floatArrayOf(i * 30f, 0.9f, 0.95f))
            // hue 0 at top, clockwise
            c.drawArc(oval, -90f + i * 30f - 15f, 30.5f, false, wheelPaint)
        }
        val hs = harmonyHues
        linkPaint.pathEffect = null
        for (hue in hs) {
            val a = Math.toRadians((hue - 90f).toDouble())
            val x = cx + (r * kotlin.math.cos(a)).toFloat()
            val y = cy + (r * kotlin.math.sin(a)).toFloat()
            focusBracket.strokeWidth = 2f * d
            c.drawLine(cx, cy, x, y, focusBracket)
            wheelDot.color = Color.HSVToColor(floatArrayOf(hue, 0.9f, 0.95f))
            c.drawCircle(x, y, 7f * d, wheelDot)
            c.drawCircle(x, y, 7f * d, focusBracket)
        }
        focusBracket.strokeWidth = 2.5f * d
        linkPaint.pathEffect = DashPathEffect(floatArrayOf(8f * d, 6f * d), 0f)
        levelText.color = if (harmonyGood) green else Color.rgb(255, 214, 0)
        levelText.textAlign = Paint.Align.LEFT
        c.drawText(harmonyName, cx + r + ring + 8f * d, cy + 5f * d, levelText)
        levelText.textAlign = Paint.Align.CENTER
    }
    private var apex: PointF? = null

    private fun kindOf(l: FloatArray): Int {
        val dx = (l[2] - l[0]) * viewW()
        val dy = (l[3] - l[1]) * viewH()
        val deg = Math.toDegrees(kotlin.math.atan2(abs(dy), abs(dx)).toDouble())
        return when { deg < 12 -> 0; deg > 78 -> 1; else -> 2 }
    }

    /** New lines from the camera (screen 0..1). Smoothed so they don't jump. */
    fun updateLines(found: List<FloatArray>) {
        for (l in lines) l.ttl--
        for (f in found) {
            // keep endpoints ordered left -> right so matching works
            val a = if (f[0] <= f[2]) f else floatArrayOf(f[2], f[3], f[0], f[1])
            val k = kindOf(a)
            val mx = (a[0] + a[2]) / 2f; val my = (a[1] + a[3]) / 2f
            val old = lines.firstOrNull {
                it.kind == k && hypot((it.x1 + it.x2) / 2f - mx, (it.y1 + it.y2) / 2f - my) < 0.1f &&
                    abs((it.y2 - it.y1) / (it.x2 - it.x1 + 1e-3f) - (a[3] - a[1]) / (a[2] - a[0] + 1e-3f)) < 0.3f
            }
            if (old != null) {
                old.x1 += (a[0] - old.x1) * 0.5f; old.y1 += (a[1] - old.y1) * 0.5f
                old.x2 += (a[2] - old.x2) * 0.5f; old.y2 += (a[3] - old.y2) * 0.5f
                old.ttl = 3
            } else lines.add(SceneLine(a[0], a[1], a[2], a[3], k))
        }
        lines.removeAll { it.ttl <= 0 }
        while (lines.size > 6) lines.removeAt(0)
        apex = findApex()
        vanish = findVanishing()
        recompute()
        invalidate()
    }

    /** Two diagonals leaning towards each other (mountain, roof, tent): their top meeting point. */
    private fun findApex(): PointF? {
        val diag = lines.filter { it.kind == 2 }
        for (i in diag.indices) for (j in i + 1 until diag.size) {
            val a = diag[i]; val b = diag[j]
            val sa = (a.y2 - a.y1) / (a.x2 - a.x1 + 1e-4f)
            val sb = (b.y2 - b.y1) / (b.x2 - b.x1 + 1e-4f)
            if (sa * sb >= 0f) continue
            // intersection
            val x = (b.y1 - a.y1 + sa * a.x1 - sb * b.x1) / (sa - sb)
            val y = a.y1 + sa * (x - a.x1)
            if (x !in 0.05f..0.95f || y !in 0.03f..0.85f) continue
            // it must be the top of both lines (a peak, not a valley)
            if (y > min(a.y1, a.y2) + 0.08f || y > min(b.y1, b.y2) + 0.08f) continue
            val near = { l: SceneLine -> min(hypot(l.x1 - x, l.y1 - y), hypot(l.x2 - x, l.y2 - y)) }
            if (near(a) < 0.2f && near(b) < 0.2f) return PointF(x, y)
        }
        return null
    }

    private var vanish: PointF? = null

    /**
     * Vanishing point: where 3+ lines (or 2 lines that don't touch) point to the same spot –
     * corridors, rails, roads, bridges, tunnels.
     */
    private fun findVanishing(): PointF? {
        val cand = lines.filter { it.kind != 0 }
        if (cand.size < 2) return null
        var best: PointF? = null
        var bestN = 0
        for (i in cand.indices) for (j in i + 1 until cand.size) {
            val a = cand[i]; val b = cand[j]
            val ax = a.x2 - a.x1; val ay = a.y2 - a.y1
            val bx = b.x2 - b.x1; val by = b.y2 - b.y1
            val den = ax * by - ay * bx
            if (abs(den) < 1e-4f) continue
            val t = ((b.x1 - a.x1) * by - (b.y1 - a.y1) * bx) / den
            val p = PointF(a.x1 + ax * t, a.y1 + ay * t)
            if (p.x !in 0.1f..0.9f || p.y !in 0.1f..0.9f) continue
            // how many lines run through this point?
            var n = 0
            var gapOk = 0
            for (l in cand) {
                val dx = l.x2 - l.x1; val dy = l.y2 - l.y1
                val len = hypot(dx, dy)
                if (len < 1e-4f) continue
                val dist = abs((p.x - l.x1) * dy - (p.y - l.y1) * dx) / len
                if (dist < 0.035f) {
                    n++
                    val gap = min(hypot(l.x1 - p.x, l.y1 - p.y), hypot(l.x2 - p.x, l.y2 - p.y))
                    if (gap > 0.06f) gapOk++
                }
            }
            if ((n >= 3 || (n == 2 && gapOk == 2)) && n > bestN) { bestN = n; best = p }
        }
        val old = vanish
        return if (best != null && old != null) PointF(old.x + (best.x - old.x) * 0.5f, old.y + (best.y - old.y) * 0.5f) else best
    }

    private fun drawVanishing(c: Canvas, w: Float, h: Float) {
        val vp = vanish ?: PointF(0.5f, 0.5f)
        val px = vp.x * w; val py = vp.y * h
        // corners to the vanishing point + nested frames shrinking toward it (like a tunnel)
        c.drawLine(0f, 0f, px, py, gridPaint); c.drawLine(w, 0f, px, py, gridPaint)
        c.drawLine(0f, h, px, py, gridPaint); c.drawLine(w, h, px, py, gridPaint)
        for (k in listOf(0.72f, 0.45f, 0.22f)) {
            c.drawRect(px + (0f - px) * k, py + (0f - py) * k, px + (w - px) * k, py + (h - py) * k, guidePaint)
        }
        for (l in lines) if (l.kind != 0) drawExtended(c, l.x1, l.y1, l.x2, l.y2, w, h)
        c.drawCircle(px, py, 6f * d, dotPaint)
        levelText.color = Color.WHITE
        c.drawText(if (vanish != null) "Vanishing point found" else "Point the camera down a road, corridor or rails",
            w / 2f, h * 0.08f, levelText)
    }

    private fun drawSymmetry(c: Canvas, w: Float, h: Float) {
        c.drawLine(w / 2f, 0f, w / 2f, h, linePaint)
        val m = 10f * d
        for (f in listOf(1f / 3f, 2f / 3f)) c.drawLine(w / 2f - m, f * h, w / 2f + m, f * h, gridPaint)
        val sym = scene?.symmetry ?: 0f
        levelText.color = if (sym > 0.7f) green else Color.WHITE
        c.drawText("Mirror match ${(sym * 100).toInt()}%  (keep both sides equal)", w / 2f, h * 0.08f, levelText)
    }

    private fun drawNegative(c: Canvas, w: Float, h: Float) {
        // measuring cross through the empty area, like a ruler showing the space
        val open = scene?.openSide ?: 0f
        val cx = if (open < 0f) w * 0.38f else w * 0.62f   // the empty side
        val cap = 12f * d
        val top = h * 0.15f; val bot = h * 0.75f
        val left = cx - w * 0.25f; val right = cx + w * 0.25f
        val cy = h * 0.47f
        c.drawLine(cx, top, cx, bot, guidePaint)
        c.drawLine(cx - cap, top, cx + cap, top, guidePaint); c.drawLine(cx - cap, bot, cx + cap, bot, guidePaint)
        c.drawLine(left, cy, right, cy, guidePaint)
        c.drawLine(left, cy - cap, left, cy + cap, guidePaint); c.drawLine(right, cy - cap, right, cy + cap, guidePaint)
        val e = scene?.empty ?: 0f
        levelText.color = if (e > 0.6f) green else Color.WHITE
        c.drawText("Empty space ${(e * 100).toInt()}%  · keep the subject small, near an edge", w / 2f, h * 0.08f, levelText)
    }

    private val LAYER_MIN = 0.06f

    /**
     * Layering: foreground (bottom), middle ground (subject) and background (top).
     * Each band shows ✓ when it has something in it, so the photo gets real depth.
     */
    private fun drawLayers(c: Canvas, w: Float, h: Float) {
        val ly = scene?.layers ?: FloatArray(3)
        val names = arrayOf("Background", "Middle (subject)", "Foreground")
        val tints = intArrayOf(Color.argb(28, 80, 160, 255), Color.argb(22, 255, 214, 0), Color.argb(28, 0, 230, 118))
        val fill = Paint()
        for (b in 0 until 3) {
            val top = h * b / 3f; val bot = h * (b + 1) / 3f
            fill.color = tints[b]
            c.drawRect(0f, top, w, bot, fill)
            if (b > 0) c.drawLine(0f, top, w, top, guidePaint)
            val ok = ly[b] > LAYER_MIN
            levelText.textAlign = Paint.Align.LEFT
            levelText.color = if (ok) green else Color.rgb(255, 214, 0)
            c.drawText((if (ok) "✓ " else "○ ") + names[b], 12f * d, top + 22f * d, levelText)
        }
        levelText.textAlign = Paint.Align.CENTER
        val missing = (0 until 3).filter { ly[it] <= LAYER_MIN }
        levelText.color = if (missing.isEmpty()) green else Color.WHITE
        val tip = when {
            missing.isEmpty() -> "3 layers ✓ – great depth"
            2 in missing -> "Add something close at the bottom: flowers, rocks, a branch"
            0 in missing -> "Show more of the background: tilt up or step back"
            else -> "Put your subject in the middle layer"
        }
        c.drawText(tip, w / 2f, h - 14f * d, levelText)
    }

    private fun negativePoints(): List<PointF> {
        val open = scene?.openSide ?: 0f
        // subject goes on the opposite side of the empty space, near the edge
        return if (open > 0f) listOf(PointF(0.2f, 1f / 3f), PointF(0.2f, 2f / 3f))
        else if (open < 0f) listOf(PointF(0.8f, 1f / 3f), PointF(0.8f, 2f / 3f))
        else listOf(PointF(0.2f, 1f / 3f), PointF(0.8f, 1f / 3f), PointF(0.2f, 2f / 3f), PointF(0.8f, 2f / 3f))
    }

    private fun longestLeading(): SceneLine? =
        lines.filter { it.kind == 2 }.maxByOrNull { hypot((it.x2 - it.x1) * viewW(), (it.y2 - it.y1) * viewH()) }
            ?.takeIf { hypot(it.x2 - it.x1, it.y2 - it.y1) > 0.4f }

    /** Where the subject should go with scene lines: on a line near a thirds point, or under the apex. */
    private fun linePoints(): List<PointF> {
        val pts = ArrayList<PointF>()
        apex?.let { pts.add(PointF(it.x, it.y)) }
        val thirds = grid4(1f / 3f, 2f / 3f)
        for (l in lines) {
            if (l.kind == 0) {
                // horizon: subject stands on it at a vertical third
                pts.add(PointF(1f / 3f, l.y1 + (l.y2 - l.y1) * ((1f / 3f - l.x1) / (l.x2 - l.x1 + 1e-4f))))
                pts.add(PointF(2f / 3f, l.y1 + (l.y2 - l.y1) * ((2f / 3f - l.x1) / (l.x2 - l.x1 + 1e-4f))))
                continue
            }
            val dx = l.x2 - l.x1; val dy = l.y2 - l.y1
            val len2 = dx * dx + dy * dy
            if (len2 < 1e-5f) continue
            for (t in thirds) {
                // nearest point on the (extended) line to the thirds point
                val u = ((t.x - l.x1) * dx + (t.y - l.y1) * dy) / len2
                val p = PointF(l.x1 + u * dx, l.y1 + u * dy)
                if (p.x in 0.05f..0.95f && p.y in 0.05f..0.95f && hypot(p.x - t.x, p.y - t.y) < 0.2f) pts.add(p)
            }
        }
        return pts.filter { it.y in 0.03f..0.97f }.ifEmpty { thirds }
    }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * d
        strokeCap = Paint.Cap.ROUND
        setShadowLayer(3f * d, 0f, 0f, Color.argb(180, 0, 0, 0))
    }

    /** Extends segment (x1,y1)-(x2,y2) to the frame edges and draws it. */
    private fun drawExtended(c: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, w: Float, h: Float) {
        val dx = x2 - x1; val dy = y2 - y1
        var t0 = -1e9f; var t1 = 1e9f
        if (abs(dx) > 1e-6f) { val a = -x1 / dx; val b = (1f - x1) / dx; t0 = maxOf(t0, minOf(a, b)); t1 = minOf(t1, maxOf(a, b)) }
        if (abs(dy) > 1e-6f) { val a = -y1 / dy; val b = (1f - y1) / dy; t0 = maxOf(t0, minOf(a, b)); t1 = minOf(t1, maxOf(a, b)) }
        if (t0 > t1) return
        c.drawLine((x1 + dx * t0) * w, (y1 + dy * t0) * h, (x1 + dx * t1) * w, (y1 + dy * t1) * h, linePaint)
    }

    private fun drawSceneLines(c: Canvas, w: Float, h: Float) {
        val ap = apex
        if (lines.isEmpty()) {
            drawGridLines(c, 1f / 3f, 2f / 3f, w, h)
            levelText.color = Color.WHITE
            c.drawText("Looking for lines… (horizon, road, hill, roof)", w / 2f, h * 0.08f, levelText)
            return
        }
        for (l in lines) {
            if (ap != null && l.kind == 2) {
                // triangle side: from the apex down to the frame edge
                val far = if (hypot(l.x1 - ap.x, l.y1 - ap.y) > hypot(l.x2 - ap.x, l.y2 - ap.y)) PointF(l.x1, l.y1) else PointF(l.x2, l.y2)
                val dx = far.x - ap.x; val dy = far.y - ap.y
                var t = 1e9f
                if (dx > 0) t = min(t, (1f - ap.x) / dx) else if (dx < 0) t = min(t, -ap.x / dx)
                if (dy > 0) t = min(t, (1f - ap.y) / dy)
                c.drawLine(ap.x * w, ap.y * h, (ap.x + dx * t) * w, (ap.y + dy * t) * h, linePaint)
            } else drawExtended(c, l.x1, l.y1, l.x2, l.y2, w, h)
        }
        levelText.color = Color.WHITE
        val label = when {
            ap != null -> "Triangle"
            longestLeading() != null -> "Leading line"
            lines.any { it.kind == 0 } -> "Horizon line"
            else -> "Vertical line"
        }
        c.drawText(label, w / 2f, h * 0.08f, levelText)
    }

    private fun drawFrame(c: Canvas, w: Float, h: Float) {
        val mx = 0.09f; val my = 0.1f
        c.drawRect(w * mx, h * my, w * (1 - mx), h * (1 - my), gridPaint)
        val iw = 1 - 2 * mx; val ih = 1 - 2 * my
        for (f in listOf(1f / 3f, 2f / 3f)) {
            c.drawLine(w * (mx + iw * f), h * my, w * (mx + iw * f), h * (1 - my), guidePaint)
            c.drawLine(w * mx, h * (my + ih * f), w * (1 - mx), h * (my + ih * f), guidePaint)
        }
        levelText.color = Color.argb(220, 255, 255, 255)
        c.drawText("Frame with a door, window, arch or branches", w / 2f, h * my - 8f * d, levelText)
    }

    private fun framePoints(): List<PointF> {
        val mx = 0.09f; val my = 0.1f
        val iw = 1 - 2 * mx; val ih = 1 - 2 * my
        return listOf(
            PointF(mx + iw / 3f, my + ih / 3f), PointF(mx + iw * 2f / 3f, my + ih / 3f),
            PointF(mx + iw / 3f, my + ih * 2f / 3f), PointF(mx + iw * 2f / 3f, my + ih * 2f / 3f),
            PointF(0.5f, my + ih * 2f / 3f)
        )
    }

    /** Name of the grid currently shown (saved with each photo). */
    fun activeGridLabel(): String = activeMode.label

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
        if (vanish != null) return GridMode.VANISHING to "Lines meet in the distance: put the subject at the vanishing point."
        if (apex != null) return GridMode.LINES to "Triangle shape (hill, roof, tent): a strong, stable composition."
        if (longestLeading() != null) return GridMode.LINES to "Strong leading line: put the subject on it so the eye follows."
        if (sc != null && sc.empty > 0.65f && (s == null || s.width() * s.height() < 0.06f)) {
            return GridMode.NEGATIVE to "Lots of plain space (sky, wall): small subject near the edge = calm, strong photo."
        }
        if (sc != null && sc.symmetry > 0.72f) {
            return GridMode.SYMMETRY to "Symmetric surroundings: center the subject for a mirror-like shot."
        }
        if (sc != null && sc.diagonal > 0.55f) {
            return if (s != null && area in 0.05f..0.25f)
                GridMode.TRIANGLES to "Strong diagonal lines around it: golden triangles follow them."
            else GridMode.DIAGONAL to "Strong diagonal lines: let them lead the eye to the subject."
        }
        if (sc != null && sc.layers.all { it > LAYER_MIN } && (s == null || s.centerY() in 0.3f..0.7f)) {
            return GridMode.LAYERS to "Foreground, middle and background all have detail: layering gives depth."
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
            score = -1
            val best = bestSpot(viewW(), viewH())
            target = best
            aligned = false
            val where = spotName(best)
            listener?.onGuidance(
                activeMode, reason + " Best spot: $where.",
                "Put your subject on the red circle ($where), then tap it to lock focus" + horizonHint(), false
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
        }.let { pts -> if (faceMode) pts.filter { it.y < 0.5f }.ifEmpty { pts } else pts }
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
        score = composeScore(s, dx, dy)
        val faceNote = if (faceMode) " Face found: keep the eyes on the upper third line." else ""
        listener?.onGuidance(activeMode, reason + faceNote + sideNote, guidance, aligned)
    }

    /** 0..100: subject on its grid point, level horizon, horizon on a third, room around the subject. */
    private fun composeScore(s: RectF, dx: Float, dy: Float): Int {
        val place = (1f - hypot(dx, dy) / 0.35f).coerceIn(0f, 1f)
        val tilt = if (rollDeg.isNaN() || flatMode) 0f else abs(residual())
        val level = (1f - tilt / 8f).coerceIn(0f, 1f)
        val hy = horizonScreenY
        val horizon = if (hy.isNaN()) 1f else {
            val d = minOf(abs(hy - 1f / 3f), abs(hy - 2f / 3f))
            (1f - d / 0.17f).coerceIn(0f, 1f)
        }
        val margin = minOf(s.left, s.top, 1f - s.right, 1f - s.bottom)
        val room = (margin / 0.04f).coerceIn(0f, 1f)
        return (100f * (0.55f * place + 0.2f * level + 0.15f * horizon + 0.1f * room)).toInt()
    }

    /** Pro overlays: focus peaking, histogram; both modes: score badge and AE/AF lock. */
    private fun drawExtras(c: Canvas, w: Float, h: Float) {
        val pk = peaking
        if (proUi && pk != null) {
            c.save()
            c.scale(peakingZoom, peakingZoom, w / 2f, h / 2f)
            c.drawBitmap(pk, null, RectF(0f, 0f, w, h), null)
            c.restore()
        }
        val hist = histogram
        if (proUi && hist != null) {
            val bw = 120f * d
            val bh = 56f * d
            val left = 10f * d
            val top = 84f * d
            c.drawRoundRect(left, top, left + bw, top + bh, 8f * d, 8f * d, boxPaint)
            val step = bw / hist.size
            for (i in hist.indices) {
                val hh = hist[i] * (bh - 8f * d)
                c.drawRect(left + i * step, top + bh - 4f * d - hh, left + (i + 1) * step, top + bh - 4f * d, histPaint)
            }
        }
        if (score >= 0) {
            val col = when {
                score >= 80 -> Color.rgb(0, 200, 110)
                score >= 60 -> Color.rgb(255, 196, 0)
                else -> Color.rgb(255, 82, 82)
            }
            val cx = w - 44f * d
            val cy = 104f * d
            val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 0, 0, 0) }
            c.drawCircle(cx, cy, 26f * d, bg)
            val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col; style = Paint.Style.STROKE; strokeWidth = 3f * d }
            c.drawArc(RectF(cx - 24f * d, cy - 24f * d, cx + 24f * d, cy + 24f * d), -90f, 360f * score / 100f, false, ring)
            badgeText.color = Color.WHITE
            badgeText.textSize = 16f * d
            c.drawText(score.toString(), cx, cy + 6f * d, badgeText)
            badgeText.textSize = 10f * d
            c.drawText("score", cx, cy + 40f * d, badgeText)
        }
        if (aeAfLocked) {
            val tw = 120f * d
            val left = (w - tw) / 2f
            val top = h - 120f * d
            val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 214, 0) }
            c.drawRoundRect(left, top, left + tw, top + 28f * d, 14f * d, 14f * d, bg)
            badgeText.color = Color.BLACK
            badgeText.textSize = 13f * d
            c.drawText("AE/AF LOCK", w / 2f, top + 19f * d, badgeText)
        }
    }

    /**
     * Best grid point for the current grid when no subject is tapped yet:
     * leaves open space on the emptier side and favours the upper points (eyes, heads, tops).
     */
    private fun bestSpot(w: Float, h: Float): PointF {
        val open = scene?.openSide ?: 0f
        val cands: List<PointF> = when (activeMode) {
            GridMode.TRIANGLES -> trianglePoints(false, w, h) + trianglePoints(true, w, h)
            else -> strongPoints(activeMode, w, h)
        }
        val best = cands.minByOrNull { p ->
            var score = 0f
            if (open > 0.1f && p.x > 0.55f) score += 1f      // right side is emptier -> subject left
            if (open < -0.1f && p.x < 0.45f) score += 1f     // left side is emptier -> subject right
            score += p.y * 0.6f                              // prefer upper points
            score += abs(p.x - 0.5f) * 0.1f
            score
        } ?: PointF(0.5f, 0.5f)
        if (activeMode == GridMode.TRIANGLES) {
            trianglesMirrored = trianglePoints(true, w, h).any { abs(it.x - best.x) < 1e-3f && abs(it.y - best.y) < 1e-3f }
        }
        if (activeMode == GridMode.SPIRAL) {
            spiralFlipX = abs(best.x - spiralEye.x) > 0.001f
            spiralFlipY = abs(best.y - spiralEye.y) > 0.001f
        }
        return best
    }

    private fun spotName(p: PointF): String {
        val v = when { p.y < 0.45f -> "upper"; p.y > 0.55f -> "lower"; else -> "middle" }
        val hz = when { p.x < 0.45f -> "left"; p.x > 0.55f -> "right"; else -> "center" }
        return if (v == "middle" && hz == "center") "center" else "$v-$hz"
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
        GridMode.LINES -> linePoints()
        GridMode.VANISHING -> listOf(vanish?.let { PointF(it.x, it.y) } ?: PointF(0.5f, 0.5f))
        GridMode.SYMMETRY -> listOf(PointF(0.5f, 1f / 3f), PointF(0.5f, 0.5f), PointF(0.5f, 2f / 3f))
        GridMode.NEGATIVE -> negativePoints()
        GridMode.LAYERS -> listOf(PointF(1f / 3f, 0.5f), PointF(2f / 3f, 0.5f), PointF(0.5f, 0.5f))
        GridMode.FRAME -> framePoints()
        GridMode.CENTER, GridMode.SAFE, GridMode.AUTO -> listOf(PointF(0.5f, 0.5f))
    }

    // ---------- Drawing ----------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        drawGridCached(canvas, w, h)
        when (activeMode) {
            GridMode.LINES -> drawSceneLines(canvas, w, h)
            GridMode.VANISHING -> drawVanishing(canvas, w, h)
            GridMode.SYMMETRY -> drawSymmetry(canvas, w, h)
            GridMode.NEGATIVE -> drawNegative(canvas, w, h)
            GridMode.LAYERS -> drawLayers(canvas, w, h)
            else -> {}
        }
        if (!horizonScreenY.isNaN()) {
            canvas.drawLine(0f, horizonScreenY * h, w, horizonScreenY * h, horizonPaint)
        }
        drawLevel(canvas, w, h)
        drawExtras(canvas, w, h)
        if (colourWheel) drawColourWheel(canvas, w, h)

        val pts = if (activeMode == GridMode.SPIRAL) listOf(currentEye()) else strongPoints(activeMode, w, h)
        for (p in pts) canvas.drawCircle(p.x * w, p.y * h, 4f * d, dotPaint)

        val now = SystemClock.uptimeMillis()

        subject?.let { s ->
            // Pixel-style: a focus ring on the tapped spot (it follows that spot, nothing else)
            val fx = s.centerX() * w
            val fy = s.centerY() * h
            val t = target
            if (t != null && !aligned) {
                canvas.drawLine(fx, fy, t.x * w, t.y * h, linkPaint)
            }
            canvas.drawCircle(fx, fy, 30f * d, focusBracket)
            canvas.drawCircle(fx, fy, 5f * d, subjectFill)
            val nearTarget = t != null && hypot(t.x * w - fx, t.y * h - fy) < 90f * d
            if (!nearTarget) {
                labelPaint.color = Color.WHITE
                canvas.drawText("Focus locked", fx, fy + 30f * d + 18f * d, labelPaint)
            }
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
                if (aligned) "Perfect" else if (subject == null) "Best spot" else "Put subject here",
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

        // ~25 fps is plenty for the pulse animation and keeps the preview smooth
        if (target != null || focusActive) postInvalidateDelayed(40)
    }

    // Grid lines (with shadows) are drawn once into a bitmap and reused every frame.
    private var gridCache: Bitmap? = null
    private var gridKey = ""

    private fun drawGridCached(canvas: Canvas, w: Float, h: Float) {
        val key = "$activeMode|$spiralFlipX|$spiralFlipY|$trianglesMirrored|${width}x$height"
        var bmp = gridCache
        if (key != gridKey || bmp == null) {
            if (bmp == null || bmp.width != width || bmp.height != height) {
                bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            }
            bmp.eraseColor(Color.TRANSPARENT)
            val c = Canvas(bmp)
        when (activeMode) {
                GridMode.THIRDS -> drawGridLines(c, 1f / 3f, 2f / 3f, w, h)
                GridMode.PHI -> drawGridLines(c, 0.382f, 0.618f, w, h)
                GridMode.SPIRAL -> drawSpiral(c, w, h)
                GridMode.DIAGONAL -> drawDiagonals(c, w, h)
                GridMode.TRIANGLES -> drawTriangles(c, w, h)
                GridMode.CENTER, GridMode.AUTO -> drawCenter(c, w, h)
                GridMode.SQUARE -> drawSquare(c, w, h)
                GridMode.ARMATURE -> drawArmature(c, w, h)
                GridMode.SAFE -> drawSafe(c, w, h)
                GridMode.FRAME -> drawFrame(c, w, h)
                GridMode.LINES, GridMode.VANISHING, GridMode.SYMMETRY, GridMode.NEGATIVE, GridMode.LAYERS -> {}   // live, drawn every frame
            }
            gridCache = bmp
            gridKey = key
        }
        canvas.drawBitmap(bmp, 0f, 0f, null)
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
