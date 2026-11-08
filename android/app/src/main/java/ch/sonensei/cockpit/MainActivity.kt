package ch.sonensei.cockpit

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import ch.sonensei.cockpit.auth.CockpitRoot
import ch.sonensei.cockpit.core.network.AuthSession
import ch.sonensei.cockpit.core.settings.UserSettingsRepository
import ch.sonensei.cockpit.reminders.ReminderOpenRequests
import ch.sonensei.cockpit.ui.theme.CockpitSettingsTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var userSettings: UserSettingsRepository

    @Inject lateinit var authSession: AuthSession

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // A recreated activity has handled its intent already, and a relaunch from Recents replays an old one.
        val fromHistory = intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
        if (savedInstanceState == null && !fromHistory) ReminderOpenRequests.offer(intent)
        setContent { CockpitSettingsTheme(userSettings.settings) { CockpitRoot() } }
    }

    /** Back in front: the settings may have been changed in the web app meanwhile (the server sends no event). */
    override fun onStart() {
        super.onStart()
        if (authSession.isLoggedIn) lifecycleScope.launch { userSettings.refresh() }
    }

    /** A tapped reminder notification while the app is open. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        ReminderOpenRequests.offer(intent)
    }
}
