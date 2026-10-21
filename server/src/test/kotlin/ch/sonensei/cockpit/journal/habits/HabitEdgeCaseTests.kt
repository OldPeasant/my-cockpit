package ch.sonensei.cockpit.journal.habits

import ch.sonensei.cockpit.core.auth.WithCockpitUser
import ch.sonensei.cockpit.core.items.domain.ItemRef
import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.core.persistence.MutableClock
import ch.sonensei.cockpit.core.persistence.MutableClockTestConfiguration
import ch.sonensei.cockpit.core.reminders.domain.Reminder
import ch.sonensei.cockpit.core.reminders.domain.ReminderFiredEvent
import ch.sonensei.cockpit.core.reminders.domain.ReminderService
import ch.sonensei.cockpit.core.reminders.domain.ResolvedReminder
import ch.sonensei.cockpit.core.settings.domain.SettingsService
import ch.sonensei.cockpit.journal.habits.domain.HabitService
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
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
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Edge cases of `/api/v1/habits` against Postgres: daylight saving and time zone changes for reminders, the week start
 * for TIMES_PER_WEEK reminders, PATCH resets and limits, check ranges and concurrent checks.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MutableClockTestConfiguration::class)
@WithCockpitUser
class HabitEdgeCaseTests(
	@Autowired private val mockMvc: MockMvc,
	@Autowired private val jsonMapper: JsonMapper,
	@Autowired private val jdbc: JdbcTemplate,
	@Autowired private val clock: MutableClock,
	@Autowired private val settings: SettingsService,
	@Autowired private val service: HabitService,
	@Autowired private val reminders: ReminderService,
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
		ifMatch: String,
	) = mockMvc.patch("$HABITS/$id") {
		with(csrf())
		contentType = MediaType.APPLICATION_JSON
		content = body
		header(HttpHeaders.IF_MATCH, ifMatch)
	}

	private fun putCheck(
		id: String,
		date: String,
		body: String,
	) = mockMvc.put("$HABITS/$id/checks/$date") {
		with(csrf())
		contentType = MediaType.APPLICATION_JSON
		content = body
	}

	private fun check(
		id: String,
		date: String,
		count: Int,
	) = putCheck(id, date, """{"count":$count}""").andExpect { status { isOk() } }

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

	private fun checkRows(id: String): List<Pair<String, Int>> =
		jdbc.query(
			"select check_date, count from habit_check where habit_id = ?::uuid order by check_date",
			{ rs, _ -> rs.getDate(1).toLocalDate().toString() to rs.getInt(2) },
			id,
		)

	// --- reminders: daylight saving, time zone, week start, schedule changes

	@Test
	fun `a reminder time in the spring-forward gap fires at the shifted local time`() {
		// Saturday 2026-03-28 13:00 CET; on Sunday 02:00-03:00 does not exist in Zurich.
		clock.set(Instant.parse("2026-03-28T12:00:00Z"))
		val habit = newHabit("""{"name":"Night run","schedule":"WEEKDAYS","weekdays":["SUNDAY"],"reminderTime":"02:30"}""")
		// 02:30 is shifted by the gap to 03:30 CEST.
		pendingReminders(habit.id()) shouldContainExactly listOf(Instant.parse("2026-03-29T01:30:00Z"))
	}

	@Test
	fun `reminders follow the local time across the switch back to winter time`() {
		// Saturday 2026-10-24 21:00 CEST; summer time ends on Sunday 2026-10-25 at 03:00.
		clock.set(Instant.parse("2026-10-24T19:00:00Z"))
		val evening = newHabit("""{"name":"Floss","reminderTime":"20:00"}""")
		// 20:00 on Saturday is past; Sunday 20:00 is CET (UTC+1).
		pendingReminders(evening.id()) shouldContainExactly listOf(Instant.parse("2026-10-25T19:00:00Z"))
		// 02:30 happens twice on Sunday: the first one (CEST) is taken.
		val night = newHabit("""{"name":"Night meds","reminderTime":"02:30"}""")
		pendingReminders(night.id()) shouldContainExactly listOf(Instant.parse("2026-10-25T00:30:00Z"))
	}

	@Test
	fun `a time zone change applies to today and to the next reminder move`() {
		val habit = newHabit("""{"name":"Meditate","reminderTime":"20:00"}""")
		pendingReminders(habit.id()) shouldContainExactly listOf(Instant.parse("2026-03-04T19:00:00Z"))
		// 12:00 UTC: 13:00 on the 4th in Zurich, 01:00 on the 5th in Auckland (UTC+13).
		clock.set(Instant.parse("2026-03-04T12:00:00Z"))
		settings.update(mapOf("timeZone" to "Pacific/Auckland"))
		mockMvc.get("$HABITS/${habit.id()}").andExpect { jsonPath("$.stats.today") { value("2026-03-05") } }
		// The setting change alone does not move the reminder; the next check does.
		pendingReminders(habit.id()) shouldContainExactly listOf(Instant.parse("2026-03-04T19:00:00Z"))
		check(habit.id(), "2026-03-05", 0)
		pendingReminders(habit.id()) shouldContainExactly listOf(Instant.parse("2026-03-05T07:00:00Z"))
		putCheck(habit.id(), "2026-03-06", """{"count":1}""").isProblem(400, "date")
	}

	@Test
	fun `a weekly reminder moves to the next week once the quota is reached, by the week start setting`() {
		val habit = newHabit("""{"name":"Swim","schedule":"TIMES_PER_WEEK","timesPerWeek":2,"reminderTime":"20:00"}""")
		pendingReminders(habit.id()) shouldContainExactly listOf(Instant.parse("2026-03-04T19:00:00Z"))
		check(habit.id(), "2026-03-02", 1)
		check(habit.id(), "2026-03-03", 1)
		// Monday weeks: this week is full, next Monday 03-09.
		pendingReminders(habit.id()) shouldContainExactly listOf(Instant.parse("2026-03-09T19:00:00Z"))
		// Sunday weeks: the next week starts on Sunday 03-08.
		settings.update(mapOf("weekStart" to "SUNDAY"))
		check(habit.id(), "2026-03-03", 1)
		pendingReminders(habit.id()) shouldContainExactly listOf(Instant.parse("2026-03-08T19:00:00Z"))
	}

	@Test
	fun `changing the schedule or the reminder time moves the reminder, seconds are dropped`() {
		val habit = newHabit("""{"name":"Gym","reminderTime":"20:00"}""")
		pendingReminders(habit.id()) shouldContainExactly listOf(Instant.parse("2026-03-04T19:00:00Z"))
		val weekly = patch(habit.id(), """{"schedule":"WEEKDAYS","weekdays":["FRIDAY"]}""", habit.etag()).json()
		pendingReminders(habit.id()) shouldContainExactly listOf(Instant.parse("2026-03-06T19:00:00Z"))
		patch(habit.id(), """{"reminderTime":"07:30:45"}""", weekly.etag()).andExpect {
			status { isOk() }
			jsonPath("$.reminderTime") { value("07:30:00") }
		}
		pendingReminders(habit.id()) shouldContainExactly listOf(Instant.parse("2026-03-06T06:30:00Z"))
	}

	@Test
	fun `a fired reminder moves only the reminder of the habit it belongs to`() {
		val habit = newHabit("""{"name":"Meditate","reminderTime":"20:00"}""")
		val id = UUID.fromString(habit.id())
		// 20:30 in Zurich: today's reminder time is past, but it has not fired yet.
		clock.set(Instant.parse("2026-03-04T19:30:00Z"))

		fun fired(type: ItemType) = ReminderFiredEvent(ResolvedReminder(Reminder(type, id, Instant.parse("2026-03-04T19:00:00Z")), null))
		// A reminder of another item type with the same id is not the habit's.
		service.onReminderFired(fired(ItemType.TASK))
		pendingReminders(habit.id()) shouldContainExactly listOf(Instant.parse("2026-03-04T19:00:00Z"))
		// A habit reminder of a habit that is gone is ignored.
		service.onReminderFired(
			ReminderFiredEvent(ResolvedReminder(Reminder(ItemType.HABIT, UUID.randomUUID(), Instant.parse("2026-03-04T19:00:00Z")), null)),
		)
		service.onReminderFired(fired(ItemType.HABIT))
		pendingReminders(habit.id()) shouldContainExactly listOf(Instant.parse("2026-03-05T19:00:00Z"))
	}

	@Test
	fun `a failing habit reminder move neither rolls back nor stops the firing batch`() {
		val broken = newHabit("""{"name":"Broken","reminderTime":"20:00"}""")
		val fine = newHabit("""{"name":"Fine","reminderTime":"20:00"}""")
		// A row the habit entity cannot load (unknown weekday): every sync of this habit throws.
		jdbc.update("update habit set weekdays = '[\"FUNDAY\"]' where id = ?::uuid", broken.id())
		clock.set(Instant.parse("2026-03-04T19:30:00Z"))

		reminders.fireDue() shouldBe 2

		jdbc.queryForObject(
			"select count(*) from reminder where item_type = 'HABIT' and fired_at is not null",
			Int::class.java,
		) shouldBe 2
		pendingReminders(fine.id()) shouldContainExactly listOf(Instant.parse("2026-03-05T19:00:00Z"))
		pendingReminders(broken.id()).shouldBeEmpty()
	}

	@Test
	fun `reminders the user added to a habit are left alone`() {
		val habit = newHabit("""{"name":"Meditate","reminderTime":"20:00"}""")
		val ref = ItemRef(ItemType.HABIT, UUID.fromString(habit.id()))
		val own = Instant.parse("2026-03-10T08:00:00Z")
		reminders.create(ref, own)
		pendingReminders(habit.id()) shouldContainExactly listOf(Instant.parse("2026-03-04T19:00:00Z"), own)

		// A check moves only the habit's reminder.
		check(habit.id(), "2026-03-04", 1)
		pendingReminders(habit.id()) shouldContainExactly listOf(Instant.parse("2026-03-05T19:00:00Z"), own)
		// Firing moves only the habit's reminder.
		clock.set(Instant.parse("2026-03-05T19:30:00Z"))
		reminders.fireDue() shouldBe 1
		pendingReminders(habit.id()) shouldContainExactly listOf(Instant.parse("2026-03-06T19:00:00Z"), own)
		// Clearing the reminder time removes only the habit's reminder.
		val current = mockMvc.get("$HABITS/${habit.id()}").json()
		patch(habit.id(), """{"reminderTime":null}""", current.etag()).andExpect { status { isOk() } }
		pendingReminders(habit.id()) shouldContainExactly listOf(own)
		check(habit.id(), "2026-03-05", 1)
		pendingReminders(habit.id()) shouldContainExactly listOf(own)
	}

	@Test
	fun `moving the habit's reminder does not change its version`() {
		val habit = newHabit("""{"name":"Meditate","reminderTime":"20:00"}""")
		check(habit.id(), "2026-03-04", 1)
		pendingReminders(habit.id()) shouldContainExactly listOf(Instant.parse("2026-03-05T19:00:00Z"))
		patch(habit.id(), """{"name":"Meditate daily"}""", habit.etag()).andExpect { status { isOk() } }
	}

	@Test
	fun `an archived habit can still be checked and gets no reminder`() {
		val habit = newHabit("""{"name":"Stretch","reminderTime":"20:00","archived":true}""")
		pendingReminders(habit.id()).shouldBeEmpty()
		putCheck(habit.id(), "2026-03-04", """{"count":1}""").andExpect {
			status { isOk() }
			jsonPath("$.archived") { value(true) }
			jsonPath("$.stats.doneToday") { value(true) }
		}
		pendingReminders(habit.id()).shouldBeEmpty()
	}

	// --- PATCH resets and limits

	@Test
	fun `PATCH with null resets schedule, target count and archived to their defaults`() {
		val habit = newHabit("""{"name":"Plank","schedule":"TIMES_PER_WEEK","timesPerWeek":3,"targetCount":4,"archived":true}""")
		patch(habit.id(), """{"schedule":null,"targetCount":null,"archived":null}""", habit.etag()).andExpect {
			status { isOk() }
			jsonPath("$.schedule") { value("DAILY") }
			jsonPath("$.timesPerWeek") { doesNotExist() }
			jsonPath("$.targetCount") { value(1) }
			jsonPath("$.archived") { value(false) }
			jsonPath("$.stats.streakUnit") { value("DAYS") }
		}
	}

	@Test
	fun `PATCH rejects clearing required fields and keeps the habit unchanged`() {
		val weekly = newHabit("""{"name":"Swim","schedule":"TIMES_PER_WEEK","timesPerWeek":2}""")
		patch(weekly.id(), """{"name":null}""", weekly.etag()).isProblem(400, "name")
		patch(weekly.id(), """{"timesPerWeek":null}""", weekly.etag()).isProblem(400, "timesPerWeek")
		patch(weekly.id(), """{"timesPerWeek":0}""", weekly.etag()).isProblem(400, "timesPerWeek")
		val days = newHabit("""{"name":"Gym","schedule":"WEEKDAYS","weekdays":["MONDAY"]}""")
		patch(days.id(), """{"weekdays":null}""", days.etag()).isProblem(400, "weekdays")
		mockMvc.get("$HABITS/${weekly.id()}").andExpect {
			header { string(HttpHeaders.ETAG, "\"0\"") }
			jsonPath("$.name") { value("Swim") }
			jsonPath("$.timesPerWeek") { value(2) }
		}
		mockMvc.get("$HABITS/${days.id()}").andExpect { jsonPath("$.weekdays[0]") { value("MONDAY") } }
	}

	@Test
	fun `limits are inclusive at their maximum`() {
		newHabit("""{"name":"${"n".repeat(200)}","targetCount":1000}""")
		post("""{"name":"Run","targetCount":1001}""").isProblem(400, "targetCount")
		newHabit("""{"name":"Run","schedule":"TIMES_PER_WEEK","timesPerWeek":7}""")
		post("""{"name":"Run","schedule":"TIMES_PER_WEEK","timesPerWeek":0}""").isProblem(400, "timesPerWeek")
		val habit = newHabit("""{"name":"Water"}""")
		putCheck(habit.id(), "2026-03-04", """{"count":1000}""").andExpect {
			status { isOk() }
			jsonPath("$.stats.todayCount") { value(1000) }
		}
	}

	@Test
	fun `DELETE with a stale If-Match is refused and an unknown habit is not found`() {
		val habit = newHabit("""{"name":"Read"}""")
		val changed = patch(habit.id(), """{"name":"Read more"}""", habit.etag()).json()
		mockMvc
			.delete("$HABITS/${habit.id()}") {
				with(csrf())
				header(HttpHeaders.IF_MATCH, habit.etag())
			}.isProblem(412)
		mockMvc.get("$HABITS/${habit.id()}").andExpect { status { isOk() } }
		mockMvc
			.delete("$HABITS/${UUID.randomUUID()}") {
				with(csrf())
				header(HttpHeaders.IF_MATCH, changed.etag())
			}.isProblem(404)
	}

	// --- checks

	@Test
	fun `PUT check rejects unknown fields and a missing count`() {
		val habit = newHabit("""{"name":"Water"}""")
		putCheck(habit.id(), "2026-03-04", """{"count":1,"note":"x"}""").isProblem(400, "note")
		putCheck(habit.id(), "2026-03-04", """{}""").isProblem(400, "count")
		checkRows(habit.id()).shouldBeEmpty()
	}

	@Test
	fun `removing a check that does not exist changes nothing`() {
		val habit = newHabit("""{"name":"Water"}""")
		putCheck(habit.id(), "2026-03-01", """{"count":0}""").andExpect {
			status { isOk() }
			jsonPath("$.stats.dueLast30Days") { value(0) }
		}
		checkRows(habit.id()).shouldBeEmpty()
	}

	@Test
	fun `a check before the creation day counts in the stats`() {
		val habit = newHabit("""{"name":"Walk"}""")
		putCheck(habit.id(), "2026-02-28", """{"count":1}""").andExpect {
			status { isOk() }
			// 02-28..03-03 are due (today is still open), 1 done.
			jsonPath("$.stats.dueLast30Days") { value(4) }
			jsonPath("$.stats.doneLast30Days") { value(1) }
			jsonPath("$.stats.currentStreak") { value(0) }
			jsonPath("$.stats.longestStreak") { value(1) }
		}
	}

	@Test
	fun `GET checks accepts a single day and at most 3660 days`() {
		val habit = newHabit("""{"name":"Walk"}""")
		check(habit.id(), "2026-03-04", 2)
		mockMvc
			.get("$HABITS/${habit.id()}/checks") {
				param("from", "2026-03-04")
				param("to", "2026-03-04")
			}.andExpect {
				status { isOk() }
				jsonPath("$.items[0].date") { value("2026-03-04") }
				jsonPath("$.items[0].count") { value(2) }
			}
		val from = LocalDate.parse("2016-03-10")
		mockMvc
			.get("$HABITS/${habit.id()}/checks") {
				param("from", from.toString())
				param("to", from.plusDays(3659).toString())
			}.andExpect { status { isOk() } }
		mockMvc
			.get("$HABITS/${habit.id()}/checks") {
				param("from", from.toString())
				param("to", from.plusDays(3660).toString())
			}.isProblem(400, "to")
	}

	@Test
	fun `the list computes each habit's stats from its own checks`() {
		val water = newHabit("""{"name":"Water"}""")
		val walk = newHabit("""{"name":"Walk"}""")
		check(water.id(), "2026-03-04", 1)
		check(walk.id(), "2026-03-02", 1)
		check(walk.id(), "2026-03-03", 1)
		val items =
			mockMvc
				.get(HABITS)
				.json()
				.get("items")
				.values()
				.associateBy { it.get("name").asString() }
		items.getValue("Water").get("stats").let {
			it.get("doneToday").asBoolean() shouldBe true
			it.get("currentStreak").asInt() shouldBe 1
		}
		items.getValue("Walk").get("stats").let {
			it.get("doneToday").asBoolean() shouldBe false
			it.get("currentStreak").asInt() shouldBe 2
		}
	}

	@Test
	fun `concurrent checks of the same habit and date never insert twice`() {
		val habit = newHabit("""{"name":"Water","reminderTime":"20:00"}""")
		val id = UUID.fromString(habit.id())
		val threads = 4
		val pool = Executors.newFixedThreadPool(threads)
		try {
			listOf("2026-03-02", "2026-03-03", "2026-03-04").forEach { date ->
				val barrier = CyclicBarrier(threads)
				val results =
					(1..threads).map { n ->
						pool.submit<Int> {
							barrier.await(10, TimeUnit.SECONDS)
							service.setCheck(id, LocalDate.parse(date), n).stats.todayCount
						}
					}
				results.forEach { it.get(30, TimeUnit.SECONDS) }
			}
		} finally {
			pool.shutdown()
		}
		val rows = checkRows(habit.id())
		rows.map { it.first } shouldContainExactly listOf("2026-03-02", "2026-03-03", "2026-03-04")
		rows.forEach { (it.second in 1..threads) shouldBe true }
		// Exactly one pending reminder survives the concurrent moves.
		pendingReminders(habit.id()).size shouldBe 1
	}

	private companion object {
		const val HABITS = "/api/v1/habits"
		val NOW: Instant = Instant.parse("2026-03-04T08:00:00Z")
	}
}
