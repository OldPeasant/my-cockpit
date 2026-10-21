package ch.sonensei.cockpit

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import ch.sonensei.cockpit.auth.CockpitRoot
import ch.sonensei.cockpit.reminders.ReminderOpenRequests
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // A recreated activity has handled its intent already, and a relaunch from Recents replays an old one.
        val fromHistory = intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
        if (savedInstanceState == null && !fromHistory) ReminderOpenRequests.offer(intent)
        setContent { CockpitTheme { CockpitRoot() } }
    }

    /** A tapped reminder notification while the app is open. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        ReminderOpenRequests.offer(intent)
    }
}
