package com.uwrlab.gridcam

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
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
                        .setPositiveButton("Install") { _, _ -> downloadAndInstall(activity, apkUrl) }
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

    private fun downloadAndInstall(activity: Activity, url: String) {
        Toast.makeText(activity, "Downloading update…", Toast.LENGTH_SHORT).show()
        val appCtx = activity.applicationContext
        io.execute {
            try {
                val file = File(appCtx.cacheDir, "update.apk")
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = true   // GitHub redirects to its file server (https -> https)
                conn.connectTimeout = 15000
                conn.readTimeout = 60000
                conn.inputStream.use { input -> file.outputStream().use { input.copyTo(it) } }
                install(appCtx, file)
                activity.runOnUiThread {
                    Toast.makeText(activity, "Installing update…", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                activity.runOnUiThread {
                    Toast.makeText(activity, "Update failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun install(ctx: Context, apk: File) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(ctx.packageName)
        if (Build.VERSION.SDK_INT >= 31) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("gridcam.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(ctx, UpdateReceiver::class.java)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pi = PendingIntent.getBroadcast(ctx, id, intent, flags)
            session.commit(pi.intentSender)
        }
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
