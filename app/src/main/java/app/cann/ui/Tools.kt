package app.cann.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.runtime.saveable.rememberSaveable
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
    val known = remember { mutableStateMapOf<String, Toolkit>() }
    val store = remember { Store.get(ctx) }
    val db by store.db.collectAsState()
    var filter by rememberSaveable { mutableStateOf(AppFilter.All) }

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
                kits.forEach { k -> k.logo?.let { logos[k.slug] = it }; known[k.slug] = k }
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
                IconTile(a.logo, a.name, 36.dp, fallbackUrl = logoUrl(a.slug), slug = a.slug)
                Spacer(Modifier.width(10.dp))
                Text(a.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                val acc = accounts.filter { it.toolkit == a.slug }
                val fav = a.slug in db.favApps
                IconButton({ store.toggleFav(a.slug) }) {
                    Icon(if (fav) Icons.Outlined.Star else Icons.Outlined.StarBorder, if (fav) "Unstar" else "Star",
                        tint = if (fav) P.warn else P.muted)
                }
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
        if (app == null) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppFilter.entries.forEach { f ->
                FilterChip(filter == f, { filter = f }, label = { Text(f.label) },
                    leadingIcon = { Icon(f.icon, null, Modifier.size(18.dp)) })
            }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp), color = P.accent)
        err?.let { Text(it, color = P.danger, fontSize = 13.sp, modifier = Modifier.padding(16.dp, 4.dp)) }

        val connected = accounts.filter { it.status == "ACTIVE" }.map { it.toolkit }.toSet()
        val used = db.workflows.flatMap { w -> w.steps.map { it.app } }.toSet()
        val shownKits = when (filter) {
            AppFilter.All -> kits
            else -> {
                val slugs = when (filter) {
                    AppFilter.Fav -> db.favApps
                    AppFilter.Connected -> connected
                    else -> used
                }
                slugs.sorted().map { known[it] ?: Toolkit(it, prettySlug(it), "", null) }
                    .filter { q.isBlank() || it.name.contains(q.trim(), true) || it.slug.contains(q.trim(), true) }
            }
        }
        val shownTools = if (filter == AppFilter.All || app != null) tools else emptyList()
        LazyVerticalGrid(GridCells.Adaptive(96.dp), Modifier.weight(1f).imePadding(),
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 32.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (app == null && shownKits.isNotEmpty()) {
                if (q.isNotBlank() && shownTools.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { SectionLabel("Apps") }
                items(shownKits, key = { "k" + it.slug }) { k ->
                    AppTile(k, k.slug in connected, k.slug in db.favApps, onLong = { store.toggleFav(k.slug) }) { app = k; q = "" }
                }
            }
            if (shownTools.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    SectionLabel(if (app != null) "${tools.size}${if (toolNext != null) "+" else ""} actions" else "Actions")
                }
                items(shownTools, key = { "t" + it.slug }, span = { GridItemSpan(maxLineSpan) }) { t ->
                    ToolRow(t, logos[t.toolkit] ?: app?.logo) { detail = t }
                }
            }
            val more = if (app == null && filter != AppFilter.All) null else if (app == null && q.isBlank()) kitNext else toolNext
            if (more != null && !loading) item(span = { GridItemSpan(maxLineSpan) }) {
                TextButton({ loadMore() }, Modifier.fillMaxWidth()) { Text("Load more") }
            }
            if (!loading && err == null && (app != null || shownKits.isEmpty()) && shownTools.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                Text(filter.empty.takeIf { app == null && q.isBlank() } ?: "Nothing found.", color = P.muted, textAlign = TextAlign.Center, modifier = Modifier.padding(32.dp))
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

enum class AppFilter(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector, val empty: String) {
    All("All", Icons.Outlined.Apps, "Nothing found."),
    Fav("Favorites", Icons.Outlined.StarBorder, "No favorites yet. Long-press an app or tap its star."),
    Connected("Connected", Icons.Outlined.Link, "No connected apps yet."),
    Used("In my flows", Icons.Outlined.AccountTree, "No apps used in your flows yet."),
}

private fun prettySlug(s: String) = s.replace('_', ' ').replaceFirstChar { it.uppercase() }

/** Square app tile; long-press stars it. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppTile(k: Toolkit, connected: Boolean, fav: Boolean, onLong: () -> Unit, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        Modifier.aspectRatio(1f).clip(shape).background(P.surfaceLo)
            .border(1.dp, if (connected) P.success.copy(alpha = .6f) else P.stroke, shape)
            .combinedClickable(onLongClick = onLong, onClick = onClick),
    ) {
        Column(Modifier.fillMaxSize().padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
            IconTile(k.logo, k.name, 44.dp, fallbackUrl = logoUrl(k.slug), slug = k.slug)
            Spacer(Modifier.height(6.dp))
            Text(k.name, fontSize = 12.sp, maxLines = 1, textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis)
        }
        if (fav) Icon(Icons.Outlined.Star, "Favorite", Modifier.align(Alignment.TopStart).padding(6.dp).size(14.dp), tint = P.warn)
        if (connected) Box(Modifier.align(Alignment.TopEnd).padding(8.dp).size(9.dp)
            .clip(RoundedCornerShape(50)).background(P.success))
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
        IconTile(logo, t.toolkit, 36.dp, fallbackUrl = logoUrl(t.toolkit), slug = t.toolkit)
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
            IconTile(logo, t.toolkit, 44.dp, fallbackUrl = logoUrl(t.toolkit), slug = t.toolkit)
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
