package ch.sonensei.cockpit.gtd.lists

import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.core.network.generated.apis.TasksApi
import ch.sonensei.cockpit.core.network.generated.infrastructure.Serializer
import ch.sonensei.cockpit.gtd.data.GtdHarness
import ch.sonensei.cockpit.gtd.data.GtdTask
import ch.sonensei.cockpit.gtd.data.RoutingDispatcher
import ch.sonensei.cockpit.gtd.data.TASKS
import ch.sonensei.cockpit.gtd.data.TaskList
import ch.sonensei.cockpit.gtd.data.json
import ch.sonensei.cockpit.gtd.data.status
import ch.sonensei.cockpit.gtd.data.taskJson
import ch.sonensei.cockpit.gtd.data.taskPage
import ch.sonensei.cockpit.gtd.data.uuid
import ch.sonensei.cockpit.gtd.next.TaskCompletionRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import okhttp3.MediaType.Companion.toMediaType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.time.Instant
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LogbookRepositoryTest {
    private val dispatcher = RoutingDispatcher()
    private val server = MockWebServer().apply { this.dispatcher = this@LogbookRepositoryTest.dispatcher }
    private lateinit var harness: GtdHarness
    private lateinit var repository: LogbookRepository

    @Before
    fun setUp() {
        server.start()
        harness = GtdHarness(server, dispatcher)
        val api =
            Retrofit
                .Builder()
                .baseUrl(server.url("/"))
                .addConverterFactory(Serializer.kotlinxSerializationJson.asConverterFactory("application/json".toMediaType()))
                .build()
                .create(TasksApi::class.java)
        repository =
            LogbookRepository(
                api,
                harness.connectivity,
                harness.triggers,
                TaskCompletionRepository(api, harness.tasks, harness.connectivity),
            )
    }

    @After
    fun tearDown() {
        harness.close()
        server.close()
    }

    private fun done(
        n: Int,
        title: String,
        at: String,
    ) = taskJson(n, title, "NEXT", ""","completedAt":"$at","projectId":"${uuid(10)}"""", contextIds = listOf(uuid(20)))

    @Test
    fun `loads a page of done tasks with their completion time and cursor`() =
        runBlocking {
            dispatcher.enqueue(
                TASKS,
                json(
                    taskPage(
                        done(1, "File taxes", "2026-10-20T09:00:00Z"),
                        done(2, "Call Ben", "2026-10-19T17:30:00+02:00"),
                        nextCursor = "c2",
                    ),
                ),
            )
            val load = repository.page(null) as LogbookLoad.Loaded
            assertEquals(listOf("File taxes", "Call Ben"), load.page.tasks.map { it.task.title })
            assertEquals(Instant.parse("2026-10-19T15:30:00Z"), load.page.tasks[1].completedAt)
            assertEquals(
                UUID.fromString(uuid(10)),
                load.page.tasks[0]
                    .task.projectId,
            )
            assertEquals(
                listOf(UUID.fromString(uuid(20))),
                load.page.tasks[0]
                    .task.contextIds,
            )
            assertEquals("c2", load.page.nextCursor)
            val url = dispatcher.requests.last().url
            assertEquals("true", url.queryParameter("completed"))
            assertEquals("$LOGBOOK_PAGE_SIZE", url.queryParameter("limit"))
            assertEquals(null, url.queryParameter("cursor"))
        }

    @Test
    fun `sends the cursor and skips tasks without a completion time`() =
        runBlocking {
            dispatcher.enqueue(TASKS, json(taskPage(taskJson(3, "Reopened meanwhile", "NEXT"), done(4, "Old", "2026-09-01T08:00:00Z"))))
            val load = repository.page("c2") as LogbookLoad.Loaded
            assertEquals(listOf("Old"), load.page.tasks.map { it.task.title })
            assertEquals(null, load.page.nextCursor)
            assertEquals(
                "c2",
                dispatcher.requests
                    .last()
                    .url
                    .queryParameter("cursor"),
            )
        }

    @Test
    fun `an unreachable server and a refusal are load errors`() =
        runBlocking {
            dispatcher.enqueue(TASKS, status(503))
            dispatcher.enqueue(TASKS, status(400))
            assertEquals(LogbookLoad.NotLoaded(LoadError.OFFLINE), repository.page(null))
            assertEquals(LogbookLoad.NotLoaded(LoadError.FAILED), repository.page(null))
        }

    @Test
    fun `reopen uncompletes the task with its version and maps refusals`() =
        runBlocking {
            val entry =
                LoggedTask(GtdTask(UUID.fromString(uuid(1)), "File taxes", TaskList.NEXT, 0, 7), Instant.parse("2026-10-20T09:00:00Z"))
            val path = "$TASKS/${uuid(1)}/uncomplete"
            dispatcher.enqueue(path, json(taskJson(1, "File taxes", "NEXT")))
            dispatcher.enqueue(path, status(412))
            dispatcher.enqueue(path, status(503))
            dispatcher.enqueue(path, status(400))
            assertEquals(ActionOutcome.DONE, repository.reopen(entry))
            val request = dispatcher.requests.first { it.url.encodedPath == path }
            assertEquals("POST", request.method)
            assertEquals("\"7\"", request.headers["If-Match"])
            assertEquals(ActionOutcome.CONFLICT, repository.reopen(entry))
            assertEquals(ActionOutcome.OFFLINE, repository.reopen(entry))
            assertEquals(ActionOutcome.FAILED, repository.reopen(entry))
        }

    @Test
    fun `a malformed page is a failed load`() =
        runBlocking {
            dispatcher.enqueue(TASKS, json("""{"items":"not a list"}"""))
            assertEquals(LogbookLoad.NotLoaded(LoadError.FAILED), repository.page(null))
        }

    @Test
    fun `reopening a task trashed elsewhere is a conflict, so the logbook reloads`() =
        runBlocking {
            val entry =
                LoggedTask(GtdTask(UUID.fromString(uuid(1)), "File taxes", TaskList.NEXT, 0, 7), Instant.parse("2026-10-20T09:00:00Z"))
            dispatcher.enqueue("$TASKS/${uuid(1)}/uncomplete", status(404))
            assertEquals(ActionOutcome.CONFLICT, repository.reopen(entry))
        }

    @Test
    fun `a reopened task is back on its list, so the cached Next and Today views reload`() =
        runBlocking {
            val entry =
                LoggedTask(GtdTask(UUID.fromString(uuid(1)), "File taxes", TaskList.NEXT, 0, 7), Instant.parse("2026-10-20T09:00:00Z"))
            dispatcher.enqueue("$TASKS/${uuid(1)}/uncomplete", json(taskJson(1, "File taxes", "NEXT")))
            assertEquals(ActionOutcome.DONE, repository.reopen(entry))
            val lists = dispatcher.requests.filter { it.url.encodedPath == TASKS }.map { it.url.query }
            assertTrue("Next was not reloaded: $lists", lists.any { it?.contains("list=NEXT") == true })
            assertTrue("Today was not reloaded: $lists", lists.any { it?.contains("due=") == true })
        }

    @Test
    fun `the logbook is told to load at once`() =
        runBlocking {
            assertEquals(Unit, repository.changes().first())
        }
}
