package ch.sonensei.cockpit.dashboard

import ch.sonensei.cockpit.calendar.agenda.CalendarInfo
import ch.sonensei.cockpit.core.network.generated.apis.BookmarksApi
import ch.sonensei.cockpit.core.network.generated.apis.CalendarsApi
import ch.sonensei.cockpit.core.network.generated.apis.HabitsApi
import ch.sonensei.cockpit.core.network.generated.apis.NotesApi
import ch.sonensei.cockpit.core.network.generated.apis.ProjectsApi
import ch.sonensei.cockpit.core.network.generated.apis.SettingsApi
import ch.sonensei.cockpit.core.network.generated.apis.TasksApi
import ch.sonensei.cockpit.core.network.generated.apis.WeeklyReviewApi
import ch.sonensei.cockpit.core.network.generated.infrastructure.Serializer
import ch.sonensei.cockpit.mail.list.MailApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
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
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.Collections
import java.util.UUID

/** Saving the layout in the web's setting with optimistic locking, and the calendars a tapped event needs. */
class DashboardSaveLayoutTest {
    private val server = MockWebServer()
    private lateinit var dashboard: DashboardRepository

    /** Answers per "METHOD path", taken in order; the last one repeats. Anything else is a 404. */
    private val answers = Collections.synchronizedMap(mutableMapOf<String, MutableList<MockResponse>>())
    private val requests = Collections.synchronizedList(mutableListOf<RecordedRequest>())

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private fun json(
        body: String,
        etag: String? = null,
        code: Int = 200,
    ) = MockResponse
        .Builder()
        .code(code)
        .addHeader("Content-Type", "application/json")
        .apply { if (etag != null) addHeader("ETag", etag) }
        .body(body)
        .build()

    private fun status(code: Int) = MockResponse.Builder().code(code).build()

    private fun answer(
        methodAndPath: String,
        vararg responses: MockResponse,
    ) {
        answers[methodAndPath] = responses.toMutableList()
    }

    private fun settings(vararg layout: String) = """{"theme":"dark","dashboardWidgets":[${layout.joinToString(",") { "\"$it\"" }}]}"""

    private fun sent(method: String) = synchronized(requests) { requests.filter { it.method == method } }

    private fun body(request: RecordedRequest): JsonObject = Json.parseToJsonElement(request.body!!.utf8()).jsonObject

    private val edited = DEFAULT_LAYOUT.moved(WidgetId.PINNED_NOTES, 0).withVisible(WidgetId.TODAY, false)
    private val editedKeys =
        arrayOf("pinnedNotes", "today:hidden", "inbox", "upcoming", "waitingOverdue", "stalledProjects", "weeklyReview", "readLater")

    @Before
    fun setUp() {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += request
                    val queue = answers["${request.method} ${request.url.encodedPath}"] ?: return status(404)
                    synchronized(queue) { return if (queue.size > 1) queue.removeAt(0) else queue.first() }
                }
            }
        server.start()
        val retrofit =
            Retrofit
                .Builder()
                .baseUrl(server.url("/"))
                .addConverterFactory(Serializer.kotlinxSerializationJson.asConverterFactory("application/json".toMediaType()))
                .build()
        dashboard =
            DashboardRepository(
                retrofit.create(SettingsApi::class.java),
                retrofit.create(TasksApi::class.java),
                retrofit.create(ProjectsApi::class.java),
                retrofit.create(CalendarsApi::class.java),
                retrofit.create(MailApi::class.java),
                retrofit.create(HabitsApi::class.java),
                retrofit.create(WeeklyReviewApi::class.java),
                retrofit.create(BookmarksApi::class.java),
                retrofit.create(NotesApi::class.java),
                Clock.fixed(Instant.parse("2026-10-21T08:00:00Z"), ZoneId.of("Europe/Zurich")),
            )
    }

    @After
    fun tearDown() = server.close()

    @Test
    fun `a save sends only the layout setting with the ETag of the last read and returns the stored layout`() =
        runBlocking {
            answer("GET /api/v1/settings", json(settings("inbox"), etag = "\"s1\""))
            answer("PATCH /api/v1/settings", json(settings(*editedKeys), etag = "\"s2\""))
            dashboard.layout()

            val result = dashboard.saveLayout(edited)

            assertEquals(DashboardResult.Ok(edited), result)
            val patch = sent("PATCH").single()
            assertEquals("\"s1\"", patch.headers["If-Match"])
            assertEquals(
                JsonObject(mapOf(LAYOUT_SETTING to JsonArray(editedKeys.map(::JsonPrimitive)))),
                body(patch),
            )
            assertEquals(1, sent("GET").size)
        }

    @Test
    fun `the next save uses the ETag the previous save returned`() =
        runBlocking {
            answer("GET /api/v1/settings", json(settings(), etag = "\"s1\""))
            answer(
                "PATCH /api/v1/settings",
                json(settings(*editedKeys), etag = "\"s2\""),
                json(settings(), etag = "\"s3\""),
            )
            dashboard.layout()

            dashboard.saveLayout(edited)
            dashboard.saveLayout(DEFAULT_LAYOUT)

            assertEquals(listOf("\"s1\"", "\"s2\""), sent("PATCH").map { it.headers["If-Match"] })
        }

    @Test
    fun `a read that started before a save does not replace the save's ETag with its older one`() =
        runBlocking {
            val readStarted = CompletableDeferred<Unit>()
            val saveDone = CompletableDeferred<Unit>()
            answer("GET /api/v1/settings", json(settings(), etag = "\"s1\""))
            answer("PATCH /api/v1/settings", json(settings(*editedKeys), etag = "\"s2\""))
            dashboard.layout()
            // The next read answers only after the save finished, with the ETag it had when it was sent.
            answer("GET /api/v1/settings", json(settings(), etag = "\"s1\""))
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        requests += request
                        if (request.method == "GET") {
                            readStarted.complete(Unit)
                            runBlocking { saveDone.await() }
                        }
                        val queue = answers["${request.method} ${request.url.encodedPath}"] ?: return status(404)
                        synchronized(queue) { return if (queue.size > 1) queue.removeAt(0) else queue.first() }
                    }
                }

            coroutineScope {
                val read = async(Dispatchers.IO) { dashboard.layout() }
                readStarted.await()
                dashboard.saveLayout(edited)
                saveDone.complete(Unit)
                read.await()
            }
            dashboard.saveLayout(edited)

            assertEquals(listOf("\"s1\"", "\"s2\""), sent("PATCH").map { it.headers["If-Match"] })
        }

    @Test
    fun `a save answered without an ETag keeps the last one`() =
        runBlocking {
            answer("GET /api/v1/settings", json(settings(), etag = "\"s1\""))
            answer("PATCH /api/v1/settings", json(settings(*editedKeys)))
            dashboard.layout()

            dashboard.saveLayout(edited)
            dashboard.saveLayout(edited)

            assertEquals(listOf("\"s1\"", "\"s1\""), sent("PATCH").map { it.headers["If-Match"] })
        }

    @Test
    fun `a stale ETag re-reads the settings and sends the layout once more with the new ETag`() =
        runBlocking {
            answer("GET /api/v1/settings", json(settings("inbox"), etag = "\"s1\""), json(settings("today"), etag = "\"s5\""))
            answer("PATCH /api/v1/settings", status(412), json(settings(*editedKeys), etag = "\"s6\""))
            dashboard.layout()

            val result = dashboard.saveLayout(edited)

            assertEquals(DashboardResult.Ok(edited), result)
            val patches = sent("PATCH")
            assertEquals(listOf("\"s1\"", "\"s5\""), patches.map { it.headers["If-Match"] })
            // The user's layout is sent again unchanged: the latest layout wins over the one read meanwhile.
            assertEquals(body(patches[0]), body(patches[1]))
            assertEquals(2, sent("GET").size)
        }

    @Test
    fun `a save before any read is answered 428, then reads the settings and sends again`() =
        runBlocking {
            answer("GET /api/v1/settings", json(settings(), etag = "\"s1\""))
            answer("PATCH /api/v1/settings", status(428), json(settings(*editedKeys), etag = "\"s2\""))

            val result = dashboard.saveLayout(edited)

            assertEquals(DashboardResult.Ok(edited), result)
            val patches = sent("PATCH")
            assertNull(patches[0].headers["If-Match"])
            assertEquals("\"s1\"", patches[1].headers["If-Match"])
        }

    @Test
    fun `a concurrent change answered 409 is retried once`() =
        runBlocking {
            answer("GET /api/v1/settings", json(settings(), etag = "\"s1\""), json(settings(), etag = "\"s2\""))
            answer("PATCH /api/v1/settings", status(409), json(settings(*editedKeys), etag = "\"s3\""))
            dashboard.layout()

            assertEquals(DashboardResult.Ok(edited), dashboard.saveLayout(edited))
            assertEquals(2, sent("PATCH").size)
        }

    @Test
    fun `a second conflict fails the save without a third try`() =
        runBlocking {
            answer("GET /api/v1/settings", json(settings(), etag = "\"s1\""))
            answer("PATCH /api/v1/settings", status(412))
            dashboard.layout()

            assertEquals(DashboardResult.Failed, dashboard.saveLayout(edited))
            assertEquals(2, sent("PATCH").size)
        }

    @Test
    fun `a rejected layout fails, a server error or a failing re-read is offline`() =
        runBlocking {
            answer("GET /api/v1/settings", json(settings(), etag = "\"s1\""))
            dashboard.layout()

            answer("PATCH /api/v1/settings", status(400))
            assertEquals(DashboardResult.Failed, dashboard.saveLayout(edited))
            assertEquals(1, sent("PATCH").size)

            answer("PATCH /api/v1/settings", status(503))
            assertEquals(DashboardResult.Offline, dashboard.saveLayout(edited))

            answer("PATCH /api/v1/settings", status(412))
            answer("GET /api/v1/settings", status(502))
            assertEquals(DashboardResult.Offline, dashboard.saveLayout(edited))
        }

    @Test
    fun `an unreachable server is offline for saving and for the calendars`() =
        runBlocking {
            server.close()
            assertEquals(DashboardResult.Offline, dashboard.saveLayout(edited))
            assertEquals(DashboardResult.Offline, dashboard.calendars())
        }

    @Test
    fun `the stored layout is the one the server answers, not the one sent`() =
        runBlocking {
            answer("GET /api/v1/settings", json(settings(), etag = "\"s1\""))
            answer("PATCH /api/v1/settings", json(settings("weeklyReview", "inbox:hidden"), etag = "\"s2\""))
            dashboard.layout()

            val stored = (dashboard.saveLayout(edited) as DashboardResult.Ok).value

            assertEquals(WidgetSlot(WidgetId.WEEKLY_REVIEW, true), stored[0])
            assertEquals(WidgetSlot(WidgetId.INBOX, false), stored[1])
        }

    private fun calendarJson(
        n: Int,
        name: String,
        readOnly: Boolean,
        sourceType: String,
    ) = """{"id":"${id(n)}","name":"$name","color":"#3366ff","visible":true,"readOnly":$readOnly,"isDefault":false,
        |"sourceId":"${id(80)}","sourceType":"$sourceType","createdAt":"2026-10-01T09:00:00Z","updatedAt":"2026-10-01T09:00:00Z",
        |"version":4}
        """.trimMargin().replace("\n", "")

    @Test
    fun `the calendars are listed with the birthday calendar marked virtual`() =
        runBlocking {
            answer(
                "GET /api/v1/calendar/calendars",
                json(
                    """{"items":[${calendarJson(1, "Personal", false, "LOCAL")},${calendarJson(2, "Birthdays", true, "LOCAL")},""" +
                        """${calendarJson(3, "Holidays", true, "ICS_FEED")}]}""",
                ),
            )

            val calendars = (dashboard.calendars() as DashboardResult.Ok).value

            assertEquals(listOf("Personal", "Birthdays", "Holidays"), calendars.map(CalendarInfo::name))
            assertEquals(listOf(false, true, false), calendars.map(CalendarInfo::virtual))
            assertEquals(listOf(id(1), id(2), id(3)), calendars.map(CalendarInfo::id))
        }

    @Test
    fun `calendars that cannot be read fail or are offline`() =
        runBlocking {
            answer("GET /api/v1/calendar/calendars", status(403))
            assertEquals(DashboardResult.Failed, dashboard.calendars())

            answer("GET /api/v1/calendar/calendars", status(500))
            assertEquals(DashboardResult.Offline, dashboard.calendars())

            answer("GET /api/v1/calendar/calendars", json("""{"items":"none"}"""))
            assertEquals(DashboardResult.Failed, dashboard.calendars())
        }
}
