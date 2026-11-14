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
import ch.sonensei.cockpit.gtd.data.json
import ch.sonensei.cockpit.gtd.data.status
import ch.sonensei.cockpit.gtd.data.uuid
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
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
import java.io.IOException
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID

/** "To reference" and "To calendar" from the task detail screen against a fake server: paths, If-Match, body, answers. */
@RunWith(AndroidJUnit4::class)
class TaskConversionRepositoryTest {
    private val dispatcher = RoutingDispatcher()
    private val server = MockWebServer().apply { this.dispatcher = this@TaskConversionRepositoryTest.dispatcher }
    private lateinit var harness: GtdHarness
    private lateinit var retrofit: Retrofit
    private lateinit var repository: TaskDetailRepository

    private val taskId: UUID = UUID.fromString(uuid(1))
    private val task = GtdTask(taskId, "Dentist appointment", TaskList.NEXT, 1, version = 7)
    private val taskPath = "$TASKS/${uuid(1)}"
    private val notePath = "$taskPath/convert-to-note"
    private val eventPath = "$taskPath/convert-to-event"
    private val calendarsPath = "/api/v1/calendar/calendars"
    private val zurich = ZoneId.of("Europe/Zurich")

    @Before
    fun setUp() {
        server.start()
        harness = GtdHarness(server, dispatcher)
        retrofit =
            Retrofit
                .Builder()
                .baseUrl(server.url("/"))
                .addConverterFactory(Serializer.kotlinxSerializationJson.asConverterFactory("application/json".toMediaType()))
                .build()
        repository = repository(retrofit.create(CalendarsApi::class.java))
    }

    @After
    fun tearDown() {
        TaskSnapshots.clear()
        harness.close()
        server.close()
    }

    private fun repository(calendars: CalendarsApi) =
        TaskDetailRepository(
            retrofit.create(TasksApi::class.java),
            retrofit.create(TaskWriteApi::class.java),
            harness.dao,
            harness.connectivity,
            harness.tasks,
            TaskConverter(retrofit.create(TasksApi::class.java), calendars),
            retrofit.create(ProjectsApi::class.java),
            harness.projects,
        )

    private fun request(
        method: String,
        path: String,
    ): RecordedRequest = dispatcher.requests.single { it.method == method && it.url.encodedPath == path }

    private fun RecordedRequest.bodyJson(): JsonObject = Json.parseToJsonElement(body!!.utf8()).jsonObject

    private fun problem(
        code: Int,
        body: String,
    ): MockResponse =
        MockResponse
            .Builder()
            .code(code)
            .addHeader("Content-Type", "application/problem+json")
            .body(body)
            .build()

    private fun noteJson(
        n: Int,
        displayTitle: String,
    ) = """{"id":"${uuid(n)}","archived":false,"bodyMd":"","createdAt":"2026-10-20T08:00:00Z","displayTitle":"$displayTitle",""" +
        """"pinned":false,"tags":[],"updatedAt":"2026-10-20T08:00:00Z","version":1}"""

    private fun eventJson(
        n: Int,
        title: String,
        times: String,
    ) = """{"id":"${uuid(n)}","allDay":false,"attendees":[],"calendarId":"${uuid(90)}","createdAt":"2026-10-20T08:00:00Z",""" +
        """"exdates":[],"reminderMinutes":[],"status":"CONFIRMED","syncState":"LOCAL","title":"$title","transparency":"OPAQUE",""" +
        """"updatedAt":"2026-10-20T08:00:00Z","version":1,$times}"""

    private fun calendarJson(
        n: Int,
        name: String,
        isDefault: Boolean = false,
        readOnly: Boolean = false,
    ) = """{"id":"${uuid(n)}","name":"$name","isDefault":$isDefault,"readOnly":$readOnly,"createdAt":"2026-10-01T08:00:00Z",""" +
        """"updatedAt":"2026-10-01T08:00:00Z","sourceId":"${uuid(99)}","sourceType":"LOCAL","version":1,"visible":true}"""

    // --- To reference ---

    @Test
    fun `to reference posts to convert-to-note with the task's version and no body, and returns the note`() =
        runBlocking {
            dispatcher.enqueue(notePath, json(noteJson(70, "Dentist appointment")))

            val result = repository.convertToNote(task)

            assertEquals(TaskResult.Ok(ConvertedItem(ConvertedType.NOTE, UUID.fromString(uuid(70)), "Dentist appointment")), result)
            val sent = request("POST", notePath)
            assertEquals("\"7\"", sent.headers["If-Match"])
            assertEquals(0, sent.body?.size ?: 0)
            assertFalse(harness.connectivity.offline.value)
        }

    @Test
    fun `a conversion of a task gone or trashed elsewhere is not found`() =
        runBlocking {
            dispatcher.enqueue(notePath, status(404))
            assertEquals(TaskResult.NotFound, repository.convertToNote(task))
            dispatcher.enqueue(notePath, status(410))
            assertEquals(TaskResult.NotFound, repository.convertToNote(task))
        }

    @Test
    fun `a conversion of a task changed elsewhere is a conflict`() =
        runBlocking {
            dispatcher.enqueue(notePath, status(412))
            assertEquals(TaskResult.Conflict, repository.convertToNote(task))
            dispatcher.enqueue(eventPath, status(409))
            assertEquals(TaskResult.Conflict, repository.convertToEvent(task, timedPlan().request(zurich)))
        }

    @Test
    fun `an unreachable server makes a conversion offline, other refusals fail with their status`() =
        runBlocking {
            dispatcher.enqueue(notePath, status(503))
            assertEquals(TaskResult.Offline, repository.convertToNote(task))
            assertTrue(harness.connectivity.offline.value)

            dispatcher.enqueue(notePath, status(403))
            assertEquals(TaskResult.Failed(403), repository.convertToNote(task))
        }

    // --- To calendar ---

    private fun timedPlan() = EventPlan(UUID.fromString(uuid(90)), LocalDate.of(2026, 10, 22), LocalTime.of(9, 0), durationMin = 30)

    @Test
    fun `to calendar posts the timed request with If-Match and returns the event with its day`() =
        runBlocking {
            val times = """"start":"2026-10-22T07:00:00Z","end":"2026-10-22T07:30:00Z","timeZone":"Europe/Zurich""""
            dispatcher.enqueue(eventPath, json(eventJson(71, "Dentist appointment", times)))

            val result = repository.convertToEvent(task, timedPlan().request(zurich))

            assertEquals(
                TaskResult.Ok(
                    ConvertedItem(ConvertedType.EVENT, UUID.fromString(uuid(71)), "Dentist appointment", LocalDate.of(2026, 10, 22)),
                ),
                result,
            )
            val sent = request("POST", eventPath)
            assertEquals("\"7\"", sent.headers["If-Match"])
            val body = JsonObject(sent.bodyJson().filterValues { it.toString() != "null" })
            assertEquals(setOf("allDay", "calendarId", "start", "end", "timeZone"), body.keys)
            assertEquals("false", body.getValue("allDay").jsonPrimitive.content)
            assertEquals(uuid(90), body.getValue("calendarId").jsonPrimitive.content)
            assertEquals(
                OffsetDateTime.parse("2026-10-22T09:00:00+02:00"),
                OffsetDateTime.parse(body.getValue("start").jsonPrimitive.content),
            )
            assertEquals(
                OffsetDateTime.parse("2026-10-22T09:30:00+02:00"),
                OffsetDateTime.parse(body.getValue("end").jsonPrimitive.content),
            )
            assertEquals("Europe/Zurich", body.getValue("timeZone").jsonPrimitive.content)
        }

    @Test
    fun `an all-day request sends the start day and the exclusive end day`() =
        runBlocking {
            val times = """"startDate":"2026-10-22","endDate":"2026-10-23""""
            dispatcher.enqueue(eventPath, json(eventJson(72, "Dentist appointment", times).replace("\"allDay\":false", "\"allDay\":true")))

            val result = repository.convertToEvent(task, timedPlan().copy(allDay = true).request(zurich))

            assertEquals(LocalDate.of(2026, 10, 22), (result as TaskResult.Ok).value.day)
            val body = JsonObject(request("POST", eventPath).bodyJson().filterValues { it.toString() != "null" })
            assertEquals(setOf("allDay", "calendarId", "startDate", "endDate"), body.keys)
            assertEquals("true", body.getValue("allDay").jsonPrimitive.content)
            assertEquals("2026-10-22", body.getValue("startDate").jsonPrimitive.content)
            assertEquals("2026-10-23", body.getValue("endDate").jsonPrimitive.content)
        }

    @Test
    fun `the event's day is read on its own clock`() =
        runBlocking {
            // 23:30 UTC is already the next day in Tokyo.
            val times = """"start":"2026-10-22T23:30:00Z","end":"2026-10-23T00:00:00Z","timeZone":"Asia/Tokyo""""
            dispatcher.enqueue(eventPath, json(eventJson(73, "Call", times)))

            val result = repository.convertToEvent(task, timedPlan().request(zurich))

            assertEquals(LocalDate.of(2026, 10, 23), (result as TaskResult.Ok).value.day)
        }

    @Test
    fun `an event without a known zone is dated on the device clock`() =
        runBlocking {
            // Midday UTC is the same day in every zone the device could plausibly be in.
            listOf("", ""","timeZone":"Not/AZone"""").forEachIndexed { i, zone ->
                val times = """"start":"2026-10-22T12:00:00Z","end":"2026-10-22T13:00:00Z"$zone"""
                dispatcher.enqueue(eventPath, json(eventJson(74 + i, "Call", times)))

                val result = repository.convertToEvent(task, timedPlan().request(zurich))

                assertEquals(zone, LocalDate.of(2026, 10, 22), (result as TaskResult.Ok).value.day)
            }
        }

    @Test
    fun `a refused event request with problem details gives the field messages`() =
        runBlocking {
            dispatcher.enqueue(
                eventPath,
                problem(400, """{"status":400,"detail":"Invalid event","errors":[{"field":"end","message":"must be after start"}]}"""),
            )

            assertEquals(
                TaskResult.Invalid(mapOf("end" to "must be after start"), "Invalid event"),
                repository.convertToEvent(task, timedPlan().request(zurich)),
            )
        }

    @Test
    fun `an event request to a task gone elsewhere is not found`() =
        runBlocking {
            dispatcher.enqueue(eventPath, status(404))
            assertEquals(TaskResult.NotFound, repository.convertToEvent(task, timedPlan().request(zurich)))
        }

    // --- calendars ---

    @Test
    fun `calendars offers only writable ones, the default first, then by name ignoring case`() =
        runBlocking {
            val items =
                listOf(
                    calendarJson(91, "work"),
                    calendarJson(92, "Holidays", readOnly = true),
                    calendarJson(93, "Personal", isDefault = true),
                    calendarJson(94, "Family"),
                )
            dispatcher.enqueue(calendarsPath, json("""{"items":[${items.joinToString(",")}]}"""))

            val result = repository.calendars()

            assertEquals(
                TaskResult.Ok(
                    listOf(
                        TargetCalendar(UUID.fromString(uuid(93)), "Personal", isDefault = true),
                        TargetCalendar(UUID.fromString(uuid(94)), "Family"),
                        TargetCalendar(UUID.fromString(uuid(91)), "work"),
                    ),
                ),
                result,
            )
            assertEquals("GET", request("GET", calendarsPath).method)
        }

    @Test
    fun `calendars that fail on the server fail with the status and leave the shared offline state alone`() =
        runBlocking {
            dispatcher.enqueue(calendarsPath, status(500))
            assertEquals(TaskResult.Failed(500), repository.calendars())
            dispatcher.enqueue(calendarsPath, status(403))
            assertEquals(TaskResult.Failed(403), repository.calendars())
            dispatcher.enqueue(calendarsPath, json("""{"unexpected":true}"""))
            assertEquals(TaskResult.Failed(null), repository.calendars())
            assertFalse(harness.connectivity.offline.value)
        }

    @Test
    fun `calendars that cannot be reached are offline without marking the GTD screens offline`() =
        runBlocking {
            val unreachable = mockk<CalendarsApi> { coEvery { listCalendars() } throws IOException("connection reset") }

            assertEquals(TaskResult.Offline, repository(unreachable).calendars())
            assertFalse(harness.connectivity.offline.value)
        }

    @Test
    fun `a cancelled load of the calendars stays cancelled instead of failing`() {
        // A newer dialog cancels the older load: it must not answer "could not be loaded" into the newer dialog.
        val cancelled = mockk<CalendarsApi> { coEvery { listCalendars() } throws CancellationException("a newer load started") }

        val thrown = runCatching { runBlocking { repository(cancelled).calendars() } }.exceptionOrNull()

        assertTrue("$thrown", thrown is CancellationException)
    }

    @Test
    fun `no writable calendar gives an empty list`() =
        runBlocking {
            dispatcher.enqueue(calendarsPath, json("""{"items":[${calendarJson(92, "Holidays", readOnly = true)}]}"""))

            assertEquals(TaskResult.Ok(emptyList<TargetCalendar>()), repository.calendars())
        }
}
