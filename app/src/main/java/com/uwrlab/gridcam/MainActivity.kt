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
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
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
                    overlay.userMode = mode
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
                    if (!pinching && event.eventTime - event.downTime < 400) {
                        val point = previewView.meteringPointFactory.createPoint(event.x, event.y)
                        val action = FocusMeteringAction.Builder(
                            point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
                        ).setAutoCancelDuration(3, TimeUnit.SECONDS).build()
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
        val input = InputImage.fromMediaImage(media, rotation)

        detector.process(input)
            .addOnSuccessListener { objects ->
                val best = objects.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                val box = best?.boundingBox?.let {
                    RectF(
                        (it.left / w).coerceIn(0f, 1f),
                        (it.top / h).coerceIn(0f, 1f),
                        (it.right / w).coerceIn(0f, 1f),
                        (it.bottom / h).coerceIn(0f, 1f)
                    )
                }
                overlay.updateSubject(box?.let { toScreen(it) })
            }
            .addOnCompleteListener { proxy.close() }
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
