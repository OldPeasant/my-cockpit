package ch.sonensei.cockpit.gtd.task

import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.core.network.generated.apis.CalendarsApi
import ch.sonensei.cockpit.core.network.generated.apis.ProjectsApi
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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.MediaType.Companion.toMediaType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** The detail screen's requests against a fake server: paths, `If-Match`, bodies, and how answers are read. */
@RunWith(AndroidJUnit4::class)
class TaskDetailRepositoryTest {
    private val dispatcher = RoutingDispatcher()
    private val server = MockWebServer().apply { this.dispatcher = this@TaskDetailRepositoryTest.dispatcher }
    private lateinit var harness: GtdHarness
    private lateinit var repository: TaskDetailRepository

    private val taskId: UUID = UUID.fromString(uuid(1))
    private val task = GtdTask(taskId, "Buy seeds", TaskList.NEXT, 1, version = 3, projectId = UUID.fromString(uuid(20)))
    private val item = ChecklistItem(UUID.fromString(uuid(40)), "Tomatoes", done = false, position = 0, version = 5)

    private val taskPath = "$TASKS/${uuid(1)}"
    private val checklistPath = "$taskPath/checklist"
    private val linksPath = "/api/v1/links"

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
        TaskSnapshots.clear()
        harness.close()
        server.close()
    }

    private fun request(
        method: String,
        path: String,
    ): RecordedRequest = dispatcher.requests.single { it.method == method && it.url.encodedPath == path }

    private fun RecordedRequest.bodyJson(): JsonObject = Json.parseToJsonElement(body!!.utf8()).jsonObject

    private fun problemResponse(
        code: Int,
        body: String,
    ): MockResponse =
        MockResponse
            .Builder()
            .code(code)
            .addHeader("Content-Type", "application/problem+json")
            .body(body)
            .build()

    private fun itemJson(
        n: Int,
        text: String,
        position: Int,
        done: Boolean = false,
        version: Int = 1,
    ) = """{"id":"${uuid(n)}","taskId":"${uuid(1)}","text":"$text","done":$done,"position":$position,"version":$version}"""

    // --- load ---

    @Test
    fun `loads the task with its checklist in order, without its links (the linked-items panel loads them)`() =
        runBlocking {
            val extra = ""","completedAt":"2026-10-19T08:00:00Z","ticklerDate":"2026-12-01","recurrenceRule":"FREQ=WEEKLY;BYDAY=SA""""
            dispatcher.enqueue(taskPath, json(taskJson(1, "Buy seeds", "SOMEDAY", extra = extra)))
            val items = listOf(itemJson(41, "Basil", 1), itemJson(40, "Tomatoes", 0, done = true))
            dispatcher.enqueue(checklistPath, json("""{"items":[${items.joinToString(",")}]}"""))

            val detail = (repository.load(taskId) as TaskResult.Ok).value

            assertEquals("Buy seeds", detail.task.title)
            assertEquals(TaskList.SOMEDAY, detail.task.list)
            assertEquals(Instant.parse("2026-10-19T08:00:00Z"), detail.completedAt)
            assertEquals(true, detail.completed)
            assertEquals(LocalDate.of(2026, 12, 1), detail.ticklerDate)
            assertEquals("FREQ=WEEKLY;BYDAY=SA", detail.recurrenceRule)
            assertEquals(listOf("Tomatoes", "Basil"), detail.checklist.map { it.text })
            assertEquals(true, detail.checklist.first().done)
            assertTrue(dispatcher.requests.none { it.url.encodedPath == linksPath })
        }

    @Test
    fun `a task gone or trashed elsewhere is not found`() =
        runBlocking {
            dispatcher.enqueue(taskPath, status(404))
            assertEquals(TaskResult.NotFound, repository.load(taskId))
            dispatcher.enqueue(taskPath, status(410))
            assertEquals(TaskResult.NotFound, repository.load(taskId))
        }

    @Test
    fun `an unreachable server is offline for the screen and the lists`() =
        runBlocking {
            dispatcher.enqueue(taskPath, status(503))
            dispatcher.enqueue(checklistPath, status(503))

            assertEquals(TaskResult.Offline, repository.load(taskId))
            assertEquals(true, harness.connectivity.offline.value)
        }

    @Test
    fun `a checklist that cannot be loaded fails the load`() =
        runBlocking {
            dispatcher.enqueue(taskPath, json(taskJson(1, "Buy seeds", "NEXT")))
            dispatcher.enqueue(checklistPath, status(500))

            assertEquals(TaskResult.Offline, repository.load(taskId))
        }

    // --- update ---

    @Test
    fun `update patches only the given fields, with null to clear, and the loaded version as If-Match`() =
        runBlocking {
            dispatcher.enqueue(taskPath, json(taskJson(1, "Buy seeds and soil", "NEXT").replace("\"version\":1", "\"version\":4")))
            val patch = JsonObject(mapOf("title" to JsonPrimitive("Buy seeds and soil"), "dueDate" to JsonNull))

            val updated = (repository.update(task, patch) as TaskResult.Ok).value

            assertEquals("Buy seeds and soil", updated.title)
            assertEquals(4L, updated.version)
            val sent = request("PATCH", taskPath)
            assertEquals("\"3\"", sent.headers["If-Match"])
            assertEquals(patch, sent.bodyJson())
        }

    @Test
    fun `a 412 or 409 is a conflict`() =
        runBlocking {
            dispatcher.enqueue(taskPath, status(412))
            assertEquals(TaskResult.Conflict, repository.update(task, JsonObject(mapOf("title" to JsonPrimitive("Mine")))))
            dispatcher.enqueue(taskPath, status(409))
            assertEquals(TaskResult.Conflict, repository.update(task, JsonObject(mapOf("title" to JsonPrimitive("Mine")))))
        }

    @Test
    fun `a 422 or 400 with problem details gives the field messages and the detail`() =
        runBlocking {
            dispatcher.enqueue(
                taskPath,
                problemResponse(
                    422,
                    """{"type":"about:blank","title":"Unprocessable","status":422,"detail":"Invalid task",""" +
                        """"errors":[{"field":"timeEstimateMin","message":"must be at most 10000"}]}""",
                ),
            )
            assertEquals(
                TaskResult.Invalid(mapOf("timeEstimateMin" to "must be at most 10000"), "Invalid task"),
                repository.update(task, JsonObject(mapOf("timeEstimateMin" to JsonPrimitive(20000)))),
            )
            dispatcher.enqueue(
                taskPath,
                problemResponse(400, """{"status":400,"detail":"Malformed","errors":[{"field":"dueTime","message":"needs a due date"}]}"""),
            )
            assertEquals(
                TaskResult.Invalid(mapOf("dueTime" to "needs a due date"), "Malformed"),
                repository.update(task, JsonObject(mapOf("dueTime" to JsonPrimitive("08:00:00")))),
            )
        }

    @Test
    fun `a 400 without a body is invalid without messages`() =
        runBlocking {
            dispatcher.enqueue(taskPath, status(400))
            assertEquals(TaskResult.Invalid(emptyMap(), null), repository.update(task, JsonObject(mapOf("title" to JsonPrimitive("x")))))
        }

    @Test
    fun `other refusals fail with their status, server errors are offline, an unreadable answer fails`() =
        runBlocking {
            val patch = JsonObject(mapOf("title" to JsonPrimitive("x")))
            dispatcher.enqueue(taskPath, status(403))
            assertEquals(TaskResult.Failed(403), repository.update(task, patch))
            dispatcher.enqueue(taskPath, status(502))
            assertEquals(TaskResult.Offline, repository.update(task, patch))
            dispatcher.enqueue(taskPath, json("""{"unexpected":true}"""))
            assertEquals(TaskResult.Failed(null), repository.update(task, patch))
        }

    // --- actions ---

    @Test
    fun `complete and reopen send the version as If-Match`() =
        runBlocking {
            dispatcher.enqueue(
                "$taskPath/complete",
                json(taskJson(1, "Buy seeds", "NEXT", extra = ""","needsNextStep":false,"completedAt":"2026-10-20T10:00:00Z"""")),
            )
            dispatcher.enqueue("$taskPath/uncomplete", json(taskJson(1, "Buy seeds", "NEXT")))

            assertEquals(TaskResult.Ok(Unit), repository.complete(task))
            assertEquals(TaskResult.Ok(Unit), repository.reopen(task.copy(version = 4)))

            assertEquals("\"3\"", request("POST", "$taskPath/complete").headers["If-Match"])
            assertEquals("\"4\"", request("POST", "$taskPath/uncomplete").headers["If-Match"])
        }

    @Test
    fun `moving to Waiting sends the trimmed person and keeps the project`() =
        runBlocking {
            dispatcher.enqueue("$taskPath/move", json(taskJson(1, "Buy seeds", "WAITING", extra = ""","waitingForText":"Anna"""")))

            val moved = (repository.move(task, MoveTarget.WAITING, "  Anna ") as TaskResult.Ok).value

            assertEquals(TaskList.WAITING, moved.list)
            val sent = request("POST", "$taskPath/move")
            assertEquals("\"3\"", sent.headers["If-Match"])
            val body = sent.bodyJson()
            assertEquals(JsonPrimitive("WAITING"), body["list"])
            assertEquals(JsonPrimitive(uuid(20)), body["projectId"])
            assertEquals(JsonPrimitive("Anna"), body["waitingForText"])
        }

    @Test
    fun `moving elsewhere sends no person`() =
        runBlocking {
            dispatcher.enqueue("$taskPath/move", json(taskJson(1, "Buy seeds", "SOMEDAY")))

            repository.move(task, MoveTarget.SOMEDAY, "Anna")

            val body = request("POST", "$taskPath/move").bodyJson()
            assertEquals(JsonPrimitive("SOMEDAY"), body["list"])
            assertTrue(body["waitingForText"] == null || body["waitingForText"] == JsonNull)
        }

    private fun JsonObject.absent(key: String) = this[key] == null || this[key] == JsonNull

    @Test
    fun `relisting to Scheduled sends the list, project, scheduled date and tickler with If-Match`() =
        runBlocking {
            dispatcher.enqueue(
                "$taskPath/move",
                json(taskJson(1, "Buy seeds", "SCHEDULED", extra = ""","scheduledDate":"2026-11-05"""")),
            )

            val move = TaskMove(TaskList.SCHEDULED, LocalDate.of(2026, 11, 5), LocalDate.of(2026, 12, 1), waitingForText = "Anna")
            val moved = (repository.relist(task, move) as TaskResult.Ok).value

            assertEquals(TaskList.SCHEDULED, moved.list)
            assertEquals(LocalDate.of(2026, 11, 5), moved.scheduledDate)
            val sent = request("POST", "$taskPath/move")
            assertEquals("\"3\"", sent.headers["If-Match"])
            val body = sent.bodyJson()
            assertEquals(JsonPrimitive("SCHEDULED"), body["list"])
            assertEquals(JsonPrimitive(uuid(20)), body["projectId"])
            assertEquals(JsonPrimitive("2026-11-05"), body["scheduledDate"])
            assertEquals(JsonPrimitive("2026-12-01"), body["ticklerDate"])
            // A person is only sent for Waiting.
            assertTrue(body.absent("waitingForText"))
        }

    @Test
    fun `relisting to Waiting sends the trimmed person and no scheduled date`() =
        runBlocking {
            dispatcher.enqueue("$taskPath/move", json(taskJson(1, "Buy seeds", "WAITING", extra = ""","waitingForText":"Anna"""")))

            repository.relist(task, TaskMove(TaskList.WAITING, scheduledDate = LocalDate.of(2026, 11, 5), waitingForText = "  Anna "))

            val body = request("POST", "$taskPath/move").bodyJson()
            assertEquals(JsonPrimitive("WAITING"), body["list"])
            assertEquals(JsonPrimitive("Anna"), body["waitingForText"])
            assertTrue(body.absent("scheduledDate"))
            assertTrue(body.absent("ticklerDate"))
        }

    @Test
    fun `relisting with a blank person or to another list sends no person, a task without a project none`() =
        runBlocking {
            dispatcher.enqueue("$taskPath/move", json(taskJson(1, "Buy seeds", "WAITING")))
            repository.relist(task.copy(projectId = null), TaskMove(TaskList.WAITING, waitingForText = "   "))
            val body = request("POST", "$taskPath/move").bodyJson()
            assertTrue(body.absent("waitingForText"))
            assertTrue(body.absent("projectId"))
        }

    @Test
    fun `relisting to Inbox with a tickler keeps it, a refused relist is read like any refusal`() =
        runBlocking {
            dispatcher.enqueue("$taskPath/move", json(taskJson(1, "Buy seeds", "INBOX")))
            repository.relist(task, TaskMove(TaskList.INBOX, ticklerDate = LocalDate.of(2027, 1, 4)))
            val body = request("POST", "$taskPath/move").bodyJson()
            assertEquals(JsonPrimitive("INBOX"), body["list"])
            assertEquals(JsonPrimitive("2027-01-04"), body["ticklerDate"])

            dispatcher.enqueue("$taskPath/move", status(412))
            assertEquals(TaskResult.Conflict, repository.relist(task, TaskMove(TaskList.SOMEDAY)))
            dispatcher.enqueue(
                "$taskPath/move",
                problemResponse(
                    422,
                    """{"title":"Invalid","detail":"Invalid move","errors":[{"field":"ticklerDate","message":"must be in the future"}]}""",
                ),
            )
            val invalid = repository.relist(task, TaskMove(TaskList.SOMEDAY, ticklerDate = LocalDate.of(2020, 1, 1)))
            assertEquals(mapOf("ticklerDate" to "must be in the future"), (invalid as TaskResult.Invalid).fields)
        }

    @Test
    fun `the recurrence mode of a task is read with its rule`() =
        runBlocking {
            val extra = ""","recurrenceRule":"FREQ=DAILY","recurrenceMode":"AFTER_COMPLETION""""
            dispatcher.enqueue(taskPath, json(taskJson(1, "Water the plants", "NEXT", extra = extra)))
            dispatcher.enqueue(checklistPath, json("""{"items":[]}"""))

            val detail = (repository.load(taskId) as TaskResult.Ok).value

            assertEquals("FREQ=DAILY", detail.recurrenceRule)
            assertEquals(RecurrenceMode.AFTER_COMPLETION, detail.recurrenceMode)
        }

    @Test
    fun `delete moves the task to the Trash with If-Match, restore takes it out again`() =
        runBlocking {
            dispatcher.enqueue(taskPath, status(204))
            dispatcher.enqueue("/api/v1/trash/TASK/${uuid(1)}/restore", status(204))

            assertEquals(TaskResult.Ok(Unit), repository.delete(task))
            assertEquals(TaskResult.Ok(Unit), repository.restore(taskId))

            assertEquals("\"3\"", request("DELETE", taskPath).headers["If-Match"])
            assertNull(request("POST", "/api/v1/trash/TASK/${uuid(1)}/restore").headers["If-Match"])
        }

    @Test
    fun `a restore of a task purged meanwhile is not found`() =
        runBlocking {
            dispatcher.enqueue("/api/v1/trash/TASK/${uuid(1)}/restore", status(404))
            assertEquals(TaskResult.NotFound, repository.restore(taskId))
        }

    // --- checklist ---

    @Test
    fun `checklist items are added, changed with the item's version and deleted`() =
        runBlocking {
            dispatcher.enqueue(checklistPath, json(itemJson(41, "Basil", 1)))
            dispatcher.enqueue("$checklistPath/${uuid(40)}", json(itemJson(40, "Tomatoes", 0, done = true, version = 6)))
            dispatcher.enqueue("$checklistPath/${uuid(40)}", status(204))

            val added = (repository.addChecklistItem(taskId, "Basil") as TaskResult.Ok).value
            val done = JsonObject(mapOf("done" to JsonPrimitive(true)))
            val ticked = (repository.updateChecklistItem(taskId, item, done) as TaskResult.Ok).value
            val deleted = repository.deleteChecklistItem(taskId, item)

            assertEquals(ChecklistItem(UUID.fromString(uuid(41)), "Basil", false, 1, 1), added)
            assertEquals(ChecklistItem(item.id, "Tomatoes", true, 0, 6), ticked)
            assertEquals(TaskResult.Ok(Unit), deleted)
            assertEquals(JsonPrimitive("Basil"), request("POST", checklistPath).bodyJson()["text"])
            val patch = request("PATCH", "$checklistPath/${uuid(40)}")
            assertEquals("\"5\"", patch.headers["If-Match"])
            assertEquals(JsonObject(mapOf("done" to JsonPrimitive(true))), patch.bodyJson())
            assertEquals("\"5\"", request("DELETE", "$checklistPath/${uuid(40)}").headers["If-Match"])
        }

    @Test
    fun `a stale checklist item is a conflict`() =
        runBlocking {
            dispatcher.enqueue("$checklistPath/${uuid(40)}", status(412))
            assertEquals(
                TaskResult.Conflict,
                repository.updateChecklistItem(taskId, item, JsonObject(mapOf("text" to JsonPrimitive("Cherry tomatoes")))),
            )
        }

    // --- offline copy ---

    @Test
    fun `the offline copy is the opened list's snapshot first, else the cached view's row with its load time`() =
        runBlocking {
            assertNull(repository.cached(taskId))

            dispatcher.enqueue("$TASKS|NEXT", json(taskPage(taskJson(1, "Buy seeds", "NEXT"))))
            harness.tasks.refresh(TaskView.NEXT)
            val fromCache = repository.cached(taskId)
            assertEquals("Buy seeds", fromCache?.task?.title)
            assertEquals(harness.clock.instant(), fromCache?.loadedAt)

            TaskSnapshots.put(task.copy(title = "Buy seeds (from Waiting)"))
            assertEquals(CachedTask(task.copy(title = "Buy seeds (from Waiting)"), null), repository.cached(taskId))
        }

    @Test
    fun `a snapshot from the Logbook keeps the completion time for the offline copy`() =
        runBlocking {
            val doneAt = Instant.parse("2026-10-20T08:00:00Z")
            TaskSnapshots.put(task, doneAt)
            assertEquals(CachedTask(task, null, completedAt = doneAt), repository.cached(taskId))
            assertEquals(task, TaskSnapshots.get(taskId))
        }

    @Test
    fun `after a change the live lists are told and the cached views reload`() =
        runBlocking {
            val signal = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(5_000) { TaskEdits.changes.first() } }

            repository.changed()

            assertEquals(Unit, signal.await())
            val reloaded =
                dispatcher.requests
                    .filter { it.url.encodedPath == TASKS }
                    .mapNotNull { it.url.queryParameter("list") ?: it.url.queryParameter("due")?.lowercase() }
                    .toSet()
            assertTrue(reloaded.toString(), reloaded.containsAll(setOf("NEXT", "INBOX")))
            assertTrue(reloaded.toString(), "today" in reloaded || "overdue" in reloaded)
        }
}
