package ch.sonensei.cockpit.reminders

import android.content.Intent
import ch.sonensei.cockpit.ui.navigation.CockpitDestination
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** An item a reminder notification asked to open. */
data class OpenItemRequest(
    val itemType: String,
    val itemId: String,
) {
    /**
     * Where the app shows the item. The Android app has no item detail screens yet, so this is the screen listing items
     * of the type; tasks open in Next, where actionable tasks are.
     */
    val destination: CockpitDestination
        get() =
            when (itemType) {
                "TASK" -> CockpitDestination.Next
                "PROJECT" -> CockpitDestination.Projects
                "NOTE" -> CockpitDestination.Notes
                "EVENT" -> CockpitDestination.Calendar
                "EMAIL" -> CockpitDestination.Mail
                "FILE", "FOLDER" -> CockpitDestination.Files
                "CONTACT" -> CockpitDestination.Contacts
                "BOOKMARK" -> CockpitDestination.Bookmarks
                "JOURNAL_ENTRY" -> CockpitDestination.Journal
                "HABIT" -> CockpitDestination.Habits
                else -> CockpitDestination.start
            }
}

/**
 * Hands the item of a tapped reminder notification from the activity to the shell. The request waits until the shell
 * takes it, so it also survives the login screen being shown first.
 */
object ReminderOpenRequests {
    const val ACTION_OPEN_ITEM = "ch.sonensei.cockpit.reminders.OPEN_ITEM"
    const val EXTRA_ITEM_TYPE = "ch.sonensei.cockpit.reminders.ITEM_TYPE"
    const val EXTRA_ITEM_ID = "ch.sonensei.cockpit.reminders.ITEM_ID"

    private val pending = MutableStateFlow<OpenItemRequest?>(null)

    val requests: StateFlow<OpenItemRequest?> = pending.asStateFlow()

    /** Keeps the request of [intent] if it is a reminder's; returns whether it was. */
    fun offer(intent: Intent?): Boolean {
        if (intent?.action != ACTION_OPEN_ITEM) return false
        val type = intent.getStringExtra(EXTRA_ITEM_TYPE) ?: return false
        val id = intent.getStringExtra(EXTRA_ITEM_ID) ?: return false
        pending.value = OpenItemRequest(type, id)
        return true
    }

    /** Takes [request] if it is still the pending one; true for the one caller that gets it. */
    fun take(request: OpenItemRequest): Boolean = pending.compareAndSet(request, null)
}
