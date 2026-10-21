package ch.sonensei.cockpit.calendar.agenda

import ch.sonensei.cockpit.core.network.generated.apis.CalendarsApi
import ch.sonensei.cockpit.core.network.generated.infrastructure.Serializer
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.MediaType.Companion.toMediaType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** Range splitting at its limit, the mapping of answers, and refusals of the occurrence calls. */
class CalendarRepositoryEdgeCasesTest {
    private val server = MockWebServer()
    private lateinit var repository: CalendarRepository
    private val utc = ZoneId.of("UTC")

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

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

    private val empty = """{"items":[]}"""

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
    fun `an empty range asks the server nothing`() =
        runBlocking {
            val day = LocalDate.parse("2026-10-21")
            assertEquals(CalendarResult.Ok(emptyList<Occurrence>()), repository.occurrences(day, day, utc))
            assertEquals(0, server.requestCount)
        }

    @Test
    fun `366 days are one call, 367 days are two`() =
        runBlocking {
            val from = LocalDate.parse("2026-01-01")
            server.enqueue(json(empty))
            repository.occurrences(from, from.plusDays(366), utc)
            assertEquals(1, server.requestCount)

            server.enqueue(json(empty))
            server.enqueue(json(empty))
            repository.occurrences(from, from.plusDays(367), utc)
            assertEquals(3, server.requestCount)
            server.takeRequest()
            server.takeRequest()
            val last = server.takeRequest()
            assertEquals("2027-01-02T00:00Z", last.url.queryParameter("from"))
            assertEquals("2027-01-03T00:00Z", last.url.queryParameter("to"))
        }

    @Test
    fun `a failing second part fails the whole range instead of returning half of it`() =
        runBlocking {
            server.enqueue(json(empty))
            server.enqueue(status(404))
            val from = LocalDate.parse("2026-01-01")
            assertEquals(CalendarResult.Failed(404), repository.occurrences(from, from.plusDays(400), utc))
        }

    @Test
    fun `all-day and series occurrences are read with their dates, a blank location is left out`() =
        runBlocking {
            server.enqueue(
                json(
                    """{"items":[
                    |{"eventId":"${id(1)}","calendarId":"${id(90)}","title":"Holiday","allDay":true,"startDate":"2026-10-23",
                    |"endDate":"2026-10-24","location":"  ","status":"CONFIRMED","transparency":"TRANSPARENT","version":2},
                    |{"eventId":"${id(2)}","calendarId":"${id(90)}","title":"Weekly","allDay":false,"start":"2026-10-22T07:00:00Z",
                    |"end":"2026-10-22T08:00:00Z","location":"Room 4","status":"TENTATIVE","transparency":"OPAQUE","version":5,
                    |"seriesId":"${id(2)}","occurrence":"2026-10-22T07:00:00Z"}]}
                    """.trimMargin().replace("\n", ""),
                ),
            )

            val result = repository.occurrences(LocalDate.parse("2026-10-21"), LocalDate.parse("2026-10-28"), utc)

            val (holiday, weekly) = (result as CalendarResult.Ok).value
            assertEquals(
                Occurrence(
                    id(1),
                    id(90),
                    "Holiday",
                    null,
                    true,
                    null,
                    null,
                    LocalDate.parse("2026-10-23"),
                    LocalDate.parse("2026-10-24"),
                    version = 2,
                ),
                holiday,
            )
            assertEquals("Room 4", weekly.location)
            assertEquals(Instant.parse("2026-10-22T07:00:00Z"), weekly.start)
            assertEquals(Instant.parse("2026-10-22T08:00:00Z"), weekly.end)
            assertEquals(id(2), weekly.seriesId)
            assertEquals("2026-10-22T07:00:00Z", weekly.occurrence)
            assertEquals(false, weekly.cancelled)
            assertEquals("${id(2)}/2026-10-22T07:00:00Z", weekly.key)
        }

    @Test
    fun `two occurrences of one series are both kept`() =
        runBlocking {
            fun occurrence(start: String) =
                """{"eventId":"${id(2)}","calendarId":"${id(90)}","title":"Weekly","allDay":false,"start":"$start",
                |"status":"CONFIRMED","transparency":"OPAQUE","version":1,"seriesId":"${id(2)}","occurrence":"$start"}
                """.trimMargin().replace("\n", "")
            server.enqueue(json("""{"items":[${occurrence("2026-10-22T07:00:00Z")},${occurrence("2026-10-29T07:00:00Z")}]}"""))

            val result = repository.occurrences(LocalDate.parse("2026-10-21"), LocalDate.parse("2026-11-01"), utc)

            assertEquals(2, (result as CalendarResult.Ok).value.size)
        }

    @Test
    fun `refused, unauthorised, empty and unreadable answers are reported, server errors count as offline`() =
        runBlocking {
            val day = LocalDate.parse("2026-10-21")
            server.enqueue(status(401))
            assertEquals(CalendarResult.Failed(401), repository.occurrences(day, day.plusDays(1), utc))
            server.enqueue(status(400))
            assertEquals(CalendarResult.Failed(400), repository.occurrences(day, day.plusDays(1), utc))
            server.enqueue(status(500))
            assertEquals(CalendarResult.Offline, repository.occurrences(day, day.plusDays(1), utc))
            server.enqueue(status(204))
            assertEquals(CalendarResult.Failed(204), repository.setVisible(CalendarInfo(id(90), "Work", null, true, false, 3), false))
            server.enqueue(
                json(
                    """{"items":[{"eventId":"${id(1)}","calendarId":"${id(90)}","title":"Bad","allDay":false,
                    |"start":"not a date","status":"CONFIRMED","transparency":"OPAQUE","version":1}]}
                    """.trimMargin().replace("\n", ""),
                ),
            )
            assertEquals(CalendarResult.Failed(null), repository.occurrences(day, day.plusDays(1), utc))
            server.enqueue(json("""{"items":[{"eventId":"not-a-uuid"}]}"""))
            assertEquals(CalendarResult.Failed(null), repository.occurrences(day, day.plusDays(1), utc))
        }

    @Test
    fun `a calendar without a color or with an unreadable one has none`() =
        runBlocking {
            fun calendar(
                n: Int,
                color: String?,
            ) = """{"id":"${id(n)}","name":"Cal $n",${color?.let { "\"color\":\"$it\"," } ?: ""}"visible":false,"readOnly":true,
                |"isDefault":false,"sourceId":"${id(91)}","sourceType":"ICS_FEED","createdAt":"2026-10-01T09:00:00Z",
                |"updatedAt":"2026-10-01T09:00:00Z","version":1}
                """.trimMargin().replace("\n", "")
            server.enqueue(json("""{"items":[${calendar(1, null)},${calendar(2, "tomato")}]}"""))

            val calendars = (repository.calendars() as CalendarResult.Ok).value

            assertEquals(listOf(null, null), calendars.map { it.color })
            assertEquals(listOf(true, true), calendars.map { it.readOnly })
            assertEquals(listOf(false, false), calendars.map { it.visible })
        }
}
