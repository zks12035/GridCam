package com.uwrlab.gridcam

import android.content.ContentValues
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.nio.ByteBuffer
import java.util.Locale
import kotlin.concurrent.thread

/** Simple video trimmer: choose start and end, then save a trimmed copy (no re-encoding, no quality loss). */
class VideoTrimActivity : AppCompatActivity() {

    private var uri: Uri? = null
    private lateinit var video: VideoView
    private lateinit var startBar: SeekBar
    private lateinit var endBar: SeekBar
    private lateinit var info: TextView
    private lateinit var playBtn: TextView
    private var durationMs = 0
    private val handler = Handler(Looper.getMainLooper())
    private val density by lazy { resources.displayMetrics.density }
    private fun dp(v: Int) = (v * density).toInt()

    private val ticker = object : Runnable {
        override fun run() {
            if (video.isPlaying && video.currentPosition >= endMs()) {
                video.pause(); video.seekTo(startMs()); playBtn.text = "▶"
            }
            handler.postDelayed(this, 100)
        }
    }

    private fun startMs() = (startBar.progress.toLong() * durationMs / 1000).toInt()
    private fun endMs() = (endBar.progress.toLong() * durationMs / 1000).toInt()

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        uri = intent.data ?: if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
              else intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
        if (uri == null) { finish(); return }
        setContentView(buildUi())
        video.setVideoURI(uri)
        video.setOnPreparedListener { mp ->
            durationMs = mp.duration
            mp.isLooping = false
            updateInfo()
            video.seekTo(1)
        }
        video.setOnErrorListener { _, _, _ -> Toast.makeText(this, "Can't play this video", Toast.LENGTH_SHORT).show(); true }
        handler.post(ticker)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun fmt(ms: Int): String {
        val s = ms / 1000
        return String.format(Locale.US, "%d:%02d.%d", s / 60, s % 60, (ms % 1000) / 100)
    }

    private fun updateInfo() {
        info.text = "Start ${fmt(startMs())}   •   End ${fmt(endMs())}   •   Length ${fmt(endMs() - startMs())}"
    }

    private fun button(label: String, light: Boolean, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 17f
        gravity = Gravity.CENTER
        setTextColor(if (light) Color.BLACK else Color.WHITE)
        setPadding(dp(20), dp(10), dp(20), dp(10))
        background = GradientDrawable().apply {
            cornerRadius = 30f * density
            setColor(if (light) Color.WHITE else Color.argb(70, 255, 255, 255))
        }
        setOnClickListener { onClick() }
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(dp(12), dp(28), dp(12), dp(20))
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(button("✕", false) { finish() })
        top.addView(TextView(this).apply {
            text = "Trim video"; textSize = 18f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(button("Save", true) { save() })
        root.addView(top)

        val frame = FrameLayout(this)
        video = VideoView(this)
        frame.addView(video, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        root.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(10) })

        playBtn = button("▶", false) {
            if (video.isPlaying) { video.pause(); playBtn.text = "▶" }
            else {
                if (video.currentPosition < startMs() || video.currentPosition >= endMs()) video.seekTo(startMs())
                video.start(); playBtn.text = "❚❚"
            }
        }
        root.addView(playBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(10)
        })

        info = TextView(this).apply { setTextColor(Color.WHITE); textSize = 14f; gravity = Gravity.CENTER; setPadding(0, dp(10), 0, dp(4)) }
        root.addView(info)

        fun label(t: String) = TextView(this).apply { text = t; setTextColor(Color.LTGRAY); textSize = 13f; setPadding(dp(8), dp(8), 0, 0) }
        root.addView(label("Start"))
        startBar = SeekBar(this).apply { max = 1000; progress = 0 }
        root.addView(startBar)
        root.addView(label("End"))
        endBar = SeekBar(this).apply { max = 1000; progress = 1000 }
        root.addView(endBar)

        val listener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                if (sb === startBar && p > endBar.progress - 10) sb.progress = (endBar.progress - 10).coerceAtLeast(0)
                if (sb === endBar && p < startBar.progress + 10) sb.progress = (startBar.progress + 10).coerceAtMost(1000)
                video.seekTo(if (sb === startBar) startMs() else endMs())
                updateInfo()
            }
            override fun onStartTrackingTouch(sb: SeekBar) { if (video.isPlaying) { video.pause(); playBtn.text = "▶" } }
            override fun onStopTrackingTouch(sb: SeekBar) {}
        }
        startBar.setOnSeekBarChangeListener(listener)
        endBar.setOnSeekBarChangeListener(listener)
        return root
    }

    private fun save() {
        if (durationMs <= 0) return
        val src = uri ?: return
        val startUs = startMs() * 1000L
        val endUs = endMs() * 1000L
        Toast.makeText(this, "Saving…", Toast.LENGTH_SHORT).show()
        thread {
            val ok = try { trim(src, startUs, endUs) } catch (e: Throwable) { false }
            runOnUiThread {
                Toast.makeText(this, if (ok) "Saved trimmed video to Movies/GridCam" else "Couldn't trim this video", Toast.LENGTH_LONG).show()
                if (ok) finish()
            }
        }
    }

    /** Copies the chosen part with MediaExtractor + MediaMuxer (starts at the nearest key frame). */
    private fun trim(src: Uri, startUs: Long, endUs: Long): Boolean {
        val tmp = File(cacheDir, "trim_${System.currentTimeMillis()}.mp4")
        val extractor = MediaExtractor()
        extractor.setDataSource(this, src, null)
        val muxer = MediaMuxer(tmp.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(this, src)
            val rot = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            muxer.setOrientationHint(rot)
        } catch (e: Exception) { } finally { try { retriever.release() } catch (e: Exception) { } }

        val map = HashMap<Int, Int>()
        var maxSize = 1 shl 20
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                extractor.selectTrack(i)
                map[i] = muxer.addTrack(f)
                if (f.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) maxSize = maxOf(maxSize, f.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
            }
        }
        if (map.isEmpty()) { extractor.release(); return false }
        extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        val buf = ByteBuffer.allocate(maxSize)
        val bi = MediaCodec.BufferInfo()
        muxer.start()
        var firstUs = -1L
        while (true) {
            val size = extractor.readSampleData(buf, 0)
            if (size < 0) break
            val t = extractor.sampleTime
            if (t > endUs) break
            val track = map[extractor.sampleTrackIndex]
            if (track != null) {
                if (firstUs < 0) firstUs = t
                bi.offset = 0
                bi.size = size
                bi.presentationTimeUs = (t - firstUs).coerceAtLeast(0)
                bi.flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                muxer.writeSampleData(track, buf, bi)
            }
            extractor.advance()
        }
        muxer.stop(); muxer.release(); extractor.release()

        val cv = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "GridCam_trim_${System.currentTimeMillis()}.mp4")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/GridCam")
        }
        val out = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv) ?: return false
        contentResolver.openOutputStream(out)?.use { os -> tmp.inputStream().use { it.copyTo(os) } }
        tmp.delete()
        return true
    }
}
