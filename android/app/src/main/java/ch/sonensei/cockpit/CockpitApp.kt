package ch.sonensei.cockpit

import android.app.Application
import ch.sonensei.cockpit.capture.entry.CaptureWidgetUpdater
import ch.sonensei.cockpit.push.PushRegistrar
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/** Application entry point; the root of the Hilt dependency graph. */
@HiltAndroidApp
class CockpitApp : Application() {
    @Inject lateinit var captureWidgetUpdater: CaptureWidgetUpdater

    @Inject lateinit var pushRegistrar: PushRegistrar

    override fun onCreate() {
        super.onCreate()
        captureWidgetUpdater.start()
        pushRegistrar.start()
    }
}
