package app.cann.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.net.HttpURLConnection
import java.net.URL

/** Free-tier friendly presets; any OpenAI-compatible chat endpoint works. */
data class AiPreset(val name: String, val url: String, val model: String, val keyUrl: String)

val AI_PRESETS = listOf(
    AiPreset("Gemini", "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-flash-latest",
        "https://aistudio.google.com/apikey"),
    AiPreset("Groq", "https://api.groq.com/openai/v1", "openai/gpt-oss-120b", "https://console.groq.com/keys"),
    AiPreset("OpenRouter", "https://openrouter.ai/api/v1", "openrouter/free", "https://openrouter.ai/keys"),
)

/**
 * Builds or edits a flow from a plain-language request. The model searches Composio
 * tools itself, then calls save_flow; slugs are checked against Composio before accepting.
 */
class Assistant(private val s: Settings) {
    private val api = Composio(s.apiKey, s.userId)
    private val pretty = Json { prettyPrint = true }

    suspend fun build(request: String, current: Workflow?, log: (String) -> Unit): Workflow {
        if (s.aiKey.isBlank()) throw ComposioException("Add an AI key in Settings")
        val msgs = mutableListOf<JsonObject>(
            msg("system", SYSTEM),
            msg("user", buildString {
                if (current != null && current.steps.isNotEmpty()) {
                    append("Current flow (edit it):\n")
                    append(json.encodeToString(Workflow.serializer(), current.copy(id = "", nextRunAt = null)))
                    append("\n\n")
                }
                append(request)
            }),
        )
        repeat(16) {
            log("Thinking…")
            val m = chat(msgs)
            msgs += m
            val calls = m["tool_calls"] as? JsonArray
            if (calls.isNullOrEmpty()) {
                msgs += msg("user", "Call save_flow with the finished flow.")
                return@repeat
            }
            for (c in calls) {
                val co = c.jsonObject
                val fn = co.obj("function")!!
                val name = fn.str("name").orEmpty()
                val args = runCatching { json.parseToJsonElement(fn.str("arguments") ?: "{}").jsonObject }
                    .getOrElse { JsonObject(emptyMap()) }
                val out = runCatching {
                    when (name) {
                        "save_flow" -> {
                            log("Checking nodes…")
                            return toFlow(args, current)
                        }
                        else -> runTool(name, args, log)
                    }
                }.getOrElse { "Error: ${it.message}" }
                msgs += buildJsonObject {
                    put("role", "tool"); put("tool_call_id", co.str("id").orEmpty()); put("content", out.take(6000))
                }
            }
        }
        throw ComposioException("The AI didn't finish. Try a more specific request.")
    }

    private suspend fun runTool(name: String, a: JsonObject, log: (String) -> Unit): String = when (name) {
        "search_tools" -> {
            val q = a.str("query").orEmpty(); val kit = a.str("app")
            log("Searching ${kit ?: "all apps"}: $q")
            api.tools(kit?.lowercase(), q).take(15).joinToString("\n") { "${it.slug} — ${it.description.take(140)}" }
                .ifBlank { "No tools found. Try other words or no app filter." }
        }
        "get_tool" -> {
            val t = api.tool(a.str("slug").orEmpty())
            log("Reading ${t.slug}")
            "${t.slug}: ${t.description.take(400)}\nParams:\n" + t.params.joinToString("\n") {
                "- ${it.name} (${it.type}${if (it.required) ", required" else ""}): ${it.description.take(160)}"
            }
        }
        "connected_apps" -> {
            log("Checking connected apps")
            api.accounts().filter { it.status.equals("ACTIVE", true) }.map { it.toolkit }.distinct()
                .joinToString().ifBlank { "None connected yet." }
        }
        else -> "Unknown tool $name"
    }

    /** Validates every slug against Composio; throws so the model can fix bad ones. */
    private suspend fun toFlow(a: JsonObject, current: Workflow?): Workflow {
        val steps = (a["steps"] as? JsonArray).orEmpty().map { e ->
            val o = e.jsonObject
            val slug = o.str("tool").orEmpty()
            val t = runCatching { api.tool(slug) }.getOrElse { throw ComposioException("Unknown tool $slug, search again") }
            val args = when (val v = o["args"]) {
                is JsonObject -> v
                is JsonPrimitive -> runCatching { json.parseToJsonElement(v.content).jsonObject }.getOrNull()
                else -> null
            } ?: JsonObject(emptyMap())
            Step(t.slug, pretty.encodeToString(JsonObject.serializer(), args), o.str("note") ?: t.name, t.version,
                toolkit = t.toolkit, required = t.params.filter { it.required }.map { it.name })
        }
        if (steps.isEmpty()) throw ComposioException("A flow needs at least one step")
        val base = current ?: Workflow("", "")
        return base.copy(name = a.str("name") ?: base.name, steps = steps,
            schedule = (a["schedule"] as? JsonObject)?.let(::schedule) ?: base.schedule)
    }

    private fun schedule(o: JsonObject): Schedule {
        fun i(k: String) = (o[k] as? JsonPrimitive)?.intOrNull
        return when (o.str("type")) {
            "interval" -> Schedule.Interval((i("minutes") ?: 60).coerceAtLeast(15))
            "daily" -> Schedule.Daily((i("hour") ?: 9).coerceIn(0, 23), (i("minute") ?: 0).coerceIn(0, 59),
                (o["days"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.intOrNull?.takeIf { d -> d in 1..7 } }?.toSet().orEmpty())
            else -> Schedule.Manual
        }
    }

    private suspend fun chat(msgs: List<JsonObject>): JsonObject = withContext(Dispatchers.IO) {
        val c = URL(s.aiUrl.trimEnd('/') + "/chat/completions").openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.connectTimeout = 20_000; c.readTimeout = 120_000; c.doOutput = true
        c.setRequestProperty("Authorization", "Bearer ${s.aiKey.trim()}")
        c.setRequestProperty("Content-Type", "application/json")
        val body = buildJsonObject {
            put("model", s.aiModel); put("messages", JsonArray(msgs)); put("tools", TOOLS)
        }
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = c.responseCode
        val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        val o = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
        if (code >= 400 || o == null) {
            val err = (o?.get("error") as? JsonObject)?.str("message") ?: (o?.get("error") as? JsonArray)?.toString()
            throw ComposioException("AI HTTP $code: ${err ?: text.take(200)}")
        }
        val m = (o["choices"] as? JsonArray)?.firstOrNull()?.jsonObject?.obj("message")
            ?: throw ComposioException("AI returned no message")
        // Keep only fields chat endpoints accept back.
        JsonObject(m.filterKeys { it in setOf("role", "content", "tool_calls") }
            .let { if (it["content"] == null || it["content"] is JsonNull) it + ("content" to JsonPrimitive("")) else it })
    }

    private fun msg(role: String, content: String) = buildJsonObject { put("role", role); put("content", content) }

    companion object {
        private val SYSTEM = """
You build automation flows for an app that runs Composio tools in order.
A flow = trigger (schedule) + ordered steps. Each step calls one Composio tool slug with JSON args.
Placeholders inside string args: {{step1.data.x}} = output of step 1 (1-based, dotted path, numbers index arrays),
{{prev.data.x}} = previous step, {{now}} ISO time, {{date}} yyyy-MM-dd. Tool output looks like {"data": {...}}.
A string that is exactly one placeholder keeps the raw JSON value.
Process: use search_tools to find real slugs (never invent them), get_tool to read params, then call save_flow.
Fill every required param. If you don't know a value the user must supply (email, channel, ids), put "" so they fill it in.
Keep flows short. Days are 1=Mon..7=Sun. Interval is at least 15 minutes.
If editing an existing flow, return the whole updated flow.
""".trimIndent()

        private fun fn(name: String, desc: String, props: JsonObject, req: List<String>) = buildJsonObject {
            put("type", "function")
            putJsonObject("function") {
                put("name", name); put("description", desc)
                putJsonObject("parameters") {
                    put("type", "object"); put("properties", props)
                    putJsonArray("required") { req.forEach { add(it) } }
                }
            }
        }
        private fun str(d: String) = buildJsonObject { put("type", "string"); put("description", d) }

        private val TOOLS = buildJsonArray {
            add(fn("search_tools", "Search Composio tools by keywords, optionally within one app.", buildJsonObject {
                put("query", str("What the action does, e.g. 'send email'"))
                put("app", str("Optional app slug, e.g. gmail, slack, github"))
            }, listOf("query")))
            add(fn("get_tool", "Get a tool's parameters.", buildJsonObject { put("slug", str("Tool slug")) }, listOf("slug")))
            add(fn("connected_apps", "List apps the user has connected.", JsonObject(emptyMap()), emptyList()))
            add(fn("save_flow", "Save the finished flow. Call once at the end.", buildJsonObject {
                put("name", str("Short flow name"))
                putJsonObject("schedule") {
                    put("type", "object")
                    put("description", "type: manual | interval (minutes) | daily (hour, minute, days)")
                    putJsonObject("properties") {
                        putJsonObject("type") { put("type", "string"); putJsonArray("enum") { add("manual"); add("interval"); add("daily") } }
                        putJsonObject("minutes") { put("type", "integer") }
                        putJsonObject("hour") { put("type", "integer") }
                        putJsonObject("minute") { put("type", "integer") }
                        putJsonObject("days") { put("type", "array"); putJsonObject("items") { put("type", "integer") } }
                    }
                    putJsonArray("required") { add("type") }
                }
                putJsonObject("steps") {
                    put("type", "array")
                    putJsonObject("items") {
                        put("type", "object")
                        putJsonObject("properties") {
                            put("tool", str("Composio tool slug"))
                            put("note", str("Short label"))
                            putJsonObject("args") { put("type", "object"); put("description", "Tool arguments") }
                        }
                        putJsonArray("required") { add("tool"); add("args") }
                    }
                }
            }, listOf("name", "schedule", "steps")))
        }
    }
}
