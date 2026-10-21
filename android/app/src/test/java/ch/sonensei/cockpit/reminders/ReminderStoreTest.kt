package ch.sonensei.cockpit.reminders

import android.content.Context
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The device-local reminder store: survives a restart (reboot without network), tolerates unreadable data, clears fully. */
@RunWith(AndroidJUnit4::class)
class ReminderStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs = context.getSharedPreferences(ReminderStore.PREFS_NAME, Context.MODE_PRIVATE)

    @After
    fun tearDown() {
        prefs.edit(commit = true) { clear() }
    }

    private fun scheduled(
        n: Int,
        minutes: Long,
    ) = reminder(n, minutes).toScheduled()!!

    @Test
    fun `stored reminders are read back by a new store, as after a reboot`() {
        val reminders = listOf(scheduled(1, minutes = 10), scheduled(2, minutes = 20))
        ReminderStore(context).replaceScheduled(reminders)

        val reopened = ReminderStore(context)

        assertEquals(reminders, reopened.scheduled.value)
        assertEquals("Call plumber 2", reopened.find(reminderId(2).toString())?.title)
        assertNull(reopened.find(reminderId(3).toString()))
    }

    @Test
    fun `unreadable stored reminders start empty instead of crashing`() {
        prefs.edit(commit = true) { putString(ReminderStore.KEY_SCHEDULED, "{not json") }

        assertTrue(ReminderStore(context).scheduled.value.isEmpty())
    }

    @Test
    fun `stored reminders of an incompatible shape start empty`() {
        prefs.edit(commit = true) { putString(ReminderStore.KEY_SCHEDULED, """[{"id":"r-1","remindAt":"tomorrow"}]""") }

        assertTrue(ReminderStore(context).scheduled.value.isEmpty())
    }

    @Test
    fun `unknown fields written by a newer version are ignored`() {
        prefs.edit(commit = true) {
            putString(
                ReminderStore.KEY_SCHEDULED,
                """[{"id":"r-1","itemType":"NOTE","itemId":"i-1","title":null,"remindAtMillis":42,"snoozed":true}]""",
            )
        }

        assertEquals(listOf(ScheduledReminder("r-1", "NOTE", "i-1", null, 42)), ReminderStore(context).scheduled.value)
    }

    @Test
    fun `notified ids are kept, and only those still listed survive a retain`() {
        val store = ReminderStore(context)
        store.markNotified("r-1")
        store.markNotified("r-2")

        store.retainNotified(setOf("r-2", "r-3"))

        assertEquals(setOf("r-2"), ReminderStore(context).notifiedIds())
    }

    @Test
    fun `clearing forgets reminders, notified ids and the prompt flags`() {
        val store = ReminderStore(context)
        store.replaceScheduled(listOf(scheduled(1, minutes = 10)))
        store.markNotified("r-1")
        store.dismissPrompt()
        store.markNotificationPermissionRequested()

        store.clear()

        assertTrue(store.scheduled.value.isEmpty())
        val reopened = ReminderStore(context)
        assertTrue(reopened.scheduled.value.isEmpty())
        assertTrue(reopened.notifiedIds().isEmpty())
        assertFalse(reopened.isPromptDismissed())
        assertFalse(reopened.wasNotificationPermissionRequested())
    }

    @Test
    fun `a reminder that was fired already or whose item is gone is not scheduled`() {
        assertNull(reminder(1, minutes = 10, firedAt = NOW).toScheduled())
        assertNull(reminder(1, minutes = 10, available = false).toScheduled())
        val expected =
            ScheduledReminder(
                id = reminderId(1).toString(),
                itemType = "TASK",
                itemId = itemId(1).toString(),
                title = "Call plumber 1",
                remindAtMillis = NOW.plusSeconds(600).toEpochMilli(),
            )
        assertEquals(expected, reminder(1, minutes = 10).toScheduled())
    }
}
