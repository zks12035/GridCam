package com.uwrlab.gridcam

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Colour looks inspired by Sony's "Creative Style" settings. */
enum class CreativeStyle(val label: String) {
    STANDARD("Standard"),
    VIVID("Vivid"),
    PORTRAIT("Portrait"),
    LANDSCAPE("Landscape"),
    NEUTRAL("Neutral"),
    SUNSET("Sunset"),
    BW("B/W");

    fun matrix(): ColorMatrix {
        // saturation, contrast, lift (0..255), red gain, blue gain
        val (sat, con, lift, rg, bg) = when (this) {
            STANDARD -> floatArrayOf(1.05f, 1.04f, 0f, 1f, 1f)
            VIVID -> floatArrayOf(1.35f, 1.10f, 0f, 1f, 1f)
            PORTRAIT -> floatArrayOf(0.92f, 0.95f, 6f, 1.04f, 0.97f)
            LANDSCAPE -> floatArrayOf(1.25f, 1.12f, 0f, 0.99f, 1.03f)
            NEUTRAL -> floatArrayOf(0.80f, 0.90f, 4f, 1f, 1f)
            SUNSET -> floatArrayOf(1.20f, 1.05f, 0f, 1.12f, 0.85f)
            BW -> floatArrayOf(0f, 1.12f, 0f, 1f, 1f)
        }
        val m = ColorMatrix()
        m.setSaturation(sat)
        val t = 128f * (1f - con) + lift
        m.postConcat(ColorMatrix(floatArrayOf(
            con, 0f, 0f, 0f, t,
            0f, con, 0f, 0f, t,
            0f, 0f, con, 0f, t,
            0f, 0f, 0f, 1f, 0f
        )))
        m.postConcat(ColorMatrix(floatArrayOf(
            rg, 0f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f, 0f,
            0f, 0f, bg, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        )))
        return m
    }

    fun paint(): Paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(matrix()) }

    fun apply(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, 0f, 0f, paint())
        return out
    }
}

/**
 * Night mode without Google's engine: several handheld shots are aligned and averaged
 * (less noise), moving things are rejected, then shadows are brightened.
 */
class NightStacker {
    private var w = 0
    private var h = 0
    private var ref: IntArray? = null
    private var refSmall: FloatArray? = null
    private var sw = 0
    private var sh = 0
    private lateinit var accR: IntArray
    private lateinit var accG: IntArray
    private lateinit var accB: IntArray
    private lateinit var count: ByteArray
    var frames = 0
        private set

    fun add(bmp: Bitmap) {
        if (ref == null) {
            w = bmp.width
            h = bmp.height
            val px = IntArray(w * h)
            bmp.getPixels(px, 0, w, 0, 0, w, h)
            ref = px
            sw = max(1, w / 4)
            sh = max(1, h / 4)
            refSmall = smallGray(bmp)
            accR = IntArray(w * h)
            accG = IntArray(w * h)
            accB = IntArray(w * h)
            count = ByteArray(w * h)
            for (i in px.indices) {
                accR[i] = Color.red(px[i]); accG[i] = Color.green(px[i]); accB[i] = Color.blue(px[i])
                count[i] = 1
            }
            frames = 1
            return
        }
        if (bmp.width != w || bmp.height != h) return
        val r = ref ?: return
        val small = smallGray(bmp)
        val (dx, dy) = align(refSmall!!, small)
        val ox = dx * 4
        val oy = dy * 4
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        for (y in 0 until h) {
            val sy = y + oy
            if (sy < 0 || sy >= h) continue
            for (x in 0 until w) {
                val sx = x + ox
                if (sx < 0 || sx >= w) continue
                val p = px[sy * w + sx]
                val i = y * w + x
                val q = r[i]
                val lp = (Color.red(p) * 3 + Color.green(p) * 6 + Color.blue(p)) / 10
                val lq = (Color.red(q) * 3 + Color.green(q) * 6 + Color.blue(q)) / 10
                if (abs(lp - lq) < 30) { // reject moving things (ghosts)
                    accR[i] += Color.red(p); accG[i] += Color.green(p); accB[i] += Color.blue(p)
                    count[i] = (count[i] + 1).toByte()
                }
            }
        }
        frames++
    }

    /** Final night photo: averaged, brightened, slightly sharpened. */
    fun result(): Bitmap? {
        if (ref == null) return null
        val n = w * h
        val out = IntArray(n)
        var lumSum = 0.0
        val avg = FloatArray(n * 3)
        for (i in 0 until n) {
            val c = max(1, count[i].toInt())
            val r = accR[i].toFloat() / c / 255f
            val g = accG[i].toFloat() / c / 255f
            val b = accB[i].toFloat() / c / 255f
            avg[i * 3] = r; avg[i * 3 + 1] = g; avg[i * 3 + 2] = b
            lumSum += 0.299 * r + 0.587 * g + 0.114 * b
        }
        val mean = (lumSum / n).toFloat()
        val gain = (0.36f / max(mean, 0.01f)).coerceIn(1f, 4f)
        val a = 0.6f
        fun tone(v: Float): Float = (v * gain / (1f + v * gain * a) * (1f + a)).coerceIn(0f, 1f)
        for (i in 0 until n) {
            var r = tone(avg[i * 3]); var g = tone(avg[i * 3 + 1]); var b = tone(avg[i * 3 + 2])
            val l = 0.299f * r + 0.587f * g + 0.114f * b
            r = l + (r - l) * 1.1f; g = l + (g - l) * 1.1f; b = l + (b - l) * 1.1f
            out[i] = Color.rgb((r * 255).toInt().coerceIn(0, 255), (g * 255).toInt().coerceIn(0, 255), (b * 255).toInt().coerceIn(0, 255))
        }
        val bmp = Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
        return sharpen(bmp)
    }

    private fun smallGray(bmp: Bitmap): FloatArray {
        val s = Bitmap.createScaledBitmap(bmp, sw, sh, true)
        val px = IntArray(sw * sh)
        s.getPixels(px, 0, sw, 0, 0, sw, sh)
        return FloatArray(sw * sh) { (Color.red(px[it]) * 0.3f + Color.green(px[it]) * 0.59f + Color.blue(px[it]) * 0.11f) / 255f }
    }

    /** Finds the shift (in small-image pixels) that best matches the frame to the reference. */
    private fun align(a: FloatArray, b: FloatArray): Pair<Int, Int> {
        val range = 12
        var best = Float.MAX_VALUE
        var bx = 0
        var by = 0
        for (dy in -range..range) for (dx in -range..range) {
            var sum = 0f
            var cnt = 0
            var y = range
            while (y < sh - range) {
                var x = range
                while (x < sw - range) {
                    sum += abs(a[y * sw + x] - b[(y + dy) * sw + (x + dx)])
                    cnt++
                    x += 3
                }
                y += 3
            }
            if (cnt > 0) {
                val s = sum / cnt
                if (s < best) { best = s; bx = dx; by = dy }
            }
        }
        return Pair(bx, by)
    }

    private fun sharpen(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val blur = Bitmap.createScaledBitmap(Bitmap.createScaledBitmap(src, max(1, w / 2), max(1, h / 2), true), w, h, true)
        val s = IntArray(w * h)
        val b = IntArray(w * h)
        src.getPixels(s, 0, w, 0, 0, w, h)
        blur.getPixels(b, 0, w, 0, 0, w, h)
        val k = 0.5f
        for (i in s.indices) {
            val r = Color.red(s[i]) + k * (Color.red(s[i]) - Color.red(b[i]))
            val g = Color.green(s[i]) + k * (Color.green(s[i]) - Color.green(b[i]))
            val bl = Color.blue(s[i]) + k * (Color.blue(s[i]) - Color.blue(b[i]))
            s[i] = Color.rgb(r.toInt().coerceIn(0, 255), g.toInt().coerceIn(0, 255), bl.toInt().coerceIn(0, 255))
        }
        return Bitmap.createBitmap(s, w, h, Bitmap.Config.ARGB_8888)
    }
}

/** Lens-style background blur: round (disc) bokeh with glowing highlights, like a fast prime lens. */
object LensBlur {
    /** [radiusFrac] = blur radius as a fraction of image width (f/1.4 ≈ 0.045, f/8 ≈ 0.008). */
    fun apply(src: Bitmap, radiusFrac: Float): Bitmap {
        val scale = 4
        val sw = max(1, src.width / scale)
        val sh = max(1, src.height / scale)
        val small = Bitmap.createScaledBitmap(src, sw, sh, true)
        val n = sw * sh
        val px = IntArray(n)
        small.getPixels(px, 0, sw, 0, 0, sw, sh)
        // work in (approximately) linear light; bright points get extra weight -> bokeh balls
        val r = FloatArray(n)
        val g = FloatArray(n)
        val b = FloatArray(n)
        val wgt = FloatArray(n)
        for (i in 0 until n) {
            val rr = Color.red(px[i]) / 255f
            val gg = Color.green(px[i]) / 255f
            val bb = Color.blue(px[i]) / 255f
            val l = 0.299f * rr + 0.587f * gg + 0.114f * bb
            val boost = if (l > 0.78f) 1f + (l - 0.78f) * 18f else 1f
            wgt[i] = boost
            r[i] = rr * rr * boost; g[i] = gg * gg * boost; b[i] = bb * bb * boost
        }
        val rad = max(1.5f, radiusFrac * sw)
        val step = max(1f, rad / 5f)
        val offs = ArrayList<Int>()
        var oy = -rad
        while (oy <= rad) {
            var ox = -rad
            while (ox <= rad) {
                if (ox * ox + oy * oy <= rad * rad) { offs.add(ox.toInt()); offs.add(oy.toInt()) }
                ox += step
            }
            oy += step
        }
        val o = offs.toIntArray()
        val out = IntArray(n)
        for (y in 0 until sh) for (x in 0 until sw) {
            var sr = 0f; var sg = 0f; var sb = 0f; var sw8 = 0f
            var k = 0
            while (k < o.size) {
                val xx = min(sw - 1, max(0, x + o[k]))
                val yy = min(sh - 1, max(0, y + o[k + 1]))
                val j = yy * sw + xx
                sr += r[j]; sg += g[j]; sb += b[j]; sw8 += wgt[j]
                k += 2
            }
            val inv = 1f / max(1e-6f, sw8)
            out[y * sw + x] = Color.rgb(
                (sqrt(sr * inv) * 255).toInt().coerceIn(0, 255),
                (sqrt(sg * inv) * 255).toInt().coerceIn(0, 255),
                (sqrt(sb * inv) * 255).toInt().coerceIn(0, 255)
            )
        }
        val blurredSmall = Bitmap.createBitmap(out, sw, sh, Bitmap.Config.ARGB_8888)
        return Bitmap.createScaledBitmap(blurredSmall, src.width, src.height, true)
    }

    fun radiusFor(fNumber: Float): Float = (0.034f * (1.8f / fNumber)).coerceIn(0.004f, 0.06f)
}
