package com.uwrlab.gridcam

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.util.Random
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** All edit settings. Values are -1..1 unless noted. */
class EditState {
    var tune = FloatArray(7)            // brightness, contrast, saturation, ambiance, highlights, shadows, warmth
    var details = FloatArray(2)         // structure, sharpening (0..1)
    var wb = FloatArray(2)              // temperature, tint
    var vignette = FloatArray(2)        // outer brightness, inner brightness
    var blur = floatArrayOf(0f, 0.35f)  // strength (0..1), size (0.1..0.8)
    var blurCx = 0.5f
    var blurCy = 0.45f
    var look = 0                        // 0 = none, else CreativeStyle index + 1
    var lookStrength = 1f
    var bw = false
    var bwParams = FloatArray(3)        // brightness, contrast, grain (0..1)
    var bwFilter = 0
    var rot90 = 0
    var flip = false
    var straighten = 0f                 // -1..1 = ±15°
    var crop = 0
    var cropX = 0f
    var cropY = 0f

    fun copy(): EditState {
        val c = EditState()
        c.tune = tune.copyOf(); c.details = details.copyOf(); c.wb = wb.copyOf()
        c.vignette = vignette.copyOf(); c.blur = blur.copyOf(); c.blurCx = blurCx; c.blurCy = blurCy
        c.look = look; c.lookStrength = lookStrength; c.bw = bw; c.bwParams = bwParams.copyOf()
        c.bwFilter = bwFilter; c.rot90 = rot90; c.flip = flip; c.straighten = straighten
        c.crop = crop; c.cropX = cropX; c.cropY = cropY
        return c
    }
}

class Param(
    val name: String, val min: Float, val max: Float,
    val get: (EditState) -> Float, val set: (EditState, Float) -> Unit
)

class Tool(
    val name: String, val glyph: String, val params: List<Param>,
    val options: List<String> = emptyList(),
    val selectedOption: (EditState) -> Int = { -1 },
    val onOption: (EditState, Int) -> Unit = { _, _ -> },
    val onEnter: (EditState) -> Unit = {}
)

object EditRender {
    val ASPECTS = listOf("Original" to 0f, "1:1" to 1f, "4:5" to 0.8f, "3:4" to 0.75f, "4:3" to 4f / 3f,
        "16:9" to 16f / 9f, "9:16" to 9f / 16f, "3:2" to 1.5f)
    val BW_FILTERS = listOf("Neutral" to floatArrayOf(0.299f, 0.587f, 0.114f), "Red" to floatArrayOf(0.6f, 0.3f, 0.1f),
        "Orange" to floatArrayOf(0.45f, 0.45f, 0.1f), "Yellow" to floatArrayOf(0.35f, 0.55f, 0.1f),
        "Green" to floatArrayOf(0.2f, 0.7f, 0.1f))

    fun render(src: Bitmap, st: EditState): Bitmap {
        var b = geometry(src, st)
        b = tone(b, st)
        if (st.details[0] > 0.01f || st.details[1] > 0.01f) b = details(b, st)
        if (st.look > 0 && st.lookStrength > 0.01f) {
            val style = CreativeStyle.values()[st.look - 1]
            val out = b.copy(Bitmap.Config.ARGB_8888, true)
            val p = style.paint()
            p.alpha = (st.lookStrength * 255).roundToInt().coerceIn(0, 255)
            Canvas(out).drawBitmap(b, 0f, 0f, p)
            b = out
        }
        if (st.bw) b = blackWhite(b, st)
        if (abs(st.vignette[0]) > 0.01f || abs(st.vignette[1]) > 0.01f) b = vignette(b, st)
        if (st.blur[0] > 0.02f) b = lensBlur(b, st)
        return b
    }

    private fun geometry(src: Bitmap, st: EditState): Bitmap {
        var b = src
        if (st.rot90 % 4 != 0 || st.flip) {
            val m = Matrix()
            m.postRotate(90f * (st.rot90 % 4))
            if (st.flip) m.postScale(-1f, 1f)
            b = Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
        }
        if (abs(st.straighten) > 0.003f) b = PhotoEditor.straighten(b, st.straighten * 15f)
        val ratio = ASPECTS[st.crop.coerceIn(0, ASPECTS.size - 1)].second
        if (ratio > 0f) {
            var cw = b.width
            var ch = (cw / ratio).roundToInt()
            if (ch > b.height) { ch = b.height; cw = (ch * ratio).roundToInt() }
            val x = ((b.width - cw) / 2f * (1f + st.cropX)).roundToInt().coerceIn(0, b.width - cw)
            val y = ((b.height - ch) / 2f * (1f + st.cropY)).roundToInt().coerceIn(0, b.height - ch)
            b = Bitmap.createBitmap(b, x, y, max(1, cw), max(1, ch))
        }
        return b
    }

    private fun smooth(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3 - 2 * t)
    }

    private fun tone(src: Bitmap, st: EditState): Bitmap {
        val t = st.tune
        val bright = t[0]; val con = t[1]; val sat = t[2]; val amb = t[3]; val hi = t[4]; val sh = t[5]; val warm = t[6]
        val temp = st.wb[0]; val tint = st.wb[1]
        if (t.all { abs(it) < 0.005f } && abs(temp) < 0.005f && abs(tint) < 0.005f) return src
        val lut = FloatArray(256)
        val gamma = 1f - 0.5f * bright
        for (i in 0..255) {
            var v = i / 255f
            v = Math.pow(v.toDouble(), gamma.toDouble()).toFloat()
            val wh = smooth(0.5f, 1f, v)
            val ws = 1f - smooth(0f, 0.5f, v)
            v += hi * 0.25f * wh + sh * 0.25f * ws + amb * (0.15f * ws - 0.1f * wh)
            v = (v - 0.5f) * (1f + con * 0.8f) + 0.5f
            lut[i] = v.coerceIn(0f, 1f) * 255f
        }
        val rMul = 1f + warm * 0.12f + temp * 0.2f + tint * 0.06f
        val gMul = 1f - tint * 0.12f
        val bMul = 1f - warm * 0.12f - temp * 0.2f + tint * 0.06f
        val satMul = 1f + sat + amb * 0.3f
        val w = src.width; val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            val c = px[i]
            var r = lut[(c shr 16) and 0xff] * rMul
            var g = lut[(c shr 8) and 0xff] * gMul
            var bl = lut[c and 0xff] * bMul
            val l = 0.299f * r + 0.587f * g + 0.114f * bl
            r = l + (r - l) * satMul; g = l + (g - l) * satMul; bl = l + (bl - l) * satMul
            px[i] = (0xff shl 24) or (r.toInt().coerceIn(0, 255) shl 16) or
                (g.toInt().coerceIn(0, 255) shl 8) or bl.toInt().coerceIn(0, 255)
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun scaleBlur(src: Bitmap, f: Int): Bitmap {
        val s = Bitmap.createScaledBitmap(src, max(1, src.width / f), max(1, src.height / f), true)
        return Bitmap.createScaledBitmap(s, src.width, src.height, true)
    }

    /** Structure = local contrast (large radius), Sharpening = fine detail (small radius). */
    private fun details(src: Bitmap, st: EditState): Bitmap {
        val w = src.width; val h = src.height
        val s = IntArray(w * h); val bl = IntArray(w * h); val bs = IntArray(w * h)
        src.getPixels(s, 0, w, 0, 0, w, h)
        scaleBlur(src, 10).getPixels(bl, 0, w, 0, 0, w, h)
        scaleBlur(src, 2).getPixels(bs, 0, w, 0, 0, w, h)
        val ks = st.details[0] * 1.0f
        val kd = st.details[1] * 1.2f
        for (i in s.indices) {
            val c = s[i]
            fun ch(shift: Int): Int {
                val v = (c shr shift) and 0xff
                val l = (bl[i] shr shift) and 0xff
                val m = (bs[i] shr shift) and 0xff
                return (v + ks * (v - l) + kd * (v - m)).toInt().coerceIn(0, 255)
            }
            s[i] = (0xff shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
        return Bitmap.createBitmap(s, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun blackWhite(src: Bitmap, st: EditState): Bitmap {
        val wts = BW_FILTERS[st.bwFilter.coerceIn(0, BW_FILTERS.size - 1)].second
        val bright = st.bwParams[0]; val con = st.bwParams[1]; val grain = st.bwParams[2]
        val gamma = 1f - 0.5f * bright
        val lut = FloatArray(256)
        for (i in 0..255) {
            var v = Math.pow(i / 255.0, gamma.toDouble()).toFloat()
            v = (v - 0.5f) * (1f + con * 0.9f + 0.1f) + 0.5f
            lut[i] = v.coerceIn(0f, 1f) * 255f
        }
        val rnd = Random(7)
        val w = src.width; val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            val c = px[i]
            val l = (((c shr 16) and 0xff) * wts[0] + ((c shr 8) and 0xff) * wts[1] + (c and 0xff) * wts[2])
                .toInt().coerceIn(0, 255)
            var v = lut[l]
            if (grain > 0.01f) v += (rnd.nextFloat() - 0.5f) * 60f * grain
            val g = v.toInt().coerceIn(0, 255)
            px[i] = (0xff shl 24) or (g shl 16) or (g shl 8) or g
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun vignette(src: Bitmap, st: EditState): Bitmap {
        val outer = st.vignette[0]; val inner = st.vignette[1]
        val w = src.width; val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val cx = w / 2f; val cy = h / 2f
        val norm = sqrt(cx * cx + cy * cy)
        for (y in 0 until h) for (x in 0 until w) {
            val r = sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy)) / norm
            val f = 1f + outer * 0.7f * smooth(0.35f, 1f, r) + inner * 0.3f * (1f - smooth(0f, 0.55f, r))
            val i = y * w + x
            val c = px[i]
            px[i] = (0xff shl 24) or
                ((((c shr 16) and 0xff) * f).toInt().coerceIn(0, 255) shl 16) or
                ((((c shr 8) and 0xff) * f).toInt().coerceIn(0, 255) shl 8) or
                (((c and 0xff) * f).toInt().coerceIn(0, 255))
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun lensBlur(src: Bitmap, st: EditState): Bitmap {
        val blurred = LensBlur.apply(src, 0.04f * st.blur[0])
        val w = src.width; val h = src.height
        val s = IntArray(w * h); val b = IntArray(w * h)
        src.getPixels(s, 0, w, 0, 0, w, h)
        blurred.getPixels(b, 0, w, 0, 0, w, h)
        val cx = st.blurCx * w; val cy = st.blurCy * h
        val rad = st.blur[1] * max(w, h) * 0.5f
        val feather = rad * 0.6f + 1f
        for (y in 0 until h) for (x in 0 until w) {
            val d = sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy))
            val keep = 1f - smooth(rad, rad + feather, d)
            val i = y * w + x
            val c = s[i]; val o = b[i]
            fun mix(shift: Int): Int {
                val a = (c shr shift) and 0xff
                val z = (o shr shift) and 0xff
                return (a * keep + z * (1 - keep)).toInt()
            }
            s[i] = (0xff shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
        }
        return Bitmap.createBitmap(s, w, h, Bitmap.Config.ARGB_8888)
    }
}

/** Grid Cam's built-in photo editor: swipe up/down to pick a setting, left/right to change it. */
class EditorActivity : AppCompatActivity() {

    private var uri: Uri? = null
    private var previewSrc: Bitmap? = null
    private var shown: Bitmap? = null
    private var state = EditState()
    private var beforeTool: EditState? = null
    private val history = ArrayList<EditState>()
    private var tool: Tool? = null
    private var paramIndex = 0
    private lateinit var worker: ExecutorService
    @Volatile private var version = 0
    private val density by lazy { resources.displayMetrics.density }

    private lateinit var image: ImageView
    private lateinit var title: TextView
    private lateinit var bigValue: TextView
    private lateinit var toolsRow: View
    private lateinit var toolPanel: LinearLayout
    private lateinit var optionsBar: LinearLayout
    private lateinit var optionsScroll: View
    private lateinit var paramBar: LinearLayout
    private lateinit var slider: SeekBar

    private fun dp(v: Int) = (v * density).toInt()

    private val tools: List<Tool> by lazy {
        fun p(name: String, min: Float, max: Float, get: (EditState) -> Float, set: (EditState, Float) -> Unit) =
            Param(name, min, max, get, set)
        val tuneNames = listOf("Brightness", "Contrast", "Saturation", "Ambiance", "Highlights", "Shadows", "Warmth")
        listOf(
            Tool("Tune", "☀", tuneNames.mapIndexed { i, n -> p(n, -1f, 1f, { it.tune[i] }, { s, v -> s.tune[i] = v }) }),
            Tool("Details", "◈", listOf(
                p("Structure", 0f, 1f, { it.details[0] }, { s, v -> s.details[0] = v }),
                p("Sharpening", 0f, 1f, { it.details[1] }, { s, v -> s.details[1] = v })
            )),
            Tool("White balance", "◑", listOf(
                p("Temperature", -1f, 1f, { it.wb[0] }, { s, v -> s.wb[0] = v }),
                p("Tint", -1f, 1f, { it.wb[1] }, { s, v -> s.wb[1] = v })
            )),
            Tool("Crop", "⌗", listOf(
                p("Horizontal", -1f, 1f, { it.cropX }, { s, v -> s.cropX = v }),
                p("Vertical", -1f, 1f, { it.cropY }, { s, v -> s.cropY = v })
            ), options = EditRender.ASPECTS.map { it.first }, selectedOption = { it.crop },
                onOption = { s, i -> s.crop = i; s.cropX = 0f; s.cropY = 0f }),
            Tool("Rotate", "↻", listOf(
                p("Straighten", -1f, 1f, { it.straighten }, { s, v -> s.straighten = v })
            ), options = listOf("Rotate 90°", "Flip"),
                onOption = { s, i -> if (i == 0) { s.rot90 = (s.rot90 + 1) % 4 } else { s.flip = !s.flip } }),
            Tool("Vignette", "◎", listOf(
                p("Outer brightness", -1f, 1f, { it.vignette[0] }, { s, v -> s.vignette[0] = v }),
                p("Inner brightness", -1f, 1f, { it.vignette[1] }, { s, v -> s.vignette[1] = v })
            ), onEnter = { if (it.vignette[0] == 0f) it.vignette[0] = -0.4f }),
            Tool("Lens blur", "◍", listOf(
                p("Blur strength", 0f, 1f, { it.blur[0] }, { s, v -> s.blur[0] = v }),
                p("Size", 0.1f, 0.8f, { it.blur[1] }, { s, v -> s.blur[1] = v })
            ), onEnter = { if (it.blur[0] == 0f) it.blur[0] = 0.5f }),
            Tool("Looks", "✦", listOf(
                p("Strength", 0f, 1f, { it.lookStrength }, { s, v -> s.lookStrength = v })
            ), options = listOf("None") + CreativeStyle.values().map { it.label }, selectedOption = { it.look },
                onOption = { s, i -> s.look = i }),
            Tool("Black & White", "◐", listOf(
                p("Brightness", -1f, 1f, { it.bwParams[0] }, { s, v -> s.bwParams[0] = v }),
                p("Contrast", -1f, 1f, { it.bwParams[1] }, { s, v -> s.bwParams[1] = v }),
                p("Grain", 0f, 1f, { it.bwParams[2] }, { s, v -> s.bwParams[2] = v })
            ), options = EditRender.BW_FILTERS.map { it.first }, selectedOption = { it.bwFilter },
                onOption = { s, i -> s.bwFilter = i }, onEnter = { it.bw = true })
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        uri = intent.data
        if (uri == null) { finish(); return }
        worker = Executors.newSingleThreadExecutor()
        setContentView(buildUi())
        worker.execute {
            try {
                val bmp = MediaHelper.decode(contentResolver, uri!!, 1400)
                runOnUiThread { previewSrc = bmp; render() }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "Could not open photo", Toast.LENGTH_SHORT).show(); finish() }
            }
        }
    }

    private fun chipBg(on: Boolean) = GradientDrawable().apply {
        cornerRadius = 40f * density
        setColor(if (on) Color.rgb(255, 214, 0) else Color.argb(50, 255, 255, 255))
    }

    private fun textButton(label: String, size: Float = 16f, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = size
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        setPadding(dp(14), dp(10), dp(14), dp(10))
        setOnClickListener { onClick() }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        // top bar
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(textButton("✕", 20f) { if (tool != null) cancelTool() else finish() })
        title = TextView(this).apply {
            text = "Edit"
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
        }
        top.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(textButton("↶", 20f) { undo() })
        val compare = textButton("◐", 20f) {}
        compare.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> image.setImageBitmap(previewSrc)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> image.setImageBitmap(shown ?: previewSrc)
            }
            true
        }
        top.addView(compare)
        top.addView(textButton("✓", 20f) { if (tool != null) applyTool() else save() })
        root.addView(top)

        // photo with swipe controls
        val frame = FrameLayout(this)
        image = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        frame.addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        bigValue = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 22f
            setPadding(dp(16), dp(8), dp(16), dp(8))
            background = GradientDrawable().apply { cornerRadius = 20f * density; setColor(Color.argb(150, 0, 0, 0)) }
            visibility = View.GONE
        }
        frame.addView(bigValue, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL
        ).apply { topMargin = dp(12) })
        attachGestures(frame)
        root.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // tool panel (shown while a tool is open)
        toolPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        optionsBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(8), dp(4), dp(8), dp(4)) }
        optionsScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(optionsBar) }
        toolPanel.addView(optionsScroll)
        paramBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(8), dp(4), dp(8), dp(4)) }
        toolPanel.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(paramBar) })
        slider = SeekBar(this).apply {
            max = 200
            setPadding(dp(24), dp(10), dp(24), dp(14))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    if (fromUser) setValueFraction(p / 200f)
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        toolPanel.addView(slider)
        root.addView(toolPanel)

        // tools grid
        val toolBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(6), dp(8), dp(6), dp(16)) }
        for (t in tools) {
            toolBar.addView(TextView(this).apply {
                text = t.glyph + "\n" + t.name
                gravity = Gravity.CENTER
                textSize = 12f
                setTextColor(Color.WHITE)
                setPadding(dp(10), dp(6), dp(10), dp(6))
                setOnClickListener { openTool(t) }
            }, LinearLayout.LayoutParams(dp(84), ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        toolBar.addView(TextView(this).apply {
            text = "✨\nAuto"
            gravity = Gravity.CENTER
            textSize = 12f
            setTextColor(Color.WHITE)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setOnClickListener { autoFix() }
        }, LinearLayout.LayoutParams(dp(84), ViewGroup.LayoutParams.WRAP_CONTENT))
        toolsRow = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(toolBar) }
        root.addView(toolsRow)
        return root
    }

    // ---------- gestures: vertical = choose setting, horizontal = change it ----------

    @SuppressLint("ClickableViewAccessibility")
    private fun attachGestures(frame: View) {
        var downX = 0f; var downY = 0f; var lastX = 0f; var accY = 0f
        var mode = 0 // 0 = undecided, 1 = horizontal, 2 = vertical
        frame.setOnTouchListener { v, e ->
            val t = tool
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.x; downY = e.y; lastX = e.x; accY = 0f; mode = 0
                    if (t == null) image.setImageBitmap(previewSrc) // hold to compare
                }
                MotionEvent.ACTION_MOVE -> if (t != null && t.params.isNotEmpty()) {
                    val dx = e.x - downX; val dy = e.y - downY
                    if (mode == 0 && (abs(dx) > dp(12) || abs(dy) > dp(12))) mode = if (abs(dx) > abs(dy)) 1 else 2
                    if (mode == 1) {
                        val p = t.params[paramIndex]
                        val delta = (e.x - lastX) / v.width * (p.max - p.min)
                        p.set(state, (p.get(state) + delta).coerceIn(p.min, p.max))
                        lastX = e.x
                        refreshParam(); render()
                    } else if (mode == 2) {
                        accY = dy
                        val steps = (accY / dp(45)).toInt()
                        val idx = (paramIndex + steps).coerceIn(0, t.params.size - 1)
                        if (idx != paramIndex) { paramIndex = idx; downY = e.y; refreshParam() }
                        showBig(t.params[paramIndex])
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (t == null) image.setImageBitmap(shown ?: previewSrc)
                    else if (mode == 0 && t.name == "Lens blur") setBlurCenter(e.x, e.y, v)
                    bigValue.postDelayed({ bigValue.visibility = View.GONE }, 700)
                }
            }
            true
        }
    }

    /** Lens blur: tap on the photo to choose the sharp spot. */
    private fun setBlurCenter(x: Float, y: Float, v: View) {
        val bmp = shown ?: previewSrc ?: return
        val scale = min(v.width.toFloat() / bmp.width, v.height.toFloat() / bmp.height)
        val ox = (v.width - bmp.width * scale) / 2f
        val oy = (v.height - bmp.height * scale) / 2f
        state.blurCx = ((x - ox) / (bmp.width * scale)).coerceIn(0f, 1f)
        state.blurCy = ((y - oy) / (bmp.height * scale)).coerceIn(0f, 1f)
        render()
    }

    private fun fmt(p: Param, v: Float): String {
        val n = if (p.min < 0f) (v * 100).roundToInt() else (v * 100).roundToInt()
        return if (p.min < 0f && n > 0) "+$n" else "$n"
    }

    private fun showBig(p: Param) {
        bigValue.text = p.name + "  " + fmt(p, p.get(state))
        bigValue.visibility = View.VISIBLE
    }

    // ---------- tools ----------

    private fun openTool(t: Tool) {
        if (previewSrc == null) return
        beforeTool = state.copy()
        t.onEnter(state)
        tool = t
        paramIndex = 0
        toolsRow.visibility = View.GONE
        toolPanel.visibility = View.VISIBLE
        buildToolPanel()
        refreshParam()
        render()
    }

    private fun buildToolPanel() {
        val t = tool ?: return
        optionsBar.removeAllViews()
        optionsScroll.visibility = if (t.options.isEmpty()) View.GONE else View.VISIBLE
        t.options.forEachIndexed { i, label ->
            optionsBar.addView(TextView(this).apply {
                text = label
                textSize = 13f
                setPadding(dp(12), dp(6), dp(12), dp(6))
                val on = t.selectedOption(state) == i
                background = chipBg(on)
                setTextColor(if (on) Color.BLACK else Color.WHITE)
                setOnClickListener { t.onOption(state, i); buildToolPanel(); refreshParam(); render() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(6)
            })
        }
        paramBar.removeAllViews()
        t.params.forEachIndexed { i, p ->
            paramBar.addView(TextView(this).apply {
                text = p.name
                textSize = 13f
                setPadding(dp(12), dp(6), dp(12), dp(6))
                tag = i
                setOnClickListener { paramIndex = i; refreshParam() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(6)
            })
        }
    }

    private fun refreshParam() {
        val t = tool ?: return
        for (k in 0 until paramBar.childCount) {
            val c = paramBar.getChildAt(k) as TextView
            val on = k == paramIndex
            c.background = chipBg(on)
            c.setTextColor(if (on) Color.BLACK else Color.WHITE)
        }
        if (t.params.isEmpty()) { title.text = t.name; return }
        val p = t.params[paramIndex]
        val v = p.get(state)
        slider.progress = ((v - p.min) / (p.max - p.min) * 200).roundToInt()
        title.text = t.name + " · " + p.name + " " + fmt(p, v)
    }

    private fun setValueFraction(f: Float) {
        val t = tool ?: return
        if (t.params.isEmpty()) return
        val p = t.params[paramIndex]
        p.set(state, p.min + f * (p.max - p.min))
        refreshParam(); render()
    }

    private fun applyTool() {
        val before = beforeTool
        if (before != null) history.add(before)
        closeTool()
    }

    private fun cancelTool() {
        beforeTool?.let { state = it }
        closeTool()
        render()
    }

    private fun closeTool() {
        tool = null
        beforeTool = null
        toolPanel.visibility = View.GONE
        toolsRow.visibility = View.VISIBLE
        title.text = "Edit"
    }

    private fun undo() {
        if (tool != null) { cancelTool(); return }
        if (history.isEmpty()) { Toast.makeText(this, "Nothing to undo", Toast.LENGTH_SHORT).show(); return }
        state = history.removeAt(history.size - 1)
        render()
    }

    private fun autoFix() {
        val src = previewSrc ?: return
        worker.execute {
            val report = PhotoAnalyzer.analyze(src)
            val a = report.auto
            runOnUiThread {
                history.add(state.copy())
                state.tune[0] = (a[0] * 0.8f).coerceIn(-1f, 1f)
                state.tune[1] = a[1]
                state.tune[2] = a[5]
                state.tune[4] = a[2]
                state.tune[5] = a[3]
                state.tune[6] = a[4]
                Toast.makeText(this, "Auto applied (Undo to remove)", Toast.LENGTH_SHORT).show()
                render()
            }
        }
    }

    // ---------- rendering & saving ----------

    private fun render() {
        val src = previewSrc ?: return
        val st = state.copy()
        val v = ++version
        worker.execute {
            if (v != version) return@execute
            val out = try { EditRender.render(src, st) } catch (e: Throwable) { null } ?: return@execute
            runOnUiThread {
                if (v == version) { shown = out; image.setImageBitmap(out) }
            }
        }
    }

    private fun save() {
        val u = uri ?: return
        val st = state.copy()
        Toast.makeText(this, "Saving…", Toast.LENGTH_SHORT).show()
        worker.execute {
            try {
                val full = MediaHelper.decode(contentResolver, u, 4000)
                val out = EditRender.render(full, st)
                val saved = MediaHelper.saveJpeg(
                    contentResolver, out, MediaHelper.newName("GridCam_edit_"),
                    MediaHelper.makeInfo(0f, MediaHelper.readInfo(contentResolver, u)["grid"] ?: "", "Edited in Grid Cam")
                )
                runOnUiThread {
                    Toast.makeText(this, if (saved != null) "Saved as a new photo" else "Save failed", Toast.LENGTH_SHORT).show()
                    if (saved != null) { setResult(RESULT_OK); finish() }
                }
            } catch (e: Throwable) {
                runOnUiThread { Toast.makeText(this, "Save failed: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::worker.isInitialized) worker.shutdown()
    }
}
