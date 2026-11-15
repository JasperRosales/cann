package app.cann.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import java.io.File

val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }

/** Whole app state in one JSON file. Small data, so simple wins. */
class Store private constructor(private val file: File) {
    private val _db = MutableStateFlow(load())
    val db: StateFlow<Db> = _db.asStateFlow()

    private fun load(): Db = runCatching { json.decodeFromString<Db>(file.readText()) }.getOrDefault(Db())

    @Synchronized
    fun edit(f: (Db) -> Db) {
        _db.update(f)
        val tmp = File(file.path + ".tmp")
        tmp.writeText(json.encodeToString(Db.serializer(), _db.value))
        tmp.renameTo(file)
    }

    fun workflow(id: String) = db.value.workflows.firstOrNull { it.id == id }

    fun upsert(w: Workflow) = edit { d ->
        val list = d.workflows.toMutableList()
        val i = list.indexOfFirst { it.id == w.id }
        if (i >= 0) list[i] = w else list += w
        d.copy(workflows = list)
    }

    fun delete(id: String) = edit { d ->
        d.copy(workflows = d.workflows.filterNot { it.id == id }, runs = d.runs.filterNot { it.workflowId == id })
    }

    fun toggleFav(slug: String) = edit { d ->
        d.copy(favApps = if (slug in d.favApps) d.favApps - slug else d.favApps + slug)
    }

    fun saveTemplate(t: Template) = edit { d -> d.copy(templates = d.templates.filterNot { it.id == t.id } + t) }

    fun deleteTemplate(id: String) = edit { d -> d.copy(templates = d.templates.filterNot { it.id == id }) }

    fun addRun(r: Run) = edit { d -> d.copy(runs = (listOf(r) + d.runs).take(MAX_RUNS)) }

    companion object {
        private const val MAX_RUNS = 200
        @Volatile private var inst: Store? = null
        fun get(ctx: Context): Store = inst ?: synchronized(this) {
            inst ?: Store(File(ctx.applicationContext.filesDir, "cann.json")).also { inst = it }
        }
    }
}
