package com.uwrlab.gridcam

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.app.DownloadManager
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import android.os.Build
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Self-updater: every GitHub build is published as a release "build-N".
 * The app compares N with its own version, downloads the APK and installs it.
 */
object Updater {
    private const val API = "https://api.github.com/repos/zks12035/GridCam/releases/latest"
    private const val VERSION_OFFSET = 100   // versionCode = GitHub run number + 100
    private val io = Executors.newSingleThreadExecutor()
    @Volatile private var checkedThisRun = false

    fun checkOnStart(activity: Activity) {
        if (checkedThisRun) return
        checkedThisRun = true
        io.execute {
            try {
                val json = JSONObject(httpText(API))
                val tag = json.optString("tag_name")                // e.g. "build-12"
                val remote = tag.substringAfter("build-", "").toIntOrNull() ?: return@execute
                val remoteCode = remote + VERSION_OFFSET
                val local = currentVersion(activity)
                if (remoteCode <= local) return@execute
                val assets = json.optJSONArray("assets") ?: return@execute
                var url: String? = null
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name").endsWith(".apk")) url = a.optString("browser_download_url")
                }
                val apkUrl = url ?: return@execute
                activity.runOnUiThread {
                    if (activity.isFinishing) return@runOnUiThread
                    AlertDialog.Builder(activity)
                        .setTitle("Grid Cam update available")
                        .setMessage("Build $remote is ready (you have build ${local - VERSION_OFFSET}). Install now?")
                        .setPositiveButton("Install") { _, _ -> downloadAndInstall(activity, apkUrl, remote) }
                        .setNegativeButton("Later", null)
                        .show()
                }
            } catch (e: Exception) {
                // offline or GitHub not reachable: try again next launch
            }
        }
    }

    private fun currentVersion(ctx: Context): Long =
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode

    private fun httpText(u: String): String {
        val c = URL(u).openConnection() as HttpURLConnection
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.connectTimeout = 10000
        c.readTimeout = 15000
        return c.inputStream.bufferedReader().use { it.readText() }
    }

    @Volatile private var pendingInstall: Uri? = null

    /** Download with Android's DownloadManager (shows a progress notification), then open the installer. */
    private fun downloadAndInstall(activity: Activity, url: String, build: Int) {
        val dm = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val req = DownloadManager.Request(Uri.parse(url))
            .setTitle("Grid Cam update")
            .setDescription("Build $build")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(activity, Environment.DIRECTORY_DOWNLOADS, "GridCam-build-$build.apk")
        val id = try {
            dm.enqueue(req)
        } catch (e: Exception) {
            Toast.makeText(activity, "Update download failed: ${e.message}", Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(activity, "Downloading update… (see the notification)", Toast.LENGTH_LONG).show()
        io.execute {
            while (true) {
                Thread.sleep(800)
                var status = -1
                var reason = 0
                dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
                    if (c.moveToFirst()) {
                        status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                        reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                    }
                }
                when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        val uri = dm.getUriForDownloadedFile(id)
                        activity.runOnUiThread { launchInstaller(activity, uri) }
                        return@execute
                    }
                    DownloadManager.STATUS_FAILED, -1 -> {
                        activity.runOnUiThread {
                            Toast.makeText(activity, "Update download failed (code $reason). Try again later.", Toast.LENGTH_LONG).show()
                        }
                        return@execute
                    }
                }
            }
        }
    }

    /** Opens Android's installer; asks for the "install unknown apps" permission first if needed. */
    private fun launchInstaller(activity: Activity, uri: Uri?) {
        if (uri == null) return
        if (!activity.packageManager.canRequestPackageInstalls()) {
            pendingInstall = uri
            Toast.makeText(activity, "Allow Grid Cam to install updates, then press Back", Toast.LENGTH_LONG).show()
            activity.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + activity.packageName))
            )
            return
        }
        pendingInstall = null
        val install = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            activity.startActivity(install)
        } catch (e: Exception) {
            Toast.makeText(activity, "Could not open the installer: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /** Call from onResume: continues the install after the user allowed it in Settings. */
    fun onResume(activity: Activity) {
        val uri = pendingInstall ?: return
        if (activity.packageManager.canRequestPackageInstalls()) launchInstaller(activity, uri)
    }
}

/** Receives the install result; shows Android's confirmation screen when it is needed. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(confirm)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                Toast.makeText(context, "Grid Cam updated", Toast.LENGTH_SHORT).show()
            }
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "unknown error"
                Toast.makeText(context, "Update failed: $msg", Toast.LENGTH_LONG).show()
            }
        }
    }
}
