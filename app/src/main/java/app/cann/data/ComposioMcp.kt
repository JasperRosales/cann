package app.cann.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

/**
 * Composio over MCP (connect.composio.dev/mcp), for consumer keys (ck_…) sent as
 * x-consumer-api-key. Uses the tool-router meta tools; there is no toolkit catalog,
 * so app browsing uses a curated list and actions are found via search.
 */
internal class ComposioMcp(private val key: String) {
    private val url = "https://connect.composio.dev/mcp"
    private val ids = AtomicInteger(1)

    private suspend fun rpc(method: String, params: JsonObject): JsonObject = withContext(Dispatchers.IO) {
        val id = ids.getAndIncrement()
        val body = buildJsonObject {
            put("jsonrpc", "2.0"); put("id", id); put("method", method); put("params", params)
        }
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 20_000
        c.readTimeout = 180_000
        c.doOutput = true
        c.setRequestProperty("x-consumer-api-key", key)
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("Accept", "application/json, text/event-stream")
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = c.responseCode
        val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code == 401 || code == 403) throw ComposioException("HTTP $code: consumer key rejected")
        // Body is either plain JSON or SSE ("data: {...}" lines).
        val msgs = if (text.trimStart().startsWith("{")) listOf(text)
        else text.lineSequence().filter { it.startsWith("data:") }.map { it.removePrefix("data:").trim() }.toList()
        val msg = msgs.mapNotNull { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }
            .firstOrNull { (it["id"] as? JsonPrimitive)?.intOrNull == id }
            ?: throw ComposioException("HTTP $code: ${text.take(200)}")
        msg.obj("error")?.let { throw ComposioException(it.str("message") ?: it.toString()) }
        msg.obj("result") ?: JsonObject(emptyMap())
    }

    /** Calls a meta tool and returns its parsed {successful, data, error} payload. */
    private suspend fun call(tool: String, args: JsonObject): JsonObject {
        val r = rpc("tools/call", buildJsonObject { put("name", tool); put("arguments", args) })
        val text = (r["content"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.str("text") }?.joinToString("").orEmpty()
        return runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: if (r["isError"]?.jsonPrimitive?.booleanOrNull == true) throw ComposioException(text.take(300))
            else buildJsonObject { put("data", text) }
    }

    fun toolkits(search: String): List<Toolkit> {
        val s = search.trim().lowercase()
        return KITS.filter { (slug, name) -> s.isEmpty() || s in slug || s in name.lowercase() }
            .map { (slug, name) -> Toolkit(slug, name, "", null) }
    }

    suspend fun tools(toolkit: String?, search: String): List<Tool> {
        val kitName = toolkit?.let { k -> KITS.firstOrNull { it.first == k }?.second ?: k }
        val cases = when {
            search.isNotBlank() && kitName != null -> listOf("$search in $kitName")
            search.isNotBlank() -> listOf(search)
            kitName != null -> listOf("list or search", "get details", "create or send", "update", "delete")
                .map { "$it in $kitName" }
            else -> return emptyList()
        }
        val d = call("COMPOSIO_SEARCH_TOOLS", buildJsonObject {
            putJsonArray("queries") { cases.forEach { u -> addJsonObject { put("use_case", u) } } }
        }).obj("data") ?: return emptyList()
        val slugs = LinkedHashSet<String>()
        (d["results"] as? JsonArray)?.forEach { r ->
            val o = r as? JsonObject ?: return@forEach
            listOf("primary_tool_slugs", "related_tool_slugs").forEach { k ->
                (o[k] as? JsonArray)?.forEach { it.jsonPrimitive.contentOrNull?.let(slugs::add) }
            }
        }
        val schemas = d.obj("tool_schemas")
        val all = schemas(slugs.filter { schemas?.obj(it)?.obj("input_schema") == null }) +
            slugs.mapNotNull { s -> schemas?.obj(s)?.takeIf { it.obj("input_schema") != null }?.let { s to parse(s, it) } }
        val bySlug = all.toMap()
        return slugs.mapNotNull { bySlug[it] }
            .filter { toolkit == null || it.toolkit.equals(toolkit, true) || it.slug.startsWith(toolkit.uppercase() + "_") }
    }

    suspend fun tool(slug: String): Tool = schemas(listOf(slug)).firstOrNull()?.second
        ?: throw ComposioException("Tool $slug not found")

    private suspend fun schemas(slugs: List<String>): List<Pair<String, Tool>> {
        if (slugs.isEmpty()) return emptyList()
        val m = call("COMPOSIO_GET_TOOL_SCHEMAS", buildJsonObject {
            putJsonArray("tool_slugs") { slugs.forEach { add(it) } }
        }).obj("data")?.obj("tool_schemas") ?: return emptyList()
        return slugs.mapNotNull { s -> m.obj(s)?.let { s to parse(s, it) } }
    }

    private fun parse(slug: String, o: JsonObject): Tool {
        val ip = o.obj("input_schema")
        val req = (ip?.get("required") as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty().toSet()
        val params = ip?.obj("properties")?.map { (k, v) ->
            val vo = v as? JsonObject
            Param(k, vo?.str("type") ?: "any", vo?.str("description").orEmpty(), k in req)
        }.orEmpty().sortedBy { !it.required }
        val kit = o.str("toolkit")?.lowercase() ?: slug.substringBefore('_').lowercase()
        val name = slug.removePrefix(kit.uppercase() + "_").replace('_', ' ').lowercase()
            .replaceFirstChar { it.uppercase() }
        return Tool(slug, name, o.str("description").orEmpty(), kit, null, params)
    }

    suspend fun accounts(): List<Account> = coroutineScope {
        KITS.map { it.first }.chunked(15).map { chunk -> async { listAccounts(chunk) } }.awaitAll().flatten()
    }

    private suspend fun listAccounts(kits: List<String>): List<Account> {
        val res = call("COMPOSIO_MANAGE_CONNECTIONS", buildJsonObject {
            putJsonArray("toolkits") { kits.forEach { k -> addJsonObject { put("name", k); put("action", "list") } } }
        }).obj("data")?.obj("results") ?: return emptyList()
        return res.flatMap { (kit, v) ->
            val o = v as? JsonObject ?: return@flatMap emptyList()
            val accs = (o["accounts"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
            if (accs.isEmpty()) {
                val st = o.str("status")?.uppercase()
                if (st == null || st == "NOT_CONNECTED" || st == "NONE") emptyList() else listOf(Account("", kit, st))
            } else accs.map { Account(it.str("id").orEmpty(), kit, it.str("status").orEmpty().uppercase()) }
        }
    }

    suspend fun connectLink(toolkit: String): String {
        val r = call("COMPOSIO_MANAGE_CONNECTIONS", buildJsonObject {
            putJsonArray("toolkits") { addJsonObject { put("name", toolkit); put("action", "add") } }
        })
        return findUrl(r) ?: throw ComposioException(
            r.str("error") ?: "No auth link returned: ${r.toString().take(200)}")
    }

    /** Finds the auth/redirect URL anywhere in the response. */
    private fun findUrl(e: JsonElement): String? = when (e) {
        is JsonObject -> listOf("redirect_url", "redirectUrl", "auth_url", "connect_url", "url", "link")
            .firstNotNullOfOrNull { k -> (e[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.startsWith("http") } }
            ?: e.values.firstNotNullOfOrNull(::findUrl)
        is JsonArray -> e.firstNotNullOfOrNull(::findUrl)
        else -> null
    }

    suspend fun execute(slug: String, args: JsonObject): ExecResult {
        val r = call("COMPOSIO_MULTI_EXECUTE_TOOL", buildJsonObject {
            putJsonArray("tools") { addJsonObject { put("tool_slug", slug); put("arguments", args) } }
        })
        val item = (r.obj("data")?.get("results") as? JsonArray)?.firstOrNull() as? JsonObject
            ?: return ExecResult(false, r, r.str("error") ?: "No result")
        val resp = item.obj("response")
        if (resp == null) return ExecResult(false, item, item.str("not_found_message") ?: item.str("error") ?: "Failed")
        val ok = resp["successful"]?.jsonPrimitive?.booleanOrNull ?: true
        val err = (resp["error"] as? JsonPrimitive)?.contentOrNull
        return ExecResult(ok, resp["data"] ?: JsonNull, err)
    }

    companion object {
        val KITS = listOf(
            "gmail" to "Gmail", "googlecalendar" to "Google Calendar", "googledrive" to "Google Drive",
            "googlesheets" to "Google Sheets", "googledocs" to "Google Docs", "googletasks" to "Google Tasks",
            "slack" to "Slack", "discord" to "Discord", "telegram" to "Telegram", "whatsapp" to "WhatsApp",
            "microsoft_teams" to "Microsoft Teams", "outlook" to "Outlook", "github" to "GitHub",
            "gitlab" to "GitLab", "notion" to "Notion", "linear" to "Linear", "jira" to "Jira",
            "trello" to "Trello", "asana" to "Asana", "clickup" to "ClickUp", "todoist" to "Todoist",
            "airtable" to "Airtable", "hubspot" to "HubSpot", "salesforce" to "Salesforce",
            "dropbox" to "Dropbox", "onedrive" to "OneDrive", "twitter" to "X (Twitter)",
            "linkedin" to "LinkedIn", "reddit" to "Reddit", "youtube" to "YouTube", "spotify" to "Spotify",
            "zoom" to "Zoom", "calendly" to "Calendly", "stripe" to "Stripe", "shopify" to "Shopify",
            "figma" to "Figma", "mailchimp" to "Mailchimp", "supabase" to "Supabase", "sentry" to "Sentry",
            "hackernews" to "Hacker News", "weathermap" to "OpenWeatherMap", "composio_search" to "Composio Search",
        )
    }
}
