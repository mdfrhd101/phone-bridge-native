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
import android.util.Log
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
    val fallbackUrl: String = "",
    val lanDownloadUrl: String = "",
    val webReleaseUrl: String = "https://github.com/mdfrhd101/phone-bridge-native/releases/latest",
    val notes: String = "Bug fixes and performance improvements."
)

object AppUpdater {
    private const val TAG = "AppUpdater"
    private const val GITHUB_REPO = "mdfrhd101/phone-bridge-native"
    const val GITHUB_LATEST_RELEASE_PAGE = "https://github.com/$GITHUB_REPO/releases/latest"
    const val GITHUB_DIRECT_APK = "https://github.com/$GITHUB_REPO/releases/latest/download/PB.apk"
    const val LAN_APK = "http://192.168.0.105:8080/PB.apk"
    const val LAN_MANIFEST = "http://192.168.0.105:8080/version.json"

    // Multi-mirror endpoints tried in sequential priority:
    // 1. Direct GitHub Raw CDN (No rate limit, Fastly edge)
    // 2. jsDelivr Global Edge CDN (Cloudflare edge)
    // 3. Local Home Wi-Fi LAN mirror (Instant offline fallback)
    // 4. GitHub REST API (Official fallback)
    private val UPDATE_MANIFEST_URLS = listOf(
        "https://raw.githubusercontent.com/$GITHUB_REPO/main/version.json",
        "https://cdn.jsdelivr.net/gh/$GITHUB_REPO@main/version.json",
        LAN_MANIFEST,
        "https://api.github.com/repos/$GITHUB_REPO/releases/latest"
    )

    fun checkForUpdate(context: Context, silentIfNone: Boolean = false, onFound: ((UpdateInfo) -> Unit)? = null) {
        thread {
            var foundInfo: UpdateInfo? = null
            var lastErrorMessage: String? = null

            for (endpoint in UPDATE_MANIFEST_URLS) {
                try {
                    val url = URL(endpoint)
                    val conn = (url.openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        setRequestProperty("Accept", "application/json, text/plain, */*")
                        setRequestProperty("User-Agent", "PB-Android/${BuildConfig.VERSION_NAME}")
                        connectTimeout = 12000
                        readTimeout = 12000
                        instanceFollowRedirects = true
                    }

                    val code = conn.responseCode
                    if (code == 200) {
                        val bodyText = conn.inputStream.bufferedReader().use { it.readText() }
                        conn.disconnect()
                        val parsed = parseUpdateResponse(endpoint, bodyText)
                        if (parsed != null) {
                            foundInfo = parsed
                            break
                        }
                    } else {
                        conn.disconnect()
                        lastErrorMessage = "HTTP $code from $endpoint"
                    }
                } catch (e: Exception) {
                    lastErrorMessage = e.message ?: e.toString()
                    Log.w(TAG, "Failed to query mirror $endpoint: $lastErrorMessage")
                }
            }

            val currentVersion = BuildConfig.VERSION_NAME

            if (foundInfo != null) {
                if (isNewerVersion(foundInfo.versionName, currentVersion)) {
                    Handler(Looper.getMainLooper()).post {
                        onFound?.invoke(foundInfo)
                    }
                } else if (!silentIfNone) {
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(context, "PB is up to date (v$currentVersion)", Toast.LENGTH_SHORT).show()
                    }
                }
            } else if (!silentIfNone) {
                Handler(Looper.getMainLooper()).post {
                    if (context is Activity) {
                        showUpdateFailedDialog(context, lastErrorMessage)
                    } else {
                        Toast.makeText(context, "Update check failed: $lastErrorMessage", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun parseUpdateResponse(endpoint: String, body: String): UpdateInfo? {
        return try {
            val json = JSONObject(body)
            if (endpoint.contains("version.json")) {
                // version.json schema
                val vName = json.optString("versionName", "").trim().trimStart('v')
                val dUrl = json.optString("downloadUrl", GITHUB_DIRECT_APK)
                val fbUrl = json.optString("fallbackUrl", "")
                val lanUrl = json.optString("lanDownloadUrl", LAN_APK)
                val webUrl = json.optString("webReleaseUrl", GITHUB_LATEST_RELEASE_PAGE)
                val notes = json.optString("notes", "Bug fixes and performance improvements.")
                if (vName.isNotBlank()) {
                    UpdateInfo(
                        versionName = vName,
                        downloadUrl = dUrl,
                        fallbackUrl = fbUrl,
                        lanDownloadUrl = lanUrl,
                        webReleaseUrl = webUrl,
                        notes = notes
                    )
                } else null
            } else {
                // GitHub REST API schema
                val tag = json.optString("tag_name", "").trim().trimStart('v')
                val notes = json.optString("body", "Bug fixes and performance improvements.")
                var apkUrl: String? = null
                val assets = json.optJSONArray("assets")
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        val name = asset.optString("name", "")
                        if (name.endsWith(".apk")) {
                            apkUrl = asset.optString("browser_download_url")
                            if (name.equals("PB.apk", ignoreCase = true)) {
                                break
                            }
                        }
                    }
                }
                if (tag.isNotBlank()) {
                    UpdateInfo(
                        versionName = tag,
                        downloadUrl = apkUrl ?: GITHUB_DIRECT_APK,
                        fallbackUrl = GITHUB_DIRECT_APK,
                        lanDownloadUrl = LAN_APK,
                        webReleaseUrl = GITHUB_LATEST_RELEASE_PAGE,
                        notes = notes
                    )
                } else null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing update JSON", e)
            null
        }
    }

    fun isNewerVersion(remote: String, local: String): Boolean {
        val rClean = remote.trim().trimStart('v')
        val lClean = local.trim().trimStart('v')
        if (rClean == lClean) return false
        val rParts = rClean.split(".").mapNotNull { it.toIntOrNull() }
        val lParts = lClean.split(".").mapNotNull { it.toIntOrNull() }
        val maxLen = maxOf(rParts.size, lParts.size)
        for (i in 0 until maxLen) {
            val r = rParts.getOrElse(i) { 0 }
            val l = lParts.getOrElse(i) { 0 }
            if (r > l) return true
            if (r < l) return false
        }
        return false
    }

    fun promptUpdateDialog(activity: Activity, info: UpdateInfo) {
        if (activity.isFinishing || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && activity.isDestroyed)) return

        AlertDialog.Builder(activity)
            .setTitle("🚀 New PB Update Available (v${info.versionName})")
            .setMessage("A new version of PB is ready!\n\nVersion: v${info.versionName}\n\nWhat's new:\n${info.notes}\n\nDo you want to update now?")
            .setPositiveButton("Update Now") { _, _ ->
                val urls = listOfNotNull(
                    info.downloadUrl.takeIf { it.isNotBlank() },
                    info.fallbackUrl.takeIf { it.isNotBlank() },
                    info.lanDownloadUrl.takeIf { it.isNotBlank() },
                    GITHUB_DIRECT_APK
                ).distinct()
                downloadAndInstall(activity, urls, info.versionName)
            }
            .setNeutralButton("Open in Browser") { _, _ ->
                openBrowser(activity, info.webReleaseUrl)
            }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun showUpdateFailedDialog(activity: Activity, error: String?) {
        if (activity.isFinishing || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && activity.isDestroyed)) return

        AlertDialog.Builder(activity)
            .setTitle("⚠️ Update Connection Issue")
            .setMessage("Could not connect to online update servers (${error ?: "Network timeout"}).\n\nYou can open the download page directly in your browser or download over home Wi-Fi.")
            .setPositiveButton("Open Browser") { _, _ ->
                openBrowser(activity, GITHUB_LATEST_RELEASE_PAGE)
            }
            .setNeutralButton("Wi-Fi Download") { _, _ ->
                openBrowser(activity, LAN_APK)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun openBrowser(context: Context, url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "Could not open browser: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    fun downloadAndInstall(activity: Activity, apkUrl: String, versionName: String = "") {
        downloadAndInstall(activity, listOf(apkUrl), versionName)
    }

    fun downloadAndInstall(activity: Activity, urls: List<String>, versionName: String = "") {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!activity.packageManager.canRequestPackageInstalls()) {
                Toast.makeText(activity, "Please allow 'Install unknown apps' permission to install updates", Toast.LENGTH_LONG).show()
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${activity.packageName}")
                }
                activity.startActivity(intent)
                return
            }
        }

        @Suppress("DEPRECATION")
        val progressDialog = ProgressDialog(activity).apply {
            setTitle("Downloading PB Update")
            setMessage("Downloading PB ${if (versionName.isNotBlank()) "v$versionName" else ""}... Please wait.")
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            isIndeterminate = false
            max = 100
            setCancelable(false)
            show()
        }

        thread {
            var downloadedFile: File? = null
            var lastError: String? = null

            for (targetUrl in urls) {
                try {
                    var currentUrl = targetUrl
                    var conn: HttpURLConnection
                    var redirects = 0

                    while (true) {
                        conn = URL(currentUrl).openConnection() as HttpURLConnection
                        conn.instanceFollowRedirects = true
                        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android; Mobile)")
                        conn.connectTimeout = 15000
                        conn.readTimeout = 25000

                        val status = conn.responseCode
                        if (status in 301..308 && redirects < 5) {
                            val location = conn.getHeaderField("Location")
                            if (!location.isNullOrBlank()) {
                                currentUrl = location
                                conn.disconnect()
                                redirects++
                                continue
                            }
                        }
                        break
                    }

                    if (conn.responseCode == 200) {
                        val totalLength = conn.contentLength
                        val apkFile = File(activity.cacheDir, "PB-update.apk")
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
                                    if (progressDialog.isShowing) {
                                        progressDialog.progress = progress
                                    }
                                }
                            }
                        }

                        output.flush()
                        output.close()
                        input.close()
                        conn.disconnect()

                        if (apkFile.length() > 500_000) { // Valid APK check (>500KB)
                            downloadedFile = apkFile
                            break
                        }
                    } else {
                        conn.disconnect()
                        lastError = "HTTP ${conn.responseCode} from $targetUrl"
                    }
                } catch (e: Exception) {
                    lastError = e.message ?: e.toString()
                    Log.w(TAG, "Download failed from $targetUrl: $lastError")
                }
            }

            Handler(Looper.getMainLooper()).post {
                try {
                    if (progressDialog.isShowing) progressDialog.dismiss()
                } catch (_: Exception) {}

                if (downloadedFile != null) {
                    installApk(activity, downloadedFile)
                } else {
                    Toast.makeText(activity, "Download failed ($lastError). Opening browser...", Toast.LENGTH_LONG).show()
                    openBrowser(activity, GITHUB_DIRECT_APK)
                }
            }
        }
    }

    private fun installApk(activity: Activity, apkFile: File) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.packageManager.canRequestPackageInstalls()) {
                Toast.makeText(activity, "Please allow 'Install unknown apps' to complete the update", Toast.LENGTH_LONG).show()
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${activity.packageName}")
                }
                activity.startActivity(intent)
                return
            }

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
