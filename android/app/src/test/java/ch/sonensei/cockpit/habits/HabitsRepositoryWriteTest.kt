package ch.sonensei.cockpit.habits

import ch.sonensei.cockpit.core.network.generated.apis.HabitsApi
import ch.sonensei.cockpit.core.network.generated.infrastructure.Serializer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
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
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/** Creating, editing, archiving and the archived list against a fake server; the incremental check. */
class HabitsRepositoryWriteTest {
    private val server = MockWebServer()
    private lateinit var habits: HabitsRepository

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private fun habitJson(
        n: Int,
        name: String = "Habit $n",
        archived: Boolean = false,
        reminderTime: String? = null,
        longestStreak: Int = 5,
        version: Long = 3,
    ) = """{"id":"${id(n)}","name":"$name","schedule":"DAILY","targetCount":1,"archived":$archived,
        |"weekdays":[],"timesPerWeek":null,"createdAt":"2026-10-01T09:00:00Z",
        |"updatedAt":"2026-10-20T09:00:00Z","version":$version,
        |"reminderTime":${reminderTime?.let { "\"$it\"" } ?: "null"},
        |"stats":{"today":"2026-10-21","todayCount":0,"dueToday":true,"doneToday":false,"currentStreak":2,
        |"longestStreak":$longestStreak,"streakUnit":"DAYS","doneLast30Days":12,"dueLast30Days":20,"completionRateLast30Days":0.6}}
        """.trimMargin().replace("\n", "")

    private fun json(body: String) =
        MockResponse
            .Builder()
            .code(200)
            .addHeader("Content-Type", "application/json")
            .body(body)
            .build()

    private fun status(code: Int) = MockResponse.Builder().code(code).build()

    private fun RecordedRequest.json(): JsonObject = Json.parseToJsonElement(body!!.utf8()).jsonObject

    private fun habit(
        targetCount: Int = 1,
        todayCount: Int = 0,
        version: Long = 3,
    ) = Habit(
        id(1),
        "Water",
        HabitSchedule.DAILY,
        null,
        emptyList(),
        targetCount,
        LocalDate.of(2026, 10, 21),
        todayCount,
        true,
        todayCount >= targetCount,
        0,
        false,
        null,
        version = version,
    )

    @Before
    fun setUp() {
        server.start()
        val retrofit =
            Retrofit
                .Builder()
                .baseUrl(server.url("/"))
                .addConverterFactory(Serializer.kotlinxSerializationJson.asConverterFactory("application/json".toMediaType()))
                .build()
        habits = HabitsRepository(retrofit.create(HabitsApi::class.java), retrofit.create(HabitsWriteApi::class.java))
    }

    @After
    fun tearDown() = server.close()

    @Test
    fun `create posts a daily habit with a trimmed name and an explicit null reminder`() =
        runBlocking {
            server.enqueue(json(habitJson(1, name = "Read")))

            val created = (habits.create(HabitDraft(name = "  Read  ", targetCount = 2, timesPerWeek = 5)) as HabitsResult.Ok).value

            assertEquals("Read", created.name)
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/v1/habits", request.url.encodedPath)
            assertEquals(
                JsonObject(
                    mapOf(
                        "name" to JsonPrimitive("Read"),
                        "schedule" to JsonPrimitive("DAILY"),
                        "targetCount" to JsonPrimitive(2),
                        "reminderTime" to JsonNull,
                    ),
                ),
                request.json(),
            )
        }

    @Test
    fun `create sends only the fields of the weekday schedule, days in week order, reminder as HH-mm`() =
        runBlocking {
            server.enqueue(json(habitJson(1)))

            habits.create(
                HabitDraft(
                    name = "Piano",
                    schedule = HabitSchedule.WEEKDAYS,
                    weekdays = setOf(DayOfWeek.FRIDAY, DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY),
                    timesPerWeek = 4,
                    reminderTime = LocalTime.of(7, 5),
                ),
            )

            val body = server.takeRequest().json()
            assertEquals(setOf("name", "schedule", "targetCount", "reminderTime", "weekdays"), body.keys)
            assertEquals(JsonPrimitive("WEEKDAYS"), body["schedule"])
            assertEquals(JsonPrimitive("07:05"), body["reminderTime"])
            assertEquals(
                JsonArray(listOf(JsonPrimitive("MONDAY"), JsonPrimitive("WEDNESDAY"), JsonPrimitive("FRIDAY"))),
                body["weekdays"],
            )
        }

    @Test
    fun `create sends only the days a week for the per-week schedule`() =
        runBlocking {
            server.enqueue(json(habitJson(1)))

            habits.create(HabitDraft(name = "Run", schedule = HabitSchedule.TIMES_PER_WEEK, timesPerWeek = 3))

            val body = server.takeRequest().json()
            assertEquals(setOf("name", "schedule", "targetCount", "reminderTime", "timesPerWeek"), body.keys)
            assertEquals(JsonPrimitive(3), body["timesPerWeek"])
            assertEquals(JsonPrimitive("TIMES_PER_WEEK"), body["schedule"])
        }

    @Test
    fun `update patches every editable field with the loaded version as If-Match`() =
        runBlocking {
            server.enqueue(json(habitJson(1, name = "Water daily", version = 8)))

            val updated =
                (
                    habits.update(
                        habit(version = 7),
                        HabitDraft(name = "Water daily", targetCount = 4, reminderTime = LocalTime.of(21, 30)),
                    ) as HabitsResult.Ok
                ).value

            assertEquals(8L, updated.version)
            val request = server.takeRequest()
            assertEquals("PATCH", request.method)
            assertEquals("/api/v1/habits/${id(1)}", request.url.encodedPath)
            assertEquals("\"7\"", request.headers["If-Match"])
            assertEquals(
                JsonObject(
                    mapOf(
                        "name" to JsonPrimitive("Water daily"),
                        "schedule" to JsonPrimitive("DAILY"),
                        "targetCount" to JsonPrimitive(4),
                        "reminderTime" to JsonPrimitive("21:30"),
                    ),
                ),
                request.json(),
            )
        }

    @Test
    fun `archiving patches only the archived flag`() =
        runBlocking {
            server.enqueue(json(habitJson(1, archived = true)))
            server.enqueue(json(habitJson(1, archived = false)))

            val archived = (habits.setArchived(habit(version = 2), true) as HabitsResult.Ok).value
            val restored = (habits.setArchived(habit(version = 3), false) as HabitsResult.Ok).value

            assertEquals(true, archived.archived)
            assertEquals(false, restored.archived)
            val first = server.takeRequest()
            assertEquals("PATCH", first.method)
            assertEquals("\"2\"", first.headers["If-Match"])
            assertEquals(JsonObject(mapOf("archived" to JsonPrimitive(true))), first.json())
            val second = server.takeRequest()
            assertEquals("\"3\"", second.headers["If-Match"])
            assertEquals(JsonObject(mapOf("archived" to JsonPrimitive(false))), second.json())
        }

    @Test
    fun `the archived list asks for archived habits and maps best streak, reminder, archived and version`() =
        runBlocking {
            server.enqueue(
                json("""{"items":[${habitJson(1, archived = true, reminderTime = "07:30:00", longestStreak = 12, version = 9)}]}"""),
            )

            val list = (habits.list(archived = true) as HabitsResult.Ok).value

            assertEquals("true", server.takeRequest().url.queryParameter("archived"))
            val habit = list.single()
            assertEquals(true, habit.archived)
            assertEquals(12, habit.longestStreak)
            assertEquals(LocalTime.of(7, 30), habit.reminderTime)
            assertEquals(9L, habit.version)
        }

    @Test
    fun `a habit without a reminder maps to no reminder`() =
        runBlocking {
            server.enqueue(json("""{"items":[${habitJson(1)}]}"""))

            assertNull((habits.list() as HabitsResult.Ok).value.single().reminderTime)
        }

    @Test
    fun `an unreadable reminder time is a failure without status`() =
        runBlocking {
            server.enqueue(json("""{"items":[${habitJson(1, reminderTime = "half past seven")}]}"""))

            assertEquals(HabitsResult.Failed(null), habits.list())
        }

    @Test
    fun `a tap counts one more check until the target, then resets to none`() {
        assertEquals(1, habit(targetCount = 3, todayCount = 0).nextCount)
        assertEquals(2, habit(targetCount = 3, todayCount = 1).nextCount)
        assertEquals(3, habit(targetCount = 3, todayCount = 2).nextCount)
        assertEquals(0, habit(targetCount = 3, todayCount = 3).nextCount)
        assertEquals(0, habit(targetCount = 3, todayCount = 4).nextCount)
        assertEquals(1, habit(targetCount = 1, todayCount = 0).nextCount)
        assertEquals(0, habit(targetCount = 1, todayCount = 1).nextCount)
    }

    @Test
    fun `a check sends the next count for a habit with a target above one`() =
        runBlocking {
            server.enqueue(json(habitJson(1)))

            habits.check(habit(targetCount = 5, todayCount = 2))

            assertEquals(JsonPrimitive(3), server.takeRequest().json()["count"])
        }

    @Test
    fun `conflicts and refusals of a change are failures with their status, server errors are offline`() =
        runBlocking {
            server.enqueue(status(409))
            assertEquals(HabitsResult.Failed(409), habits.update(habit(), HabitDraft(name = "Water")))
            server.enqueue(status(412))
            assertEquals(HabitsResult.Failed(412), habits.setArchived(habit(), true))
            server.enqueue(status(400))
            assertEquals(HabitsResult.Failed(400), habits.create(HabitDraft(name = "Water")))
            server.enqueue(status(502))
            assertEquals(HabitsResult.Offline, habits.create(HabitDraft(name = "Water")))
            server.close()
            assertEquals(HabitsResult.Offline, habits.setArchived(habit(), true))
        }

    @Test
    fun `the conflict statuses are not found, conflict and precondition failed`() {
        assertEquals(setOf(404, 409, 412), HabitsRepository.CONFLICTS)
    }
}
