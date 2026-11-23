package ch.sonensei.cockpit.gtd.lists

import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.core.network.generated.apis.TasksApi
import ch.sonensei.cockpit.core.network.generated.infrastructure.Serializer
import ch.sonensei.cockpit.gtd.data.GtdHarness
import ch.sonensei.cockpit.gtd.data.RoutingDispatcher
import ch.sonensei.cockpit.gtd.data.TASKS
import ch.sonensei.cockpit.gtd.data.json
import ch.sonensei.cockpit.gtd.data.status
import ch.sonensei.cockpit.gtd.data.taskJson
import ch.sonensei.cockpit.gtd.data.taskPage
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockWebServer
import okhttp3.MediaType.Companion.toMediaType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.time.LocalDate

/** The Tickler data: the next years read a year at a time from `GET /tasks?from=&to=`, only pending tickler dates kept. */
@RunWith(AndroidJUnit4::class)
class TicklerRepositoryTest {
    private val dispatcher = RoutingDispatcher()
    private val server = MockWebServer().apply { this.dispatcher = this@TicklerRepositoryTest.dispatcher }
    private lateinit var harness: GtdHarness
    private lateinit var repository: TaskListsRepository

    private val today = LocalDate.of(2026, 11, 23)

    @Before
    fun setUp() {
        server.start()
        harness = GtdHarness(server, dispatcher)
        val api =
            Retrofit
                .Builder()
                .baseUrl(server.url("/"))
                .addConverterFactory(Serializer.kotlinxSerializationJson.asConverterFactory("application/json".toMediaType()))
                .build()
                .create(TasksApi::class.java)
        repository = TaskListsRepository(api, harness.connectivity, harness.triggers, harness.tasks)
    }

    @After
    fun tearDown() {
        harness.close()
        server.close()
    }

    private fun tickler(
        n: Int,
        title: String,
        date: String,
    ) = taskJson(n, title, "INBOX", ""","ticklerDate":"$date"""")

    /** Answers the remaining yearly requests with empty pages. */
    private fun emptyYears(count: Int) = repeat(count) { dispatcher.enqueue(TASKS, json(taskPage())) }

    private suspend fun load() =
        withTimeout(TIMEOUT) {
            repository.observeTickler({ today }, MutableSharedFlow()).first { it.data != null || it.error != null }
        }

    private fun taskRequests() = dispatcher.requests.filter { it.url.encodedPath == TASKS }

    @Test
    fun `asks for the next ten years, one gap-free year per request starting tomorrow`() =
        runBlocking {
            emptyYears(TICKLER_HORIZON_YEARS)
            val live = load()
            assertEquals(emptyList<Any>(), live.data)
            val windows = taskRequests().map { it.url.queryParameter("from") to it.url.queryParameter("to") }
            assertEquals(10, TICKLER_HORIZON_YEARS)
            assertEquals(TICKLER_HORIZON_YEARS, windows.size)
            assertEquals("2026-11-24" to "2027-11-23", windows.first())
            assertEquals("2027-11-24" to "2028-11-23", windows[1])
            assertEquals("2035-11-24" to "2036-11-23", windows.last())
            windows.zipWithNext().forEach { (a, b) ->
                assertEquals(LocalDate.parse(a.second).plusDays(1), LocalDate.parse(b.first))
            }
            taskRequests().forEach { assertNull(it.url.queryParameter("list")) }
        }

    @Test
    fun `keeps only tasks with a tickler date after today, carries the date, and drops duplicates`() =
        runBlocking {
            dispatcher.enqueue(
                TASKS,
                json(
                    taskPage(
                        tickler(1, "Renew passport", "2026-12-14"),
                        tickler(2, "Came back today", "2026-11-23"),
                        tickler(3, "Came back earlier", "2026-11-01"),
                        taskJson(4, "Due without tickler", "NEXT", ""","dueDate":"2026-12-01""""),
                    ),
                ),
            )
            // The same task again in a later window (e.g. due next year, tickler this year) is listed once.
            dispatcher.enqueue(TASKS, json(taskPage(tickler(1, "Renew passport", "2026-12-14"), tickler(5, "Plan trip", "2028-03-01"))))
            emptyYears(TICKLER_HORIZON_YEARS - 2)
            val tasks = load().data!!
            assertEquals(listOf("Renew passport", "Plan trip"), tasks.map { it.title })
            assertEquals(listOf(LocalDate.of(2026, 12, 14), LocalDate.of(2028, 3, 1)), tasks.map { it.ticklerDate })
        }

    @Test
    fun `follows the next page within a year`() =
        runBlocking {
            dispatcher.enqueue(TASKS, json(taskPage(tickler(1, "Renew passport", "2026-12-14"), nextCursor = "page-2")))
            dispatcher.enqueue(TASKS, json(taskPage(tickler(2, "Plan trip", "2027-01-10"))))
            emptyYears(TICKLER_HORIZON_YEARS - 1)
            assertEquals(listOf("Renew passport", "Plan trip"), load().data!!.map { it.title })
            assertEquals("page-2", taskRequests()[1].url.queryParameter("cursor"))
        }

    @Test
    fun `a failing year makes the whole load an error, not a partial list`() =
        runBlocking {
            dispatcher.enqueue(TASKS, json(taskPage(tickler(1, "Renew passport", "2026-12-14"))))
            dispatcher.enqueue(TASKS, status(503))
            val live = load()
            assertNull(live.data)
            assertEquals(LoadError.OFFLINE, live.error)
        }

    private companion object {
        const val TIMEOUT = 10_000L
    }
}
