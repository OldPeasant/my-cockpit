package ch.sonensei.cockpit.reminders

import android.app.AlarmManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.shadows.ShadowAlarmManager
import java.time.Clock
import java.time.Duration
import java.time.ZoneOffset

/** Local alarms follow the synced reminders: set, moved, cancelled; exact only with the user's permission. */
@RunWith(AndroidJUnit4::class)
class ReminderSchedulerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val store = ReminderStore(context)
    private val alarms = AlarmManagerReminderAlarms(context)
    private var clock: Clock = Clock.fixed(NOW, ZoneOffset.UTC)
    private val scheduler get() = ReminderScheduler(store, alarms, ReminderNotifier(context), clock)

    @Before
    fun setUp() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    @After
    fun tearDown() {
        store.clear()
        ShadowAlarmManager.reset()
    }

    @Test
    fun `a sync sets an exact alarm at the reminder time for each pending reminder`() {
        scheduler.apply(listOf(reminder(1, minutes = 30), reminder(2, minutes = 10)))

        val alarms = scheduledAlarms(context).sortedBy { it.triggerAtMs }
        assertEquals(listOf(NOW.plusSeconds(600).toEpochMilli(), NOW.plusSeconds(1800).toEpochMilli()), alarms.map { it.triggerAtMs })
        assertTrue(alarms.all { it.type == AlarmManager.RTC_WAKEUP && it.isAllowWhileIdle && it.windowLengthMs == 0L })
        assertEquals(listOf(reminderId(2), reminderId(1)).map { it.toString() }, store.scheduled.value.map { it.id })
    }

    @Test
    fun `without the exact alarm permission the alarm is inexact`() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)

        scheduler.apply(listOf(reminder(1, minutes = 30)))

        val alarm = scheduledAlarms(context).single()
        assertEquals(NOW.plusSeconds(1800).toEpochMilli(), alarm.triggerAtMs)
        assertTrue(alarm.isAllowWhileIdle)
        assertTrue("inexact: no exact window", alarm.windowLengthMs != 0L)
    }

    @Test
    fun `fired and unavailable reminders get no alarm`() {
        scheduler.apply(
            listOf(
                reminder(1, minutes = 30, firedAt = NOW),
                reminder(2, minutes = 30, available = false),
                reminder(3, minutes = 30),
            ),
        )

        assertEquals(listOf(reminderId(3).toString()), alarmReminderIds(context))
    }

    @Test
    fun `a later sync cancels deleted reminders, moves changed ones and adds new ones`() {
        scheduler.apply(listOf(reminder(1, minutes = 30), reminder(2, minutes = 60)))

        scheduler.apply(listOf(reminder(2, minutes = 90), reminder(3, minutes = 120)))

        val byId =
            scheduledAlarms(context).associateBy { alarm ->
                alarm.operation
                    ?.let {
                        org.robolectric.Shadows
                            .shadowOf(it)
                            .savedIntent
                    }?.let(::reminderIdOf)
            }
        assertEquals(setOf(reminderId(2).toString(), reminderId(3).toString()), byId.keys)
        assertEquals(NOW.plusSeconds(90 * 60).toEpochMilli(), byId[reminderId(2).toString()]!!.triggerAtMs)
        assertEquals(listOf(reminderId(2), reminderId(3)).map { it.toString() }, store.scheduled.value.map { it.id })
    }

    @Test
    fun `an empty sync cancels every alarm`() {
        scheduler.apply(listOf(reminder(1, minutes = 30)))

        scheduler.apply(emptyList())

        assertTrue(scheduledAlarms(context).isEmpty())
        assertTrue(store.scheduled.value.isEmpty())
    }

    @Test
    fun `a due reminder the server fired before the local alarm went off keeps its alarm`() {
        scheduler.apply(listOf(reminder(1, minutes = 5)))
        clock = Clock.offset(clock, Duration.ofMinutes(6))

        scheduler.apply(emptyList())

        assertEquals(listOf(reminderId(1).toString()), alarmReminderIds(context))
    }

    @Test
    fun `only the earliest reminders up to the limit get an alarm`() {
        val many = (1..ReminderScheduler.MAX_ALARMS + 5).map { reminder(it, minutes = 1_000L - it) }

        scheduler.apply(many)

        assertEquals(ReminderScheduler.MAX_ALARMS, scheduledAlarms(context).size)
        assertTrue(reminderId(ReminderScheduler.MAX_ALARMS + 5).toString() in alarmReminderIds(context))
        assertTrue(reminderId(1).toString() !in alarmReminderIds(context))
    }

    @Test
    fun `a fired reminder is notified once, also when a later sync still lists it`() {
        scheduler.apply(listOf(reminder(1, minutes = 0)))

        assertEquals(reminderId(1).toString(), scheduler.fire(reminderId(1).toString())?.id)
        assertNull(scheduler.fire(reminderId(1).toString()))

        scheduler.apply(listOf(reminder(1, minutes = 0)))
        assertTrue("no second alarm", scheduledAlarms(context).isEmpty())
    }

    @Test
    fun `a cancelled reminder is not notified`() {
        scheduler.apply(listOf(reminder(1, minutes = 30)))
        scheduler.apply(emptyList())

        assertNull(scheduler.fire(reminderId(1).toString()))
    }

    @Test
    fun `rescheduling sets the stored alarms again`() {
        store.replaceScheduled(listOfNotNull(reminder(1, minutes = 30).toScheduled()))

        scheduler.rescheduleAll()

        assertEquals(listOf(reminderId(1).toString()), alarmReminderIds(context))
    }

    @Test
    fun `an answer that arrives after a logout sets no alarm`() {
        scheduler.apply(listOf(reminder(1, minutes = 30))) { false }

        assertTrue(scheduledAlarms(context).isEmpty())
        assertTrue(store.scheduled.value.isEmpty())
    }

    @Test
    fun `clearing removes every alarm and the stored reminders`() {
        scheduler.apply(listOf(reminder(1, minutes = 30), reminder(2, minutes = 40)))

        scheduler.clear()

        assertTrue(scheduledAlarms(context).isEmpty())
        assertTrue(ReminderStore(context).scheduled.value.isEmpty())
    }

    @Test
    fun `the sync window ends a week from now`() {
        assertEquals(NOW.plus(Duration.ofDays(7)), scheduler.syncUntil())
    }
}
