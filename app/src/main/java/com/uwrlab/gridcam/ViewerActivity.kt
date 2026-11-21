package com.uwrlab.gridcam

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.bottomsheet.BottomSheetDialog
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Full-screen photo viewer: swipe between Grid Cam photos, icon bar at the bottom. */
class ViewerActivity : AppCompatActivity() {

    private val items = ArrayList<MediaItem>()
    private val cache = LruCache<String, Bitmap>(12)
    private lateinit var worker: ExecutorService
    private lateinit var pager: ViewPager2
    private lateinit var infoText: TextView
    private val density by lazy { resources.displayMetrics.density }
    private var startUri: Uri? = null

    private val deleteLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) reload(keepIndex = true)
        }

    private val editLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { reload(keepIndex = false) }

    private fun dp(v: Int) = (v * density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startUri = intent.data
        worker = Executors.newFixedThreadPool(2)

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        pager = ViewPager2(this)
        root.addView(pager, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))

        // top bar: back + info
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.argb(120, 0, 0, 0))
            setPadding(dp(8), dp(8), dp(12), dp(8))
        }
        top.addView(TextView(this).apply {
            text = "←"
            textSize = 24f
            setTextColor(Color.WHITE)
            setPadding(dp(10), 0, dp(14), 0)
            setOnClickListener { finish() }
        })
        infoText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
        }
        top.addView(infoText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(top, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP
        ))

        // bottom icon bar
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.argb(150, 0, 0, 0))
            setPadding(dp(4), dp(8), dp(4), dp(14))
        }
        for ((glyph, label) in listOf(
            "✎" to "Edit", "ⓘ" to "Guide", "◎" to "Snapseed", "▣" to "Photos", "⇪" to "Share", "✕" to "Delete"
        )) {
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(0, dp(6), 0, dp(6))
                setOnClickListener { onIcon(label) }
            }
            cell.addView(TextView(this).apply {
                text = glyph
                textSize = 30f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
            })
            cell.addView(TextView(this).apply {
                text = label
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
            })
            bar.addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(bar, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM
        ))
        setContentView(root)

        pager.adapter = PageAdapter()
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) = updateInfo()
        })
        reload(keepIndex = false)
    }

    private fun current(): MediaItem? = items.getOrNull(pager.currentItem)

    private fun reload(keepIndex: Boolean) {
        val keep = pager.currentItem
        worker.execute {
            val list = MediaHelper.listAll(contentResolver)
            runOnUiThread {
                items.clear()
                items.addAll(list)
                cache.evictAll()
                pager.adapter?.notifyDataSetChanged()
                if (items.isEmpty()) { finish(); return@runOnUiThread }
                val idx = if (keepIndex) keep.coerceAtMost(items.size - 1)
                else items.indexOfFirst { it.uri == startUri }.let { if (it < 0) 0 else it }
                pager.setCurrentItem(idx, false)
                startUri = null
                updateInfo()
            }
        }
    }

    private fun updateInfo() {
        val item = current() ?: return
        if (item.isVideo) { infoText.text = "Video"; return }
        worker.execute {
            val info = MediaHelper.readInfo(contentResolver, item.uri)
            val text = listOfNotNull(info["mode"], info["grid"]?.let { "Grid: $it" }).joinToString("  ·  ")
            runOnUiThread { if (current() == item) infoText.text = text.ifEmpty { "Photo" } }
        }
    }

    private fun onIcon(label: String) {
        val item = current() ?: return
        when (label) {
            "Edit" -> {
                if (item.isVideo) { startActivity(Intent(this, VideoTrimActivity::class.java).setData(item.uri)); return }
                editLauncher.launch(Intent(this, EditorActivity::class.java).setData(item.uri))
            }
            "Guide" -> {
                if (item.isVideo) return
                showGuide(item.uri)
            }
            "Snapseed" -> openSnapseed(item.uri)
            "Photos" -> {
                val view = Intent(Intent.ACTION_VIEW).setDataAndType(item.uri, if (item.isVideo) "video/*" else "image/*")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                try { startActivity(Intent(view).setPackage("com.google.android.apps.photos")) }
                catch (e: ActivityNotFoundException) {
                    try { startActivity(view) } catch (e2: ActivityNotFoundException) { }
                }
            }
            "Share" -> {
                val send = Intent(Intent.ACTION_SEND).setType(if (item.isVideo) "video/*" else "image/jpeg")
                    .putExtra(Intent.EXTRA_STREAM, item.uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                startActivity(Intent.createChooser(send, "Share"))
            }
            "Delete" -> {
                try {
                    if (android.os.Build.VERSION.SDK_INT >= 30) {
                        val pi = MediaStore.createDeleteRequest(contentResolver, listOf(item.uri))
                        deleteLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
                    } else {
                        contentResolver.delete(item.uri, null, null)
                        reload(keepIndex = true)
                    }
                } catch (e: Exception) {
                    Toast.makeText(this, "Could not delete: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** Edit guide only when asked for, in a pop-up sheet. */
    private fun showGuide(uri: Uri) {
        val sheet = BottomSheetDialog(this)
        val text = TextView(this).apply {
            text = "Analysing photo…"
            setTextColor(Color.WHITE)
            textSize = 14f
            setLineSpacing(0f, 1.25f)
            setPadding(dp(20), dp(20), dp(20), dp(28))
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.rgb(32, 33, 36))
            addView(text)
        }
        sheet.setContentView(scroll)
        sheet.show()
        worker.execute {
            try {
                val bmp = MediaHelper.decode(contentResolver, uri, 1200)
                val info = MediaHelper.readInfo(contentResolver, uri)
                val report = PhotoAnalyzer.analyze(
                    bmp, info["tilt"]?.toFloatOrNull() ?: 0f, info["grid"] ?: "", info["mode"] ?: ""
                )
                runOnUiThread { text.text = report.tips }
            } catch (e: Exception) {
                runOnUiThread { text.text = "Could not analyse this photo." }
            }
        }
    }

    private fun openSnapseed(uri: Uri) {
        val pkg = "com.niksoftware.snapseed"
        val edit = Intent(Intent.ACTION_EDIT).setDataAndType(uri, "image/jpeg")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).setPackage(pkg)
        try { startActivity(edit) } catch (e: ActivityNotFoundException) {
            try {
                startActivity(Intent(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).setPackage(pkg))
            } catch (e2: ActivityNotFoundException) {
                Toast.makeText(this, "Snapseed is not installed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private class PageHolder(val frame: FrameLayout, val image: ZoomImageView, val play: TextView) :
        RecyclerView.ViewHolder(frame)

    private inner class PageAdapter : RecyclerView.Adapter<PageHolder>() {
        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
            val frame = FrameLayout(parent.context)
            frame.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            val img = ZoomImageView(parent.context)
            frame.addView(img, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            val play = TextView(parent.context).apply {
                text = "▶"
                textSize = 56f
                setTextColor(Color.WHITE)
                setShadowLayer(8f, 0f, 0f, Color.BLACK)
            }
            frame.addView(play, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER
            ))
            return PageHolder(frame, img, play)
        }

        override fun onBindViewHolder(holder: PageHolder, position: Int) {
            val item = items[position]
            holder.play.visibility = if (item.isVideo) View.VISIBLE else View.GONE
            holder.play.setOnClickListener {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(item.uri, "video/*")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                } catch (e: ActivityNotFoundException) { }
            }
            val key = item.uri.toString()
            holder.image.tag = key
            val cached = cache.get(key)
            if (cached != null) { holder.image.setImageBitmap(cached); return }
            holder.image.setImageDrawable(null)
            worker.execute {
                val bmp = try {
                    if (item.isVideo) contentResolver.loadThumbnail(item.uri, android.util.Size(1080, 1440), null)
                    else MediaHelper.decode(contentResolver, item.uri, 1800)
                } catch (e: Exception) { null }
                if (bmp != null) cache.put(key, bmp)
                runOnUiThread { if (holder.image.tag == key) holder.image.setImageBitmap(bmp) }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        worker.shutdown()
    }
}
