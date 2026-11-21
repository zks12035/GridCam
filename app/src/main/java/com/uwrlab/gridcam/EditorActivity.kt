package com.uwrlab.gridcam

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.content.Context
import android.content.Intent
import android.os.Build
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
    var cropRect = floatArrayOf(0f, 0f, 1f, 1f)   // left, top, right, bottom (0..1) after rotate/straighten
    var cropAspect = 0                            // index in EditRender.ASPECTS (0 = free)
    var curve = FloatArray(3)           // tone curve: shadows, midtones, highlights
    var hsl = FloatArray(24)            // 8 colour bands × (hue, saturation, luminance)
    var denoise = FloatArray(2)         // luminance, colour noise reduction (0..1)
    var perspV = 0f                     // vertical keystone (-1..1)
    var perspH = 0f                     // horizontal keystone (-1..1)
    var strokes = ArrayList<Stroke>()   // healing + selective brush strokes (in final-image coordinates)

    fun copy(): EditState {
        val c = EditState()
        c.tune = tune.copyOf(); c.details = details.copyOf(); c.wb = wb.copyOf()
        c.vignette = vignette.copyOf(); c.blur = blur.copyOf(); c.blurCx = blurCx; c.blurCy = blurCy
        c.look = look; c.lookStrength = lookStrength; c.bw = bw; c.bwParams = bwParams.copyOf()
        c.bwFilter = bwFilter; c.rot90 = rot90; c.flip = flip; c.straighten = straighten
        c.cropRect = cropRect.copyOf(); c.cropAspect = cropAspect
        c.curve = curve.copyOf(); c.hsl = hsl.copyOf(); c.denoise = denoise.copyOf()
        c.perspV = perspV; c.perspH = perspH; c.strokes = ArrayList(strokes)
        return c
    }
}

/**
 * A brush stroke. [pts] = x,y pairs (0..1 of the edited image), [radius] = fraction of width.
 * kind: 0 heal, 1 brightness, 2 saturation, 3 warmth, 4 structure; [amount] -1..1.
 */
class Stroke(val pts: FloatArray, val radius: Float, val kind: Int, val amount: Float)

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
    val ASPECTS = listOf("Free" to 0f, "Original" to -1f, "1:1" to 1f, "4:5" to 0.8f, "3:4" to 0.75f, "4:3" to 4f / 3f,
        "16:9" to 16f / 9f, "9:16" to 9f / 16f, "3:2" to 1.5f)
    val BW_FILTERS = listOf("Neutral" to floatArrayOf(0.299f, 0.587f, 0.114f), "Red" to floatArrayOf(0.6f, 0.3f, 0.1f),
        "Orange" to floatArrayOf(0.45f, 0.45f, 0.1f), "Yellow" to floatArrayOf(0.35f, 0.55f, 0.1f),
        "Green" to floatArrayOf(0.2f, 0.7f, 0.1f))

    fun render(src: Bitmap, st: EditState): Bitmap {
        var b = geometry(src, st)
        if (st.strokes.any { it.kind == 0 }) b = heal(b, st)
        b = tone(b, st)
        if (st.hsl.any { abs(it) > 0.005f }) b = colourMixer(b, st)
        if (st.denoise[0] > 0.01f || st.denoise[1] > 0.01f) b = denoise(b, st)
        if (st.details[0] > 0.01f || st.details[1] > 0.01f) b = details(b, st)
        if (st.strokes.any { it.kind != 0 }) b = brush(b, st)
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
        if (abs(st.perspV) > 0.003f || abs(st.perspH) > 0.003f) b = perspective(b, st.perspV, st.perspH)
        if (abs(st.straighten) > 0.003f) b = PhotoEditor.straighten(b, st.straighten * 15f)
        val r = st.cropRect
        if (r[0] > 0.001f || r[1] > 0.001f || r[2] < 0.999f || r[3] < 0.999f) {
            val x = (r[0] * b.width).roundToInt().coerceIn(0, b.width - 1)
            val y = (r[1] * b.height).roundToInt().coerceIn(0, b.height - 1)
            val cw = ((r[2] - r[0]) * b.width).roundToInt().coerceIn(1, b.width - x)
            val ch = ((r[3] - r[1]) * b.height).roundToInt().coerceIn(1, b.height - y)
            b = Bitmap.createBitmap(b, x, y, cw, ch)
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
        if (t.all { abs(it) < 0.005f } && abs(temp) < 0.005f && abs(tint) < 0.005f && st.curve.all { abs(it) < 0.005f }) return src
        val cv = st.curve
        val lut = FloatArray(256)
        val gamma = 1f - 0.5f * bright
        for (i in 0..255) {
            var v = i / 255f
            v = Math.pow(v.toDouble(), gamma.toDouble()).toFloat()
            val wh = smooth(0.5f, 1f, v)
            val ws = 1f - smooth(0f, 0.5f, v)
            v += hi * 0.25f * wh + sh * 0.25f * ws + amb * (0.15f * ws - 0.1f * wh)
            v = (v - 0.5f) * (1f + con * 0.8f) + 0.5f
            v = curveAt(v.coerceIn(0f, 1f), cv)
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

    /** Smooth tone curve through (0,0) (¼, ¼+s) (½, ½+m) (¾, ¾+h) (1,1). */
    private fun curveAt(v: Float, c: FloatArray): Float {
        if (c[0] == 0f && c[1] == 0f && c[2] == 0f) return v
        val xs = floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f)
        val ys = floatArrayOf(0f, 0.25f + c[0] * 0.15f, 0.5f + c[1] * 0.15f, 0.75f + c[2] * 0.15f, 1f)
        val k = (v * 4f).toInt().coerceIn(0, 3)
        val t = (v - xs[k]) / 0.25f
        // Catmull-Rom between ys[k] and ys[k+1]
        val p0 = ys[max(0, k - 1)]; val p1 = ys[k]; val p2 = ys[k + 1]; val p3 = ys[min(4, k + 2)]
        val t2 = t * t; val t3 = t2 * t
        return 0.5f * ((2 * p1) + (-p0 + p2) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t2 + (-p0 + 3 * p1 - 3 * p2 + p3) * t3)
    }

    /** Keystone correction: makes converging lines (buildings) straight. */
    private fun perspective(src: Bitmap, pv: Float, ph: Float): Bitmap {
        val w = src.width.toFloat(); val h = src.height.toFloat()
        val top = max(0f, pv) * 0.2f * w
        val bottom = max(0f, -pv) * 0.2f * w
        val left = max(0f, ph) * 0.2f * h
        val right = max(0f, -ph) * 0.2f * h
        val srcPts = floatArrayOf(top, left, w - top, right, w - bottom, h - right, bottom, h - left)
        val dstPts = floatArrayOf(0f, 0f, w, 0f, w, h, 0f, h)
        val m = Matrix()
        m.setPolyToPoly(srcPts, 0, dstPts, 0, 4)
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    val HSL_BANDS = listOf("Red" to 0f, "Orange" to 30f, "Yellow" to 60f, "Green" to 120f,
        "Aqua" to 180f, "Blue" to 225f, "Purple" to 270f, "Magenta" to 315f)

    /** Colour mixer: hue / saturation / luminance per colour band. */
    private fun colourMixer(src: Bitmap, st: EditState): Bitmap {
        val w = src.width; val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val hsv = FloatArray(3)
        val hl = st.hsl
        for (i in px.indices) {
            Color.colorToHSV(px[i], hsv)
            if (hsv[1] < 0.04f) continue
            var dh = 0f; var ds = 0f; var dl = 0f
            for (bnd in 0 until 8) {
                var d = abs(hsv[0] - HSL_BANDS[bnd].second)
                if (d > 180f) d = 360f - d
                val wgt = 1f - d / 45f
                if (wgt <= 0f) continue
                dh += wgt * hl[bnd * 3]; ds += wgt * hl[bnd * 3 + 1]; dl += wgt * hl[bnd * 3 + 2]
            }
            if (dh == 0f && ds == 0f && dl == 0f) continue
            val amt = min(1f, hsv[1] * 2f)      // grey pixels barely change
            hsv[0] = (hsv[0] + dh * 30f * amt + 360f) % 360f
            hsv[1] = (hsv[1] * (1f + ds * amt)).coerceIn(0f, 1f)
            hsv[2] = (hsv[2] * (1f + dl * 0.5f * amt)).coerceIn(0f, 1f)
            px[i] = Color.HSVToColor(hsv)
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    /** Noise reduction: colour noise (chroma) smoothing + edge-aware luminance smoothing. */
    private fun denoise(src: Bitmap, st: EditState): Bitmap {
        val lum = st.denoise[0]; val col = st.denoise[1]
        val w = src.width; val h = src.height
        val s = IntArray(w * h); val c = IntArray(w * h); val l = IntArray(w * h)
        src.getPixels(s, 0, w, 0, 0, w, h)
        scaleBlur(src, (2 + col * 8).toInt().coerceAtLeast(2)).getPixels(c, 0, w, 0, 0, w, h)
        scaleBlur(src, 2).getPixels(l, 0, w, 0, 0, w, h)
        for (i in s.indices) {
            val r0 = (s[i] shr 16) and 0xff; val g0 = (s[i] shr 8) and 0xff; val b0 = s[i] and 0xff
            var y = 0.299f * r0 + 0.587f * g0 + 0.114f * b0
            var cb = b0 - y; var cr = r0 - y
            val rc = (c[i] shr 16) and 0xff; val gc = (c[i] shr 8) and 0xff; val bc = c[i] and 0xff
            val yc = 0.299f * rc + 0.587f * gc + 0.114f * bc
            cb += (bc - yc - cb) * col
            cr += (rc - yc - cr) * col
            val rl = (l[i] shr 16) and 0xff; val gl = (l[i] shr 8) and 0xff; val bl = l[i] and 0xff
            val yl = 0.299f * rl + 0.587f * gl + 0.114f * bl
            val edge = (abs(y - yl) / 18f).coerceIn(0f, 1f)          // keep real edges sharp
            y += (yl - y) * lum * (1f - edge)
            val r = y + cr
            val b = y + cb
            val g = (y - 0.299f * r - 0.114f * b) / 0.587f
            s[i] = (0xff shl 24) or (r.toInt().coerceIn(0, 255) shl 16) or (g.toInt().coerceIn(0, 255) shl 8) or b.toInt().coerceIn(0, 255)
        }
        return Bitmap.createBitmap(s, w, h, Bitmap.Config.ARGB_8888)
    }

    /** Soft round brush mask (0..1) for strokes of one kind; returns null if none. */
    private fun strokeMask(w: Int, h: Int, strokes: List<Stroke>, signed: Boolean): FloatArray? {
        if (strokes.isEmpty()) return null
        val m = FloatArray(w * h)
        for (st in strokes) {
            val r = max(2f, st.radius * w)
            val p = st.pts
            var k = 0
            var px = -1f; var py = -1f
            while (k + 1 < p.size) {
                val x = p[k] * w; val y = p[k + 1] * h
                // dabs along the path
                val steps = if (px < 0) 1 else max(1, (sqrt((x - px) * (x - px) + (y - py) * (y - py)) / (r * 0.3f)).toInt())
                for (sIdx in 1..steps) {
                    val cx = if (px < 0) x else px + (x - px) * sIdx / steps
                    val cy = if (py < 0) y else py + (y - py) * sIdx / steps
                    val x0 = max(0, (cx - r).toInt()); val x1 = min(w - 1, (cx + r).toInt())
                    val y0 = max(0, (cy - r).toInt()); val y1 = min(h - 1, (cy + r).toInt())
                    for (yy in y0..y1) for (xx in x0..x1) {
                        val d = sqrt((xx - cx) * (xx - cx) + (yy - cy) * (yy - cy)) / r
                        if (d >= 1f) continue
                        val v = (1f - smooth(0.55f, 1f, d)) * (if (signed) st.amount else 1f)
                        val i = yy * w + xx
                        if (abs(v) > abs(m[i])) m[i] = v
                    }
                }
                px = x; py = y
                k += 2
            }
        }
        return m
    }

    /** Selective brush: local brightness, saturation, warmth, structure. */
    private fun brush(src: Bitmap, st: EditState): Bitmap {
        val w = src.width; val h = src.height
        val masks = (1..4).map { kind -> strokeMask(w, h, st.strokes.filter { it.kind == kind }, true) }
        if (masks.all { it == null }) return src
        val s = IntArray(w * h)
        src.getPixels(s, 0, w, 0, 0, w, h)
        val blur = if (masks[3] != null) IntArray(w * h).also { scaleBlur(src, 10).getPixels(it, 0, w, 0, 0, w, h) } else null
        val mb = masks[0]; val ms = masks[1]; val mw = masks[2]; val mt = masks[3]
        for (i in s.indices) {
            var r = ((s[i] shr 16) and 0xff).toFloat(); var g = ((s[i] shr 8) and 0xff).toFloat(); var b = (s[i] and 0xff).toFloat()
            if (mt != null && blur != null && mt[i] != 0f) {
                val k = mt[i] * 1.2f
                r += k * (r - ((blur[i] shr 16) and 0xff)); g += k * (g - ((blur[i] shr 8) and 0xff)); b += k * (b - (blur[i] and 0xff))
            }
            if (mb != null && mb[i] != 0f) { val f = 1f + mb[i] * 0.6f; r *= f; g *= f; b *= f }
            if (ms != null && ms[i] != 0f) {
                val l = 0.299f * r + 0.587f * g + 0.114f * b
                val f = 1f + ms[i]
                r = l + (r - l) * f; g = l + (g - l) * f; b = l + (b - l) * f
            }
            if (mw != null && mw[i] != 0f) { r *= 1f + mw[i] * 0.15f; b *= 1f - mw[i] * 0.15f }
            s[i] = (0xff shl 24) or (r.toInt().coerceIn(0, 255) shl 16) or (g.toInt().coerceIn(0, 255) shl 8) or b.toInt().coerceIn(0, 255)
        }
        return Bitmap.createBitmap(s, w, h, Bitmap.Config.ARGB_8888)
    }

    /**
     * Healing: for each stroke, finds the best-matching nearby patch (by comparing the ring around
     * the area) and copies it in with soft edges and colour matching.
     */
    private fun heal(src: Bitmap, st: EditState): Bitmap {
        val w = src.width; val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        for (stroke in st.strokes.filter { it.kind == 0 }) {
            val mask = strokeMask(w, h, listOf(stroke), false) ?: continue
            var x0 = w; var y0 = h; var x1 = 0; var y1 = 0
            for (y in 0 until h) for (x in 0 until w) if (mask[y * w + x] > 0.01f) {
                if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y
            }
            if (x1 < x0) continue
            val bw = x1 - x0 + 1; val bh = y1 - y0 + 1
            val ring = max(3, (stroke.radius * w * 0.4f).toInt())
            // ring pixels: just outside the mask
            val ringIdx = ArrayList<Int>()
            val rx0 = max(0, x0 - ring); val rx1 = min(w - 1, x1 + ring)
            val ry0 = max(0, y0 - ring); val ry1 = min(h - 1, y1 + ring)
            var yy = ry0
            while (yy <= ry1) {
                var xx = rx0
                while (xx <= rx1) {
                    if (mask[yy * w + xx] < 0.01f) ringIdx.add(yy * w + xx)
                    xx += 2
                }
                yy += 2
            }
            if (ringIdx.isEmpty()) continue
            var bestDx = 0; var bestDy = 0; var best = Float.MAX_VALUE
            val size = max(bw, bh)
            for (dist in listOf(1.2f, 1.7f, 2.4f)) for (a in 0 until 16) {
                val ang = a * Math.PI / 8
                val dx = (Math.cos(ang) * size * dist).toInt()
                val dy = (Math.sin(ang) * size * dist).toInt()
                if (rx0 + dx < 0 || rx1 + dx >= w || ry0 + dy < 0 || ry1 + dy >= h) continue
                var sum = 0f
                for (idx in ringIdx) {
                    val sIdx = idx + dy * w + dx
                    val a1 = px[idx]; val b1 = px[sIdx]
                    sum += abs(((a1 shr 16) and 0xff) - ((b1 shr 16) and 0xff)) +
                        abs(((a1 shr 8) and 0xff) - ((b1 shr 8) and 0xff)) + abs((a1 and 0xff) - (b1 and 0xff))
                    if (sum >= best) break
                }
                if (sum < best) { best = sum; bestDx = dx; bestDy = dy }
            }
            if (best == Float.MAX_VALUE) continue
            // colour match: average difference on the ring
            var dr = 0f; var dg = 0f; var db = 0f
            for (idx in ringIdx) {
                val a1 = px[idx]; val b1 = px[idx + bestDy * w + bestDx]
                dr += ((a1 shr 16) and 0xff) - ((b1 shr 16) and 0xff)
                dg += ((a1 shr 8) and 0xff) - ((b1 shr 8) and 0xff)
                db += (a1 and 0xff) - (b1 and 0xff)
            }
            val n = ringIdx.size.toFloat()
            dr /= n; dg /= n; db /= n
            val copy = px.copyOf()
            for (y in y0..y1) for (x in x0..x1) {
                val i = y * w + x
                val a = mask[i]
                if (a <= 0.01f) continue
                val sIdx = i + bestDy * w + bestDx
                val sc = copy[sIdx]
                val r = ((sc shr 16) and 0xff) + dr; val g = ((sc shr 8) and 0xff) + dg; val b = (sc and 0xff) + db
                val o = copy[i]
                val rr = ((o shr 16) and 0xff) * (1 - a) + r * a
                val gg = ((o shr 8) and 0xff) * (1 - a) + g * a
                val bb2 = (o and 0xff) * (1 - a) + b * a
                px[i] = (0xff shl 24) or (rr.toInt().coerceIn(0, 255) shl 16) or (gg.toInt().coerceIn(0, 255) shl 8) or bb2.toInt().coerceIn(0, 255)
            }
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

/** Grid Cam's built-in photo editor, laid out like Google Photos: tabs → setting tiles → slider. */
class EditorActivity : AppCompatActivity() {

    private class Setting(val name: String, val glyph: String, val param: Param,
                          val brushKind: Int = -1, val brushAmount: Float = 0f)
    private class Category(
        val name: String,
        val settings: List<Setting> = emptyList(),
        val chips: List<String> = emptyList(),
        val chipSelected: (EditState) -> Int = { -1 },
        val onChip: (EditState, Int) -> Unit = { _, _ -> }
    )

    private var uri: Uri? = null
    private var previewSrc: Bitmap? = null
    private var shown: Bitmap? = null
    private var state = EditState()
    private var beforeTool: EditState? = null
    private val history = ArrayList<EditState>()
    private var category: Category? = null
    private var setting: Setting? = null
    private lateinit var worker: ExecutorService
    @Volatile private var version = 0
    private val density by lazy { resources.displayMetrics.density }

    private lateinit var image: ImageView
    private lateinit var title: TextView
    private lateinit var bigValue: TextView
    private lateinit var editPanel: LinearLayout
    private lateinit var sliderRow: View
    private lateinit var sliderLabel: TextView
    private lateinit var slider: SeekBar
    private lateinit var chipsScroll: View
    private lateinit var chipsBar: LinearLayout
    private lateinit var tilesScroll: View
    private lateinit var tilesBar: LinearLayout
    private lateinit var tabsBar: LinearLayout
    private lateinit var cropView: CropView
    private lateinit var cropPanel: LinearLayout
    private lateinit var aspectBar: LinearLayout
    private lateinit var aspectScroll: View
    private lateinit var straightenLabel: TextView
    private lateinit var straightenBar: SeekBar
    private var cropping = false
    private var hslBand = 0
    private var brushSize = 0.04f
    private var cropTarget = 0          // straighten ruler controls: 0 straighten, 1 vertical, 2 horizontal
    private lateinit var strokeView: StrokeView

    private fun dp(v: Int) = (v * density).toInt()

    private val categories: List<Category> by lazy {
        fun s(name: String, glyph: String, min: Float, max: Float, get: (EditState) -> Float, set: (EditState, Float) -> Unit) =
            Setting(name, glyph, Param(name, min, max, get, set))
        // brush tiles: the slider sets the brush size; paint on the photo with your finger
        fun b(name: String, glyph: String, kind: Int, amount: Float) =
            Setting(name, glyph, Param("Brush size", 0.01f, 0.12f, { brushSize }, { _, v -> brushSize = v }), kind, amount)
        listOf(
            Category("Auto"),
            Category("Crop"),
            Category("Adjust", listOf(
                s("Brightness", "☀", -1f, 1f, { it.tune[0] }, { st, v -> st.tune[0] = v }),
                s("Contrast", "◑", -1f, 1f, { it.tune[1] }, { st, v -> st.tune[1] = v }),
                s("Highlights", "◔", -1f, 1f, { it.tune[4] }, { st, v -> st.tune[4] = v }),
                s("Shadows", "◕", -1f, 1f, { it.tune[5] }, { st, v -> st.tune[5] = v }),
                s("Saturation", "◉", -1f, 1f, { it.tune[2] }, { st, v -> st.tune[2] = v }),
                s("Ambiance", "✺", -1f, 1f, { it.tune[3] }, { st, v -> st.tune[3] = v }),
                s("Warmth", "♨", -1f, 1f, { it.tune[6] }, { st, v -> st.tune[6] = v }),
                s("Temperature", "🌡", -1f, 1f, { it.wb[0] }, { st, v -> st.wb[0] = v }),
                s("Tint", "◐", -1f, 1f, { it.wb[1] }, { st, v -> st.wb[1] = v }),
                s("Structure", "◈", 0f, 1f, { it.details[0] }, { st, v -> st.details[0] = v }),
                s("Sharpen", "△", 0f, 1f, { it.details[1] }, { st, v -> st.details[1] = v }),
                s("Noise", "◌", 0f, 1f, { it.denoise[0] }, { st, v -> st.denoise[0] = v }),
                s("Colour noise", "◍", 0f, 1f, { it.denoise[1] }, { st, v -> st.denoise[1] = v }),
                s("Curve shadows", "╰", -1f, 1f, { it.curve[0] }, { st, v -> st.curve[0] = v }),
                s("Curve mids", "∿", -1f, 1f, { it.curve[1] }, { st, v -> st.curve[1] = v }),
                s("Curve highlights", "╮", -1f, 1f, { it.curve[2] }, { st, v -> st.curve[2] = v })
            )),
            Category("Colour",
                listOf(
                    s("Hue", "◐", -1f, 1f, { it.hsl[hslBand * 3] }, { st, v -> st.hsl[hslBand * 3] = v }),
                    s("Saturation", "◉", -1f, 1f, { it.hsl[hslBand * 3 + 1] }, { st, v -> st.hsl[hslBand * 3 + 1] = v }),
                    s("Luminance", "☀", -1f, 1f, { it.hsl[hslBand * 3 + 2] }, { st, v -> st.hsl[hslBand * 3 + 2] = v })
                ),
                chips = EditRender.HSL_BANDS.map { it.first },
                chipSelected = { hslBand },
                onChip = { _, i -> hslBand = i }),
            Category("Retouch", listOf(
                b("Heal", "✚", 0, 1f), b("Brighten", "☀", 1, 0.6f), b("Darken", "☾", 1, -0.6f),
                b("Saturate", "◉", 2, 0.7f), b("Desaturate", "○", 2, -0.7f),
                b("Warm", "♨", 3, 0.7f), b("Cool", "❄", 3, -0.7f), b("Detail", "◈", 4, 0.8f)
            )),
            Category("Presets"),
            Category("Filters",
                listOf(s("Strength", "✦", 0f, 1f, { it.lookStrength }, { st, v -> st.lookStrength = v })),
                chips = listOf("None") + CreativeStyle.values().map { it.label },
                chipSelected = { it.look },
                onChip = { st, i -> st.look = i }),
            Category("B&W",
                listOf(
                    s("Brightness", "☀", -1f, 1f, { it.bwParams[0] }, { st, v -> st.bwParams[0] = v }),
                    s("Contrast", "◑", -1f, 1f, { it.bwParams[1] }, { st, v -> st.bwParams[1] = v }),
                    s("Grain", "⁘", 0f, 1f, { it.bwParams[2] }, { st, v -> st.bwParams[2] = v })
                ),
                chips = listOf("Off") + EditRender.BW_FILTERS.map { it.first },
                chipSelected = { if (it.bw) it.bwFilter + 1 else 0 },
                onChip = { st, i -> if (i == 0) { st.bw = false } else { st.bw = true; st.bwFilter = i - 1 } }),
            Category("Effects", listOf(
                s("Vignette", "◎", -1f, 1f, { it.vignette[0] }, { st, v -> st.vignette[0] = v }),
                s("Centre light", "◌", -1f, 1f, { it.vignette[1] }, { st, v -> st.vignette[1] = v }),
                s("Blur", "◍", 0f, 1f, { it.blur[0] }, { st, v -> st.blur[0] = v }),
                s("Blur size", "○", 0.1f, 0.8f, { it.blur[1] }, { st, v -> st.blur[1] = v })
            ))
        )
    }

    /** Photo shared into Grid Cam from another app ("Share → Edit with Grid Cam"). */
    @Suppress("DEPRECATION")
    private fun streamUri(i: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else i.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        uri = intent.data ?: streamUri(intent)
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

    private fun hscroll(child: View) = HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        addView(child)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(textButton("✕", 22f) { if (cropping) cancelCrop() else finish() })
        title = TextView(this).apply {
            text = "Edit"
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
        }
        top.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(textButton("↶", 22f) { undo() })
        top.addView(textButton("✓", 22f) { if (cropping) applyCrop() else save() })
        root.addView(top)

        val frame = FrameLayout(this)
        image = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        frame.addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        bigValue = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 18f
            setPadding(dp(16), dp(8), dp(16), dp(8))
            background = GradientDrawable().apply { cornerRadius = 20f * density; setColor(Color.argb(150, 0, 0, 0)) }
            visibility = View.GONE
        }
        frame.addView(bigValue, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL
        ).apply { topMargin = dp(12) })
        strokeView = StrokeView(this)
        frame.addView(strokeView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        cropView = CropView(this).apply { visibility = View.GONE }
        frame.addView(cropView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        attachGestures(frame)
        root.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // edit panel: slider (after tapping a tile) · chips · tiles · tabs
        editPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(4), 0, dp(14)) }
        val sRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(6), dp(18), dp(6))
            visibility = View.GONE
        }
        sliderLabel = TextView(this).apply { setTextColor(Color.WHITE); textSize = 15f; minWidth = dp(120) }
        sRow.addView(sliderLabel)
        slider = SeekBar(this).apply {
            max = 200
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    if (fromUser) setValueFraction(p / 200f)
                }
                override fun onStartTrackingTouch(sb: SeekBar) { pushHistory() }
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        sRow.addView(slider, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        sliderRow = sRow
        editPanel.addView(sRow)
        chipsBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(10), dp(4), dp(10), dp(4)) }
        chipsScroll = hscroll(chipsBar).apply { visibility = View.GONE }
        editPanel.addView(chipsScroll)
        tilesBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(8), dp(4), dp(8), dp(4)) }
        tilesScroll = hscroll(tilesBar).apply { visibility = View.GONE }
        editPanel.addView(tilesScroll)
        tabsBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(8), dp(8), dp(8), dp(4)) }
        editPanel.addView(hscroll(tabsBar))
        root.addView(editPanel)
        root.addView(buildCropPanel())
        buildTabs()
        return root
    }

    // ---------- tabs → tiles → slider ----------

    private fun buildTabs() {
        tabsBar.removeAllViews()
        for (c in categories) {
            tabsBar.addView(TextView(this).apply {
                text = c.name
                textSize = 16f
                setTextColor(Color.WHITE)
                setPadding(dp(18), dp(10), dp(18), dp(10))
                background = GradientDrawable().apply {
                    cornerRadius = 40f * density
                    setColor(Color.TRANSPARENT)
                    if (c == category) setStroke(dp(2), Color.WHITE)
                }
                setOnClickListener { selectCategory(c) }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(4)
            })
        }
    }

    private fun selectCategory(c: Category) {
        if (previewSrc == null) return
        when (c.name) {
            "Auto" -> { autoFix(); return }
            "Crop" -> { openCrop(); return }
        }
        category = c
        setting = null
        sliderRow.visibility = View.GONE
        buildTabs()
        buildChips()
        buildTiles()
        tilesScroll.visibility = if (c.settings.isEmpty()) View.GONE else View.VISIBLE
        title.text = c.name
    }

    private fun buildChips() {
        val c = category ?: return
        chipsBar.removeAllViews()
        if (c.name == "Presets") { buildPresetChips(); return }
        chipsScroll.visibility = if (c.chips.isEmpty()) View.GONE else View.VISIBLE
        c.chips.forEachIndexed { i, label ->
            chipsBar.addView(TextView(this).apply {
                text = label
                textSize = 14f
                setPadding(dp(14), dp(8), dp(14), dp(8))
                val on = c.chipSelected(state) == i
                background = chipBg(on)
                setTextColor(if (on) Color.BLACK else Color.WHITE)
                setOnClickListener { pushHistory(); c.onChip(state, i); buildChips(); refreshSlider(); render() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(6)
            })
        }
    }

    // ---------- presets ----------

    private fun presetPrefs() = getSharedPreferences("gridcam_presets", MODE_PRIVATE)

    private fun loadPresets(): org.json.JSONArray =
        try { org.json.JSONArray(presetPrefs().getString("list", "[]")) } catch (e: Exception) { org.json.JSONArray() }

    private fun buildPresetChips() {
        chipsScroll.visibility = View.VISIBLE
        val list = loadPresets()
        val labels = ArrayList<String>()
        labels.add("+ Save current look")
        for (i in 0 until list.length()) labels.add("★ " + list.getJSONObject(i).optString("name"))
        labels.forEachIndexed { i, label ->
            chipsBar.addView(TextView(this).apply {
                text = label
                textSize = 14f
                setPadding(dp(14), dp(8), dp(14), dp(8))
                background = chipBg(i == 0)
                setTextColor(if (i == 0) Color.BLACK else Color.WHITE)
                setOnClickListener { if (i == 0) savePreset() else applyPreset(list.getJSONObject(i - 1)) }
                setOnLongClickListener {
                    if (i > 0) { deletePreset(i - 1); true } else false
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(6)
            })
        }
        title.text = "Presets (long-press one to delete)"
    }

    private fun arr(a: FloatArray) = org.json.JSONArray().also { j -> a.forEach { j.put(it.toDouble()) } }
    private fun fill(dst: FloatArray, j: org.json.JSONArray?) { if (j != null) for (i in 0 until min(dst.size, j.length())) dst[i] = j.optDouble(i).toFloat() }

    /** Saves all colour/tone settings (not crop or brush strokes) as a reusable look. */
    private fun savePreset() {
        val list = loadPresets()
        val o = org.json.JSONObject()
        o.put("name", "My look " + (list.length() + 1))
        o.put("tune", arr(state.tune)); o.put("details", arr(state.details)); o.put("wb", arr(state.wb))
        o.put("vignette", arr(state.vignette)); o.put("curve", arr(state.curve)); o.put("hsl", arr(state.hsl))
        o.put("denoise", arr(state.denoise)); o.put("bwParams", arr(state.bwParams))
        o.put("look", state.look); o.put("lookStrength", state.lookStrength.toDouble())
        o.put("bw", state.bw); o.put("bwFilter", state.bwFilter)
        list.put(o)
        presetPrefs().edit().putString("list", list.toString()).apply()
        Toast.makeText(this, "Saved as \"" + o.getString("name") + "\"", Toast.LENGTH_SHORT).show()
        buildChips()
    }

    private fun applyPreset(o: org.json.JSONObject) {
        pushHistory()
        fill(state.tune, o.optJSONArray("tune")); fill(state.details, o.optJSONArray("details")); fill(state.wb, o.optJSONArray("wb"))
        fill(state.vignette, o.optJSONArray("vignette")); fill(state.curve, o.optJSONArray("curve")); fill(state.hsl, o.optJSONArray("hsl"))
        fill(state.denoise, o.optJSONArray("denoise")); fill(state.bwParams, o.optJSONArray("bwParams"))
        state.look = o.optInt("look"); state.lookStrength = o.optDouble("lookStrength", 1.0).toFloat()
        state.bw = o.optBoolean("bw"); state.bwFilter = o.optInt("bwFilter")
        render()
    }

    private fun deletePreset(index: Int) {
        val list = loadPresets()
        val out = org.json.JSONArray()
        for (i in 0 until list.length()) if (i != index) out.put(list.get(i))
        presetPrefs().edit().putString("list", out.toString()).apply()
        Toast.makeText(this, "Preset deleted", Toast.LENGTH_SHORT).show()
        buildChips()
    }

    private fun buildTiles() {
        val c = category ?: return
        tilesBar.removeAllViews()
        for (st in c.settings) {
            val on = st == setting
            val tile = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(6), dp(6), dp(6), dp(6))
                setOnClickListener { selectSetting(st) }
            }
            tile.addView(TextView(this).apply {
                text = st.name
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(if (on) Color.rgb(255, 214, 0) else Color.WHITE)
            })
            tile.addView(TextView(this).apply {
                text = st.glyph
                textSize = 26f
                gravity = Gravity.CENTER
                setTextColor(if (on) Color.BLACK else Color.WHITE)
                background = GradientDrawable().apply {
                    cornerRadius = 18f * density
                    setColor(if (on) Color.rgb(255, 214, 0) else Color.argb(45, 255, 255, 255))
                }
            }, LinearLayout.LayoutParams(dp(84), dp(64)).apply { topMargin = dp(6) })
            tilesBar.addView(tile)
        }
    }

    private fun selectSetting(st: Setting) {
        setting = st
        if (st.brushKind >= 0) {
            Toast.makeText(this, if (st.brushKind == 0) "Paint over the spot to remove it" else "Paint where you want: ${st.name}", Toast.LENGTH_SHORT).show()
        }
        sliderRow.visibility = View.VISIBLE
        buildTiles()
        refreshSlider()
    }

    private fun fmt(p: Param, v: Float): String {
        val n = (v * 100).roundToInt()
        return if (p.min < 0f && n > 0) "+$n" else "$n"
    }

    private fun refreshSlider() {
        val st = setting ?: return
        val p = st.param
        val v = p.get(state)
        slider.progress = ((v - p.min) / (p.max - p.min) * 200).roundToInt()
        sliderLabel.text = st.name + "  " + fmt(p, v)
    }

    private fun setValueFraction(f: Float) {
        val st = setting ?: return
        val p = st.param
        p.set(state, p.min + f * (p.max - p.min))
        refreshSlider()
        render()
    }

    private fun pushHistory() {
        history.add(state.copy())
        if (history.size > 50) history.removeAt(0)
    }

    // ---------- photo gestures: hold = original, swipe left/right = adjust ----------

    @SuppressLint("ClickableViewAccessibility")
    private fun attachGestures(frame: View) {
        var downX = 0f; var downY = 0f; var lastX = 0f
        var moved = false
        var showingOriginal = false
        val showOriginal = Runnable {
            showingOriginal = true
            image.setImageBitmap(previewSrc)
            bigValue.text = "Original"
            bigValue.visibility = View.VISIBLE
        }
        frame.setOnTouchListener { v, e ->
            if (cropping) return@setOnTouchListener false
            val brushSetting = setting?.takeIf { it.brushKind >= 0 }
            if (brushSetting != null) { paint(e, v, brushSetting); return@setOnTouchListener true }
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.x; downY = e.y; lastX = e.x; moved = false; showingOriginal = false
                    frame.postDelayed(showOriginal, 250)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!moved && (abs(e.x - downX) > dp(12) || abs(e.y - downY) > dp(12))) {
                        moved = true
                        frame.removeCallbacks(showOriginal)
                        if (!showingOriginal && setting != null) pushHistory()
                    }
                    val st = setting
                    if (moved && !showingOriginal && st != null && abs(e.x - downX) > abs(e.y - downY)) {
                        val p = st.param
                        val delta = (e.x - lastX) / v.width * (p.max - p.min)
                        p.set(state, (p.get(state) + delta).coerceIn(p.min, p.max))
                        refreshSlider(); render()
                        bigValue.text = sliderLabel.text
                        bigValue.visibility = View.VISIBLE
                    }
                    lastX = e.x
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    frame.removeCallbacks(showOriginal)
                    if (showingOriginal) image.setImageBitmap(shown ?: previewSrc)
                    else if (!moved && setting?.name?.startsWith("Blur") == true) setBlurCenter(e.x, e.y, v)
                    showingOriginal = false
                    bigValue.postDelayed({ bigValue.visibility = View.GONE }, 500)
                }
            }
            true
        }
    }

    private val paintPts = ArrayList<Float>()

    /** Maps a touch on the view to 0..1 coordinates of the displayed photo, or null if outside. */
    private fun toImage(x: Float, y: Float, v: View): PointF? {
        val bmp = shown ?: previewSrc ?: return null
        val scale = min(v.width.toFloat() / bmp.width, v.height.toFloat() / bmp.height)
        val ox = (v.width - bmp.width * scale) / 2f
        val oy = (v.height - bmp.height * scale) / 2f
        val nx = (x - ox) / (bmp.width * scale)
        val ny = (y - oy) / (bmp.height * scale)
        return if (nx in 0f..1f && ny in 0f..1f) PointF(nx, ny) else null
    }

    /** Brush / heal painting with the finger. */
    private fun paint(e: MotionEvent, v: View, st: Setting) {
        val bmp = shown ?: previewSrc ?: return
        val scale = min(v.width.toFloat() / bmp.width, v.height.toFloat() / bmp.height)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                paintPts.clear()
                strokeView.start(brushSize * bmp.width * scale, if (st.brushKind == 0) Color.argb(120, 255, 80, 80) else Color.argb(110, 255, 214, 0))
                toImage(e.x, e.y, v)?.let { paintPts.add(it.x); paintPts.add(it.y) }
                strokeView.add(e.x, e.y)
            }
            MotionEvent.ACTION_MOVE -> {
                toImage(e.x, e.y, v)?.let { paintPts.add(it.x); paintPts.add(it.y) }
                strokeView.add(e.x, e.y)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                strokeView.clear()
                if (paintPts.size >= 2) {
                    pushHistory()
                    state.strokes.add(Stroke(paintPts.toFloatArray(), brushSize, st.brushKind, st.brushAmount))
                    render()
                }
            }
        }
    }

    /** Lens blur: tap on the photo to choose the sharp spot. */
    private fun setBlurCenter(x: Float, y: Float, v: View) {
        val bmp = shown ?: previewSrc ?: return
        val scale = min(v.width.toFloat() / bmp.width, v.height.toFloat() / bmp.height)
        val ox = (v.width - bmp.width * scale) / 2f
        val oy = (v.height - bmp.height * scale) / 2f
        pushHistory()
        state.blurCx = ((x - ox) / (bmp.width * scale)).coerceIn(0f, 1f)
        state.blurCy = ((y - oy) / (bmp.height * scale)).coerceIn(0f, 1f)
        if (state.blur[0] < 0.05f) state.blur[0] = 0.5f
        render()
    }

    private fun undo() {
        if (cropping) { cancelCrop(); return }
        if (history.isEmpty()) { Toast.makeText(this, "Nothing to undo", Toast.LENGTH_SHORT).show(); return }
        state = history.removeAt(history.size - 1)
        refreshSlider()
        buildChips()
        render()
    }

    private fun autoFix() {
        val src = previewSrc ?: return
        worker.execute {
            val report = PhotoAnalyzer.analyze(src)
            val a = report.auto
            runOnUiThread {
                pushHistory()
                state.tune[0] = (a[0] * 0.8f).coerceIn(-1f, 1f)
                state.tune[1] = a[1]
                state.tune[2] = a[5]
                state.tune[4] = a[2]
                state.tune[5] = a[3]
                state.tune[6] = a[4]
                Toast.makeText(this, "Auto applied (↶ to undo)", Toast.LENGTH_SHORT).show()
                refreshSlider()
                render()
            }
        }
    }

    // ---------- crop screen (Google Photos style) ----------

    private fun roundButton(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 15f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        setPadding(dp(16), dp(10), dp(16), dp(10))
        background = GradientDrawable().apply { cornerRadius = 30f * density; setColor(Color.argb(70, 255, 255, 255)) }
        setOnClickListener { onClick() }
    }

    private fun buildCropPanel(): View {
        cropPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(10), dp(6), dp(10), dp(16))
        }
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        fun lp() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = dp(4); marginEnd = dp(4)
        }
        actions.addView(roundButton("✨ Auto Frame") { cropView.autoFrame() }, lp())
        actions.addView(roundButton("▭ Aspect") {
            aspectScroll.visibility = if (aspectScroll.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }, lp())
        actions.addView(roundButton("⇋") { state.flip = !state.flip; refreshCropImage(resetFrame = true) }, lp())
        actions.addView(roundButton("↻") { state.rot90 = (state.rot90 + 1) % 4; refreshCropImage(resetFrame = true) }, lp())
        cropPanel.addView(actions)

        aspectBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
        aspectScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            visibility = View.GONE
            addView(aspectBar)
        }
        cropPanel.addView(aspectScroll)

        cropTargetBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, dp(8), 0, 0) }
        cropPanel.addView(cropTargetBar)

        val ruler = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(10), dp(8), 0)
        }
        straightenLabel = TextView(this).apply {
            text = "0°"; setTextColor(Color.WHITE); textSize = 16f; minWidth = dp(52)
        }
        ruler.addView(straightenLabel)
        straightenBar = SeekBar(this).apply {
            max = 300
            progress = 150
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val v = (p - 150) / 150f
                    when (cropTarget) {
                        0 -> state.straighten = v
                        1 -> state.perspV = v
                        else -> state.perspH = v
                    }
                    updateRulerLabel()
                    refreshCropImage(resetFrame = false)
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        ruler.addView(straightenBar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        cropPanel.addView(ruler)

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        bottom.addView(roundButton("  ✕  ") { cancelCrop() })
        bottom.addView(TextView(this).apply {
            text = "Framing"; textSize = 17f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bottom.addView(TextView(this).apply {
            text = "  ✓  "
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(Color.BLACK)
            setPadding(dp(22), dp(10), dp(22), dp(10))
            background = GradientDrawable().apply { cornerRadius = 30f * density; setColor(Color.WHITE) }
            setOnClickListener { applyCrop() }
        })
        cropPanel.addView(bottom)
        return cropPanel
    }

    private lateinit var cropTargetBar: LinearLayout

    private fun rulerValue() = when (cropTarget) { 0 -> state.straighten; 1 -> state.perspV; else -> state.perspH }

    private fun updateRulerLabel() {
        val v = rulerValue()
        straightenLabel.text = if (cropTarget == 0) String.format(java.util.Locale.US, "%.1f°", v * 15f)
            else ((v * 100).roundToInt()).toString()
    }

    /** Straighten / Vertical / Horizontal: what the ruler controls (perspective fixes tilted buildings). */
    private fun buildCropTargets() {
        cropTargetBar.removeAllViews()
        listOf("Straighten", "Vertical", "Horizontal").forEachIndexed { i, label ->
            cropTargetBar.addView(TextView(this).apply {
                text = label
                textSize = 14f
                setPadding(dp(14), dp(8), dp(14), dp(8))
                val on = cropTarget == i
                background = chipBg(on)
                setTextColor(if (on) Color.BLACK else Color.WHITE)
                setOnClickListener {
                    cropTarget = i
                    straightenBar.progress = (rulerValue() * 150 + 150).roundToInt()
                    updateRulerLabel()
                    buildCropTargets()
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(3); marginEnd = dp(3)
            })
        }
    }

    private fun buildAspectChips() {
        aspectBar.removeAllViews()
        EditRender.ASPECTS.forEachIndexed { i, (label, _) ->
            aspectBar.addView(TextView(this).apply {
                text = label
                textSize = 14f
                setPadding(dp(14), dp(8), dp(14), dp(8))
                val on = state.cropAspect == i
                background = chipBg(on)
                setTextColor(if (on) Color.BLACK else Color.WHITE)
                setOnClickListener { chooseAspect(i) }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(6)
            })
        }
    }

    private fun chooseAspect(i: Int) {
        state.cropAspect = i
        val bmp = cropView.bitmap
        val ratio = EditRender.ASPECTS[i].second
        cropView.setAspect(
            when {
                ratio > 0f -> ratio
                ratio < 0f && bmp != null -> bmp.width.toFloat() / bmp.height   // "Original"
                else -> 0f
            }
        )
        buildAspectChips()
    }

    private fun openCrop() {
        beforeTool = state.copy()
        cropping = true
        editPanel.visibility = View.GONE
        cropPanel.visibility = View.VISIBLE
        image.visibility = View.INVISIBLE
        cropView.visibility = View.VISIBLE
        title.text = "Crop"
        cropTarget = 0
        straightenBar.progress = (state.straighten * 150 + 150).roundToInt()
        updateRulerLabel()
        buildCropTargets()
        buildAspectChips()
        val r = state.cropRect
        cropView.setCrop(r[0], r[1], r[2], r[3])
        refreshCropImage(resetFrame = false)
    }

    /** Shows the whole (uncropped) photo with current edits inside the crop screen. */
    private fun refreshCropImage(resetFrame: Boolean) {
        val src = previewSrc ?: return
        val st = state.copy()
        st.cropRect = floatArrayOf(0f, 0f, 1f, 1f)
        st.blur[0] = 0f
        st.strokes = ArrayList()
        val v = ++version
        worker.execute {
            if (v != version) return@execute
            val out = try { EditRender.render(src, st) } catch (e: Throwable) { null } ?: return@execute
            runOnUiThread {
                if (v != version) return@runOnUiThread
                cropView.bitmap = out
                if (resetFrame) { cropView.setCrop(0f, 0f, 1f, 1f); chooseAspect(state.cropAspect) }
            }
        }
    }

    private fun applyCrop() {
        val c = cropView.crop
        state.cropRect = floatArrayOf(c.left, c.top, c.right, c.bottom)
        beforeTool?.let { history.add(it) }
        closeCrop()
        render()
    }

    private fun cancelCrop() {
        beforeTool?.let { state = it }
        closeCrop()
        render()
    }

    private fun closeCrop() {
        cropping = false
        beforeTool = null
        cropPanel.visibility = View.GONE
        cropView.visibility = View.GONE
        image.visibility = View.VISIBLE
        editPanel.visibility = View.VISIBLE
        title.text = category?.name ?: "Edit"
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


/** Draws the finger stroke live while painting with a brush. */
class StrokeView(context: Context) : View(context) {
    private val path = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private var active = false

    fun start(width: Float, color: Int) {
        path.reset(); active = true
        paint.strokeWidth = width * 2f
        paint.color = color
    }

    fun add(x: Float, y: Float) {
        if (path.isEmpty) path.moveTo(x, y) else path.lineTo(x, y)
        invalidate()
    }

    fun clear() { path.reset(); active = false; invalidate() }

    override fun onDraw(canvas: Canvas) {
        if (active) canvas.drawPath(path, paint)
    }
}
