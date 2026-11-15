@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package app.cann.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.cann.data.*
import app.cann.engine.Scheduler
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import java.time.*
import java.util.UUID

@Composable
fun EditorScreen(store: Store, id: String?, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val orig = remember { id?.let(store::workflow) ?: Workflow(UUID.randomUUID().toString(), "") }
    var name by remember { mutableStateOf(orig.name) }
    var schedule by remember { mutableStateOf(orig.schedule) }
    val steps = remember { orig.steps.toMutableStateList() }
    var stopOnError by remember { mutableStateOf(orig.stopOnError) }
    var picking by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    val badArgs = steps.indexOfFirst { runCatching { json.parseToJsonElement(it.args).jsonObject }.isFailure }
    fun save() {
        val wf = orig.copy(name = name.trim().ifEmpty { "Untitled flow" }, schedule = schedule, steps = steps.toList(),
            stopOnError = stopOnError, enabled = if (schedule != orig.schedule) true else orig.enabled)
        Scheduler.sync(ctx, wf)
        onClose()
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(if (id == null) "New flow" else "Edit flow") },
            navigationIcon = { IconButton(onClose) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
            actions = {
                if (id != null) IconButton({ confirmDelete = true }) { Icon(Icons.Outlined.Delete, "Delete") }
                TextButton(::save, enabled = badArgs < 0) { Text("Save") }
            },
        )
    }) { pad ->
        LazyColumn(Modifier.padding(pad).imePadding(), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 48.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Name") }, singleLine = true)
            }
            item { ScheduleEditor(schedule) { schedule = it } }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Stop on first error", Modifier.weight(1f))
                    Switch(stopOnError, { stopOnError = it })
                }
            }
            item { Text("Steps", style = MaterialTheme.typography.titleMedium) }
            itemsIndexed(steps) { i, s ->
                StepCard(i, s, steps.size, onChange = { steps[i] = it }, onDelete = { steps.removeAt(i) },
                    onMove = { d -> val t = i + d; if (t in steps.indices) steps.add(t, steps.removeAt(i)) })
            }
            item {
                OutlinedButton({ picking = true }, Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(8.dp)); Text("Add step")
                }
            }
            item {
                Text("Use earlier results in args: {{step1.path.to.field}}, {{prev.field}}, {{now}}, {{date}}. " +
                    "Array items by index, e.g. {{step1.messages.0.id}}.", fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    if (picking) ToolPicker(store.db.value.settings, onDismiss = { picking = false }) { t ->
        picking = false
        val args = buildJsonObject {
            t.params.filter { it.required }.forEach { p ->
                when (p.type) {
                    "integer", "number" -> put(p.name, 0)
                    "boolean" -> put(p.name, false)
                    "array" -> put(p.name, JsonArray(emptyList()))
                    "object" -> put(p.name, JsonObject(emptyMap()))
                    else -> put(p.name, "")
                }
            }
        }
        steps += Step(t.slug, prettyJson.encodeToString(JsonObject.serializer(), args), t.name, t.version)
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete \"${orig.name}\"?") },
        confirmButton = { TextButton({ Scheduler.cancel(ctx, orig.id); store.delete(orig.id); onClose() }) { Text("Delete") } },
        dismissButton = { TextButton({ confirmDelete = false }) { Text("Cancel") } },
    )
}

private val prettyJson = Json { prettyPrint = true }

@Composable
private fun StepCard(i: Int, s: Step, count: Int, onChange: (Step) -> Unit, onDelete: () -> Unit, onMove: (Int) -> Unit) {
    val valid = remember(s.args) { runCatching { json.parseToJsonElement(s.args).jsonObject }.isSuccess }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}", color = Amber, fontFamily = Mono, modifier = Modifier.width(24.dp))
                Column(Modifier.weight(1f)) {
                    Text(s.note.ifBlank { s.toolSlug }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(s.toolSlug, fontSize = 11.sp, fontFamily = Mono, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton({ onMove(-1) }, enabled = i > 0) { Icon(Icons.Outlined.KeyboardArrowUp, "Up") }
                IconButton({ onMove(1) }, enabled = i < count - 1) { Icon(Icons.Outlined.KeyboardArrowDown, "Down") }
                IconButton(onDelete) { Icon(Icons.Outlined.Close, "Remove") }
            }
            OutlinedTextField(s.args, { onChange(s.copy(args = it)) }, Modifier.fillMaxWidth(),
                textStyle = TextStyle(fontFamily = Mono, fontSize = 12.sp), label = { Text("Arguments (JSON)") },
                isError = !valid, supportingText = if (valid) null else ({ Text("Invalid JSON object") }))
        }
    }
}

@Composable
private fun ScheduleEditor(s: Schedule, onChange: (Schedule) -> Unit) {
    val kinds = listOf("Manual", "Once", "Repeat", "Daily")
    val sel = when (s) { Schedule.Manual -> 0; is Schedule.Once -> 1; is Schedule.Interval -> 2; is Schedule.Daily -> 3 }
    var pickDate by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("When", style = MaterialTheme.typography.titleMedium)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            kinds.forEachIndexed { i, k ->
                SegmentedButton(i == sel, {
                    onChange(when (i) {
                        0 -> Schedule.Manual
                        1 -> Schedule.Once(System.currentTimeMillis() + 3_600_000)
                        2 -> Schedule.Interval(60)
                        else -> Schedule.Daily(8, 0)
                    })
                }, SegmentedButtonDefaults.itemShape(i, kinds.size)) { Text(k, fontSize = 13.sp) }
            }
        }
        when (s) {
            Schedule.Manual -> Text("Runs only when you tap ▶.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            is Schedule.Once -> {
                val dt = LocalDateTime.ofInstant(Instant.ofEpochMilli(s.atMillis), ZoneId.systemDefault())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ pickDate = true }) { Text(dt.toLocalDate().toString()) }
                    OutlinedButton({ pickTime = true }) { Text("%02d:%02d".format(dt.hour, dt.minute)) }
                }
                if (s.atMillis <= System.currentTimeMillis()) Text("That time has passed.", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                if (pickDate) {
                    val st = rememberDatePickerState(dt.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
                    DatePickerDialog({ pickDate = false }, confirmButton = {
                        TextButton({
                            st.selectedDateMillis?.let {
                                val d = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()
                                onChange(Schedule.Once(dt.with(d).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()))
                            }
                            pickDate = false
                        }) { Text("OK") }
                    }) { DatePicker(st) }
                }
                if (pickTime) TimeDialog(dt.hour, dt.minute, { pickTime = false }) { h, m ->
                    onChange(Schedule.Once(dt.withHour(h).withMinute(m).withSecond(0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()))
                }
            }
            is Schedule.Interval -> {
                val units = listOf(15, 30, 60, 180, 360, 720)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    units.forEach { u ->
                        FilterChip(s.minutes == u, { onChange(Schedule.Interval(u)) },
                            { Text(if (u < 60) "${u}m" else "${u / 60}h") })
                    }
                }
                var custom by remember { mutableStateOf(s.minutes.toString()) }
                OutlinedTextField(custom, {
                    custom = it.filter(Char::isDigit)
                    custom.toIntOrNull()?.let { m -> onChange(Schedule.Interval(m.coerceAtLeast(15))) }
                }, label = { Text("Minutes (min 15)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
            is Schedule.Daily -> {
                OutlinedButton({ pickTime = true }) { Text("At %02d:%02d".format(s.hour, s.minute)) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DAYS.forEachIndexed { i, d ->
                        val day = i + 1
                        FilterChip(day in s.days, {
                            onChange(s.copy(days = if (day in s.days) s.days - day else s.days + day))
                        }, { Text(d) })
                    }
                }
                Text(if (s.days.isEmpty()) "Every day" else "Only on selected days", fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (pickTime) TimeDialog(s.hour, s.minute, { pickTime = false }) { h, m -> onChange(s.copy(hour = h, minute = m)) }
            }
        }
        Text("Scheduled runs may be delayed a few minutes by Android battery saving.", fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TimeDialog(h: Int, m: Int, onDismiss: () -> Unit, onPick: (Int, Int) -> Unit) {
    val st = rememberTimePickerState(h, m)
    AlertDialog(onDismiss, confirmButton = { TextButton({ onPick(st.hour, st.minute); onDismiss() }) { Text("OK") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } }, text = { TimePicker(st) })
}

@Composable
private fun ToolPicker(s: Settings, onDismiss: () -> Unit, onPick: (Tool) -> Unit) {
    val api = remember(s) { Composio(s.apiKey, s.userId) }
    val scope = rememberCoroutineScope()
    var kit by remember { mutableStateOf("") }
    var q by remember { mutableStateOf("") }
    var tools by remember { mutableStateOf<List<Tool>>(emptyList()) }
    var err by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<Tool?>(null) }

    LaunchedEffect(kit, q) {
        delay(350)
        if (kit.isBlank() && q.isBlank()) { tools = emptyList(); return@LaunchedEffect }
        loading = true; err = null
        runCatching { api.tools(kit.trim().lowercase(), q.trim()) }.onSuccess { tools = it }.onFailure { err = it.message }
        loading = false
    }

    Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.padding(16.dp).systemBarsPadding().imePadding()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Pick a tool", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    IconButton(onDismiss) { Icon(Icons.Outlined.Close, "Close") }
                }
                OutlinedTextField(kit, { kit = it }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("App (e.g. gmail, slack, github)") })
                OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("What should it do?") }, leadingIcon = { Icon(Icons.Outlined.Search, null) })
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                err?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
                LazyColumn(Modifier.weight(1f)) {
                    items(tools, key = { it.slug }) { t ->
                        ListItem(
                            modifier = Modifier.clickable { detail = t },
                            headlineContent = { Text(t.name) },
                            overlineContent = { Text(t.slug, fontFamily = Mono, fontSize = 10.sp) },
                            supportingContent = { Text(t.description, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 12.sp) },
                        )
                    }
                }
            }
        }
    }

    detail?.let { t ->
        AlertDialog({ detail = null },
            title = { Text(t.name) },
            text = {
                LazyColumn {
                    item { Text(t.description, fontSize = 13.sp); Spacer(Modifier.height(8.dp)) }
                    items(t.params) { p ->
                        Text(p.name + (if (p.required) " *" else "") + "  ·  " + p.type, fontFamily = Mono, fontSize = 12.sp, color = Amber)
                        if (p.description.isNotBlank()) Text(p.description, fontSize = 12.sp, modifier = Modifier.padding(bottom = 6.dp))
                    }
                }
            },
            confirmButton = { TextButton({ detail = null; scope.launch { onPick(t) } }) { Text("Add step") } },
            dismissButton = { TextButton({ detail = null }) { Text("Back") } })
    }
}
