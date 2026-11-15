package app.cann.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
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
        Text(title, fontSize = 30.sp, style = MaterialTheme.typography.headlineMedium)
        sub?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp) }
    }
}

@Composable
fun FlowsScreen(db: Db, m: Modifier, onOpen: (String) -> Unit, needsKey: Boolean, goSettings: () -> Unit) {
    val ctx = LocalContext.current
    val store = Store.get(ctx)
    Box(m.fillMaxSize()) {
        LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
            item { Header("cann", "${db.workflows.size} workflow(s)") }
            if (needsKey) item {
                Card(Modifier.padding(16.dp, 4.dp).fillMaxWidth().clickable(onClick = goSettings)) {
                    Text("Add your Composio API key in Settings to start.", Modifier.padding(16.dp))
                }
            }
            items(db.workflows, key = { it.id }) { w ->
                val last = db.runs.firstOrNull { it.workflowId == w.id }
                Card(Modifier.padding(16.dp, 6.dp).fillMaxWidth().clickable { onOpen(w.id) },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(w.name, style = MaterialTheme.typography.titleMedium)
                            Text("${w.steps.size} step(s) · ${w.schedule.label()}", fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            w.nextRunAt?.let { Text("Next: ${fmtTime(it)}", fontSize = 12.sp, color = Amber) }
                            last?.let {
                                Text((if (it.ok) "✓ " else "✗ ") + fmtTime(it.startedAt), fontSize = 12.sp,
                                    color = if (it.ok) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
                            }
                        }
                        IconButton({ Scheduler.runNow(ctx, w.id) }, enabled = w.steps.isNotEmpty()) {
                            Icon(Icons.Outlined.PlayArrow, "Run now")
                        }
                        Switch(w.enabled, { on -> Scheduler.sync(ctx, w.copy(enabled = on)) },
                            enabled = w.schedule !is Schedule.Manual)
                    }
                }
            }
        }
        ExtendedFloatingActionButton(onClick = { onOpen("") }, modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
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
                        tint = if (r.ok) Amber else MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(r.workflowName)
                        Text("${fmtTime(r.startedAt)} · ${(r.endedAt - r.startedAt) / 1000.0}s", fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (open == r.id) r.steps.forEachIndexed { i, s ->
                    Text("${i + 1}. ${s.toolSlug} " + if (s.ok) "✓" else "✗", Modifier.padding(top = 8.dp), fontSize = 13.sp)
                    Text(s.output.take(1500), fontFamily = Mono, fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            HorizontalDivider()
        }
    }
}

@Composable
fun AppsScreen(s: Settings, m: Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val api = remember(s) { Composio(s.apiKey, s.userId) }
    var accounts by remember { mutableStateOf<List<Account>>(emptyList()) }
    var kits by remember { mutableStateOf<List<Toolkit>>(emptyList()) }
    var q by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }

    suspend fun refresh() {
        err = null
        runCatching { accounts = api.accounts(); kits = api.toolkits(q) }.onFailure { err = it.message }
    }
    LaunchedEffect(api, q) { kotlinx.coroutines.delay(300); refresh() }

    LazyColumn(m.fillMaxSize()) {
        item { Header("Apps", "Connected as \"${s.userId}\"") }
        item {
            OutlinedTextField(q, { q = it }, Modifier.padding(16.dp, 0.dp).fillMaxWidth(),
                placeholder = { Text("Search apps (gmail, slack, notion…)") }, singleLine = true,
                leadingIcon = { Icon(Icons.Outlined.Search, null) })
        }
        err?.let { item { Text(it, Modifier.padding(20.dp), color = MaterialTheme.colorScheme.error) } }
        items(kits, key = { it.slug }) { k ->
            val acc = accounts.filter { it.toolkit == k.slug }
            val active = acc.any { it.status == "ACTIVE" }
            ListItem(
                headlineContent = { Text(k.name) },
                supportingContent = { Text(k.description, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 12.sp) },
                trailingContent = {
                    if (active) AssistChip({}, { Text("Connected") }, leadingIcon = { Icon(Icons.Outlined.Check, null) })
                    else TextButton(enabled = busy == null, onClick = {
                        busy = k.slug
                        scope.launch {
                            runCatching { api.connectLink(k.slug) }
                                .onSuccess { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) }
                                .onFailure { err = it.message }
                            busy = null
                        }
                    }) { Text(if (busy == k.slug) "…" else if (acc.isNotEmpty()) acc.first().status.lowercase() else "Connect") }
                },
            )
        }
    }
    // Pick up new connections when coming back from the browser.
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) scope.launch { refresh() }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
}

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
