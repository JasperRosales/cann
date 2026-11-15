package app.cann.engine

import app.cann.data.Step
import app.cann.data.json
import kotlinx.serialization.json.*

/** A problem found on a node before (or after) it runs. */
data class Issue(val error: Boolean, val message: String, val hint: String? = null)

/** Static checks for a node so you can see what to fix without running it. */
object Lint {
    fun check(steps: List<Step>, i: Int): List<Issue> {
        val s = steps[i]
        val out = mutableListOf<Issue>()
        val el = runCatching { json.parseToJsonElement(s.args.ifBlank { "{}" }) }
            .getOrElse { return listOf(Issue(true, "Arguments aren't valid JSON", it.message?.lineSequence()?.firstOrNull())) }
        val obj = el as? JsonObject ?: return listOf(Issue(true, "Arguments must be a JSON object { … }"))

        for (expr in Templates.refs(obj).distinct()) {
            val root = expr.substringBefore('.')
            when {
                root == "now" || root == "date" -> {}
                root == "prev" -> if (i == 0) out += Issue(true, "{{$expr}}: there's no previous node", "Use it from node 2 onward.")
                root.startsWith("step") -> {
                    val n = root.removePrefix("step").toIntOrNull()
                    when {
                        n == null || n < 1 -> out += Issue(true, "{{$expr}}: bad step number", "Write it like {{step1.field}}.")
                        n > steps.size -> out += Issue(true, "{{$expr}}: node $n doesn't exist")
                        n - 1 >= i -> out += Issue(true, "{{$expr}}: node $n runs ${if (n - 1 == i) "as this node" else "after this one"}",
                            "A node can only read results from nodes above it.")
                    }
                }
                else -> out += Issue(true, "Unknown placeholder {{$expr}}", "Use stepN, prev, now or date.")
            }
        }

        for (p in s.required) {
            val v = obj[p]
            if (v == null || v is JsonNull || (v is JsonPrimitive && v.isString && v.content.isBlank()))
                out += Issue(false, "Required field “$p” is empty")
        }
        return out
    }

    /** Turns a raw failure message into a short tip on how to fix it. */
    fun hint(output: String): String? {
        val o = output.lowercase()
        return when {
            "http 401" in o || "http 403" in o || "api key" in o -> "Check your Composio API key in Settings."
            "connected account" in o || "no connection" in o || "not connected" in o || "connection" in o && "not found" in o ->
                "Connect this app in the Apps tab, then run again."
            "http 404" in o || "not found" in o -> "The tool or a referenced item wasn't found. Check the slug and IDs."
            "http 429" in o || "rate limit" in o -> "Rate limited. Try again later or run less often."
            "timeout" in o || "timed out" in o || "unable to resolve host" in o -> "Network problem. Check your connection."
            "required" in o || "missing" in o || "validation" in o || "invalid" in o -> "An argument is missing or has the wrong type."
            else -> null
        }
    }
}
