package ch.sonensei.cockpit.gtd.task

import ch.sonensei.cockpit.core.network.generated.apis.RemindersApi
import ch.sonensei.cockpit.core.network.generated.infrastructure.Serializer
import ch.sonensei.cockpit.gtd.data.GtdTask
import ch.sonensei.cockpit.gtd.data.TaskList
import ch.sonensei.cockpit.reminders.ReminderSync
import ch.sonensei.cockpit.reminders.ReminderSyncResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.MediaType.Companion.toMediaType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

private val ZURICH: ZoneId = ZoneId.of("Europe/Zurich")
private val TASK_ID: UUID = UUID.fromString("0192f000-0000-7000-9000-000000000001")
private val OTHER_TASK_ID: UUID = UUID.fromString("0192f000-0000-7000-9000-000000000002")

private fun rid(n: Int): UUID = UUID.fromString("0192f000-0000-7000-8000-%012d".format(n))

/** The reminder presets relative to the task's due and scheduled dates. */
class TaskReminderPresetsTest {
    private val due = ReminderAnchors(dueDate = LocalDate.of(2026, 11, 10), dueTime = LocalTime.of(14, 30))

    @Test
    fun `presets with a due time remind relative to it in the user's zone`() {
        assertEquals(Instant.parse("2026-11-10T13:30:00Z"), ReminderPreset.AT_DUE.at(due, ZURICH))
        assertEquals(Instant.parse("2026-11-10T12:30:00Z"), ReminderPreset.HOUR_BEFORE_DUE.at(due, ZURICH))
        assertEquals(Instant.parse("2026-11-09T13:30:00Z"), ReminderPreset.DAY_BEFORE_DUE.at(due, ZURICH))
        assertNull(ReminderPreset.AT_SCHEDULED.at(due, ZURICH))
    }

    @Test
    fun `a due date without a time reminds at nine and offers no hour-before preset`() {
        val dateOnly = ReminderAnchors(dueDate = LocalDate.of(2026, 11, 10))

        assertEquals(Instant.parse("2026-11-10T08:00:00Z"), ReminderPreset.AT_DUE.at(dateOnly, ZURICH))
        assertNull(ReminderPreset.HOUR_BEFORE_DUE.at(dateOnly, ZURICH))
        assertEquals(Instant.parse("2026-11-09T08:00:00Z"), ReminderPreset.DAY_BEFORE_DUE.at(dateOnly, ZURICH))
    }

    @Test
    fun `the scheduled date reminds at nine local time`() {
        val scheduled = ReminderAnchors(scheduledDate = LocalDate.of(2026, 11, 12))

        assertEquals(Instant.parse("2026-11-12T08:00:00Z"), ReminderPreset.AT_SCHEDULED.at(scheduled, ZURICH))
        assertNull(ReminderPreset.AT_DUE.at(scheduled, ZURICH))
        assertNull(ReminderPreset.DAY_BEFORE_DUE.at(scheduled, ZURICH))
    }

    @Test
    fun `a day before keeps the local time across a daylight saving change`() {
        // Zurich switches to summer time on 2026-03-29.
        val anchors = ReminderAnchors(dueDate = LocalDate.of(2026, 3, 29), dueTime = LocalTime.of(9, 0))

        assertEquals(Instant.parse("2026-03-29T07:00:00Z"), ReminderPreset.AT_DUE.at(anchors, ZURICH))
        assertEquals(Instant.parse("2026-03-28T08:00:00Z"), ReminderPreset.DAY_BEFORE_DUE.at(anchors, ZURICH))
    }

    @Test
    fun `a task without dates offers no presets`() {
        assertEquals(emptyList<Pair<ReminderPreset, Instant>>(), availablePresets(ReminderAnchors(), ZURICH, Instant.EPOCH))
    }

    @Test
    fun `only presets in the future are offered, a preset exactly now is not`() {
        val anchors = due.copy(scheduledDate = LocalDate.of(2026, 11, 12))

        assertEquals(
            listOf(ReminderPreset.AT_DUE, ReminderPreset.HOUR_BEFORE_DUE, ReminderPreset.DAY_BEFORE_DUE, ReminderPreset.AT_SCHEDULED),
            availablePresets(anchors, ZURICH, Instant.parse("2026-11-09T08:00:00Z")).map { it.first },
        )
        // The day-before preset is exactly now: gone.
        assertEquals(
            listOf(
                ReminderPreset.AT_DUE to Instant.parse("2026-11-10T13:30:00Z"),
                ReminderPreset.HOUR_BEFORE_DUE to Instant.parse("2026-11-10T12:30:00Z"),
                ReminderPreset.AT_SCHEDULED to Instant.parse("2026-11-12T08:00:00Z"),
            ),
            availablePresets(anchors, ZURICH, Instant.parse("2026-11-09T13:30:00Z")),
        )
        assertEquals(emptyList<Any>(), availablePresets(anchors, ZURICH, Instant.parse("2026-11-12T08:00:00Z")))
    }

    @Test
    fun `a task's anchors read its due time and ignore an unreadable one`() {
        val task =
            GtdTask(
                TASK_ID,
                "File tax return",
                TaskList.NEXT,
                0,
                1,
                dueDate = LocalDate.of(2026, 11, 10),
                dueTime = "14:30:00",
                scheduledDate = LocalDate.of(2026, 11, 8),
            )

        assertEquals(
            ReminderAnchors(LocalDate.of(2026, 11, 10), LocalTime.of(14, 30), LocalDate.of(2026, 11, 8)),
            task.reminderAnchors(),
        )
        assertNull(task.copy(dueTime = "half past two").reminderAnchors().dueTime)
        assertEquals(ReminderAnchors(), task.copy(dueDate = null, dueTime = null, scheduledDate = null).reminderAnchors())
    }
}

/** A reminder source whose answers the test hands out. */
private class FakeReminderSource : TaskReminderSource {
    val listed = mutableListOf<UUID>()
    val added = mutableListOf<Pair<UUID, Instant>>()
    val removed = mutableListOf<UUID>()
    var listAnswer: CompletableDeferred<Result<List<TaskReminder>>> = CompletableDeferred()
    var addAnswer: CompletableDeferred<Result<TaskReminder>> = CompletableDeferred()
    var removeAnswer: CompletableDeferred<Result<Unit>> = CompletableDeferred()

    override suspend fun list(taskId: UUID): Result<List<TaskReminder>> {
        listed += taskId
        return listAnswer.await()
    }

    override suspend fun add(
        taskId: UUID,
        at: Instant,
    ): Result<TaskReminder> {
        added += taskId to at
        return addAnswer.await()
    }

    override suspend fun remove(id: UUID): Result<Unit> {
        removed += id
        return removeAnswer.await()
    }
}

private fun failure(error: ReminderError) = Result.failure<Nothing>(ReminderCallFailure(error))

@OptIn(ExperimentalCoroutinesApi::class)
class TaskRemindersViewModelTest {
    private val now = Instant.parse("2026-11-09T08:00:00Z")
    private val early = TaskReminder(rid(1), now.plusSeconds(3600))
    private val late = TaskReminder(rid(2), now.plusSeconds(7200))
    private val source = FakeReminderSource()
    private lateinit var viewModel: TaskRemindersViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        viewModel = TaskRemindersViewModel(source).also { it.now = { now } }
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun openWith(vararg reminders: TaskReminder) {
        viewModel.open(TASK_ID)
        source.listAnswer.complete(Result.success(reminders.toList()))
    }

    @Test
    fun `opening a task loads its reminders, opening it again does not reload`() =
        runTest {
            viewModel.open(TASK_ID)
            assertTrue(viewModel.uiState.value.loading)

            source.listAnswer.complete(Result.success(listOf(early, late)))
            viewModel.open(TASK_ID)

            assertEquals(TaskRemindersUiState(reminders = listOf(early, late)), viewModel.uiState.value)
            assertEquals(listOf(TASK_ID), source.listed)
        }

    @Test
    fun `opening another task starts from an empty state`() =
        runTest {
            openWith(early)
            source.listAnswer = CompletableDeferred()

            viewModel.open(OTHER_TASK_ID)

            assertEquals(TaskRemindersUiState(loading = true), viewModel.uiState.value)
            assertEquals(listOf(TASK_ID, OTHER_TASK_ID), source.listed)
        }

    @Test
    fun `a failed load ends loading with the error, retry loads again`() =
        runTest {
            viewModel.open(TASK_ID)
            source.listAnswer.complete(failure(ReminderError.OFFLINE))
            assertEquals(TaskRemindersUiState(error = ReminderError.OFFLINE), viewModel.uiState.value)

            source.listAnswer = CompletableDeferred()
            viewModel.retry()
            assertTrue(viewModel.uiState.value.loading)
            source.listAnswer.complete(Result.success(listOf(early)))

            assertEquals(TaskRemindersUiState(reminders = listOf(early)), viewModel.uiState.value)
        }

    @Test
    fun `an unexpected failure counts as failed`() =
        runTest {
            viewModel.open(TASK_ID)
            source.listAnswer.complete(Result.failure(IllegalStateException("boom")))

            assertEquals(ReminderError.FAILED, viewModel.uiState.value.error)
        }

    @Test
    fun `retry and add before a task is opened do nothing`() =
        runTest {
            viewModel.retry()
            viewModel.add(now.plusSeconds(60))

            assertEquals(TaskRemindersUiState(), viewModel.uiState.value)
            assertTrue(source.listed.isEmpty() && source.added.isEmpty())
        }

    @Test
    fun `an added reminder is busy until the server answers, then listed in time order`() =
        runTest {
            openWith(late)

            viewModel.add(early.remindAt)
            assertTrue(viewModel.uiState.value.busy)
            source.addAnswer.complete(Result.success(early))

            assertEquals(TaskRemindersUiState(reminders = listOf(early, late)), viewModel.uiState.value)
            assertEquals(listOf(TASK_ID to early.remindAt), source.added)
        }

    @Test
    fun `a time that has passed or is now is refused without a request`() =
        runTest {
            openWith()

            viewModel.add(now)
            assertTrue(viewModel.uiState.value.pastTime)
            viewModel.add(now.minusSeconds(1))

            assertTrue(source.added.isEmpty())
            viewModel.dismissError()
            assertFalse(viewModel.uiState.value.pastTime)
        }

    @Test
    fun `a valid add clears an earlier past-time message`() =
        runTest {
            openWith()
            viewModel.add(now.minusSeconds(60))

            viewModel.add(early.remindAt)

            assertFalse(viewModel.uiState.value.pastTime)
            assertEquals(1, source.added.size)
        }

    @Test
    fun `a second add or a remove while busy is ignored`() =
        runTest {
            openWith(late)

            viewModel.add(early.remindAt)
            viewModel.add(early.remindAt.plusSeconds(60))
            viewModel.remove(late)

            assertEquals(1, source.added.size)
            assertTrue(source.removed.isEmpty())
            source.addAnswer.complete(Result.success(early))
            assertFalse(viewModel.uiState.value.busy)
        }

    @Test
    fun `a rejected add keeps the list and shows the error`() =
        runTest {
            openWith(late)

            viewModel.add(early.remindAt)
            source.addAnswer.complete(failure(ReminderError.INVALID))

            assertEquals(TaskRemindersUiState(reminders = listOf(late), error = ReminderError.INVALID), viewModel.uiState.value)
            viewModel.dismissError()
            assertNull(viewModel.uiState.value.error)
        }

    @Test
    fun `a removed reminder leaves the list once the server confirms`() =
        runTest {
            openWith(early, late)

            viewModel.remove(early)
            assertEquals(listOf(early, late), viewModel.uiState.value.reminders)
            source.removeAnswer.complete(Result.success(Unit))

            assertEquals(TaskRemindersUiState(reminders = listOf(late)), viewModel.uiState.value)
            assertEquals(listOf(rid(1)), source.removed)
        }

    @Test
    fun `a failed remove keeps the reminder and shows the error`() =
        runTest {
            openWith(early)

            viewModel.remove(early)
            source.removeAnswer.complete(failure(ReminderError.OFFLINE))

            assertEquals(TaskRemindersUiState(reminders = listOf(early), error = ReminderError.OFFLINE), viewModel.uiState.value)
        }
}

/** The repository's requests against a fake server, and that every change triggers the reminder sync. */
class TaskReminderRepositoryTest {
    private val server = MockWebServer()
    private val sync = mockk<ReminderSync> { coEvery { sync() } returns ReminderSyncResult.SYNCED }
    private lateinit var repository: TaskReminderRepository

    @Before
    fun setUp() {
        server.start()
        val api =
            Retrofit
                .Builder()
                .baseUrl(server.url("/"))
                .addConverterFactory(Serializer.kotlinxSerializationJson.asConverterFactory("application/json".toMediaType()))
                .build()
                .create(RemindersApi::class.java)
        repository = TaskReminderRepository(api, sync)
    }

    @After
    fun tearDown() = server.close()

    private fun answer(
        code: Int,
        body: String = "",
    ) = server.enqueue(
        MockResponse
            .Builder()
            .code(code)
            .addHeader("Content-Type", if (code < 300) "application/json" else "application/problem+json")
            .body(body)
            .build(),
    )

    private fun reminderJson(
        n: Int,
        remindAt: String,
        firedAt: String? = null,
    ): String =
        """{"id":"${rid(n)}","channel":"NOTIFICATION","createdAt":"2026-11-08T07:00:00Z","remindAt":"$remindAt",""" +
            (firedAt?.let { """"firedAt":"$it",""" } ?: "") +
            """"item":{"available":true,"id":"$TASK_ID","type":"TASK","title":"File tax return","deepLink":"/tasks/$TASK_ID"}}"""

    private fun failureOf(result: Result<*>): ReminderError? = (result.exceptionOrNull() as? ReminderCallFailure)?.error

    @Test
    fun `list asks for the task's reminders and sorts them by time`() =
        runBlocking {
            answer(
                200,
                """{"items":[${reminderJson(2, "2026-11-10T12:00:00Z")},""" +
                    """${reminderJson(1, "2026-11-09T12:00:00Z", firedAt = "2026-11-09T12:00:05Z")}]}""",
            )

            val result = repository.list(TASK_ID)

            assertEquals(
                listOf(
                    TaskReminder(rid(1), Instant.parse("2026-11-09T12:00:00Z"), Instant.parse("2026-11-09T12:00:05Z")),
                    TaskReminder(rid(2), Instant.parse("2026-11-10T12:00:00Z")),
                ),
                result.getOrThrow(),
            )
            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/api/v1/reminders", request.url.encodedPath)
            assertEquals("TASK", request.url.queryParameter("itemType"))
            assertEquals(TASK_ID.toString(), request.url.queryParameter("itemId"))
            coVerify(exactly = 0) { sync.sync() }
        }

    @Test
    fun `add posts a task notification reminder in UTC and then syncs the local alarms`() =
        runBlocking {
            answer(201, reminderJson(3, "2026-11-10T13:30:00Z"))
            val at = Instant.parse("2026-11-10T13:30:00Z")

            val result = repository.add(TASK_ID, at)

            assertEquals(TaskReminder(rid(3), at), result.getOrThrow())
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/v1/reminders", request.url.encodedPath)
            val body = Json.parseToJsonElement(request.body!!.utf8()).jsonObject
            assertEquals(TASK_ID.toString(), body["itemId"]!!.jsonPrimitive.content)
            assertEquals("TASK", body["itemType"]!!.jsonPrimitive.content)
            assertEquals("NOTIFICATION", body["channel"]!!.jsonPrimitive.content)
            val remindAt = OffsetDateTime.parse(body["remindAt"]!!.jsonPrimitive.content)
            assertEquals(at, remindAt.toInstant())
            assertEquals(ZoneOffset.UTC, remindAt.offset)
            coVerify(exactly = 1) { sync.sync() }
        }

    @Test
    fun `a rejected add is invalid and does not sync`() =
        runBlocking {
            answer(422, """{"status":422,"title":"Unprocessable"}""")
            assertEquals(ReminderError.INVALID, failureOf(repository.add(TASK_ID, Instant.parse("2026-11-10T13:30:00Z"))))
            answer(400, """{"status":400,"title":"Bad Request"}""")
            assertEquals(ReminderError.INVALID, failureOf(repository.add(TASK_ID, Instant.parse("2026-11-10T13:30:00Z"))))

            coVerify(exactly = 0) { sync.sync() }
        }

    @Test
    fun `an empty created body counts as failed`() =
        runBlocking {
            answer(201)

            assertEquals(ReminderError.FAILED, failureOf(repository.add(TASK_ID, Instant.parse("2026-11-10T13:30:00Z"))))
        }

    @Test
    fun `a server error or an unreadable body counts as failed`() =
        runBlocking {
            answer(500, """{"status":500}""")
            assertEquals(ReminderError.FAILED, failureOf(repository.list(TASK_ID)))
            answer(200, "not json")
            assertEquals(ReminderError.FAILED, failureOf(repository.list(TASK_ID)))
        }

    @Test
    fun `an unreachable server is offline`() =
        runBlocking {
            server.close()

            assertEquals(ReminderError.OFFLINE, failureOf(repository.list(TASK_ID)))
            assertEquals(ReminderError.OFFLINE, failureOf(repository.remove(rid(1))))
            coVerify(exactly = 0) { sync.sync() }
        }

    @Test
    fun `remove deletes the reminder and then syncs the local alarms`() =
        runBlocking {
            answer(204)

            assertTrue(repository.remove(rid(4)).isSuccess)

            val request = server.takeRequest()
            assertEquals("DELETE", request.method)
            assertEquals("/api/v1/reminders/${rid(4)}", request.url.encodedPath)
            coVerify(exactly = 1) { sync.sync() }
        }

    @Test
    fun `removing a reminder that is already gone counts as removed`() =
        runBlocking {
            answer(404, """{"status":404,"title":"Not Found"}""")

            assertTrue(repository.remove(rid(5)).isSuccess)
            coVerify(exactly = 1) { sync.sync() }
        }

    @Test
    fun `a failed remove does not sync`() =
        runBlocking {
            answer(500, """{"status":500}""")

            assertEquals(ReminderError.FAILED, failureOf(repository.remove(rid(6))))
            coVerify(exactly = 0) { sync.sync() }
        }
}
