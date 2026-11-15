package app.cann

import android.app.Application
import app.cann.data.Store
import app.cann.engine.Scheduler

class CannApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Re-arm schedules (e.g. after an update) and skip past one-offs.
        Thread {
            Store.get(this).db.value.workflows.forEach { Scheduler.sync(this, it, Scheduler.Mode.ENSURE) }
        }.start()
    }
}
