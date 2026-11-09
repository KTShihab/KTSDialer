package com.shihabkt.ktsdialer

import android.app.Activity
import android.app.ProgressDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Manages GitHub Releases In-App Updates for KTS Dialer.
 * Checks for updates via GitHub API, downloads the release APK, and launches package installer.
 */
object AppUpdateManager {

    private const val TAG = "AppUpdateManager"
    private const val GITHUB_REPO_SLUG = "KTShihab/KTSDialer"
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun checkForUpdates(activity: Activity, manual: Boolean = false) {
        executor.execute {
            try {
                val apiUrl = "https://api.github.com/repos/$GITHUB_REPO_SLUG/releases/latest"
                val url = URL(apiUrl)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
                conn.connectTimeout = 8000
                conn.readTimeout = 8000

                val code = conn.responseCode
                if (code == 200) {
                    val responseStr = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(responseStr)

                    val tagName = json.optString("tag_name", "").removePrefix("v").trim()
                    val releaseNotes = json.optString("body", "Bug fixes and improvements.")
                    val assets = json.optJSONArray("assets")

                    var downloadUrl: String? = null
                    if (assets != null) {
                        for (i in 0 until assets.length()) {
                            val asset = assets.getJSONObject(i)
                            val name = asset.optString("name", "")
                            if (name.endsWith(".apk", ignoreCase = true)) {
                                downloadUrl = asset.optString("browser_download_url", null)
                                break
                            }
                        }
                    }

                    val currentVersion = try {
                        activity.packageManager.getPackageInfo(activity.packageName, 0).versionName ?: "1.0.0"
                    } catch (_: Exception) {
                        "1.0.0"
                    }

                    val isNewer = isVersionNewer(currentVersion, tagName)

                    mainHandler.post {
                        if (isNewer && !downloadUrl.isNullOrBlank()) {
                            showUpdateAvailableDialog(activity, tagName, releaseNotes, downloadUrl)
                        } else if (manual) {
                            Toast.makeText(activity, "You are using the latest version ($currentVersion)", Toast.LENGTH_SHORT).show()
                        }
                    }
                } else if (manual) {
                    mainHandler.post {
                        Toast.makeText(activity, "No update available", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Update check failed: ${e.message}")
                if (manual) {
                    mainHandler.post {
                        Toast.makeText(activity, "Could not check for updates. Check internet connection.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun isVersionNewer(current: String, remote: String): Boolean {
        if (remote.isBlank()) return false
        val currParts = current.split(".").mapNotNull { it.toIntOrNull() }
        val remParts = remote.split(".").mapNotNull { it.toIntOrNull() }

        val maxLen = maxOf(currParts.size, remParts.size)
        for (i in 0 until maxLen) {
            val c = currParts.getOrElse(i) { 0 }
            val r = remParts.getOrElse(i) { 0 }
            if (r > c) return true
            if (r < c) return false
        }
        return false
    }

    private fun showUpdateAvailableDialog(
        activity: Activity,
        newVersion: String,
        notes: String,
        downloadUrl: String
    ) {
        if (activity.isFinishing || activity.isDestroyed) return

        MaterialAlertDialogBuilder(activity)
            .setTitle("New Update Available (v$newVersion)")
            .setMessage("A new version of KTS Dialer is available.\n\nChanges:\n$notes\n\nWould you like to download and install it now?")
            .setPositiveButton("Update Now") { _, _ ->
                startDownloadAndInstall(activity, downloadUrl, newVersion)
            }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun startDownloadAndInstall(activity: Activity, downloadUrl: String, newVersion: String) {
        val progressDialog = ProgressDialog(activity).apply {
            setTitle("Downloading Update")
            setMessage("Downloading KTS Dialer v$newVersion...")
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            isIndeterminate = false
            max = 100
            setCancelable(false)
            show()
        }

        executor.execute {
            try {
                val url = URL(downloadUrl)
                val conn = url.openConnection() as HttpURLConnection
                conn.connect()

                val fileLength = conn.contentLength
                val updatesDir = File(activity.getExternalFilesDir(null), "updates")
                if (!updatesDir.exists()) updatesDir.mkdirs()
                val apkFile = File(updatesDir, "KTSDialer_v$newVersion.apk")

                val input: InputStream = conn.inputStream
                val output = FileOutputStream(apkFile)

                val data = ByteArray(4096)
                var total: Long = 0
                var count: Int
                while (input.read(data).also { count = it } != -1) {
                    total += count.toLong()
                    if (fileLength > 0) {
                        val progress = (total * 100 / fileLength).toInt()
                        mainHandler.post { progressDialog.progress = progress }
                    }
                    output.write(data, 0, count)
                }

                output.flush()
                output.close()
                input.close()

                mainHandler.post {
                    progressDialog.dismiss()
                    launchApkInstaller(activity, apkFile)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Download failed: ${e.message}", e)
                mainHandler.post {
                    progressDialog.dismiss()
                    Toast.makeText(activity, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun launchApkInstaller(context: Context, apkFile: File) {
        try {
            val contentUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            context.startActivity(installIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Install launch failed: ${e.message}", e)
            Toast.makeText(context, "Could not open installer: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
