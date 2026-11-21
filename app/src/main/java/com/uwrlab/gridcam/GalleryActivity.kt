package com.uwrlab.gridcam

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.LruCache
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** All Grid Cam photos and videos. Tap a photo to see it with edit instructions. */
class GalleryActivity : AppCompatActivity() {

    private val items = ArrayList<MediaItem>()
    private val cache = LruCache<String, Bitmap>(120)
    private lateinit var worker: ExecutorService
    private lateinit var grid: GridView
    private lateinit var emptyText: TextView
    private val density by lazy { resources.displayMetrics.density }

    private val mediaPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { reload() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        worker = Executors.newFixedThreadPool(2)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(12), dp(8))
        }
        top.addView(TextView(this).apply {
            text = "Grid Cam photos"
            setTextColor(Color.WHITE)
            textSize = 20f
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(Button(this).apply {
            text = "Google Photos"
            isAllCaps = false
            setOnClickListener { openGooglePhotos() }
        })
        root.addView(top)

        val frame = FrameLayout(this)
        grid = GridView(this).apply {
            numColumns = 3
            horizontalSpacing = dp(2)
            verticalSpacing = dp(2)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            adapter = MediaAdapter()
            setOnItemClickListener { _, _, pos, _ -> open(items[pos]) }
        }
        frame.addView(grid)
        emptyText = TextView(this).apply {
            text = "No photos yet. Take one with Grid Cam."
            setTextColor(Color.argb(180, 255, 255, 255))
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        frame.addView(emptyText, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        root.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        // Needed to also show photos from before the app was reinstalled
        if (Build.VERSION.SDK_INT >= 33) {
            mediaPermission.launch(
                arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
            )
        }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun dp(v: Int) = (v * density).toInt()

    private fun reload() {
        worker.execute {
            val list = MediaHelper.listAll(contentResolver)
            runOnUiThread {
                items.clear()
                items.addAll(list)
                (grid.adapter as BaseAdapter).notifyDataSetChanged()
                emptyText.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun open(item: MediaItem) {
        if (item.isVideo) {
            try {
                startActivity(
                    Intent(Intent.ACTION_VIEW).setDataAndType(item.uri, "video/*")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                )
            } catch (e: Exception) {
                Toast.makeText(this, "No video player found", Toast.LENGTH_SHORT).show()
            }
        } else {
            startActivity(Intent(this, ViewerActivity::class.java).setData(item.uri))
        }
    }

    private fun openGooglePhotos() {
        val launch = packageManager.getLaunchIntentForPackage("com.google.android.apps.photos")
        if (launch != null) startActivity(launch)
        else Toast.makeText(this, "Google Photos is not installed", Toast.LENGTH_SHORT).show()
    }

    private inner class MediaAdapter : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val size = parent.width / 3
            val cell = (convertView as? FrameLayout) ?: FrameLayout(this@GalleryActivity).apply {
                addView(ImageView(this@GalleryActivity).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setBackgroundColor(Color.DKGRAY)
                }, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                ))
                addView(TextView(this@GalleryActivity).apply {
                    text = "▶"
                    setTextColor(Color.WHITE)
                    textSize = 22f
                    setShadowLayer(4f, 0f, 0f, Color.BLACK)
                }, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER
                ))
            }
            cell.layoutParams = AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, if (size > 0) size else dp(120))
            val item = items[position]
            val img = cell.getChildAt(0) as ImageView
            val play = cell.getChildAt(1)
            play.visibility = if (item.isVideo) View.VISIBLE else View.GONE
            val key = item.uri.toString()
            img.tag = key
            val cached = cache.get(key)
            if (cached != null) {
                img.setImageBitmap(cached)
            } else {
                img.setImageDrawable(null)
                worker.execute {
                    val bmp = try {
                        contentResolver.loadThumbnail(item.uri, Size(300, 300), null)
                    } catch (e: Exception) {
                        null
                    }
                    if (bmp != null) cache.put(key, bmp)
                    runOnUiThread { if (img.tag == key) img.setImageBitmap(bmp) }
                }
            }
            return cell
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        worker.shutdown()
    }
}
