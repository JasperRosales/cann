package app.cann.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.net.HttpURLConnection
import java.net.URL

data class Update(val version: String, val notes: String, val apkUrl: String?, val pageUrl: String)

/** Checks GitHub Releases for a newer cann build. */
object Updater {
    private const val LATEST = "https://api.github.com/repos/JasperRosales/cann/releases/latest"

    fun currentVersion(ctx: Context): String =
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0"

    suspend fun check(ctx: Context): Update? = withContext(Dispatchers.IO) {
        runCatching {
            val c = URL(LATEST).openConnection() as HttpURLConnection
            c.connectTimeout = 10_000; c.readTimeout = 10_000
            c.setRequestProperty("Accept", "application/vnd.github+json")
            if (c.responseCode != 200) return@runCatching null
            val o = json.parseToJsonElement(c.inputStream.bufferedReader().use { it.readText() }).jsonObject
            val tag = o.str("tag_name")?.removePrefix("v") ?: return@runCatching null
            if (!isNewer(tag, currentVersion(ctx))) return@runCatching null
            val apk = (o["assets"] as? JsonArray)?.mapNotNull { it as? JsonObject }
                ?.firstOrNull { it.str("name")?.endsWith(".apk") == true }?.str("browser_download_url")
            val page = o.str("html_url").orEmpty()
            Update(tag, o.str("body").orEmpty(), apk, page)
        }.getOrNull()
    }

    /** Downloads the APK into cache/update, reporting 0..1 progress (or -1 if size unknown). */
    suspend fun download(ctx: Context, url: String, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "update").apply { deleteRecursively(); mkdirs() }
        val out = File(dir, "cann.apk")
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 60_000
        if (c.responseCode != 200) throw Exception("Download failed: HTTP ${c.responseCode}")
        val total = c.contentLengthLong
        c.inputStream.use { inp ->
            out.outputStream().use { o ->
                val buf = ByteArray(64 * 1024); var done = 0L
                while (true) {
                    val n = inp.read(buf); if (n < 0) break
                    o.write(buf, 0, n); done += n
                    onProgress(if (total > 0) done.toFloat() / total else -1f)
                }
            }
        }
        out
    }

    /** False until the user allows cann to install apps; [openInstallSettings] sends them there. */
    fun canInstall(ctx: Context) = ctx.packageManager.canRequestPackageInstalls()

    fun openInstallSettings(ctx: Context) = ctx.startActivity(
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    /** Hands the APK to the system installer. */
    fun install(ctx: Context, apk: File) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", apk)
        ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Numeric dotted compare: 0.10.0 > 0.9.2. */
    fun isNewer(remote: String, local: String): Boolean {
        fun parts(v: String) = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val r = parts(remote); val l = parts(local)
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }; val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }
}
