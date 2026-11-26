package com.uwrlab.gridcam

import android.graphics.RectF
import androidx.camera.core.ImageProxy
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** What the camera sees around the subject. All positions are normalized 0..1 (camera frame). */
class SceneInfo(
    /** 0..1, 1 = left and right halves are mirror images. */
    val symmetry: Float,
    /** Share of edge strength that runs diagonally (≈0.4 in an average scene). */
    val diagonal: Float,
    /** y of a long horizontal line (horizon, table edge), or NaN if none. */
    val horizonY: Float,
    /** > 0 when the right side is emptier than the left, < 0 when the left is emptier. */
    val openSide: Float
) {
    /** 0..1 share of the frame that is plain/empty (sky, wall, fog) – for negative space. */
    var empty: Float = 0f
    /** Layering: share of detailed pixels in the top (background), middle and bottom (foreground) thirds. */
    var layers: FloatArray = FloatArray(3)
}

/** Small upright grayscale copy of the camera frame plus simple scene measurements. */
object SceneAnalyzer {
    const val GW = 90
    const val GH = 120

    /**
     * Colour content of the frame: 36-bin hue histogram (10° per bin), weighted by how
     * saturated and bright each sampled pixel is. Grey/dark pixels don't count.
     */
    fun hues(proxy: ImageProxy): FloatArray {
        val hist = FloatArray(36)
        if (proxy.planes.size < 3) return hist
        val yP = proxy.planes[0]; val uP = proxy.planes[1]; val vP = proxy.planes[2]
        val yb = yP.buffer; val ub = uP.buffer; val vb = vP.buffer
        val w = proxy.width; val h = proxy.height
        val hsv = FloatArray(3)
        for (j in 0 until 30) for (i in 0 until 40) {
            val x = ((i + 0.5f) / 40 * w).toInt().coerceIn(0, w - 1)
            val y = ((j + 0.5f) / 30 * h).toInt().coerceIn(0, h - 1)
            val yi = y * yP.rowStride + x * yP.pixelStride
            val ci = (y / 2) * uP.rowStride + (x / 2) * uP.pixelStride
            val vi = (y / 2) * vP.rowStride + (x / 2) * vP.pixelStride
            if (yi >= yb.limit() || ci >= ub.limit() || vi >= vb.limit()) continue
            val yy = (yb.get(yi).toInt() and 0xff).toFloat()
            val u = (ub.get(ci).toInt() and 0xff) - 128f
            val v = (vb.get(vi).toInt() and 0xff) - 128f
            val r = (yy + 1.402f * v).toInt().coerceIn(0, 255)
            val g = (yy - 0.344f * u - 0.714f * v).toInt().coerceIn(0, 255)
            val b = (yy + 1.772f * u).toInt().coerceIn(0, 255)
            android.graphics.Color.RGBToHSV(r, g, b, hsv)
            if (hsv[1] < 0.22f || hsv[2] < 0.18f) continue
            hist[((hsv[0] / 10f).toInt()).coerceIn(0, 35)] += hsv[1] * hsv[2]
        }
        return hist
    }

    /** Reads the Y (brightness) plane into a GW x GH upright grayscale array (values 0..1). */
    fun gray(proxy: ImageProxy): FloatArray = grayAt(proxy, GW, GH)

    /** Upright grayscale copy of the camera frame at [gw] x [gh] (values 0..1). */
    fun grayAt(proxy: ImageProxy, gw: Int, gh: Int): FloatArray {
        val GW = gw
        val GH = gh
        val plane = proxy.planes[0]
        val buf = plane.buffer
        val rs = plane.rowStride
        val ps = plane.pixelStride
        val sw = proxy.width
        val sh = proxy.height
        val rot = proxy.imageInfo.rotationDegrees
        val out = FloatArray(GW * GH)
        val limit = buf.limit()
        for (j in 0 until GH) {
            val v = (j + 0.5f) / GH
            for (i in 0 until GW) {
                val u = (i + 0.5f) / GW
                val sxF: Float
                val syF: Float
                when (rot) {
                    90 -> { sxF = v * sw; syF = (1f - u) * sh }
                    180 -> { sxF = (1f - u) * sw; syF = (1f - v) * sh }
                    270 -> { sxF = (1f - v) * sw; syF = u * sh }
                    else -> { sxF = u * sw; syF = v * sh }
                }
                val sx = sxF.toInt().coerceIn(0, sw - 1)
                val sy = syF.toInt().coerceIn(0, sh - 1)
                val idx = sy * rs + sx * ps
                out[j * GW + i] = if (idx < limit) (buf.get(idx).toInt() and 0xff) / 255f else 0f
            }
        }
        return out
    }

    /**
     * Finds the strongest real straight lines in the scene (horizon, hill slope, road, roof edge,
     * tree trunk) with a Hough transform. Returns up to 6 segments as [x1, y1, x2, y2] in 0..1.
     */
    fun findLines(g: FloatArray): List<FloatArray> {
        val w = GW
        val h = GH
        val gx = FloatArray(w * h)
        val gy = FloatArray(w * h)
        val mag = FloatArray(w * h)
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            val a = g[i - w - 1]; val b = g[i - w]; val c = g[i - w + 1]
            val dd = g[i - 1]; val f = g[i + 1]
            val gg = g[i + w - 1]; val hh = g[i + w]; val ii = g[i + w + 1]
            gx[i] = (c + 2 * f + ii) - (a + 2 * dd + gg)
            gy[i] = (gg + 2 * hh + ii) - (a + 2 * b + c)
            mag[i] = kotlin.math.sqrt(gx[i] * gx[i] + gy[i] * gy[i])
        }
        val sorted = mag.copyOf(); sorted.sort()
        val thr = max(0.25f, sorted[(sorted.size * 0.88f).toInt()])
        val ex = ArrayList<Int>(); val ey = ArrayList<Int>(); val et = ArrayList<Float>()
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            if (mag[i] > thr) {
                ex.add(x); ey.add(y)
                var t = kotlin.math.atan2(gy[i], gx[i])
                if (t < 0) t += Math.PI.toFloat()
                if (t >= Math.PI.toFloat()) t -= Math.PI.toFloat()
                et.add(t)
            }
        }
        val nt = 60
        val dMax = kotlin.math.hypot(w.toDouble(), h.toDouble()).toInt() + 1
        val nr = 2 * dMax + 1
        val acc = IntArray(nt * nr)
        val cosT = FloatArray(nt) { kotlin.math.cos(it * Math.PI / nt).toFloat() }
        val sinT = FloatArray(nt) { kotlin.math.sin(it * Math.PI / nt).toFloat() }
        for (k in ex.indices) {
            val tb = ((et[k] / Math.PI * nt).toInt()) % nt
            for (dt in -2..2) {
                val t = (tb + dt + nt) % nt
                val r = Math.round(ex[k] * cosT[t] + ey[k] * sinT[t]) + dMax
                acc[t * nr + r]++
            }
        }
        val out = ArrayList<FloatArray>()
        repeat(10) {
            if (out.size >= 6) return@repeat
            var bi = 0
            for (i in acc.indices) if (acc[i] > acc[bi]) bi = i
            if (acc[bi] < 22) return out
            val t = bi / nr
            val r = bi % nr
            val rho = (r - dMax).toFloat()
            val ca = cosT[t]; val sa = sinT[t]
            val angle = (t * Math.PI / nt).toFloat()
            for (dt in -3..3) {
                val tt = (t + dt + nt) % nt
                for (rr in max(0, r - 5)..min(nr - 1, r + 5)) acc[tt * nr + rr] = 0
            }
            // pixels that support this line, measured along it
            val along = ArrayList<Float>()
            for (k in ex.indices) {
                val dist = abs(ex[k] * ca + ey[k] * sa - rho)
                var ad = abs(et[k] - angle)
                if (ad > Math.PI / 2) ad = (Math.PI - ad).toFloat()
                if (dist < 1.6f && ad < 0.22f) along.add(-ex[k] * sa + ey[k] * ca)
            }
            if (along.size < 15) return@repeat
            along.sort()
            // longest continuous run (gaps under 5 px)
            var bestA = 0f; var bestB = 0f; var bestN = 0
            var s = 0
            for (i in 1..along.size) {
                if (i == along.size || along[i] - along[i - 1] > 5f) {
                    if (along[i - 1] - along[s] > bestB - bestA) { bestA = along[s]; bestB = along[i - 1]; bestN = i - s }
                    s = i
                }
            }
            val len = bestB - bestA
            if (len < 28f || bestN < 0.45f * len) return@repeat
            out.add(floatArrayOf(
                (rho * ca - bestA * sa) / w, (rho * sa + bestA * ca) / h,
                (rho * ca - bestB * sa) / w, (rho * sa + bestB * ca) / h
            ))
        }
        return out
    }

    fun analyze(g: FloatArray, subject: RectF?): SceneInfo {
        val thr = 0.08f
        var hE = 0f
        var vE = 0f
        var dE = 0f
        val rowH = FloatArray(GH)
        val rowCover = IntArray(GH)
        var leftE = 0f
        var rightE = 0f
        var leftN = 0
        var rightN = 0
        val bandDetail = FloatArray(3)
        val bandN = IntArray(3)

        for (y in 1 until GH - 1) {
            val ny = y.toFloat() / GH
            for (x in 1 until GW - 1) {
                val nx = x.toFloat() / GW
                val inSubject = subject != null && subject.contains(nx, ny)
                val a = g[(y - 1) * GW + x - 1]; val b = g[(y - 1) * GW + x]; val c = g[(y - 1) * GW + x + 1]
                val d = g[y * GW + x - 1]; val f = g[y * GW + x + 1]
                val k = g[(y + 1) * GW + x - 1]; val m = g[(y + 1) * GW + x]; val n = g[(y + 1) * GW + x + 1]
                val gx = (c + 2 * f + n) - (a + 2 * d + k)
                val gy = (k + 2 * m + n) - (a + 2 * b + c)
                val ax = abs(gx)
                val ay = abs(gy)
                val mag = ax + ay
                val band = (y * 3 / GH).coerceIn(0, 2)
                bandN[band]++
                if (mag > 0.15f) bandDetail[band] += 1f
                if (!inSubject) {
                    if (nx < 0.5f) { leftE += mag; leftN++ } else { rightE += mag; rightN++ }
                }
                if (mag < thr || inSubject) continue
                when {
                    ay > 2f * ax -> { hE += mag; rowH[y] += mag; rowCover[y]++ } // horizontal line
                    ax > 2f * ay -> vE += mag                                  // vertical line
                    else -> dE += mag                                          // diagonal line
                }
            }
        }
        val total = hE + vE + dE
        val diagonal = if (total > 1f) dE / total else 0f

        // Horizon: a row band whose horizontal edges cover most of the width
        var horizonY = Float.NaN
        var bestScore = 0f
        val meanRow = if (GH > 0) rowH.sum() / GH else 0f
        for (y in 6 until GH - 6) {
            val e = rowH[y - 1] + rowH[y] + rowH[y + 1]
            val cover = max(rowCover[y - 1], max(rowCover[y], rowCover[y + 1])).toFloat() / GW
            if (cover > 0.55f && e > 3f * 3f * meanRow && e > bestScore) {
                bestScore = e
                horizonY = y.toFloat() / GH
            }
        }

        // Mirror symmetry (left vs right)
        var diff = 0f
        for (y in 0 until GH) for (x in 0 until GW / 2) {
            diff += abs(g[y * GW + x] - g[y * GW + (GW - 1 - x)])
        }
        diff /= (GH * (GW / 2)).toFloat()
        val symmetry = (1f - diff / 0.15f).coerceIn(0f, 1f)

        val dl = if (leftN > 0) leftE / leftN else 0f
        val dr = if (rightN > 0) rightE / rightN else 0f
        val openSide = (dl - dr) / max(0.02f, dl + dr)

        // Negative space: share of 6x6 blocks with almost no detail
        var plain = 0
        var blocks = 0
        var by = 0
        while (by + 6 <= GH) {
            var bx = 0
            while (bx + 6 <= GW) {
                var mn = 1f; var mx = 0f
                for (yy in by until by + 6) for (xx in bx until bx + 6) {
                    val v = g[yy * GW + xx]; if (v < mn) mn = v; if (v > mx) mx = v
                }
                if (mx - mn < 0.06f) plain++
                blocks++
                bx += 6
            }
            by += 6
        }
        return SceneInfo(symmetry, diagonal, horizonY, openSide).also {
            it.empty = if (blocks > 0) plain.toFloat() / blocks else 0f
            it.layers = FloatArray(3) { b -> if (bandN[b] > 0) bandDetail[b] / bandN[b] else 0f }
        }
    }
}

/**
 * Follows a patch of the image the user tapped (for objects the detector doesn't know).
 * Works on the GW x GH grayscale frames from [SceneAnalyzer.gray].
 */
class TemplateTracker {
    private val half = 7
    private val search = 9
    private var tpl: FloatArray? = null
    private var cx = 0f
    private var cy = 0f

    fun start(g: FloatArray, nx: Float, ny: Float) {
        cx = nx * SceneAnalyzer.GW
        cy = ny * SceneAnalyzer.GH
        tpl = patch(g, cx.toInt(), cy.toInt())
    }

    /** Returns the new normalized center, or null if the patch could not be found. */
    fun update(g: FloatArray): FloatArray? {
        val t = tpl ?: return null
        val x0 = cx.toInt()
        val y0 = cy.toInt()
        var best = Float.MAX_VALUE
        var bx = x0
        var by = y0
        for (dy in -search..search) for (dx in -search..search) {
            val sad = sad(g, t, x0 + dx, y0 + dy, best)
            if (sad < best) {
                best = sad
                bx = x0 + dx
                by = y0 + dy
            }
        }
        val n = (2 * half + 1) * (2 * half + 1)
        if (best / n > 0.10f) return null
        cx = bx.toFloat()
        cy = by.toFloat()
        val fresh = patch(g, bx, by)
        for (i in t.indices) t[i] = 0.85f * t[i] + 0.15f * fresh[i]
        return floatArrayOf(cx / SceneAnalyzer.GW, cy / SceneAnalyzer.GH)
    }

    private fun px(g: FloatArray, x: Int, y: Int): Float =
        g[min(SceneAnalyzer.GH - 1, max(0, y)) * SceneAnalyzer.GW + min(SceneAnalyzer.GW - 1, max(0, x))]

    private fun patch(g: FloatArray, x: Int, y: Int): FloatArray {
        val s = 2 * half + 1
        val out = FloatArray(s * s)
        var k = 0
        for (j in -half..half) for (i in -half..half) out[k++] = px(g, x + i, y + j)
        return out
    }

    private fun sad(g: FloatArray, t: FloatArray, x: Int, y: Int, stopAt: Float): Float {
        var sum = 0f
        var k = 0
        for (j in -half..half) {
            for (i in -half..half) sum += abs(px(g, x + i, y + j) - t[k++])
            if (sum >= stopAt) return sum
        }
        return sum
    }
}
