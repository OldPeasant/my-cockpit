package ch.sonensei.cockpit.gtd.projects

import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.core.network.generated.apis.ProjectsApi
import ch.sonensei.cockpit.core.network.generated.apis.TasksApi
import ch.sonensei.cockpit.core.network.generated.infrastructure.Serializer
import ch.sonensei.cockpit.gtd.data.GtdHarness
import ch.sonensei.cockpit.gtd.data.GtdProject
import ch.sonensei.cockpit.gtd.data.GtdTask
import ch.sonensei.cockpit.gtd.data.PROJECTS
import ch.sonensei.cockpit.gtd.data.ProjectStatus
import ch.sonensei.cockpit.gtd.data.RoutingDispatcher
import ch.sonensei.cockpit.gtd.data.TASKS
import ch.sonensei.cockpit.gtd.data.TaskList
import ch.sonensei.cockpit.gtd.data.json
import ch.sonensei.cockpit.gtd.data.projectJson
import ch.sonensei.cockpit.gtd.data.status
import ch.sonensei.cockpit.gtd.data.taskJson
import ch.sonensei.cockpit.gtd.data.taskPage
import ch.sonensei.cockpit.gtd.data.uuid
import ch.sonensei.cockpit.gtd.lists.ActionOutcome
import ch.sonensei.cockpit.gtd.lists.Live
import ch.sonensei.cockpit.gtd.lists.LoadError
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
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

/** The project detail's changes against a fake server: what is sent, how answers map to outcomes, which caches reload. */
@RunWith(AndroidJUnit4::class)
class ProjectWorkRepositoryTest {
    private val dispatcher = RoutingDispatcher()
    private val server = MockWebServer().apply { this.dispatcher = this@ProjectWorkRepositoryTest.dispatcher }
    private lateinit var harness: GtdHarness
    private lateinit var repository: ProjectWorkRepository

    private val project =
        GtdProject(UUID.fromString(uuid(10)), "Plan holiday", ProjectStatus.ACTIVE, stalled = false, version = 1)
    private val book = GtdTask(UUID.fromString(uuid(1)), "Book the flights", TaskList.NEXT, 0, 4, projectId = project.id)

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
            ProjectWorkRepository(
                retrofit.create(ProjectsApi::class.java),
                retrofit.create(TasksApi::class.java),
                harness.connectivity,
                harness.triggers,
                harness.projects,
                harness.tasks,
            )
    }

    @After
    fun tearDown() {
        harness.close()
        server.close()
    }

    private fun requests(
        method: String,
        path: String,
    ): List<RecordedRequest> = dispatcher.requests.filter { it.method == method && it.url.encodedPath == path }

    private fun completed(needsNextStep: Boolean) = json(taskJson(1, "Book the flights", "NEXT", ""","needsNextStep":$needsNextStep"""))

    private fun enqueueCacheReloads() {
        dispatcher.enqueue(PROJECTS, json(taskPage(projectJson(10, "Plan holiday"))))
        dispatcher.enqueue("$TASKS|NEXT", json(taskPage()))
        dispatcher.enqueue("$TASKS|overdue", json(taskPage()))
        dispatcher.enqueue("$TASKS|today", json(taskPage()))
    }

    @Test
    fun `completing the next step reports that the project needs one, with the task's version, and reloads the caches`() =
        runBlocking {
            dispatcher.enqueue("$TASKS/${uuid(1)}/complete", completed(needsNextStep = true))
            enqueueCacheReloads()
            assertEquals(ProjectCompletion.Completed(needsNextStep = true), repository.complete(book))
            assertEquals("\"4\"", requests("POST", "$TASKS/${uuid(1)}/complete").single().headers["If-Match"])
            assertEquals(1, requests("GET", PROJECTS).size)
            val views = requests("GET", TASKS).map { it.url.queryParameter("list") ?: it.url.queryParameter("due") }
            assertEquals(setOf("NEXT", "OVERDUE", "TODAY"), views.toSet())
            assertEquals(
                listOf("Plan holiday"),
                harness.dao
                    .projectRows()
                    .rows
                    .map { it.title },
            )
        }

    @Test
    fun `completing another task does not ask for a next step`() =
        runBlocking {
            dispatcher.enqueue("$TASKS/${uuid(1)}/complete", completed(needsNextStep = false))
            enqueueCacheReloads()
            assertEquals(ProjectCompletion.Completed(needsNextStep = false), repository.complete(book))
        }

    @Test
    fun `a completion refused because the task changed elsewhere is a conflict, and the caches still reload`() =
        runBlocking {
            dispatcher.enqueue("$TASKS/${uuid(1)}/complete", status(412))
            enqueueCacheReloads()
            assertEquals(ProjectCompletion.NotDone(ActionOutcome.CONFLICT), repository.complete(book))
            assertEquals(1, requests("GET", PROJECTS).size)
        }

    @Test
    fun `a completion while the server is unreachable is offline and reloads nothing`() =
        runBlocking {
            dispatcher.enqueue("$TASKS/${uuid(1)}/complete", status(503))
            assertEquals(ProjectCompletion.NotDone(ActionOutcome.OFFLINE), repository.complete(book))
            assertTrue(requests("GET", PROJECTS).isEmpty())
            assertTrue(requests("GET", TASKS).isEmpty())
        }

    @Test
    fun `a bad request on completion is a failure, not a conflict`() =
        runBlocking {
            dispatcher.enqueue("$TASKS/${uuid(1)}/complete", status(400))
            enqueueCacheReloads()
            assertEquals(ProjectCompletion.NotDone(ActionOutcome.FAILED), repository.complete(book))
        }

    @Test
    fun `setting the next step sends the task id with the project's version and reloads only the projects`() =
        runBlocking {
            dispatcher.enqueue("$PROJECTS/${uuid(10)}/next-step", json(projectJson(10, "Plan holiday")))
            enqueueCacheReloads()
            assertEquals(ActionOutcome.DONE, repository.setNextStep(project, book.id))
            val request = requests("POST", "$PROJECTS/${uuid(10)}/next-step").single()
            assertEquals("\"1\"", request.headers["If-Match"])
            assertTrue(request.body!!.utf8().contains("\"taskId\":\"${uuid(1)}\""))
            assertEquals(1, requests("GET", PROJECTS).size)
            assertTrue(requests("GET", TASKS).isEmpty())
        }

    @Test
    fun `setting the next step on a project changed elsewhere is a conflict`() =
        runBlocking {
            dispatcher.enqueue("$PROJECTS/${uuid(10)}/next-step", status(412))
            enqueueCacheReloads()
            assertEquals(ActionOutcome.CONFLICT, repository.setNextStep(project, book.id))
        }

    @Test
    fun `setting the next step offline changes and reloads nothing`() =
        runBlocking {
            dispatcher.enqueue("$PROJECTS/${uuid(10)}/next-step", status(502))
            assertEquals(ActionOutcome.OFFLINE, repository.setNextStep(project, book.id))
            assertTrue(requests("GET", PROJECTS).isEmpty())
        }

    @Test
    fun `adding a next step creates a next action in the project and sets it on the project's current version`() =
        runBlocking {
            dispatcher.enqueue(TASKS, json(taskJson(7, "Pack the bags", "NEXT")))
            // The fake server's project is at version 2, newer than the version 1 the screen had.
            dispatcher.enqueue("$PROJECTS/${uuid(10)}", json(projectJson(10, "Plan holiday")))
            dispatcher.enqueue("$PROJECTS/${uuid(10)}/next-step", json(projectJson(10, "Plan holiday")))
            enqueueCacheReloads()
            assertEquals(NextStepAdded(ActionOutcome.DONE, UUID.fromString(uuid(7))), repository.addNextStep(project, "Pack the bags"))

            // Create, then re-read the project's version, then set the next step.
            val order = listOf("POST $TASKS", "GET $PROJECTS/${uuid(10)}", "POST $PROJECTS/${uuid(10)}/next-step")
            assertEquals(order, dispatcher.requests.map { "${it.method} ${it.url.encodedPath}" }.filter { it in order })
            val create = requests("POST", TASKS).single().body!!.utf8()
            assertTrue(create.contains("\"title\":\"Pack the bags\""))
            assertTrue(create.contains("\"list\":\"NEXT\""))
            assertTrue(create.contains("\"projectId\":\"${uuid(10)}\""))
            assertEquals(1, requests("GET", "$PROJECTS/${uuid(10)}").size)
            val next = requests("POST", "$PROJECTS/${uuid(10)}/next-step").single()
            assertEquals("\"2\"", next.headers["If-Match"])
            assertTrue(next.body!!.utf8().contains("\"taskId\":\"${uuid(7)}\""))
            val views = requests("GET", TASKS).map { it.url.queryParameter("list") ?: it.url.queryParameter("due") }
            assertEquals(setOf("NEXT", "OVERDUE", "TODAY"), views.toSet())
        }

    @Test
    fun `a next step that could not be created is not set on the project`() =
        runBlocking {
            dispatcher.enqueue(TASKS, status(400))
            enqueueCacheReloads()
            assertEquals(NextStepAdded(ActionOutcome.FAILED, null), repository.addNextStep(project, "Pack the bags"))
            assertTrue(requests("GET", "$PROJECTS/${uuid(10)}").isEmpty())
            assertTrue(requests("POST", "$PROJECTS/${uuid(10)}/next-step").isEmpty())
        }

    @Test
    fun `adding a next step to a project trashed meanwhile is a conflict`() =
        runBlocking {
            dispatcher.enqueue(TASKS, json(taskJson(7, "Pack the bags", "NEXT")))
            dispatcher.enqueue("$PROJECTS/${uuid(10)}", status(404))
            enqueueCacheReloads()
            assertEquals(NextStepAdded(ActionOutcome.CONFLICT, UUID.fromString(uuid(7))), repository.addNextStep(project, "Pack the bags"))
            assertTrue(requests("POST", "$PROJECTS/${uuid(10)}/next-step").isEmpty())
        }

    @Test
    fun `a created next step that could not be set reports the task, and a retry with it only sets the next step`() =
        runBlocking {
            dispatcher.enqueue(TASKS, json(taskJson(7, "Pack the bags", "NEXT")))
            dispatcher.enqueue("$PROJECTS/${uuid(10)}", json(projectJson(10, "Plan holiday")))
            dispatcher.enqueue("$PROJECTS/${uuid(10)}/next-step", status(412))
            enqueueCacheReloads()
            val first = repository.addNextStep(project, "Pack the bags")
            assertEquals(NextStepAdded(ActionOutcome.CONFLICT, UUID.fromString(uuid(7))), first)

            dispatcher.requests.clear()
            dispatcher.enqueue("$PROJECTS/${uuid(10)}", json(projectJson(10, "Plan holiday")))
            dispatcher.enqueue("$PROJECTS/${uuid(10)}/next-step", json(projectJson(10, "Plan holiday")))
            enqueueCacheReloads()
            assertEquals(
                NextStepAdded(ActionOutcome.DONE, UUID.fromString(uuid(7))),
                repository.addNextStep(project, "Pack the bags", first.taskId),
            )
            assertTrue(requests("POST", TASKS).isEmpty())
            val next = requests("POST", "$PROJECTS/${uuid(10)}/next-step").single()
            assertEquals("\"2\"", next.headers["If-Match"])
            assertTrue(next.body!!.utf8().contains("\"taskId\":\"${uuid(7)}\""))
        }

    @Test
    fun `a next step refused with 409 is a conflict`() =
        runBlocking {
            dispatcher.enqueue("$PROJECTS/${uuid(10)}/next-step", status(409))
            enqueueCacheReloads()
            assertEquals(ActionOutcome.CONFLICT, repository.setNextStep(project, book.id))
        }

    @Test
    fun `a completion of a task trashed elsewhere (404) is a conflict`() =
        runBlocking {
            dispatcher.enqueue("$TASKS/${uuid(1)}/complete", status(404))
            enqueueCacheReloads()
            assertEquals(ProjectCompletion.NotDone(ActionOutcome.CONFLICT), repository.complete(book))
        }

    @Test
    fun `areas load in their position order`() =
        runBlocking {
            dispatcher.enqueue(
                "/api/v1/areas",
                json(
                    """{"items":[{"id":"${uuid(90)}","title":"Home","position":1,"version":1},""" +
                        """{"id":"${uuid(91)}","title":"Work","position":0,"version":1}]}""",
                ),
            )
            val live = withTimeout(TIMEOUT) { repository.observeAreas(MutableSharedFlow()).first { it.data != null } }
            assertEquals(
                listOf(Area(UUID.fromString(uuid(91)), "Work", 0), Area(UUID.fromString(uuid(90)), "Home", 1)),
                live.data,
            )
        }

    @Test
    fun `areas that cannot load are an error, not loading forever`() =
        runBlocking {
            dispatcher.enqueue("/api/v1/areas", status(500))
            val live = withTimeout(TIMEOUT) { repository.observeAreas(MutableSharedFlow()).first { !it.loading } }
            assertEquals(Live<List<Area>>(error = LoadError.OFFLINE), live)
        }

    private companion object {
        const val TIMEOUT = 10_000L
    }
}
