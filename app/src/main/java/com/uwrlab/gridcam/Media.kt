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
import kotlin.math.min
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
        val mask = refineMask(chooseMask(segmentMask(src), w, h, subject), w, h, subject)
        val dm = depthMap(src, mask, subject, topDown)

        // blur a copy where the subject is painted over with nearby background,
        // so the subject's colours don't leak into the bokeh as a dark/bright halo
        val bgSrc = try { fillSubject(src, mask) } catch (e: OutOfMemoryError) { src }
        val radius = LensBlur.radiusFor(fNumber) * (if (topDown) 0.6f else 1f)
        val full = try { LensBlur.apply(bgSrc, radius) } catch (e: OutOfMemoryError) { blur(bgSrc) }
        val light = try { LensBlur.soft(bgSrc, radius * 0.5f) } catch (e: OutOfMemoryError) { full }

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
                // sharp at subject distance even if the cut-out missed it (whole bottle, not half)
                val keep = max(mask[i], (1f - d * 4f)).coerceIn(0f, 1f)
                val s0 = sp[i]
                val l = lp[i]
                val f = fp[i]
                // background colour: sharp -> light blur -> full blur as distance grows
                val sr = Color.red(s0); val sg = Color.green(s0); val sb = Color.blue(s0)
                val br: Float
                val bgc: Float
                val bb: Float
                if (d < 0.5f) {
                    val t = d * 2f
                    br = sr + (Color.red(l) - sr) * t
                    bgc = sg + (Color.green(l) - sg) * t
                    bb = sb + (Color.blue(l) - sb) * t
                } else {
                    val t = (d - 0.5f) * 2f
                    br = Color.red(l) + (Color.red(f) - Color.red(l)) * t
                    bgc = Color.green(l) + (Color.green(f) - Color.green(l)) * t
                    bb = Color.blue(l) + (Color.blue(f) - Color.blue(l)) * t
                }
                sp[i] = Color.rgb(
                    (sr * keep + br * (1 - keep)).toInt().coerceIn(0, 255),
                    (sg * keep + bgc * (1 - keep)).toInt().coerceIn(0, 255),
                    (sb * keep + bb * (1 - keep)).toInt().coerceIn(0, 255)
                )
            }
        }
        return Bitmap.createBitmap(sp, w, h, Bitmap.Config.ARGB_8888)
    }

    class DepthMap(val v: FloatArray, val w: Int, val h: Int)

    /**
     * Cleans the cut-out:
     *  - keeps only the piece connected to the chosen subject (no half-sharp neighbours with
     *    jagged edges, like the second bottle);
     *  - smooths the stair-step edge into a soft, natural roll-off.
     */
    private fun refineMask(mask: FloatArray, w: Int, h: Int, subject: RectF?): FloatArray {
        val f = 4
        val sw = max(1, w / f)
        val sh = max(1, h / f)
        val small = FloatArray(sw * sh)
        for (y in 0 until sh) for (x in 0 until sw) {
            small[y * sw + x] = mask[(min(h - 1, y * f + f / 2)) * w + min(w - 1, x * f + f / 2)]
        }
        // connected piece(s) touching the subject box (or the biggest piece)
        val keep = BooleanArray(sw * sh)
        val seen = BooleanArray(sw * sh)
        val queue = IntArray(sw * sh)
        fun flood(start: Int, mark: BooleanArray?): Int {
            var head = 0; var tail = 0; var n = 0
            queue[tail++] = start; seen[start] = true
            while (head < tail) {
                val i = queue[head++]; n++
                mark?.set(i, true)
                val x = i % sw; val y = i / sw
                if (x > 0) { val j = i - 1; if (!seen[j] && small[j] > 0.35f) { seen[j] = true; queue[tail++] = j } }
                if (x < sw - 1) { val j = i + 1; if (!seen[j] && small[j] > 0.35f) { seen[j] = true; queue[tail++] = j } }
                if (y > 0) { val j = i - sw; if (!seen[j] && small[j] > 0.35f) { seen[j] = true; queue[tail++] = j } }
                if (y < sh - 1) { val j = i + sw; if (!seen[j] && small[j] > 0.35f) { seen[j] = true; queue[tail++] = j } }
            }
            return n
        }
        if (subject != null) {
            val l = (subject.left * sw).toInt().coerceIn(0, sw - 1)
            val r = (subject.right * sw).toInt().coerceIn(l + 1, sw)
            val t = (subject.top * sh).toInt().coerceIn(0, sh - 1)
            val b = (subject.bottom * sh).toInt().coerceIn(t + 1, sh)
            for (y in t until b) for (x in l until r) {
                val i = y * sw + x
                if (!seen[i] && small[i] > 0.35f) flood(i, keep)
            }
        }
        if (keep.none { it }) {
            // no box: keep the biggest piece
            java.util.Arrays.fill(seen, false)
            var best = -1; var bestN = 0
            for (i in 0 until sw * sh) if (!seen[i] && small[i] > 0.35f) {
                val n = flood(i, null); if (n > bestN) { bestN = n; best = i }
            }
            if (best < 0) return mask
            java.util.Arrays.fill(seen, false)
            flood(best, keep)
        }
        // grow the kept area a little so its own soft edge is not cut, then make it soft
        val gate = FloatArray(sw * sh) { if (keep[it]) 1f else 0f }
        LensBlur.boxBlur(gate, sw, sh, 3)
        for (i in gate.indices) gate[i] = (gate[i] * 3f).coerceAtMost(1f)
        // smooth edge: blurred copy of the cut-out at low resolution
        val soft = small.copyOf()
        LensBlur.boxBlur(soft, sw, sh, 1)
        val out = FloatArray(w * h)
        for (y in 0 until h) {
            val fy = ((y + 0.5f) / f - 0.5f).coerceIn(0f, (sh - 1).toFloat())
            val y0 = fy.toInt(); val y1 = min(sh - 1, y0 + 1); val ty = fy - y0
            for (x in 0 until w) {
                val fx = ((x + 0.5f) / f - 0.5f).coerceIn(0f, (sw - 1).toFloat())
                val x0 = fx.toInt(); val x1 = min(sw - 1, x0 + 1); val tx = fx - x0
                val a = y0 * sw + x0; val b = y0 * sw + x1; val c0 = y1 * sw + x0; val d = y1 * sw + x1
                val w00 = (1 - tx) * (1 - ty); val w01 = tx * (1 - ty); val w10 = (1 - tx) * ty; val w11 = tx * ty
                val sv = soft[a] * w00 + soft[b] * w01 + soft[c0] * w10 + soft[d] * w11
                val gv = gate[a] * w00 + gate[b] * w01 + gate[c0] * w10 + gate[d] * w11
                val i = y * w + x
                // fine detail from the original mask inside, smooth roll-off at the border
                val m = mask[i] * 0.5f + sv * 0.5f
                // contrast curve: solid inside, short soft edge
                val c = ((m - 0.3f) / 0.4f).coerceIn(0f, 1f)
                out[i] = c * c * (3 - 2 * c) * gv
            }
        }
        return out
    }

    /** Replaces subject pixels with the surrounding background colours (mask-weighted blur). */
    private fun fillSubject(src: Bitmap, mask: FloatArray): Bitmap {
        val w = src.width
        val h = src.height
        val f = 8
        val sw = max(1, w / f)
        val sh = max(1, h / f)
        val small = Bitmap.createScaledBitmap(src, sw, sh, true)
        val px = IntArray(sw * sh)
        small.getPixels(px, 0, sw, 0, 0, sw, sh)
        val wr = FloatArray(sw * sh); val wg = FloatArray(sw * sh); val wb = FloatArray(sw * sh); val ww = FloatArray(sw * sh)
        for (y in 0 until sh) for (x in 0 until sw) {
            val i = y * sw + x
            val m = mask[min(h - 1, y * f + f / 2) * w + min(w - 1, x * f + f / 2)]
            val bgw = ((0.6f - m) / 0.6f).coerceIn(0f, 1f)   // only clear background counts
            wr[i] = Color.red(px[i]) * bgw; wg[i] = Color.green(px[i]) * bgw; wb[i] = Color.blue(px[i]) * bgw; ww[i] = bgw
        }
        var rad = 2
        repeat(4) {
            LensBlur.boxBlur(wr, sw, sh, rad); LensBlur.boxBlur(wg, sw, sh, rad)
            LensBlur.boxBlur(wb, sw, sh, rad); LensBlur.boxBlur(ww, sw, sh, rad)
            rad *= 2
        }
        val fill = IntArray(sw * sh) {
            val n = ww[it]
            if (n < 1e-4f) px[it] else Color.rgb((wr[it] / n).toInt().coerceIn(0, 255), (wg[it] / n).toInt().coerceIn(0, 255), (wb[it] / n).toInt().coerceIn(0, 255))
        }
        val fillBmp = Bitmap.createScaledBitmap(Bitmap.createBitmap(fill, sw, sh, Bitmap.Config.ARGB_8888), w, h, true)
        val sp = IntArray(w * h)
        val fp = IntArray(w * h)
        src.getPixels(sp, 0, w, 0, 0, w, h)
        fillBmp.getPixels(fp, 0, w, 0, 0, w, h)
        for (i in sp.indices) {
            val m = (mask[i] * 1.6f).coerceIn(0f, 1f)        // cover the edge too
            if (m <= 0f) continue
            val a = sp[i]; val b = fp[i]
            sp[i] = Color.rgb(
                (Color.red(a) + (Color.red(b) - Color.red(a)) * m).toInt(),
                (Color.green(a) + (Color.green(b) - Color.green(a)) * m).toInt(),
                (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * m).toInt()
            )
        }
        return Bitmap.createBitmap(sp, w, h, Bitmap.Config.ARGB_8888)
    }

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
            subj.sort(); subj[(subj.size * 0.5f).toInt().coerceIn(0, subj.size - 1)]
        } else {
            val all = e.copyOf(); all.sort(); all[(all.size * 0.9f).toInt().coerceIn(0, all.size - 1)]
        }
        val ref = refRaw.coerceAtLeast(0.004f)

        // the whole subject (top to bottom), from the tapped box or the cut-out
        // union: a small tapped box + the full cut-out connected to it = the whole object
        val box = RectF(maskBounds(mask, w, h)).also { if (subject != null) it.union(subject) }
        val top = box.top
        val bottom = box.bottom
        val d = FloatArray(sw * sh)
        for (y in 0 until sh) {
            val ny = y.toFloat() / sh
            // like a real lens on a table/floor scene:
            //  above the subject = farther away (strong blur),
            //  level with the subject's base = same distance (sharp),
            //  below it = closer to the camera (gentle foreground blur)
            val prior = when {
                topDown -> 0f
                ny < top -> ((top - ny) / 0.2f).coerceIn(0f, 1f) * 0.85f
                ny > bottom -> ((ny - bottom) / 0.35f).coerceIn(0f, 1f) * 0.5f
                else -> 0f
            }
            for (x in 0 until sw) {
                val nx = x.toFloat() / sw
                val i = y * sw + x
                val ratio = e[i] / ref
                // as crisp as the subject -> same distance -> stays sharp (like Pixel's notebook)
                val soft = ((0.55f - ratio) / 0.4f).coerceIn(0f, 1f)
                var v = if (ny > bottom) max(soft * 0.6f, prior) else max(soft, prior)
                // inside the subject's own box keep it sharp even where it has no texture
                // (clear plastic, plain clothes), so the whole object stays in focus
                if (nx in box.left..box.right && ny in top..bottom) v *= 0.15f
                if (topDown) v *= 0.45f
                d[i] = v
            }
        }
        LensBlur.boxBlur(d, sw, sh, 4)
        LensBlur.boxBlur(d, sw, sh, 4)
        return DepthMap(d, sw, sh)
    }

    /** Bounding box (0..1) of the cut-out, or a centre box if there is none. */
    private fun maskBounds(mask: FloatArray, w: Int, h: Int): RectF {
        var l = w; var r = -1; var t = h; var b = -1
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                if (mask[y * w + x] > 0.5f) {
                    if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > b) b = y
                }
                x += 4
            }
            y += 4
        }
        if (r < 0) return RectF(0.3f, 0.3f, 0.7f, 0.75f)
        return RectF(l.toFloat() / w, t.toFloat() / h, r.toFloat() / w, b.toFloat() / h)
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

    /** Runs the AI cut-out on a smaller copy (much faster) and scales the mask back up. */
    private fun segmentMask(src: Bitmap): FloatArray? {
        return try {
            val w = src.width
            val h = src.height
            val scale = (1024f / max(w, h)).coerceAtMost(1f)
            val mw = max(1, (w * scale).toInt())
            val mh = max(1, (h * scale).toInt())
            val small = if (scale < 1f) Bitmap.createScaledBitmap(src, mw, mh, true) else src
            val options = SubjectSegmenterOptions.Builder()
                .enableForegroundConfidenceMask()
                .build()
            val segmenter = SubjectSegmentation.getClient(options)
            val result = Tasks.await(segmenter.process(InputImage.fromBitmap(small, 0)))
            segmenter.close()
            val buf = result.foregroundConfidenceMask ?: return null
            buf.rewind()
            if (buf.remaining() < mw * mh) return null
            val m = FloatArray(mw * mh)
            buf.get(m)
            if (scale >= 1f) return m
            // bilinear upscale to full size
            val out = FloatArray(w * h)
            for (y in 0 until h) {
                val fy = (y + 0.5f) * mh / h - 0.5f
                val y0 = fy.toInt().coerceIn(0, mh - 1)
                val y1 = (y0 + 1).coerceAtMost(mh - 1)
                val ty = (fy - y0).coerceIn(0f, 1f)
                for (x in 0 until w) {
                    val fx = (x + 0.5f) * mw / w - 0.5f
                    val x0 = fx.toInt().coerceIn(0, mw - 1)
                    val x1 = (x0 + 1).coerceAtMost(mw - 1)
                    val tx = (fx - x0).coerceIn(0f, 1f)
                    val top = m[y0 * mw + x0] * (1 - tx) + m[y0 * mw + x1] * tx
                    val bot = m[y1 * mw + x0] * (1 - tx) + m[y1 * mw + x1] * tx
                    out[y * w + x] = top * (1 - ty) + bot * ty
                }
            }
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
