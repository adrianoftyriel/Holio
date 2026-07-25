package org.holio.game

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks GitHub Releases for a newer APK than the one installed and, if the
 * user agrees, downloads and hands it to the system installer.
 *
 * Everything network-related runs on a background thread; UI work is posted
 * back to the activity. Note: the target repository (or at least its releases)
 * must be publicly downloadable — GitHub blocks anonymous access to private
 * release assets, and no token is embedded in the app.
 */
class Updater(private val activity: Activity) {

    private data class Release(val tag: String, val versionCode: Int, val apkUrl: String, val apkName: String)

    @Volatile
    private var busy = false

    /** Entry point, safe to call from the UI thread (e.g. a button tap). */
    fun checkForUpdate() {
        if (busy) return
        busy = true
        toast("Checking for updates…")
        Thread {
            try {
                val latest = fetchLatestRelease()
                when {
                    latest == null -> ui {
                        toast("Couldn't reach releases.\nIs the repository public?")
                    }
                    latest.versionCode <= installedVersionCode() -> ui {
                        toast("You're on the latest version (v1.0.${installedVersionCode()}).")
                    }
                    else -> ui { promptInstall(latest) }
                }
            } catch (e: Exception) {
                ui { toast("Update check failed: ${e.message}") }
            } finally {
                busy = false
            }
        }.start()
    }

    private fun fetchLatestRelease(): Release? {
        val url = URL("https://api.github.com/repos/$OWNER/$REPO/releases/latest")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "Holio-Updater")
            connectTimeout = 15_000
            readTimeout = 15_000
        }
        try {
            if (conn.responseCode != 200) return null
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val tag = json.getString("tag_name") // e.g. v1.0.7
            val versionCode = tag.substringAfterLast('.').toIntOrNull() ?: return null
            val assets = json.getJSONArray("assets")
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.getString("name")
                if (name.endsWith(".apk")) {
                    return Release(tag, versionCode, asset.getString("browser_download_url"), name)
                }
            }
            return null
        } finally {
            conn.disconnect()
        }
    }

    private fun promptInstall(release: Release) {
        if (activity.isFinishing) return
        AlertDialog.Builder(activity)
            .setTitle("Update available")
            .setMessage("Version ${release.tag} is available.\nDownload and install now?")
            .setPositiveButton("Update") { _, _ -> ensureCanInstall(release) }
            .setNegativeButton("Later", null)
            .show()
    }

    /** On API 26+ the user must allow installs from this app before we proceed. */
    private fun ensureCanInstall(release: Release) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !activity.packageManager.canRequestPackageInstalls()
        ) {
            toast("Allow installs from Holio, then tap Update again.")
            try {
                activity.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${activity.packageName}")
                    )
                )
            } catch (ignored: Exception) {
                // Some devices lack this settings screen; the install intent below
                // will surface its own prompt instead.
                download(release)
            }
            return
        }
        download(release)
    }

    private fun download(release: Release) {
        toast("Downloading ${release.apkName}…")
        Thread {
            try {
                val outFile = File(activity.cacheDir, "update.apk")
                URL(release.apkUrl).openStream().use { input ->
                    outFile.outputStream().use { output -> input.copyTo(output) }
                }
                ui { launchInstaller(outFile) }
            } catch (e: Exception) {
                ui { toast("Download failed: ${e.message}") }
            }
        }.start()
    }

    private fun launchInstaller(apk: File) {
        if (activity.isFinishing) return
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            activity.startActivity(intent)
        } catch (e: Exception) {
            toast("Couldn't open installer: ${e.message}")
        }
    }

    @Suppress("DEPRECATION")
    private fun installedVersionCode(): Int =
        activity.packageManager.getPackageInfo(activity.packageName, 0).versionCode

    private fun ui(block: () -> Unit) = activity.runOnUiThread {
        if (!activity.isFinishing) block()
    }

    private fun toast(message: String) =
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()

    companion object {
        private const val OWNER = "adrianoftyriel"
        private const val REPO = "Holio"
    }
}
