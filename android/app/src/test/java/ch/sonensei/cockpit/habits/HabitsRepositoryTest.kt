package ch.sonensei.cockpit.habits

import ch.sonensei.cockpit.core.network.generated.apis.HabitsApi
import ch.sonensei.cockpit.core.network.generated.infrastructure.Serializer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.MediaType.Companion.toMediaType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.UUID

/** The habit calls against a fake server. */
class HabitsRepositoryTest {
    private val server = MockWebServer()
    private lateinit var habits: HabitsRepository

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private fun habitJson(
        n: Int,
        schedule: String = "DAILY",
        target: Int = 1,
        todayCount: Int = 0,
        done: Boolean = false,
        streak: Int = 2,
        unit: String = "DAYS",
        weekdays: String = "[]",
        timesPerWeek: Int? = null,
    ) = """{"id":"${id(n)}","name":"Habit $n","schedule":"$schedule","targetCount":$target,"archived":false,
        |"weekdays":$weekdays,"timesPerWeek":${timesPerWeek ?: "null"},"createdAt":"2026-10-01T09:00:00Z",
        |"updatedAt":"2026-10-20T09:00:00Z","version":3,
        |"stats":{"today":"2026-10-21","todayCount":$todayCount,"dueToday":true,"doneToday":$done,"currentStreak":$streak,
        |"longestStreak":5,"streakUnit":"$unit","doneLast30Days":12,"dueLast30Days":20,"completionRateLast30Days":0.6}}
        """.trimMargin().replace("\n", "")

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
        habits = HabitsRepository(retrofit.create(HabitsApi::class.java))
    }

    @After
    fun tearDown() = server.close()

    @Test
    fun `lists the active habits with their state today`() =
        runBlocking {
            server.enqueue(
                json(
                    """{"items":[${habitJson(1)},${habitJson(
                        2,
                        schedule = "TIMES_PER_WEEK",
                        timesPerWeek = 3,
                        unit = "WEEKS",
                    )},${habitJson(3, schedule = "WEEKDAYS", weekdays = "[\"MONDAY\",\"FRIDAY\"]")}]}""",
                ),
            )

            val list = (habits.list() as HabitsResult.Ok).value

            assertEquals(listOf("Habit 1", "Habit 2", "Habit 3"), list.map { it.name })
            assertEquals(LocalDate.of(2026, 10, 21), list[0].today)
            assertEquals(0.6, list[0].completionLast30Days!!, 0.0)
            assertEquals(false, list[0].streakInWeeks)
            assertEquals(HabitSchedule.TIMES_PER_WEEK, list[1].schedule)
            assertEquals(3, list[1].timesPerWeek)
            assertEquals(true, list[1].streakInWeeks)
            assertEquals(listOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY), list[2].weekdays)
            assertEquals("false", server.takeRequest().url.queryParameter("archived"))
        }

    @Test
    fun `a check-off sends the target count for the server's today`() =
        runBlocking {
            server.enqueue(json(habitJson(1, target = 3, todayCount = 3, done = true, streak = 3)))
            val habit =
                Habit(id(1), "Water", HabitSchedule.DAILY, null, emptyList(), 3, LocalDate.of(2026, 10, 21), 1, true, false, 2, false, 0.5)

            val result = (habits.check(habit, done = true) as HabitsResult.Ok).value

            assertEquals(true, result.doneToday)
            assertEquals(3, result.currentStreak)
            val request = server.takeRequest()
            assertEquals("PUT", request.method)
            assertEquals("/api/v1/habits/${id(1)}/checks/2026-10-21", request.url.encodedPath)
            assertEquals(
                3,
                Json
                    .parseToJsonElement(request.body!!.utf8())
                    .jsonObject["count"]!!
                    .jsonPrimitive.int,
            )
        }

    @Test
    fun `unchecking sends zero`() =
        runBlocking {
            server.enqueue(json(habitJson(1)))
            val habit =
                Habit(id(1), "Water", HabitSchedule.DAILY, null, emptyList(), 1, LocalDate.of(2026, 10, 21), 1, true, true, 3, false, 0.5)

            habits.check(habit, done = false)

            assertEquals(
                0,
                Json
                    .parseToJsonElement(server.takeRequest().body!!.utf8())
                    .jsonObject["count"]!!
                    .jsonPrimitive.int,
            )
        }

    @Test
    fun `server errors and no connection are offline, refusals are failures`() =
        runBlocking {
            server.enqueue(status(503))
            assertEquals(HabitsResult.Offline, habits.list())
            server.enqueue(status(404))
            assertEquals(HabitsResult.Failed(404), habits.list())
            server.close()
            assertEquals(HabitsResult.Offline, habits.list())
        }
}
