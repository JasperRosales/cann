package app.cann.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.cann.data.Assistant
import app.cann.data.Settings
import app.cann.data.Workflow
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private val EXAMPLES = listOf(
    "Every weekday at 8am, email me my unread Gmail count",
    "When I tap run, post a message to Slack #general",
    "Every hour, add a row with the time to a Google Sheet",
)
private val EDITS = listOf("Also send the result to Slack", "Run it daily at 7am instead", "Add a step that emails me the output")

/** Asks the AI to build a new flow, or change [current] if given. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AiDialog(s: Settings, current: Workflow?, onDismiss: () -> Unit, onResult: (Workflow) -> Unit) {
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var job by remember { mutableStateOf<Job?>(null) }
    val log = remember { mutableStateListOf<String>() }
    var err by remember { mutableStateOf<String?>(null) }
    val busy = job?.isActive == true
    val ready = s.aiKey.isNotBlank() && s.apiKey.isNotBlank()

    fun go() {
        err = null; log.clear()
        job = scope.launch {
            runCatching { Assistant(s).build(text.trim(), current) { log += it } }
                .onSuccess(onResult)
                .onFailure { if (it !is kotlinx.coroutines.CancellationException) err = it.message ?: it.toString() }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        icon = { Icon(Icons.Outlined.AutoAwesome, null, tint = P.accent) },
        title = { Text(if (current == null) "Build with AI" else "Change with AI") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                if (!ready) Text(
                    if (s.apiKey.isBlank()) "Add your Composio key in Settings first."
                    else "Add a free AI key in Settings → AI assistant (Gemini works well).",
                    color = P.warn, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp))
                OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), enabled = !busy, minLines = 3,
                    placeholder = { Text(if (current == null) "What should this flow do?" else "What should change?") })
                if (!busy && log.isEmpty()) FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (if (current == null) EXAMPLES else EDITS).forEach { e ->
                        SuggestionChip({ text = e }, { Text(e, fontSize = 12.sp) })
                    }
                }
                if (log.isNotEmpty()) Column(Modifier.padding(top = 10.dp)) {
                    log.takeLast(6).forEachIndexed { i, l ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (busy && i == log.takeLast(6).lastIndex) CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp)
                            else Text("✓", color = P.success, fontSize = 12.sp)
                            Spacer(Modifier.width(8.dp))
                            Text(l, fontSize = 12.sp, color = P.muted)
                        }
                    }
                }
                err?.let { Text(it, color = P.danger, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }
                if (current == null) Text("You can review and tweak every node before saving.", fontSize = 11.sp,
                    color = P.muted, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = {
            Button(::go, enabled = ready && !busy && text.isNotBlank()) { Text(if (busy) "Building…" else "Build") }
        },
        dismissButton = {
            TextButton({ if (busy) job?.cancel() else onDismiss() }) { Text(if (busy) "Stop" else "Cancel") }
        },
    )
}
