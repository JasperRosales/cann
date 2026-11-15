package app.cann.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.cann.data.*
import app.cann.engine.Scheduler
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

fun fmtTime(ms: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ms))

fun Schedule.label(): String = when (this) {
    Schedule.Manual -> "Manual"
    is Schedule.Once -> "Once · ${fmtTime(atMillis)}"
    is Schedule.Interval -> if (minutes % 60 == 0) "Every ${minutes / 60}h" else "Every ${minutes}m"
    is Schedule.Daily -> "Daily %02d:%02d".format(hour, minute) +
        if (days.isEmpty() || days.size == 7) "" else " · " + days.sorted().joinToString(" ") { DAYS[it - 1] }
}
val DAYS = listOf("Mo", "Tu", "We", "Th", "Fr", "Sa", "Su")

@Composable
fun Header(title: String, sub: String? = null) {
    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 12.dp)) {
        Text(title, fontSize = 30.sp, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.headlineMedium)
        sub?.let { Text(it, color = P.muted, fontSize = 13.sp) }
    }
}

@Composable
fun FlowsScreen(store: Store, db: Db, m: Modifier, onOpen: (String) -> Unit, needsKey: Boolean, goSettings: () -> Unit) {
    val ctx = LocalContext.current
    var picking by remember { mutableStateOf(false) }
    Box(m.fillMaxSize()) {
        LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
            item { Header("cann", "${db.workflows.size} workflow(s)") }
            if (needsKey) item {
                Card(Modifier.padding(16.dp, 4.dp).fillMaxWidth().clickable(onClick = goSettings),
                    colors = CardDefaults.cardColors(containerColor = P.accentSoft)) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Key, null, tint = P.accent); Spacer(Modifier.width(12.dp))
                        Text("Add your Composio API key in Settings to start.")
                    }
                }
            }
            if (db.workflows.isEmpty()) {
                item { Text("Start from a template", color = P.muted, fontSize = 13.sp, modifier = Modifier.padding(20.dp, 8.dp)) }
                items(BuiltinTemplates.all, key = { it.id }) { t -> TemplateCard(t, Modifier.padding(16.dp, 4.dp)) { onOpen("tpl:" + t.id) } }
            }
            items(db.workflows, key = { it.id }) { w ->
                val last = db.runs.firstOrNull { it.workflowId == w.id }
                val shape = RoundedCornerShape(18.dp)
                Column(Modifier.padding(16.dp, 6.dp).fillMaxWidth().clip(shape).background(P.surfaceLo)
                    .border(1.dp, P.stroke, shape).clickable { onOpen(w.id) }.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(w.name, style = MaterialTheme.typography.titleMedium)
                            Text(w.schedule.label(), fontSize = 13.sp, color = P.muted)
                        }
                        IconButton({ Scheduler.runNow(ctx, w.id) }, enabled = w.steps.isNotEmpty()) {
                            Icon(Icons.Outlined.PlayArrow, "Run now", tint = P.success)
                        }
                        Switch(w.enabled, { on -> Scheduler.sync(ctx, w.copy(enabled = on)) },
                            enabled = w.schedule !is Schedule.Manual)
                    }
                    if (w.steps.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        // Mini pipeline: node icons joined by short lines.
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            w.steps.take(6).forEachIndexed { i, st ->
                                if (i > 0) Box(Modifier.width(10.dp).height(2.dp).background(P.muted))
                                StepIcon(st, 30.dp)
                            }
                            if (w.steps.size > 6) Text("  +${w.steps.size - 6}", fontSize = 12.sp, color = P.muted)
                        }
                    }
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        w.nextRunAt?.let { StatusPill("Next ${fmtTime(it)}", P.accent, Icons.Outlined.Schedule) }
                        last?.let { StatusPill(fmtTime(it.startedAt), if (it.ok) P.success else P.danger, if (it.ok) Icons.Outlined.CheckCircle else Icons.Outlined.Cancel) }
                    }
                }
            }
        }
        ExtendedFloatingActionButton(onClick = { picking = true }, modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
            containerColor = P.accent, contentColor = P.onAccent,
            icon = { Icon(Icons.Outlined.Add, null) }, text = { Text("New flow") })
    }
    if (picking) TemplateSheet(store, db.templates, onDismiss = { picking = false }) { picking = false; onOpen(it) }
}

/** New-flow chooser: blank, built-in templates, and the user's own. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TemplateSheet(store: Store, mine: List<Template>, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    ModalBottomSheet(onDismiss, containerColor = P.surfaceLo) {
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text("New flow", style = MaterialTheme.typography.titleLarge) }
            item {
                val shape = RoundedCornerShape(16.dp)
                Row(Modifier.fillMaxWidth().clip(shape).background(P.accentSoft).border(1.dp, P.accent, shape)
                    .clickable { onPick("ai") }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(32.dp), tint = P.accent); Spacer(Modifier.width(12.dp))
                    Column {
                        Text("Build with AI", fontWeight = FontWeight.Medium)
                        Text("Describe what it should do, AI makes the nodes", fontSize = 12.sp, color = P.muted)
                    }
                }
            }
            item {
                val shape = RoundedCornerShape(16.dp)
                Row(Modifier.fillMaxWidth().clip(shape).border(1.dp, P.stroke, shape).clickable { onPick("") }.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    IconTile("mi:bolt", "Blank", 40.dp); Spacer(Modifier.width(12.dp))
                    Text("Blank flow", fontWeight = FontWeight.Medium)
                }
            }
            if (mine.isNotEmpty()) {
                item { SectionLabel("My templates") }
                items(mine, key = { it.id }) { t ->
                    TemplateCard(t, onDelete = { store.deleteTemplate(t.id) }) { onPick("tpl:" + t.id) }
                }
            }
            item { SectionLabel("Templates") }
            items(BuiltinTemplates.all, key = { it.id }) { t -> TemplateCard(t) { onPick("tpl:" + t.id) } }
            item {
                Text("Tip: open any flow and tap the bookmark to save it as a template.", fontSize = 12.sp,
                    color = P.muted, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

@Composable
private fun TemplateCard(t: Template, m: Modifier = Modifier, onDelete: (() -> Unit)? = null, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(m.fillMaxWidth().clip(shape).background(P.surfaceLo).border(1.dp, P.stroke, shape).clickable(onClick = onClick)
        .padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(t.name, fontWeight = FontWeight.Medium)
                Text(t.description.ifBlank { t.flow.schedule.label() }, fontSize = 12.sp, color = P.muted,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            onDelete?.let { IconButton(it) { Icon(Icons.Outlined.Delete, "Delete template", tint = P.muted) } }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            t.flow.steps.take(6).forEachIndexed { i, st ->
                if (i > 0) Box(Modifier.width(10.dp).height(2.dp).background(P.muted))
                StepIcon(st, 28.dp)
            }
            Spacer(Modifier.weight(1f))
            Text(t.flow.schedule.label(), fontSize = 11.sp, color = P.muted)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RunsScreen(store: Store, all: List<Run>, m: Modifier) {
    var open by remember { mutableStateOf<String?>(null) }
    var flow by remember { mutableStateOf<String?>(null) }
    var failedOnly by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val flows = all.distinctBy { it.workflowId }.map { it.workflowId to it.workflowName }
    if (flow != null && flows.none { it.first == flow }) flow = null
    val runs = all.filter { (flow == null || it.workflowId == flow) && (!failedOnly || !it.ok) }
    LazyColumn(m.fillMaxSize()) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { Header("Runs", "${runs.size} shown") }
                if (all.isNotEmpty()) TextButton({ confirmClear = true }, Modifier.padding(end = 12.dp)) {
                    Icon(Icons.Outlined.DeleteSweep, null, tint = P.danger); Spacer(Modifier.width(6.dp))
                    Text(if (flow == null && !failedOnly) "Clear all" else "Clear shown", color = P.danger)
                }
            }
        }
        if (all.isNotEmpty()) item {
            FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(failedOnly, { failedOnly = !failedOnly }, { Text("Failed only") })
                flows.forEach { (id, n) -> FilterChip(flow == id, { flow = if (flow == id) null else id }, { Text(n) }) }
            }
        }
        if (runs.isEmpty()) item {
            Text("No runs yet.", color = P.muted, modifier = Modifier.padding(20.dp))
        }
        items(runs, key = { it.id }) { r ->
            Column(Modifier.fillMaxWidth().clickable { open = if (open == r.id) null else r.id }.padding(20.dp, 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (r.ok) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline, null,
                        tint = if (r.ok) P.success else P.danger)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(r.workflowName)
                        Text("${fmtTime(r.startedAt)} · ${(r.endedAt - r.startedAt) / 1000.0}s", fontSize = 12.sp, color = P.muted)
                    }
                    IconButton({ store.deleteRun(r.id) }) { Icon(Icons.Outlined.Delete, "Delete run", tint = P.muted) }
                }
                if (open == r.id) r.steps.forEachIndexed { i, s ->
                    Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        StepIcon(Step(s.toolSlug), 24.dp); Spacer(Modifier.width(8.dp))
                        Text("${i + 1}. ${s.toolSlug}", fontSize = 13.sp, modifier = Modifier.weight(1f))
                        OkIcon(s.ok)
                    }
                    if (!s.ok) app.cann.engine.Lint.hint(s.output)?.let {
                        Text("Tip: $it", fontSize = 12.sp, color = P.warn, modifier = Modifier.padding(top = 4.dp))
                    }
                    Text(s.output.take(1500), fontFamily = Mono, fontSize = 11.sp, color = P.muted,
                        modifier = Modifier.padding(top = 4.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp))
                            .background(P.codeBg).padding(8.dp))
                }
            }
            HorizontalDivider(color = P.stroke)
        }
    }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Delete ${runs.size} run log(s)?") },
        confirmButton = {
            TextButton({
                val ids = runs.map { it.id }.toSet()
                store.edit { d -> d.copy(runs = d.runs.filterNot { it.id in ids }) }
                confirmClear = false
            }) { Text("Delete", color = P.danger) }
        },
        dismissButton = { TextButton({ confirmClear = false }) { Text("Cancel") } },
    )
}

@Composable
fun AppsScreen(s: Settings, m: Modifier) = ToolBrowser(s, m)

@Composable
fun SettingsScreen(store: Store, s: Settings, m: Modifier) {
    val ctx = LocalContext.current
    var key by remember(s) { mutableStateOf(s.apiKey) }
    var user by remember(s) { mutableStateOf(s.userId) }
    var aiUrl by remember(s) { mutableStateOf(s.aiUrl) }
    var aiKey by remember(s) { mutableStateOf(s.aiKey) }
    var aiModel by remember(s) { mutableStateOf(s.aiModel) }
    var custom by remember(s) { mutableStateOf(AI_PRESETS.none { it.url == s.aiUrl }) }
    val next = s.copy(apiKey = key.trim(), userId = user.trim().ifEmpty { "default" },
        aiUrl = aiUrl.trim(), aiKey = aiKey.trim(), aiModel = aiModel.trim())
    Column(m.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
        Header("Settings")
        OutlinedTextField(key, { key = it }, Modifier.fillMaxWidth(), label = { Text("Composio API key") },
            singleLine = true, visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
        Hint("Project key (ak_…) or MCP consumer key (ck_…) from dashboard.composio.dev.")
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(user, { user = it }, Modifier.fillMaxWidth(), label = { Text("User ID") }, singleLine = true)
        Hint("Your connected accounts are stored under this id.")

        SectionLabel("AI assistant")
        Hint("Describe a flow and AI builds the nodes. Pick a free provider, get a key, paste it below.")
        Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AI_PRESETS.forEach { p ->
                FilterChip(aiUrl == p.url && !custom, { custom = false; aiUrl = p.url; aiModel = p.model }, { Text(p.name) })
            }
            FilterChip(custom, { custom = true }, { Text("Custom") })
        }
        AI_PRESETS.firstOrNull { it.url == aiUrl }?.let { p ->
            TextButton({ ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(p.keyUrl))) }) {
                Icon(Icons.Outlined.Key, null); Spacer(Modifier.width(6.dp)); Text("Get a free ${p.name} key")
            }
        }
        OutlinedTextField(aiKey, { aiKey = it }, Modifier.fillMaxWidth(), label = { Text("AI API key") },
            singleLine = true, visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(aiModel, { aiModel = it }, Modifier.fillMaxWidth(), label = { Text("Model") }, singleLine = true)
        if (custom) {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(aiUrl, { aiUrl = it }, Modifier.fillMaxWidth(), label = { Text("Endpoint (OpenAI-compatible)") },
                singleLine = true)
        }
        Spacer(Modifier.height(16.dp))
        Button({ store.edit { it.copy(settings = next) } }, enabled = next != s) { Text("Save") }
    }
}

@Composable
private fun Hint(t: String) = Text(t, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(4.dp))
