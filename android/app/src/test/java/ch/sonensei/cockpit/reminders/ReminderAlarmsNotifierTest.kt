package ch.sonensei.cockpit.reminders

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.ContextWrapper
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.R
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

/** The AlarmManager and notification adapters on their own: fallbacks, per-reminder identity, dismissing. */
@RunWith(AndroidJUnit4::class)
class ReminderAlarmsNotifierTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val notifications = context.getSystemService(NotificationManager::class.java)

    @Before
    fun setUp() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @After
    fun tearDown() {
        notifications.cancelAll()
        ShadowAlarmManager.reset()
    }

    private fun scheduled(
        n: Int,
        title: String? = "Call plumber $n",
    ) = reminder(n, minutes = 30).toScheduled()!!.copy(title = title)

    @Test
    fun `an exact alarm permission revoked between the check and the call falls back to an inexact alarm`() {
        val manager = mockk<AlarmManager>()
        every { manager.canScheduleExactAlarms() } returns true
        every { manager.setExactAndAllowWhileIdle(any(), any(), any<PendingIntent>()) } throws SecurityException("revoked")
        every { manager.setAndAllowWhileIdle(any(), any(), any<PendingIntent>()) } just runs
        val withMockedAlarms =
            object : ContextWrapper(context) {
                override fun getSystemService(name: String): Any? =
                    if (name == Context.ALARM_SERVICE) manager else super.getSystemService(name)
            }
        val reminder = scheduled(1)

        AlarmManagerReminderAlarms(withMockedAlarms).schedule(reminder)

        verify(exactly = 1) { manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminder.remindAtMillis, any<PendingIntent>()) }
    }

    @Test
    @Config(sdk = [30])
    fun `before Android 12 alarms are always exact, no permission needed`() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        val alarms = AlarmManagerReminderAlarms(context)

        alarms.schedule(scheduled(1))

        assertTrue(alarms.canScheduleExact)
        assertEquals(0L, scheduledAlarms(context).single().windowLengthMs)
    }

    @Test
    @Config(sdk = [30])
    fun `before Android 13 a notification is shown without a runtime permission`() {
        shadowOf(context as Application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        ReminderNotifier(context).show(scheduled(1))

        assertEquals(1, shadowOf(notifications).allNotifications.size)
    }

    @Test
    fun `canScheduleExact follows the exact alarm permission`() {
        val alarms = AlarmManagerReminderAlarms(context)
        assertTrue(alarms.canScheduleExact)

        ShadowAlarmManager.setCanScheduleExactAlarms(false)

        assertFalse(alarms.canScheduleExact)
    }

    @Test
    fun `scheduling a reminder again replaces its alarm, and cancelling removes only that one`() {
        val alarms = AlarmManagerReminderAlarms(context)
        alarms.schedule(scheduled(1))
        alarms.schedule(scheduled(2))
        alarms.schedule(scheduled(1).copy(remindAtMillis = NOW.plusSeconds(7200).toEpochMilli()))

        assertEquals(2, scheduledAlarms(context).size)

        alarms.cancel(reminderId(1).toString())

        assertEquals(listOf(reminderId(2).toString()), alarmReminderIds(context))
    }

    @Test
    fun `cancelling a reminder that has no alarm does nothing`() {
        val alarms = AlarmManagerReminderAlarms(context)
        alarms.schedule(scheduled(1))

        alarms.cancel(reminderId(9).toString())

        assertEquals(listOf(reminderId(1).toString()), alarmReminderIds(context))
    }

    @Test
    fun `two reminders show two notifications, each opening its own item`() {
        val notifier = ReminderNotifier(context)

        notifier.show(scheduled(1))
        notifier.show(scheduled(2))

        val shown = shadowOf(notifications).allNotifications
        assertEquals(2, shown.size)
        val openedItems =
            shown.associate { notification ->
                shadowOf(notification).contentTitle.toString() to
                    shadowOf(notification.contentIntent).savedIntent.getStringExtra(ReminderOpenRequests.EXTRA_ITEM_ID)
            }
        assertEquals(mapOf("Call plumber 1" to itemId(1).toString(), "Call plumber 2" to itemId(2).toString()), openedItems)
    }

    @Test
    fun `a reminder without a title shows a generic title, and the item title stays off the lock screen`() {
        ReminderNotifier(context).show(scheduled(1, title = null))

        val shown = shadowOf(notifications).allNotifications.single()
        assertEquals(context.getString(R.string.reminder_untitled), shadowOf(shown).contentTitle)
        assertEquals(Notification.VISIBILITY_PRIVATE, shown.visibility)
        assertEquals(Notification.CATEGORY_REMINDER, shown.category)
        assertEquals(NOW.plusSeconds(1800).toEpochMilli(), shown.`when`)
    }

    @Test
    fun `dismissing removes only that reminder's notification`() {
        val notifier = ReminderNotifier(context)
        notifier.show(scheduled(1))
        notifier.show(scheduled(2))

        notifier.dismiss(reminderId(1).toString())

        assertEquals(listOf("Call plumber 2"), shadowOf(notifications).allNotifications.map { shadowOf(it).contentTitle.toString() })
    }

    @Test
    fun `dismissing all removes every reminder notification but keeps the notifications of other features`() {
        val notifier = ReminderNotifier(context)
        notifier.show(scheduled(1))
        notifier.show(scheduled(2))
        notifications.createNotificationChannel(NotificationChannel(OTHER_CHANNEL, "Other", NotificationManager.IMPORTANCE_LOW))
        // Another feature's notifications: one even uses the same notification id, one has no tag.
        notifications.notify("other-tag", ReminderNotifier.NOTIFICATION_ID, otherNotification("Upload finished"))
        notifications.notify(OTHER_ID, otherNotification("Sync failed"))

        notifier.dismissAll()

        assertEquals(
            listOf("Sync failed", "Upload finished"),
            shadowOf(notifications).allNotifications.map { shadowOf(it).contentTitle.toString() }.sorted(),
        )
    }

    @Test
    fun `dismissing all with no notification shown does nothing`() {
        ReminderNotifier(context).dismissAll()

        assertTrue(shadowOf(notifications).allNotifications.isEmpty())
    }

    private fun otherNotification(title: String): Notification =
        NotificationCompat
            .Builder(context, OTHER_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification_reminder)
            .setContentTitle(title)
            .build()

    @Test
    fun `notifications cannot be shown without the permission or when the user turned them off`() {
        val notifier = ReminderNotifier(context)
        assertTrue(notifier.canNotify())

        shadowOf(notifications).setNotificationsEnabled(false)
        assertFalse(notifier.canNotify())

        shadowOf(notifications).setNotificationsEnabled(true)
        shadowOf(context as Application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertFalse(notifier.canNotify())
    }

    @Test
    fun `the reminders channel is created with high importance when a notification is shown`() {
        ReminderNotifier(context).show(scheduled(1))

        val channel = notifications.getNotificationChannel(ReminderNotifier.CHANNEL_ID)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
        assertEquals(context.getString(R.string.reminder_channel_name), channel.name.toString())
    }

    private companion object {
        const val OTHER_CHANNEL = "fictitious-other-feature"
        const val OTHER_ID = 42
    }
}
