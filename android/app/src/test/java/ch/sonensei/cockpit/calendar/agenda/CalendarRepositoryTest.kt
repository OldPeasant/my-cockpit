package ch.sonensei.cockpit.calendar.agenda

import ch.sonensei.cockpit.core.network.generated.apis.CalendarsApi
import ch.sonensei.cockpit.core.network.generated.infrastructure.Serializer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.MediaType.Companion.toMediaType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** The calendar calls against a fake server: the ranges asked for, and how answers and refusals are reported. */
class CalendarRepositoryTest {
    private val server = MockWebServer()
    private lateinit var repository: CalendarRepository
    private val zurich = ZoneId.of("Europe/Zurich")

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private fun occurrenceJson(
        n: Int,
        start: String = "2026-10-21T08:00:00Z",
        status: String = "CONFIRMED",
    ) = """{"eventId":"${id(n)}","calendarId":"${id(90)}","title":"Event $n","allDay":false,"start":"$start",
        |"end":"2026-10-21T09:00:00Z","status":"$status","transparency":"OPAQUE","version":1}
        """.trimMargin().replace("\n", "")

    private fun calendarJson(
        visible: Boolean = true,
        version: Long = 3,
    ) = """{"id":"${id(90)}","name":"Work","color":"#3366ff","visible":$visible,"readOnly":false,"isDefault":true,
        |"sourceId":"${id(91)}","sourceType":"LOCAL","createdAt":"2026-10-01T09:00:00Z","updatedAt":"2026-10-01T09:00:00Z",
        |"version":$version}
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

    @Before
    fun setUp() {
        server.start()
        val retrofit =
            Retrofit
                .Builder()
                .baseUrl(server.url("/"))
                .addConverterFactory(Serializer.kotlinxSerializationJson.asConverterFactory("application/json".toMediaType()))
                .build()
        repository = CalendarRepository(retrofit.create(CalendarsApi::class.java), retrofit.create(CalendarWriteApi::class.java))
    }

    @After
    fun tearDown() = server.close()

    @Test
    fun `the range is asked for from local midnight to local midnight, in UTC`() =
        runBlocking {
            server.enqueue(json("""{"items":[${occurrenceJson(1)}]}"""))

            val result = repository.occurrences(LocalDate.parse("2026-10-21"), LocalDate.parse("2026-10-28"), zurich)

            val request = server.takeRequest()
            assertEquals("/api/v1/calendar/events", request.url.encodedPath)
            assertEquals("2026-10-20T22:00Z", request.url.queryParameter("from"))
            // The clocks go back on 25 October: midnight is UTC+1 again.
            assertEquals("2026-10-27T23:00Z", request.url.queryParameter("to"))
            assertEquals(listOf("Event 1"), (result as CalendarResult.Ok).value.map { it.title })
        }

    @Test
    fun `a long range is loaded in parts, merged without duplicates and without cancelled occurrences`() =
        runBlocking {
            server.enqueue(json("""{"items":[${occurrenceJson(1)},${occurrenceJson(2)}]}"""))
            server.enqueue(json("""{"items":[${occurrenceJson(2)},${occurrenceJson(3, status = "CANCELLED")}]}"""))

            val result = repository.occurrences(LocalDate.parse("2026-01-01"), LocalDate.parse("2027-03-01"), ZoneId.of("UTC"))

            assertEquals(listOf("Event 1", "Event 2"), (result as CalendarResult.Ok).value.map { it.title })
            val first = server.takeRequest()
            val second = server.takeRequest()
            assertEquals("2026-01-01T00:00Z", first.url.queryParameter("from"))
            assertEquals("2027-01-02T00:00Z", first.url.queryParameter("to"))
            assertEquals("2027-01-02T00:00Z", second.url.queryParameter("from"))
            assertEquals("2027-03-01T00:00Z", second.url.queryParameter("to"))
        }

    @Test
    fun `calendars are read with their color`() =
        runBlocking {
            server.enqueue(json("""{"items":[${calendarJson()}]}"""))
            val calendars = (repository.calendars() as CalendarResult.Ok).value
            assertEquals(listOf(CalendarInfo(id(90), "Work", 0xFF3366FFL, true, false, 3)), calendars)
        }

    @Test
    fun `visibility sends only the flag, with the calendar's version`() =
        runBlocking {
            server.enqueue(json(calendarJson(visible = false, version = 4)))
            val calendar = CalendarInfo(id(90), "Work", null, true, false, 3)

            val result = repository.setVisible(calendar, false)

            val request = server.takeRequest()
            assertEquals("PATCH", request.method)
            assertEquals("\"3\"", request.headers["If-Match"])
            val body: JsonObject = Json.parseToJsonElement(request.body!!.utf8()).jsonObject
            assertEquals(setOf("visible"), body.keys)
            assertEquals(CalendarInfo(id(90), "Work", 0xFF3366FFL, false, false, 4), (result as CalendarResult.Ok).value)
        }

    @Test
    fun `refusals, server errors and lost connections are reported`() =
        runBlocking {
            server.enqueue(status(412))
            assertEquals(CalendarResult.Failed(412), repository.setVisible(CalendarInfo(id(90), "Work", null, true, false, 3), false))
            server.enqueue(status(503))
            assertEquals(CalendarResult.Offline, repository.calendars())
            server.enqueue(json("not json"))
            assertEquals(CalendarResult.Failed(null), repository.calendars())
            server.close()
            assertTrue(repository.occurrences(LocalDate.EPOCH, LocalDate.EPOCH.plusDays(1), zurich) is CalendarResult.Offline)
        }
}
