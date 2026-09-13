package com.phonerelay.phonebridge

import android.app.Activity
import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

data class UpdateInfo(
    val versionName: String,
    val downloadUrl: String,
    val notes: String
)

object AppUpdater {
    private const val GITHUB_REPO = "mdfrhd101/phone-bridge-native"
    private const val RELEASES_API = "https://api.github.com/repos/$GITHUB_REPO/releases/latest"

    fun checkForUpdate(context: Context, silentIfNone: Boolean = false, onFound: ((UpdateInfo) -> Unit)? = null) {
        thread {
            try {
                val url = URL(RELEASES_API)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
                conn.setRequestProperty("User-Agent", "PhoneBridge-Android")
                conn.connectTimeout = 7000
                conn.readTimeout = 7000

                if (conn.responseCode == 200) {
                    val response = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(response)
                    val tag = json.optString("tag_name", "").trimStart('v')
                    val notes = json.optString("body", "Bug fixes and improvements.")

                    var apkUrl: String? = null
                    val assets = json.optJSONArray("assets")
                    if (assets != null) {
                        for (i in 0 until assets.length()) {
                            val asset = assets.getJSONObject(i)
                            val name = asset.optString("name", "")
                            if (name.endsWith(".apk")) {
                                apkUrl = asset.optString("browser_download_url")
                                break
                            }
                        }
                    }

                    val currentVersion = BuildConfig.VERSION_NAME
                    if (apkUrl != null && isNewerVersion(tag, currentVersion)) {
                        val info = UpdateInfo(
                            versionName = tag,
                            downloadUrl = apkUrl,
                            notes = notes
                        )
                        Handler(Looper.getMainLooper()).post {
                            onFound?.invoke(info)
                        }
                    } else if (!silentIfNone) {
                        Handler(Looper.getMainLooper()).post {
                            Toast.makeText(context, "PhoneBridge is up to date (v$currentVersion)", Toast.LENGTH_SHORT).show()
                        }
                    }
                } else if (!silentIfNone) {
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(context, "No releases found on GitHub yet.", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                if (!silentIfNone) {
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(context, "Update check failed: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun isNewerVersion(remote: String, local: String): Boolean {
        if (remote == local) return false
        val rParts = remote.split(".").mapNotNull { it.toIntOrNull() }
        val lParts = local.split(".").mapNotNull { it.toIntOrNull() }
        val maxLen = maxOf(rParts.size, lParts.size)
        for (i in 0 until maxLen) {
            val r = rParts.getOrElse(i) { 0 }
            val l = lParts.getOrElse(i) { 0 }
            if (r > l) return true
            if (r < l) return false
        }
        return remote != local
    }

    fun promptUpdateDialog(activity: Activity, info: UpdateInfo) {
        AlertDialog.Builder(activity)
            .setTitle("🚀 New Update Available (v${info.versionName})")
            .setMessage("A new version of PhoneBridge has been pushed to GitHub!\n\nRelease notes:\n${info.notes}\n\nDo you want to update now?")
            .setPositiveButton("Update Now") { _, _ ->
                downloadAndInstall(activity, info.downloadUrl)
            }
            .setNegativeButton("Later", null)
            .show()
    }

    fun downloadAndInstall(activity: Activity, apkUrl: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!activity.packageManager.canRequestPackageInstalls()) {
                Toast.makeText(activity, "Please allow permission to install updates", Toast.LENGTH_LONG).show()
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${activity.packageName}")
                }
                activity.startActivity(intent)
                return
            }
        }

        @Suppress("DEPRECATION")
        val progressDialog = ProgressDialog(activity).apply {
            setTitle("Downloading Update")
            setMessage("Downloading PhoneBridge v... Please wait.")
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            isIndeterminate = false
            max = 100
            setCancelable(false)
            show()
        }

        thread {
            try {
                val url = URL(apkUrl)
                var conn = url.openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = true

                var status = conn.responseCode
                if (status == HttpURLConnection.HTTP_MOVED_TEMP ||
                    status == HttpURLConnection.HTTP_MOVED_PERM ||
                    status == 307 || status == 308) {
                    val newUrl = conn.getHeaderField("Location")
                    conn = URL(newUrl).openConnection() as HttpURLConnection
                }

                val totalLength = conn.contentLength
                val apkFile = File(activity.cacheDir, "PhoneBridge-update.apk")
                if (apkFile.exists()) apkFile.delete()

                val input = conn.inputStream
                val output = FileOutputStream(apkFile)

                val buffer = ByteArray(8192)
                var bytesRead: Int
                var totalBytesRead = 0L

                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    totalBytesRead += bytesRead
                    if (totalLength > 0) {
                        val progress = ((totalBytesRead * 100) / totalLength).toInt()
                        Handler(Looper.getMainLooper()).post {
                            progressDialog.progress = progress
                        }
                    }
                }

                output.flush()
                output.close()
                input.close()

                Handler(Looper.getMainLooper()).post {
                    progressDialog.dismiss()
                    installApk(activity, apkFile)
                }

            } catch (e: Exception) {
                Handler(Looper.getMainLooper()).post {
                    progressDialog.dismiss()
                    Toast.makeText(activity, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun installApk(activity: Activity, apkFile: File) {
        try {
            val contentUri = FileProvider.getUriForFile(
                activity,
                "${activity.packageName}.provider",
                apkFile
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
            }

            activity.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(activity, "Failed to launch installer: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
