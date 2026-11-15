package app.cann.engine

import app.cann.data.*
import kotlinx.serialization.json.*
import java.time.ZonedDateTime
import java.util.UUID

object Runner {
    /** Runs one node with [outputs] from earlier nodes; returns the result and raw data. */
    suspend fun step(api: Composio, step: Step, outputs: List<JsonElement>, now: ZonedDateTime = ZonedDateTime.now()): Pair<StepResult, JsonElement> {
        var missing = emptyList<String>()
        val r = runCatching {
            val raw = json.parseToJsonElement(step.args.ifBlank { "{}" })
            missing = Templates.unresolved(raw, outputs, now)
            val args = Templates.fill(raw, outputs, now) as? JsonObject ?: error("Args must be a JSON object")
            api.execute(step.toolSlug, args, step.version)
        }
        val res = r.fold(
            { StepResult(step.toolSlug, it.ok, if (it.ok) it.data.toString() else it.error ?: it.data.toString(), missing) },
            { StepResult(step.toolSlug, false, it.message ?: it.toString(), missing) },
        )
        return res to (r.getOrNull()?.data ?: JsonNull)
    }

    suspend fun run(store: Store, wf: Workflow): Run {
        val s = store.db.value.settings
        val api = Composio(s.apiKey, s.userId)
        val start = System.currentTimeMillis()
        val outputs = mutableListOf<JsonElement>()
        val results = mutableListOf<StepResult>()
        val now = ZonedDateTime.now()
        for (st in wf.steps) {
            val (res, data) = step(api, st, outputs, now)
            outputs += data
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
