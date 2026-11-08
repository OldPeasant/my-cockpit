package ch.sonensei.cockpit.gtd.inbox

import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.core.network.generated.apis.CalendarsApi
import ch.sonensei.cockpit.core.network.generated.apis.ProjectsApi
import ch.sonensei.cockpit.core.network.generated.apis.TasksApi
import ch.sonensei.cockpit.core.network.generated.infrastructure.Serializer
import ch.sonensei.cockpit.gtd.data.Energy
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
import ch.sonensei.cockpit.gtd.task.ConvertedItem
import ch.sonensei.cockpit.gtd.task.ConvertedType
import ch.sonensei.cockpit.gtd.task.EventPlan
import ch.sonensei.cockpit.gtd.task.TargetCalendar
import ch.sonensei.cockpit.gtd.task.TaskConverter
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.MediaType.Companion.toMediaType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.IOException
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/** "To reference" and "To calendar" in Inbox processing against a fake server: calls, If-Match, body, results. */
@RunWith(AndroidJUnit4::class)
class InboxConversionRepositoryTest {
    private val dispatcher = RoutingDispatcher()
    private val server = MockWebServer().apply { this.dispatcher = this@InboxConversionRepositoryTest.dispatcher }
    private lateinit var gtd: GtdHarness
    private lateinit var retrofit: Retrofit
    private lateinit var inbox: InboxRepository

    private val id = UUID.fromString(uuid(1))
    private val path = "$TASKS/$id"
    private val notePath = "$path/convert-to-note"
    private val eventPath = "$path/convert-to-event"
    private val task = GtdTask(id = id, title = "Book the concert", list = TaskList.INBOX, position = 1, version = 3)
    private val calendar = UUID.fromString(uuid(90))
    private val plan = EventPlan(calendar, LocalDate.of(2026, 11, 14), LocalTime.of(19, 30), durationMin = 120)

    @Before
    fun setUp() {
        server.start()
        gtd = GtdHarness(server, dispatcher)
        retrofit =
            Retrofit
                .Builder()
                .baseUrl(server.url("/"))
                .addConverterFactory(Serializer.kotlinxSerializationJson.asConverterFactory("application/json".toMediaType()))
                .build()
        inbox = repository(retrofit.create(CalendarsApi::class.java))
        // The reloads after an action.
        repeat(3) { dispatcher.enqueue("$TASKS|INBOX", json(taskPage())) }
    }

    @After
    fun tearDown() {
        gtd.close()
        server.close()
    }

    private fun repository(calendars: CalendarsApi) =
        InboxRepository(
            retrofit.create(TasksApi::class.java),
            retrofit.create(InboxTaskPatchApi::class.java),
            retrofit.create(ProjectsApi::class.java),
            gtd.tasks,
            gtd.connectivity,
            TaskConverter(retrofit.create(TasksApi::class.java), calendars),
        )

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

    private fun noteJson() =
        """{"id":"${uuid(70)}","archived":false,"bodyMd":"","createdAt":"2026-10-20T08:00:00Z","displayTitle":"Book the concert",""" +
            """"pinned":false,"tags":[],"updatedAt":"2026-10-20T08:00:00Z","version":1}"""

    private fun eventJson() =
        """{"id":"${uuid(71)}","allDay":false,"attendees":[],"calendarId":"$calendar","createdAt":"2026-10-20T08:00:00Z",""" +
            """"exdates":[],"reminderMinutes":[],"status":"CONFIRMED","syncState":"LOCAL","title":"Book the concert",""" +
            """"transparency":"OPAQUE","updatedAt":"2026-10-20T08:00:00Z","version":1,""" +
            """"start":"2026-11-14T19:30:00Z","end":"2026-11-14T21:30:00Z","timeZone":"UTC"}"""

    @Test
    fun `to reference converts the item to a note with its version and reports where it went`() {
        dispatcher.enqueue(notePath, json(noteJson()))

        val result = process(InboxAction.ToReference)

        assertEquals(ProcessResult.Converted(ConvertedItem(ConvertedType.NOTE, UUID.fromString(uuid(70)), "Book the concert")), result)
        assertEquals("\"3\"", call("POST", notePath).headers["If-Match"])
        assertTrue(calls().any { it.url.queryParameter("list") == "INBOX" })
    }

    @Test
    fun `converting saves no clarification first`() {
        dispatcher.enqueue(notePath, json(noteJson()))
        dispatcher.enqueue(eventPath, json(eventJson()))

        process(InboxAction.ToReference, Clarification(energy = Energy.HIGH, timeEstimateMin = 30))
        process(InboxAction.ToCalendar(plan.request(ZoneOffset.UTC)), Clarification(energy = Energy.LOW))

        assertFalse(calls().any { it.method == "PATCH" })
        assertEquals("\"3\"", call("POST", eventPath).headers["If-Match"])
    }

    @Test
    fun `to calendar sends the plan's request and reports the event with its day`() {
        dispatcher.enqueue(eventPath, json(eventJson()))

        val result = process(InboxAction.ToCalendar(plan.request(ZoneId.of("Europe/Zurich"))))

        assertEquals(
            ProcessResult.Converted(
                ConvertedItem(ConvertedType.EVENT, UUID.fromString(uuid(71)), "Book the concert", LocalDate.of(2026, 11, 14)),
            ),
            result,
        )
        val body = JsonObject(call("POST", eventPath).json().filterValues { it.toString() != "null" })
        assertEquals(setOf("allDay", "calendarId", "start", "end", "timeZone"), body.keys)
        assertEquals(calendar.toString(), body.getValue("calendarId").jsonPrimitive.content)
        assertEquals("Europe/Zurich", body.getValue("timeZone").jsonPrimitive.content)
        assertEquals("2026-11-14T19:30:00+01:00", body.getValue("start").jsonPrimitive.content)
        assertEquals("2026-11-14T21:30:00+01:00", body.getValue("end").jsonPrimitive.content)
    }

    @Test
    fun `a conversion after a saved clarification uses the saved version`() {
        val saved = taskJson(1, "Book the concert", extra = ""","energy":"LOW"""").replace("\"version\":1", "\"version\":4")
        dispatcher.enqueue(path, json(saved))
        dispatcher.enqueue("$path/move", status(503))
        dispatcher.enqueue(notePath, json(noteJson()))

        assertEquals(ProcessResult.Offline, process(InboxAction.Next, Clarification(energy = Energy.LOW)))
        val result = process(InboxAction.ToReference, Clarification(energy = Energy.LOW))

        assertTrue(result is ProcessResult.Converted)
        assertEquals("\"4\"", call("POST", notePath).headers["If-Match"])
    }

    @Test
    fun `an item changed or gone elsewhere is a conflict and the inbox is reloaded`() {
        listOf(412, 409, 404).forEach { code ->
            dispatcher.requests.clear()
            dispatcher.enqueue(notePath, status(code))
            dispatcher.enqueue("$TASKS|INBOX", json(taskPage()))

            assertEquals("$code", ProcessResult.Conflict, process(InboxAction.ToReference))
            assertTrue("$code", calls().any { it.url.queryParameter("list") == "INBOX" })
        }
    }

    @Test
    fun `an invalid event is rejected with its status`() {
        dispatcher.enqueue(eventPath, status(400))

        assertEquals(ProcessResult.Rejected(400), process(InboxAction.ToCalendar(plan.request(ZoneOffset.UTC))))
    }

    @Test
    fun `converting while the server is down is offline and reloads nothing`() {
        dispatcher.enqueue(notePath, status(503))

        assertEquals(ProcessResult.Offline, process(InboxAction.ToReference))

        assertTrue(gtd.connectivity.offline.value)
        assertFalse(calls().any { it.url.queryParameter("list") == "INBOX" })
    }

    @Test
    fun `the calendars offered are the writable ones, the default first`() {
        val calendars =
            listOf(
                """{"id":"${uuid(91)}","name":"Work","isDefault":false,"readOnly":false""",
                """{"id":"${uuid(92)}","name":"Holidays","isDefault":false,"readOnly":true""",
                """{"id":"${uuid(93)}","name":"Home","isDefault":true,"readOnly":false""",
            ).joinToString(",") {
                it + ""","createdAt":"2026-10-01T08:00:00Z","updatedAt":"2026-10-01T08:00:00Z","sourceId":"${uuid(99)}",""" +
                    """"sourceType":"LOCAL","version":1,"visible":true}"""
            }
        dispatcher.enqueue("/api/v1/calendar/calendars", json("""{"items":[$calendars]}"""))

        assertEquals(
            listOf(TargetCalendar(UUID.fromString(uuid(93)), "Home", true), TargetCalendar(UUID.fromString(uuid(91)), "Work")),
            runBlocking { inbox.calendars() },
        )
    }

    @Test
    fun `calendars that cannot be loaded are null, also without a connection, and do not mark the inbox offline`() {
        dispatcher.enqueue("/api/v1/calendar/calendars", status(500))
        assertNull(runBlocking { inbox.calendars() })

        val unreachable = mockk<CalendarsApi> { coEvery { listCalendars() } throws IOException("connection reset") }
        assertNull(runBlocking { repository(unreachable).calendars() })

        assertFalse(gtd.connectivity.offline.value)
    }
}
