package ch.sonensei.cockpit.capture.queue

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CaptureSyncTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun worker(result: SendResult): CaptureSyncWorker {
        val uploader = mockk<CaptureUploader> { coEvery { sendAll() } returns result }
        return TestListenableWorkerBuilder<CaptureSyncWorker>(context)
            .setWorkerFactory(
                object : WorkerFactory() {
                    override fun createWorker(
                        appContext: Context,
                        workerClassName: String,
                        workerParameters: WorkerParameters,
                    ): ListenableWorker = CaptureSyncWorker(appContext, workerParameters) { uploader }
                },
            ).build()
    }

    @Test
    fun `the job succeeds when the queue is sent and retries otherwise`() =
        runBlocking {
            assertEquals(ListenableWorker.Result.success(), worker(SendResult.DONE).doWork())
            assertEquals(ListenableWorker.Result.retry(), worker(SendResult.RETRY).doWork())
        }

    @Test
    fun `scheduling enqueues one unique job that needs a network`() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val scheduler = WorkManagerCaptureSyncScheduler(context)

        scheduler.schedule()
        scheduler.schedule()

        val infos = WorkManager.getInstance(context).getWorkInfosForUniqueWork(WorkManagerCaptureSyncScheduler.WORK_NAME).get()
        assertEquals(2, infos.size)
        infos.forEach { assertEquals(NetworkType.CONNECTED, it.constraints.requiredNetworkType) }
        assertEquals(WorkInfo.State.ENQUEUED, infos.first { it.state != WorkInfo.State.BLOCKED }.state)
    }
}
