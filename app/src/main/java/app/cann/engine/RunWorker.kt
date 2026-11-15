package app.cann.engine

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import app.cann.R
import app.cann.data.Schedule
import app.cann.data.Store

class RunWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val store = Store.get(applicationContext)
        val id = inputData.getString(KEY_ID) ?: return Result.failure()
        val wf = store.workflow(id) ?: return Result.failure()
        val manual = inputData.getBoolean(KEY_MANUAL, false)
        // Ignore stale jobs from an older version of this workflow's schedule.
        if (!manual && (!wf.enabled || wf.nextRunAt != inputData.getLong(KEY_AT, -1))) return Result.success()

        val run = Runner.run(store, wf)

        if (!manual) {
            // Once-off timelines switch off after firing; repeating ones queue the next run.
            val after = store.workflow(id) ?: return Result.success()
            if (after.schedule is Schedule.Once) Scheduler.sync(applicationContext, after.copy(enabled = false), Scheduler.Mode.CHAIN)
            else Scheduler.sync(applicationContext, after, Scheduler.Mode.CHAIN)
        }
        notify(applicationContext, wf.name, if (run.ok) "Done: ${run.steps.size} step(s)" else "Node ${run.steps.size} failed: " + (run.steps.lastOrNull()?.output?.let { Lint.hint(it) ?: it.take(120) } ?: "no steps"))
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        ensureChannel(applicationContext)
        val n = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_fg).setContentTitle("Running workflow").build()
        return ForegroundInfo(42, n)
    }

    companion object {
        const val KEY_ID = "id"
        const val KEY_MANUAL = "manual"
        const val KEY_AT = "at"
        private const val CHANNEL = "runs"

        fun ensureChannel(ctx: Context) {
            ctx.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(CHANNEL, "Workflow runs", NotificationManager.IMPORTANCE_DEFAULT))
        }

        fun notify(ctx: Context, title: String, text: String) {
            if (ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
            ensureChannel(ctx)
            val n = NotificationCompat.Builder(ctx, CHANNEL).setSmallIcon(R.drawable.ic_fg)
                .setContentTitle(title).setContentText(text).setAutoCancel(true).build()
            NotificationManagerCompat.from(ctx).notify(title.hashCode(), n)
        }
    }
}
