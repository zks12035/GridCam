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
)

/** Small upright grayscale copy of the camera frame plus simple scene measurements. */
object SceneAnalyzer {
    const val GW = 90
    const val GH = 120

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

        return SceneInfo(symmetry, diagonal, horizonY, openSide)
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
