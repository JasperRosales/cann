package app.cann.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.net.HttpURLConnection
import java.net.URL

data class Update(val version: String, val notes: String, val apkUrl: String, val pageUrl: String)

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
            Update(tag, o.str("body").orEmpty(), apk ?: page, page)
        }.getOrNull()
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
