package ch.sonensei.cockpit.reminders

import ch.sonensei.cockpit.core.network.generated.models.ReminderResponse
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the local alarms in line with the reminders the server lists: a sync sets alarms for new and moved reminders and
 * cancels those that are gone (deleted, fired elsewhere, item trashed); a reboot or a changed exact-alarm permission sets
 * them again from the [ReminderStore].
 */
@Singleton
class ReminderScheduler internal constructor(
    private val store: ReminderStore,
    private val alarms: ReminderAlarms,
    private val notifier: ReminderNotifier,
    private val clock: Clock,
) {
    @Inject
    constructor(store: ReminderStore, alarms: ReminderAlarms, notifier: ReminderNotifier) :
        this(store, alarms, notifier, Clock.systemUTC())

    /**
     * Applies the server's pending reminders (`GET /api/v1/reminders/upcoming`): at most [MAX_ALARMS] of the earliest
     * get an alarm (Android limits alarms per app). Ones already notified on this device are skipped.
     *
     * A stored reminder that is due but no longer listed was most likely fired by the server while its (possibly
     * inexact) local alarm has not gone off yet: it keeps its alarm, so it is still shown here. One that is not due yet
     * and not listed was deleted (or its item trashed) and loses its alarm.
     *
     * Nothing happens unless [stillValid] holds; it is checked under the same lock as [clear], so the answer of a sync
     * that ran into a logout cannot set alarms again.
     */
    @Synchronized
    fun apply(
        upcoming: List<ReminderResponse>,
        stillValid: () -> Boolean = { true },
    ) {
        if (!stillValid()) return
        val now = clock.millis()
        val listedIds = upcoming.map { it.id.toString() }.toSet()
        val notified = store.notifiedIds()
        val firedByServer = store.scheduled.value.filter { it.id !in listedIds && it.remindAtMillis <= now }
        val wanted =
            (upcoming.mapNotNull { it.toScheduled() } + firedByServer)
                .filter { it.id !in notified }
                .sortedBy { it.remindAtMillis }
                .take(MAX_ALARMS)
        val wantedIds = wanted.map { it.id }.toSet()
        store.scheduled.value
            .filter { it.id !in wantedIds }
            .forEach { alarms.cancel(it.id) }
        wanted.forEach { alarms.schedule(it) }
        store.replaceScheduled(wanted)
        // Ids the server still lists stay notified until it marks them fired; the others cannot come back.
        store.retainNotified(listedIds)
    }

    /** Sets every stored alarm again: after a reboot (alarms are lost) or when the exact-alarm permission changed. */
    @Synchronized
    fun rescheduleAll() {
        val notified = store.notifiedIds()
        store.scheduled.value
            .filter { it.id !in notified }
            .forEach { alarms.schedule(it) }
    }

    /**
     * The alarm of [id] went off: the reminder to notify, or null if it was cancelled meanwhile or already shown. It is
     * marked notified either way, so a later sync or reboot does not show it again.
     */
    @Synchronized
    fun fire(id: String): ScheduledReminder? {
        val reminder = store.find(id) ?: return null
        if (id in store.notifiedIds()) return null
        store.markNotified(id)
        return reminder
    }

    /** Logout: no alarm or notification of the user stays behind. */
    @Synchronized
    fun clear() {
        store.scheduled.value.forEach {
            alarms.cancel(it.id)
            notifier.dismiss(it.id)
        }
        store.clear()
    }

    /** The latest reminder time a sync asks for. */
    fun syncUntil(): java.time.Instant = clock.instant().plus(SYNC_WINDOW)

    internal companion object {
        /** Android allows 500 alarms per app; reminders further out are set by a later sync. */
        const val MAX_ALARMS = 100

        /** How far ahead a sync schedules; the periodic sync moves the window along. */
        val SYNC_WINDOW: java.time.Duration = java.time.Duration.ofDays(7)
    }
}
