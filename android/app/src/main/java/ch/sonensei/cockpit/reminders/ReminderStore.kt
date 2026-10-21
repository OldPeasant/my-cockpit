package ch.sonensei.cockpit.reminders

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import ch.sonensei.cockpit.core.network.generated.models.ReminderResponse
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** A reminder this device has a local alarm for. Only what the notification needs; the item's content stays on the server. */
@Serializable
data class ScheduledReminder(
    val id: String,
    /** Item type as the API names it, e.g. `TASK`. */
    val itemType: String,
    val itemId: String,
    val title: String?,
    val remindAtMillis: Long,
)

/**
 * The reminder to schedule for a synced one, or null if none should be: fired by the server already, or its item is
 * not available (trashed, deleted or not resolvable; the server does not notify for a gone item either).
 */
internal fun ReminderResponse.toScheduled(): ScheduledReminder? {
    if (firedAt != null || !item.available) return null
    return ScheduledReminder(
        id = id.toString(),
        itemType = item.type.value,
        itemId = item.id.toString(),
        title = item.title,
        remindAtMillis = remindAt.toInstant().toEpochMilli(),
    )
}

/**
 * The reminders with a local alarm, kept on the device so alarms survive a reboot without network, and the ids already
 * notified, so a reminder the server has not marked fired yet is not shown twice. Private app storage; cleared on logout.
 */
@Singleton
class ReminderStore
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) {
        private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        private val json = Json { ignoreUnknownKeys = true }
        private val scheduledState = MutableStateFlow(readScheduled())

        /** The reminders with an alarm, earliest first. */
        val scheduled: StateFlow<List<ScheduledReminder>> = scheduledState.asStateFlow()

        @Synchronized
        fun find(id: String): ScheduledReminder? = scheduledState.value.firstOrNull { it.id == id }

        @Synchronized
        fun replaceScheduled(reminders: List<ScheduledReminder>) {
            prefs.edit { putString(KEY_SCHEDULED, json.encodeToString(reminders)) }
            scheduledState.value = reminders
        }

        @Synchronized
        fun notifiedIds(): Set<String> = prefs.getStringSet(KEY_NOTIFIED, emptySet()).orEmpty().toSet()

        @Synchronized
        fun markNotified(id: String) {
            prefs.edit { putStringSet(KEY_NOTIFIED, notifiedIds() + id) }
        }

        /** Forgets notified ids that are not in [keep]: the server no longer lists them, so they cannot come back. */
        @Synchronized
        fun retainNotified(keep: Set<String>) {
            prefs.edit { putStringSet(KEY_NOTIFIED, notifiedIds().intersect(keep)) }
        }

        @Synchronized
        fun isPromptDismissed(): Boolean = prefs.getBoolean(KEY_PROMPT_DISMISSED, false)

        @Synchronized
        fun dismissPrompt() {
            prefs.edit { putBoolean(KEY_PROMPT_DISMISSED, true) }
        }

        @Synchronized
        fun wasNotificationPermissionRequested(): Boolean = prefs.getBoolean(KEY_PERMISSION_REQUESTED, false)

        @Synchronized
        fun markNotificationPermissionRequested() {
            prefs.edit { putBoolean(KEY_PERMISSION_REQUESTED, true) }
        }

        @Synchronized
        fun clear() {
            prefs.edit { clear() }
            scheduledState.value = emptyList()
        }

        private fun readScheduled(): List<ScheduledReminder> =
            prefs.getString(KEY_SCHEDULED, null)?.let {
                try {
                    json.decodeFromString<List<ScheduledReminder>>(it)
                } catch (_: Exception) {
                    // Unreadable (e.g. written by an incompatible version): the next sync writes it again.
                    emptyList()
                }
            } ?: emptyList()

        internal companion object {
            const val PREFS_NAME = "reminders"
            const val KEY_SCHEDULED = "scheduled"
            const val KEY_NOTIFIED = "notified"
            const val KEY_PROMPT_DISMISSED = "prompt_dismissed"
            const val KEY_PERMISSION_REQUESTED = "notification_permission_requested"
        }
    }
