package com.uwrlab.gridcam

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.roundToInt

/** Shows a saved photo with Snapseed edit instructions, plus a quick in-app editor. */
class EditActivity : AppCompatActivity() {

    private lateinit var imageView: ImageView
    private lateinit var tipsText: TextView
    private val seekBars = ArrayList<SeekBar>()
    private val valueLabels = ArrayList<TextView>()

    private var photoUri: Uri? = null
    private var original: Bitmap? = null   // large version used for saving
    private var previewSrc: Bitmap? = null // small version used for fast preview
    private var edited: Bitmap? = null
    private var autoValues = FloatArray(EDIT_NAMES.size)
    private val values = FloatArray(EDIT_NAMES.size)
    private var shotTilt = 0f
    private var gridUsed = ""
    private var modeUsed = ""
    private lateinit var infoText: TextView

    private lateinit var worker: ExecutorService
    @Volatile private var renderVersion = 0
    private val density by lazy { resources.displayMetrics.density }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        photoUri = intent.data
        if (photoUri == null) {
            finish()
            return
        }
        shotTilt = intent.getFloatExtra("tilt", Float.NaN)
        worker = Executors.newSingleThreadExecutor()
        setContentView(buildUi())
        loadPhoto()
    }

    private fun dp(v: Int) = (v * density).toInt()

    @SuppressLint("ClickableViewAccessibility")
    private fun buildUi(): ScrollView {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }

        root.addView(TextView(this).apply {
            text = "Photo & edit guide"
            setTextColor(Color.WHITE)
            textSize = 20f
        })
        infoText = TextView(this).apply {
            text = ""
            setTextColor(Color.argb(180, 255, 255, 255))
            textSize = 13f
        }
        root.addView(infoText)

        imageView = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            setOnTouchListener { _, e ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> setImageBitmap(previewSrc)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> setImageBitmap(edited ?: previewSrc)
                }
                true
            }
        }
        root.addView(imageView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12) })

        val rowA = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        rowA.addView(button("Open in Snapseed") { openInSnapseed() }, weightLp())
        rowA.addView(button("Google Photos") { openInPhotos() }, weightLp())
        root.addView(rowA, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })
        val rowB = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        rowB.addView(button("Share") { sharePhoto() }, weightLp())
        rowB.addView(button("All Grid Cam photos") {
            startActivity(Intent(this@EditActivity, GalleryActivity::class.java))
        }, weightLp())
        root.addView(rowB)

        tipsText = TextView(this).apply {
            text = "Analysing photo..."
            setTextColor(Color.WHITE)
            textSize = 14f
            setLineSpacing(0f, 1.25f)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = GradientDrawable().apply {
                cornerRadius = 14f * density
                setColor(Color.argb(40, 255, 255, 255))
            }
        }
        root.addView(tipsText, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12) })

        root.addView(TextView(this).apply {
            text = "Quick edit in Grid Cam (touch and hold the photo to compare)"
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(0, dp(18), 0, dp(4))
        })
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row1.addView(button("Auto fix") { setValues(autoValues) }, weightLp())
        row1.addView(button("Reset") { setValues(FloatArray(EDIT_NAMES.size)) }, weightLp())
        root.addView(row1)

        for (i in EDIT_NAMES.indices) root.addView(sliderRow(i))

        root.addView(button("Save edited copy") { saveEdited() }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })

        root.addView(button("Back to camera") { finish() }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12) })

        return ScrollView(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(root)
        }
    }

    private fun weightLp() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
        marginStart = dp(4)
        marginEnd = dp(4)
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun sliderRow(i: Int): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        row.addView(TextView(this).apply {
            text = EDIT_NAMES[i]
            setTextColor(Color.WHITE)
            textSize = 14f
        }, LinearLayout.LayoutParams(dp(92), ViewGroup.LayoutParams.WRAP_CONTENT))

        val bar = SeekBar(this).apply {
            max = 200
            progress = 100
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    values[i] = (p - 100) / 100f
                    valueLabels[i].text = fmt(i, values[i])
                    if (fromUser) render()
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        seekBars.add(bar)
        row.addView(bar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val label = TextView(this).apply {
            text = "0"
            setTextColor(Color.WHITE)
            gravity = Gravity.END
        }
        valueLabels.add(label)
        row.addView(label, LinearLayout.LayoutParams(dp(58), ViewGroup.LayoutParams.WRAP_CONTENT))
        return row
    }

    private fun fmt(i: Int, v: Float): String {
        if (i == 6) return String.format(Locale.US, "%.1f°", v * 15f)
        val n = (v * 100).roundToInt()
        return if (n > 0) "+$n" else "$n"
    }

    private fun setValues(v: FloatArray) {
        for (i in v.indices) {
            values[i] = v[i]
            seekBars[i].progress = (v[i] * 100).roundToInt() + 100
            valueLabels[i].text = fmt(i, v[i])
        }
        render()
    }

    // ---------- Loading, preview, saving ----------

    private fun decode(uri: Uri, maxSide: Int): Bitmap {
        val source = ImageDecoder.createSource(contentResolver, uri)
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

    private fun loadPhoto() {
        val uri = photoUri ?: return
        worker.execute {
            try {
                val full = decode(uri, 3000)
                val scale = 1200f / max(full.width, full.height)
                val small = if (scale < 1f) Bitmap.createScaledBitmap(
                    full, (full.width * scale).toInt(), (full.height * scale).toInt(), true
                ) else full
                val info = MediaHelper.readInfo(contentResolver, uri)
                if (shotTilt.isNaN()) shotTilt = info["tilt"]?.toFloatOrNull() ?: 0f
                gridUsed = info["grid"] ?: ""
                modeUsed = info["mode"] ?: ""
                val report = PhotoAnalyzer.analyze(small, shotTilt, gridUsed, modeUsed)
                runOnUiThread {
                    infoText.text = buildString {
                        if (modeUsed.isNotEmpty()) append("Mode: ").append(modeUsed)
                        if (gridUsed.isNotEmpty()) append(if (isEmpty()) "" else "  ·  ").append("Grid: ").append(gridUsed)
                        if (isEmpty()) append("Photo")
                    }
                    original = full
                    previewSrc = small
                    edited = null
                    imageView.setImageBitmap(small)
                    tipsText.text = report.tips
                    autoValues = report.auto
                }
            } catch (e: Exception) {
                runOnUiThread { tipsText.text = "Could not open the photo: ${e.message}" }
            }
        }
    }

    private fun render() {
        val src = previewSrc ?: return
        val v = values.copyOf()
        val version = ++renderVersion
        worker.execute {
            if (version != renderVersion) return@execute
            val out = PhotoEditor.apply(src, v)
            runOnUiThread {
                if (version == renderVersion) {
                    edited = out
                    imageView.setImageBitmap(out)
                }
            }
        }
    }

    private fun saveEdited() {
        val full = original ?: return
        val v = values.copyOf()
        Toast.makeText(this, "Saving...", Toast.LENGTH_SHORT).show()
        worker.execute {
            try {
                val out = PhotoEditor.apply(full, v)
                val name = "GridCam_edit_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                    .format(System.currentTimeMillis())
                val cv = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, MediaHelper.PHOTO_DIR)
                    put(
                        MediaStore.Images.ImageColumns.DESCRIPTION,
                        MediaHelper.makeInfo(0f, gridUsed, "Edited in Grid Cam")
                    )
                }
                val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
                    ?: throw IllegalStateException("No storage")
                contentResolver.openOutputStream(uri)?.use { out.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                runOnUiThread {
                    Toast.makeText(this, "Edited copy saved to Photos", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "Save failed: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun openInSnapseed() {
        val uri = photoUri ?: return
        val pkg = "com.niksoftware.snapseed"
        val edit = Intent(Intent.ACTION_EDIT).setDataAndType(uri, "image/jpeg")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            .setPackage(pkg)
        val send = Intent(Intent.ACTION_SEND).setType("image/jpeg")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .setPackage(pkg)
        try {
            startActivity(edit)
        } catch (e: ActivityNotFoundException) {
            try {
                startActivity(send)
            } catch (e2: ActivityNotFoundException) {
                Toast.makeText(this, "Snapseed is not installed. Opening Play Store.", Toast.LENGTH_SHORT).show()
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")))
                } catch (e3: ActivityNotFoundException) {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$pkg")))
                }
            }
        }
    }

    private fun openInPhotos() {
        val uri = photoUri ?: return
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(Intent(view).setPackage("com.google.android.apps.photos"))
        } catch (e: ActivityNotFoundException) {
            try {
                startActivity(view)
            } catch (e2: ActivityNotFoundException) {
                Toast.makeText(this, "No gallery app found", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun sharePhoto() {
        val uri = photoUri ?: return
        val send = Intent(Intent.ACTION_SEND).setType("image/jpeg")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, "Share photo"))
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::worker.isInitialized) worker.shutdown()
    }
}
