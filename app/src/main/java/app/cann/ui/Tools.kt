package app.cann.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.cann.data.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * n8n-style node panel: browse every Composio app, open one to see all its tools,
 * or search actions across all apps. [onPick] null = browse only (Apps tab).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolBrowser(
    s: Settings, modifier: Modifier = Modifier, title: String = "Apps & tools",
    onClose: (() -> Unit)? = null, onPick: ((Tool, Toolkit?) -> Unit)? = null,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val api = remember(s) { Composio(s.apiKey, s.userId) }
    var q by remember { mutableStateOf("") }
    var app by remember { mutableStateOf<Toolkit?>(null) }
    var kits by remember { mutableStateOf<List<Toolkit>>(emptyList()) }
    var kitNext by remember { mutableStateOf<String?>(null) }
    var tools by remember { mutableStateOf<List<Tool>>(emptyList()) }
    var toolNext by remember { mutableStateOf<String?>(null) }
    var accounts by remember { mutableStateOf<List<Account>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    var detail by remember { mutableStateOf<Tool?>(null) }
    var busy by remember { mutableStateOf(false) }
    val logos = remember { mutableStateMapOf<String, String>() }

    suspend fun refreshAccounts() { runCatching { accounts = api.accounts() } }

    LaunchedEffect(api, q, app) {
        delay(300)
        loading = true; err = null
        runCatching {
            val a = app
            if (a != null) {
                api.toolPage(a.slug, q.trim()).let { tools = it.items; toolNext = it.next }
            } else {
                api.toolkitPage(q.trim()).let { kits = it.items; kitNext = it.next }
                kits.forEach { k -> k.logo?.let { logos[k.slug] = it } }
                if (q.isNotBlank()) api.toolPage(null, q.trim()).let { tools = it.items; toolNext = it.next }
                else { tools = emptyList(); toolNext = null }
            }
        }.onFailure { err = it.message }
        loading = false
    }
    LaunchedEffect(api) { refreshAccounts() }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) scope.launch { refreshAccounts() } }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    if (app != null) BackHandler { app = null; q = "" }

    fun connect(slug: String) {
        busy = true
        scope.launch {
            runCatching { api.connectLink(slug) }
                .onSuccess { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) }
                .onFailure { err = it.message }
            busy = false
        }
    }
    fun loadMore() = scope.launch {
        runCatching {
            if (app == null && q.isBlank()) kitNext?.let { c ->
                api.toolkitPage(q.trim(), c).let { kits = kits + it.items; kitNext = it.next }
            } else toolNext?.let { c ->
                api.toolPage(app?.slug, q.trim(), c).let { tools = tools + it.items; toolNext = it.next }
            }
        }.onFailure { err = it.message }
    }

    Column(modifier.fillMaxSize().background(P.bg)) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            val a = app
            if (a != null) {
                IconButton({ app = null; q = "" }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
                IconTile(a.logo, a.name, 36.dp, fallbackUrl = logoUrl(a.slug))
                Spacer(Modifier.width(10.dp))
                Text(a.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                val acc = accounts.filter { it.toolkit == a.slug }
                if (acc.any { it.status == "ACTIVE" }) StatusPill("Connected", P.success)
                else FilledTonalButton({ connect(a.slug) }, enabled = !busy) {
                    Text(if (busy) "…" else if (acc.isNotEmpty()) acc.first().status.lowercase() else "Connect")
                }
            } else {
                Spacer(Modifier.width(12.dp))
                Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            }
            onClose?.let { IconButton(it) { Icon(Icons.Outlined.Close, "Close") } }
        }
        OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth().padding(16.dp, 8.dp), singleLine = true,
            shape = RoundedCornerShape(14.dp),
            placeholder = { Text(if (app == null) "Search apps or actions…" else "Search ${app!!.name} actions…") },
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
            trailingIcon = { if (q.isNotEmpty()) IconButton({ q = "" }) { Icon(Icons.Outlined.Close, "Clear") } })
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp), color = P.accent)
        err?.let { Text(it, color = P.danger, fontSize = 13.sp, modifier = Modifier.padding(16.dp, 4.dp)) }

        val connected = accounts.filter { it.status == "ACTIVE" }.map { it.toolkit }.toSet()
        LazyVerticalGrid(GridCells.Adaptive(96.dp), Modifier.weight(1f).imePadding(),
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 32.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (app == null && kits.isNotEmpty()) {
                if (q.isNotBlank()) item(span = { GridItemSpan(maxLineSpan) }) { SectionLabel("Apps") }
                items(kits, key = { "k" + it.slug }) { k ->
                    AppTile(k, k.slug in connected) { app = k; q = "" }
                }
            }
            if (tools.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    SectionLabel(if (app != null) "${tools.size}${if (toolNext != null) "+" else ""} actions" else "Actions")
                }
                items(tools, key = { "t" + it.slug }, span = { GridItemSpan(maxLineSpan) }) { t ->
                    ToolRow(t, logos[t.toolkit] ?: app?.logo) { detail = t }
                }
            }
            val more = if (app == null && q.isBlank()) kitNext else toolNext
            if (more != null && !loading) item(span = { GridItemSpan(maxLineSpan) }) {
                TextButton({ loadMore() }, Modifier.fillMaxWidth()) { Text("Load more") }
            }
            if (!loading && err == null && kits.isEmpty() && tools.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                Text("Nothing found.", color = P.muted, textAlign = TextAlign.Center, modifier = Modifier.padding(32.dp))
            }
        }
    }

    detail?.let { t ->
        val kit = app ?: kits.firstOrNull { it.slug == t.toolkit }
        ModalBottomSheet({ detail = null }, containerColor = P.surfaceLo) {
            ToolDetail(t, logos[t.toolkit] ?: kit?.logo, onPick?.let { pick -> { detail = null; pick(t, kit) } })
        }
    }
}

@Composable
fun SectionLabel(t: String) = Text(t.uppercase(), color = P.muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
    letterSpacing = 1.sp, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))

@Composable
fun StatusPill(t: String, c: androidx.compose.ui.graphics.Color) = Text(t, color = c, fontSize = 11.sp,
    fontWeight = FontWeight.SemiBold, modifier = Modifier.clip(RoundedCornerShape(50))
        .background(c.copy(alpha = 0.14f)).padding(horizontal = 10.dp, vertical = 4.dp))

@Composable
private fun AppTile(k: Toolkit, connected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier.clip(shape).background(P.surfaceLo).border(1.dp, if (connected) P.success.copy(alpha = .6f) else P.stroke, shape)
            .clickable(onClick = onClick).padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            IconTile(k.logo, k.name, 48.dp, fallbackUrl = logoUrl(k.slug))
            if (connected) Box(Modifier.align(Alignment.TopEnd).offset(4.dp, (-4).dp).size(12.dp)
                .clip(RoundedCornerShape(50)).background(P.success))
        }
        Spacer(Modifier.height(8.dp))
        Text(k.name, fontSize = 12.sp, maxLines = 2, textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis,
            lineHeight = 14.sp)
    }
}

@Composable
private fun ToolRow(t: Tool, logo: String?, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(P.surfaceLo).border(1.dp, P.stroke, shape)
            .clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(logo, t.toolkit, 36.dp, fallbackUrl = logoUrl(t.toolkit))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(t.name, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(t.description, fontSize = 12.sp, color = P.muted, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 15.sp)
        }
        Icon(Icons.Outlined.ChevronRight, null, tint = P.muted)
    }
}

@Composable
private fun ToolDetail(t: Tool, logo: String?, onAdd: (() -> Unit)?) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(logo, t.toolkit, 44.dp, fallbackUrl = logoUrl(t.toolkit))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(t.name, style = MaterialTheme.typography.titleMedium)
                Text(t.slug, fontFamily = Mono, fontSize = 11.sp, color = P.muted)
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(t.description, fontSize = 13.sp)
        if (t.params.isNotEmpty()) {
            SectionLabel("Inputs")
            Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                t.params.forEach { p ->
                    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(p.name, fontFamily = Mono, fontSize = 12.sp, color = P.accent)
                        if (p.required) Text(" *", color = P.danger, fontSize = 12.sp)
                        Text("  ${p.type}", fontSize = 11.sp, color = P.muted)
                    }
                    if (p.description.isNotBlank()) Text(p.description, fontSize = 12.sp, color = P.muted, lineHeight = 15.sp)
                }
            }
        }
        onAdd?.let {
            Spacer(Modifier.height(16.dp))
            Button(it, Modifier.fillMaxWidth()) { Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(6.dp)); Text("Add node") }
        }
    }
}
