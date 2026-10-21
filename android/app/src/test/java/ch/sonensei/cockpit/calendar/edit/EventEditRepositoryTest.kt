package ch.sonensei.cockpit.calendar.edit

import ch.sonensei.cockpit.calendar.agenda.CalendarResult
import ch.sonensei.cockpit.calendar.agenda.Occurrence
import ch.sonensei.cockpit.core.network.generated.apis.CalendarsApi
import ch.sonensei.cockpit.core.network.generated.infrastructure.Serializer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.MediaType.Companion.toMediaType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.time.Instant
import java.util.UUID

/** The event calls against a fake server: where a change of an occurrence goes, and how refusals are reported. */
class EventEditRepositoryTest {
    private val server = MockWebServer()
    private lateinit var repository: EventEditRepository

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private fun eventJson(
        n: Int,
        rrule: String? = null,
    ) = """{"id":"${id(n)}","calendarId":"${id(90)}","title":"Event $n","allDay":false,"attendees":[],"exdates":[],
        |"reminderMinutes":[],"status":"CONFIRMED","syncState":"LOCAL","transparency":"OPAQUE",
        |"start":"2026-10-21T08:00:00Z","end":"2026-10-21T09:00:00Z","timeZone":"Europe/Zurich",
        |${rrule?.let { "\"rrule\":\"$it\"," } ?: ""}
        |"createdAt":"2026-10-01T09:00:00Z","updatedAt":"2026-10-01T09:00:00Z","version":6}
        """.trimMargin().replace("\n", "")

    private fun json(
        body: String,
        code: Int = 200,
    ) = MockResponse
        .Builder()
        .code(code)
        .addHeader("Content-Type", "application/json")
        .body(body)
        .build()

    private fun status(code: Int) = MockResponse.Builder().code(code).build()

    private fun occurrence(
        eventId: UUID,
        seriesId: UUID? = null,
        original: String? = null,
    ) = Occurrence(
        eventId,
        id(90),
        "Standup",
        null,
        false,
        Instant.parse("2026-10-23T07:00:00Z"),
        Instant.parse("2026-10-23T07:15:00Z"),
        null,
        null,
        seriesId = seriesId,
        occurrence = original,
    )

    private val patch = JsonObject(mapOf("title" to JsonPrimitive("Moved")))

    @Before
    fun setUp() {
        server.start()
        val retrofit =
            Retrofit
                .Builder()
                .baseUrl(server.url("/"))
                .addConverterFactory(Serializer.kotlinxSerializationJson.asConverterFactory("application/json".toMediaType()))
                .build()
        repository = EventEditRepository(retrofit.create(CalendarsApi::class.java), retrofit.create(EventWriteApi::class.java))
    }

    @After
    fun tearDown() = server.close()

    @Test
    fun `an event is loaded with its rule and zone`() =
        runBlocking {
            server.enqueue(json(eventJson(2, rrule = "FREQ=WEEKLY")))
            val result = repository.event(id(2)) as CalendarResult.Ok
            assertEquals("FREQ=WEEKLY", result.value.rrule)
            assertEquals("Europe/Zurich", result.value.timeZone)
            assertEquals(6L, result.value.version)
            assertEquals("/api/v1/calendar/events/${id(2)}", server.takeRequest().url.encodedPath)
        }

    @Test
    fun `a create posts the body as built`() =
        runBlocking {
            server.enqueue(json(eventJson(1), 201))
            val body = JsonObject(mapOf("id" to JsonPrimitive(id(1).toString()), "title" to JsonPrimitive("Lunch")))
            assertTrue(repository.create(body) is CalendarResult.Ok)
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals(body, Json.parseToJsonElement(request.body!!.utf8()).jsonObject)
        }

    @Test
    fun `a single event is patched without scope, with its version as If-Match`() =
        runBlocking {
            server.enqueue(json(eventJson(1)))
            assertTrue(repository.update(occurrence(id(1)), 4, EditScope.ALL, patch) is CalendarResult.Ok)
            val request = server.takeRequest()
            assertEquals("PATCH", request.method)
            assertEquals("/api/v1/calendar/events/${id(1)}", request.url.encodedPath)
            assertNull(request.url.queryParameter("scope"))
            assertEquals("\"4\"", request.headers["If-Match"])
            assertEquals(patch, Json.parseToJsonElement(request.body!!.utf8()).jsonObject)
        }

    @Test
    fun `a generated occurrence is patched at its master with scope and original start`() =
        runBlocking {
            server.enqueue(json(eventJson(2)))
            repository.update(occurrence(id(2), id(2), "2026-10-23T07:00:00Z"), 7, EditScope.FOLLOWING, patch)
            val request = server.takeRequest()
            assertEquals("/api/v1/calendar/events/${id(2)}", request.url.encodedPath)
            assertEquals("FOLLOWING", request.url.queryParameter("scope"))
            assertEquals("2026-10-23T07:00:00Z", request.url.queryParameter("occurrence"))
        }

    @Test
    fun `an override is patched directly, with scope but no occurrence`() =
        runBlocking {
            server.enqueue(json(eventJson(3)))
            repository.update(occurrence(id(3), id(2), "2026-10-23T07:00:00Z"), 2, null, patch)
            val request = server.takeRequest()
            assertEquals("/api/v1/calendar/events/${id(3)}", request.url.encodedPath)
            assertEquals("THIS", request.url.queryParameter("scope"))
            assertNull(request.url.queryParameter("occurrence"))
        }

    @Test
    fun `refusals keep their status, server errors and no connection count as offline`() =
        runBlocking {
            server.enqueue(status(412))
            assertEquals(CalendarResult.Failed(412), repository.update(occurrence(id(1)), 1, null, patch))
            server.enqueue(status(409))
            assertEquals(CalendarResult.Failed(409), repository.update(occurrence(id(1)), 1, null, patch))
            server.enqueue(status(502))
            assertEquals(CalendarResult.Offline, repository.create(patch))
            server.enqueue(json("{not json"))
            assertEquals(CalendarResult.Failed(null), repository.event(id(1)))
            server.close()
            assertEquals(CalendarResult.Offline, repository.event(id(1)))
        }
}
