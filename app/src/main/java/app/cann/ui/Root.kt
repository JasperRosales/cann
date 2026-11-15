package app.cann.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import app.cann.data.Store

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
        EditorScreen(store, id.ifEmpty { null }, onClose = { editing = null })
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
            Tab.Flows -> FlowsScreen(db, m, onOpen = { editing = it }, needsKey = db.settings.apiKey.isBlank(), goSettings = { tab = Tab.Settings })
            Tab.Runs -> RunsScreen(db.runs, m)
            Tab.Apps -> AppsScreen(db.settings, m)
            Tab.Settings -> SettingsScreen(store, db.settings, m)
        }
    }
}

/** Checks GitHub once per app open and offers the newer APK. */
@Composable
private fun UpdatePrompt() {
    val ctx = LocalContext.current
    var update by remember { mutableStateOf<app.cann.data.Update?>(null) }
    var checked by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!checked) { checked = true; update = app.cann.data.Updater.check(ctx) }
    }
    update?.let { u ->
        AlertDialog(
            onDismissRequest = { update = null },
            icon = { Icon(Icons.Outlined.SystemUpdate, null, tint = P.accent) },
            title = { Text("Update to v${u.version}") },
            text = {
                androidx.compose.foundation.layout.Column(
                    Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                    Text("You have v${app.cann.data.Updater.currentVersion(ctx)}.", color = P.muted)
                    if (u.notes.isNotBlank()) Text(u.notes, Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = {
                Button({
                    ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(u.apkUrl)))
                    update = null
                }) { Text("Download") }
            },
            dismissButton = { TextButton({ update = null }) { Text("Later") } },
        )
    }
}
