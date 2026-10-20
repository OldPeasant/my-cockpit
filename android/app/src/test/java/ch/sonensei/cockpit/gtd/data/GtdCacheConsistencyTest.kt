package ch.sonensei.cockpit.gtd.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Loads that overlap each other, a logout or a failing cache write leave the cache consistent. */
@RunWith(AndroidJUnit4::class)
class GtdCacheConsistencyTest {
    private val dispatcher = RoutingDispatcher()
    private val server = MockWebServer().apply { this.dispatcher = this@GtdCacheConsistencyTest.dispatcher }
    private lateinit var gtd: GtdHarness
    private val release = CountDownLatch(1)

    @Before
    fun setUp() {
        server.start()
        gtd = GtdHarness(server, dispatcher)
    }

    @After
    fun tearDown() {
        release.countDown()
        gtd.close()
        server.close()
    }

    /** Holds back the answer to the first API call until [release]. */
    private fun holdFirstAnswer() {
        val first = AtomicBoolean(true)
        dispatcher.beforeAnswer = { if (first.getAndSet(false)) release.await(5, TimeUnit.SECONDS) }
    }

    private suspend fun awaitRequests(count: Int) = withTimeout(5_000) { while (dispatcher.requests.size < count) delay(10) }

    private suspend fun inboxTitles() =
        gtd.dao
            .observeTasks("INBOX")
            .first()
            .map { it.title }

    @Test
    fun `an older load of a view never overwrites a newer one`() =
        runBlocking {
            holdFirstAnswer()
            dispatcher.enqueue("$TASKS|INBOX", json(taskPage(taskJson(1, "Older"))))
            dispatcher.enqueue("$TASKS|INBOX", json(taskPage(taskJson(2, "Newer"))))

            val older = async(Dispatchers.IO) { gtd.tasks.refresh(TaskView.INBOX) }
            awaitRequests(1)
            val newer = async(Dispatchers.IO) { gtd.tasks.refresh(TaskView.INBOX) }
            // Loading at the same time, the newer load would have been stored by now, before the older one.
            delay(300)
            release.countDown()

            assertEquals(RefreshResult.Refreshed, older.await())
            assertEquals(RefreshResult.Refreshed, newer.await())
            assertEquals(listOf("Newer"), inboxTitles())
        }

    @Test
    fun `a load still running at logout does not refill the cleared cache`() =
        runBlocking {
            holdFirstAnswer()
            dispatcher.enqueue("$TASKS|INBOX", json(taskPage(taskJson(1, "Previous session"))))
            val load = async(Dispatchers.IO) { gtd.tasks.refresh(TaskView.INBOX) }
            awaitRequests(1)

            GtdDataModule.gtdCache(gtd.database, gtd.scheduler, gtd.connectivity).clear()
            release.countDown()

            assertEquals(RefreshResult.Failed(null), load.await())
            assertEquals(emptyList<String>(), inboxTitles())
            assertNull(gtd.dao.observeMeta("INBOX").first())

            // Loads started after the logout are stored again.
            dispatcher.enqueue("$TASKS|INBOX", json(taskPage(taskJson(2, "Next session"))))
            assertEquals(RefreshResult.Refreshed, gtd.tasks.refresh(TaskView.INBOX))
            assertEquals(listOf("Next session"), inboxTitles())
        }

    @Test
    fun `a failing cache write keeps the cache and the observed view`() =
        runBlocking {
            dispatcher.enqueue("$TASKS|INBOX", json(taskPage(taskJson(1, "Kept"))))
            gtd.tasks.refresh(TaskView.INBOX)
            val db = gtd.database.openHelper.writableDatabase
            db.execSQL(
                "CREATE TRIGGER fictitious_full_disk BEFORE INSERT ON cached_task BEGIN SELECT RAISE(ABORT, 'full'); END",
            )
            dispatcher.enqueue("$TASKS|INBOX", json(taskPage(taskJson(2, "Lost"))))

            assertEquals(RefreshResult.Failed(null), gtd.tasks.refresh(TaskView.INBOX))
            assertEquals(listOf("Kept"), inboxTitles())

            dispatcher.enqueue("$TASKS|INBOX", json(taskPage(taskJson(3, "Lost too"))))
            val seen = CopyOnWriteArrayList<Cached<List<GtdTask>>>()
            val job = launch(Dispatchers.Default) { gtd.tasks.observe(TaskView.INBOX).collect { seen += it } }
            awaitRequests(3)
            delay(300)
            assertTrue("the observed view still runs", job.isActive)
            assertEquals(listOf("Kept"), seen.last().data.map { it.title })

            db.execSQL("DROP TRIGGER fictitious_full_disk")
            dispatcher.enqueue("$TASKS|INBOX", json(taskPage(taskJson(4, "Fresh"))))
            assertEquals(RefreshResult.Refreshed, gtd.tasks.refresh(TaskView.INBOX))
            withTimeout(5_000) { while (seen.last().data.map { it.title } != listOf("Fresh")) delay(10) }
            job.cancel()
        }

    @Test
    fun `a load of one view does not wait for a load of another view`() =
        runBlocking {
            holdFirstAnswer()
            dispatcher.enqueue("$TASKS|INBOX", json(taskPage(taskJson(1, "Call the plumber"))))
            dispatcher.enqueue(CONTEXTS, json("""{"items":[${contextJson(2, "@home")}]}"""))

            val inbox = async(Dispatchers.IO) { gtd.tasks.refresh(TaskView.INBOX) }
            awaitRequests(1)

            // The inbox answer is still held back; the contexts load must not queue behind it.
            assertEquals(RefreshResult.Refreshed, withTimeout(3_000) { gtd.contexts.refresh() })
            assertEquals(
                listOf("@home"),
                gtd.dao
                    .observeContexts()
                    .first()
                    .map { it.name },
            )
            release.countDown()
            assertEquals(RefreshResult.Refreshed, inbox.await())
            assertEquals(listOf("Call the plumber"), inboxTitles())
        }

    @Test
    fun `a failing cache write leaves the offline flag as it is`() =
        runBlocking {
            gtd.connectivity.refreshing { throw IOException("fictitious unreachable server") }
            assertTrue(gtd.connectivity.offline.value)
            gtd.database.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER fictitious_full_disk BEFORE INSERT ON cached_task BEGIN SELECT RAISE(ABORT, 'full'); END",
            )
            dispatcher.enqueue("$TASKS|INBOX", json(taskPage(taskJson(1, "Lost"))))

            assertEquals(RefreshResult.Failed(null), gtd.tasks.refresh(TaskView.INBOX))
            assertTrue("a local write failure is not a reachable-server proof", gtd.connectivity.offline.value)
            assertNull(gtd.dao.observeMeta("INBOX").first())
        }

    @Test
    fun `an observed view shows the logout as an empty, never loaded view`() =
        runBlocking {
            dispatcher.enqueue("$TASKS|INBOX", json(taskPage(taskJson(1, "Previous session"))))
            val seen = CopyOnWriteArrayList<Cached<List<GtdTask>>>()
            val job = launch(Dispatchers.Default) { gtd.tasks.observe(TaskView.INBOX).collect { seen += it } }
            withTimeout(5_000) { while (seen.lastOrNull()?.data?.map { it.title } != listOf("Previous session")) delay(10) }

            GtdDataModule.gtdCache(gtd.database, gtd.scheduler, gtd.connectivity).clear()

            withTimeout(5_000) { while (seen.last().data.isNotEmpty()) delay(10) }
            job.cancel()
            assertNull("no refresh time after the logout", seen.last().refreshedAt)
            assertEquals(false, seen.last().offline)
        }

    @Test
    fun `loaded rows are never shown without their refresh time`() =
        runBlocking {
            val seen = CopyOnWriteArrayList<Cached<List<GtdTask>>>()
            repeat(6) { n -> dispatcher.enqueue("$TASKS|INBOX", json(taskPage(taskJson(n, "Load $n")))) }
            val job = launch(Dispatchers.Default) { gtd.tasks.observe(TaskView.INBOX).collect { seen += it } }
            awaitRequests(1)
            repeat(5) { gtd.tasks.refresh(TaskView.INBOX) }
            withTimeout(5_000) { while (seen.lastOrNull()?.data?.map { it.title } != listOf("Load 5")) delay(10) }
            job.cancel()

            assertTrue("emissions $seen", seen.none { it.data.isNotEmpty() && it.refreshedAt == null })
        }
}
