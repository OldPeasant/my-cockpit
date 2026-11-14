package ch.sonensei.cockpit.gtd.task

import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.core.network.generated.apis.CalendarsApi
import ch.sonensei.cockpit.core.network.generated.apis.ProjectsApi
import ch.sonensei.cockpit.core.network.generated.apis.TasksApi
import ch.sonensei.cockpit.core.network.generated.infrastructure.Serializer
import ch.sonensei.cockpit.gtd.data.GtdHarness
import ch.sonensei.cockpit.gtd.data.GtdProject
import ch.sonensei.cockpit.gtd.data.PROJECTS
import ch.sonensei.cockpit.gtd.data.ProjectStatus
import ch.sonensei.cockpit.gtd.data.RoutingDispatcher
import ch.sonensei.cockpit.gtd.data.json
import ch.sonensei.cockpit.gtd.data.projectJson
import ch.sonensei.cockpit.gtd.data.status
import ch.sonensei.cockpit.gtd.data.taskPage
import ch.sonensei.cockpit.gtd.data.uuid
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import mockwebserver3.MockResponse
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
import java.util.UUID

/** "New project…" of the task detail: the POST it sends, the projects reload, and how failures are read. */
@RunWith(AndroidJUnit4::class)
class TaskDetailNewProjectRepositoryTest {
    private val dispatcher = RoutingDispatcher()
    private val server = MockWebServer().apply { this.dispatcher = this@TaskDetailNewProjectRepositoryTest.dispatcher }
    private lateinit var harness: GtdHarness
    private lateinit var repository: TaskDetailRepository

    @Before
    fun setUp() {
        server.start()
        harness = GtdHarness(server, dispatcher)
        val retrofit =
            Retrofit
                .Builder()
                .baseUrl(server.url("/"))
                .addConverterFactory(Serializer.kotlinxSerializationJson.asConverterFactory("application/json".toMediaType()))
                .build()
        repository =
            TaskDetailRepository(
                retrofit.create(TasksApi::class.java),
                retrofit.create(TaskWriteApi::class.java),
                harness.dao,
                harness.connectivity,
                harness.tasks,
                TaskConverter(retrofit.create(TasksApi::class.java), retrofit.create(CalendarsApi::class.java)),
                retrofit.create(ProjectsApi::class.java),
                harness.projects,
            )
    }

    @After
    fun tearDown() {
        harness.close()
        server.close()
    }

    private fun posts() = dispatcher.requests.filter { it.method == "POST" && it.url.encodedPath == PROJECTS }

    private fun projectLoads() = dispatcher.requests.count { it.method == "GET" && it.url.encodedPath == PROJECTS }

    @Test
    fun `creates an Active project with only the title, then reloads the projects`() =
        runBlocking {
            dispatcher.enqueue(PROJECTS, json(projectJson(50, "Kitchen")))
            dispatcher.enqueue(PROJECTS, json(taskPage(projectJson(20, "Garden"), projectJson(50, "Kitchen"))))

            val project = (repository.createProject("Kitchen") as TaskResult.Ok).value

            assertEquals(
                GtdProject(UUID.fromString(uuid(50)), "Kitchen", ProjectStatus.ACTIVE, stalled = false, version = 2),
                project,
            )
            val body = Json.parseToJsonElement(posts().single().body!!.utf8()).jsonObject
            assertEquals(JsonPrimitive("Kitchen"), body["title"])
            assertTrue("no area: $body", body["areaId"] == null || body["areaId"] == JsonNull)
            assertTrue("only a title: $body", body.filterValues { it != JsonNull }.keys == setOf("title"))
            assertEquals(1, projectLoads())
            val cached = withTimeout(5_000) { harness.dao.observeProjects().first { it.isNotEmpty() } }
            assertEquals(listOf("Garden", "Kitchen"), cached.map { it.title })
        }

    @Test
    fun `a refused title gives the problem's field message and reloads nothing`() =
        runBlocking {
            dispatcher.enqueue(
                PROJECTS,
                MockResponse
                    .Builder()
                    .code(422)
                    .addHeader("Content-Type", "application/problem+json")
                    .body(
                        """{"status":422,"detail":"Invalid project","errors":[{"field":"title","message":"must be at most 200 characters"}]}""",
                    ).build(),
            )

            assertEquals(
                TaskResult.Invalid(mapOf("title" to "must be at most 200 characters"), "Invalid project"),
                repository.createProject("Kitchen"),
            )
            assertEquals(0, projectLoads())
        }

    @Test
    fun `an unreachable server is offline, a refused request a failure, neither reloads the projects`() =
        runBlocking {
            dispatcher.enqueue(PROJECTS, status(503))
            assertEquals(TaskResult.Offline, repository.createProject("Kitchen"))

            dispatcher.enqueue(PROJECTS, status(403))
            assertEquals(TaskResult.Failed(403), repository.createProject("Kitchen"))

            assertEquals(0, projectLoads())
        }

    private val projectId = UUID.fromString(uuid(50))
    private val taskId = UUID.fromString(uuid(1))

    private fun stalledKitchen() = json(projectJson(50, "Kitchen").replace("\"stalled\":false", "\"stalled\":true"))

    private fun nextStepCalls() = dispatcher.requests.filter { it.url.encodedPath == "$PROJECTS/$projectId/next-step" }

    @Test
    fun `a stalled project gets the task as its next step with If-Match, then the projects reload`() =
        runBlocking {
            dispatcher.enqueue("$PROJECTS/$projectId", stalledKitchen())
            dispatcher.enqueue("$PROJECTS/$projectId/next-step", json(projectJson(50, "Kitchen")))
            dispatcher.enqueue(PROJECTS, json(taskPage(projectJson(50, "Kitchen"))))

            repository.makeNextStepIfStalled(projectId, taskId)

            val call = nextStepCalls().single()
            assertEquals("POST", call.method)
            assertEquals("\"2\"", call.headers["If-Match"])
            assertEquals(JsonPrimitive(taskId.toString()), Json.parseToJsonElement(call.body!!.utf8()).jsonObject["taskId"])
            assertEquals(1, projectLoads())
        }

    @Test
    fun `a project that is not stalled keeps its next step`() =
        runBlocking {
            dispatcher.enqueue("$PROJECTS/$projectId", json(projectJson(50, "Kitchen")))

            repository.makeNextStepIfStalled(projectId, taskId)

            assertTrue(nextStepCalls().isEmpty())
            assertEquals(0, projectLoads())
        }

    @Test
    fun `failures are ignored and reload nothing`() =
        runBlocking {
            // Unreachable while reading the project.
            dispatcher.enqueue("$PROJECTS/$projectId", status(503))
            repository.makeNextStepIfStalled(projectId, taskId)
            assertTrue(nextStepCalls().isEmpty())

            // Changed elsewhere while setting the next step.
            dispatcher.enqueue("$PROJECTS/$projectId", stalledKitchen())
            dispatcher.enqueue("$PROJECTS/$projectId/next-step", status(412))
            repository.makeNextStepIfStalled(projectId, taskId)
            assertEquals(1, nextStepCalls().size)

            // An unreadable answer.
            dispatcher.enqueue("$PROJECTS/$projectId", json("""{"id":"oops"}"""))
            repository.makeNextStepIfStalled(projectId, taskId)

            assertEquals(1, nextStepCalls().size)
            assertEquals(0, projectLoads())
        }

    @Test
    fun `no connection at all is ignored too`() =
        runBlocking {
            server.close()

            repository.makeNextStepIfStalled(projectId, taskId)

            assertTrue(nextStepCalls().isEmpty())
        }
}
