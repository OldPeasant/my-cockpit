package ch.sonensei.cockpit.push

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import ch.sonensei.cockpit.core.network.AuthSession
import ch.sonensei.cockpit.core.network.generated.apis.RemindersApi
import ch.sonensei.cockpit.core.network.generated.models.ReminderResponse
import ch.sonensei.cockpit.reminders.ReminderScheduler
import ch.sonensei.cockpit.reminders.ScheduledReminder
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.util.UUID
import javax.inject.Inject

/** How delivering a pushed reminder ended. */
enum class PushReminderResult {
    /** Shown now. */
    SHOWN,

    /** Nothing to show: logged out, already shown here, not fired, gone (404), item trashed, or an error answer. */
    IGNORED,

    /** The server could not be reached; worth a retry. */
    OFFLINE,
}

/**
 * Shows the reminder a push announces (`GET /api/v1/reminders/{id}`). The reminder sync cannot: the server lists only
 * pending reminders, so one created on another client since the last sync and fired by the server would never reach
 * this device. Already notified ones (by the local alarm or an earlier push) are not shown again.
 */
class PushReminderDelivery
    @Inject
    constructor(
        private val session: AuthSession,
        private val api: RemindersApi,
        private val scheduler: ReminderScheduler,
    ) {
        suspend fun deliver(id: UUID): PushReminderResult {
            if (!session.isLoggedIn) return PushReminderResult.IGNORED
            val response =
                try {
                    api.getReminder(id)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: IOException) {
                    return PushReminderResult.OFFLINE
                } catch (_: Exception) {
                    // An unreadable body. Not logged: it may carry user data.
                    return PushReminderResult.IGNORED
                }
            val body = response.body()
            if (!response.isSuccessful || body == null || body.id != id) return PushReminderResult.IGNORED
            val reminder = body.toFired() ?: return PushReminderResult.IGNORED
            // A logout while the request ran has cleared the reminders; its answer must not show one again.
            val shown = scheduler.showFiredByServer(reminder) { session.isLoggedIn }
            return if (shown) PushReminderResult.SHOWN else PushReminderResult.IGNORED
        }
    }

/** The notification of a reminder the server fired; null if it did not fire it or its item is gone. */
private fun ReminderResponse.toFired(): ScheduledReminder? {
    if (firedAt == null || !item.available) return null
    return ScheduledReminder(
        id = id.toString(),
        itemType = item.type.value,
        itemId = item.id.toString(),
        title = item.title,
        remindAtMillis = remindAt.toInstant().toEpochMilli(),
    )
}

/** Delivers one pushed reminder; gets [PushReminderDelivery] from Hilt through an entry point, tests pass their own. */
class PushReminderWorker internal constructor(
    context: Context,
    params: WorkerParameters,
    private val delivery: () -> PushReminderDelivery,
) : CoroutineWorker(context, params) {
    constructor(context: Context, params: WorkerParameters) : this(context, params, {
        pushGraph(context).pushReminderDelivery()
    })

    override suspend fun doWork(): Result {
        val id =
            try {
                UUID.fromString(inputData.getString(KEY_REMINDER_ID))
            } catch (_: Exception) {
                return Result.failure()
            }
        return when (delivery().deliver(id)) {
            // Network up but server down: retry with backoff.
            PushReminderResult.OFFLINE -> Result.retry()
            else -> Result.success()
        }
    }

    internal companion object {
        const val KEY_REMINDER_ID = "reminder_id"
    }
}
