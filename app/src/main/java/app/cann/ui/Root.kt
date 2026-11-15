package app.cann.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import app.cann.data.BuiltinTemplates
import app.cann.data.Store
import app.cann.data.Update
import app.cann.data.Updater
import kotlinx.coroutines.launch

private enum class Tab(val label: String, val icon: ImageVector) {
    Flows("Flows", Icons.Outlined.AccountTree),
    Runs("Runs", Icons.Outlined.History),
    Apps("Apps", Icons.Outlined.Apps),
    Settings("Settings", Icons.Outlined.Settings),
}

@Composable
fun CannRoot() {
    val store = Store.get(LocalContext.current)
    val db by store.db.collectAsState()
    var tab by rememberSaveable { mutableStateOf(Tab.Flows) }
    // null = closed, "" = new workflow, else editing that id
    var editing by rememberSaveable { mutableStateOf<String?>(null) }

    UpdatePrompt()

    editing?.let { id ->
        BackHandler { editing = null }
        // "tpl:<id>" opens a new flow seeded from that template.
        val tpl = id.removePrefix("tpl:").takeIf { id.startsWith("tpl:") }
            ?.let { t -> (BuiltinTemplates.all + db.templates).firstOrNull { it.id == t } }
        EditorScreen(store, id.takeUnless { it.isEmpty() || tpl != null || it.startsWith("tpl:") },
            seed = tpl?.flow, onClose = { editing = null })
        return
    }

    Scaffold(bottomBar = {
        NavigationBar {
            Tab.entries.forEach { t ->
                NavigationBarItem(t == tab, { tab = t }, { Icon(t.icon, null) }, label = { Text(t.label) })
            }
        }
    }) { pad ->
        val m = Modifier.padding(pad)
        when (tab) {
            Tab.Flows -> FlowsScreen(store, db, m, onOpen = { editing = it }, needsKey = db.settings.apiKey.isBlank(), goSettings = { tab = Tab.Settings })
            Tab.Runs -> RunsScreen(db.runs, m)
            Tab.Apps -> AppsScreen(db.settings, m)
            Tab.Settings -> SettingsScreen(store, db.settings, m)
        }
    }
}

/** Checks GitHub once per app open, then downloads and installs the new APK in-app. */
@Composable
private fun UpdatePrompt() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var update by remember { mutableStateOf<Update?>(null) }
    var checked by rememberSaveable { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var apk by remember { mutableStateOf<java.io.File?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        if (!checked) { checked = true; update = Updater.check(ctx) }
    }
    fun install(f: java.io.File) =
        if (Updater.canInstall(ctx)) Updater.install(ctx, f) else Updater.openInstallSettings(ctx)
    fun start(u: Update) {
        val url = u.apkUrl ?: return ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u.pageUrl)))
        err = null; progress = 0f
        scope.launch {
            runCatching { Updater.download(ctx, url) { progress = it } }
                .onSuccess { apk = it; install(it) }
                .onFailure { err = it.message }
            progress = null
        }
    }
    update?.let { u ->
        val busy = progress != null
        AlertDialog(
            onDismissRequest = { if (!busy) update = null },
            icon = { Icon(Icons.Outlined.SystemUpdate, null, tint = P.accent) },
            title = { Text("Update to v${u.version}") },
            text = {
                Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                    Text("You have v${Updater.currentVersion(ctx)}.", color = P.muted)
                    progress?.let { p ->
                        Spacer(Modifier.height(10.dp))
                        if (p < 0) LinearProgressIndicator(Modifier.fillMaxWidth(), color = P.accent)
                        else LinearProgressIndicator({ p }, Modifier.fillMaxWidth(), color = P.accent)
                    }
                    if (apk != null && !Updater.canInstall(ctx))
                        Text("Allow cann to install apps, then tap Install.", Modifier.padding(top = 8.dp), color = P.muted)
                    err?.let { Text(it, Modifier.padding(top = 8.dp), color = P.danger) }
                    if (u.notes.isNotBlank()) Text(u.notes, Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = {
                Button({ apk?.let(::install) ?: start(u) }, enabled = !busy) {
                    Text(when { busy -> "Downloading…"; apk != null -> "Install"; else -> "Update" })
                }
            },
            dismissButton = { TextButton({ update = null }, enabled = !busy) { Text("Later") } },
        )
    }
}
