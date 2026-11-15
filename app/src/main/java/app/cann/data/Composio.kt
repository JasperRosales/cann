package app.cann.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class ComposioException(msg: String) : Exception(msg)

data class Toolkit(val slug: String, val name: String, val description: String, val logo: String?)
data class Param(val name: String, val type: String, val description: String, val required: Boolean)
data class Tool(
    val slug: String, val name: String, val description: String,
    val toolkit: String, val version: String?, val params: List<Param>,
)
data class Account(val id: String, val toolkit: String, val status: String)
data class ExecResult(val ok: Boolean, val data: JsonElement, val error: String?)

/** Minimal Composio v3 REST client. */
class Composio(private val apiKey: String, private val userId: String) {
    private val base = "https://backend.composio.dev/api/v3"

    private suspend fun call(method: String, path: String, body: JsonElement? = null): JsonObject =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank()) throw ComposioException("Set your Composio API key in Settings")
            val c = URL(base + path).openConnection() as HttpURLConnection
            c.requestMethod = method
            c.connectTimeout = 20_000
            c.readTimeout = 120_000
            // Project keys (ak_) use x-api-key; user keys (uak_) use x-user-api-key.
            c.setRequestProperty(if (apiKey.startsWith("uak_")) "x-user-api-key" else "x-api-key", apiKey)
            c.setRequestProperty("Accept", "application/json")
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val code = c.responseCode
            val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            if (code >= 400) {
                val msg = obj?.get("error")?.let { e -> (e as? JsonObject)?.str("message") ?: e.toString() }
                throw ComposioException("HTTP $code: ${msg ?: text.take(200)}")
            }
            obj ?: JsonObject(emptyMap())
        }

    private fun q(s: String) = URLEncoder.encode(s, "UTF-8")

    suspend fun toolkits(search: String = ""): List<Toolkit> {
        val r = call("GET", "/toolkits?limit=50" + if (search.isNotBlank()) "&search=${q(search)}" else "")
        return r.items().map {
            val meta = it.obj("meta")
            Toolkit(it.str("slug").orEmpty(), it.str("name").orEmpty(), meta?.str("description").orEmpty(), meta?.str("logo"))
        }
    }

    suspend fun tools(toolkit: String?, search: String = ""): List<Tool> {
        val p = buildString {
            append("/tools?limit=50")
            if (!toolkit.isNullOrBlank()) append("&toolkit_slug=${q(toolkit)}")
            if (search.isNotBlank()) append("&search=${q(search)}")
        }
        return call("GET", p).items().map(::parseTool)
    }

    suspend fun tool(slug: String): Tool = parseTool(call("GET", "/tools/${q(slug)}"))

    private fun parseTool(o: JsonObject): Tool {
        val ip = o.obj("input_parameters")
        val req = ip?.get("required")?.let { r -> (r as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull } }.orEmpty().toSet()
        val params = ip?.obj("properties")?.map { (k, v) ->
            val vo = v as? JsonObject
            Param(k, vo?.str("type") ?: "any", vo?.str("description").orEmpty(), k in req)
        }.orEmpty().sortedBy { !it.required }
        return Tool(
            o.str("slug").orEmpty(), o.str("name").orEmpty(), o.str("description").orEmpty(),
            o.obj("toolkit")?.str("slug").orEmpty(), o.str("version"), params,
        )
    }

    suspend fun accounts(): List<Account> =
        call("GET", "/connected_accounts?limit=100&user_ids=${q(userId)}").items().map {
            Account(it.str("id").orEmpty(), it.obj("toolkit")?.str("slug").orEmpty(), it.str("status").orEmpty())
        }

    /** Returns a URL the user opens to authorize [toolkit] using Composio-managed auth. */
    suspend fun connectLink(toolkit: String): String {
        val authId = call("GET", "/auth_configs?limit=1&toolkit_slug=${q(toolkit)}").items().firstOrNull()?.str("id")
            ?: call("POST", "/auth_configs", buildJsonObject {
                putJsonObject("toolkit") { put("slug", toolkit) }
                putJsonObject("auth_config") { put("type", "use_composio_managed_auth") }
            }).let { it.obj("auth_config")?.str("id") ?: it.str("id") }
            ?: throw ComposioException("Couldn't create an auth config for $toolkit")
        val r = call("POST", "/connected_accounts/link", buildJsonObject {
            put("auth_config_id", authId)
            put("user_id", userId)
        })
        return r.str("redirect_url") ?: r.str("redirectUrl") ?: throw ComposioException("No redirect URL returned")
    }

    suspend fun execute(slug: String, args: JsonObject, version: String?): ExecResult {
        val r = call("POST", "/tools/execute/${q(slug)}", buildJsonObject {
            put("user_id", userId)
            put("arguments", args)
            if (version != null) put("version", version)
        })
        val ok = r["successful"]?.jsonPrimitive?.booleanOrNull ?: (r["error"] == null || r["error"] is JsonNull)
        val err = (r["error"] as? JsonPrimitive)?.contentOrNull
        return ExecResult(ok, r["data"] ?: JsonNull, err)
    }
}

private fun JsonObject.items() = (this["items"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
internal fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
internal fun JsonObject.obj(k: String) = this[k] as? JsonObject
