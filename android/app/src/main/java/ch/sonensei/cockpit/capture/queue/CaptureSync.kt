package ch.sonensei.cockpit.capture.queue

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Starts sending the queue once the network is up. */
interface CaptureSyncScheduler {
    suspend fun schedule()
}

@Singleton
class WorkManagerCaptureSyncScheduler
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) : CaptureSyncScheduler {
        override suspend fun schedule() {
            val workManager = WorkManager.getInstance(context)
            val request =
                OneTimeWorkRequestBuilder<CaptureSyncWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                    .build()
            val states = workManager.getWorkInfosForUniqueWorkFlow(WORK_NAME).first().map { it.state }
            workManager.enqueueUniqueWork(WORK_NAME, policyFor(states), request).await()
        }

        companion object {
            const val WORK_NAME = "capture-queue"
            const val BACKOFF_SECONDS = 30L

            /**
             * APPEND_OR_REPLACE while a run is sending: it may have read the queue before the new capture, so another
             * run follows it. Otherwise REPLACE: a job that only waits (for the network or out a retry backoff, which
             * grows to hours) would hold the new capture back, and starting afresh is safe because the capture is
             * idempotent and uploaded file ids are stored. Accepted race: a job that starts between reading the states
             * and enqueueing is replaced mid-run; a file it uploaded but had not stored the id of yet is uploaded again
             * and the first copy stays unreferenced on the server. No capture is lost or duplicated.
             */
            internal fun policyFor(states: List<WorkInfo.State>): ExistingWorkPolicy =
                if (WorkInfo.State.RUNNING in states) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.REPLACE
        }
    }

/**
 * The job that sends the queue. WorkManager creates it with its default factory, so it gets the [CaptureUploader] from
 * Hilt through an entry point; tests pass their own.
 */
class CaptureSyncWorker internal constructor(
    context: Context,
    params: WorkerParameters,
    private val uploader: () -> CaptureUploader,
) : CoroutineWorker(context, params) {
    constructor(context: Context, params: WorkerParameters) : this(context, params, {
        EntryPointAccessors.fromApplication(context, CaptureQueueEntryPoint::class.java).captureUploader()
    })

    override suspend fun doWork(): Result =
        try {
            when (uploader().sendAll()) {
                SendResult.DONE -> Result.success()
                SendResult.RETRY -> Result.retry()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // A failure would end the job and every run appended to it, leaving the queue unsent until the next
            // capture (e.g. the database failing to open): try again with backoff instead.
            Result.retry()
        }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface CaptureQueueEntryPoint {
    fun captureUploader(): CaptureUploader
}
