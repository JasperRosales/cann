package app.cann.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.padding
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
