package ch.sonensei.cockpit.gtd.inbox

import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.core.network.generated.apis.ProjectsApi
import ch.sonensei.cockpit.core.network.generated.apis.TasksApi
import ch.sonensei.cockpit.core.network.generated.infrastructure.Serializer
import ch.sonensei.cockpit.gtd.data.Energy
import ch.sonensei.cockpit.gtd.data.GtdHarness
import ch.sonensei.cockpit.gtd.data.GtdTask
import ch.sonensei.cockpit.gtd.data.PROJECTS
import ch.sonensei.cockpit.gtd.data.RoutingDispatcher
import ch.sonensei.cockpit.gtd.data.TASKS
import ch.sonensei.cockpit.gtd.data.TaskList
import ch.sonensei.cockpit.gtd.data.json
import ch.sonensei.cockpit.gtd.data.projectJson
import ch.sonensei.cockpit.gtd.data.status
import ch.sonensei.cockpit.gtd.data.taskJson
import ch.sonensei.cockpit.gtd.data.taskPage
import ch.sonensei.cockpit.gtd.data.uuid
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.MediaType.Companion.toMediaType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.time.LocalDate
import java.util.UUID

/** Each processing action against a fake server: the calls it makes, their If-Match, and what it reports. */
@RunWith(AndroidJUnit4::class)
class InboxRepositoryTest {
    private val dispatcher = RoutingDispatcher()
    private val server = MockWebServer().apply { this.dispatcher = this@InboxRepositoryTest.dispatcher }
    private lateinit var gtd: GtdHarness
    private lateinit var inbox: InboxRepository

    private val id = UUID.fromString(uuid(1))
    private val path = "$TASKS/$id"
    private val task = GtdTask(id = id, title = "Call the plumber", list = TaskList.INBOX, position = 1, version = 3)

    @Before
    fun setUp() {
        server.start()
        gtd = GtdHarness(server, dispatcher)
        val retrofit =
            Retrofit
                .Builder()
                .baseUrl(server.url("/"))
                .addConverterFactory(Serializer.kotlinxSerializationJson.asConverterFactory("application/json".toMediaType()))
                .build()
        inbox =
            InboxRepository(
                retrofit.create(TasksApi::class.java),
                retrofit.create(InboxTaskPatchApi::class.java),
                retrofit.create(ProjectsApi::class.java),
                gtd.tasks,
                gtd.connectivity,
            )
        // The reload after an action.
        repeat(3) { dispatcher.enqueue("$TASKS|INBOX", json(taskPage())) }
    }

    @After
    fun tearDown() {
        gtd.close()
        server.close()
    }

    private fun taskAnswer(
        version: Int = 4,
        list: String = "NEXT",
    ) = json(taskJson(1, "Call the plumber", list).replace("\"version\":1", "\"version\":$version"))

    private fun calls(): List<RecordedRequest> = dispatcher.requests.filter { it.url.encodedPath != "/api/v1/events" }

    private fun call(
        method: String,
        path: String,
    ): RecordedRequest = calls().single { it.method == method && it.url.encodedPath == path }

    private fun RecordedRequest.json(): JsonObject = Json.parseToJsonElement(body!!.utf8()).jsonObject

    private fun process(
        action: InboxAction,
        clarification: Clarification = Clarification.of(task),
    ) = runBlocking { inbox.process(task, clarification, action) }

    @Test
    fun `do it completes the task and reloads the inbox`() {
        dispatcher.enqueue("$path/complete", json(taskJson(1, "Call the plumber", extra = ""","needsNextStep":false""")))

        assertEquals(ProcessResult.Processed, process(InboxAction.Done))

        assertEquals("\"3\"", call("POST", "$path/complete").headers["If-Match"])
        assertTrue(calls().any { it.url.queryParameter("list") == "INBOX" })
    }

    @Test
    fun `next, someday, waiting, schedule and tickler move the task`() {
        val cases =
            listOf(
                InboxAction.Next to """{"list":"NEXT"}""",
                InboxAction.Someday to """{"list":"SOMEDAY"}""",
                InboxAction.Waiting(" Alex ") to """{"list":"WAITING","waitingForText":"Alex"}""",
                InboxAction.Schedule(LocalDate.of(2026, 10, 30)) to """{"list":"SCHEDULED","scheduledDate":"2026-10-30"}""",
                InboxAction.Tickler(LocalDate.of(2026, 11, 2)) to """{"list":"INBOX","ticklerDate":"2026-11-02"}""",
            )
        cases.forEach { (action, expected) ->
            dispatcher.requests.clear()
            dispatcher.enqueue("$path/move", taskAnswer())
            dispatcher.enqueue("$TASKS|INBOX", json(taskPage()))

            assertEquals(action.toString(), ProcessResult.Processed, process(action))

            val move = call("POST", "$path/move")
            assertEquals("\"3\"", move.headers["If-Match"])
            val sent = move.json().filterValues { it.toString() != "null" }
            assertEquals(action.toString(), Json.parseToJsonElement(expected), JsonObject(sent))
        }
    }

    @Test
    fun `waiting without a name sends no waiting-for text`() {
        dispatcher.enqueue("$path/move", taskAnswer())

        process(InboxAction.Waiting("  "))

        assertEquals("null", call("POST", "$path/move").json()["waitingForText"].toString())
    }

    @Test
    fun `trash deletes the task without saving the clarification`() {
        dispatcher.enqueue(path, status(204))

        assertEquals(ProcessResult.Processed, process(InboxAction.Trash, Clarification(energy = Energy.HIGH)))

        assertEquals("\"3\"", call("DELETE", path).headers["If-Match"])
        assertFalse(calls().any { it.method == "PATCH" })
    }

    @Test
    fun `a changed clarification is patched first, alone, and the action uses the new version`() {
        val context = UUID.fromString(uuid(80))
        dispatcher.enqueue(path, taskAnswer(version = 4, list = "INBOX"))
        dispatcher.enqueue("$path/move", taskAnswer(version = 5))

        val result = process(InboxAction.Next, Clarification(energy = Energy.LOW, timeEstimateMin = 15, contextIds = listOf(context)))

        assertEquals(ProcessResult.Processed, result)
        val patch = call("PATCH", path)
        assertEquals("\"3\"", patch.headers["If-Match"])
        assertEquals(
            Json.parseToJsonElement("""{"energy":"LOW","timeEstimateMin":15,"contextIds":["$context"]}"""),
            patch.json(),
        )
        assertEquals("\"4\"", call("POST", "$path/move").headers["If-Match"])
    }

    @Test
    fun `clearing an attribute sends null for it`() {
        val clarified = task.copy(energy = Energy.HIGH)
        dispatcher.enqueue(path, taskAnswer(version = 4, list = "INBOX"))
        dispatcher.enqueue("$path/move", taskAnswer(version = 5))

        runBlocking { inbox.process(clarified, Clarification(), InboxAction.Next) }

        assertEquals(Json.parseToJsonElement("""{"energy":null}"""), call("PATCH", path).json())
    }

    @Test
    fun `to an existing project moves it there and makes it the next step of a stalled project`() {
        val project = UUID.fromString(uuid(50))
        dispatcher.enqueue("$path/move", taskAnswer())
        dispatcher.enqueue("$PROJECTS/$project", json(projectJson(50, "Kitchen").replace("\"stalled\":false", "\"stalled\":true")))
        dispatcher.enqueue("$PROJECTS/$project/next-step", json(projectJson(50, "Kitchen")))

        assertEquals(ProcessResult.Processed, process(InboxAction.ToProject(ProjectChoice.Existing(project))))

        val move = call("POST", "$path/move").json()
        assertEquals("\"NEXT\"", move["list"].toString())
        assertEquals("\"$project\"", move["projectId"].toString())
        val nextStep = call("POST", "$PROJECTS/$project/next-step")
        assertEquals("\"2\"", nextStep.headers["If-Match"])
        assertEquals("\"$id\"", nextStep.json()["taskId"].toString())
    }

    @Test
    fun `to a new project creates it once, also when the move is retried`() {
        val project = UUID.fromString(uuid(50))
        dispatcher.enqueue(PROJECTS, json(projectJson(50, "Kitchen")))
        dispatcher.enqueue("$path/move", status(503))
        dispatcher.enqueue("$path/move", taskAnswer())
        dispatcher.enqueue("$PROJECTS/$project", json(projectJson(50, "Kitchen")))
        val action = InboxAction.ToProject(ProjectChoice.New(" Kitchen "))

        assertEquals(ProcessResult.Offline, process(action))
        assertEquals(ProcessResult.Processed, process(action))

        val creates = calls().filter { it.method == "POST" && it.url.encodedPath == PROJECTS }
        assertEquals(1, creates.size)
        assertEquals("\"Kitchen\"", creates.single().json()["title"].toString())
        // Not stalled any more: the next step is left alone.
        assertFalse(calls().any { it.url.encodedPath.endsWith("/next-step") })
    }

    @Test
    fun `a failed next step still counts as processed`() {
        val project = UUID.fromString(uuid(50))
        dispatcher.enqueue("$path/move", taskAnswer())
        dispatcher.enqueue("$PROJECTS/$project", status(500))

        assertEquals(ProcessResult.Processed, process(InboxAction.ToProject(ProjectChoice.Existing(project))))
    }

    @Test
    fun `an unreachable server reports offline and marks the views offline`() {
        dispatcher.enqueue("$path/move", status(502))

        assertEquals(ProcessResult.Offline, process(InboxAction.Next))
        assertTrue(gtd.connectivity.offline.value)
    }

    @Test
    fun `a stale version reports a conflict and reloads the inbox`() {
        dispatcher.enqueue("$path/move", status(412))

        assertEquals(ProcessResult.Conflict, process(InboxAction.Next))
        assertTrue(calls().any { it.url.queryParameter("list") == "INBOX" })
    }

    @Test
    fun `a refused action reports its status`() {
        dispatcher.enqueue("$path/move", status(400))

        assertEquals(ProcessResult.Rejected(400), process(InboxAction.Next))
    }

    @Test
    fun `a retry after a saved clarification uses the saved version instead of patching again`() {
        val saved =
            taskJson(1, "Call the plumber", extra = ""","energy":"LOW"""").replace("\"version\":1", "\"version\":4")
        dispatcher.enqueue(path, json(saved))
        dispatcher.enqueue("$path/move", status(503))
        dispatcher.enqueue("$path/move", taskAnswer(version = 5))
        val clarification = Clarification(energy = Energy.LOW)

        assertEquals(ProcessResult.Offline, process(InboxAction.Next, clarification))
        // The cached task is still version 3 without energy.
        assertEquals(ProcessResult.Processed, process(InboxAction.Next, clarification))

        assertEquals(1, calls().count { it.method == "PATCH" })
        val moves = calls().filter { it.url.encodedPath == "$path/move" }
        assertEquals(listOf("\"4\"", "\"4\""), moves.map { it.headers["If-Match"] })
    }

    @Test
    fun `an unchanged clarification is not patched`() {
        dispatcher.enqueue("$path/move", taskAnswer())

        assertEquals(ProcessResult.Processed, process(InboxAction.Next))

        assertFalse(calls().any { it.method == "PATCH" })
    }

    @Test
    fun `an item trashed or changed elsewhere reports a conflict`() {
        listOf(404, 409).forEach { code ->
            dispatcher.requests.clear()
            dispatcher.enqueue("$path/complete", status(code))
            dispatcher.enqueue("$TASKS|INBOX", json(taskPage()))

            assertEquals("status $code", ProcessResult.Conflict, process(InboxAction.Done))
            assertTrue(calls().any { it.url.queryParameter("list") == "INBOX" })
        }
    }

    @Test
    fun `offline does not reload the inbox, so the cached list stays`() {
        dispatcher.enqueue("$path/move", status(503))

        assertEquals(ProcessResult.Offline, process(InboxAction.Someday))

        assertFalse(calls().any { it.url.queryParameter("list") == "INBOX" })
    }

    @Test
    fun `a refused clarification stops before the action`() {
        dispatcher.enqueue(path, status(412))

        assertEquals(ProcessResult.Conflict, process(InboxAction.Next, Clarification(energy = Energy.HIGH)))

        assertFalse(calls().any { it.url.encodedPath == "$path/move" })
    }

    @Test
    fun `a malformed answer is reported as refused without a status`() {
        dispatcher.enqueue("$path/move", json("""{"unexpected":true}"""))

        assertEquals(ProcessResult.Rejected(null), process(InboxAction.Next))
    }

    @Test
    fun `trash after a saved clarification deletes the saved version`() {
        val saved =
            taskJson(1, "Call the plumber", extra = ""","energy":"LOW"""").replace("\"version\":1", "\"version\":4")
        dispatcher.enqueue(path, json(saved))
        dispatcher.enqueue("$path/move", status(503))
        dispatcher.enqueue(path, status(204))

        assertEquals(ProcessResult.Offline, process(InboxAction.Next, Clarification(energy = Energy.LOW)))
        assertEquals(ProcessResult.Processed, process(InboxAction.Trash, Clarification(energy = Energy.LOW)))

        assertEquals("\"4\"", call("DELETE", path).headers["If-Match"])
    }

    @Test
    fun `a newer cached version wins over a saved clarification`() {
        val saved =
            taskJson(1, "Call the plumber", extra = ""","energy":"LOW"""").replace("\"version\":1", "\"version\":4")
        dispatcher.enqueue(path, json(saved))
        dispatcher.enqueue("$path/move", status(503))
        dispatcher.enqueue(path, taskAnswer(version = 8, list = "INBOX"))
        dispatcher.enqueue("$path/move", taskAnswer(version = 9))

        assertEquals(ProcessResult.Offline, process(InboxAction.Next, Clarification(energy = Energy.LOW)))
        // Meanwhile the item was reloaded at version 7 (changed elsewhere, without energy).
        val reloaded = task.copy(version = 7)
        val result = runBlocking { inbox.process(reloaded, Clarification(energy = Energy.LOW), InboxAction.Next) }

        assertEquals(ProcessResult.Processed, result)
        val patches = calls().filter { it.method == "PATCH" }
        assertEquals(listOf("\"3\"", "\"7\""), patches.map { it.headers["If-Match"] })
        assertEquals("\"8\"", calls().last { it.url.encodedPath == "$path/move" }.headers["If-Match"])
    }

    @Test
    fun `a project that already has a next step is left alone`() {
        val project = UUID.fromString(uuid(50))
        dispatcher.enqueue("$path/move", taskAnswer())
        dispatcher.enqueue("$PROJECTS/$project", json(projectJson(50, "Kitchen")))

        assertEquals(ProcessResult.Processed, process(InboxAction.ToProject(ProjectChoice.Existing(project))))

        assertFalse(calls().any { it.url.encodedPath.endsWith("/next-step") })
    }

    @Test
    fun `a refused next step still counts as processed`() {
        val project = UUID.fromString(uuid(50))
        dispatcher.enqueue("$path/move", taskAnswer())
        dispatcher.enqueue("$PROJECTS/$project", json(projectJson(50, "Kitchen").replace("\"stalled\":false", "\"stalled\":true")))
        dispatcher.enqueue("$PROJECTS/$project/next-step", status(412))

        assertEquals(ProcessResult.Processed, process(InboxAction.ToProject(ProjectChoice.Existing(project))))
        assertFalse(gtd.connectivity.offline.value)
    }

    @Test
    fun `a project that cannot be created leaves the item where it is`() {
        dispatcher.enqueue(PROJECTS, status(400))

        assertEquals(ProcessResult.Rejected(400), process(InboxAction.ToProject(ProjectChoice.New("Kitchen"))))

        assertFalse(calls().any { it.url.encodedPath == "$path/move" })
    }
}
