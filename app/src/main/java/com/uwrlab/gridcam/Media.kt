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

    /** Must be called on a background thread. [subject] is the selected box (0..1) or null. */
    fun make(context: Context, src: Bitmap, subject: RectF?, fNumber: Float = 1.8f): Bitmap {
        val w = src.width
        val h = src.height
        val mask = segmentMask(src) ?: ovalMask(w, h, subject)
        val blurred = try {
            LensBlur.apply(src, LensBlur.radiusFor(fNumber))
        } catch (e: OutOfMemoryError) {
            blur(src)
        }

        val sp = IntArray(w * h)
        val bp = IntArray(w * h)
        src.getPixels(sp, 0, w, 0, 0, w, h)
        blurred.getPixels(bp, 0, w, 0, 0, w, h)
        for (i in sp.indices) {
            val a = mask[i].coerceIn(0f, 1f)
            val s = sp[i]
            val b = bp[i]
            val r = (Color.red(s) * a + Color.red(b) * (1 - a)).toInt()
            val g = (Color.green(s) * a + Color.green(b) * (1 - a)).toInt()
            val bl = (Color.blue(s) * a + Color.blue(b) * (1 - a)).toInt()
            sp[i] = Color.rgb(r, g, bl)
        }
        return Bitmap.createBitmap(sp, w, h, Bitmap.Config.ARGB_8888)
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
        val rx = max(1f, box.width() * w * 0.65f)
        val ry = max(1f, box.height() * h * 0.65f)
        val out = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val dx = (x - cx) / rx
            val dy = (y - cy) / ry
            val d = sqrt(dx * dx + dy * dy)
            out[y * w + x] = (1.4f - d).coerceIn(0f, 1f) // 1 inside, soft edge
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
