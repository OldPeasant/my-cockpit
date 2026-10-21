package ch.sonensei.cockpit.gtd

import ch.sonensei.cockpit.core.auth.WithCockpitUser
import ch.sonensei.cockpit.core.persistence.MutableClock
import ch.sonensei.cockpit.core.persistence.MutableClockTestConfiguration
import ch.sonensei.cockpit.core.settings.domain.SettingsService
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
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
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.databind.json.JsonMapper

/**
 * The `from`/`to` filter of the task list (web-cal-overlay, SPEC.md §6.3): open tasks due or in a tickler on the days a
 * calendar view shows, including tasks still hidden in a tickler.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MutableClockTestConfiguration::class)
@WithCockpitUser
class TaskDateRangeWebTests(
	@Autowired private val mockMvc: MockMvc,
	@Autowired private val jsonMapper: JsonMapper,
	@Autowired private val clock: MutableClock,
	@Autowired private val settings: SettingsService,
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

	private fun create(body: String): String =
		mockMvc
			.post(BASE) {
				with(csrf())
				contentType = MediaType.APPLICATION_JSON
				content = body
			}.andExpect { status { isCreated() } }
			.andReturn()
			.response.contentAsString
			.let(jsonMapper::readTree)
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

	private fun ids(query: String): List<String> =
		mockMvc
			.get("$BASE$query")
			.andExpect { status { isOk() } }
			.andReturn()
			.response.contentAsString
			.let(jsonMapper::readTree)
			.get("items")
			.values()
			.map { it.get("id").asString() }

	private fun rejected(query: String) {
		mockMvc.get("$BASE$query").andExpect { status { isBadRequest() } }
	}

	@Test
	fun `a range lists the open tasks due in it and the ticklers coming back in it`() {
		val first = create("""{"title":"Pay rent","list":"NEXT","dueDate":"2026-03-02"}""")
		val last = create("""{"title":"File taxes","list":"NEXT","dueDate":"2026-03-08","dueTime":"17:00"}""")
		val before = create("""{"title":"Before the range","list":"NEXT","dueDate":"2026-03-01"}""")
		create("""{"title":"After the range","list":"NEXT","dueDate":"2026-03-09"}""")
		create("""{"title":"No dates","list":"NEXT"}""")
		val tickler = create("""{"title":"Renew passport","list":"NEXT"}""")
		move(tickler, """{"list":"SOMEDAY","ticklerDate":"2026-03-05"}""")
		val laterTickler = create("""{"title":"Plan holidays","list":"NEXT"}""")
		move(laterTickler, """{"list":"SOMEDAY","ticklerDate":"2026-04-01"}""")
		val done = create("""{"title":"Done already","list":"NEXT","dueDate":"2026-03-03"}""")
		mockMvc.post("$BASE/$done/complete") { with(csrf()) }.andExpect { status { isOk() } }

		ids("?from=2026-03-02&to=2026-03-08") shouldContainExactlyInAnyOrder listOf(first, last, tickler)
		// Without a range the tickler stays hidden.
		ids("?due=week") shouldContainExactlyInAnyOrder listOf(before, first)
		ids("?from=2026-03-20&to=2026-03-25").shouldBeEmpty()
	}

	@Test
	fun `a range combines with the other filters`() {
		val next = create("""{"title":"Pay rent","list":"NEXT","dueDate":"2026-03-02"}""")
		create("""{"title":"Call bank","list":"INBOX","dueDate":"2026-03-02"}""")

		ids("?from=2026-03-02&to=2026-03-02&list=NEXT") shouldContainExactlyInAnyOrder listOf(next)
	}

	@Test
	fun `an incomplete, reversed or too long range is rejected`() {
		rejected("?from=2026-03-02")
		rejected("?to=2026-03-02")
		rejected("?from=2026-03-08&to=2026-03-02")
		rejected("?from=2026-01-01&to=2027-01-02")
		rejected("?from=yesterday&to=2026-03-02")
		ids("?from=2026-01-01&to=2026-12-31").shouldBeEmpty()
	}

	@Test
	fun `a rejected range names the field and the rule in Problem Details`() {
		problem("?from=2026-03-02", "to", "is required with from")
		problem("?to=2026-03-02", "from", "is required with to")
		problem("?from=2026-03-08&to=2026-03-02", "to", "must not be before from")
		// 366 days after from is a 367-day range: one day too long.
		problem("?from=2026-01-01&to=2027-01-02", "to", "the range must span at most 366 days")
	}

	private fun problem(
		query: String,
		field: String,
		message: String,
	) {
		mockMvc.get("$BASE$query").andExpect {
			status { isBadRequest() }
			content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
			jsonPath("$.errors[0].field") { value(field) }
			jsonPath("$.errors[0].message") { value(message) }
		}
	}

	@Test
	fun `a range of exactly 365 days after from is accepted and includes both ends`() {
		val first = create("""{"title":"New year call","list":"NEXT","dueDate":"2026-01-01"}""")
		val last = create("""{"title":"Next new year call","list":"NEXT","dueDate":"2027-01-01"}""")
		create("""{"title":"Just after","list":"NEXT","dueDate":"2027-01-02"}""")

		ids("?from=2026-01-01&to=2027-01-01") shouldContainExactlyInAnyOrder listOf(first, last)
	}

	@Test
	fun `tasks in the Trash are not listed in a range`() {
		val kept = create("""{"title":"Pay rent","list":"NEXT","dueDate":"2026-03-02"}""")
		val trashed = create("""{"title":"Old errand","list":"NEXT","dueDate":"2026-03-03"}""")
		val etag =
			mockMvc
				.get("$BASE/$trashed")
				.andExpect { status { isOk() } }
				.andReturn()
				.response
				.getHeader(HttpHeaders.ETAG)
		mockMvc
			.delete("$BASE/$trashed") {
				with(csrf())
				header(HttpHeaders.IF_MATCH, etag!!)
			}.andExpect { status { isNoContent() } }

		ids("?from=2026-03-02&to=2026-03-08") shouldContainExactlyInAnyOrder listOf(kept)
	}

	@Test
	fun `a task due in the range is listed even when its tickler date is later`() {
		val task = create("""{"title":"Renew passport","list":"NEXT","dueDate":"2026-03-04"}""")
		move(task, """{"list":"SOMEDAY","ticklerDate":"2026-04-01"}""")

		ids("?from=2026-03-02&to=2026-03-08") shouldContainExactlyInAnyOrder listOf(task)
		// Without a range it stays hidden in the tickler.
		ids("?list=SOMEDAY").shouldBeEmpty()
	}

	companion object {
		const val BASE = "/api/v1/tasks"
	}
}
