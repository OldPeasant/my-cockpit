package ch.sonensei.cockpit.journal.habits

import ch.sonensei.cockpit.core.auth.WithCockpitUser
import ch.sonensei.cockpit.core.items.domain.ItemRef
import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.core.persistence.MutableClock
import ch.sonensei.cockpit.core.persistence.MutableClockTestConfiguration
import ch.sonensei.cockpit.core.reminders.domain.ReminderService
import ch.sonensei.cockpit.core.settings.domain.SettingsService
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
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
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * The one reminder the habits module manages per habit (`habit.reminder_id`): it always names the pending reminder at the
 * habit's reminder time, is cleared when there is none, and is replaced when that reminder fired or was deleted
 * elsewhere. Deleting the habit removes all its reminders.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MutableClockTestConfiguration::class)
@WithCockpitUser
class HabitReminderOwnershipTests(
	@Autowired private val mockMvc: MockMvc,
	@Autowired private val jsonMapper: JsonMapper,
	@Autowired private val jdbc: JdbcTemplate,
	@Autowired private val clock: MutableClock,
	@Autowired private val settings: SettingsService,
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

	private fun newHabit(body: String): JsonNode =
		mockMvc
			.post(HABITS) {
				with(csrf())
				contentType = MediaType.APPLICATION_JSON
				content = body
			}.andExpect { status { isCreated() } }
			.json()

	private fun patch(
		id: String,
		body: String,
	): JsonNode {
		val current = mockMvc.get("$HABITS/$id").json()
		return mockMvc
			.patch("$HABITS/$id") {
				with(csrf())
				contentType = MediaType.APPLICATION_JSON
				content = body
				header(HttpHeaders.IF_MATCH, "\"${current.get("version").asLong()}\"")
			}.andExpect { status { isOk() } }
			.json()
	}

	private fun check(
		id: String,
		date: String,
		count: Int,
	) = mockMvc
		.put("$HABITS/$id/checks/$date") {
			with(csrf())
			contentType = MediaType.APPLICATION_JSON
			content = """{"count":$count}"""
		}.andExpect { status { isOk() } }

	private fun ResultActionsDsl.json(): JsonNode = andReturn().response.contentAsString.let(jsonMapper::readTree)

	private fun JsonNode.id(): String = get("id").asString()

	private fun managedId(habitId: String): UUID? =
		jdbc.queryForObject("select reminder_id from habit where id = ?::uuid", UUID::class.java, habitId)

	/** The pending reminders of the habit: id and time, earliest first. */
	private fun pending(habitId: String): List<Pair<UUID, Instant>> =
		jdbc.query(
			"select id, remind_at from reminder where item_type = 'HABIT' and item_id = ?::uuid and fired_at is null " +
				"order by remind_at, id",
			{ rs, _ -> rs.getObject(1, UUID::class.java) to rs.getTimestamp(2).toInstant() },
			habitId,
		)

	private fun allReminders(habitId: String): Long? =
		jdbc.queryForObject("select count(*) from reminder where item_type = 'HABIT' and item_id = ?::uuid", Long::class.java, habitId)

	// --- tests

	@Test
	fun `the habit names its pending reminder, and no reminder once the reminder time is cleared`() {
		val habit = newHabit("""{"name":"Meditate","reminderTime":"20:00"}""")
		val first = pending(habit.id()).single()
		managedId(habit.id()) shouldBe first.first

		// A move replaces the reminder and the habit names the new one.
		check(habit.id(), "2026-03-04", 1)
		val moved = pending(habit.id()).single()
		moved.second shouldBe Instant.parse("2026-03-05T19:00:00Z")
		managedId(habit.id()) shouldBe moved.first

		patch(habit.id(), """{"reminderTime":null}""")
		pending(habit.id()).shouldBeEmpty()
		managedId(habit.id()).shouldBeNull()
	}

	@Test
	fun `archiving clears the managed reminder, unarchiving creates a new one`() {
		val habit = newHabit("""{"name":"Stretch","reminderTime":"20:00"}""")
		val before = managedId(habit.id()).shouldNotBeNull()
		patch(habit.id(), """{"archived":true}""")
		managedId(habit.id()).shouldBeNull()
		patch(habit.id(), """{"archived":false}""")
		val after = managedId(habit.id()).shouldNotBeNull()
		after shouldNotBe before
		pending(habit.id()).map { it.first } shouldContainExactly listOf(after)
	}

	@Test
	fun `a habit without reminder time never gets a managed reminder`() {
		val habit = newHabit("""{"name":"Read"}""")
		check(habit.id(), "2026-03-04", 1)
		managedId(habit.id()).shouldBeNull()
		allReminders(habit.id()) shouldBe 0
	}

	@Test
	fun `after its reminder fired the habit names the next pending one`() {
		val habit = newHabit("""{"name":"Meditate","reminderTime":"20:00"}""")
		val firedId = managedId(habit.id()).shouldNotBeNull()
		clock.set(Instant.parse("2026-03-04T19:00:30Z"))
		// Reminders of other tests may be due too, so the count of fired ones is not asserted.
		reminders.fireDue()

		val next = pending(habit.id()).single()
		next.second shouldBe Instant.parse("2026-03-05T19:00:00Z")
		managedId(habit.id()) shouldBe next.first
		next.first shouldNotBe firedId
		// The fired reminder stays as history.
		allReminders(habit.id()) shouldBe 2
	}

	@Test
	fun `a managed reminder deleted through the reminders API is created again on the next move`() {
		val habit = newHabit("""{"name":"Journal","reminderTime":"20:00"}""")
		val deleted = managedId(habit.id()).shouldNotBeNull()
		reminders.delete(deleted)
		pending(habit.id()).shouldBeEmpty()

		// The next change notices the managed reminder is gone and creates a new one instead of failing.
		patch(habit.id(), """{"name":"Evening journal"}""")
		val recreated = pending(habit.id()).single()
		recreated.second shouldBe Instant.parse("2026-03-04T19:00:00Z")
		managedId(habit.id()) shouldBe recreated.first
		recreated.first shouldNotBe deleted
	}

	@Test
	fun `a managed reminder moved through the reminders API is put back to the habit's time on the next move`() {
		val habit = newHabit("""{"name":"Journal","reminderTime":"20:00"}""")
		val managed = managedId(habit.id()).shouldNotBeNull()
		// Simulates a client changing the managed reminder's time behind the module's back.
		jdbc.update("update reminder set remind_at = ? where id = ?", Timestamp.from(Instant.parse("2026-03-06T10:00:00Z")), managed)

		check(habit.id(), "2026-03-03", 1)
		val now = pending(habit.id()).single()
		now.second shouldBe Instant.parse("2026-03-04T19:00:00Z")
		managedId(habit.id()) shouldBe now.first
	}

	@Test
	fun `deleting the habit removes every reminder of it, managed, user-added and fired`() {
		val habit = newHabit("""{"name":"Meditate","reminderTime":"20:00"}""")
		val ref = ItemRef(ItemType.HABIT, UUID.fromString(habit.id()))
		reminders.create(ref, Instant.parse("2026-03-10T08:00:00Z"))
		clock.set(Instant.parse("2026-03-04T19:00:30Z"))
		// Reminders of other tests may be due too, so the count of fired ones is not asserted.
		reminders.fireDue()
		allReminders(habit.id()) shouldBe 3

		val current = mockMvc.get("$HABITS/${habit.id()}").json()
		mockMvc
			.delete("$HABITS/${habit.id()}") {
				with(csrf())
				header(HttpHeaders.IF_MATCH, "\"${current.get("version").asLong()}\"")
			}.andExpect { status { isNoContent() } }

		allReminders(habit.id()) shouldBe 0
		reminders.pendingOfItem(ref).shouldBeEmpty()
	}

	private companion object {
		const val HABITS = "/api/v1/habits"
		val NOW: Instant = Instant.parse("2026-03-04T08:00:00Z")
	}
}
