package ch.sonensei.cockpit.gtd.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import ch.sonensei.cockpit.core.network.AuthSession
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/** Reloads every cached GTD view, so the read cache is fresh even if the app was not opened for a while. */
class GtdSync
    @Inject
    constructor(
        private val session: AuthSession,
        private val tasks: TaskRepository,
        private val projects: ProjectRepository,
        private val contexts: ContextRepository,
    ) {
        /** [RefreshResult.Offline] if any load found the server unreachable, else the first failure, else refreshed. */
        suspend fun refreshAll(): RefreshResult {
            if (!session.isLoggedIn) return RefreshResult.Refreshed
            val results = listOf(contexts.refresh(), projects.refresh()) + TaskView.entries.map { tasks.refresh(it) }
            return results.firstOrNull { it == RefreshResult.Offline }
                ?: results.firstOrNull { it is RefreshResult.Failed }
                ?: RefreshResult.Refreshed
        }
    }

/** Keeps the periodic GTD sync scheduled while the user is logged in. */
interface GtdSyncScheduler {
    /** Schedules the periodic sync unless it already is; cheap enough to call on every screen start. */
    fun ensureScheduled()

    fun cancel()
}

@Singleton
class WorkManagerGtdSyncScheduler
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) : GtdSyncScheduler {
        private val scheduled = AtomicBoolean(false)

        override fun ensureScheduled() {
            if (!scheduled.compareAndSet(false, true)) return
            val request =
                PeriodicWorkRequestBuilder<GtdSyncWorker>(SYNC_INTERVAL_MINUTES, TimeUnit.MINUTES)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build()
            // KEEP: an app start must not push the next run back by another interval.
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        override fun cancel() {
            scheduled.set(false)
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }

        companion object {
            const val WORK_NAME = "gtd-sync"

            /** SPEC.md §9.3 asks for 15 minutes, which is also WorkManager's shortest periodic interval. */
            const val SYNC_INTERVAL_MINUTES = 15L
        }
    }

/**
 * The periodic sync job. WorkManager creates it with its default factory, so it gets [GtdSync] from Hilt through an
 * entry point; tests pass their own.
 */
class GtdSyncWorker internal constructor(
    context: Context,
    params: WorkerParameters,
    private val sync: () -> GtdSync,
) : CoroutineWorker(context, params) {
    constructor(context: Context, params: WorkerParameters) : this(context, params, {
        EntryPointAccessors.fromApplication(context, GtdSyncEntryPoint::class.java).gtdSync()
    })

    override suspend fun doWork(): Result =
        when (sync().refreshAll()) {
            // Network up but server down: retry with backoff instead of waiting for the next period.
            RefreshResult.Offline -> Result.retry()
            // A rejected request will not succeed on retry; the next period tries again.
            else -> Result.success()
        }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface GtdSyncEntryPoint {
    fun gtdSync(): GtdSync
}
