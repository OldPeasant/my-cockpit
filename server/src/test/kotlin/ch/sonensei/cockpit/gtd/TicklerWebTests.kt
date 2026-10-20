package ch.sonensei.cockpit.gtd

import ch.sonensei.cockpit.core.auth.WithCockpitUser
import ch.sonensei.cockpit.core.events.domain.ChangeEvent
import ch.sonensei.cockpit.core.events.domain.ChangeOp
import ch.sonensei.cockpit.core.persistence.MutableClock
import ch.sonensei.cockpit.core.persistence.MutableClockTestConfiguration
import ch.sonensei.cockpit.core.settings.domain.SettingsService
import ch.sonensei.cockpit.gtd.domain.TicklerJob
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
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.event.ApplicationEvents
import org.springframework.test.context.event.RecordApplicationEvents
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * The tickler and scheduled tasks (gtd-tickler, SPEC.md §5.1, acceptance scenario 5): tasks in a tickler are hidden from
 * every list until their date, then the tickler job (or the next read) puts them back in the Inbox; scheduled tasks
 * become next actions on their date.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MutableClockTestConfiguration::class)
@RecordApplicationEvents
@WithCockpitUser
class TicklerWebTests(
	@Autowired private val mockMvc: MockMvc,
	@Autowired private val jsonMapper: JsonMapper,
	@Autowired private val clock: MutableClock,
	@Autowired private val settings: SettingsService,
	@Autowired private val tickler: TicklerJob,
	@Autowired private val jdbc: JdbcTemplate,
) : PostgresTestBase() {
	@BeforeEach
	fun reset() {
		jdbc.execute("update project set next_task_id = null")
		jdbc.execute("delete from checklist_item")
		jdbc.execute("delete from task_context")
		jdbc.execute("update task set next_occurrence_id = null")
		jdbc.execute("delete from task")
		jdbc.execute("delete from project")
		jdbc.execute("delete from search_document")
		settings.update(mapOf("timeZone" to "Europe/Zurich"))
		clock.set(MutableClockTestConfiguration.START) // Sunday 2026-03-01, 09:00 in Europe/Zurich
	}

	@AfterEach
	fun resetTimeZone() {
		settings.update(mapOf("timeZone" to null))
	}

	// --- helpers

	private fun ResultActionsDsl.json(): JsonNode = andReturn().response.contentAsString.let(jsonMapper::readTree)

	private fun create(body: String): String =
		mockMvc
			.post(BASE) {
				with(csrf())
				contentType = MediaType.APPLICATION_JSON
				content = body
			}.andExpect { status { isCreated() } }
			.json()
			.get("id")
			.asString()

	private fun move(
		id: String,
		body: String,
	) = mockMvc
		.post("$BASE/$id/move") {
			with(csrf())
			contentType = MediaType.APPLICATION_JSON
			content = body
		}.andExpect { status { isOk() } }

	private fun ids(query: String = ""): List<String> =
		mockMvc
			.get("$BASE$query")
			.andExpect { status { isOk() } }
			.json()
			.get("items")
			.values()
			.map { it.get("id").asString() }

	private fun task(id: String): JsonNode = mockMvc.get("$BASE/$id").andExpect { status { isOk() } }.json()

	/** The list and tickler date of the task, read from the database (no on-read check). */
	private fun stored(id: String): Pair<String, String?> =
		jdbc.queryForObject("select list, tickler_date from task where id = ?", { rs, _ ->
			rs.getString("list") to rs.getString("tickler_date")
		}, UUID.fromString(id))!!

	private fun JsonNode.isAbsent(field: String): Boolean = get(field)?.isNull ?: true

	private fun updated(
		events: ApplicationEvents,
		id: String,
	): Int =
		events
			.stream(ChangeEvent::class.java)
			.toList()
			.count { it.type == "TASK" && it.op == ChangeOp.UPDATED && it.id.toString() == id }

	// --- tests

	@Test
	fun `a task in a tickler is hidden from every list, and two days later the job puts it back in the Inbox`(events: ApplicationEvents) {
		val passport = create("""{"title":"Renew passport","list":"NEXT"}""")
		val other = create("""{"title":"Buy stamps","list":"NEXT"}""")
		move(passport, """{"list":"NEXT","ticklerDate":"2026-03-03"}""")

		ids() shouldContainExactly listOf(other)
		ids("?list=NEXT") shouldContainExactly listOf(other)
		ids("?list=INBOX").shouldBeEmpty()
		task(passport).get("ticklerDate").asString() shouldBe "2026-03-03"

		clock.advance(Duration.ofDays(1))
		tickler.run() shouldBe 0
		ids() shouldContainExactly listOf(other)

		clock.advance(Duration.ofDays(1))
		events.clear()
		tickler.run() shouldBe 1

		stored(passport) shouldBe ("INBOX" to null)
		updated(events, passport) shouldBe 1
		ids("?list=INBOX") shouldContainExactly listOf(passport)
		task(passport).isAbsent("ticklerDate") shouldBe true
	}

	@Test
	fun `reading the tasks releases a due tickler when the job has not run`(events: ApplicationEvents) {
		val passport = create("""{"title":"Renew passport","list":"SOMEDAY"}""")
		move(passport, """{"list":"SOMEDAY","ticklerDate":"2026-03-03"}""")
		clock.advance(Duration.ofDays(2))
		events.clear()

		ids("?list=INBOX") shouldContainExactly listOf(passport)
		stored(passport) shouldBe ("INBOX" to null)
		updated(events, passport) shouldBe 1
	}

	@Test
	fun `the tickler date is a day in the user's time zone`() {
		val passport = create("""{"title":"Renew passport"}""")
		move(passport, """{"list":"NEXT","ticklerDate":"2026-03-02"}""")

		// 23:30 on 2026-03-01 in Zurich is still the day before the tickler.
		clock.set(Instant.parse("2026-03-01T22:30:00Z"))
		tickler.run() shouldBe 0
		ids().shouldBeEmpty()

		// 00:05 on 2026-03-02 in Zurich, though still 2026-03-01 in UTC.
		clock.set(Instant.parse("2026-03-01T23:05:00Z"))
		tickler.run() shouldBe 1
		ids("?list=INBOX") shouldContainExactly listOf(passport)
	}

	@Test
	fun `a released tickler leaves the scheduled date and the waiting-for behind and goes to the end of the Inbox`() {
		val first = create("""{"title":"Sort the mail"}""")
		val waiting = create("""{"title":"Answer from the landlord","list":"WAITING","waitingForText":"Landlord"}""")
		move(waiting, """{"list":"WAITING","waitingForText":"Landlord","ticklerDate":"2026-03-02"}""")
		val scheduled = create("""{"title":"Book the dentist","list":"SCHEDULED","scheduledDate":"2026-03-05"}""")
		move(scheduled, """{"list":"SCHEDULED","scheduledDate":"2026-03-05","ticklerDate":"2026-03-02"}""")
		clock.advance(Duration.ofDays(1))

		ids("?list=INBOX") shouldContainExactly listOf(first, waiting, scheduled)
		listOf(waiting, scheduled).map(::task).forEach {
			it.isAbsent("scheduledDate") shouldBe true
			it.isAbsent("waitingForText") shouldBe true
			it.isAbsent("waitingSince") shouldBe true
		}
	}

	@Test
	fun `a scheduled task becomes a next action on its date`(events: ApplicationEvents) {
		val dentist = create("""{"title":"Book the dentist","list":"SCHEDULED","scheduledDate":"2026-03-02"}""")
		val later = create("""{"title":"File the tax return","list":"SCHEDULED","scheduledDate":"2026-03-10"}""")

		ids("?list=SCHEDULED") shouldContainExactly listOf(dentist, later)

		clock.advance(Duration.ofDays(1))
		events.clear()
		tickler.run() shouldBe 1

		updated(events, dentist) shouldBe 1
		ids("?list=NEXT") shouldContainExactly listOf(dentist)
		ids("?list=SCHEDULED") shouldContainExactly listOf(later)
		task(dentist).isAbsent("scheduledDate") shouldBe true
	}

	@Test
	fun `a scheduled task created for today or a past day is a next action when the tasks are read`() {
		val today = create("""{"title":"Book the dentist","list":"SCHEDULED","scheduledDate":"2026-03-01"}""")
		val overdue = create("""{"title":"Call the plumber","list":"SCHEDULED","scheduledDate":"2026-02-20"}""")

		// Released in date order, so each goes to the end of Next in turn.
		ids("?list=NEXT") shouldContainExactly listOf(overdue, today)
	}

	@Test
	fun `a scheduled task in a tickler waits for the tickler, then goes to the Inbox`() {
		val dentist = create("""{"title":"Book the dentist","list":"SCHEDULED","scheduledDate":"2026-03-02"}""")
		move(dentist, """{"list":"SCHEDULED","scheduledDate":"2026-03-02","ticklerDate":"2026-03-04"}""")

		clock.advance(Duration.ofDays(1))
		tickler.run() shouldBe 0
		stored(dentist) shouldBe ("SCHEDULED" to "2026-03-04")

		clock.advance(Duration.ofDays(2))
		tickler.run() shouldBe 1
		stored(dentist) shouldBe ("INBOX" to null)
	}

	@Test
	fun `done and deleted tasks are not moved`() {
		val done = create("""{"title":"Book the dentist","list":"SCHEDULED","scheduledDate":"2026-03-02"}""")
		mockMvc.post("$BASE/$done/complete") { with(csrf()) }.andExpect { status { isOk() } }
		val deleted = create("""{"title":"Renew passport"}""")
		move(deleted, """{"list":"NEXT","ticklerDate":"2026-03-02"}""")
		jdbc.update("update task set deleted_at = now() where id = ?", UUID.fromString(deleted))

		clock.advance(Duration.ofDays(1))
		tickler.run() shouldBe 0
		stored(done).first shouldBe "SCHEDULED"
		stored(deleted).first shouldBe "NEXT"
	}

	companion object {
		const val BASE = "/api/v1/tasks"
	}
}
