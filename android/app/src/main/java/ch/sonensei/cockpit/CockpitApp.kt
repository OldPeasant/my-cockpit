package ch.sonensei.cockpit

import android.app.Application
import ch.sonensei.cockpit.capture.entry.CaptureWidgetUpdater
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/** Application entry point; the root of the Hilt dependency graph. */
@HiltAndroidApp
class CockpitApp : Application() {
    @Inject lateinit var captureWidgetUpdater: CaptureWidgetUpdater

    override fun onCreate() {
        super.onCreate()
        captureWidgetUpdater.start()
    }
}
