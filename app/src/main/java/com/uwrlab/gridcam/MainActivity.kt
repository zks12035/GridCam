package com.uwrlab.gridcam

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.ObjectDetector
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs

private const val MAX_TOTAL_ZOOM = 30f

class MainActivity : AppCompatActivity(), GridOverlayView.Listener, SensorEventListener {

    private lateinit var previewView: PreviewView
    private lateinit var overlay: GridOverlayView
    private lateinit var modeText: TextView
    private lateinit var reasonText: TextView
    private lateinit var guidanceText: TextView
    private lateinit var flash: View
    private lateinit var modeBar: LinearLayout
    private lateinit var zoomBar: LinearLayout
    private lateinit var zoomText: TextView
    private lateinit var scaleDetector: ScaleGestureDetector
    private var pinching = false
    private var minZoom = 1f
    private var maxZoom = 1f
    private var currentZoom = 1f
    private var extraZoom = 1f            // digital crop beyond the camera's own maximum
    private val zoomPresets = ArrayList<Float>()
    private var zoomInfoShown = false
    private val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private lateinit var analysisExecutor: ExecutorService
    private lateinit var detector: ObjectDetector

    private lateinit var sensorManager: SensorManager

    private val chips = LinkedHashMap<GridMode, TextView>()
    private val guidanceBg = GradientDrawable()
    private val density by lazy { resources.displayMetrics.density }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera()
            else {
                guidanceText.text = "Camera permission is needed. Allow it in Settings > Apps > Grid Cam."
                Toast.makeText(this, "Camera permission denied", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        overlay = findViewById(R.id.overlay)
        modeText = findViewById(R.id.modeText)
        reasonText = findViewById(R.id.reasonText)
        guidanceText = findViewById(R.id.guidanceText)
        flash = findViewById(R.id.flash)
        modeBar = findViewById(R.id.modeBar)
        zoomBar = findViewById(R.id.zoomBar)
        zoomText = findViewById(R.id.zoomText)
        // TextureView mode so the preview can be scaled for extra digital zoom
        previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE

        guidanceBg.cornerRadius = 18f * density
        guidanceBg.setColor(Color.argb(150, 0, 0, 0))
        guidanceText.background = guidanceBg

        analysisExecutor = Executors.newSingleThreadExecutor()
        detector = ObjectDetection.getClient(
            ObjectDetectorOptions.Builder()
                .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
                .enableMultipleObjects()
                .build()
        )

        overlay.listener = this
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        buildModeChips()
        setupTapToFocus()
        findViewById<View>(R.id.shutter).setOnClickListener { takePhoto() }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) startCamera() else permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    // ---------- UI ----------

    private fun buildModeChips() {
        for (mode in GridMode.values()) {
            val chip = TextView(this).apply {
                text = mode.label
                textSize = 14f
                val padH = (14 * density).toInt()
                val padV = (8 * density).toInt()
                setPadding(padH, padV, padH, padV)
                setOnClickListener {
                    this@MainActivity.overlay.userMode = mode
                    refreshChips()
                }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.marginEnd = (8 * density).toInt()
            modeBar.addView(chip, lp)
            chips[mode] = chip
        }
        refreshChips()
    }

    private fun refreshChips() {
        for ((mode, chip) in chips) {
            val selected = mode == overlay.userMode
            chip.background = GradientDrawable().apply {
                cornerRadius = 50f * density
                setColor(if (selected) Color.WHITE else Color.argb(60, 255, 255, 255))
            }
            chip.setTextColor(if (selected) Color.BLACK else Color.WHITE)
        }
    }

    override fun onGuidance(mode: GridMode, reason: String, guidance: String, aligned: Boolean) {
        val prefix = if (overlay.userMode == GridMode.AUTO) "Suggested grid: " else "Grid: "
        modeText.text = prefix + mode.label
        reasonText.text = reason
        guidanceText.text = guidance
        guidanceBg.setColor(
            if (aligned) Color.argb(200, 0, 160, 80) else Color.argb(150, 0, 0, 0)
        )
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTapToFocus() {
        scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                pinching = true
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                setZoom(currentZoom * detector.scaleFactor)
                return true
            }
        })
        previewView.setOnTouchListener { v, event ->
            scaleDetector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> pinching = false
                MotionEvent.ACTION_UP -> {
                    val pressTime = event.eventTime - event.downTime
                    if (!pinching && pressTime >= 600) {
                        // long-press: clear the selected subject
                        selected = false
                        lockedBox = null
                        lockedId = null
                        pendingTap = null
                        overlay.clearSubject()
                        camera?.cameraControl?.cancelFocusAndMetering()
                        Toast.makeText(this, "Subject cleared", Toast.LENGTH_SHORT).show()
                    }
                    if (!pinching && pressTime < 400) {
                        // tap: select this object (camera-frame coordinates) and focus on it
                        if (previewView.width > 0) {
                            pendingTap = PointF(event.x / previewView.width, event.y / previewView.height)
                        }
                        lastFocusTime = SystemClock.uptimeMillis()
                        lastFocusX = event.x / previewView.width.coerceAtLeast(1)
                        lastFocusY = event.y / previewView.height.coerceAtLeast(1)
                        val point = previewView.meteringPointFactory.createPoint(event.x, event.y)
                        val action = FocusMeteringAction.Builder(
                            point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
                        ).disableAutoCancel().build()
                        camera?.cameraControl?.startFocusAndMetering(action)
                        // the preview may be scaled for extra digital zoom; convert to screen position
                        val cx = previewView.width / 2f
                        val cy = previewView.height / 2f
                        overlay.showFocus(cx + (event.x - cx) * extraZoom, cy + (event.y - cy) * extraZoom)
                        v.performClick()
                    }
                }
            }
            true
        }
    }

    // ---------- Zoom (optical/hybrid from the camera, then digital crop up to 30x) ----------

    private fun setZoom(target: Float) {
        val cam = camera ?: return
        val t = target.coerceIn(minZoom, MAX_TOTAL_ZOOM)
        currentZoom = t
        if (t <= maxZoom) {
            cam.cameraControl.setZoomRatio(t)
            extraZoom = 1f
        } else {
            cam.cameraControl.setZoomRatio(maxZoom)
            extraZoom = t / maxZoom
        }
        previewView.scaleX = extraZoom
        previewView.scaleY = extraZoom
        updateZoomUi()
    }

    private fun zoomLabel(z: Float) =
        if (z < 10f) String.format(Locale.US, "%.1f×", z).replace(".0×", "×")
        else String.format(Locale.US, "%.0f×", z)

    private fun buildZoomChips() {
        zoomBar.removeAllViews()
        zoomPresets.clear()
        if (minZoom < 0.95f) zoomPresets.add(minZoom)
        zoomPresets.addAll(listOf(1f, 2f, 5f, 10f, 30f))
        for (z in zoomPresets) {
            val chip = TextView(this).apply {
                text = zoomLabel(z)
                textSize = 13f
                gravity = Gravity.CENTER
                setOnClickListener { setZoom(z) }
            }
            val size = (40 * density).toInt()
            val lp = LinearLayout.LayoutParams(size, size)
            lp.marginStart = (3 * density).toInt()
            lp.marginEnd = (3 * density).toInt()
            zoomBar.addView(chip, lp)
        }
        updateZoomUi()
    }

    private fun updateZoomUi() {
        for (i in 0 until zoomBar.childCount) {
            val chip = zoomBar.getChildAt(i) as TextView
            val on = abs(zoomPresets[i] - currentZoom) / zoomPresets[i] < 0.04f
            chip.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (on) Color.argb(220, 255, 255, 255) else Color.argb(110, 0, 0, 0))
            }
            chip.setTextColor(if (on) Color.BLACK else Color.WHITE)
        }
        val sb = StringBuilder(zoomLabel(currentZoom))
        if (extraZoom > 1.01f) sb.append("  ·  digital crop")
        if (currentZoom >= 5f) sb.append("  ·  hold steady")
        zoomText.text = sb.toString()
    }

    // ---------- Camera ----------

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()

            val ratio43 = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .build()

            val preview = Preview.Builder()
                .setResolutionSelector(ratio43)
                .build()
            preview.setSurfaceProvider(previewView.surfaceProvider)

            val capture = ImageCapture.Builder()
                .setResolutionSelector(ratio43)
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(ratio43)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(analysisExecutor) { proxy -> analyze(proxy) }

            try {
                provider.unbindAll()
                camera = provider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture, analysis
                )
                imageCapture = capture
                camera?.cameraInfo?.zoomState?.observe(this) { state ->
                    val newMin = state.minZoomRatio
                    val newMax = state.maxZoomRatio
                    if (newMin != minZoom || newMax != maxZoom || zoomBar.childCount == 0) {
                        minZoom = newMin
                        maxZoom = newMax
                        buildZoomChips()
                        if (!zoomInfoShown && maxZoom < MAX_TOTAL_ZOOM) {
                            zoomInfoShown = true
                            Toast.makeText(
                                this,
                                "Camera zoom up to ${zoomLabel(maxZoom)}; beyond that up to 30× uses digital crop",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(this, "Could not start camera: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // ---------- Subject selection + tracking ----------

    private var pendingTap: PointF? = null      // where the user tapped (camera-frame 0..1)
    private var selected = false
    private var lockedId: Int? = null           // ML Kit tracking id, if the detector knows the object
    private var lockedBox: RectF? = null        // current subject box (camera-frame 0..1)
    private var lostFrames = 0
    private val tracker = TemplateTracker()
    private var lastFocusTime = 0L
    private var lastFocusX = 0f
    private var lastFocusY = 0f

    private class Det(val id: Int?, val box: RectF)

    @OptIn(ExperimentalGetImage::class)
    private fun analyze(proxy: ImageProxy) {
        val media = proxy.image
        if (media == null) {
            proxy.close()
            return
        }
        val rotation = proxy.imageInfo.rotationDegrees
        val sideways = rotation == 90 || rotation == 270
        val w = (if (sideways) proxy.height else proxy.width).toFloat()
        val h = (if (sideways) proxy.width else proxy.height).toFloat()
        val gray = SceneAnalyzer.gray(proxy)   // small grayscale copy for tracking + scene analysis
        val input = InputImage.fromMediaImage(media, rotation)

        detector.process(input)
            .addOnSuccessListener { objects ->
                val dets = objects.map {
                    val bb = it.boundingBox
                    Det(
                        it.trackingId,
                        RectF(
                            (bb.left / w).coerceIn(0f, 1f), (bb.top / h).coerceIn(0f, 1f),
                            (bb.right / w).coerceIn(0f, 1f), (bb.bottom / h).coerceIn(0f, 1f)
                        )
                    )
                }
                handleFrame(gray, dets)
            }
            .addOnCompleteListener { proxy.close() }
    }

    private fun handleFrame(gray: FloatArray, dets: List<Det>) {
        // 1. A new tap: pick the detected object under the finger, or follow the tapped spot
        pendingTap?.let { tap ->
            pendingTap = null
            val hit = dets.filter { it.box.contains(tap.x, tap.y) }
                .minByOrNull { it.box.width() * it.box.height() }
                ?: dets.minByOrNull { Math.hypot((it.box.centerX() - tap.x).toDouble(), (it.box.centerY() - tap.y).toDouble()) }
                    ?.takeIf { Math.hypot((it.box.centerX() - tap.x).toDouble(), (it.box.centerY() - tap.y).toDouble()) < 0.12 }
            overlay.clearSubject()
            selected = true
            lostFrames = 0
            if (hit != null) {
                lockedId = hit.id
                lockedBox = RectF(hit.box)
                tracker.start(gray, hit.box.centerX(), hit.box.centerY())
            } else {
                lockedId = null
                lockedBox = RectF(tap.x - 0.11f, tap.y - 0.08f, tap.x + 0.11f, tap.y + 0.08f)
                tracker.start(gray, tap.x, tap.y)
            }
        }

        // 2. Follow the selected subject
        var box: RectF? = null
        if (selected) {
            val id = lockedId
            if (id != null) box = dets.firstOrNull { it.id == id }?.box
            val c = tracker.update(gray)
            if (box != null) {
                tracker.start(gray, box.centerX(), box.centerY())   // re-anchor on the detector's box
            } else if (c != null) {
                val old = lockedBox
                if (old != null) {
                    val hw = old.width() / 2f
                    val hh = old.height() / 2f
                    box = RectF(c[0] - hw, c[1] - hh, c[0] + hw, c[1] + hh)
                }
            }
            if (box != null) {
                lockedBox = RectF(box)
                lostFrames = 0
                maybeRefocus(box)
            } else if (++lostFrames > 30) {
                selected = false
                lockedBox = null
                camera?.cameraControl?.cancelFocusAndMetering()
                Toast.makeText(this, "Subject lost. Tap it again.", Toast.LENGTH_SHORT).show()
            } else {
                box = lockedBox   // keep the last position for a moment
            }
        }

        // 3. Read the surroundings and update the grid
        val info = SceneAnalyzer.analyze(gray, box)
        val hy = if (info.horizonY.isNaN()) Float.NaN else 0.5f + (info.horizonY - 0.5f) * extraZoom
        overlay.updateScene(info, if (hy in 0f..1f) hy else Float.NaN)
        overlay.updateSubject(if (selected) box?.let { toScreen(it) } else null)
    }

    /** Keeps focus on the subject while it moves. */
    private fun maybeRefocus(b: RectF) {
        val now = SystemClock.uptimeMillis()
        val moved = Math.hypot((b.centerX() - lastFocusX).toDouble(), (b.centerY() - lastFocusY).toDouble())
        // refocus when the subject moves, and every 3 s anyway (distance may change)
        if (now - lastFocusTime < 1500) return
        if (moved < 0.08 && now - lastFocusTime < 3000) return
        lastFocusTime = now
        lastFocusX = b.centerX()
        lastFocusY = b.centerY()
        if (previewView.width == 0) return
        val point = previewView.meteringPointFactory.createPoint(
            b.centerX() * previewView.width, b.centerY() * previewView.height
        )
        camera?.cameraControl?.startFocusAndMetering(
            FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                .disableAutoCancel().build()
        )
    }

    /** Maps a normalized box from the camera frame to the (possibly digitally zoomed) screen. */
    private fun toScreen(b: RectF): RectF? {
        val k = extraZoom
        if (k <= 1.001f) return b
        val r = RectF(
            0.5f + (b.left - 0.5f) * k, 0.5f + (b.top - 0.5f) * k,
            0.5f + (b.right - 0.5f) * k, 0.5f + (b.bottom - 0.5f) * k
        )
        if (r.right <= 0f || r.left >= 1f || r.bottom <= 0f || r.top >= 1f) return null
        return RectF(r.left.coerceIn(0f, 1f), r.top.coerceIn(0f, 1f), r.right.coerceIn(0f, 1f), r.bottom.coerceIn(0f, 1f))
    }

    /** Crops the saved photo's center by [factor] and overwrites it (for zoom beyond the camera max). */
    private fun cropInPlace(uri: Uri, factor: Float) {
        val src = ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { d, _, _ ->
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        val cw = (src.width / factor).toInt().coerceAtLeast(1)
        val ch = (src.height / factor).toInt().coerceAtLeast(1)
        val cropped = Bitmap.createBitmap(src, (src.width - cw) / 2, (src.height - ch) / 2, cw, ch)
        contentResolver.openOutputStream(uri, "wt")?.use {
            cropped.compress(Bitmap.CompressFormat.JPEG, 95, it)
        }
    }

    private fun takePhoto() {
        val capture = imageCapture ?: return
        val shotTilt = overlay.straightenAngle()
        val shotCrop = extraZoom
        val name = "GridCam_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
            .format(System.currentTimeMillis())
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/GridCam")
        }
        val options = ImageCapture.OutputFileOptions.Builder(
            contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
        ).build()

        flash.alpha = 0.8f
        flash.animate().alpha(0f).setDuration(250).start()

        capture.takePicture(options, ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    Toast.makeText(this@MainActivity, "Saved to Photos (Pictures/GridCam)", Toast.LENGTH_SHORT).show()
                    val uri = output.savedUri ?: return
                    val openEditor = {
                        startActivity(
                            Intent(this@MainActivity, EditActivity::class.java)
                                .setData(uri)
                                .putExtra("tilt", shotTilt)
                        )
                    }
                    if (shotCrop > 1.01f) {
                        ioExecutor.execute {
                            try {
                                cropInPlace(uri, shotCrop)
                            } catch (e: Exception) {
                                // keep the uncropped photo if cropping fails
                            }
                            runOnUiThread { openEditor() }
                        }
                    } else {
                        openEditor()
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Toast.makeText(this@MainActivity, "Photo failed: ${exception.message}", Toast.LENGTH_LONG).show()
                }
            })
    }

    // ---------- Horizon level ----------

    override fun onResume() {
        super.onResume()
        sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    private var smoothRoll = Float.NaN
    private var smoothBx = 0f
    private var smoothBy = 0f

    override fun onSensorChanged(event: SensorEvent) {
        val gx = event.values[0]
        val gy = event.values[1]
        val planar = Math.hypot(gx.toDouble(), gy.toDouble()).toFloat()
        if (planar < 2.5f) {
            // phone almost flat: top-down level with two crosshairs
            smoothBx += (gx - smoothBx) * 0.3f
            smoothBy += (gy - smoothBy) * 0.3f
            smoothRoll = Float.NaN
            overlay.setLevel(Float.NaN, true, smoothBx, smoothBy)
            return
        }
        if (planar < 4f) {
            overlay.setLevel(Float.NaN, false, 0f, 0f) // in between: hide
            return
        }
        val roll = Math.toDegrees(Math.atan2(-gx.toDouble(), gy.toDouble())).toFloat()
        if (smoothRoll.isNaN()) smoothRoll = roll
        var diff = roll - smoothRoll
        while (diff > 180f) diff -= 360f
        while (diff < -180f) diff += 360f
        smoothRoll += diff * 0.25f
        if (smoothRoll > 180f) smoothRoll -= 360f
        if (smoothRoll < -180f) smoothRoll += 360f
        overlay.setLevel(smoothRoll, false, 0f, 0f)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onDestroy() {
        super.onDestroy()
        analysisExecutor.shutdown()
        ioExecutor.shutdown()
        detector.close()
    }
}
