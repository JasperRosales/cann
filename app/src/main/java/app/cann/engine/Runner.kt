package app.cann.engine

import app.cann.data.*
import kotlinx.serialization.json.*
import java.time.ZonedDateTime
import java.util.UUID

object Runner {
    suspend fun run(store: Store, wf: Workflow): Run {
        val s = store.db.value.settings
        val api = Composio(s.apiKey, s.userId)
        val start = System.currentTimeMillis()
        val outputs = mutableListOf<JsonElement>()
        val results = mutableListOf<StepResult>()
        val now = ZonedDateTime.now()
        for (step in wf.steps) {
            val r = runCatching {
                val raw = json.parseToJsonElement(step.args.ifBlank { "{}" })
                val args = Templates.fill(raw, outputs, now) as? JsonObject
                    ?: error("Args must be a JSON object")
                api.execute(step.toolSlug, args, step.version)
            }
            val res = r.fold(
                { StepResult(step.toolSlug, it.ok, if (it.ok) it.data.toString() else it.error ?: it.data.toString()) },
                { StepResult(step.toolSlug, false, it.message ?: it.toString()) },
            )
            outputs += r.getOrNull()?.data ?: JsonNull
            results += res
            if (!res.ok && wf.stopOnError) break
        }
        val run = Run(
            UUID.randomUUID().toString(), wf.id, wf.name, start, System.currentTimeMillis(),
            results.size == wf.steps.size && results.all { it.ok }, results,
        )
        store.addRun(run)
        return run
    }
}
