@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package app.cann.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.cann.data.*
import app.cann.engine.Scheduler
import kotlinx.serialization.json.*
import java.time.*
import java.util.UUID

private val prettyJson = Json { prettyPrint = true }
private fun validArgs(a: String) = runCatching { json.parseToJsonElement(a).jsonObject }.isSuccess

/** Dotted canvas like n8n's editor. */
fun Modifier.dotGrid(color: Color) = drawBehind {
    val step = 22.dp.toPx(); val r = 1.2.dp.toPx()
    var y = step / 2
    while (y < size.height) {
        var x = step / 2
        while (x < size.width) { drawCircle(color, r, Offset(x, y)); x += step }
        y += step
    }
}

@Composable
fun EditorScreen(store: Store, id: String?, seed: Workflow? = null, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val db by store.db.collectAsState()
    val orig = remember {
        id?.let(store::workflow) ?: (seed ?: Workflow("", "")).copy(id = UUID.randomUUID().toString(), nextRunAt = null)
    }
    var name by remember { mutableStateOf(orig.name) }
    var schedule by remember { mutableStateOf(orig.schedule) }
    val steps = remember { orig.steps.toMutableStateList() }
    var stopOnError by remember { mutableStateOf(orig.stopOnError) }
    var pickAt by remember { mutableStateOf<Int?>(null) }
    var openStep by remember { mutableStateOf<Int?>(null) }
    var openTrigger by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var saveTpl by remember { mutableStateOf(false) }
    val lastRun = db.runs.firstOrNull { it.workflowId == orig.id }

    val badArgs = steps.indexOfFirst { !validArgs(it.args) }
    fun build() = orig.copy(name = name.trim().ifEmpty { "Untitled flow" }, schedule = schedule, steps = steps.toList(),
        stopOnError = stopOnError, enabled = if (schedule != orig.schedule) true else orig.enabled)
    fun save() { Scheduler.sync(ctx, build()); onClose() }

    Scaffold(containerColor = P.bgDeep, topBar = {
        TopAppBar(
            colors = TopAppBarDefaults.topAppBarColors(containerColor = P.bg),
            title = {
                BasicTextField(name, { name = it }, singleLine = true, cursorBrush = SolidColor(P.accent),
                    textStyle = MaterialTheme.typography.titleLarge.copy(color = P.ink),
                    decorationBox = { inner ->
                        if (name.isEmpty()) Text("Untitled flow", style = MaterialTheme.typography.titleLarge, color = P.muted)
                        inner()
                    })
            },
            navigationIcon = { IconButton(onClose) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
            actions = {
                IconButton({ saveTpl = true }, enabled = steps.isNotEmpty()) { Icon(Icons.Outlined.BookmarkAdd, "Save as template") }
                if (id != null) IconButton({ confirmDelete = true }) { Icon(Icons.Outlined.Delete, "Delete") }
                if (id != null) IconButton({ Scheduler.sync(ctx, build()); Scheduler.runNow(ctx, orig.id) },
                    enabled = badArgs < 0 && steps.isNotEmpty()) { Icon(Icons.Outlined.PlayArrow, "Save & run", tint = P.success) }
                Button(::save, enabled = badArgs < 0, modifier = Modifier.padding(end = 8.dp)) { Text("Save") }
            },
        )
    }) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().dotGrid(P.grid).verticalScroll(rememberScrollState())
                .padding(vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            TriggerNode(schedule) { openTrigger = true }
            steps.forEachIndexed { i, s ->
                Connector { pickAt = i }
                val res = lastRun?.steps?.getOrNull(i)?.takeIf { it.toolSlug == s.toolSlug }
                StepNode(i, s, validArgs(s.args), res?.ok) { openStep = i }
            }
            Connector(null)
            AddNode { pickAt = steps.size }
            Spacer(Modifier.height(20.dp))
            Text("Tap a node to edit · + between nodes to insert", fontSize = 12.sp, color = P.muted)
        }
    }

    if (saveTpl) TemplateDialog(name, onDismiss = { saveTpl = false }) { n, d ->
        store.saveTemplate(Template(UUID.randomUUID().toString(), n, d,
            build().copy(id = "", name = n, enabled = true, nextRunAt = null)))
        saveTpl = false
        android.widget.Toast.makeText(ctx, "Saved template “$n”", android.widget.Toast.LENGTH_SHORT).show()
    }

    pickAt?.let { at ->
        Dialog({ pickAt = null }, DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize(), color = P.bg) {
                ToolBrowser(db.settings, Modifier.systemBarsPadding(), title = "Add a node", onClose = { pickAt = null }) { t, kit ->
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
                    steps.add(at.coerceIn(0, steps.size), Step(t.slug, prettyJson.encodeToString(JsonObject.serializer(), args),
                        t.name, t.version, toolkit = t.toolkit, logo = kit?.logo))
                    pickAt = null
                    openStep = at
                }
            }
        }
    }
    openStep?.takeIf { it in steps.indices }?.let { i ->
        ModalBottomSheet({ openStep = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = P.surfaceLo) {
            NodeSheet(i, steps[i], steps.size, onChange = { steps[i] = it },
                onDelete = { steps.removeAt(i); openStep = null },
                onMove = { d -> val t = i + d; if (t in steps.indices) { steps.add(t, steps.removeAt(i)); openStep = t } })
        }
    }
    if (openTrigger) ModalBottomSheet({ openTrigger = false }, containerColor = P.surfaceLo) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState())) {
            ScheduleEditor(schedule) { schedule = it }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Stop on first error", Modifier.weight(1f))
                Switch(stopOnError, { stopOnError = it })
            }
        }
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete \"${orig.name}\"?") },
        confirmButton = { TextButton({ Scheduler.cancel(ctx, orig.id); store.delete(orig.id); onClose() }) { Text("Delete", color = P.danger) } },
        dismissButton = { TextButton({ confirmDelete = false }) { Text("Cancel") } },
    )
}

private val NodeSize = 76.dp

@Composable
private fun NodeLabel(title: String, sub: String?) {
    Spacer(Modifier.height(8.dp))
    Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 2, textAlign = TextAlign.Center,
        overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 240.dp))
    sub?.let { Text(it, fontSize = 11.sp, color = P.muted, fontFamily = Mono, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.widthIn(max = 240.dp)) }
}

@Composable
private fun TriggerNode(s: Schedule, onClick: () -> Unit) {
    // n8n draws triggers with a rounded left edge.
    val shape = RoundedCornerShape(topStart = NodeSize / 2, bottomStart = NodeSize / 2, topEnd = 14.dp, bottomEnd = 14.dp)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(NodeSize).clip(shape).background(P.surfaceHi).border(2.dp, P.accent, shape).clickable(onClick = onClick),
            contentAlignment = Alignment.Center) {
            Icon(if (s is Schedule.Manual) Icons.Outlined.TouchApp else Icons.Outlined.Schedule, null,
                Modifier.size(34.dp), tint = P.accent)
        }
        NodeLabel(if (s is Schedule.Manual) "When tapped" else "On schedule", s.label())
    }
}

@Composable
private fun StepNode(i: Int, s: Step, valid: Boolean, ok: Boolean?, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    val border = when { !valid -> P.danger; ok == false -> P.danger; ok == true -> P.success; else -> P.stroke }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box {
            Box(Modifier.size(NodeSize).clip(shape).background(P.surfaceHi).border(2.dp, border, shape).clickable(onClick = onClick),
                contentAlignment = Alignment.Center) {
                StepIcon(s, 48.dp)
            }
            Text("${i + 1}", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = P.onAccent,
                modifier = Modifier.align(Alignment.TopStart).offset((-6).dp, (-6).dp).clip(CircleShape)
                    .background(P.accent).padding(horizontal = 6.dp, vertical = 1.dp))
            if (ok != null) Icon(if (ok) Icons.Outlined.CheckCircle else Icons.Outlined.Error, null,
                tint = if (ok) P.success else P.danger,
                modifier = Modifier.align(Alignment.TopEnd).offset(6.dp, (-6).dp).size(18.dp).clip(CircleShape).background(P.bgDeep))
        }
        NodeLabel(s.note.ifBlank { s.toolSlug }, s.toolSlug)
        if (!valid) Text("Invalid JSON", color = P.danger, fontSize = 11.sp)
    }
}

/** Line between nodes; [onAdd] puts a + on it to insert a node there. */
@Composable
private fun Connector(onAdd: (() -> Unit)?) {
    val c = P.muted
    Box(Modifier.height(if (onAdd != null) 64.dp else 36.dp).width(40.dp).drawBehind {
        val x = size.width / 2
        drawLine(c, Offset(x, 4.dp.toPx()), Offset(x, size.height - 4.dp.toPx()), 2.dp.toPx())
        val a = 5.dp.toPx(); val yb = size.height - 4.dp.toPx()
        drawLine(c, Offset(x - a, yb - a), Offset(x, yb), 2.dp.toPx())
        drawLine(c, Offset(x + a, yb - a), Offset(x, yb), 2.dp.toPx())
        drawCircle(c, 3.5.dp.toPx(), Offset(x, 4.dp.toPx()))
    }, contentAlignment = Alignment.Center) {
        onAdd?.let {
            Box(Modifier.size(24.dp).clip(RoundedCornerShape(7.dp)).background(P.surfaceHi)
                .border(1.dp, P.stroke, RoundedCornerShape(7.dp)).clickable(onClick = it), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Add, "Insert node", Modifier.size(16.dp), tint = P.muted)
            }
        }
    }
}

@Composable
private fun AddNode(onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    val c = P.muted
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(NodeSize).clip(shape).clickable(onClick = onClick).drawBehind {
            drawRoundRect(c, cornerRadius = androidx.compose.ui.geometry.CornerRadius(16.dp.toPx()),
                style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))))
        }, contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Add, "Add node", Modifier.size(32.dp), tint = c)
        }
        NodeLabel("Add node", null)
    }
}

@Composable
private fun NodeSheet(i: Int, s: Step, count: Int, onChange: (Step) -> Unit, onDelete: () -> Unit, onMove: (Int) -> Unit) {
    val valid = remember(s.args) { validArgs(s.args) }
    Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).imePadding().verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StepIcon(s, 44.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Node ${i + 1}", fontSize = 11.sp, color = P.muted)
                Text(s.toolSlug, fontFamily = Mono, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton({ onMove(-1) }, enabled = i > 0) { Icon(Icons.Outlined.KeyboardArrowUp, "Move up") }
            IconButton({ onMove(1) }, enabled = i < count - 1) { Icon(Icons.Outlined.KeyboardArrowDown, "Move down") }
            IconButton(onDelete) { Icon(Icons.Outlined.Delete, "Remove", tint = P.danger) }
        }
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(s.note, { onChange(s.copy(note = it)) }, Modifier.fillMaxWidth(), label = { Text("Label") }, singleLine = true)

        SectionLabel("Icon")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { IconChoice(s.icon == null, { onChange(s.copy(icon = null)) }) { IconTile(null, s.app, 40.dp, fallbackUrl = s.logo ?: logoUrl(s.app), slug = s.app) } }
            items(Glyphs.keys.toList()) { k ->
                IconChoice(s.icon == "mi:$k", { onChange(s.copy(icon = "mi:$k")) }) { IconTile("mi:$k", k, 40.dp) }
            }
        }
        var url by remember(s.icon) { mutableStateOf(s.icon?.takeUnless { it.startsWith("mi:") }.orEmpty()) }
        OutlinedTextField(url, { url = it; onChange(s.copy(icon = it.trim().ifEmpty { null })) }, Modifier.fillMaxWidth().padding(top = 8.dp),
            label = { Text("…or image URL") }, singleLine = true, textStyle = TextStyle(fontSize = 13.sp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))

        SectionLabel("Arguments")
        OutlinedTextField(s.args, { onChange(s.copy(args = it)) }, Modifier.fillMaxWidth(),
            textStyle = TextStyle(fontFamily = Mono, fontSize = 12.sp), minLines = 4,
            colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = P.codeBg, focusedContainerColor = P.codeBg),
            isError = !valid, supportingText = if (valid) null else ({ Text("Invalid JSON object") }))
        Text("Use earlier results: {{step1.path.to.field}}, {{prev.field}}, {{now}}, {{date}}. " +
            "Array items by index, e.g. {{step1.messages.0.id}}.", fontSize = 12.sp, color = P.muted)
    }
}

@Composable
private fun IconChoice(selected: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Box(Modifier.clip(shape).border(2.dp, if (selected) P.accent else Color.Transparent, shape)
        .clickable(onClick = onClick).padding(3.dp)) { content() }
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
private fun TemplateDialog(initial: String, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var n by remember { mutableStateOf(initial) }
    var d by remember { mutableStateOf("") }
    AlertDialog(onDismiss, title = { Text("Save as template") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(n, { n = it }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(d, { d = it }, label = { Text("Description (optional)") })
            }
        },
        confirmButton = { Button({ onSave(n.trim(), d.trim()) }, enabled = n.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } })
}
