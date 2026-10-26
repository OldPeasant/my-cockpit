package ch.sonensei.cockpit.reminders

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlarmManager
import java.time.Clock
import java.time.ZoneOffset

/**
 * A reminder the server fired and announced by a push is shown once per device: not again by a second push, its local
 * alarm, a later sync or a reboot; never after a logout.
 */
@RunWith(AndroidJUnit4::class)
class ReminderShowFiredByServerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val store = ReminderStore(context)
    private val scheduler =
        ReminderScheduler(store, AlarmManagerReminderAlarms(context), ReminderNotifier(context), Clock.fixed(NOW, ZoneOffset.UTC))

    @Before
    fun setUp() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    @After
    fun tearDown() {
        store.clear()
        notifications.cancelAll()
        ShadowAlarmManager.reset()
    }

    private fun fired(
        n: Int,
        title: String? = "Call plumber $n",
    ) = ScheduledReminder(
        id = reminderId(n).toString(),
        itemType = "TASK",
        itemId = itemId(n).toString(),
        title = title,
        remindAtMillis = NOW.minusSeconds(60).toEpochMilli(),
    )

    private fun shownTags(): List<String?> = notifications.activeNotifications.map { it.tag }

    @Test
    fun `an unknown reminder the server fired is shown, tagged with its id, and marked notified`() {
        assertTrue(scheduler.showFiredByServer(fired(1, title = "Renew fictitious passport")))

        assertEquals(listOf(reminderId(1).toString()), shownTags())
        val notification = shadowOf(notifications).allNotifications.single()
        assertEquals(ReminderNotifier.CHANNEL_ID, notification.channelId)
        assertEquals("Renew fictitious passport", shadowOf(notification).contentTitle.toString())
        assertEquals(setOf(reminderId(1).toString()), store.notifiedIds())
        // Not added to the alarms: it has fired already.
        assertTrue(store.scheduled.value.isEmpty())
        assertTrue(scheduledAlarms(context).isEmpty())
    }

    @Test
    fun `it is shown once, also when pushed twice`() {
        assertTrue(scheduler.showFiredByServer(fired(1)))
        notifications.cancelAll()

        assertFalse(scheduler.showFiredByServer(fired(1)))

        assertTrue(shownTags().isEmpty())
    }

    @Test
    fun `when no longer valid (logged out meanwhile) nothing is shown or marked`() {
        var asked = 0

        val shown =
            scheduler.showFiredByServer(fired(1)) {
                asked++
                false
            }

        assertFalse(shown)

        assertEquals(1, asked)
        assertTrue(shownTags().isEmpty())
        assertTrue(store.notifiedIds().isEmpty())
    }

    @Test
    fun `two reminders fired by the server show two notifications`() {
        assertTrue(scheduler.showFiredByServer(fired(1)))
        assertTrue(scheduler.showFiredByServer(fired(2)))

        assertEquals(setOf(reminderId(1).toString(), reminderId(2).toString()), shownTags().toSet())
    }

    @Test
    fun `without the notification permission it is marked notified but nothing is posted`() {
        shadowOf(context as Application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        assertTrue(scheduler.showFiredByServer(fired(1)))

        assertTrue(shownTags().isEmpty())
        assertEquals(setOf(reminderId(1).toString()), store.notifiedIds())
    }

    @Test
    fun `a pushed reminder that had a local alarm is not set again by the next sync or a reboot`() {
        scheduler.apply(listOf(reminder(1, minutes = -1), reminder(2, minutes = 30)))
        assertTrue(scheduler.showFiredByServer(fired(1)))

        // The server has fired reminder 1, so the next sync no longer lists it.
        scheduler.apply(listOf(reminder(2, minutes = 30)))
        ShadowAlarmManager.reset()
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        scheduler.rescheduleAll()

        assertEquals(listOf(reminderId(2).toString()), alarmReminderIds(context))
        assertNull(store.find(reminderId(1).toString()))
        assertNull(scheduler.fire(reminderId(1).toString()))
        assertEquals(1, shadowOf(notifications).allNotifications.size)
    }

    @Test
    fun `a logout removes a notification shown by a push, also of a reminder this device never synced`() {
        assertTrue(scheduler.showFiredByServer(fired(1)))
        assertEquals(1, shownTags().size)

        scheduler.clear()

        assertTrue(shownTags().isEmpty())
        assertTrue(store.notifiedIds().isEmpty())
    }
}
