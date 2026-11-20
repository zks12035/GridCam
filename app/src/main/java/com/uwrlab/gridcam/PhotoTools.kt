package com.uwrlab.gridcam

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Slider order used everywhere. Each value is in -1..1. */
val EDIT_NAMES = arrayOf("Exposure", "Contrast", "Highlights", "Shadows", "Warmth", "Saturation", "Straighten")

class PhotoReport(val tips: String, val auto: FloatArray)

object PhotoAnalyzer {

    fun analyze(src: Bitmap, tiltDeg: Float = 0f): PhotoReport {
        val sw = 200
        val sh = max(1, (sw.toFloat() * src.height / src.width).toInt())
        val small = Bitmap.createScaledBitmap(src, sw, sh, true)
        val px = IntArray(sw * sh)
        small.getPixels(px, 0, sw, 0, 0, sw, sh)

        var sum = 0.0
        var sumSq = 0.0
        var hi = 0
        var lo = 0
        var satSum = 0.0
        var rbSum = 0.0
        for (c in px) {
            val r = ((c shr 16) and 0xff) / 255f
            val g = ((c shr 8) and 0xff) / 255f
            val b = (c and 0xff) / 255f
            val lum = 0.299f * r + 0.587f * g + 0.114f * b
            sum += lum
            sumSq += lum * lum
            if (lum > 0.96f) hi++
            if (lum < 0.04f) lo++
            val mx = max(r, max(g, b))
            val mn = min(r, min(g, b))
            if (mx > 0.02f) satSum += (mx - mn) / mx
            rbSum += (r - b)
        }
        val n = px.size.toDouble()
        val mean = (sum / n).toFloat()
        val std = sqrt(max(0.0, sumSq / n - (sum / n) * (sum / n))).toFloat()
        val hiPct = (hi / n).toFloat()
        val loPct = (lo / n).toFloat()
        val sat = (satSum / n).toFloat()
        val rb = (rbSum / n).toFloat()

        val auto = FloatArray(EDIT_NAMES.size)
        val light = ArrayList<String>()
        val color = ArrayList<String>()

        // Exposure
        val ev = (ln(0.46f / max(mean, 0.02f)) / ln(2f)).coerceIn(-1.5f, 1.5f)
        if (abs(ev) >= 0.15f) {
            auto[0] = ev / 1.5f
            light.add(
                "Exposure ${sign1(ev)}: photo is ${if (ev > 0) "too dark" else "too bright"} " +
                        "(average brightness ${(mean * 100).roundToInt()}%)."
            )
        }
        // Highlights
        if (hiPct > 0.02f) {
            auto[2] = -min(0.8f, hiPct * 10f + 0.2f)
            light.add("Highlights ${sign0(auto[2] * 100)}: ${pct(hiPct)} of the photo is burnt white (sky, lights). Pull it down to bring detail back.")
        }
        // Shadows
        if (loPct > 0.03f) {
            auto[3] = min(0.8f, loPct * 8f + 0.2f)
            light.add("Shadows ${sign0(auto[3] * 100)}: ${pct(loPct)} is nearly black. Lift shadows to reveal hidden detail.")
        }
        // Contrast
        if (std < 0.16f) {
            auto[1] = 0.25f
            light.add("Contrast +25: the photo looks flat/hazy.")
            if (hiPct < 0.002f && loPct < 0.002f) light.add("Whites +15 and Blacks -15: gives the image punch without clipping.")
        } else if (std > 0.30f) {
            auto[1] = -0.15f
            light.add("Contrast -15: the photo is very harsh; soften it a little.")
        }
        // White balance
        if (rb > 0.06f) {
            auto[4] = -min(0.6f, (rb - 0.03f) * 4f)
            color.add("Temp ${sign0(auto[4] * 100)} (cooler): the photo looks too yellow/orange.")
        } else if (rb < -0.03f) {
            auto[4] = min(0.6f, -rb * 4f)
            color.add("Temp ${sign0(auto[4] * 100)} (warmer): the photo looks too blue/cold.")
        }
        // Saturation
        if (sat < 0.18f) {
            auto[5] = 0.25f
            color.add("Vibrance +25: colours are dull. Vibrance boosts weak colours without overdoing skin.")
        } else if (sat > 0.55f) {
            auto[5] = -0.15f
            color.add("Saturation -15: colours are too strong.")
        }

        val sb = StringBuilder()
        sb.append("Photo check\n")
        sb.append("Brightness ${(mean * 100).roundToInt()}%  ·  Contrast ${(std * 100).roundToInt()}  ·  ")
        sb.append("Colour ${(sat * 100).roundToInt()}%  ·  ${if (rb > 0.06f) "Warm" else if (rb < -0.03f) "Cool" else "Neutral"}\n\n")
        sb.append("Lightroom: Edit > Light\n")
        if (light.isEmpty()) sb.append("• Light looks well balanced. No change needed.\n")
        else light.forEach { sb.append("• ").append(it).append('\n') }
        sb.append("\nLightroom: Edit > Color\n")
        if (color.isEmpty()) sb.append("• Colour and white balance look natural.\n")
        else color.forEach { sb.append("• ").append(it).append('\n') }
        sb.append("\nLightroom: Effects & Crop\n")
        if (abs(tiltDeg) >= 0.5f) {
            auto[6] = (tiltDeg / 15f).coerceIn(-1f, 1f)
            sb.append("• Crop > Straighten ${sign1(tiltDeg)}°: the phone was ${String.format("%.1f", abs(tiltDeg))}° off level when you took it (Auto fix does this).\n")
        } else {
            sb.append("• Horizon is level. No straightening needed.\n")
        }
        sb.append("• Clarity +10 to +15 for landscapes and buildings; Texture -10 to soften skin in portraits.\n")
        sb.append("• Vignette -10 to pull the eye toward your subject.\n")
        sb.append("• Crop: keep the subject on the grid point the camera showed you; straighten the horizon.\n")
        sb.append("\nTap Auto fix to apply these here, or Open in Lightroom to edit there.")
        return PhotoReport(sb.toString(), auto)
    }

    private fun sign1(v: Float) = (if (v >= 0) "+" else "") + String.format("%.1f", v)
    private fun sign0(v: Float) = (if (v >= 0) "+" else "") + v.roundToInt()
    private fun pct(v: Float) = "${(v * 100).roundToInt()}%"
}

object PhotoEditor {

    /** Returns a new bitmap with the edits applied. [v] is in EDIT_NAMES order, each -1..1. */
    fun apply(input: Bitmap, v: FloatArray): Bitmap {
        val angle = if (v.size > 6) v[6] * 15f else 0f
        val src = if (abs(angle) > 0.05f) straighten(input, angle) else input
        val gain = 2.0.pow((v[0] * 1.5f).toDouble()).toFloat()
        val contrast = v[1]
        val highlights = v[2]
        val shadows = v[3]
        val warm = v[4]
        val sat = v[5]

        val lut = FloatArray(256)
        for (i in 0..255) {
            var x = i / 255f * gain
            val wSh = exp(-((x - 0.25f) / 0.18f).let { it * it })
            val wHi = exp(-((x - 0.80f) / 0.20f).let { it * it })
            x += shadows * 0.22f * wSh + highlights * 0.25f * wHi
            x = (x - 0.5f) * (1f + contrast * 0.8f) + 0.5f
            lut[i] = x.coerceIn(0f, 1f) * 255f
        }
        val rMul = 1f + warm * 0.12f
        val bMul = 1f - warm * 0.12f
        val satMul = 1f + sat

        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            val c = px[i]
            var r = lut[(c shr 16) and 0xff] * rMul
            var g = lut[(c shr 8) and 0xff]
            var b = lut[c and 0xff] * bMul
            val lum = 0.299f * r + 0.587f * g + 0.114f * b
            r = lum + (r - lum) * satMul
            g = lum + (g - lum) * satMul
            b = lum + (b - lum) * satMul
            val ri = r.toInt().coerceIn(0, 255)
            val gi = g.toInt().coerceIn(0, 255)
            val bi = b.toInt().coerceIn(0, 255)
            px[i] = (0xff shl 24) or (ri shl 16) or (gi shl 8) or bi
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    /** Rotates by [deg] and zooms just enough to fill the frame (no empty corners). */
    fun straighten(src: Bitmap, deg: Float): Bitmap {
        val w = src.width.toFloat()
        val h = src.height.toFloat()
        val r = Math.toRadians(abs(deg).toDouble())
        val cs = cos(r).toFloat()
        val sn = sin(r).toFloat()
        val s = max((w * cs + h * sn) / w, (w * sn + h * cs) / h)
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.translate(w / 2f, h / 2f)
        canvas.rotate(deg)
        canvas.scale(s, s)
        canvas.drawBitmap(src, -w / 2f, -h / 2f, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        return out
    }
}
