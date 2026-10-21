package ch.sonensei.cockpit.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/** A reminder's alarm went off: shows its notification unless a sync cancelled it meanwhile or it was shown already. */
class ReminderAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != ACTION_FIRE) return
        val id = reminderIdOf(intent) ?: return
        val graph = remindersGraph(context)
        graph.reminderScheduler().fire(id)?.let { graph.reminderNotifier().show(it) }
    }

    internal companion object {
        const val ACTION_FIRE = "ch.sonensei.cockpit.reminders.FIRE"
    }
}

/**
 * Sets the alarms again from the stored reminders when they are lost or should change kind: after a reboot or an app
 * update (both drop all alarms), and when the user grants exact alarms (inexact ones become exact). The periodic sync
 * then brings them up to date with the server.
 */
class ReminderRescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action !in RESCHEDULE_ACTIONS) return
        remindersGraph(context).reminderScheduler().rescheduleAll()
    }

    internal companion object {
        /** `AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED` (Android 12+); never sent before. */
        const val ACTION_EXACT_ALARM_PERMISSION_CHANGED = "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"

        val RESCHEDULE_ACTIONS =
            setOf(
                Intent.ACTION_BOOT_COMPLETED,
                Intent.ACTION_MY_PACKAGE_REPLACED,
                ACTION_EXACT_ALARM_PERMISSION_CHANGED,
            )
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface RemindersEntryPoint {
    fun reminderScheduler(): ReminderScheduler

    fun reminderNotifier(): ReminderNotifier

    fun reminderSync(): ReminderSync

    fun reminderStore(): ReminderStore
}

internal fun remindersGraph(context: Context): RemindersEntryPoint =
    EntryPointAccessors.fromApplication(context.applicationContext, RemindersEntryPoint::class.java)
