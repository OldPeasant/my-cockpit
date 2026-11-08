package ch.sonensei.cockpit.dashboard

import ch.sonensei.cockpit.core.network.generated.apis.BookmarksApi
import ch.sonensei.cockpit.core.network.generated.apis.CalendarsApi
import ch.sonensei.cockpit.core.network.generated.apis.HabitsApi
import ch.sonensei.cockpit.core.network.generated.apis.NotesApi
import ch.sonensei.cockpit.core.network.generated.apis.ProjectsApi
import ch.sonensei.cockpit.core.network.generated.apis.SettingsApi
import ch.sonensei.cockpit.core.network.generated.apis.TasksApi
import ch.sonensei.cockpit.core.network.generated.apis.WeeklyReviewApi
import ch.sonensei.cockpit.core.network.generated.infrastructure.Serializer
import ch.sonensei.cockpit.gtd.data.taskJson
import ch.sonensei.cockpit.gtd.data.taskPage
import ch.sonensei.cockpit.mail.list.MailApi
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.MediaType.Companion.toMediaType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Collections
import java.util.UUID

/** The dashboard calls against a fake server: what is asked, what each widget makes of it, and how failures stay per widget. */
class DashboardRepositoryTest {
    private val server = MockWebServer()
    private lateinit var dashboard: DashboardRepository

    /** 2026-10-21 10:00 in Zurich (UTC+2). */
    private val clock = Clock.fixed(Instant.parse("2026-10-21T08:00:00Z"), ZoneId.of("Europe/Zurich"))

    /** Answers by path and query (the calls of a widget run concurrently); anything else is a 404. */
    private val answers = Collections.synchronizedMap(mutableMapOf<String, MockResponse>())
    private val requests = Collections.synchronizedList(mutableListOf<RecordedRequest>())

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private fun json(body: String) =
        MockResponse
            .Builder()
            .code(200)
            .addHeader("Content-Type", "application/json")
            .body(body)
            .build()

    private fun status(code: Int) = MockResponse.Builder().code(code).build()

    private fun answer(
        pathAndQuery: String,
        response: MockResponse,
    ) {
        answers[normalized(pathAndQuery)] = response
    }

    /** Path and query with the query parameters sorted, so the order the client sends them in does not matter. */
    private fun normalized(pathAndQuery: String): String {
        val path = pathAndQuery.substringBefore('?')
        val query =
            pathAndQuery
                .substringAfter('?', "")
                .split('&')
                .filter { it.isNotEmpty() }
                .sorted()
        return if (query.isEmpty()) path else "$path?${query.joinToString("&")}"
    }

    private fun occurrenceJson(
        n: Int,
        start: String,
        status: String = "CONFIRMED",
    ) = """{"eventId":"${id(n)}","calendarId":"${id(90)}","title":"Event $n","allDay":false,"start":"$start",
        |"status":"$status","transparency":"OPAQUE","version":1}
        """.trimMargin().replace("\n", "")

    private fun habitJson(
        n: Int,
        due: Boolean,
        done: Boolean,
    ) = """{"id":"${id(n)}","name":"Habit $n","schedule":"DAILY","targetCount":1,"archived":false,"weekdays":[],
        |"createdAt":"2026-10-01T09:00:00Z","updatedAt":"2026-10-20T09:00:00Z","version":3,
        |"stats":{"today":"2026-10-21","todayCount":0,"dueToday":$due,"doneToday":$done,"currentStreak":0,
        |"longestStreak":5,"streakUnit":"DAYS","doneLast30Days":12,"dueLast30Days":20,"completionRateLast30Days":0.6}}
        """.trimMargin().replace("\n", "")

    private fun mailJson(
        n: Int,
        seen: Boolean,
    ) = """{"id":"${id(n)}","subject":"Mail $n","seen":$seen}"""

    private fun ok(widget: WidgetId): WidgetData =
        when (val result = runBlocking { dashboard.load(widget) }) {
            is DashboardResult.Ok -> result.value
            else -> throw AssertionError("$widget: $result after ${paths()}")
        }

    private fun paths(): List<String> =
        synchronized(requests) {
            requests.toList()
        }.map { "${it.url.encodedPath}?${it.url.encodedQuery.orEmpty()}" }

    @Before
    fun setUp() {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += request
                    val key = normalized("${request.url.encodedPath}?${request.url.encodedQuery.orEmpty()}")
                    return answers[key] ?: answers[request.url.encodedPath] ?: status(404)
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
                clock,
            )
    }

    @After
    fun tearDown() = server.close()

    @Test
    fun `the layout comes from the dashboardWidgets setting`() =
        runBlocking {
            answer("/api/v1/settings", json("""{"theme":"dark","dashboardWidgets":["inbox","today:hidden","pinnedNotes"]}"""))

            val layout = (dashboard.layout() as DashboardResult.Ok).value.shown()

            assertEquals(
                listOf(WidgetId.INBOX, WidgetId.PINNED_NOTES) +
                    WidgetId.entries.filter { it !in setOf(WidgetId.INBOX, WidgetId.TODAY, WidgetId.PINNED_NOTES) },
                layout,
            )
        }

    @Test
    fun `settings without a layout give every widget, and an unreachable server is offline`() =
        runBlocking {
            answer("/api/v1/settings", json("""{"theme":"dark"}"""))
            assertEquals(DashboardResult.Ok(DEFAULT_LAYOUT), dashboard.layout())

            answer("/api/v1/settings", status(503))
            assertEquals(DashboardResult.Offline, dashboard.layout())
        }

    @Test
    fun `today merges overdue and due-today tasks overdue first, local-day events and the due habits`() {
        answer(
            "/api/v1/tasks?due=OVERDUE&completed=false&limit=50",
            json(taskPage(taskJson(1, "Overdue", "NEXT", ""","dueDate":"2026-10-19""""), taskJson(2, "Both", "NEXT"))),
        )
        answer(
            "/api/v1/tasks?due=TODAY&completed=false&limit=50",
            json(taskPage(taskJson(2, "Both", "NEXT"), taskJson(3, "Due today", "NEXT"))),
        )
        answer(
            "/api/v1/calendar/events",
            json(
                """{"items":[${occurrenceJson(4, "2026-10-21T12:00:00Z")},${occurrenceJson(5, "2026-10-21T13:00:00Z", "CANCELLED")}]}""",
            ),
        )
        answer(
            "/api/v1/habits?archived=false",
            json(
                """{"items":[${habitJson(
                    6,
                    due = true,
                    done = true,
                )},${habitJson(7, due = true, done = false)},${habitJson(8, due = false, done = false)}]}""",
            ),
        )

        val today = ok(WidgetId.TODAY) as WidgetData.Today

        assertEquals(listOf("Overdue", "Both", "Due today"), today.tasks.map { it.title })
        assertEquals(LocalDate.parse("2026-10-19"), today.tasks.first().dueDate)
        assertEquals(listOf("Event 4"), today.events.map { it.title })
        assertEquals(Instant.parse("2026-10-21T12:00:00Z"), today.events.single().start)
        assertEquals(2, today.habitsDue)
        assertEquals(1, today.habitsDone)
        val events = requests.single { it.url.encodedPath == "/api/v1/calendar/events" }.url
        // From local midnight to the next one, in the user's zone.
        assertEquals("2026-10-20T22:00Z", events.queryParameter("from")?.let { normalize(it) })
        assertEquals("2026-10-21T22:00Z", events.queryParameter("to")?.let { normalize(it) })
    }

    /** The offset form the client sends (`+02:00` or `Z`) as UTC minutes. */
    private fun normalize(value: String) =
        java.time.OffsetDateTime
            .parse(value)
            .toInstant()
            .toString()
            .removeSuffix(":00Z") + "Z"

    @Test
    fun `upcoming looks from now to the end of the seventh local day`() {
        answer("/api/v1/calendar/events", json("""{"items":[${occurrenceJson(4, "2026-10-23T07:00:00Z")}]}"""))

        val upcoming = ok(WidgetId.UPCOMING) as WidgetData.Upcoming

        assertEquals(LocalDate.parse("2026-10-23"), upcoming.events.single().date)
        val url = requests.single().url
        assertEquals("2026-10-21T08:00Z", normalize(url.queryParameter("from")!!))
        assertEquals("2026-10-27T23:00Z", normalize(url.queryParameter("to")!!))
    }

    @Test
    fun `the inbox counts inbox tasks and unread mail of the unified inbox, cut at the page size`() {
        answer("/api/v1/tasks?list=INBOX&completed=false&limit=50", json(taskPage(taskJson(1, "A"), taskJson(2, "B"), nextCursor = "c")))
        answer(
            "/api/v1/mail/messages?limit=50",
            json("""{"items":[${mailJson(3, seen = false)},${mailJson(4, seen = true)}],"nextCursor":"m"}"""),
        )

        assertEquals(WidgetData.Inbox(Count(2, more = true), Count(1, more = true)), ok(WidgetId.INBOX))
    }

    @Test
    fun `no unread mail on a cut page is 0, never 0+`() {
        answer("/api/v1/tasks?list=INBOX&completed=false&limit=50", json(taskPage()))
        answer("/api/v1/mail/messages?limit=50", json("""{"items":[${mailJson(4, seen = true)}],"nextCursor":"m"}"""))

        assertEquals(WidgetData.Inbox(Count(0, more = false), Count(0, more = false)), ok(WidgetId.INBOX))
    }

    @Test
    fun `the list widgets ask the web dashboard's queries`() {
        answer(
            "/api/v1/tasks?list=WAITING&due=OVERDUE&completed=false&limit=50",
            json(taskPage(taskJson(1, "Reply from the landlord", "WAITING"))),
        )
        answer(
            "/api/v1/projects?status=ACTIVE&stalled=true&limit=50",
            json(
                """{"items":[{"id":"${id(2)}","title":"Move flat","status":"ACTIVE","stalled":true,""" +
                    """"createdAt":"2026-10-01T09:00:00Z","updatedAt":"2026-10-01T09:00:00Z","version":1}]}""",
            ),
        )
        answer(
            "/api/v1/bookmarks?readLater=true&read=false&limit=50",
            json(
                """{"items":[{"id":"${id(3)}","url":"https://www.example.org/3","displayTitle":"Page 3","tags":[],""" +
                    """"readLater":true,"metadataStatus":"FETCHED","createdAt":"2026-10-01T09:00:00Z",""" +
                    """"updatedAt":"2026-10-01T09:00:00Z","version":1}],"nextCursor":"b"}""",
            ),
        )
        answer(
            "/api/v1/notes?pinned=true&limit=50",
            json(
                """{"items":[{"id":"${id(4)}","displayTitle":"Packing list","bodyMd":"Socks","pinned":true,"archived":false,""" +
                    """"tags":[],"createdAt":"2026-10-01T09:00:00Z","updatedAt":"2026-10-01T09:00:00Z","version":1}]}""",
            ),
        )

        assertEquals(listOf("Reply from the landlord"), (ok(WidgetId.WAITING_OVERDUE) as WidgetData.WaitingOverdue).tasks.map { it.title })
        assertEquals(WidgetData.StalledProjects(listOf(DashboardItem(id(2), "Move flat"))), ok(WidgetId.STALLED_PROJECTS))
        assertEquals(
            WidgetData.ReadLater(listOf(DashboardItem(id(3), "Page 3", "https://www.example.org/3")), more = true),
            ok(WidgetId.READ_LATER),
        )
        assertEquals(WidgetData.PinnedNotes(listOf(DashboardItem(id(4), "Packing list")), more = false), ok(WidgetId.PINNED_NOTES))
    }

    @Test
    fun `the weekly review widget has the days since the last review and the open step`() {
        answer(
            "/api/v1/weekly-reviews/status",
            json(
                """{"current":{"id":"${id(9)}","step":"WAITING_FOR","stepNumber":4,"startedAt":"2026-10-20T18:00:00Z",""" +
                    """"createdAt":"2026-10-20T18:00:00Z","updatedAt":"2026-10-20T18:30:00Z","version":3},"daysSinceLastReview":6}""",
            ),
        )
        assertEquals(WidgetData.WeeklyReview(6, 4), ok(WidgetId.WEEKLY_REVIEW))

        answer("/api/v1/weekly-reviews/status", json("""{"current":null,"daysSinceLastReview":null}"""))
        assertEquals(WidgetData.WeeklyReview(null, null), ok(WidgetId.WEEKLY_REVIEW))
    }

    @Test
    fun `a failing call fails only its widget`() =
        runBlocking {
            answer("/api/v1/notes?pinned=true&limit=50", status(500))
            answer("/api/v1/bookmarks?readLater=true&read=false&limit=50", status(403))
            answer("/api/v1/projects?status=ACTIVE&stalled=true&limit=50", json("""{"items":"no list"}"""))
            answer("/api/v1/weekly-reviews/status", json("""{"current":null,"daysSinceLastReview":2}"""))

            assertEquals(DashboardResult.Offline, dashboard.load(WidgetId.PINNED_NOTES))
            assertEquals(DashboardResult.Failed, dashboard.load(WidgetId.READ_LATER))
            assertEquals(DashboardResult.Failed, dashboard.load(WidgetId.STALLED_PROJECTS))
            assertEquals(DashboardResult.Ok(WidgetData.WeeklyReview(2, null)), dashboard.load(WidgetId.WEEKLY_REVIEW))
        }

    @Test
    fun `one failing call of today fails the today widget`() =
        runBlocking {
            answer("/api/v1/tasks?due=OVERDUE&completed=false&limit=50", json(taskPage()))
            answer("/api/v1/tasks?due=TODAY&completed=false&limit=50", json(taskPage()))
            answer("/api/v1/calendar/events", json("""{"items":[]}"""))
            answer("/api/v1/habits?archived=false", status(404))

            assertEquals(DashboardResult.Failed, dashboard.load(WidgetId.TODAY))
            // The failing habits call cancels the other three, which may or may not have reached the server by then.
            assertTrue(paths().toString(), paths().any { it.startsWith("/api/v1/habits?") })
            assertTrue(paths().toString(), paths().size <= 4)
        }

    @Test
    fun `all-day events have no time and keep their date, timed events fall on their local day`() {
        val allDay =
            """{"eventId":"${id(4)}","calendarId":"${id(90)}","title":"Holiday","allDay":true,"startDate":"2026-10-24",""" +
                """"endDate":"2026-10-25","occurrence":"2026-10-24","status":"CONFIRMED","transparency":"TRANSPARENT","version":1}"""
        // 23:30 UTC is already the next day in Zurich (UTC+2).
        answer("/api/v1/calendar/events", json("""{"items":[$allDay,${occurrenceJson(5, "2026-10-22T23:30:00Z")}]}"""))

        val events = (ok(WidgetId.UPCOMING) as WidgetData.Upcoming).events

        assertEquals(
            DashboardEvent("${id(4)}:2026-10-24", "Holiday", LocalDate.parse("2026-10-24"), null),
            events[0].copy(occurrence = null),
        )
        assertEquals(LocalDate.parse("2026-10-23"), events[1].date)
        assertEquals(Instant.parse("2026-10-22T23:30:00Z"), events[1].start)
        assertEquals("${id(5)}:2026-10-23", events[1].key)
    }

    @Test
    fun `an occurrence without any start fails its widget`() =
        runBlocking {
            answer(
                "/api/v1/calendar/events",
                json(
                    """{"items":[{"eventId":"${id(4)}","calendarId":"${id(90)}","title":"Broken","allDay":true,""" +
                        """"status":"CONFIRMED","transparency":"OPAQUE","version":1}]}""",
                ),
            )
            assertEquals(DashboardResult.Failed, dashboard.load(WidgetId.UPCOMING))
        }

    @Test
    fun `a failing mail call fails the inbox widget`() =
        runBlocking {
            answer("/api/v1/tasks?list=INBOX&completed=false&limit=50", json(taskPage(taskJson(1, "A"))))
            answer("/api/v1/mail/messages?limit=50", status(502))
            assertEquals(DashboardResult.Offline, dashboard.load(WidgetId.INBOX))

            answer("/api/v1/mail/messages?limit=50", status(400))
            assertEquals(DashboardResult.Failed, dashboard.load(WidgetId.INBOX))
        }

    @Test
    fun `a failing inbox task call fails the inbox widget even when mail answers`() =
        runBlocking {
            answer("/api/v1/tasks?list=INBOX&completed=false&limit=50", status(401))
            answer("/api/v1/mail/messages?limit=50", json("""{"items":[${mailJson(3, seen = false)}]}"""))
            assertEquals(DashboardResult.Failed, dashboard.load(WidgetId.INBOX))
        }

    @Test
    fun `an empty success body is a failed widget`() =
        runBlocking {
            answer("/api/v1/weekly-reviews/status", MockResponse.Builder().code(204).build())
            assertEquals(DashboardResult.Failed, dashboard.load(WidgetId.WEEKLY_REVIEW))
        }

    @Test
    fun `an unknown review step is a failed widget, not a crash`() =
        runBlocking {
            answer(
                "/api/v1/weekly-reviews/status",
                json(
                    """{"current":{"id":"${id(9)}","step":"MEDITATION","stepNumber":9,"startedAt":"2026-10-20T18:00:00Z",""" +
                        """"createdAt":"2026-10-20T18:00:00Z","updatedAt":"2026-10-20T18:30:00Z","version":3},"daysSinceLastReview":6}""",
                ),
            )
            assertEquals(DashboardResult.Failed, dashboard.load(WidgetId.WEEKLY_REVIEW))
        }

    @Test
    fun `an unreachable server is offline for the layout and every widget`() =
        runBlocking {
            server.close()
            assertEquals(DashboardResult.Offline, dashboard.layout())
            WidgetId.entries.forEach { assertEquals(it.name, DashboardResult.Offline, dashboard.load(it)) }
        }
}
