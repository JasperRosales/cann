package app.cann.engine

import kotlinx.serialization.json.*

/**
 * Fills {{...}} placeholders in step args with earlier outputs.
 *  {{step1.data.messages.0.id}}  output of step 1 (1-based), dotted path
 *  {{prev.id}}                    output of the previous step
 *  {{now}} / {{date}}             ISO timestamp / yyyy-MM-dd
 * A string that is exactly one placeholder takes the raw JSON value.
 */
object Templates {
    private val re = Regex("""\{\{\s*([^}]+?)\s*}}""")

    fun fill(e: JsonElement, outputs: List<JsonElement>, now: java.time.ZonedDateTime): JsonElement = when (e) {
        is JsonObject -> JsonObject(e.mapValues { fill(it.value, outputs, now) })
        is JsonArray -> JsonArray(e.map { fill(it, outputs, now) })
        is JsonPrimitive -> if (!e.isString) e else {
            val s = e.content
            val whole = re.matchEntire(s)
            if (whole != null) resolve(whole.groupValues[1], outputs, now) ?: JsonNull
            else JsonPrimitive(re.replace(s) { m ->
                when (val v = resolve(m.groupValues[1], outputs, now)) {
                    null, JsonNull -> ""
                    is JsonPrimitive -> v.content
                    else -> v.toString()
                }
            })
        }
    }

    /** Every placeholder expression used anywhere in [e]. */
    fun refs(e: JsonElement): List<String> = when (e) {
        is JsonObject -> e.values.flatMap(::refs)
        is JsonArray -> e.flatMap(::refs)
        is JsonPrimitive -> if (e.isString) re.findAll(e.content).map { it.groupValues[1] }.toList() else emptyList()
    }

    /** Placeholders in [e] that resolve to nothing given [outputs]. */
    fun unresolved(e: JsonElement, outputs: List<JsonElement>, now: java.time.ZonedDateTime) =
        refs(e).distinct().filter { resolve(it, outputs, now).let { v -> v == null || v is JsonNull } }

    fun resolve(expr: String, outputs: List<JsonElement>, now: java.time.ZonedDateTime): JsonElement? {
        if (expr == "now") return JsonPrimitive(now.toOffsetDateTime().toString())
        if (expr == "date") return JsonPrimitive(now.toLocalDate().toString())
        val parts = expr.split('.')
        val root = parts.first()
        var cur: JsonElement? = when {
            root == "prev" -> outputs.lastOrNull()
            root.startsWith("step") -> root.removePrefix("step").toIntOrNull()?.let { outputs.getOrNull(it - 1) }
            else -> null
        }
        for (p in parts.drop(1)) {
            cur = when (val c = cur) {
                is JsonObject -> c[p]
                is JsonArray -> p.toIntOrNull()?.let { c.getOrNull(it) }
                else -> null
            } ?: return null
        }
        return cur
    }
}
