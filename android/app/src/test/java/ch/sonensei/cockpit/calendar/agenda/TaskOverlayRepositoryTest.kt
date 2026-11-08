package ch.sonensei.cockpit.calendar.agenda

import ch.sonensei.cockpit.core.network.generated.apis.TasksApi
import ch.sonensei.cockpit.core.network.generated.infrastructure.Serializer
import ch.sonensei.cockpit.gtd.data.Energy
import ch.sonensei.cockpit.gtd.data.TaskList
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.MediaType.Companion.toMediaType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.time.LocalDate
import java.util.UUID

/** The overlay's task calls against a fake server: ranges and paging asked for, filtering, and failures. */
class TaskOverlayRepositoryTest {
    private val server = MockWebServer()
    private lateinit var repository: TaskOverlayRepository

    private val from = LocalDate.parse("2026-10-19")
    private val to = LocalDate.parse("2026-10-26")

    /** A today outside every range asked for: no `scheduled=today` call. */
    private val notShown = LocalDate.parse("2030-01-01")

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private fun taskJson(
        n: Int,
        list: String = "NEXT",
        due: String? = null,
        scheduled: String? = null,
        tickler: String? = null,
        completedAt: String? = null,
        extra: String = "",
        releasedOn: String? = null,
    ): String {
        val optional =
            listOfNotNull(
                releasedOn?.let { "\"releasedOn\":\"$it\"" },
                due?.let { "\"dueDate\":\"$it\"" },
                scheduled?.let { "\"scheduledDate\":\"$it\"" },
                tickler?.let { "\"ticklerDate\":\"$it\"" },
                completedAt?.let { "\"completedAt\":\"$it\"" },
            ).joinToString("") { ",$it" }
        return """{"id":"${id(n)}","title":"Task $n","list":"$list","position":$n,"version":2,"contextIds":[],
            |"createdAt":"2026-10-01T09:00:00Z","updatedAt":"2026-10-01T09:00:00Z"$optional$extra}
            """.trimMargin().replace("\n", "")
    }

    private fun page(
        vararg tasks: String,
        next: String? = null,
    ) = MockResponse
        .Builder()
        .code(200)
        .addHeader("Content-Type", "application/json")
        .body("""{"items":[${tasks.joinToString(",")}]${next?.let { ",\"nextCursor\":\"$it\"" } ?: ""}}""")
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
        repository = TaskOverlayRepository(retrofit.create(TasksApi::class.java))
    }

    @After
    fun tearDown() = server.close()

    @Test
    fun `due and tickler tasks come from the inclusive date filter, scheduled ones from the Scheduled list by date`() =
        runBlocking {
            server.enqueue(page(taskJson(1, due = "2026-10-19"), taskJson(2, list = "SOMEDAY", tickler = "2026-10-25")))
            server.enqueue(
                page(
                    taskJson(3, list = "SCHEDULED", scheduled = "2026-10-19"),
                    taskJson(4, list = "SCHEDULED", scheduled = "2026-10-25"),
                    // Outside the range: the day before, the exclusive end, and without a date.
                    taskJson(5, list = "SCHEDULED", scheduled = "2026-10-18"),
                    taskJson(6, list = "SCHEDULED", scheduled = "2026-10-26"),
                    taskJson(7, list = "SCHEDULED"),
                ),
            )

            val result = repository.tasks(from, to, notShown)

            val ranged = server.takeRequest().url
            assertEquals("/api/v1/tasks", ranged.encodedPath)
            assertEquals("2026-10-19", ranged.queryParameter("from"))
            // The API's `to` is inclusive: the last day of the range.
            assertEquals("2026-10-25", ranged.queryParameter("to"))
            assertEquals("200", ranged.queryParameter("limit"))
            assertNull(ranged.queryParameter("list"))
            assertNull(ranged.queryParameter("cursor"))
            val scheduled = server.takeRequest().url
            assertEquals("SCHEDULED", scheduled.queryParameter("list"))
            assertNull(scheduled.queryParameter("from"))
            assertEquals(2, server.requestCount)

            assertEquals(listOf(id(1), id(2), id(3), id(4)), (result as CalendarResult.Ok).value.map { it.id })
        }

    @Test
    fun `pages are followed by their cursor, completed tasks dropped and duplicates merged`() =
        runBlocking {
            server.enqueue(page(taskJson(1, due = "2026-10-20"), next = "c2"))
            server.enqueue(page(taskJson(2, due = "2026-10-21", completedAt = "2026-10-20T10:00:00Z"), next = "c3"))
            server.enqueue(page(taskJson(3, list = "SCHEDULED", due = "2026-10-22", scheduled = "2026-10-22")))
            server.enqueue(page(taskJson(3, list = "SCHEDULED", due = "2026-10-22", scheduled = "2026-10-22"), next = "s2"))
            server.enqueue(page(taskJson(4, list = "SCHEDULED", scheduled = "2026-10-23")))

            val result = repository.tasks(from, to, notShown)

            val cursors = (1..5).map { server.takeRequest().url.queryParameter("cursor") }
            assertEquals(listOf(null, "c2", "c3", null, "s2"), cursors)
            assertEquals(listOf(id(1), id(3), id(4)), (result as CalendarResult.Ok).value.map { it.id })
        }

    @Test
    fun `a range longer than a year is asked for in parts of at most 366 days`() =
        runBlocking {
            server.enqueue(page(taskJson(1, due = "2026-03-01")))
            server.enqueue(page(taskJson(2, due = "2027-03-01")))
            server.enqueue(page())

            val result = repository.tasks(LocalDate.parse("2026-01-01"), LocalDate.parse("2027-06-01"), notShown)

            val first = server.takeRequest().url
            assertEquals("2026-01-01" to "2027-01-01", first.queryParameter("from") to first.queryParameter("to"))
            val second = server.takeRequest().url
            assertEquals("2027-01-02" to "2027-05-31", second.queryParameter("from") to second.queryParameter("to"))
            assertEquals("SCHEDULED", server.takeRequest().url.queryParameter("list"))
            assertEquals(listOf(id(1), id(2)), (result as CalendarResult.Ok).value.map { it.id })
        }

    @Test
    fun `an empty range asks only for the Scheduled list`() =
        runBlocking {
            server.enqueue(page(taskJson(1, list = "SCHEDULED", scheduled = "2026-10-19")))

            val result = repository.tasks(from, from, notShown)

            assertEquals("SCHEDULED", server.takeRequest().url.queryParameter("list"))
            assertEquals(1, server.requestCount)
            assertEquals(emptyList<OverlayTask>(), (result as CalendarResult.Ok).value)
        }

    @Test
    fun `paging stops after 20 pages`() =
        runBlocking {
            repeat(20) { n -> server.enqueue(page(taskJson(n + 1, due = "2026-10-20"), next = "c${n + 2}")) }
            server.enqueue(page())

            val result = repository.tasks(from, to, notShown)

            assertEquals(20, (result as CalendarResult.Ok).value.size)
            // 20 pages of the range, then the Scheduled list: the 21st cursor is not followed.
            assertEquals(21, server.requestCount)
        }

    @Test
    fun `server errors and an unreachable server are offline, refusals and bad answers failed`() =
        runBlocking {
            server.enqueue(status(503))
            assertEquals(CalendarResult.Offline, repository.tasks(from, to, notShown))

            server.enqueue(page())
            server.enqueue(status(500))
            assertEquals(CalendarResult.Offline, repository.tasks(from, to, notShown))

            server.enqueue(status(404))
            assertEquals(CalendarResult.Failed(404), repository.tasks(from, to, notShown))

            server.enqueue(status(401))
            assertEquals(CalendarResult.Failed(401), repository.tasks(from, to, notShown))

            server.enqueue(page("""{"not":"a task"}"""))
            assertEquals(CalendarResult.Failed(null), repository.tasks(from, to, notShown))

            server.enqueue(page(taskJson(1, list = "ARCHIVED", due = "2026-10-20")))
            assertEquals(CalendarResult.Failed(null), repository.tasks(from, to, notShown))

            server.close()
            assertEquals(CalendarResult.Offline, repository.tasks(from, to, notShown))
        }

    @Test
    fun `an answer without a page or with an impossible date is failed`() =
        runBlocking {
            server.enqueue(status(204))
            assertEquals(CalendarResult.Failed(204), repository.tasks(from, to, notShown))

            server.enqueue(page(taskJson(1, due = "2026-02-30")))
            assertEquals(CalendarResult.Failed(null), repository.tasks(from, to, notShown))
        }

    @Test
    fun `the task carries a snapshot for the detail screen`() =
        runBlocking {
            server.enqueue(
                page(taskJson(1, due = "2026-10-20", extra = ""","dueTime":"14:30:00","energy":"HIGH","notesMd":"Fictitious note"""")),
            )
            server.enqueue(page())

            val task = (repository.tasks(from, to, notShown) as CalendarResult.Ok).value.single()

            assertEquals("14:30:00", task.dueTime)
            val snapshot = task.snapshot!!
            assertEquals(id(1), snapshot.id)
            assertEquals("Task 1", snapshot.title)
            assertEquals(TaskList.NEXT, snapshot.list)
            assertEquals(1, snapshot.position)
            assertEquals(2L, snapshot.version)
            assertEquals(LocalDate.parse("2026-10-20"), snapshot.dueDate)
            assertEquals(Energy.HIGH, snapshot.energy)
            assertEquals("Fictitious note", snapshot.notesMd)
        }

    @Test
    fun `with today in the range the tasks released to Next today come from the scheduled=today filter`() =
        runBlocking {
            val today = LocalDate.parse("2026-10-21")
            server.enqueue(page(taskJson(1, due = "2026-10-20")))
            server.enqueue(page())
            server.enqueue(
                page(
                    taskJson(2, releasedOn = "2026-10-21"),
                    // Not released today (still scheduled for an earlier day): not a mark of today.
                    taskJson(3, list = "SCHEDULED", scheduled = "2026-10-20"),
                    taskJson(4, releasedOn = "2026-10-21", completedAt = "2026-10-21T09:00:00Z"),
                ),
            )

            val result = repository.tasks(from, to, today)

            server.takeRequest()
            server.takeRequest()
            val released = server.takeRequest().url
            // The server reads the filter case-insensitively, like the list names.
            assertEquals("today", released.queryParameter("scheduled")?.lowercase())
            assertNull(released.queryParameter("list"))
            assertNull(released.queryParameter("from"))
            assertEquals(3, server.requestCount)
            val tasks = (result as CalendarResult.Ok).value
            assertEquals(listOf(id(1), id(2)), tasks.map { it.id })
            assertEquals(today, tasks[1].releasedOn)
        }

    @Test
    fun `today on the first day of the range asks for the released tasks, today on the exclusive end or before does not`() =
        runBlocking {
            // Today is the range's first day: included.
            server.enqueue(page())
            server.enqueue(page())
            server.enqueue(page(taskJson(1, releasedOn = "2026-10-19")))
            val first = repository.tasks(from, to, from)
            assertEquals(3, server.requestCount)
            assertEquals(listOf(id(1)), (first as CalendarResult.Ok).value.map { it.id })

            // Today is the exclusive end (the range is in the past): no third request.
            server.enqueue(page())
            server.enqueue(page())
            val end = repository.tasks(from, to, to)
            assertEquals(5, server.requestCount)
            assertEquals(emptyList<UUID>(), (end as CalendarResult.Ok).value.map { it.id })

            // Today is before the range (a future week): no third request either.
            server.enqueue(page())
            server.enqueue(page())
            repository.tasks(from, to, from.minusDays(1))
            assertEquals(7, server.requestCount)
            // The released-tasks filter was asked for exactly once, as the first call's third request.
            val scheduledFilters = (1..7).map { server.takeRequest().url.queryParameter("scheduled") }
            assertEquals(listOf(null, null, "today", null, null, null, null), scheduledFilters.map { it?.lowercase() })
        }

    @Test
    fun `a failing released-today request fails the whole overlay load`() =
        runBlocking {
            val today = LocalDate.parse("2026-10-21")
            server.enqueue(page(taskJson(1, due = "2026-10-20")))
            server.enqueue(page())
            server.enqueue(status(503))
            assertEquals(CalendarResult.Offline, repository.tasks(from, to, today))

            server.enqueue(page(taskJson(1, due = "2026-10-20")))
            server.enqueue(page())
            server.enqueue(status(403))
            assertEquals(CalendarResult.Failed(403), repository.tasks(from, to, today))
        }
}
