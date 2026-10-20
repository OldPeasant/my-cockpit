package ch.sonensei.cockpit.gtd.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import ch.sonensei.cockpit.core.network.AuthSession
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class GtdSyncTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dispatcher = RoutingDispatcher()
    private val server = MockWebServer().apply { this.dispatcher = this@GtdSyncTest.dispatcher }
    private lateinit var gtd: GtdHarness
    private val session = mockk<AuthSession> { every { isLoggedIn } returns true }

    @Before
    fun setUp() {
        server.start()
        gtd = GtdHarness(server, dispatcher)
    }

    @After
    fun tearDown() {
        gtd.close()
        server.close()
    }

    private fun sync() = GtdSync(session, gtd.tasks, gtd.projects, gtd.contexts)

    private fun enqueueAllViews() {
        dispatcher.enqueue(CONTEXTS, json("""{"items":[${contextJson(1, "@home")}]}"""))
        dispatcher.enqueue(PROJECTS, json(taskPage(projectJson(2, "Move house"))))
        dispatcher.enqueue("$TASKS|INBOX", json(taskPage(taskJson(3, "Inbox"))))
        dispatcher.enqueue("$TASKS|NEXT", json(taskPage(taskJson(4, "Next", "NEXT"))))
        dispatcher.enqueue("$TASKS|overdue", json(taskPage()))
        dispatcher.enqueue("$TASKS|today", json(taskPage(taskJson(5, "Today", "NEXT"))))
    }

    private fun worker(sync: GtdSync): GtdSyncWorker =
        TestListenableWorkerBuilder<GtdSyncWorker>(context)
            .setWorkerFactory(
                object : WorkerFactory() {
                    override fun createWorker(
                        appContext: Context,
                        workerClassName: String,
                        workerParameters: WorkerParameters,
                    ): ListenableWorker = GtdSyncWorker(appContext, workerParameters) { sync }
                },
            ).build()

    @Test
    fun `sync refreshes every cached view`() =
        runBlocking {
            enqueueAllViews()

            assertEquals(ListenableWorker.Result.success(), worker(sync()).doWork())

            assertEquals(
                listOf("@home"),
                gtd.dao
                    .observeContexts()
                    .first()
                    .map { it.name },
            )
            assertEquals(
                listOf("Move house"),
                gtd.dao
                    .observeProjects()
                    .first()
                    .map { it.title },
            )
            assertEquals(
                listOf("Inbox"),
                gtd.dao
                    .observeTasks("INBOX")
                    .first()
                    .map { it.title },
            )
            assertEquals(
                listOf("Next"),
                gtd.dao
                    .observeTasks("NEXT")
                    .first()
                    .map { it.title },
            )
            assertEquals(
                listOf("Today"),
                gtd.dao
                    .observeTasks("TODAY")
                    .first()
                    .map { it.title },
            )
        }

    @Test
    fun `sync retries while the server is unreachable`() =
        runBlocking {
            server.close()

            assertEquals(ListenableWorker.Result.retry(), worker(sync()).doWork())
        }

    @Test
    fun `sync does not retry a rejected request`() =
        runBlocking {
            enqueueAllViews()
            dispatcher.enqueue(CONTEXTS, status(400))
            sync().refreshAll()

            assertTrue(sync().refreshAll() is RefreshResult.Failed)
            assertEquals(ListenableWorker.Result.success(), worker(sync()).doWork())
        }

    @Test
    fun `sync does nothing when logged out`() =
        runBlocking {
            every { session.isLoggedIn } returns false

            assertEquals(ListenableWorker.Result.success(), worker(sync()).doWork())

            assertTrue(dispatcher.requests.isEmpty())
        }

    @Test
    fun `scheduler enqueues one periodic 15 minute sync that needs a network`() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val scheduler = WorkManagerGtdSyncScheduler(context)

        scheduler.ensureScheduled()
        scheduler.ensureScheduled()

        val infos = WorkManager.getInstance(context).getWorkInfosForUniqueWork(WorkManagerGtdSyncScheduler.WORK_NAME).get()
        val info = infos.single()
        assertEquals(WorkInfo.State.ENQUEUED, info.state)
        assertEquals(TimeUnit.MINUTES.toMillis(15), info.periodicityInfo?.repeatIntervalMillis)
        assertEquals(androidx.work.NetworkType.CONNECTED, info.constraints.requiredNetworkType)

        scheduler.cancel()

        val cancelled = WorkManager.getInstance(context).getWorkInfosForUniqueWork(WorkManagerGtdSyncScheduler.WORK_NAME).get()
        assertEquals(WorkInfo.State.CANCELLED, cancelled.single().state)
    }

    @Test
    fun `logout clears the cache, the offline flag and the periodic sync`() =
        runBlocking {
            enqueueAllViews()
            sync().refreshAll()
            server.close()
            gtd.tasks.refresh(TaskView.INBOX)
            assertTrue(gtd.connectivity.offline.value)

            GtdDataModule.gtdCache(gtd.database, gtd.scheduler, gtd.connectivity).clear()

            assertTrue(
                gtd.dao
                    .observeTasks("INBOX")
                    .first()
                    .isEmpty(),
            )
            assertTrue(
                gtd.dao
                    .observeProjects()
                    .first()
                    .isEmpty(),
            )
            assertTrue(
                gtd.dao
                    .observeContexts()
                    .first()
                    .isEmpty(),
            )
            assertEquals(null, gtd.dao.observeMeta("INBOX").first())
            assertFalse(gtd.connectivity.offline.value)
            assertEquals(1, gtd.scheduler.cancelled)
        }
}
