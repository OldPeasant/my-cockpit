package ch.sonensei.cockpit.journal.habits

import ch.sonensei.cockpit.core.auth.WithCockpitUser
import ch.sonensei.cockpit.core.events.domain.ChangeEvent
import ch.sonensei.cockpit.core.events.domain.ChangeOp
import ch.sonensei.cockpit.core.items.domain.ItemResolverRegistry
import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.core.persistence.MutableClock
import ch.sonensei.cockpit.core.persistence.MutableClockTestConfiguration
import ch.sonensei.cockpit.core.persistence.UuidV7
import ch.sonensei.cockpit.core.reminders.domain.ReminderService
import ch.sonensei.cockpit.core.settings.domain.SettingsService
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.event.ApplicationEvents
import org.springframework.test.context.event.RecordApplicationEvents
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** `/api/v1/habits` over HTTP (SPEC.md §8.5), against Postgres, with the test clock (Europe/Zurich, weeks from Monday). */
@SpringBootTest
@AutoConfigureMockMvc
@RecordApplicationEvents
@Import(MutableClockTestConfiguration::class)
@WithCockpitUser
class HabitWebTests(
	@Autowired private val mockMvc: MockMvc,
	@Autowired private val jsonMapper: JsonMapper,
	@Autowired private val jdbc: JdbcTemplate,
	@Autowired private val clock: MutableClock,
	@Autowired private val settings: SettingsService,
	@Autowired private val reminders: ReminderService,
	@Autowired private val resolvers: ItemResolverRegistry,
) : PostgresTestBase() {
	@BeforeEach
	fun setUp() {
		reset()
		settings.update(mapOf("timeZone" to "Europe/Zurich", "weekStart" to "MONDAY"))
		// Wednesday 2026-03-04, 09:00 in Zurich (UTC+1).
		clock.set(NOW)
	}

	@AfterEach
	fun reset() {
		jdbc.execute("delete from habit")
		jdbc.execute("delete from reminder where item_type = 'HABIT'")
		jdbc.execute("delete from search_document where item_type = 'HABIT'")
		settings.update(mapOf("timeZone" to null, "weekStart" to null))
	}

	// --- helpers

	private fun post(body: String) =
		mockMvc.post(HABITS) {
			with(csrf())
			contentType = MediaType.APPLICATION_JSON
			content = body
		}

	private fun patch(
		id: String,
		body: String,
		ifMatch: String?,
	) = mockMvc.patch("$HABITS/$id") {
		with(csrf())
		contentType = MediaType.APPLICATION_JSON
		content = body
		ifMatch?.let { header(HttpHeaders.IF_MATCH, it) }
	}

	private fun check(
		id: String,
		date: String,
		count: Any?,
	) = mockMvc.put("$HABITS/$id/checks/$date") {
		with(csrf())
		contentType = MediaType.APPLICATION_JSON
		content = jsonMapper.writeValueAsString(mapOf("count" to count))
	}

	private fun ResultActionsDsl.json(): JsonNode = andReturn().response.contentAsString.let(jsonMapper::readTree)

	private fun newHabit(body: String): JsonNode = post(body).andExpect { status { isCreated() } }.json()

	private fun JsonNode.id(): String = get("id").asString()

	private fun JsonNode.etag(): String = "\"${get("version").asLong()}\""

	private fun ResultActionsDsl.isProblem(
		status: Int,
		vararg fields: String,
	) = andExpect {
		status { isEqualTo(status) }
		content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
		fields.forEach { field -> jsonPath("$.errors[?(@.field == '$field')]") { isNotEmpty() } }
	}

	private fun pendingReminders(id: String): List<Instant> =
		jdbc.query(
			"select remind_at from reminder where item_type = 'HABIT' and item_id = ?::uuid and fired_at is null order by remind_at",
			{ rs, _ -> rs.getTimestamp(1).toInstant() },
			id,
		)

	private fun searchTitle(id: String): String? =
		jdbc
			.query("select title from search_document where item_type = 'HABIT' and item_id = ?::uuid", { rs, _ -> rs.getString(1) }, id)
			.singleOrNull()

	private fun ApplicationEvents.changes(): List<Pair<ChangeOp, String>> =
		stream(ChangeEvent::class.java).toList().filter { it.type == "HABIT" }.map { it.op to it.id.toString() }

	// --- CRUD

	@Test
	fun `POST creates a daily habit with defaults, ETag and Location, indexes and announces it`(events: ApplicationEvents) {
		val result =
			post("""{"name":"  Meditate  "}""")
				.andExpect {
					status { isCreated() }
					header { string(HttpHeaders.ETAG, "\"0\"") }
					jsonPath("$.name") { value("Meditate") }
					jsonPath("$.schedule") { value("DAILY") }
					jsonPath("$.timesPerWeek") { doesNotExist() }
					jsonPath("$.weekdays") { isEmpty() }
					jsonPath("$.targetCount") { value(1) }
					jsonPath("$.reminderTime") { doesNotExist() }
					jsonPath("$.archived") { value(false) }
					jsonPath("$.stats.today") { value("2026-03-04") }
					jsonPath("$.stats.dueToday") { value(true) }
					jsonPath("$.stats.doneToday") { value(false) }
					jsonPath("$.stats.currentStreak") { value(0) }
					jsonPath("$.stats.streakUnit") { value("DAYS") }
					jsonPath("$.stats.dueLast30Days") { value(0) }
					jsonPath("$.stats.completionRateLast30Days") { doesNotExist() }
				}.andReturn()
		val id = jsonMapper.readTree(result.response.contentAsString).id()
		UuidV7.isUuidV7(UUID.fromString(id)) shouldBe true
		result.response.getHeader(HttpHeaders.LOCATION) shouldBe "http://localhost$HABITS/$id"
		searchTitle(id) shouldBe "Meditate"
		events.changes() shouldContainExactly listOf(ChangeOp.CREATED to id)
		pendingReminders(id).shouldBeEmpty()
	}

	@Test
	fun `POST validates schedule fields, target count and name`() {
		post("""{}""").isProblem(400, "name")
		post("""{"name":"   "}""").isProblem(400, "name")
		post("""{"name":"${"x".repeat(201)}"}""").isProblem(400, "name")
		post("""{"name":"Run","schedule":"TIMES_PER_WEEK"}""").isProblem(400, "timesPerWeek")
		post("""{"name":"Run","schedule":"TIMES_PER_WEEK","timesPerWeek":8}""").isProblem(400, "timesPerWeek")
		post("""{"name":"Run","schedule":"WEEKDAYS","weekdays":[]}""").isProblem(400, "weekdays")
		post("""{"name":"Run","targetCount":0}""").isProblem(400, "targetCount")
		post("""{"name":"Run","schedule":"HOURLY"}""").isProblem(400, "schedule")
		post("""{"name":"Run","weekdays":["FUNDAY"]}""").isProblem(400, "weekdays")
		post("""{"name":"Run","reminderTime":"25:00"}""").isProblem(400, "reminderTime")
		post("""{"name":"Run","color":"red"}""").isProblem(400, "color")
		jdbc.queryForObject("select count(*) from habit", Long::class.java) shouldBe 0
	}

	@Test
	fun `fields that do not fit the schedule are cleared, weekdays are kept in week order`() {
		val habit = newHabit("""{"name":"Gym","schedule":"WEEKDAYS","weekdays":["FRIDAY","MONDAY","FRIDAY"],"timesPerWeek":3}""")
		habit.get("weekdays").values().map { it.asString() } shouldContainExactly listOf("MONDAY", "FRIDAY")
		habit.get("timesPerWeek").isNull shouldBe true
		patch(habit.id(), """{"schedule":"TIMES_PER_WEEK","timesPerWeek":2}""", habit.etag()).andExpect {
			status { isOk() }
			header { string(HttpHeaders.ETAG, "\"1\"") }
			jsonPath("$.schedule") { value("TIMES_PER_WEEK") }
			jsonPath("$.timesPerWeek") { value(2) }
			jsonPath("$.weekdays") { isEmpty() }
			jsonPath("$.stats.streakUnit") { value("WEEKS") }
		}
	}

	@Test
	fun `PATCH changes only the fields sent and needs a current If-Match`(events: ApplicationEvents) {
		val habit = newHabit("""{"name":"Read","targetCount":2,"reminderTime":"21:15"}""")
		patch(habit.id(), """{"name":"Read a book"}""", null).isProblem(428)
		patch(habit.id(), """{"name":"Read a book"}""", "\"7\"").isProblem(412)
		patch(habit.id(), """{"name":"Read a book","reminderTime":null}""", habit.etag()).andExpect {
			status { isOk() }
			jsonPath("$.name") { value("Read a book") }
			jsonPath("$.targetCount") { value(2) }
			jsonPath("$.reminderTime") { doesNotExist() }
		}
		patch(habit.id(), """{"name":"Again"}""", habit.etag()).isProblem(412)
		patch(habit.id(), """{"targetCount":"many"}""", "\"1\"").isProblem(400, "targetCount")
		searchTitle(habit.id()) shouldBe "Read a book"
		events.changes() shouldContainExactly listOf(ChangeOp.CREATED to habit.id(), ChangeOp.UPDATED to habit.id())
		patch(UUID.randomUUID().toString(), """{"name":"x"}""", "\"0\"").isProblem(404)
	}

	@Test
	fun `GET lists active habits by name, archived ones on request`() {
		newHabit("""{"name":"walk"}""")
		val stretch = newHabit("""{"name":"Stretch"}""")
		newHabit("""{"name":"Floss"}""")
		patch(stretch.id(), """{"archived":true}""", stretch.etag()).andExpect { status { isOk() } }
		mockMvc
			.get(HABITS)
			.json()
			.get("items")
			.values()
			.map { it.get("name").asString() } shouldContainExactly listOf("Floss", "walk")
		mockMvc
			.get(HABITS) { param("archived", "true") }
			.json()
			.get("items")
			.values()
			.map { it.get("name").asString() } shouldContainExactly listOf("Stretch")
		mockMvc.get("$HABITS/${stretch.id()}").andExpect {
			status { isOk() }
			header { string(HttpHeaders.ETAG, "\"1\"") }
			jsonPath("$.archived") { value(true) }
		}
		mockMvc.get("$HABITS/${UUID.randomUUID()}").isProblem(404)
	}

	@Test
	fun `DELETE removes the habit for good with its checks, reminders and search entry`(events: ApplicationEvents) {
		val habit = newHabit("""{"name":"Journal","reminderTime":"20:00"}""")
		check(habit.id(), "2026-03-03", 1).andExpect { status { isOk() } }
		pendingReminders(habit.id()).size shouldBe 1
		mockMvc.delete("$HABITS/${habit.id()}") { with(csrf()) }.isProblem(428)
		mockMvc
			.delete("$HABITS/${habit.id()}") {
				with(csrf())
				header(HttpHeaders.IF_MATCH, habit.etag())
			}.andExpect { status { isNoContent() } }
		mockMvc.get("$HABITS/${habit.id()}").isProblem(404)
		jdbc.queryForObject("select count(*) from habit_check", Long::class.java) shouldBe 0
		pendingReminders(habit.id()).shouldBeEmpty()
		searchTitle(habit.id()).shouldBeNull()
		events.changes().last() shouldBe (ChangeOp.DELETED to habit.id())
		resolvers.find(ItemType.HABIT)!!.resolve(UUID.fromString(habit.id())).shouldBeNull()
	}

	// --- checks and stats

	@Test
	fun `PUT check sets the day's count and returns the new stats`(events: ApplicationEvents) {
		val habit = newHabit("""{"name":"Water","targetCount":3}""")
		check(habit.id(), "2026-03-04", 2).andExpect {
			status { isOk() }
			header { string(HttpHeaders.ETAG, "\"0\"") }
			jsonPath("$.stats.todayCount") { value(2) }
			jsonPath("$.stats.doneToday") { value(false) }
		}
		check(habit.id(), "2026-03-04", 3).andExpect {
			jsonPath("$.stats.todayCount") { value(3) }
			jsonPath("$.stats.doneToday") { value(true) }
			jsonPath("$.stats.currentStreak") { value(1) }
			jsonPath("$.stats.completionRateLast30Days") { value(1.0) }
		}
		check(habit.id(), "2026-03-04", 0).andExpect {
			jsonPath("$.stats.todayCount") { value(0) }
			jsonPath("$.stats.currentStreak") { value(0) }
		}
		jdbc.queryForObject("select count(*) from habit_check", Long::class.java) shouldBe 0
		events.changes().count { it.first == ChangeOp.UPDATED } shouldBe 3
	}

	@Test
	fun `PUT check rejects future dates, bad counts and unknown habits`() {
		val habit = newHabit("""{"name":"Water"}""")
		check(habit.id(), "2026-03-05", 1).isProblem(400, "date")
		check(habit.id(), "2026-03-04", -1).isProblem(400, "count")
		check(habit.id(), "2026-03-04", 1001).isProblem(400, "count")
		check(habit.id(), "2026-03-04", "one").isProblem(400, "count")
		check(habit.id(), "2026-03-04", 1.5).isProblem(400, "count")
		check(habit.id(), "2026-03-04", null).isProblem(400, "count")
		check(habit.id(), "not-a-date", 1).andExpect { status { isBadRequest() } }
		check(UUID.randomUUID().toString(), "2026-03-04", 1).isProblem(404)
		// Today in Zurich is already the 5th at 23:30 UTC on the 4th.
		clock.set(Instant.parse("2026-03-04T23:30:00Z"))
		check(habit.id(), "2026-03-05", 1).andExpect { status { isOk() } }
	}

	@Test
	fun `GET checks lists a date range, by default the last 30 days`() {
		val habit = newHabit("""{"name":"Walk"}""")
		listOf("2026-01-15", "2026-02-03", "2026-02-20", "2026-03-04").forEach { check(habit.id(), it, 1) }
		mockMvc
			.get("$HABITS/${habit.id()}/checks")
			.json()
			.get("items")
			.values()
			.map { it.get("date").asString() } shouldContainExactly listOf("2026-02-03", "2026-02-20", "2026-03-04")
		mockMvc
			.get("$HABITS/${habit.id()}/checks") {
				param("from", "2026-01-01")
				param("to", "2026-02-10")
			}.json()
			.get("items")
			.values()
			.map { it.get("date").asString() + "x" + it.get("count").asInt() } shouldContainExactly listOf("2026-01-15x1", "2026-02-03x1")
		mockMvc
			.get("$HABITS/${habit.id()}/checks") {
				param("from", "2026-03-01")
				param("to", "2026-02-01")
			}.isProblem(400, "to")
		mockMvc.get("$HABITS/${UUID.randomUUID()}/checks").isProblem(404)
	}

	@Test
	fun `weekly streak runs across week boundaries`() {
		// 2x per week since 2026-02-16 (weeks start Monday): 02-16..22 (3), 02-23..03-01 (2), this week open with one check.
		val habit = newHabit("""{"name":"Swim","schedule":"TIMES_PER_WEEK","timesPerWeek":2}""")
		listOf("2026-02-16", "2026-02-17", "2026-02-22", "2026-02-23", "2026-03-01", "2026-03-02").forEach {
			check(habit.id(), it, 1).andExpect { status { isOk() } }
		}
		mockMvc.get("$HABITS/${habit.id()}").andExpect {
			jsonPath("$.stats.currentStreak") { value(2) }
			jsonPath("$.stats.longestStreak") { value(2) }
			jsonPath("$.stats.streakUnit") { value("WEEKS") }
			jsonPath("$.stats.dueToday") { value(true) }
		}
		// With weeks from Sunday, 02-22 and 03-01 start new weeks: 02-15..21 (2), 02-22..28 (2), 03-01..07 (2, done).
		settings.update(mapOf("weekStart" to "SUNDAY"))
		mockMvc.get("$HABITS/${habit.id()}").andExpect {
			jsonPath("$.stats.currentStreak") { value(3) }
			jsonPath("$.stats.dueToday") { value(false) }
		}
	}

	@Test
	fun `daily streak runs across week boundaries and an open today does not break it`() {
		val habit = newHabit("""{"name":"Stretch"}""")
		listOf("2026-02-27", "2026-02-28", "2026-03-01", "2026-03-02", "2026-03-03").forEach { check(habit.id(), it, 1) }
		mockMvc.get("$HABITS/${habit.id()}").andExpect {
			jsonPath("$.stats.currentStreak") { value(5) }
			jsonPath("$.stats.doneToday") { value(false) }
			jsonPath("$.stats.dueLast30Days") { value(5) }
			jsonPath("$.stats.doneLast30Days") { value(5) }
		}
		// The next day the open day is missed.
		clock.advance(Duration.ofDays(1))
		mockMvc.get("$HABITS/${habit.id()}").andExpect {
			jsonPath("$.stats.currentStreak") { value(0) }
			jsonPath("$.stats.longestStreak") { value(5) }
		}
	}

	// --- reminders

	@Test
	fun `a reminder time keeps one reminder on the next open due day`() {
		val habit = newHabit("""{"name":"Meditate","reminderTime":"20:00"}""")
		habit.get("reminderTime").asString() shouldBe "20:00:00"
		// 20:00 in Zurich (UTC+1) today.
		pendingReminders(habit.id()) shouldContainExactly listOf(Instant.parse("2026-03-04T19:00:00Z"))
		check(habit.id(), "2026-03-04", 1)
		pendingReminders(habit.id()) shouldContainExactly listOf(Instant.parse("2026-03-05T19:00:00Z"))
		check(habit.id(), "2026-03-04", 0)
		pendingReminders(habit.id()) shouldContainExactly listOf(Instant.parse("2026-03-04T19:00:00Z"))
	}

	@Test
	fun `a reminder time already past today reminds tomorrow, weekday habits on their next day`() {
		val early = newHabit("""{"name":"Vitamins","reminderTime":"07:00"}""")
		pendingReminders(early.id()) shouldContainExactly listOf(Instant.parse("2026-03-05T06:00:00Z"))
		val gym = newHabit("""{"name":"Gym","schedule":"WEEKDAYS","weekdays":["MONDAY"],"reminderTime":"18:30"}""")
		pendingReminders(gym.id()) shouldContainExactly listOf(Instant.parse("2026-03-09T17:30:00Z"))
		// Across the switch to summer time (2026-03-29), the local time stays.
		val weekly = newHabit("""{"name":"Long run","schedule":"WEEKDAYS","weekdays":["SUNDAY"],"reminderTime":"08:00"}""")
		clock.set(Instant.parse("2026-03-28T12:00:00Z"))
		patch(weekly.id(), """{"reminderTime":"08:00"}""", weekly.etag())
		check(weekly.id(), "2026-03-28", 1)
		pendingReminders(weekly.id()) shouldContainExactly listOf(Instant.parse("2026-03-29T06:00:00Z"))
	}

	@Test
	fun `a fired habit reminder schedules the next one`() {
		val habit = newHabit("""{"name":"Meditate","reminderTime":"20:00"}""")
		clock.set(Instant.parse("2026-03-04T19:00:30Z"))
		reminders.fireDue() shouldBe 1
		pendingReminders(habit.id()) shouldContainExactly listOf(Instant.parse("2026-03-05T19:00:00Z"))
		jdbc.queryForObject(
			"select count(*) from reminder where item_type = 'HABIT' and fired_at is not null",
			Long::class.java,
		) shouldBe 1
	}

	@Test
	fun `archiving or clearing the reminder time removes the pending reminder`() {
		val habit = newHabit("""{"name":"Meditate","reminderTime":"20:00"}""")
		val archived = patch(habit.id(), """{"archived":true}""", habit.etag()).json()
		pendingReminders(habit.id()).shouldBeEmpty()
		val active = patch(habit.id(), """{"archived":false}""", archived.etag()).json()
		pendingReminders(habit.id()) shouldContainExactly listOf(Instant.parse("2026-03-04T19:00:00Z"))
		patch(habit.id(), """{"reminderTime":null}""", active.etag())
		pendingReminders(habit.id()).shouldBeEmpty()
	}

	@Test
	fun `GET reminders of a habit shows the habit's name`() {
		val habit = newHabit("""{"name":"Meditate","reminderTime":"20:00"}""")
		mockMvc
			.get("/api/v1/reminders") {
				param("itemType", "HABIT")
				param("itemId", habit.id())
			}.andExpect {
				status { isOk() }
				jsonPath("$.items[0].item.title") { value("Meditate") }
			}
	}

	// --- resolver

	@Test
	fun `resolver HABIT describes the habit and its schedule`() {
		val resolver = resolvers.find(ItemType.HABIT).shouldNotBeNull()
		val daily = newHabit("""{"name":"Meditate"}""")
		val weekly = newHabit("""{"name":"Swim","schedule":"TIMES_PER_WEEK","timesPerWeek":3}""")
		val days = newHabit("""{"name":"Gym","schedule":"WEEKDAYS","weekdays":["WEDNESDAY","MONDAY"]}""")
		resolver.resolve(UUID.fromString(daily.id())).shouldNotBeNull().let {
			it.title shouldBe "Meditate"
			it.subtitle shouldBe "Daily"
			it.icon shouldBe "repeat"
			it.deepLink shouldBe "/habits/${daily.id()}"
		}
		resolver.resolve(UUID.fromString(weekly.id()))!!.subtitle shouldBe "3× per week"
		resolver.resolve(UUID.fromString(days.id()))!!.subtitle shouldBe "Mon, Wed"
		patch(days.id(), """{"archived":true}""", days.etag())
		resolver.resolve(UUID.fromString(days.id()))!!.subtitle shouldBe "Archived habit"
		resolver.resolve(UUID.randomUUID()).shouldBeNull()
	}

	@Test
	fun `habits can be linked to other items`() {
		val a = newHabit("""{"name":"Meditate"}""")
		val b = newHabit("""{"name":"Journal"}""")
		mockMvc
			.post("/api/v1/links") {
				with(csrf())
				contentType = MediaType.APPLICATION_JSON
				content = """{"source":{"type":"HABIT","id":"${a.id()}"},"target":{"type":"HABIT","id":"${b.id()}"}}"""
			}.andExpect { status { is2xxSuccessful() } }
		mockMvc
			.get("/api/v1/links") {
				param("type", "HABIT")
				param("id", a.id())
			}.andExpect {
				status { isOk() }
				jsonPath("$..title") { value(org.hamcrest.Matchers.hasItem("Journal")) }
			}
	}

	private companion object {
		const val HABITS = "/api/v1/habits"
		val NOW: Instant = Instant.parse("2026-03-04T08:00:00Z")
	}
}
