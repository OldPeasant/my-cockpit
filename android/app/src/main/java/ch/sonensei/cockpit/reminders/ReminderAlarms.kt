package ch.sonensei.cockpit.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Local alarms for reminders: one per reminder id, setting it again replaces it. */
interface ReminderAlarms {
    /** Whether alarms go off at the exact time; without the exact-alarm permission they may be late by some minutes. */
    val canScheduleExact: Boolean

    fun schedule(reminder: ScheduledReminder)

    fun cancel(id: String)
}

/**
 * [ReminderAlarms] on [AlarmManager]: exact and allowed while idle when the user granted exact alarms (Android 12+ asks
 * for it), otherwise the inexact `setAndAllowWhileIdle`.
 */
@Singleton
class AlarmManagerReminderAlarms
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) : ReminderAlarms {
        private val alarmManager = context.getSystemService(AlarmManager::class.java)

        override val canScheduleExact: Boolean
            get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

        override fun schedule(reminder: ScheduledReminder) {
            val pending = alarmPendingIntent(reminder.id, PendingIntent.FLAG_UPDATE_CURRENT) ?: return
            val at = reminder.remindAtMillis
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()) {
                try {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
                    return
                } catch (_: SecurityException) {
                    // Revoked between the check and the call: fall back to an inexact alarm.
                }
            }
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        }

        override fun cancel(id: String) {
            val pending = alarmPendingIntent(id, PendingIntent.FLAG_NO_CREATE) ?: return
            alarmManager.cancel(pending)
            pending.cancel()
        }

        private fun alarmPendingIntent(
            id: String,
            flags: Int,
        ): PendingIntent? = PendingIntent.getBroadcast(context, 0, alarmIntent(context, id), flags or PendingIntent.FLAG_IMMUTABLE)
    }

/** The broadcast an alarm sends; the data URI makes the intents (and so the pending intents) of two reminders differ. */
internal fun alarmIntent(
    context: Context,
    reminderId: String,
): Intent =
    Intent(context, ReminderAlarmReceiver::class.java)
        .setAction(ReminderAlarmReceiver.ACTION_FIRE)
        .setData(Uri.fromParts(REMINDER_URI_SCHEME, reminderId, null))

internal fun reminderIdOf(intent: Intent): String? = intent.data?.takeIf { it.scheme == REMINDER_URI_SCHEME }?.schemeSpecificPart

internal const val REMINDER_URI_SCHEME = "cockpit-reminder"
