package ch.sonensei.cockpit.capture.queue

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CaptureSyncTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun factory(uploader: CaptureUploader) =
        object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters,
            ): ListenableWorker = CaptureSyncWorker(appContext, workerParameters) { uploader }
        }

    private fun worker(uploader: CaptureUploader): CaptureSyncWorker =
        TestListenableWorkerBuilder<CaptureSyncWorker>(context).setWorkerFactory(factory(uploader)).build()

    private fun worker(result: SendResult): CaptureSyncWorker = worker(mockk<CaptureUploader> { coEvery { sendAll() } returns result })

    private fun infos() = WorkManager.getInstance(context).getWorkInfosForUniqueWork(WorkManagerCaptureSyncScheduler.WORK_NAME).get()

    @Test
    fun `the job succeeds when the queue is sent and retries otherwise`() =
        runBlocking {
            assertEquals(ListenableWorker.Result.success(), worker(SendResult.DONE).doWork())
            assertEquals(ListenableWorker.Result.retry(), worker(SendResult.RETRY).doWork())
        }

    @Test
    fun `an unexpected failure retries the job instead of failing it, cancellation still propagates`() =
        runBlocking {
            val broken = mockk<CaptureUploader> { coEvery { sendAll() } throws IllegalStateException("database closed") }
            assertEquals(ListenableWorker.Result.retry(), worker(broken).doWork())

            val cancelled = mockk<CaptureUploader> { coEvery { sendAll() } throws CancellationException("stopped") }
            try {
                worker(cancelled).doWork()
                fail("expected the cancellation to propagate")
            } catch (_: CancellationException) {
            }
        }

    @Test
    fun `scheduling enqueues one unique job that needs a network`() =
        runBlocking {
            WorkManagerTestInitHelper.initializeTestWorkManager(context)
            val scheduler = WorkManagerCaptureSyncScheduler(context)

            scheduler.schedule()
            scheduler.schedule()

            val active = infos().filterNot { it.state.isFinished }
            assertEquals(1, active.size)
            assertEquals(NetworkType.CONNECTED, active.single().constraints.requiredNetworkType)
            assertEquals(WorkInfo.State.ENQUEUED, active.single().state)
        }

    @Test
    fun `a new capture replaces a job waiting out its retry backoff instead of queueing behind it`() =
        runBlocking {
            val uploader = mockk<CaptureUploader> { coEvery { sendAll() } returns SendResult.RETRY }
            WorkManagerTestInitHelper.initializeTestWorkManager(
                context,
                Configuration
                    .Builder()
                    .setExecutor(SynchronousExecutor())
                    .setWorkerFactory(factory(uploader))
                    .build(),
            )
            val scheduler = WorkManagerCaptureSyncScheduler(context)
            val driver = WorkManagerTestInitHelper.getTestDriver(context)!!

            scheduler.schedule()
            val first = infos().single()
            driver.setAllConstraintsMet(first.id)
            val waiting = infos().single()
            assertEquals("the run asked for a retry and now waits out the backoff", WorkInfo.State.ENQUEUED, waiting.state)
            assertEquals(1, waiting.runAttemptCount)

            scheduler.schedule()

            // REPLACE cancels the waiting job and prunes it.
            val old = WorkManager.getInstance(context).getWorkInfoById(first.id).get()
            assertTrue(old == null || old.state == WorkInfo.State.CANCELLED)
            val fresh = infos().single { !it.state.isFinished }
            assertEquals("not BLOCKED behind the old backoff", WorkInfo.State.ENQUEUED, fresh.state)
            assertEquals(0, fresh.runAttemptCount)
            assertTrue(fresh.id != first.id)
        }

    @Test
    fun `a running job gets a follow-up run appended, any other state is replaced`() {
        assertEquals(
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            WorkManagerCaptureSyncScheduler.policyFor(listOf(WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED)),
        )
        listOf(
            emptyList(),
            listOf(WorkInfo.State.ENQUEUED),
            listOf(WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED),
            listOf(WorkInfo.State.FAILED, WorkInfo.State.CANCELLED),
            listOf(WorkInfo.State.SUCCEEDED),
        ).forEach { assertEquals(it.toString(), ExistingWorkPolicy.REPLACE, WorkManagerCaptureSyncScheduler.policyFor(it)) }
    }
}
