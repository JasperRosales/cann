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
fun FlowsScreen(db: Db, m: Modifier, onOpen: (String) -> Unit, needsKey: Boolean, goSettings: () -> Unit) {
    val ctx = LocalContext.current
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
                        w.nextRunAt?.let { StatusPill("Next ${fmtTime(it)}", P.accent) }
                        last?.let { StatusPill((if (it.ok) "✓ " else "✗ ") + fmtTime(it.startedAt), if (it.ok) P.success else P.danger) }
                    }
                }
            }
        }
        ExtendedFloatingActionButton(onClick = { onOpen("") }, modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
            containerColor = P.accent, contentColor = P.onAccent,
            icon = { Icon(Icons.Outlined.Add, null) }, text = { Text("New flow") })
    }
}

@Composable
fun RunsScreen(runs: List<Run>, m: Modifier) {
    var open by remember { mutableStateOf<String?>(null) }
    LazyColumn(m.fillMaxSize()) {
        item { Header("Runs", "Latest ${runs.size}") }
        items(runs, key = { it.id }) { r ->
            Column(Modifier.fillMaxWidth().clickable { open = if (open == r.id) null else r.id }.padding(20.dp, 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (r.ok) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline, null,
                        tint = if (r.ok) P.success else P.danger)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(r.workflowName)
                        Text("${fmtTime(r.startedAt)} · ${(r.endedAt - r.startedAt) / 1000.0}s", fontSize = 12.sp, color = P.muted)
                    }
                }
                if (open == r.id) r.steps.forEachIndexed { i, s ->
                    Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        StepIcon(Step(s.toolSlug), 24.dp); Spacer(Modifier.width(8.dp))
                        Text("${i + 1}. ${s.toolSlug}", fontSize = 13.sp, modifier = Modifier.weight(1f))
                        Text(if (s.ok) "✓" else "✗", color = if (s.ok) P.success else P.danger)
                    }
                    Text(s.output.take(1500), fontFamily = Mono, fontSize = 11.sp, color = P.muted,
                        modifier = Modifier.padding(top = 4.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp))
                            .background(P.codeBg).padding(8.dp))
                }
            }
            HorizontalDivider(color = P.stroke)
        }
    }
}

@Composable
fun AppsScreen(s: Settings, m: Modifier) = ToolBrowser(s, m)

@Composable
fun SettingsScreen(store: Store, s: Settings, m: Modifier) {
    var key by remember(s) { mutableStateOf(s.apiKey) }
    var user by remember(s) { mutableStateOf(s.userId) }
    Column(m.fillMaxSize().padding(horizontal = 16.dp)) {
        Header("Settings")
        OutlinedTextField(key, { key = it }, Modifier.fillMaxWidth(), label = { Text("Composio API key") },
            singleLine = true, visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
        Text("Project key (ak_…) from dashboard.composio.dev → Settings → API keys.", fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(4.dp))
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(user, { user = it }, Modifier.fillMaxWidth(), label = { Text("User ID") }, singleLine = true)
        Text("Your connected accounts are stored under this id.", fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(4.dp))
        Spacer(Modifier.height(16.dp))
        Button({ store.edit { it.copy(settings = Settings(key.trim(), user.trim().ifEmpty { "default" })) } },
            enabled = key != s.apiKey || user != s.userId) { Text("Save") }
    }
}
