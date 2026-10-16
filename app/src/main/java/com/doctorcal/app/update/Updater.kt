package com.doctorcal.app.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.doctorcal.app.BuildConfig
import com.doctorcal.app.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val sizeBytes: Long,
    val notes: String,
)

/**
 * Self-updater backed by a GitHub release tagged "appUpdate".
 *
 * The release must contain an APK asset whose file name ends with the new version code,
 * e.g. `DoctorCalendar-12.apk` (the bundled GitHub Actions workflow does this). The app is
 * "out of date" when that number is higher than its own versionCode.
 */
class Updater(private val context: Context, private val prefs: Prefs) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** Returns the available update, or null when the app is up to date. Throws with a readable message on failure. */
    suspend fun check(): UpdateInfo? = withContext(Dispatchers.IO) {
        val repo = prefs.githubRepo.trim()
        require(repo.matches(Regex("[\\w.-]+/[\\w.-]+")) && !repo.startsWith("YOUR_GITHUB_USER")) {
            "Set your GitHub repository (owner/name) in Settings > Updates first."
        }
        val request = Request.Builder()
            .url("https://api.github.com/repos/$repo/releases/tags/$TAG")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "DoctorCalendar")
            .build()
        http.newCall(request).execute().use { response ->
            if (response.code == 404) error("No release tagged \"$TAG\" found in $repo (is the repository public?)")
            if (!response.isSuccessful) error("GitHub returned error ${response.code}")
            prefs.lastUpdateCheck = System.currentTimeMillis()

            val json = JSONObject(response.body!!.string())
            val assets = json.optJSONArray("assets") ?: error("The \"$TAG\" release has no files attached")

            var best: UpdateInfo? = null
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.getString("name")
                if (!name.endsWith(".apk", ignoreCase = true)) continue
                val code = Regex("(\\d+)\\.apk$", RegexOption.IGNORE_CASE).find(name)
                    ?.groupValues?.get(1)?.toIntOrNull() ?: continue
                if (best == null || code > best.versionCode) {
                    best = UpdateInfo(
                        versionCode = code,
                        versionName = json.optString("name").ifBlank { "build $code" },
                        apkUrl = asset.getString("browser_download_url"),
                        sizeBytes = asset.optLong("size", 0L),
                        notes = json.optString("body").trim(),
                    )
                }
            }
            val found = best ?: error("The \"$TAG\" release has no APK named like DoctorCalendar-<number>.apk")
            if (found.versionCode > BuildConfig.VERSION_CODE) found else null
        }
    }

    suspend fun download(info: UpdateInfo, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply {
            mkdirs()
            listFiles()?.forEach { it.delete() }
        }
        val target = File(dir, "DoctorCalendar-${info.versionCode}.apk")
        val request = Request.Builder().url(info.apkUrl).header("User-Agent", "DoctorCalendar").build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Download failed (${response.code})")
            val body = response.body ?: error("Empty download")
            val total = body.contentLength().takeIf { it > 0 } ?: info.sizeBytes
            body.byteStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        done += n
                        if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
        }
        // Sanity check: the file must be an APK for this very app.
        val archive = context.packageManager.getPackageArchiveInfo(target.absolutePath, 0)
        if (archive == null || archive.packageName != context.packageName) {
            target.delete()
            error("The downloaded file is not a valid Doctor Calendar update")
        }
        target
    }

    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Opens the "install unknown apps" screen for this app. */
    fun openInstallPermissionSettings() {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** Hands the APK to the system installer (Android always asks the user to confirm). */
    fun install(apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    companion object {
        const val TAG = "appUpdate"
    }
}
