package ch.sonensei.cockpit.review

import ch.sonensei.cockpit.core.network.generated.apis.WeeklyReviewApi
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
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

/** Starting, moving and completing the server's review session, and the calendar steps' events. */
class ReviewRepositoryFlowTest {
    private val server = MockWebServer()
    private lateinit var reviews: ReviewRepository

    private val id = UUID.fromString("00000000-0000-7000-8000-000000000009")

    private fun reviewJson(
        step: String,
        version: Long,
    ) = """{"id":"$id","step":"$step","stepNumber":1,"startedAt":"2026-11-01T18:00:00Z",""" +
        """"createdAt":"2026-11-01T18:00:00Z","updatedAt":"2026-11-01T18:30:00Z","version":$version}"""

    private fun json(body: String) =
        MockResponse
            .Builder()
            .code(200)
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
        reviews = ReviewRepository(retrofit.create(WeeklyReviewApi::class.java)) { ZoneId.of("Europe/Zurich") }
    }

    @After
    fun tearDown() = server.close()

    @Test
    fun `start posts and returns the open review`() =
        runBlocking {
            server.enqueue(json(reviewJson("GET_CLEAR", 1)))
            assertEquals(ReviewResult.Ok(OpenReview(id, ReviewStep.GET_CLEAR, Instant.parse("2026-11-01T18:00:00Z"), 1)), reviews.start())
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/v1/weekly-reviews", request.url.encodedPath)
        }

    @Test
    fun `move patches the step with the review's version as If-Match`() =
        runBlocking {
            server.enqueue(json(reviewJson("PAST_CALENDAR", 4)))
            val open = OpenReview(id, ReviewStep.GET_CLEAR, Instant.EPOCH, 3)
            val moved = reviews.move(open, ReviewStep.PAST_CALENDAR)
            assertEquals(ReviewStep.PAST_CALENDAR, (moved as ReviewResult.Ok).value.step)
            assertEquals(4L, moved.value.version)
            val request = server.takeRequest()
            assertEquals("PATCH", request.method)
            assertEquals("/api/v1/weekly-reviews/$id", request.url.encodedPath)
            assertEquals("\"3\"", request.headers["If-Match"])
            assertEquals("""{"step":"PAST_CALENDAR"}""", request.body!!.utf8())
        }

    @Test
    fun `a move refused for an outdated version is reported with its status`() =
        runBlocking {
            server.enqueue(status(412))
            assertEquals(ReviewResult.Failed(412), reviews.move(OpenReview(id, ReviewStep.GET_CLEAR, Instant.EPOCH, 1), ReviewStep.SOMEDAY))
        }

    @Test
    fun `complete posts with If-Match`() =
        runBlocking {
            server.enqueue(json(reviewJson("REFLECTION", 9).replace("}", ""","completedAt":"2026-11-01T19:00:00Z"}""")))
            assertEquals(ReviewResult.Ok(Unit), reviews.complete(OpenReview(id, ReviewStep.REFLECTION, Instant.EPOCH, 8)))
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/v1/weekly-reviews/$id/complete", request.url.encodedPath)
            assertEquals("\"8\"", request.headers["If-Match"])
        }

    @Test
    fun `events are mapped to their local day and time, all-day ones to their date`() =
        runBlocking {
            val event = "00000000-0000-7000-8000-000000000001"
            val calendar = "00000000-0000-7000-8000-000000000002"
            server.enqueue(
                json(
                    """{"from":"2026-11-08T00:00:00Z","to":"2026-11-22T00:00:00Z","items":[""" +
                        """{"eventId":"$event","calendarId":"$calendar","title":"Late call","allDay":false,""" +
                        """"start":"2026-11-08T23:30:00Z","end":"2026-11-09T00:30:00Z","status":"CONFIRMED",""" +
                        """"transparency":"OPAQUE","version":1,"occurrence":"2026-11-08T23:30:00Z"},""" +
                        """{"eventId":"$event","calendarId":"$calendar","title":"Holiday","allDay":true,""" +
                        """"startDate":"2026-11-10","endDate":"2026-11-11","status":"CONFIRMED","transparency":"TRANSPARENT","version":1}]}""",
                ),
            )
            val events = (reviews.events(upcoming = true) as ReviewResult.Ok).value
            assertEquals("/api/v1/weekly-reviews/steps/upcoming-events", server.takeRequest().url.encodedPath)
            assertEquals(
                listOf(
                    ReviewEvent("$event@2026-11-08T23:30:00Z", "Late call", LocalDate.of(2026, 11, 9), LocalTime.of(0, 30)),
                    ReviewEvent(event, "Holiday", LocalDate.of(2026, 11, 10), null),
                ),
                events,
            )
        }

    private fun occurrences(item: String) =
        json(
            """{"from":"2026-11-01T00:00:00Z","to":"2026-11-08T00:00:00Z","items":[{"eventId":"00000000-0000-7000-8000-000000000001",""" +
                """"calendarId":"00000000-0000-7000-8000-000000000002","title":"Fictitious","status":"CONFIRMED",""" +
                """"transparency":"OPAQUE","version":1,$item}]}""",
        )

    @Test
    fun `a timed occurrence with only a start date falls back to that date`() =
        runBlocking {
            server.enqueue(occurrences(""""allDay":false,"startDate":"2026-11-04""""))
            val events = (reviews.events(upcoming = false) as ReviewResult.Ok).value
            assertEquals(listOf(ReviewEvent("00000000-0000-7000-8000-000000000001", "Fictitious", LocalDate.of(2026, 11, 4), null)), events)
        }

    @Test
    fun `an occurrence without any start is an unreadable answer, not a crash`() =
        runBlocking {
            server.enqueue(occurrences(""""allDay":true"""))
            assertEquals(ReviewResult.Failed(null), reviews.events(upcoming = false))
        }

    @Test
    fun `completing a review completed elsewhere is reported as a conflict status`() =
        runBlocking {
            server.enqueue(status(409))
            assertEquals(ReviewResult.Failed(409), reviews.complete(OpenReview(id, ReviewStep.REFLECTION, Instant.EPOCH, 2)))
        }

    @Test
    fun `starting without a connection is offline`() =
        runBlocking {
            server.close()
            assertEquals(ReviewResult.Offline, reviews.start())
        }

    @Test
    fun `past events come from their own endpoint, offline when the server is down`() =
        runBlocking {
            server.enqueue(status(503))
            assertEquals(ReviewResult.Offline, reviews.events(upcoming = false))
            assertEquals("/api/v1/weekly-reviews/steps/past-events", server.takeRequest().url.encodedPath)
        }
}
