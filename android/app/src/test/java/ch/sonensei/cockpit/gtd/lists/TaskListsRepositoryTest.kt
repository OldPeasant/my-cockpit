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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockWebServer
import okhttp3.MediaType.Companion.toMediaType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.time.LocalDate
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class TaskListsRepositoryTest {
    private val dispatcher = RoutingDispatcher()
    private val server = MockWebServer().apply { this.dispatcher = this@TaskListsRepositoryTest.dispatcher }
    private lateinit var harness: GtdHarness
    private lateinit var repository: TaskListsRepository

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
        repository = TaskListsRepository(api, harness.connectivity, harness.triggers, harness.tasks)
    }

    @After
    fun tearDown() {
        harness.close()
        server.close()
    }

    @Test
    fun `loads the waiting list with its waiting fields`() =
        runBlocking {
            dispatcher.enqueue(
                "$TASKS|WAITING",
                json(taskPage(taskJson(1, "Contract", "WAITING", ""","waitingForText":"Anna","waitingSince":"2026-10-11""""))),
            )
            val live = withTimeout(TIMEOUT) { repository.observe(TaskList.WAITING, MutableSharedFlow()).first { it.data != null } }
            val task = live.data!!.single()
            assertEquals("Anna", task.waitingForText)
            assertEquals(LocalDate.of(2026, 10, 11), task.waitingSince)
            assertEquals(TaskList.WAITING, task.list)
        }

    @Test
    fun `an unreachable server is an error, and a retry loads`() =
        runBlocking {
            dispatcher.enqueue("$TASKS|SOMEDAY", status(503))
            dispatcher.enqueue("$TASKS|SOMEDAY", json(taskPage(taskJson(4, "Learn Italian", "SOMEDAY"))))
            val reloads = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
            val states = repository.observe(TaskList.SOMEDAY, reloads).produceIn(this)
            withTimeout(TIMEOUT) {
                assertEquals(Live<List<GtdTask>>(), states.receive())
                assertEquals(Live<List<GtdTask>>(error = LoadError.OFFLINE), states.receive())
                reloads.emit(Unit)
                assertEquals(Live<List<GtdTask>>(), states.receive())
                assertEquals(listOf("Learn Italian"), states.receive().data!!.map { it.title })
            }
            states.cancel()
        }

    @Test
    fun `project tasks are loaded by project id in the project's order`() =
        runBlocking {
            dispatcher.enqueue(TASKS, json(taskPage(taskJson(5, "Second", "NEXT"), taskJson(2, "First", "WAITING"))))
            val live =
                withTimeout(TIMEOUT) {
                    repository.observeProject(UUID.fromString(uuid(10)), MutableSharedFlow()).first {
                        it.data !=
                            null
                    }
                }
            assertEquals(listOf("First", "Second"), live.data!!.map { it.title })
            assertEquals(
                uuid(10),
                dispatcher.requests
                    .first { it.url.encodedPath == TASKS }
                    .url
                    .queryParameter("projectId"),
            )
        }

    @Test
    fun `activate moves to next with the task's version, a precondition failure is a conflict`() =
        runBlocking {
            val task = GtdTask(UUID.fromString(uuid(4)), "Learn Italian", TaskList.SOMEDAY, 0, 3)
            dispatcher.enqueue("$TASKS/${uuid(4)}/move", json(taskJson(4, "Learn Italian", "NEXT")))
            dispatcher.enqueue("$TASKS/${uuid(4)}/move", status(412))
            assertEquals(ActionOutcome.DONE, repository.activate(task))
            val move = dispatcher.requests.first { it.url.encodedPath.endsWith("/move") }
            assertEquals("\"3\"", move.headers["If-Match"])
            assertEquals(true, move.body!!.utf8().contains("\"list\":\"NEXT\""))
            assertEquals(ActionOutcome.CONFLICT, repository.activate(task))
        }

    @Test
    fun `follow up creates a next action in the same project`() =
        runBlocking {
            val task = GtdTask(UUID.fromString(uuid(1)), "Contract", TaskList.WAITING, 0, 1, projectId = UUID.fromString(uuid(10)))
            dispatcher.enqueue(TASKS, json(taskJson(7, "Follow up: Contract", "NEXT")))
            assertEquals(ActionOutcome.DONE, repository.followUp(task, "Follow up: Contract"))
            val body =
                dispatcher.requests
                    .first { it.method == "POST" }
                    .body!!
                    .utf8()
            assertEquals(true, body.contains("\"projectId\":\"${uuid(10)}\""))
            assertEquals(true, body.contains("\"list\":\"NEXT\""))
        }

    private companion object {
        const val TIMEOUT = 10_000L
    }
}
