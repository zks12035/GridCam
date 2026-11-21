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
/**
 * [brighten] = true for Night (lift shadows); false for the Natural look (just clean, true-to-life).
 * [sharpen] = amount of gentle sharpening at the end.
 */
class NightStacker(private val brighten: Boolean = true, private val sharpen: Float = 0.5f) {
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
        val gain = if (brighten) (0.36f / max(mean, 0.01f)).coerceIn(1f, 4f) else 1f
        val a = 0.6f
        fun tone(v: Float): Float =
            if (brighten) (v * gain / (1f + v * gain * a) * (1f + a)).coerceIn(0f, 1f) else v.coerceIn(0f, 1f)
        val satBoost = if (brighten) 1.1f else 1.0f
        for (i in 0 until n) {
            var r = tone(avg[i * 3]); var g = tone(avg[i * 3 + 1]); var b = tone(avg[i * 3 + 2])
            val l = 0.299f * r + 0.587f * g + 0.114f * b
            r = l + (r - l) * satBoost; g = l + (g - l) * satBoost; b = l + (b - l) * satBoost
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
        val k = sharpen
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
        val step = max(1f, rad / 6f)
        // Smooth first so the sparse disc samples can't leave a dotted "mesh" pattern
        val pre = kotlin.math.ceil(step * 1.1f).toInt().coerceAtLeast(1)
        boxBlur(r, sw, sh, pre); boxBlur(g, sw, sh, pre); boxBlur(b, sw, sh, pre); boxBlur(wgt, sw, sh, pre)
        val offs = ArrayList<Int>()
        var oy = -rad
        while (oy <= rad) {
            var ox = -rad
            while (ox <= rad) {
                if (ox * ox + oy * oy <= rad * rad) { offs.add(Math.round(ox)); offs.add(Math.round(oy)) }
                ox += step
            }
            oy += step
        }
        val o = offs.toIntArray()
        val fr = FloatArray(n)
        val fg = FloatArray(n)
        val fb = FloatArray(n)
        // spread the rows over all CPU cores
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(threads)
        val jobs = (0 until threads).map { t ->
            pool.submit(Runnable {
                var y = t
                while (y < sh) {
                    for (x in 0 until sw) {
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
                        val i = y * sw + x
                        fr[i] = sr * inv; fg[i] = sg * inv; fb[i] = sb * inv
                    }
                    y += threads
                }
            })
        }
        jobs.forEach { it.get() }
        pool.shutdown()
        // final light smoothing before enlarging back to full size
        boxBlur(fr, sw, sh, 1); boxBlur(fg, sw, sh, 1); boxBlur(fb, sw, sh, 1)
        val out = IntArray(n)
        for (i in 0 until n) {
            out[i] = Color.rgb(
                (sqrt(fr[i]) * 255).toInt().coerceIn(0, 255),
                (sqrt(fg[i]) * 255).toInt().coerceIn(0, 255),
                (sqrt(fb[i]) * 255).toInt().coerceIn(0, 255)
            )
        }
        val blurredSmall = Bitmap.createBitmap(out, sw, sh, Bitmap.Config.ARGB_8888)
        // enlarge in two steps for a smoother result
        val mid = Bitmap.createScaledBitmap(blurredSmall, max(1, src.width / 2), max(1, src.height / 2), true)
        return Bitmap.createScaledBitmap(mid, src.width, src.height, true)
    }

    /** Fast separable box blur (in place) with the given radius. */
    fun boxBlur(a: FloatArray, w: Int, h: Int, rad: Int) {
        if (rad < 1) return
        val tmp = FloatArray(a.size)
        val div = (2 * rad + 1).toFloat()
        for (y in 0 until h) {
            val row = y * w
            var sum = 0f
            for (k in -rad..rad) sum += a[row + min(w - 1, max(0, k))]
            for (x in 0 until w) {
                tmp[row + x] = sum / div
                sum += a[row + min(w - 1, x + rad + 1)] - a[row + max(0, x - rad)]
            }
        }
        for (x in 0 until w) {
            var sum = 0f
            for (k in -rad..rad) sum += tmp[min(h - 1, max(0, k)) * w + x]
            for (y in 0 until h) {
                a[y * w + x] = sum / div
                sum += tmp[min(h - 1, y + rad + 1) * w + x] - tmp[max(0, y - rad) * w + x]
            }
        }
    }

    /** Cheap soft blur (for the "a little out of focus" middle distance). */
    fun soft(src: Bitmap, radiusFrac: Float): Bitmap {
        val k = max(2f, radiusFrac * src.width / 1.5f)
        val sw = max(1, (src.width / k).toInt())
        val sh = max(1, (src.height / k).toInt())
        val small = Bitmap.createScaledBitmap(src, sw, sh, true)
        val mid = Bitmap.createScaledBitmap(small, max(1, src.width / 3), max(1, src.height / 3), true)
        return Bitmap.createScaledBitmap(mid, src.width, src.height, true)
    }

    /** Blur strength: f/1.8 ≈ 2.8% of the width (strong, creamy), f/8 light. */
    fun radiusFor(fNumber: Float): Float = (0.028f * (1.8f / fNumber)).coerceIn(0.005f, 0.05f)
}

/**
 * HDR: merges a normal, a dark and a bright exposure (exposure fusion).
 * Each pixel takes most from the frame where it is well exposed; weights are smoothed
 * so there are no seams. Frames are aligned first (hand-held).
 */
object HdrFusion {

    fun fuse(frames: List<Bitmap>): Bitmap {
        val base = frames[0]
        val w = base.width
        val h = base.height
        val n = w * h
        val aligned = frames.map { f -> if (f === base) f else align(base, f) }
        val px = aligned.map { b -> IntArray(n).also { b.getPixels(it, 0, w, 0, 0, w, h) } }

        // weight maps at low resolution (smooth), then used per pixel
        val sw = max(8, w / 8)
        val sh = max(8, h / 8)
        val weights = aligned.map { b ->
            val s = Bitmap.createScaledBitmap(b, sw, sh, true)
            val sp = IntArray(sw * sh)
            s.getPixels(sp, 0, sw, 0, 0, sw, sh)
            val wt = FloatArray(sw * sh) {
                val c = sp[it]
                val l = (Color.red(c) * 0.299f + Color.green(c) * 0.587f + Color.blue(c) * 0.114f) / 255f
                val e = (l - 0.5f) / 0.22f
                (kotlin.math.exp(-0.5f * e * e) + 1e-3f)
            }
            LensBlur.boxBlur(wt, sw, sh, 3)
            wt
        }
        val out = IntArray(n)
        for (y in 0 until h) {
            val sy = (y * sh / h).coerceIn(0, sh - 1)
            for (x in 0 until w) {
                val sx = (x * sw / w).coerceIn(0, sw - 1)
                val si = sy * sw + sx
                var r = 0f; var g = 0f; var b = 0f; var ws = 0f
                val i = y * w + x
                for (k in px.indices) {
                    val c = px[k][i]
                    // fine detail: prefer the frame where this pixel itself is well exposed
                    val l = (Color.red(c) * 0.299f + Color.green(c) * 0.587f + Color.blue(c) * 0.114f) / 255f
                    val e = (l - 0.5f) / 0.3f
                    val wk = weights[k][si] * (0.35f + 0.65f * kotlin.math.exp(-0.5f * e * e))
                    r += Color.red(c) * wk; g += Color.green(c) * wk; b += Color.blue(c) * wk; ws += wk
                }
                val inv = 1f / max(1e-6f, ws)
                out[i] = Color.rgb((r * inv).toInt().coerceIn(0, 255), (g * inv).toInt().coerceIn(0, 255), (b * inv).toInt().coerceIn(0, 255))
            }
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    /** Shifts [f] to line up with [ref] (exposure-normalised comparison at 1/8 scale). */
    private fun align(ref: Bitmap, f: Bitmap): Bitmap {
        if (f.width != ref.width || f.height != ref.height) return Bitmap.createScaledBitmap(f, ref.width, ref.height, true)
        val sw = max(16, ref.width / 8)
        val sh = max(16, ref.height / 8)
        fun gray(b: Bitmap): FloatArray {
            val s = Bitmap.createScaledBitmap(b, sw, sh, true)
            val p = IntArray(sw * sh)
            s.getPixels(p, 0, sw, 0, 0, sw, sh)
            val g = FloatArray(sw * sh) { Color.red(p[it]) * 0.3f + Color.green(p[it]) * 0.59f + Color.blue(p[it]) * 0.11f }
            val mean = max(1f, g.average().toFloat())
            for (i in g.indices) g[i] /= mean
            return g
        }
        val a = gray(ref)
        val b = gray(f)
        val range = 8
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
                    sum += abs(a[y * sw + x] - b[(y + dy) * sw + (x + dx)]); cnt++; x += 2
                }
                y += 2
            }
            if (cnt > 0 && sum / cnt < best) { best = sum / cnt; bx = dx; by = dy }
        }
        if (bx == 0 && by == 0) return f
        val out = Bitmap.createBitmap(f.width, f.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(f, -bx * 8f, -by * 8f, null)
        return out
    }
}
