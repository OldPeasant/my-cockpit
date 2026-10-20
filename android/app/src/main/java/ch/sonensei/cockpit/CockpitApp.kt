package ch.sonensei.cockpit

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/** Application entry point; the root of the Hilt dependency graph. */
@HiltAndroidApp
class CockpitApp : Application()
