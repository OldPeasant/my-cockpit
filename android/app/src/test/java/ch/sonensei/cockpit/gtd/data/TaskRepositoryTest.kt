package ch.sonensei.cockpit.gtd.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class TaskRepositoryTest {
    private val dispatcher = RoutingDispatcher()
    private val server = MockWebServer().apply { this.dispatcher = this@TaskRepositoryTest.dispatcher }
    private lateinit var gtd: GtdHarness

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

    @Test
    fun `refresh stores the loaded tasks with all fields`() =
        runBlocking {
            val extra =
                ""","projectId":"${uuid(90)}","dueDate":"2026-10-22",""" +
                    """"dueTime":"14:30:00","energy":"LOW","creativity":"CREATIVE","timeEstimateMin":15,""" +
                    """"waitingForText":"Alex","waitingSince":"2026-10-01","scheduledDate":"2026-10-25","notesMd":"n""""
            dispatcher.enqueue("$TASKS|NEXT", json(taskPage(taskJson(1, "Call the plumber", "NEXT", extra, listOf(uuid(80), uuid(81))))))

            assertEquals(RefreshResult.Refreshed, gtd.tasks.refresh(TaskView.NEXT))

            val task =
                gtd.dao
                    .observeTasks("NEXT")
                    .first()
                    .single()
                    .toDomain()
            assertEquals(UUID.fromString(uuid(1)), task.id)
            assertEquals("Call the plumber", task.title)
            assertEquals(TaskList.NEXT, task.list)
            assertEquals(UUID.fromString(uuid(90)), task.projectId)
            assertEquals(listOf(UUID.fromString(uuid(80)), UUID.fromString(uuid(81))), task.contextIds)
            assertEquals(LocalDate.of(2026, 10, 22), task.dueDate)
            assertEquals("14:30:00", task.dueTime)
            assertEquals(Energy.LOW, task.energy)
            assertEquals(Creativity.CREATIVE, task.creativity)
            assertEquals(15, task.timeEstimateMin)
            assertEquals("Alex", task.waitingForText)
            assertEquals(LocalDate.of(2026, 10, 1), task.waitingSince)
            assertEquals(LocalDate.of(2026, 10, 25), task.scheduledDate)
            assertEquals("n", task.notesMd)
            val request = dispatcher.requests.single()
            assertEquals("200", request.url.queryParameter("limit"))
        }

    @Test
    fun `refresh replaces the cached view instead of merging into it`() =
        runBlocking {
            dispatcher.enqueue("$TASKS|INBOX", json(taskPage(taskJson(1, "Old one"), taskJson(2, "Old two"))))
            dispatcher.enqueue("$TASKS|INBOX", json(taskPage(taskJson(3, "New"))))
            gtd.tasks.refresh(TaskView.INBOX)

            assertEquals(RefreshResult.Refreshed, gtd.tasks.refresh(TaskView.INBOX))

            assertEquals(
                listOf("New"),
                gtd.dao
                    .observeTasks("INBOX")
                    .first()
                    .map { it.title },
            )
        }

    @Test
    fun `views are cached separately`() =
        runBlocking {
            dispatcher.enqueue("$TASKS|INBOX", json(taskPage(taskJson(1, "Inbox item"))))
            dispatcher.enqueue("$TASKS|NEXT", json(taskPage(taskJson(2, "Next item", "NEXT"))))
            dispatcher.enqueue("$TASKS|NEXT", json(taskPage()))
            gtd.tasks.refresh(TaskView.INBOX)
            gtd.tasks.refresh(TaskView.NEXT)

            gtd.tasks.refresh(TaskView.NEXT)

            assertEquals(
                listOf("Inbox item"),
                gtd.dao
                    .observeTasks("INBOX")
                    .first()
                    .map { it.title },
            )
            assertEquals(
                emptyList<String>(),
                gtd.dao
                    .observeTasks("NEXT")
                    .first()
                    .map { it.title },
            )
        }

    @Test
    fun `today is overdue then due today, without duplicates`() =
        runBlocking {
            dispatcher.enqueue("$TASKS|overdue", json(taskPage(taskJson(1, "Late"))))
            dispatcher.enqueue("$TASKS|today", json(taskPage(taskJson(2, "Today"), taskJson(1, "Late"))))

            gtd.tasks.refresh(TaskView.TODAY)

            assertEquals(
                listOf("Late", "Today"),
                gtd.dao
                    .observeTasks("TODAY")
                    .first()
                    .map { it.title },
            )
        }

    @Test
    fun `refresh follows the cursor to the last page`() =
        runBlocking {
            dispatcher.enqueue("$TASKS|INBOX", json(taskPage(taskJson(1, "One"), nextCursor = "c2")))
            dispatcher.enqueue("$TASKS|INBOX", json(taskPage(taskJson(2, "Two"))))

            gtd.tasks.refresh(TaskView.INBOX)

            assertEquals(
                listOf("One", "Two"),
                gtd.dao
                    .observeTasks("INBOX")
                    .first()
                    .map { it.title },
            )
            assertEquals(listOf(null, "c2"), dispatcher.requests.map { it.url.queryParameter("cursor") })
        }

    @Test
    fun `unreachable server keeps the cache and serves it read-only as offline`() =
        runBlocking {
            dispatcher.enqueue("$TASKS|INBOX", json(taskPage(taskJson(1, "Buy milk"))))
            gtd.tasks.refresh(TaskView.INBOX)
            server.close()

            assertEquals(RefreshResult.Offline, gtd.tasks.refresh(TaskView.INBOX))

            val cached = gtd.tasks.observe(TaskView.INBOX).first { it.offline }
            assertEquals(listOf("Buy milk"), cached.data.map { it.title })
            assertTrue(cached.readOnly)
            assertEquals(Instant.parse("2026-10-20T10:00:00Z"), cached.refreshedAt)
        }

    @Test
    fun `offline without anything cached yields an empty, never loaded view`() =
        runBlocking {
            server.close()

            val cached = withTimeout(5_000) { gtd.tasks.observe(TaskView.NEXT).first { it.offline } }

            assertEquals(emptyList<GtdTask>(), cached.data)
            assertNull(cached.refreshedAt)
        }

    @Test
    fun `a server error counts as unreachable`() =
        runBlocking {
            dispatcher.enqueue("$TASKS|INBOX", json(taskPage(taskJson(1, "Kept"))))
            dispatcher.enqueue("$TASKS|INBOX", status(502))
            gtd.tasks.refresh(TaskView.INBOX)

            assertEquals(RefreshResult.Offline, gtd.tasks.refresh(TaskView.INBOX))

            assertTrue(gtd.connectivity.offline.value)
            assertEquals(
                listOf("Kept"),
                gtd.dao
                    .observeTasks("INBOX")
                    .first()
                    .map { it.title },
            )
        }

    @Test
    fun `a rejected request keeps the cache and is not offline`() =
        runBlocking {
            dispatcher.enqueue("$TASKS|INBOX", json(taskPage(taskJson(1, "Kept"))))
            dispatcher.enqueue("$TASKS|INBOX", status(400))
            gtd.tasks.refresh(TaskView.INBOX)

            assertEquals(RefreshResult.Failed(400), gtd.tasks.refresh(TaskView.INBOX))

            assertFalse(gtd.connectivity.offline.value)
            assertEquals(
                listOf("Kept"),
                gtd.dao
                    .observeTasks("INBOX")
                    .first()
                    .map { it.title },
            )
        }

    @Test
    fun `an unreadable body keeps the cache`() =
        runBlocking {
            dispatcher.enqueue("$TASKS|INBOX", json(taskPage(taskJson(1, "Kept"))))
            dispatcher.enqueue("$TASKS|INBOX", json("""{"items":[{"id":"x"}]}"""))
            gtd.tasks.refresh(TaskView.INBOX)

            assertEquals(RefreshResult.Failed(null), gtd.tasks.refresh(TaskView.INBOX))

            assertEquals(
                listOf("Kept"),
                gtd.dao
                    .observeTasks("INBOX")
                    .first()
                    .map { it.title },
            )
        }

    @Test
    fun `a failing page leaves the earlier cache whole`() =
        runBlocking {
            dispatcher.enqueue("$TASKS|INBOX", json(taskPage(taskJson(1, "Kept"))))
            dispatcher.enqueue("$TASKS|INBOX", json(taskPage(taskJson(2, "Page one"), nextCursor = "c2")))
            dispatcher.enqueue("$TASKS|INBOX", status(503))
            gtd.tasks.refresh(TaskView.INBOX)

            assertEquals(RefreshResult.Offline, gtd.tasks.refresh(TaskView.INBOX))

            assertEquals(
                listOf("Kept"),
                gtd.dao
                    .observeTasks("INBOX")
                    .first()
                    .map { it.title },
            )
        }

    @Test
    fun `observing loads the view and clears the offline flag once the server answers`() =
        runBlocking {
            server.close()
            gtd.tasks.refresh(TaskView.INBOX)
            assertTrue(gtd.connectivity.offline.value)
            val restarted = MockWebServer().apply { this.dispatcher = this@TaskRepositoryTest.dispatcher }
            restarted.start(server.port)
            try {
                dispatcher.enqueue("$TASKS|INBOX", json(taskPage(taskJson(1, "Fresh"))))

                val cached =
                    withTimeout(5_000) { gtd.tasks.observe(TaskView.INBOX).first { !it.offline && it.data.isNotEmpty() } }

                assertEquals(listOf("Fresh"), cached.data.map { it.title })
                assertFalse(cached.readOnly)
                assertTrue(gtd.scheduler.scheduled > 0)
            } finally {
                restarted.close()
            }
        }
}
