package ch.sonensei.cockpit.reminders

import android.app.AlarmManager
import android.content.Context
import ch.sonensei.cockpit.core.network.generated.models.ReminderItemResponse
import ch.sonensei.cockpit.core.network.generated.models.ReminderResponse
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlarmManager
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

val NOW: Instant = Instant.parse("2026-10-21T08:00:00Z")

fun reminderId(n: Int): UUID = UUID.fromString("0192f000-0000-7000-8000-%012d".format(n))

fun itemId(n: Int): UUID = UUID.fromString("0192f000-0000-7000-9000-%012d".format(n))

/** A pending reminder [minutes] after [NOW] on task [n] titled [title]. */
fun reminder(
    n: Int,
    minutes: Long,
    title: String = "Call plumber $n",
    available: Boolean = true,
    firedAt: Instant? = null,
    type: ReminderItemResponse.Type = ReminderItemResponse.Type.TASK,
): ReminderResponse =
    ReminderResponse(
        channel = ReminderResponse.Channel.NOTIFICATION,
        createdAt = OffsetDateTime.ofInstant(NOW.minusSeconds(3600), ZoneOffset.UTC),
        id = reminderId(n),
        item = ReminderItemResponse(available = available, id = itemId(n), type = type, title = title.takeIf { available }),
        remindAt = OffsetDateTime.ofInstant(NOW.plusSeconds(minutes * 60), ZoneOffset.UTC),
        firedAt = firedAt?.let { OffsetDateTime.ofInstant(it, ZoneOffset.UTC) },
    )

fun upcomingJson(vararg reminders: String): String = """{"items":[${reminders.joinToString(",")}]}"""

fun reminderJson(
    n: Int,
    remindAt: Instant,
    title: String = "Call plumber $n",
): String =
    """{"id":"${reminderId(n)}","channel":"NOTIFICATION","createdAt":"2026-10-21T07:00:00Z","remindAt":"$remindAt",""" +
        """"item":{"available":true,"id":"${itemId(n)}","type":"TASK","title":"$title","deepLink":"/tasks/${itemId(n)}"}}"""

fun alarmManager(context: Context): AlarmManager = context.getSystemService(AlarmManager::class.java)

fun scheduledAlarms(context: Context): List<ShadowAlarmManager.ScheduledAlarm> = shadowOf(alarmManager(context)).scheduledAlarms

/** The reminder ids the scheduled alarms fire for. */
fun alarmReminderIds(context: Context): List<String?> =
    scheduledAlarms(context).map { alarm -> alarm.operation?.let { shadowOf(it).savedIntent }?.let(::reminderIdOf) }
