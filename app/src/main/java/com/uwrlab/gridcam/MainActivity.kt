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
import androidx.camera.video.*
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.widget.SeekBar
import android.media.AudioManager
import android.media.ToneGenerator
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import kotlin.math.roundToInt
import androidx.camera.core.*
import androidx.camera.lifecycle.*
import android.util.Size
import android.widget.ImageView
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

enum class CaptureMode(val label: String) {
    PHOTO("Photo"), PORTRAIT("Portrait"), NIGHT("Night"), VIDEO("Video"), SLOWMO("Slow motion")
}

class MainActivity : AppCompatActivity(), GridOverlayView.Listener, SensorEventListener {

    private lateinit var previewView: PreviewView
    private lateinit var overlay: GridOverlayView
    private lateinit var modeText: TextView
    private lateinit var reasonText: TextView
    private lateinit var guidanceText: TextView
    private lateinit var flash: View
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

    // capture modes, video and gallery thumbnail
    private var captureMode = CaptureMode.PHOTO
    private var cameraProvider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var recordStart = 0L
    private var lastItem: MediaItem? = null
    private lateinit var thumb: ImageView
    private lateinit var recTimer: TextView
    private lateinit var shutter: View
    // bottom: three icons (Mode · Grid · Pro), each opens a pop-up table
    private lateinit var iconRow: LinearLayout
    private lateinit var sheet: View
    private lateinit var sheetTitle: TextView
    private lateinit var sheetGrid: android.widget.GridLayout
    private var sheetKind: String? = null
    private val icons = LinkedHashMap<String, TextView>()

    // night mode
    private var extensionsManager: ExtensionsManager? = null
    private var nightExtension = false
    private var nightBusy = false

    // Sony-style "pro" controls
    private lateinit var proPanel: View
    private lateinit var proLabel: TextView
    private lateinit var proSlider: SeekBar
    private var activePro: String? = null
    private var style = CreativeStyle.STANDARD
    private var evIndex = 0
    private var manualIso: Int? = null
    private var manualShutterNs: Long? = null
    private var awbIndex = 0
    private var portraitF = 1.8f
    private var lookMode = 0         // 0 = Fast (1 shot), 1 = Natural (5 frames), 2 = HDR (3 exposures)
    private val naturalLook: Boolean get() = lookMode == 1
    private var proUi = false        // false = Simple camera for everyone, true = Pro tools
    private var manualFocus: Float? = null   // diopters (0 = infinity); null = auto focus
    private var minFocus = 10f
    private var peakingOn = false
    private var rawOn = false
    private var rawSupported = false
    private var rawActive = false
    private var aeAfLocked = false
    private var timerSec = 0
    @Volatile private var bursting = false
    private var burstCount = 0
    private lateinit var faceDetector: FaceDetector
    private lateinit var uiModeBtn: TextView
    private var naturalSettingsOk = true   // false if this camera refused the low-processing settings
    private var pendingPortraitZoom = false
    @Volatile private var readout = ""
    private var lastReadout = 0L
    private val isoSteps = listOf(50, 100, 200, 400, 800, 1600, 3200, 6400)
    private val shutterSteps = listOf(
        "1/4000" to 250_000L, "1/2000" to 500_000L, "1/1000" to 1_000_000L, "1/500" to 2_000_000L,
        "1/250" to 4_000_000L, "1/125" to 8_000_000L, "1/60" to 16_666_667L, "1/30" to 33_333_333L,
        "1/15" to 66_666_667L, "1/8" to 125_000_000L, "1/4" to 250_000_000L, "1/2" to 500_000_000L,
        "1\"" to 1_000_000_000L
    )
    private val awbModes = listOf(
        "Auto" to CameraMetadata.CONTROL_AWB_MODE_AUTO,
        "Daylight" to CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT,
        "Cloudy" to CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT,
        "Shade" to CameraMetadata.CONTROL_AWB_MODE_SHADE,
        "Tungsten" to CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT,
        "Fluorescent" to CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT
    )
    private val apertures = listOf(1.4f, 1.8f, 2.0f, 2.8f, 4.0f, 5.6f, 8.0f)
    private val timerTick = object : Runnable {
        override fun run() {
            if (recording == null) return
            val sec = (SystemClock.elapsedRealtime() - recordStart) / 1000
            recTimer.text = String.format(Locale.US, "● %02d:%02d", sec / 60, sec % 60)
            recTimer.postDelayed(this, 500)
        }
    }
    private val audioPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

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
        faceDetector = FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .build()
        )
        proUi = getSharedPreferences("gridcam", MODE_PRIVATE).getBoolean("proUi", false)
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        buildModeChips()
        setupTapToFocus()
        iconRow = findViewById(R.id.iconRow)
        sheet = findViewById(R.id.sheet)
        sheetTitle = findViewById(R.id.sheetTitle)
        sheetGrid = findViewById(R.id.sheetGrid)
        thumb = findViewById(R.id.thumb)
        recTimer = findViewById(R.id.recTimer)
        shutter = findViewById(R.id.shutter)
        shutter.setOnClickListener { onShutter() }
        // hold the shutter for a burst (Photo mode)
        shutter.setOnLongClickListener {
            if (captureMode == CaptureMode.PHOTO && lookMode == 0 && !rawActive && !nightBusy) { startBurst(); true } else false
        }
        shutter.setOnTouchListener { _, e ->
            if ((e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) && bursting) {
                bursting = false
            }
            false
        }
        thumb.setOnClickListener { openLast() }
        buildCaptureChips()
        proPanel = findViewById(R.id.proPanel)
        proLabel = findViewById(R.id.proLabel)
        proSlider = findViewById(R.id.proSlider)
        buildProChips()
        uiModeBtn = findViewById(R.id.uiModeBtn)
        uiModeBtn.setOnClickListener { setProUi(!proUi) }
        applyUiMode()
        Updater.checkOnStart(this)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) startCamera() else permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    // ---------- UI ----------

    private fun buildModeChips() {}

    private fun refreshChips() = refreshIcons()

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
            if (sheet.visibility == View.VISIBLE) {
                if (event.actionMasked == MotionEvent.ACTION_DOWN) hideSheet()
                return@setOnTouchListener true
            }
            scaleDetector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> pinching = false
                MotionEvent.ACTION_UP -> {
                    val pressTime = event.eventTime - event.downTime
                    if (!pinching && pressTime >= 600 && previewView.width > 0) {
                        // long-press: AE/AF lock on this spot (like Pixel / pro cameras)
                        pendingTap = PointF(event.x / previewView.width, event.y / previewView.height)
                        val point = previewView.meteringPointFactory.createPoint(event.x, event.y)
                        val flags = if (manualFocus != null) FocusMeteringAction.FLAG_AE
                        else FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
                        camera?.cameraControl?.startFocusAndMetering(
                            FocusMeteringAction.Builder(point, flags).disableAutoCancel().build()
                        )
                        aeAfLocked = true
                        overlay.aeAfLocked = true
                        lastFocusTime = SystemClock.uptimeMillis() + 3_600_000L   // don't refocus while locked
                        previewView.postDelayed({ applyCameraSettings() }, 600)
                        v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                    }
                    if (!pinching && pressTime < 400) {
                        if (aeAfLocked) {   // a normal tap releases the AE/AF lock
                            aeAfLocked = false
                            overlay.aeAfLocked = false
                            applyCameraSettings()
                        }
                        // tap: select this object (camera-frame coordinates) and focus on it
                        if (previewView.width > 0) {
                            pendingTap = PointF(event.x / previewView.width, event.y / previewView.height)
                        }
                        lastFocusTime = SystemClock.uptimeMillis()
                        lastFocusX = event.x / previewView.width.coerceAtLeast(1)
                        lastFocusY = event.y / previewView.height.coerceAtLeast(1)
                        val point = previewView.meteringPointFactory.createPoint(event.x, event.y)
                        val action = FocusMeteringAction.Builder(
                            point,
                            if (manualFocus != null) FocusMeteringAction.FLAG_AE
                            else FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
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
        updateZoomText()
    }

    private fun updateZoomText() {
        val sb = StringBuilder(zoomLabel(currentZoom))
        if (extraZoom > 1.01f) sb.append("  ·  digital crop")
        if (currentZoom >= 5f) sb.append("  ·  hold steady")
        if (proUi && readout.isNotEmpty() && captureMode != CaptureMode.SLOWMO) sb.append("\n").append(readout)
        zoomText.text = sb.toString()
    }

    // ---------- Camera ----------

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            cameraProvider = provider
            val extFuture = ExtensionsManager.getInstanceAsync(this, provider)
            extFuture.addListener({
                extensionsManager = try { extFuture.get() } catch (e: Exception) { null }
                nightExtension = try {
                    extensionsManager?.isExtensionAvailable(CameraSelector.DEFAULT_BACK_CAMERA, ExtensionMode.NIGHT) == true
                } catch (e: Exception) { false }
                if (captureMode == CaptureMode.NIGHT) bindForMode()
            }, ContextCompat.getMainExecutor(this))
            bindForMode()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun observeZoom() {
        camera?.cameraInfo?.zoomState?.observe(this) { state ->
            if (pendingPortraitZoom && state.maxZoomRatio > 1f) {
                pendingPortraitZoom = false
                maxZoom = state.maxZoomRatio
                minZoom = state.minZoomRatio
                setZoom(2f)   // ≈ 50 mm lens on an APS-C camera like the Sony a6000
            }
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
    }

    /** (Re)binds the camera for the current capture mode. */
    private fun bindForMode() {
        val provider = cameraProvider ?: return
        val ratio43 = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .build()
        provider.unbindAll()
        imageCapture = null
        videoCapture = null
        extraZoom = 1f
        currentZoom = 1f
        previewView.scaleX = 1f
        previewView.scaleY = 1f
        try {
            when (captureMode) {
                CaptureMode.NIGHT -> {
                    val ext = extensionsManager
                    val preview = Preview.Builder().setResolutionSelector(ratio43).build()
                    preview.setSurfaceProvider(previewView.surfaceProvider)
                    val capture = ImageCapture.Builder()
                        .setResolutionSelector(ratio43)
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                        .build()
                    if (nightExtension && ext != null) {
                        val nightSelector = ext.getExtensionEnabledCameraSelector(
                            CameraSelector.DEFAULT_BACK_CAMERA, ExtensionMode.NIGHT
                        )
                        camera = provider.bindToLifecycle(this, nightSelector, preview, capture)
                        overlay.updateSubject(null)
                        Toast.makeText(this, "Night: using the Pixel Night engine", Toast.LENGTH_SHORT).show()
                    } else {
                        val analysis = ImageAnalysis.Builder()
                            .setResolutionSelector(ratio43)
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                        analysis.setAnalyzer(analysisExecutor) { proxy -> analyze(proxy) }
                        camera = provider.bindToLifecycle(
                            this, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture, analysis
                        )
                        Toast.makeText(this, "Night: Grid Cam multi-frame stacking. Hold still.", Toast.LENGTH_SHORT).show()
                    }
                    imageCapture = capture
                }
                CaptureMode.PHOTO, CaptureMode.PORTRAIT, CaptureMode.VIDEO -> {
                    val previewBuilder = Preview.Builder().setResolutionSelector(ratio43)
                    attachReadout(previewBuilder)
                    val preview = previewBuilder.build()
                    preview.setSurfaceProvider(previewView.surfaceProvider)
                    val analysis = ImageAnalysis.Builder()
                        .setResolutionSelector(ratio43)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                    analysis.setAnalyzer(analysisExecutor) { proxy -> analyze(proxy) }
                    if (captureMode == CaptureMode.VIDEO) {
                        val recorder = Recorder.Builder()
                            .setAspectRatio(androidx.camera.core.AspectRatio.RATIO_4_3)
                            .build()
                        val vc = VideoCapture.withOutput(recorder)
                        camera = provider.bindToLifecycle(
                            this, CameraSelector.DEFAULT_BACK_CAMERA, preview, vc, analysis
                        )
                        videoCapture = vc
                    } else {
                        val captureBuilder = ImageCapture.Builder()
                            .setResolutionSelector(ratio43)
                            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        if (naturalLook && naturalSettingsOk) naturalProcessing(captureBuilder)
                        rawActive = false
                        try {
                            val caps = ImageCapture.getImageCaptureCapabilities(provider.getCameraInfo(CameraSelector.DEFAULT_BACK_CAMERA))
                            rawSupported = caps.supportedOutputFormats.contains(ImageCapture.OUTPUT_FORMAT_RAW_JPEG)
                            if (rawOn && rawSupported && lookMode == 0 && captureMode == CaptureMode.PHOTO) {
                                captureBuilder.setOutputFormat(ImageCapture.OUTPUT_FORMAT_RAW_JPEG)
                                rawActive = true
                            }
                        } catch (e: Exception) {
                            rawSupported = false
                        }
                        val capture = captureBuilder.build()
                        camera = provider.bindToLifecycle(
                            this, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture, analysis
                        )
                        imageCapture = capture
                    }
                }
                CaptureMode.SLOWMO -> bindSlowMotion(provider)
            }
            observeZoom()
            readMinFocus()
            startPreviewWatchdog()
            if (captureMode == CaptureMode.PORTRAIT) pendingPortraitZoom = true
            applyCameraSettings()
            applyStylePreview()
            refreshProChips()
        } catch (e: Exception) {
            Toast.makeText(this, "Could not start ${captureMode.label}: ${e.message}", Toast.LENGTH_LONG).show()
            if (captureMode != CaptureMode.PHOTO) {
                captureMode = CaptureMode.PHOTO
                refreshCaptureChips()
                bindForMode()
            }
        }
    }

    /** Slow motion: high-speed recording (120/240 fps) played back slowed down (CameraX 1.5). */
    @kotlin.OptIn(ExperimentalSessionConfig::class, ExperimentalHighSpeedVideo::class)
    private fun bindSlowMotion(provider: ProcessCameraProvider) {
        val selector = CameraSelector.DEFAULT_BACK_CAMERA
        val info = provider.getCameraInfo(selector)
        if (Recorder.getHighSpeedVideoCapabilities(info) == null) {
            throw IllegalStateException("this camera does not support slow motion for apps")
        }
        val preview = Preview.Builder().build()
        preview.setSurfaceProvider(previewView.surfaceProvider)
        val vc = VideoCapture.withOutput(Recorder.Builder().build())
        val builder = HighSpeedVideoSessionConfig.Builder(vc)
            .setPreview(preview)
            .setSlowMotionEnabled(true)
        val ranges = info.getSupportedFrameRateRanges(builder.build())
        // prefer 240 fps, else the fastest available
        val best = ranges.maxByOrNull { it.upper } ?: throw IllegalStateException("no high-speed frame rates")
        builder.setFrameRateRange(best)
        camera = provider.bindToLifecycle(this, selector, builder.build())
        videoCapture = vc
        overlay.updateSubject(null)
        Toast.makeText(this, "Slow motion ${best.upper} fps", Toast.LENGTH_SHORT).show()
    }

    // ---------- Capture modes ----------

    private fun buildCaptureChips() {
        for ((key, glyph) in listOf("mode" to "◉", "grid" to "▦", "pro" to "☰")) {
            val icon = TextView(this).apply {
                gravity = Gravity.CENTER
                textSize = 13f
                setLineSpacing(0f, 1.1f)
                setOnClickListener { showSheet(key) }
                tag = glyph
            }
            val lp = LinearLayout.LayoutParams((96 * density).toInt(), (56 * density).toInt())
            lp.marginStart = (6 * density).toInt()
            lp.marginEnd = (6 * density).toInt()
            iconRow.addView(icon, lp)
            icons[key] = icon
        }
        refreshCaptureChips()
    }

    private fun refreshCaptureChips() {
        shutter.setBackgroundResource(
            when {
                recording != null -> R.drawable.shutter_stop
                captureMode == CaptureMode.VIDEO || captureMode == CaptureMode.SLOWMO -> R.drawable.shutter_video
                else -> R.drawable.shutter
            }
        )
        refreshIcons()
    }

    /** Updates the three bottom icons with the current mode / grid / pro state. */
    private fun refreshIcons() {
        if (icons.isEmpty()) return
        val proActive = evIndex != 0 || manualIso != null || manualShutterNs != null ||
            awbIndex != 0 || style != CreativeStyle.STANDARD
        for ((key, icon) in icons) {
            val label = when (key) {
                "mode" -> captureMode.label
                "grid" -> if (overlay.userMode == GridMode.AUTO) "Auto grid" else overlay.userMode.label
                else -> if (proActive) "Pro •" else "Pro"
            }
            icon.text = icon.tag.toString() + "\n" + label
            val open = sheet.visibility == View.VISIBLE && sheetKind == key
            icon.background = GradientDrawable().apply {
                cornerRadius = 18f * density
                setColor(if (open) Color.rgb(255, 214, 0) else Color.argb(55, 255, 255, 255))
            }
            icon.setTextColor(if (open) Color.BLACK else Color.WHITE)
        }
    }

    private fun hideSheet() {
        sheet.visibility = View.GONE
        sheetKind = null
        refreshIcons()
    }

    private fun proKeysVisible(): List<String> {
        val video = captureMode == CaptureMode.VIDEO || captureMode == CaptureMode.SLOWMO
        return listOf("Look", "EV", "ISO", "Shutter", "Focus", "WB", "Style", "Aperture", "Peaking", "RAW", "Timer").filter { key ->
            when {
                key == "Look" -> captureMode == CaptureMode.PHOTO || captureMode == CaptureMode.PORTRAIT
                key == "RAW" -> captureMode == CaptureMode.PHOTO
                key == "Timer" -> !video
                key == "Aperture" -> captureMode == CaptureMode.PORTRAIT
                captureMode == CaptureMode.SLOWMO -> false
                video && key == "Style" -> false
                else -> true
            }
        }
    }

    /** Pop-up table with every option of one category. */
    private fun showSheet(kind: String) {
        if (sheet.visibility == View.VISIBLE && sheetKind == kind) {
            hideSheet()
            return
        }
        sheetKind = kind
        sheetGrid.removeAllViews()
        class Item(val label: String, val selected: Boolean, val action: () -> Unit)
        val items = ArrayList<Item>()
        when (kind) {
            "mode" -> {
                sheetTitle.text = "Camera mode"
                for (m in CaptureMode.values()) items.add(Item(m.label, m == captureMode) {
                    hideSheet(); switchMode(m)
                })
                items.add(Item(if (timerSec == 0) "⏱ Timer Off" else "⏱ Timer ${timerSec}s", timerSec > 0) {
                    timerSec = when (timerSec) { 0 -> 3; 3 -> 10; else -> 0 }
                    hideSheet()
                })
            }
            "grid" -> {
                sheetTitle.text = "Composition grid"
                for (g in GridMode.values()) items.add(Item(g.label, g == overlay.userMode) {
                    overlay.userMode = g; hideSheet()
                })
            }
            else -> {
                sheetTitle.text = if (proKeysVisible().isEmpty()) "Pro controls are off in slow motion" else "Pro controls (Sony style)"
                for (key in proKeysVisible()) items.add(Item(proValue(key), key == activePro ||
                    (key == "Look" && lookMode != 0) || (key == "Peaking" && peakingOn) ||
                    (key == "RAW" && rawOn) || (key == "Timer" && timerSec > 0)) {
                    hideSheet()
                    when (key) {
                        "Look" -> {
                            lookMode = (lookMode + 1) % 3
                            Toast.makeText(
                                this,
                                when (lookMode) {
                                    1 -> "Natural: 5 frames merged, cleaner but slower. Hold still."
                                    2 -> "HDR: 3 exposures merged, for bright skies and backlight. Hold still."
                                    else -> "Fast: instant single shot"
                                },
                                Toast.LENGTH_LONG
                            ).show()
                            bindForMode()
                        }
                        "Peaking" -> {
                            peakingOn = !peakingOn
                            if (!peakingOn) overlay.peaking = null
                        }
                        "RAW" -> {
                            if (!rawSupported) Toast.makeText(this, "This camera doesn't offer RAW to apps", Toast.LENGTH_SHORT).show()
                            else { rawOn = !rawOn; if (rawOn) lookMode = 0; bindForMode() }
                        }
                        "Timer" -> timerSec = when (timerSec) { 0 -> 3; 3 -> 10; else -> 0 }
                        else -> toggleProPanel(key)   // opens the slider (tap again to close it)
                    }
                    refreshIcons()
                })
                if (proKeysVisible().isNotEmpty()) items.add(Item("Reset all", false) { resetPro(); hideSheet() })
            }
        }
        for (item in items) {
            val cell = TextView(this).apply {
                text = item.label
                gravity = Gravity.CENTER
                textSize = 14f
                minHeight = (48 * density).toInt()
                val p = (6 * density).toInt()
                setPadding(p, p, p, p)
                background = GradientDrawable().apply {
                    cornerRadius = 14f * density
                    setColor(if (item.selected) Color.rgb(255, 214, 0) else Color.argb(45, 255, 255, 255))
                }
                setTextColor(if (item.selected) Color.BLACK else Color.WHITE)
                setOnClickListener { item.action() }
            }
            val lp = android.widget.GridLayout.LayoutParams(
                android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED),
                android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f)
            )
            lp.width = 0
            val m = (4 * density).toInt()
            lp.setMargins(m, m, m, m)
            sheetGrid.addView(cell, lp)
        }
        sheet.visibility = View.VISIBLE
        refreshIcons()
    }

    private fun resetPro() {
        manualFocus = null
        peakingOn = false
        overlay.peaking = null
        evIndex = 0
        manualIso = null
        manualShutterNs = null
        awbIndex = 0
        style = CreativeStyle.STANDARD
        portraitF = 1.8f
        activePro = null
        proPanel.visibility = View.GONE
        applyCameraSettings()
        applyStylePreview()
        refreshProChips()
    }

    private fun switchMode(m: CaptureMode) {
        if (recording != null) {
            Toast.makeText(this, "Stop recording first", Toast.LENGTH_SHORT).show()
            return
        }
        if (m == captureMode) return
        if (nightBusy) return
        captureMode = m
        activePro = null
        proPanel.visibility = View.GONE
        refreshCaptureChips()
        if ((m == CaptureMode.VIDEO || m == CaptureMode.SLOWMO) &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) audioPermission.launch(Manifest.permission.RECORD_AUDIO)
        if (m == CaptureMode.PORTRAIT) {
            Toast.makeText(this, "Portrait: tap your subject. Set the blur with Aperture.", Toast.LENGTH_SHORT).show()
        }
        bindForMode()
    }

    private fun onShutter() {
        val photo = captureMode != CaptureMode.VIDEO && captureMode != CaptureMode.SLOWMO
        if (timerSec > 0 && photo && !nightBusy) countdown(timerSec) { doShutter() } else doShutter()
    }

    /** Self-timer: big countdown with a beep each second. */
    private fun countdown(sec: Int, then: () -> Unit) {
        nightBusy = true
        val tone = try { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70) } catch (e: Exception) { null }
        fun tick(left: Int) {
            if (left <= 0) {
                tone?.release()
                nightBusy = false
                then()
                return
            }
            guidanceText.text = "Timer: $left"
            tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
            guidanceText.postDelayed({ tick(left - 1) }, 1000)
        }
        tick(sec)
    }

    private fun doShutter() {
        when (captureMode) {
            CaptureMode.PHOTO, CaptureMode.PORTRAIT -> when (lookMode) {
                1 -> takeCleanStack()
                2 -> takeHdr()
                else -> takePhoto()
            }
            CaptureMode.NIGHT -> if (nightExtension) takePhoto() else takeNightStack()
            CaptureMode.VIDEO, CaptureMode.SLOWMO -> toggleRecording()
        }
    }

    @SuppressLint("MissingPermission")
    private fun toggleRecording() {
        val active = recording
        if (active != null) {
            active.stop()
            return
        }
        val vc = videoCapture ?: return
        val prefix = if (captureMode == CaptureMode.SLOWMO) "GridCam_slowmo_" else "GridCam_video_"
        val cv = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, MediaHelper.newName(prefix))
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, MediaHelper.VIDEO_DIR)
        }
        val output = MediaStoreOutputOptions.Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(cv)
            .build()
        var pending = vc.output.prepareRecording(this, output)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) pending = pending.withAudioEnabled()
        recording = pending.start(ContextCompat.getMainExecutor(this)) { event ->
            when (event) {
                is VideoRecordEvent.Start -> {
                    recordStart = SystemClock.elapsedRealtime()
                    recTimer.visibility = View.VISIBLE
                    recTimer.post(timerTick)
                    refreshCaptureChips()
                }
                is VideoRecordEvent.Finalize -> {
                    recording = null
                    recTimer.visibility = View.GONE
                    refreshCaptureChips()
                    if (event.hasError()) {
                        Toast.makeText(this, "Video failed (error ${event.error})", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this, "Video saved to Movies/GridCam", Toast.LENGTH_SHORT).show()
                        setLast(MediaItem(event.outputResults.outputUri, true, System.currentTimeMillis()))
                    }
                }
                else -> {}
            }
        }
    }

    // ---------- Simple / Pro ----------

    private fun setProUi(on: Boolean) {
        proUi = on
        getSharedPreferences("gridcam", MODE_PRIVATE).edit().putBoolean("proUi", on).apply()
        if (!on) {   // Simple: everything automatic again
            resetPro()
            rawOn = false
            lookMode = 0
            aeAfLocked = false
            overlay.aeAfLocked = false
            bindForMode()
        }
        applyUiMode()
        Toast.makeText(
            this,
            if (on) "Pro: manual exposure, focus, peaking, histogram, RAW, HDR"
            else "Simple: point, follow the red circle, shoot",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun applyUiMode() {
        uiModeBtn.text = if (proUi) "PRO" else "Simple"
        uiModeBtn.setTextColor(if (proUi) Color.BLACK else Color.WHITE)
        uiModeBtn.background = GradientDrawable().apply {
            cornerRadius = 30f * density
            setColor(if (proUi) Color.rgb(255, 214, 0) else Color.argb(70, 255, 255, 255))
        }
        icons["pro"]?.visibility = if (proUi) View.VISIBLE else View.GONE
        if (!proUi) { proPanel.visibility = View.GONE; activePro = null }
        overlay.proUi = proUi
        updateZoomText()
        refreshIcons()
    }

    @OptIn(ExperimentalCamera2Interop::class)
    @kotlin.OptIn(ExperimentalCamera2Interop::class)
    private fun readMinFocus() {
        val cam = camera ?: return
        try {
            val v = Camera2CameraInfo.from(cam.cameraInfo)
                .getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
            if (v != null && v > 0f) minFocus = v
        } catch (e: Exception) { }
    }

    // ---------- Burst (hold the shutter) ----------

    private fun startBurst() {
        bursting = true
        burstCount = 0
        guidanceText.text = "Burst…"
        burstShot()
    }

    private fun burstShot() {
        val capture = imageCapture ?: return
        if (!bursting || burstCount >= 60) {
            bursting = false
            runOnUiThread { guidanceText.text = "Burst: $burstCount photos saved" }
            return
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, MediaHelper.newName("GridCam_burst_") + "_" + burstCount)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, MediaHelper.PHOTO_DIR)
            put(MediaStore.Images.ImageColumns.DESCRIPTION, MediaHelper.makeInfo(0f, overlay.activeGridLabel(), "Burst"))
        }
        val opts = ImageCapture.OutputFileOptions.Builder(contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values).build()
        capture.takePicture(opts, ioExecutor, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                burstCount++
                val u = output.savedUri
                runOnUiThread {
                    guidanceText.text = "Burst $burstCount"
                    if (u != null) setLast(MediaItem(u, false, System.currentTimeMillis()))
                }
                burstShot()
            }

            override fun onError(exception: ImageCaptureException) {
                bursting = false
                runOnUiThread { guidanceText.text = "Burst stopped: $burstCount photos" }
            }
        })
    }

    // ---------- HDR (3 exposures merged) ----------

    private fun takeHdr() {
        val capture = imageCapture ?: return
        val cam = camera ?: return
        if (manualIso != null || manualShutterNs != null) {
            Toast.makeText(this, "HDR needs auto exposure (ISO/Shutter on Auto). Taking a normal photo.", Toast.LENGTH_SHORT).show()
            takePhoto(); return
        }
        if (nightBusy) return
        nightBusy = true
        val step = evStep()
        val range = cam.cameraInfo.exposureState.exposureCompensationRange
        fun idx(ev: Float) = if (step <= 0f) evIndex else (evIndex + ev / step).roundToInt().coerceIn(range.lower, range.upper)
        val evs = listOf(evIndex, idx(-2f), idx(2f))
        val frames = java.util.Collections.synchronizedList(ArrayList<Bitmap>())
        val shot = CleanShot(
            tilt = overlay.straightenAngle(), grid = overlay.activeGridLabel(), style = style, mode = captureMode,
            crop = extraZoom, subject = (lockedBox ?: autoBox)?.let { RectF(it) }, topDown = gravityZ > 7f, fNumber = portraitF
        )
        guidanceText.text = "HDR: hold still… 0/3"
        fun next(i: Int) {
            if (i >= evs.size) {
                runOnUiThread { applyCameraSettings() }   // restore the user's exposure
                ioExecutor.execute {
                    val result = try {
                        if (frames.size >= 2) HdrFusion.fuse(frames.toList()) else frames.firstOrNull()
                    } catch (e: Throwable) { frames.firstOrNull() }
                    saveFinal(result, shot, "HDR (" + frames.size + " exposures)")
                }
                return
            }
            cam.cameraControl.setExposureCompensationIndex(evs[i]).addListener({
                try { Thread.sleep(350) } catch (e: InterruptedException) { }
                capture.takePicture(ioExecutor, object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        try { frames.add(toBitmap(image, 3000)) } catch (e: Throwable) { } finally { image.close() }
                        runOnUiThread { guidanceText.text = "HDR: hold still… ${i + 1}/3" }
                        next(i + 1)
                    }

                    override fun onError(exception: ImageCaptureException) { next(i + 1) }
                })
            }, ioExecutor)
        }
        flash.alpha = 0.6f
        flash.animate().alpha(0f).setDuration(250).start()
        next(0)
    }

    /** Crop (digital zoom), style, save, and make the portrait copy if needed. Runs on a background thread. */
    private fun saveFinal(input: Bitmap?, shot: CleanShot, label: String) {
        var uri: Uri? = null
        var error: String? = null
        try {
            var result = input
            if (result != null && shot.crop > 1.01f) {
                val cw = (result.width / shot.crop).toInt().coerceAtLeast(1)
                val ch = (result.height / shot.crop).toInt().coerceAtLeast(1)
                result = Bitmap.createBitmap(result, (result.width - cw) / 2, (result.height - ch) / 2, cw, ch)
            }
            if (result != null && shot.style != CreativeStyle.STANDARD) result = shot.style.apply(result)
            if (result != null) {
                uri = MediaHelper.saveJpeg(
                    contentResolver, result, MediaHelper.newName("GridCam_"),
                    MediaHelper.makeInfo(shot.tilt, shot.grid, shot.mode.label + " · " + label + " · " + shot.style.label)
                )
                if (shot.mode == CaptureMode.PORTRAIT) {
                    runOnUiThread { Toast.makeText(this, "Making portrait…", Toast.LENGTH_SHORT).show() }
                    val sc = 1800f / maxOf(result.width, result.height)
                    val src = if (sc < 1f) Bitmap.createScaledBitmap(
                        result, (result.width * sc).toInt(), (result.height * sc).toInt(), true
                    ) else result
                    val portrait = PortraitMaker.make(this, src, shot.subject, shot.fNumber, shot.topDown)
                    MediaHelper.saveJpeg(
                        contentResolver, portrait, MediaHelper.newName("GridCam_portrait_"),
                        MediaHelper.makeInfo(shot.tilt, shot.grid, "Portrait f/" + shot.fNumber + " · " + label + " · " + shot.style.label)
                    )?.let { uri = it }
                }
            }
        } catch (e: Throwable) {
            error = e.message ?: e.javaClass.simpleName
        }
        runOnUiThread {
            nightBusy = false
            val u = uri
            if (u != null) {
                Toast.makeText(this, "Saved ($label). Tap the thumbnail to review.", Toast.LENGTH_SHORT).show()
                setLast(MediaItem(u, false, System.currentTimeMillis()))
            } else {
                Toast.makeText(this, "Photo failed: $error", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ---------- Preview safety net ----------

    private var watchdogToken = 0

    /** If the preview hasn't started after 3 s, drop the low-processing camera settings and retry. */
    private fun startPreviewWatchdog() {
        val token = ++watchdogToken
        previewView.postDelayed({
            if (token != watchdogToken) return@postDelayed
            val streaming = previewView.previewStreamState.value == PreviewView.StreamState.STREAMING
            if (!streaming && naturalLook && naturalSettingsOk &&
                (captureMode == CaptureMode.PHOTO || captureMode == CaptureMode.PORTRAIT)
            ) {
                // keep the multi-frame merging, just use the camera's standard processing settings
                naturalSettingsOk = false
                Toast.makeText(
                    this,
                    "Camera didn't accept the low-processing settings; using standard settings (frame merging stays on).",
                    Toast.LENGTH_LONG
                ).show()
                bindForMode()
            }
        }, 3000)
    }

    // ---------- Natural look (clean, Sony-like) ----------

    /**
     * Asks the camera for minimal processing (no edge sharpening halos, light noise smoothing),
     * but only with settings this camera actually supports.
     */
    @OptIn(ExperimentalCamera2Interop::class)
    @kotlin.OptIn(ExperimentalCamera2Interop::class)
    private fun naturalProcessing(builder: ImageCapture.Builder) {
        val provider = cameraProvider ?: return
        try {
            val chars = Camera2CameraInfo.from(provider.getCameraInfo(CameraSelector.DEFAULT_BACK_CAMERA))
            val edgeModes = chars.getCameraCharacteristic(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES)
            val nrModes = chars.getCameraCharacteristic(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)
            val ext = Camera2Interop.Extender(builder)
            when {
                edgeModes?.contains(CameraMetadata.EDGE_MODE_OFF) == true ->
                    ext.setCaptureRequestOption(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_OFF)
                edgeModes?.contains(CameraMetadata.EDGE_MODE_FAST) == true ->
                    ext.setCaptureRequestOption(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_FAST)
            }
            when {
                nrModes?.contains(CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL) == true ->
                    ext.setCaptureRequestOption(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL)
                nrModes?.contains(CameraMetadata.NOISE_REDUCTION_MODE_FAST) == true ->
                    ext.setCaptureRequestOption(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_FAST)
            }
        } catch (e: Exception) {
            // keep the camera's default processing
        }
    }

    /** 5 frames at full resolution, aligned and merged: clean detail without smearing or halos. */
    private fun takeCleanStack() {
        val capture = imageCapture ?: return
        if (nightBusy) return
        nightBusy = true
        val total = 5
        val stacker = NightStacker(brighten = false, sharpen = 0.25f)
        val shot = CleanShot(
            tilt = overlay.straightenAngle(),
            grid = overlay.activeGridLabel(),
            style = style,
            mode = captureMode,
            crop = extraZoom,
            subject = (lockedBox ?: autoBox)?.let { RectF(it) },
            topDown = gravityZ > 7f,
            fNumber = portraitF
        )
        guidanceText.text = "Hold still… 0/$total"
        fun next(i: Int) {
            capture.takePicture(ioExecutor, object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    try {
                        stacker.add(toBitmap(image, 4096))
                    } catch (e: Throwable) {
                        // skip a bad frame
                    } finally {
                        image.close()
                    }
                    runOnUiThread { guidanceText.text = "Hold still… ${i + 1}/$total" }
                    if (i + 1 < total) next(i + 1) else finishClean(stacker, shot)
                }

                override fun onError(exception: ImageCaptureException) {
                    finishClean(stacker, shot)
                }
            })
        }
        flash.alpha = 0.6f
        flash.animate().alpha(0f).setDuration(250).start()
        next(0)
    }

    private class CleanShot(
        val tilt: Float, val grid: String, val style: CreativeStyle, val mode: CaptureMode,
        val crop: Float, val subject: RectF?, val topDown: Boolean, val fNumber: Float
    )

    private fun finishClean(stacker: NightStacker, shot: CleanShot) {
        ioExecutor.execute {
            val result = try { stacker.result() } catch (e: Throwable) { null }
            saveFinal(result, shot, "Natural (" + stacker.frames + " frames)")
        }
    }

    // ---------- Night (multi-frame stacking) ----------

    private fun takeNightStack() {
        val capture = imageCapture ?: return
        if (nightBusy) return
        nightBusy = true
        val total = 8
        val stacker = NightStacker()
        val shotTilt = overlay.straightenAngle()
        val gridName = overlay.activeGridLabel()
        val shotStyle = style
        guidanceText.text = "Night shot: hold still… 0/$total"
        fun next(i: Int) {
            capture.takePicture(ioExecutor, object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    try {
                        stacker.add(toBitmap(image))
                    } catch (e: Throwable) {
                        // skip a bad frame
                    } finally {
                        image.close()
                    }
                    runOnUiThread { guidanceText.text = "Night shot: hold still… ${i + 1}/$total" }
                    if (i + 1 < total) next(i + 1) else finishNight(stacker, shotTilt, gridName, shotStyle)
                }

                override fun onError(exception: ImageCaptureException) {
                    finishNight(stacker, shotTilt, gridName, shotStyle)
                }
            })
        }
        flash.alpha = 0.5f
        flash.animate().alpha(0f).setDuration(300).start()
        next(0)
    }

    /** JPEG ImageProxy -> upright Bitmap (max ~2400 px for memory). */
    private fun toBitmap(image: ImageProxy, maxSide: Int = 2000): Bitmap {
        val buf = image.planes[0].buffer
        val bytes = ByteArray(buf.remaining())
        buf.get(bytes)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        val rot = image.imageInfo.rotationDegrees
        if (rot == 0) return bmp
        val m = Matrix().apply { postRotate(rot.toFloat()) }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }

    private fun finishNight(stacker: NightStacker, tilt: Float, grid: String, st: CreativeStyle) {
        ioExecutor.execute {
            var uri: Uri? = null
            try {
                var result = stacker.result()
                if (result != null && st != CreativeStyle.STANDARD) result = st.apply(result)
                if (result != null) {
                    uri = MediaHelper.saveJpeg(
                        contentResolver, result, MediaHelper.newName("GridCam_night_"),
                        MediaHelper.makeInfo(tilt, grid, "Night (" + stacker.frames + " frames) · " + st.label)
                    )
                }
            } catch (e: Throwable) {
                // handled below
            }
            runOnUiThread {
                nightBusy = false
                val u = uri
                if (u != null) {
                    Toast.makeText(this, "Night photo saved (${stacker.frames} frames)", Toast.LENGTH_SHORT).show()
                    setLast(MediaItem(u, false, System.currentTimeMillis()))
                } else {
                    Toast.makeText(this, "Night photo failed", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // ---------- Sony-style pro controls ----------

    private fun buildProChips() {
        proSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) onProSlider(progress)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
    }

    private fun proValue(key: String): String = when (key) {
        "EV" -> String.format(Locale.US, "EV %+.1f", evIndex * evStep())
        "ISO" -> "ISO " + (manualIso?.toString() ?: "Auto")
        "Shutter" -> shutterSteps.firstOrNull { it.second == manualShutterNs }?.first ?: "Shutter Auto"
        "WB" -> "WB " + awbModes[awbIndex].first
        "Style" -> style.label
        "Look" -> when (lookMode) { 1 -> "Look: Natural (5 frames)"; 2 -> "Look: HDR (3 exposures)"; else -> "Look: Fast (1 shot)" }
        "Focus" -> manualFocus?.let { if (it < 0.05f) "Focus ∞" else String.format(Locale.US, "Focus %.2f m", 1f / it) } ?: "Focus Auto"
        "Peaking" -> if (peakingOn) "Peaking On" else "Peaking Off"
        "RAW" -> if (!rawSupported) "RAW n/a" else if (rawOn) "RAW+JPEG On" else "RAW Off"
        "Timer" -> if (timerSec == 0) "Timer Off" else "Timer ${timerSec}s"
        else -> "f/" + portraitF
    }

    private fun refreshProChips() = refreshIcons()

    private fun evStep(): Float = camera?.cameraInfo?.exposureState?.exposureCompensationStep?.toFloat() ?: 0f

    private fun toggleProPanel(key: String) {
        if (activePro == key) {
            activePro = null
            proPanel.visibility = View.GONE
            refreshProChips()
            return
        }
        activePro = key
        proPanel.visibility = View.VISIBLE
        when (key) {
            "EV" -> {
                val range = camera?.cameraInfo?.exposureState?.exposureCompensationRange
                val lo = range?.lower ?: 0
                val hi = range?.upper ?: 0
                proSlider.max = hi - lo
                proSlider.progress = evIndex - lo
            }
            "ISO" -> {
                proSlider.max = isoOptions().size
                proSlider.progress = manualIso?.let { isoOptions().indexOf(it) + 1 } ?: 0
            }
            "Shutter" -> {
                proSlider.max = shutterOptions().size
                proSlider.progress = manualShutterNs?.let { ns -> shutterOptions().indexOfFirst { it.second == ns } + 1 } ?: 0
            }
            "WB" -> { proSlider.max = awbModes.size - 1; proSlider.progress = awbIndex }
            "Style" -> { proSlider.max = CreativeStyle.values().size - 1; proSlider.progress = style.ordinal }
            "Aperture" -> { proSlider.max = apertures.size - 1; proSlider.progress = apertures.indexOf(portraitF).coerceAtLeast(0) }
            "Focus" -> {
                proSlider.max = 20
                proSlider.progress = manualFocus?.let { (it / minFocus * 19f).roundToInt() + 1 } ?: 0
            }
        }
        updateProLabel()
        refreshProChips()
    }

    private fun updateProLabel() {
        val key = activePro ?: return
        val hint = when (key) {
            "EV" -> "Exposure compensation (brighter / darker)"
            "ISO" -> "Sensor sensitivity: low = clean, high = brighter but grainy"
            "Shutter" -> "Shutter speed: fast freezes motion, slow lets in light"
            "WB" -> "White balance"
            "Style" -> "Creative Style (colour look)"
            "Focus" -> "Manual focus: left = auto, then far → close (turn on Peaking to see what's sharp)"
            else -> "Aperture: low f = more background blur"
        }
        proLabel.text = proValue(key) + "   ·   " + hint
    }

    @OptIn(ExperimentalCamera2Interop::class)
    @kotlin.OptIn(ExperimentalCamera2Interop::class)
    private fun isoOptions(): List<Int> {
        val range = camera?.let {
            Camera2CameraInfo.from(it.cameraInfo).getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        }
        return if (range == null) isoSteps else isoSteps.filter { it >= range.lower && it <= range.upper }
    }

    @OptIn(ExperimentalCamera2Interop::class)
    @kotlin.OptIn(ExperimentalCamera2Interop::class)
    private fun shutterOptions(): List<Pair<String, Long>> {
        val range = camera?.let {
            Camera2CameraInfo.from(it.cameraInfo).getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        }
        return if (range == null) shutterSteps else shutterSteps.filter { it.second >= range.lower && it.second <= range.upper }
    }

    private fun onProSlider(p: Int) {
        when (activePro) {
            "EV" -> {
                val lo = camera?.cameraInfo?.exposureState?.exposureCompensationRange?.lower ?: 0
                evIndex = lo + p
            }
            "ISO" -> manualIso = if (p == 0) null else isoOptions().getOrNull(p - 1)
            "Shutter" -> manualShutterNs = if (p == 0) null else shutterOptions().getOrNull(p - 1)?.second
            "WB" -> awbIndex = p.coerceIn(0, awbModes.size - 1)
            "Style" -> { style = CreativeStyle.values()[p.coerceIn(0, CreativeStyle.values().size - 1)]; applyStylePreview() }
            "Aperture" -> portraitF = apertures[p.coerceIn(0, apertures.size - 1)]
            "Focus" -> manualFocus = if (p == 0) null else minFocus * (p - 1) / 19f
        }
        if (activePro in setOf("EV", "ISO", "Shutter", "WB", "Focus")) applyCameraSettings()
        updateProLabel()
        refreshProChips()
    }

    /** Sends EV, manual ISO/shutter and white balance to the camera. */
    @OptIn(ExperimentalCamera2Interop::class)
    @kotlin.OptIn(ExperimentalCamera2Interop::class)
    private fun applyCameraSettings() {
        val cam = camera ?: return
        if (captureMode == CaptureMode.SLOWMO) return
        try {
            cam.cameraControl.setExposureCompensationIndex(evIndex)
            val c2 = Camera2CameraControl.from(cam.cameraControl)
            val b = CaptureRequestOptions.Builder()
                .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, awbModes[awbIndex].second)
            manualFocus?.let { fd ->
                b.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                b.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, fd)
            }
            if (aeAfLocked && manualIso == null && manualShutterNs == null) {
                b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, true)
            }
            if (manualIso != null || manualShutterNs != null) {
                b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
                b.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, manualIso ?: 200)
                b.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, manualShutterNs ?: 16_666_667L)
            }
            c2.setCaptureRequestOptions(b.build())
        } catch (e: Exception) {
            // some modes (e.g. extensions) don't allow manual settings
        }
    }

    /** Shows the Creative Style live on the preview. */
    private fun applyStylePreview() {
        if (style == CreativeStyle.STANDARD) {
            previewView.setLayerType(View.LAYER_TYPE_NONE, null)
        } else {
            previewView.setLayerType(View.LAYER_TYPE_HARDWARE, style.paint())
        }
    }

    /** Live exposure readout like a Sony screen: shutter · ISO · aperture · EV. */
    @OptIn(ExperimentalCamera2Interop::class)
    @kotlin.OptIn(ExperimentalCamera2Interop::class)
    private fun attachReadout(builder: Preview.Builder) {
        Camera2Interop.Extender(builder).setSessionCaptureCallback(object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult
            ) {
                val now = SystemClock.uptimeMillis()
                if (now - lastReadout < 400) return
                lastReadout = now
                val exp = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: return
                val iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0
                val ap = result.get(CaptureResult.LENS_APERTURE) ?: 0f
                val shutter = if (exp >= 500_000_000L) String.format(Locale.US, "%.1f\"", exp / 1e9)
                else "1/" + Math.round(1e9 / exp.toDouble())
                readout = String.format(Locale.US, "%s · ISO %d · f/%.2f · EV %+.1f", shutter, iso, ap, evIndex * evStep())
                runOnUiThread { updateZoomText() }
            }
        })
    }

    // ---------- Gallery thumbnail ----------

    private fun setLast(item: MediaItem?) {
        lastItem = item
        if (item == null) {
            thumb.setImageDrawable(null)
            return
        }
        ioExecutor.execute {
            val bmp = try {
                contentResolver.loadThumbnail(item.uri, Size(240, 240), null)
            } catch (e: Exception) {
                null
            }
            runOnUiThread { thumb.setImageBitmap(bmp) }
        }
    }

    private fun loadLastThumb() {
        ioExecutor.execute {
            val first = MediaHelper.listAll(contentResolver).firstOrNull()
            runOnUiThread { setLast(first) }
        }
    }

    /** Opens the last photo with its edit instructions (videos open in the video player). */
    private fun openLast() {
        val item = lastItem
        if (item == null) {
            startActivity(Intent(this, GalleryActivity::class.java))
            return
        }
        if (item.isVideo) {
            try {
                startActivity(
                    Intent(Intent.ACTION_VIEW).setDataAndType(item.uri, "video/*")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                )
            } catch (e: Exception) {
                startActivity(Intent(this, GalleryActivity::class.java))
            }
        } else {
            startActivity(Intent(this, ViewerActivity::class.java).setData(item.uri))
        }
    }

    // ---------- Subject selection + tracking ----------

    @Volatile private var pendingTap: PointF? = null      // where the user tapped (camera-frame 0..1)
    @Volatile private var selected = false
    private var lockedId: Int? = null           // ML Kit tracking id, if the detector knows the object
    @Volatile private var lockedBox: RectF? = null        // current subject box (camera-frame 0..1)
    private var lostFrames = 0
    private var frameCount = 0
    private var lastScene: SceneInfo? = null
    @Volatile private var autoBox: RectF? = null          // most prominent detected object (used if nothing is tapped)
    @Volatile private var gravityZ = 0f         // > 7 means the camera points at the ground
    private val tracker = TemplateTracker()
    private var lastFocusTime = 0L
    private var lastFocusX = 0f
    private var lastFocusY = 0f

    private class Det(val id: Int?, val box: RectF)
    private class FaceInfo(val box: RectF, val eyes: PointF)
    @Volatile private var lastFaces: List<FaceInfo> = emptyList()
    private var analysisCount = 0

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
        analysisCount++
        // Pro: focus peaking from a finer grayscale copy (every 2nd frame)
        if (proUi && peakingOn && analysisCount % 2 == 0) {
            val pk = peakingMask(SceneAnalyzer.grayAt(proxy, 180, 240), 180, 240)
            val z = extraZoom
            runOnUiThread { overlay.peaking = pk; overlay.peakingZoom = z }
        }
        val hist = if (proUi && analysisCount % 3 == 0) histogramOf(gray) else null
        val input = InputImage.fromMediaImage(media, rotation)
        val runFaces = analysisCount % 3 == 0
        detector.process(input).addOnCompleteListener(analysisExecutor) { ot ->
            val dets = if (ot.isSuccessful) ot.result.map {
                val bb = it.boundingBox
                Det(
                    it.trackingId,
                    RectF(
                        (bb.left / w).coerceIn(0f, 1f), (bb.top / h).coerceIn(0f, 1f),
                        (bb.right / w).coerceIn(0f, 1f), (bb.bottom / h).coerceIn(0f, 1f)
                    )
                )
            } else emptyList()
            if (runFaces) {
                faceDetector.process(input).addOnCompleteListener(analysisExecutor) { ft ->
                    if (ft.isSuccessful) lastFaces = ft.result.map { toFaceInfo(it, w, h) }
                    handleFrame(gray, dets, hist)
                    proxy.close()
                }
            } else {
                handleFrame(gray, dets, hist)
                proxy.close()
            }
        }
    }

    private fun toFaceInfo(f: Face, w: Float, h: Float): FaceInfo {
        val bb = f.boundingBox
        val box = RectF(
            (bb.left / w).coerceIn(0f, 1f), (bb.top / h).coerceIn(0f, 1f),
            (bb.right / w).coerceIn(0f, 1f), (bb.bottom / h).coerceIn(0f, 1f)
        )
        val le = f.getLandmark(FaceLandmark.LEFT_EYE)?.position
        val re = f.getLandmark(FaceLandmark.RIGHT_EYE)?.position
        val eyes = if (le != null && re != null) PointF((le.x + re.x) / 2f / w, (le.y + re.y) / 2f / h)
        else PointF(box.centerX(), box.top + box.height() * 0.38f)
        return FaceInfo(box, eyes)
    }

    private fun histogramOf(g: FloatArray): FloatArray {
        val bins = FloatArray(64)
        for (v in g) bins[(v * 63f).toInt().coerceIn(0, 63)] += 1f
        val mx = bins.maxOrNull()?.coerceAtLeast(1f) ?: 1f
        for (i in bins.indices) bins[i] = bins[i] / mx
        return bins
    }

    /** Focus peaking: red where the image is sharp (strong local edges). */
    private fun peakingMask(g: FloatArray, w: Int, h: Int): Bitmap {
        val out = IntArray(w * h)
        val red = Color.argb(230, 255, 40, 40)
        var sum = 0f
        val mag = FloatArray(w * h)
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            val gx = g[i + 1] - g[i - 1]
            val gy = g[i + w] - g[i - w]
            val m = abs(gx) + abs(gy)
            mag[i] = m
            sum += m
        }
        val thr = maxOf(0.12f, sum / (w * h) * 4f)
        for (i in mag.indices) if (mag[i] > thr) out[i] = red
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun handleFrame(gray: FloatArray, dets: List<Det>, hist: FloatArray?) {
        // 1. A new tap: pick the detected object under the finger, or follow the tapped spot
        pendingTap?.let { tap ->
            pendingTap = null
            runOnUiThread { overlay.clearSubject() }
            selected = true
            lostFrames = 0
            lockedId = null   // don't hand the subject to the object detector (it may switch objects)
            lockedBox = RectF(tap.x - 0.07f, tap.y - 0.05f, tap.x + 0.07f, tap.y + 0.05f)
            tracker.start(gray, tap.x, tap.y)
        }

        autoBox = dets.maxByOrNull { it.box.width() * it.box.height() }?.box?.let { RectF(it) }
        // a face (if any) is the natural subject: portrait keeps head + shoulders sharp
        val face = lastFaces.maxByOrNull { it.box.width() * it.box.height() }
        if (face != null) {
            val fb = face.box
            autoBox = RectF(
                (fb.left - fb.width() * 0.6f).coerceIn(0f, 1f), (fb.top - fb.height() * 0.3f).coerceIn(0f, 1f),
                (fb.right + fb.width() * 0.6f).coerceIn(0f, 1f), (fb.bottom + fb.height() * 2.2f).coerceIn(0f, 1f)
            )
        }

        // 2. Follow the tapped spot (pattern tracking); if it can't be found, stay where it was
        var box: RectF? = null
        if (selected) {
            val c = tracker.update(gray)
            val old = lockedBox
            if (c != null && old != null) {
                val hw = old.width() / 2f
                val hh = old.height() / 2f
                box = RectF(c[0] - hw, c[1] - hh, c[0] + hw, c[1] + hh)
                lockedBox = RectF(box)
                val fb = RectF(box)
                runOnUiThread { maybeRefocus(fb) }
            } else {
                box = old
            }
        }

        // 3. Read the surroundings and update the grid
        frameCount++
        val info = if (lastScene == null || frameCount % 3 == 0) SceneAnalyzer.analyze(gray, box) else lastScene!!
        lastScene = info
        val hy = if (info.horizonY.isNaN()) Float.NaN else 0.5f + (info.horizonY - 0.5f) * extraZoom
        val horizon = if (hy in 0f..1f) hy else Float.NaN
        var screenBox = if (selected) box?.let { toScreen(it) } else null
        var isFace = false
        if (!selected && face != null) {
            // like Pixel: focus on the face automatically and guide the eyes to the upper third
            val e = face.eyes
            screenBox = toScreen(RectF(e.x - 0.03f, e.y - 0.02f, e.x + 0.03f, e.y + 0.02f))
            isFace = true
            val fb = RectF(face.box)
            runOnUiThread { if (!aeAfLocked) maybeRefocus(fb) }
        }
        val faceFlag = isFace
        val sb = screenBox
        runOnUiThread {
            overlay.updateScene(info, horizon)
            if (hist != null) overlay.histogram = hist
            if (!proUi) overlay.histogram = null
            overlay.updateSubject(sb, faceFlag)
        }
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
        val gridName = overlay.activeGridLabel()
        val shotMode = captureMode
        val shotSubject = (lockedBox ?: autoBox)?.let { RectF(it) }
        val shotTopDown = gravityZ > 7f
        val shotStyle = style
        val shotF = portraitF
        val info = MediaHelper.makeInfo(shotTilt, gridName, shotMode.label + " · " + shotStyle.label)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, MediaHelper.PHOTO_DIR)
            put(MediaStore.Images.ImageColumns.DESCRIPTION, info)
        }
        val options = ImageCapture.OutputFileOptions.Builder(
            contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
        ).build()

        flash.alpha = 0.8f
        flash.animate().alpha(0f).setDuration(250).start()

        val callback = object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val uri = output.savedUri ?: return
                    if (contentResolver.getType(uri)?.contains("dng") == true) {
                        Toast.makeText(this@MainActivity, "RAW (DNG) saved too", Toast.LENGTH_SHORT).show()
                        return
                    }
                    // saved first (like Pixel); tap the thumbnail to see it with edit instructions
                    ioExecutor.execute {
                        if (shotCrop > 1.01f) {
                            try {
                                cropInPlace(uri, shotCrop)
                            } catch (e: Exception) {
                                // keep the uncropped photo if cropping fails
                            }
                        }
                        var shown = uri
                        if (shotStyle != CreativeStyle.STANDARD) {
                            try {
                                val full = MediaHelper.decode(contentResolver, uri, 4096)
                                val styled = shotStyle.apply(full)
                                contentResolver.openOutputStream(uri, "wt")?.use {
                                    styled.compress(Bitmap.CompressFormat.JPEG, 95, it)
                                }
                            } catch (e: Exception) {
                                // keep the unstyled photo
                            }
                        }
                        if (shotMode == CaptureMode.PORTRAIT) {
                            runOnUiThread {
                                Toast.makeText(this@MainActivity, "Making portrait…", Toast.LENGTH_SHORT).show()
                            }
                            try {
                                val src = MediaHelper.decode(contentResolver, uri, 1800)
                                val portrait = PortraitMaker.make(this@MainActivity, src, shotSubject, shotF, shotTopDown)
                                MediaHelper.saveJpeg(
                                    contentResolver, portrait, MediaHelper.newName("GridCam_portrait_"),
                                    MediaHelper.makeInfo(shotTilt, gridName, "Portrait f/" + shotF + " · " + shotStyle.label)
                                )?.let { shown = it }
                            } catch (e: Exception) {
                                runOnUiThread {
                                    Toast.makeText(this@MainActivity, "Portrait failed: ${e.message}", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, "Saved. Tap the thumbnail to review.", Toast.LENGTH_SHORT).show()
                            setLast(MediaItem(shown, false, System.currentTimeMillis()))
                        }
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Toast.makeText(this@MainActivity, "Photo failed: ${exception.message}", Toast.LENGTH_LONG).show()
                }
            }
        if (rawActive) {
            val rawValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name + "_RAW")
                put(MediaStore.MediaColumns.MIME_TYPE, "image/x-adobe-dng")
                put(MediaStore.Images.Media.RELATIVE_PATH, MediaHelper.PHOTO_DIR)
            }
            val rawOptions = ImageCapture.OutputFileOptions.Builder(
                contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, rawValues
            ).build()
            capture.takePicture(rawOptions, options, ContextCompat.getMainExecutor(this), callback)
        } else {
            capture.takePicture(options, ContextCompat.getMainExecutor(this), callback)
        }
    }

    // ---------- Horizon level ----------

    override fun onResume() {
        super.onResume()
        loadLastThumb()
        Updater.onResume(this)
        sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onPause() {
        super.onPause()
        recording?.stop()
        sensorManager.unregisterListener(this)
    }

    private var smoothRoll = Float.NaN
    private var smoothBx = 0f
    private var smoothBy = 0f

    override fun onSensorChanged(event: SensorEvent) {
        val gx = event.values[0]
        val gy = event.values[1]
        gravityZ = event.values[2]
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
