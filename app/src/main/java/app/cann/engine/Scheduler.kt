package app.cann.engine

import android.content.Context
import androidx.work.*
import app.cann.data.Schedule
import app.cann.data.Store
import app.cann.data.Workflow
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

object Scheduler {
    fun next(s: Schedule, from: Long, zone: ZoneId = ZoneId.systemDefault()): Long? = when (s) {
        Schedule.Manual -> null
        is Schedule.Once -> s.atMillis.takeIf { it > from }
        is Schedule.Interval -> from + s.minutes.coerceAtLeast(15) * 60_000L
        is Schedule.Daily -> {
            val now = ZonedDateTime.ofInstant(Instant.ofEpochMilli(from), zone)
            var t = now.withHour(s.hour).withMinute(s.minute).withSecond(0).withNano(0)
            if (!t.isAfter(now)) t = t.plusDays(1)
            repeat(7) { if (s.days.isEmpty() || t.dayOfWeek.value in s.days) return t.toInstant().toEpochMilli(); t = t.plusDays(1) }
            null
        }
    }

    private fun tag(id: String) = "wf-$id"

    enum class Mode { REPLACE, CHAIN, ENSURE }

    /**
     * Recomputes and saves [wf]'s next run, then queues its job.
     * REPLACE: after an edit; drops the queued job. CHAIN: from inside the running job.
     * ENSURE: app start; keeps a future nextRunAt and only queues if nothing is pending.
     */
    fun sync(ctx: Context, wf: Workflow, mode: Mode = Mode.REPLACE): Workflow {
        val wm = WorkManager.getInstance(ctx)
        val now = System.currentTimeMillis()
        if (mode == Mode.ENSURE && wf.enabled &&
            wm.getWorkInfosByTag(tag(wf.id)).get().any { it.state == WorkInfo.State.ENQUEUED }
        ) return wf
        val nextAt = when {
            !wf.enabled || wf.steps.isEmpty() -> null
            mode == Mode.ENSURE && (wf.nextRunAt ?: 0) > now -> wf.nextRunAt
            else -> next(wf.schedule, now)
        }
        val updated = wf.copy(nextRunAt = nextAt)
        Store.get(ctx).upsert(updated)
        when (mode) {
            Mode.REPLACE -> wm.cancelAllWorkByTag(tag(wf.id))
            Mode.ENSURE -> if (nextAt == null) wm.cancelAllWorkByTag(tag(wf.id))
            Mode.CHAIN -> {}
        }
        if (nextAt != null) {
            val req = OneTimeWorkRequestBuilder<RunWorker>()
                .setInitialDelay((nextAt - now).coerceAtLeast(0), TimeUnit.MILLISECONDS)
                .setInputData(workDataOf(RunWorker.KEY_ID to wf.id, RunWorker.KEY_AT to nextAt))
                .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
                .addTag(tag(wf.id))
                .build()
            wm.enqueue(req)
        }
        return updated
    }

    fun cancel(ctx: Context, id: String) { WorkManager.getInstance(ctx).cancelAllWorkByTag(tag(id)) }

    fun runNow(ctx: Context, id: String) {
        val req = OneTimeWorkRequestBuilder<RunWorker>()
            .setInputData(workDataOf(RunWorker.KEY_ID to id, RunWorker.KEY_MANUAL to true))
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(ctx).enqueue(req)
    }
}
