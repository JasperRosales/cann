package app.cann

import android.app.Application
import app.cann.data.Store
import app.cann.engine.Scheduler
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.SvgDecoder

class CannApp : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        // Re-arm schedules (e.g. after an update) and skip past one-offs.
        Thread {
            Store.get(this).db.value.workflows.forEach { Scheduler.sync(this, it, Scheduler.Mode.ENSURE) }
        }.start()
    }

    // App logos from Composio and Simple Icons are SVGs.
    override fun newImageLoader() = ImageLoader.Builder(this)
        .components { add(SvgDecoder.Factory()) }
        .crossfade(true)
        .build()
}
