package ch.sonensei.cockpit.reminders

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.MainActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlarmManager
import java.time.Duration
import java.time.Instant

/** The receivers in the real app graph: an alarm shows the notification, a reboot sets the alarms again. */
@RunWith(AndroidJUnit4::class)
class ReminderReceiversTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val graph = remindersGraph(context)
    private val notifications = context.getSystemService(NotificationManager::class.java)

    @Before
    fun setUp() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @After
    fun tearDown() {
        graph.reminderScheduler().clear()
        ShadowAlarmManager.reset()
    }

    private fun fireAlarm(n: Int) = ReminderAlarmReceiver().onReceive(context, alarmIntent(context, reminderId(n).toString()))

    @Test
    fun `an alarm shows the reminder, and a tap opens the item in the app`() {
        graph.reminderScheduler().apply(listOf(reminder(1, minutes = 0, title = "Renew passport")))

        fireAlarm(1)

        val shown = shadowOf(notifications).allNotifications.single()
        assertEquals("Renew passport", shadowOf(shown).contentTitle)
        assertEquals(ReminderNotifier.CHANNEL_ID, shown.channelId)
        val open = shadowOf(shown.contentIntent).savedIntent
        assertEquals(MainActivity::class.java.name, open.component?.className)
        assertEquals(ReminderOpenRequests.ACTION_OPEN_ITEM, open.action)
        assertEquals("TASK", open.getStringExtra(ReminderOpenRequests.EXTRA_ITEM_TYPE))
        assertEquals(itemId(1).toString(), open.getStringExtra(ReminderOpenRequests.EXTRA_ITEM_ID))
    }

    @Test
    fun `an alarm goes off only once`() {
        graph.reminderScheduler().apply(listOf(reminder(1, minutes = 0)))

        fireAlarm(1)
        notifications.cancelAll()
        fireAlarm(1)

        assertTrue(shadowOf(notifications).allNotifications.isEmpty())
    }

    @Test
    fun `without the notification permission nothing is shown`() {
        shadowOf(context as Application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        graph.reminderScheduler().apply(listOf(reminder(1, minutes = 0)))

        fireAlarm(1)

        assertTrue(shadowOf(notifications).allNotifications.isEmpty())
    }

    @Test
    fun `an alarm of a reminder a sync removed shows nothing`() {
        // The app graph runs on the real clock: the reminder must not be due yet, or the scheduler keeps it as fired by the server.
        val notDueYet = Duration.between(NOW, Instant.now()).toMinutes() + 30
        graph.reminderScheduler().apply(listOf(reminder(1, minutes = notDueYet)))
        graph.reminderScheduler().apply(emptyList())

        fireAlarm(1)

        assertTrue(shadowOf(notifications).allNotifications.isEmpty())
    }

    @Test
    fun `after a reboot the stored reminders get their alarms again`() {
        // A reboot drops all alarms; the stored reminders remain.
        graph.reminderStore().replaceScheduled(
            listOfNotNull(reminder(1, minutes = 30).toScheduled(), reminder(2, minutes = 60).toScheduled()),
        )
        graph.reminderScheduler().rescheduleAll()
        shadowOf(alarmManager(context)).scheduledAlarms.toList().forEach { alarmManager(context).cancel(it.operation!!) }
        assertTrue(scheduledAlarms(context).isEmpty())

        ReminderRescheduleReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

        assertEquals(setOf(reminderId(1), reminderId(2)).map { it.toString() }.toSet(), alarmReminderIds(context).toSet())
    }

    @Test
    fun `granting exact alarms sets the alarms again as exact`() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        graph.reminderScheduler().apply(listOf(reminder(1, minutes = 30)))
        assertTrue(scheduledAlarms(context).single().windowLengthMs != 0L)

        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        ReminderRescheduleReceiver().onReceive(context, Intent(ReminderRescheduleReceiver.ACTION_EXACT_ALARM_PERMISSION_CHANGED))

        assertEquals(0L, scheduledAlarms(context).single().windowLengthMs)
    }

    @Test
    fun `other broadcasts are ignored`() {
        graph.reminderStore().replaceScheduled(listOfNotNull(reminder(1, minutes = 30).toScheduled()))

        ReminderRescheduleReceiver().onReceive(context, Intent(Intent.ACTION_TIME_TICK))
        ReminderAlarmReceiver().onReceive(context, Intent("other"))

        assertTrue(scheduledAlarms(context).isEmpty())
        assertTrue(shadowOf(notifications).allNotifications.isEmpty())
    }

    @Test
    fun `an alarm broadcast without a reminder id shows nothing`() {
        graph.reminderScheduler().apply(listOf(reminder(1, minutes = 0)))

        ReminderAlarmReceiver().onReceive(context, Intent(ReminderAlarmReceiver.ACTION_FIRE))

        assertTrue(shadowOf(notifications).allNotifications.isEmpty())
        assertTrue(graph.reminderStore().notifiedIds().isEmpty())
    }

    @Test
    fun `an alarm broadcast with a foreign data uri shows nothing`() {
        graph.reminderScheduler().apply(listOf(reminder(1, minutes = 0)))
        val foreign =
            Intent(ReminderAlarmReceiver.ACTION_FIRE)
                .setData(android.net.Uri.fromParts("https", reminderId(1).toString(), null))

        ReminderAlarmReceiver().onReceive(context, foreign)

        assertTrue(shadowOf(notifications).allNotifications.isEmpty())
    }

    @Test
    fun `after an app update the stored reminders get their alarms again`() {
        graph.reminderStore().replaceScheduled(listOfNotNull(reminder(1, minutes = 30).toScheduled()))

        ReminderRescheduleReceiver().onReceive(context, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))

        assertEquals(listOf(reminderId(1).toString()), alarmReminderIds(context))
    }

    @Test
    fun `the alarm receiver is registered and not exported`() {
        val info =
            context.packageManager.getReceiverInfo(
                android.content.ComponentName(context, ReminderAlarmReceiver::class.java),
                0,
            )
        assertTrue(!info.exported)
    }

    @Test
    fun `the manifest registers the receivers for boot, update and the exact alarm permission`() {
        val pm = context.packageManager
        for (action in ReminderRescheduleReceiver.RESCHEDULE_ACTIONS) {
            val receivers = pm.queryBroadcastReceivers(Intent(action).setPackage(context.packageName), 0)
            assertTrue(action, receivers.any { it.activityInfo.name == ReminderRescheduleReceiver::class.java.name })
        }
    }
}
