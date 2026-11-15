package app.cann.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.cann.data.Step
import kotlinx.serialization.json.*

/** Every leaf path in [e] with a short preview; arrays show only item 0. */
fun fieldPaths(e: JsonElement, prefix: String, depth: Int = 0, out: MutableList<Pair<String, String>> = mutableListOf()):
    List<Pair<String, String>> {
    if (out.size >= 80) return out
    when (e) {
        is JsonObject -> if (depth > 5) out += prefix to "{…}" else e.forEach { (k, v) -> fieldPaths(v, "$prefix.$k", depth + 1, out) }
        is JsonArray -> {
            out += prefix to "list of ${e.size}"
            e.firstOrNull()?.let { if (depth <= 5) fieldPaths(it, "$prefix.0", depth + 1, out) }
        }
        is JsonPrimitive -> out += prefix to e.content.take(40)
        JsonNull -> {}
    }
    return out
}

/** Inserts {{expr}} at the cursor, adding quotes if the cursor is outside a JSON string. */
fun insertPlaceholder(v: TextFieldValue, expr: String): TextFieldValue {
    val at = v.selection.start.coerceIn(0, v.text.length)
    var inStr = false; var esc = false
    for (c in v.text.substring(0, at)) {
        if (esc) esc = false else if (c == '\\') esc = true else if (c == '"') inStr = !inStr
    }
    val ins = if (inStr) "{{$expr}}" else "\"{{$expr}}\""
    val t = v.text.substring(0, at) + ins + v.text.substring(v.selection.end.coerceIn(at, v.text.length))
    return TextFieldValue(t, TextRange(at + ins.length))
}

/** Picker of values this node can use: earlier node outputs plus now/date. */
@Composable
fun FieldPicker(steps: List<Step>, upTo: Int, prior: List<JsonElement>, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    AlertDialog(onDismiss, confirmButton = { TextButton(onDismiss) { Text("Close") } },
        title = { Text("Insert a value") },
        text = {
            LazyColumn(Modifier.heightIn(max = 460.dp)) {
                item { SectionLabel("Time") }
                item { PickRow("now", "current date & time", onPick) }
                item { PickRow("date", "today, yyyy-MM-dd", onPick) }
                (0 until upTo).forEach { j ->
                    val st = steps[j]
                    item { SectionLabel("Node ${j + 1} · ${st.note.ifBlank { st.toolSlug }}") }
                    val data = prior.getOrNull(j)
                    if (data == null || data is JsonNull) {
                        item { PickRow("step${j + 1}.data", "whole result", onPick) }
                        item { Text("Test node ${j + 1} to see its fields here.", fontSize = 12.sp, color = P.muted) }
                    } else items(fieldPaths(data, "step${j + 1}")) { (p, prev) -> PickRow(p, prev, onPick) }
                }
            }
        })
}

@Composable
private fun PickRow(expr: String, preview: String, onPick: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().clickable { onPick(expr) }.padding(vertical = 6.dp)) {
        Text("{{$expr}}", fontFamily = Mono, fontSize = 12.sp, color = P.accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (preview.isNotBlank()) Text(preview, fontSize = 11.sp, color = P.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
