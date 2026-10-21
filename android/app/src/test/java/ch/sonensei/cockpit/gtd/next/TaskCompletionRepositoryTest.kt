package ch.sonensei.cockpit.gtd.next

import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.core.network.generated.apis.TasksApi
import ch.sonensei.cockpit.core.network.generated.infrastructure.Serializer
import ch.sonensei.cockpit.gtd.data.GtdHarness
import ch.sonensei.cockpit.gtd.data.GtdTask
import ch.sonensei.cockpit.gtd.data.RoutingDispatcher
import ch.sonensei.cockpit.gtd.data.TASKS
import ch.sonensei.cockpit.gtd.data.TaskList
import ch.sonensei.cockpit.gtd.data.TaskView
import ch.sonensei.cockpit.gtd.data.json
import ch.sonensei.cockpit.gtd.data.status
import ch.sonensei.cockpit.gtd.data.taskJson
import ch.sonensei.cockpit.gtd.data.taskPage
import ch.sonensei.cockpit.gtd.data.uuid
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import okhttp3.MediaType.Companion.toMediaType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class TaskCompletionRepositoryTest {
    private val dispatcher = RoutingDispatcher()
    private val server = MockWebServer().apply { this.dispatcher = this@TaskCompletionRepositoryTest.dispatcher }
    private lateinit var harness: GtdHarness
    private lateinit var repository: TaskCompletionRepository

    private val task = GtdTask(UUID.fromString(uuid(1)), "Call the dentist", TaskList.NEXT, 1, 3)

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
        repository = TaskCompletionRepository(api, harness.tasks, harness.connectivity)
    }

    @After
    fun tearDown() {
        harness.close()
        server.close()
    }

    private fun enqueueReloads() {
        dispatcher.enqueue("$TASKS|NEXT", json(taskPage()))
        dispatcher.enqueue("$TASKS|overdue", json(taskPage()))
        dispatcher.enqueue("$TASKS|today", json(taskPage()))
    }

    @Test
    fun `completes with the cached version and reloads Next and Today`() =
        runBlocking {
            dispatcher.enqueue("$TASKS/${uuid(1)}/complete", json(completed(version = 4)))
            enqueueReloads()

            val result = repository.complete(task)

            assertEquals(CompletionResult.Completed(Completion(task.id, task.title, 4)), result)
            val complete = dispatcher.requests.first { it.url.encodedPath.endsWith("/complete") }
            assertEquals("POST", complete.method)
            assertEquals("\"3\"", complete.headers["If-Match"])
            val reloaded =
                dispatcher.requests
                    .filter { it.url.encodedPath == TASKS }
                    .map { it.url.queryParameter("list") ?: it.url.queryParameter("due")?.lowercase() }
            assertEquals(setOf("NEXT", "overdue", "today"), reloaded.toSet())
            assertNotNull(harness.dao.taskRows(TaskView.NEXT.name).meta)
            assertNotNull(harness.dao.taskRows(TaskView.TODAY.name).meta)
        }

    @Test
    fun `undo uncompletes with the completed version`() =
        runBlocking {
            dispatcher.enqueue("$TASKS/${uuid(1)}/uncomplete", json(taskJson(1, "Call the dentist", "NEXT")))
            enqueueReloads()

            assertEquals(CompletionResult.Reopened, repository.undo(Completion(task.id, task.title, 4)))
            val uncomplete = dispatcher.requests.first { it.url.encodedPath.endsWith("/uncomplete") }
            assertEquals("\"4\"", uncomplete.headers["If-Match"])
        }

    @Test
    fun `a stale version is a conflict and the views reload`() =
        runBlocking {
            dispatcher.enqueue("$TASKS/${uuid(1)}/complete", status(412))
            enqueueReloads()

            assertEquals(CompletionResult.NotDone(CompletionMessage.CONFLICT), repository.complete(task))
            assertEquals(true, dispatcher.requests.any { it.url.queryParameter("list") == "NEXT" })
        }

    @Test
    fun `a server error is offline and nothing reloads`() =
        runBlocking {
            dispatcher.enqueue("$TASKS/${uuid(1)}/complete", status(503))

            assertEquals(CompletionResult.NotDone(CompletionMessage.OFFLINE), repository.complete(task))
            assertEquals(true, harness.connectivity.offline.first())
            assertEquals(1, dispatcher.requests.size)
        }

    @Test
    fun `another refusal is a failure`() =
        runBlocking {
            dispatcher.enqueue("$TASKS/${uuid(1)}/complete", status(400))
            enqueueReloads()

            assertEquals(CompletionResult.NotDone(CompletionMessage.FAILED), repository.complete(task))
        }

    @Test
    fun `a task trashed elsewhere is a conflict`() =
        runBlocking {
            dispatcher.enqueue("$TASKS/${uuid(1)}/complete", status(404))
            enqueueReloads()

            assertEquals(CompletionResult.NotDone(CompletionMessage.CONFLICT), repository.complete(task))
        }

    @Test
    fun `undo refused by a newer version is a conflict and the views reload`() =
        runBlocking {
            dispatcher.enqueue("$TASKS/${uuid(1)}/uncomplete", status(409))
            enqueueReloads()

            assertEquals(CompletionResult.NotDone(CompletionMessage.CONFLICT), repository.undo(Completion(task.id, task.title, 4)))
            assertEquals(true, dispatcher.requests.any { it.url.queryParameter("list") == "NEXT" })
        }

    @Test
    fun `undo while the server is unreachable is offline and nothing reloads`() =
        runBlocking {
            dispatcher.enqueue("$TASKS/${uuid(1)}/uncomplete", status(502))

            assertEquals(CompletionResult.NotDone(CompletionMessage.OFFLINE), repository.undo(Completion(task.id, task.title, 4)))
            assertEquals(1, dispatcher.requests.size)
        }

    @Test
    fun `an unreadable completion answer is a failure`() =
        runBlocking {
            dispatcher.enqueue("$TASKS/${uuid(1)}/complete", json("{\"unexpected\":true}"))
            enqueueReloads()

            assertEquals(CompletionResult.NotDone(CompletionMessage.FAILED), repository.complete(task))
        }

    private fun completed(version: Int) =
        taskJson(1, "Call the dentist", "NEXT", extra = ""","needsNextStep":false,"completedAt":"2026-10-20T10:00:00Z"""")
            .replace("\"version\":1", "\"version\":$version")
}
