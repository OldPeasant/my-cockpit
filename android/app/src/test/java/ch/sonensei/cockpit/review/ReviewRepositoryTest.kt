package ch.sonensei.cockpit.review

import ch.sonensei.cockpit.core.network.generated.apis.WeeklyReviewApi
import ch.sonensei.cockpit.core.network.generated.infrastructure.Serializer
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.MediaType.Companion.toMediaType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.time.Instant
import java.util.UUID

/** The review calls against a fake server: what is asked, and how answers and refusals are reported. */
class ReviewRepositoryTest {
    private val server = MockWebServer()
    private lateinit var reviews: ReviewRepository

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private fun projectJson(
        n: Int,
        title: String,
        stalled: Boolean,
    ) = """{"id":"${id(n)}","title":"$title","status":"ACTIVE","stalled":$stalled,"createdAt":"2026-10-01T09:00:00Z",
        |"updatedAt":"2026-10-01T09:00:00Z","version":1}
        """.trimMargin().replace("\n", "")

    private val reviewJson =
        """{"id":"${id(9)}","step":"WAITING_FOR","stepNumber":4,"startedAt":"2026-10-20T18:00:00Z",
        |"createdAt":"2026-10-20T18:00:00Z","updatedAt":"2026-10-20T18:30:00Z","version":3}
        """.trimMargin().replace("\n", "")

    private fun json(body: String) =
        MockResponse
            .Builder()
            .code(200)
            .addHeader("Content-Type", "application/json")
            .body(body)
            .build()

    private fun status(code: Int) = MockResponse.Builder().code(code).build()

    /** Answers by path, since the two calls run concurrently. */
    private fun answer(
        status: MockResponse,
        projects: MockResponse,
    ) {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    when (request.url.encodedPath) {
                        "/api/v1/weekly-reviews/status" -> status
                        "/api/v1/weekly-reviews/steps/projects" -> projects
                        else -> status(404)
                    }
            }
    }

    @Before
    fun setUp() {
        server.start()
        val retrofit =
            Retrofit
                .Builder()
                .baseUrl(server.url("/"))
                .addConverterFactory(Serializer.kotlinxSerializationJson.asConverterFactory("application/json".toMediaType()))
                .build()
        reviews = ReviewRepository(retrofit.create(WeeklyReviewApi::class.java))
    }

    @After
    fun tearDown() = server.close()

    @Test
    fun `the overview has the open review's step, the days since the last one and the projects in the server's order`() =
        runBlocking {
            answer(
                json("""{"current":$reviewJson,"daysSinceLastReview":6,"lastCompletedAt":"2026-10-16T19:00:00Z"}"""),
                json("""{"items":[${projectJson(1, "Move flat", true)},${projectJson(2, "Tax return", false)}]}"""),
            )

            val overview = (reviews.overview() as ReviewResult.Ok).value
            assertEquals(OpenReview(id(9), ReviewStep.WAITING_FOR, Instant.parse("2026-10-20T18:00:00Z")), overview.current)
            assertEquals(6L, overview.daysSinceLastReview)
            assertEquals(listOf("Move flat", "Tax return"), overview.projects.map { it.title })
            assertEquals(listOf(ReviewProject(id(1), "Move flat", true)), overview.stalledProjects)
            val paths = List(2) { server.takeRequest().url.encodedPath }.toSet()
            assertEquals(setOf("/api/v1/weekly-reviews/status", "/api/v1/weekly-reviews/steps/projects"), paths)
        }

    @Test
    fun `without an open review or a past one both are null`() =
        runBlocking {
            answer(json("""{"current":null,"daysSinceLastReview":null}"""), json("""{"items":[]}"""))

            val overview = (reviews.overview() as ReviewResult.Ok).value
            assertNull(overview.current)
            assertNull(overview.daysSinceLastReview)
            assertEquals(emptyList<ReviewProject>(), overview.projects)
        }

    @Test
    fun `a refusal is a failure with its status, a server error counts as offline`() =
        runBlocking {
            answer(status(403), json("""{"items":[]}"""))
            assertEquals(ReviewResult.Failed(403), reviews.overview())

            answer(json("""{"current":null}"""), status(503))
            assertEquals(ReviewResult.Offline, reviews.overview())
        }

    @Test
    fun `an unreadable answer is a failure without a status`() =
        runBlocking {
            answer(json("""{"current":{"id":"${id(9)}","step":"DANCING"}}"""), json("""{"items":[]}"""))
            assertEquals(ReviewResult.Failed(null), reviews.overview())
        }

    @Test
    fun `a 4xx just below a server error is a failure, 500 itself counts as offline`() =
        runBlocking {
            answer(status(499), json("""{"items":[]}"""))
            assertEquals(ReviewResult.Failed(499), reviews.overview())

            answer(status(500), json("""{"items":[]}"""))
            assertEquals(ReviewResult.Offline, reviews.overview())
        }

    @Test
    fun `a refused projects call fails the whole overview`() =
        runBlocking {
            answer(json("""{"current":null,"daysSinceLastReview":2}"""), status(404))
            assertEquals(ReviewResult.Failed(404), reviews.overview())
        }

    @Test
    fun `a successful answer without a body is a failure with its status`() =
        runBlocking {
            answer(status(204), json("""{"items":[]}"""))
            assertEquals(ReviewResult.Failed(204), reviews.overview())
        }

    @Test
    fun `a body that is not JSON is a failure without a status`() =
        runBlocking {
            answer(json("""{"current":null}"""), json("<html>maintenance</html>"))
            assertEquals(ReviewResult.Failed(null), reviews.overview())
        }

    @Test
    fun `an unreadable start date is a failure without a status`() =
        runBlocking {
            val badDate = reviewJson.replace("\"startedAt\":\"2026-10-20T18:00:00Z\"", "\"startedAt\":\"last Tuesday\"")
            answer(json("""{"current":$badDate,"daysSinceLastReview":1}"""), json("""{"items":[]}"""))
            assertEquals(ReviewResult.Failed(null), reviews.overview())
        }

    @Test
    fun `an unreachable server is offline`() =
        runBlocking {
            server.close()
            assertEquals(ReviewResult.Offline, reviews.overview())
        }
}
