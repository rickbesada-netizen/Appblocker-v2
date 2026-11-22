package com.example.appblocker

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * In-app updater. Compares this build's number (BuildConfig.VERSION_CODE, stamped
 * by CI) against version.json on the GitHub "latest" release, and - when newer -
 * downloads the APK and hands it to the system installer. Android always shows
 * its own install prompt, so this is "download + tap Install", not silent.
 */
object Updater {
    private const val BASE =
        "https://github.com/rickbesada-netizen/Appblocker-v2/releases/download/latest"

    data class Result(val current: Int, val latest: Int, val available: Boolean)

    fun currentBuild(): Int = BuildConfig.VERSION_CODE

    /** Latest build info, or null if the release couldn't be reached. Off the main thread. */
    fun check(): Result? = try {
        val txt = httpGet("$BASE/version.json")
        if (txt == null) null else {
            val latest = JSONObject(txt).optInt("build", 0)
            Result(currentBuild(), latest, latest > currentBuild())
        }
    } catch (e: Exception) {
        null
    }

    /** Downloads the APK into the cache. Returns the file, or null on failure. Off the main thread. */
    fun downloadApk(context: Context): File? = try {
        val dir = File(context.cacheDir, "update").apply { mkdirs() }
        val out = File(dir, "AppBlocker-update.apk")
        val conn = (URL("$BASE/AppBlocker.apk").openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 20000
            readTimeout = 60000
            setRequestProperty("User-Agent", "AppBlocker")
        }
        conn.inputStream.use { input -> out.outputStream().use { output -> input.copyTo(output) } }
        if (out.length() > 0) out else null
    } catch (e: Exception) {
        null
    }

    /** Hands the downloaded APK to the system installer (shows the install prompt). */
    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    // GitHub release downloads redirect to a CDN host; follow redirects by hand.
    private fun httpGet(urlStr: String): String? {
        var url = URL(urlStr)
        var redirects = 0
        while (redirects < 6) {
            val conn = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15000
                readTimeout = 15000
                setRequestProperty("User-Agent", "AppBlocker")
            }
            val code = conn.responseCode
            if (code in 301..308) {
                val loc = conn.getHeaderField("Location")
                conn.disconnect()
                if (loc == null) return null
                url = URL(loc)
                redirects++
                continue
            }
            if (code != 200) {
                conn.disconnect()
                return null
            }
            return conn.inputStream.bufferedReader().use { it.readText() }
        }
        return null
    }
}
