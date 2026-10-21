package ch.sonensei.cockpit.reminders

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import ch.sonensei.cockpit.core.network.AuthSession
import ch.sonensei.cockpit.core.network.generated.apis.RemindersApi
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/** How a reminder sync ended. */
enum class ReminderSyncResult {
    /** Alarms match the server (or there is no session to sync). */
    SYNCED,

    /** The server could not be reached; the alarms set before stay. */
    OFFLINE,

    /** The server answered with an error or an unreadable body; the alarms set before stay. */
    FAILED,
}

/** Loads the pending reminders (`GET /api/v1/reminders/upcoming`) and sets the local alarms from them. */
class ReminderSync
    @Inject
    constructor(
        private val session: AuthSession,
        private val api: RemindersApi,
        private val scheduler: ReminderScheduler,
    ) {
        suspend fun sync(): ReminderSyncResult {
            if (!session.isLoggedIn) return ReminderSyncResult.SYNCED
            val response =
                try {
                    api.listUpcomingReminders(scheduler.syncUntil().atOffset(ZoneOffset.UTC))
                } catch (e: CancellationException) {
                    throw e
                } catch (_: IOException) {
                    return ReminderSyncResult.OFFLINE
                } catch (_: Exception) {
                    // An unreadable body. Not logged: it may carry user data.
                    return ReminderSyncResult.FAILED
                }
            val body = response.body()
            if (!response.isSuccessful || body == null) return ReminderSyncResult.FAILED
            // A logout while the request ran has cleared the alarms; its answer must not set them again.
            scheduler.apply(body.items) { session.isLoggedIn }
            return ReminderSyncResult.SYNCED
        }
    }

/** Keeps the reminder sync running while the user is logged in: every 15 minutes, and at once when the app opens. */
interface ReminderSyncScheduler {
    /** Schedules the periodic sync unless it already is, and syncs now. */
    fun start()

    fun cancel()
}

@Singleton
class WorkManagerReminderSyncScheduler
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) : ReminderSyncScheduler {
        private val periodicScheduled = AtomicBoolean(false)

        override fun start() {
            val workManager = WorkManager.getInstance(context)
            val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            if (periodicScheduled.compareAndSet(false, true)) {
                val periodic =
                    PeriodicWorkRequestBuilder<ReminderSyncWorker>(SYNC_INTERVAL_MINUTES, TimeUnit.MINUTES)
                        .setConstraints(online)
                        .build()
                // KEEP: an app start must not push the next run back by another interval.
                workManager.enqueueUniquePeriodicWork(PERIODIC_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, periodic)
            }
            val now = OneTimeWorkRequestBuilder<ReminderSyncWorker>().setConstraints(online).build()
            workManager.enqueueUniqueWork(NOW_WORK_NAME, ExistingWorkPolicy.KEEP, now)
        }

        override fun cancel() {
            periodicScheduled.set(false)
            val workManager = WorkManager.getInstance(context)
            workManager.cancelUniqueWork(PERIODIC_WORK_NAME)
            workManager.cancelUniqueWork(NOW_WORK_NAME)
        }

        companion object {
            const val PERIODIC_WORK_NAME = "reminders-sync"
            const val NOW_WORK_NAME = "reminders-sync-now"

            /** WorkManager's shortest periodic interval; the same as the GTD sync. */
            const val SYNC_INTERVAL_MINUTES = 15L
        }
    }

/** The reminder sync job; gets [ReminderSync] from Hilt through an entry point, tests pass their own. */
class ReminderSyncWorker internal constructor(
    context: Context,
    params: WorkerParameters,
    private val sync: () -> ReminderSync,
) : CoroutineWorker(context, params) {
    constructor(context: Context, params: WorkerParameters) : this(context, params, {
        remindersGraph(context).reminderSync()
    })

    override suspend fun doWork(): Result =
        when (sync().sync()) {
            // Network up but server down: retry with backoff.
            ReminderSyncResult.OFFLINE -> Result.retry()
            // A rejected request will not succeed on retry; the next period tries again.
            else -> Result.success()
        }
}
