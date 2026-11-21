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

    fun analyze(src: Bitmap, tiltDeg: Float = 0f, grid: String = "", mode: String = ""): PhotoReport {
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
        val tune = ArrayList<String>()   // Snapseed: Tools > Tune image
        val wb = ArrayList<String>()     // Snapseed: Tools > White balance

        // Brightness
        val ev = (ln(0.46f / max(mean, 0.02f)) / ln(2f)).coerceIn(-1.5f, 1.5f)
        if (abs(ev) >= 0.15f) {
            auto[0] = ev / 1.5f
            val b = (ev * 35f).coerceIn(-60f, 60f)
            tune.add("Brightness ${sign0(b)}: photo is ${if (ev > 0) "too dark" else "too bright"} (average ${(mean * 100).roundToInt()}%).")
        }
        // Highlights
        if (hiPct > 0.02f) {
            auto[2] = -min(0.8f, hiPct * 10f + 0.2f)
            tune.add("Highlights ${sign0(auto[2] * 100)}: ${pct(hiPct)} is burnt white (sky, lights). Brings detail back.")
        }
        // Shadows
        if (loPct > 0.03f) {
            auto[3] = min(0.8f, loPct * 8f + 0.2f)
            tune.add("Shadows ${sign0(auto[3] * 100)}: ${pct(loPct)} is nearly black. Reveals hidden detail.")
        }
        // Contrast / Ambiance
        if (std < 0.16f) {
            auto[1] = 0.25f
            tune.add("Contrast +25 and Ambiance +15: the photo looks flat or hazy.")
        } else if (std > 0.30f) {
            auto[1] = -0.15f
            tune.add("Contrast -15: the photo is very harsh.")
        }
        // Saturation
        if (sat < 0.18f) {
            auto[5] = 0.25f
            tune.add("Saturation +20 (or Ambiance +20): colours are dull.")
        } else if (sat > 0.55f) {
            auto[5] = -0.15f
            tune.add("Saturation -15: colours are too strong.")
        }
        // White balance
        if (rb > 0.06f) {
            auto[4] = -min(0.6f, (rb - 0.03f) * 4f)
            wb.add("Temperature ${sign0(auto[4] * 100)}: the photo looks too yellow/orange.")
        } else if (rb < -0.03f) {
            auto[4] = min(0.6f, -rb * 4f)
            wb.add("Temperature ${sign0(auto[4] * 100)}: the photo looks too blue/cold.")
        }

        val sb = StringBuilder()
        sb.append("Photo check\n")
        sb.append("Brightness ${(mean * 100).roundToInt()}%  ·  Contrast ${(std * 100).roundToInt()}  ·  ")
        sb.append("Colour ${(sat * 100).roundToInt()}%  ·  ${if (rb > 0.06f) "Warm" else if (rb < -0.03f) "Cool" else "Neutral"}\n")
        if (grid.isNotEmpty()) sb.append("Taken with the $grid grid: keep the subject on its point when you crop.\n")
        sb.append("\nIn Snapseed, tap Tools, pick the tool, swipe up/down to choose a setting and left/right to change it, then tap ✓.\n")

        sb.append("\n1. Tools > Tune image\n")
        if (tune.isEmpty()) sb.append("• Light and colour look balanced. Skip this step.\n")
        else tune.forEach { sb.append("• ").append(it).append('\n') }

        sb.append("\n2. Tools > White balance\n")
        if (wb.isEmpty()) sb.append("• Colours look natural. Skip this step.\n")
        else wb.forEach { sb.append("• ").append(it).append('\n') }

        sb.append("\n3. Tools > Rotate / Crop\n")
        if (abs(tiltDeg) >= 0.5f) {
            auto[6] = (tiltDeg / 15f).coerceIn(-1f, 1f)
            sb.append("• Rotate ${sign1(tiltDeg)}°: the phone was ${String.format("%.1f", abs(tiltDeg))}° off level. Snapseed's Rotate tool usually levels it automatically.\n")
        } else {
            sb.append("• Horizon is level. No rotation needed.\n")
        }
        sb.append("• Crop: trim empty sky or ground and distractions at the edges.\n")

        sb.append("\n4. Tools > Details\n")
        sb.append("• Structure +10 to +20 for buildings and landscapes (keep 0 for faces). Sharpening +10.\n")

        sb.append("\n5. Finishing touches\n")
        sb.append("• Tools > Healing: brush over wires, litter or spots.\n")
        sb.append("• Tools > Vignette: Outer brightness -20 to draw the eye to the subject.\n")
        if (mode.startsWith("Portrait")) {
            sb.append("• This is a portrait shot: use Tools > Portrait (Face spotlight +20, Skin smoothing +10) for faces.\n")
        } else {
            sb.append("• For a portrait look, Tools > Lens Blur: place the circle on the subject, Blur strength 40.\n")
        }
        sb.append("\nTap Open in Snapseed to edit there, or Auto fix to apply the main changes here.")
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
