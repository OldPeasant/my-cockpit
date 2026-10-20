package ch.sonensei.cockpit.capture.queue

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Starts sending the queue once the network is up. */
fun interface CaptureSyncScheduler {
    fun schedule()
}

@Singleton
class WorkManagerCaptureSyncScheduler
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) : CaptureSyncScheduler {
        override fun schedule() {
            val request =
                OneTimeWorkRequestBuilder<CaptureSyncWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                    .build()
            // APPEND_OR_REPLACE: a run already sending may have read the queue before this capture, so another run follows
            // it; a run waiting for its retry sends everything queued anyway.
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }

        companion object {
            const val WORK_NAME = "capture-queue"
            const val BACKOFF_SECONDS = 30L
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
        when (uploader().sendAll()) {
            SendResult.DONE -> Result.success()
            SendResult.RETRY -> Result.retry()
        }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface CaptureQueueEntryPoint {
    fun captureUploader(): CaptureUploader
}
