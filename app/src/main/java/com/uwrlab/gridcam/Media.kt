package com.uwrlab.gridcam

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.RectF
import android.net.Uri
import android.provider.MediaStore
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.max
import kotlin.math.sqrt

/** One photo or video saved by Grid Cam. */
class MediaItem(val uri: Uri, val isVideo: Boolean, val dateAdded: Long)

object MediaHelper {
    const val PHOTO_DIR = "Pictures/GridCam"
    const val VIDEO_DIR = "Movies/GridCam"

    fun newName(prefix: String): String =
        prefix + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())

    /** Short text stored with each photo, read back later for the edit instructions. */
    fun makeInfo(tilt: Float, grid: String, mode: String): String =
        String.format(Locale.US, "GridCam;tilt=%.2f;grid=%s;mode=%s", tilt, grid, mode)

    fun readInfo(cr: ContentResolver, uri: Uri): Map<String, String> {
        val out = HashMap<String, String>()
        try {
            cr.query(uri, arrayOf(MediaStore.Images.ImageColumns.DESCRIPTION), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val text = c.getString(0) ?: ""
                    for (part in text.split(';')) {
                        val kv = part.split('=', limit = 2)
                        if (kv.size == 2) out[kv[0].trim()] = kv[1].trim()
                    }
                }
            }
        } catch (e: Exception) {
            // no info available
        }
        return out
    }

    /** All Grid Cam photos and videos, newest first. */
    fun listAll(cr: ContentResolver): List<MediaItem> {
        val items = ArrayList<MediaItem>()
        fun collect(collection: Uri, dir: String, isVideo: Boolean) {
            try {
                cr.query(
                    collection,
                    arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATE_ADDED),
                    "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
                    arrayOf("$dir%"),
                    "${MediaStore.MediaColumns.DATE_ADDED} DESC"
                )?.use { c ->
                    while (c.moveToNext()) {
                        val id = c.getLong(0)
                        items.add(MediaItem(Uri.withAppendedPath(collection, id.toString()), isVideo, c.getLong(1)))
                    }
                }
            } catch (e: Exception) {
                // permission missing or nothing saved yet
            }
        }
        collect(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, PHOTO_DIR, false)
        collect(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, VIDEO_DIR, true)
        items.sortByDescending { it.dateAdded }
        return items
    }

    fun decode(cr: ContentResolver, uri: Uri, maxSide: Int): Bitmap {
        val source = ImageDecoder.createSource(cr, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val w = info.size.width
            val h = info.size.height
            val largest = max(w, h)
            if (largest > maxSide) {
                val s = maxSide.toFloat() / largest
                decoder.setTargetSize(max(1, (w * s).toInt()), max(1, (h * s).toInt()))
            }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = true
        }
    }

    fun saveJpeg(cr: ContentResolver, bmp: Bitmap, name: String, info: String): Uri? {
        val cv = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, PHOTO_DIR)
            put(MediaStore.Images.ImageColumns.DESCRIPTION, info)
        }
        val uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv) ?: return null
        cr.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        return uri
    }
}

/**
 * Portrait mode: keeps the subject sharp and blurs the background with lens-style bokeh.
 * Uses ML Kit subject segmentation; falls back to a soft oval around the selected subject.
 */
object PortraitMaker {

    /**
     * Must be called on a background thread. [subject] is the selected box (0..1) or null.
     * [topDown] = phone was pointing at the ground (flat scene: little blur, like Pixel).
     */
    fun make(context: Context, src: Bitmap, subject: RectF?, fNumber: Float = 1.8f, topDown: Boolean = false): Bitmap {
        val w = src.width
        val h = src.height
        val mask = chooseMask(segmentMask(src), w, h, subject)
        val dm = depthMap(src, mask, subject, topDown)

        val radius = LensBlur.radiusFor(fNumber) * (if (topDown) 0.6f else 1f)
        val full = try { LensBlur.apply(src, radius) } catch (e: OutOfMemoryError) { blur(src) }
        val light = try { LensBlur.apply(src, radius * 0.4f) } catch (e: OutOfMemoryError) { full }

        val sp = IntArray(w * h)
        val fp = IntArray(w * h)
        val lp = IntArray(w * h)
        src.getPixels(sp, 0, w, 0, 0, w, h)
        full.getPixels(fp, 0, w, 0, 0, w, h)
        light.getPixels(lp, 0, w, 0, 0, w, h)
        for (y in 0 until h) {
            val sy = (y * dm.h / h).coerceIn(0, dm.h - 1)
            for (x in 0 until w) {
                val i = y * w + x
                val sx = (x * dm.w / w).coerceIn(0, dm.w - 1)
                val d = dm.v[sy * dm.w + sx]                 // 0 = same distance as subject, 1 = far
                val keep = mask[i].coerceIn(0f, 1f)
                val s0 = sp[i]
                val l = lp[i]
                val f = fp[i]
                // background colour: sharp -> light blur -> full blur as distance grows
                val (br, bgc, bb) = if (d < 0.5f) {
                    val t = d * 2f
                    Triple(
                        Color.red(s0) + (Color.red(l) - Color.red(s0)) * t,
                        Color.green(s0) + (Color.green(l) - Color.green(s0)) * t,
                        Color.blue(s0) + (Color.blue(l) - Color.blue(s0)) * t
                    )
                } else {
                    val t = (d - 0.5f) * 2f
                    Triple(
                        Color.red(l) + (Color.red(f) - Color.red(l)) * t,
                        Color.green(l) + (Color.green(f) - Color.green(l)) * t,
                        Color.blue(l) + (Color.blue(f) - Color.blue(l)) * t
                    )
                }
                sp[i] = Color.rgb(
                    (Color.red(s0) * keep + br * (1 - keep)).toInt().coerceIn(0, 255),
                    (Color.green(s0) * keep + bgc * (1 - keep)).toInt().coerceIn(0, 255),
                    (Color.blue(s0) * keep + bb * (1 - keep)).toInt().coerceIn(0, 255)
                )
            }
        }
        return Bitmap.createBitmap(sp, w, h, Bitmap.Config.ARGB_8888)
    }

    class DepthMap(val v: FloatArray, val w: Int, val h: Int)

    /**
     * Distance guess without a depth sensor:
     *  - parts as crisp as the subject are at the same distance (stay sharp, like the pole next to
     *    Pixel's lamp); parts the lens already rendered soft are farther away (blur more);
     *  - when the phone is held level, things above the subject are usually farther away;
     *  - a top-down shot is a flat scene, so blur is reduced.
     */
    private fun depthMap(src: Bitmap, mask: FloatArray, subject: RectF?, topDown: Boolean): DepthMap {
        val w = src.width
        val h = src.height
        val sw = max(8, w / 4)
        val sh = max(8, h / 4)
        val small = Bitmap.createScaledBitmap(src, sw, sh, true)
        val px = IntArray(sw * sh)
        small.getPixels(px, 0, sw, 0, 0, sw, sh)
        val g = FloatArray(sw * sh) {
            (Color.red(px[it]) * 0.3f + Color.green(px[it]) * 0.59f + Color.blue(px[it]) * 0.11f) / 255f
        }
        // local detail (Laplacian energy)
        val e = FloatArray(sw * sh)
        for (y in 1 until sh - 1) for (x in 1 until sw - 1) {
            val i = y * sw + x
            e[i] = kotlin.math.abs(4 * g[i] - g[i - 1] - g[i + 1] - g[i - sw] - g[i + sw])
        }
        LensBlur.boxBlur(e, sw, sh, 4)

        // how crisp is the subject?
        val subj = ArrayList<Float>()
        for (y in 0 until sh) for (x in 0 until sw) {
            val m = mask[(y * h / sh).coerceIn(0, h - 1) * w + (x * w / sw).coerceIn(0, w - 1)]
            if (m > 0.5f) subj.add(e[y * sw + x])
        }
        val refRaw = if (subj.size > 50) {
            subj.sort(); subj[(subj.size * 0.75f).toInt().coerceIn(0, subj.size - 1)]
        } else {
            val all = e.copyOf(); all.sort(); all[(all.size * 0.9f).toInt().coerceIn(0, all.size - 1)]
        }
        val ref = refRaw.coerceAtLeast(0.004f)

        val top = subject?.top ?: maskTop(mask, w, h)
        val d = FloatArray(sw * sh)
        for (y in 0 until sh) {
            val ny = y.toFloat() / sh
            val prior = if (!topDown) ((top - ny) / 0.25f).coerceIn(0f, 1f) * 0.6f else 0f
            for (x in 0 until sw) {
                val i = y * sw + x
                val ratio = e[i] / ref
                val soft = ((0.55f - ratio) / 0.4f).coerceIn(0f, 1f)
                var v = max(soft, prior)
                if (topDown) v *= 0.45f
                d[i] = v
            }
        }
        LensBlur.boxBlur(d, sw, sh, 6)
        LensBlur.boxBlur(d, sw, sh, 6)
        return DepthMap(d, sw, sh)
    }

    /** Highest row (0..1) where the mask is mostly subject. */
    private fun maskTop(mask: FloatArray, w: Int, h: Int): Float {
        for (y in 0 until h step 4) {
            var x = 0
            while (x < w) {
                if (mask[y * w + x] > 0.5f) return y.toFloat() / h
                x += 4
            }
        }
        return 0.3f
    }

    /**
     * Uses the AI cut-out when it actually found the selected subject; otherwise (e.g. bushes,
     * buildings) falls back to a soft shape around the box the user tapped.
     */
    private fun chooseMask(seg: FloatArray?, w: Int, h: Int, subject: RectF?): FloatArray {
        if (seg == null) return ovalMask(w, h, subject)
        if (subject == null) {
            val mx = seg.maxOrNull() ?: 0f
            return if (mx < 0.5f) ovalMask(w, h, null) else seg
        }
        // how much of the tapped box did the AI mark as subject?
        val l = (subject.left * w).toInt().coerceIn(0, w - 1)
        val r = (subject.right * w).toInt().coerceIn(l + 1, w)
        val t = (subject.top * h).toInt().coerceIn(0, h - 1)
        val b = (subject.bottom * h).toInt().coerceIn(t + 1, h)
        var sum = 0.0
        var cnt = 0
        var y = t
        while (y < b) {
            var x = l
            while (x < r) { sum += seg[y * w + x]; cnt++; x += 4 }
            y += 4
        }
        val inside = if (cnt > 0) (sum / cnt).toFloat() else 0f
        if (inside < 0.25f) return ovalMask(w, h, subject)
        // keep only the selected subject (other people/objects elsewhere get blurred)
        val padX = subject.width() * 0.25f
        val padY = subject.height() * 0.25f
        val out = FloatArray(w * h)
        for (yy in 0 until h) {
            val ny = yy.toFloat() / h
            val dy = when {
                ny < subject.top - padY -> (subject.top - padY - ny) / padY
                ny > subject.bottom + padY -> (ny - subject.bottom - padY) / padY
                else -> 0f
            }
            for (xx in 0 until w) {
                val nx = xx.toFloat() / w
                val dx = when {
                    nx < subject.left - padX -> (subject.left - padX - nx) / padX
                    nx > subject.right + padX -> (nx - subject.right - padX) / padX
                    else -> 0f
                }
                val keep = (1f - kotlin.math.max(dx, dy)).coerceIn(0f, 1f)
                out[yy * w + xx] = seg[yy * w + xx] * keep
            }
        }
        return out
    }

    /** Lowest row (0..1) where the mask is mostly subject. */
    private fun maskBottom(mask: FloatArray, w: Int, h: Int): Float {
        for (y in h - 1 downTo 0 step 4) {
            var x = 0
            while (x < w) {
                if (mask[y * w + x] > 0.5f) return y.toFloat() / h
                x += 4
            }
        }
        return 0.75f
    }

    private fun segmentMask(src: Bitmap): FloatArray? {
        return try {
            val options = SubjectSegmenterOptions.Builder()
                .enableForegroundConfidenceMask()
                .build()
            val segmenter = SubjectSegmentation.getClient(options)
            val result = Tasks.await(segmenter.process(InputImage.fromBitmap(src, 0)))
            segmenter.close()
            val buf = result.foregroundConfidenceMask ?: return null
            buf.rewind()
            val n = src.width * src.height
            if (buf.remaining() < n) return null
            val out = FloatArray(n)
            buf.get(out)
            out
        } catch (e: Exception) {
            null
        }
    }

    private fun ovalMask(w: Int, h: Int, subject: RectF?): FloatArray {
        val box = subject ?: RectF(0.25f, 0.2f, 0.75f, 0.8f)
        val cx = box.centerX() * w
        val cy = box.centerY() * h
        val rx = max(1f, box.width() * w * 0.55f)
        val ry = max(1f, box.height() * h * 0.58f)
        val out = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val dx = (x - cx) / rx
            val dy = (y - cy) / ry
            val d = sqrt(dx * dx + dy * dy)
            out[y * w + x] = ((1.25f - d) / 0.35f).coerceIn(0f, 1f) // 1 inside, soft edge
        }
        return out
    }

    /** Fast background blur by shrinking and enlarging the image in steps. */
    private fun blur(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        var b = Bitmap.createScaledBitmap(src, max(1, w / 4), max(1, h / 4), true)
        b = Bitmap.createScaledBitmap(b, max(1, w / 16), max(1, h / 16), true)
        b = Bitmap.createScaledBitmap(b, max(1, w / 32), max(1, h / 32), true)
        b = Bitmap.createScaledBitmap(b, max(1, w / 8), max(1, h / 8), true)
        b = Bitmap.createScaledBitmap(b, max(1, w / 2), max(1, h / 2), true)
        return Bitmap.createScaledBitmap(b, w, h, true)
    }
}
