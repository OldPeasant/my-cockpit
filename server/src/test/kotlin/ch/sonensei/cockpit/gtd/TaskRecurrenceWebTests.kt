package ch.sonensei.cockpit.gtd

import ch.sonensei.cockpit.core.auth.WithCockpitUser
import ch.sonensei.cockpit.core.events.domain.ChangeEvent
import ch.sonensei.cockpit.core.events.domain.ChangeOp
import ch.sonensei.cockpit.core.persistence.MutableClock
import ch.sonensei.cockpit.core.persistence.MutableClockTestConfiguration
import ch.sonensei.cockpit.core.settings.domain.SettingsService
import ch.sonensei.cockpit.gtd.domain.Context
import ch.sonensei.cockpit.gtd.domain.Project
import ch.sonensei.cockpit.gtd.infra.ContextRepository
import ch.sonensei.cockpit.gtd.infra.ProjectRepository
import ch.sonensei.cockpit.support.PostgresTestBase
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
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Instant

/**
 * Recurring tasks (gtd-recurrence, SPEC.md §5.2): RRULE validation, and the one next occurrence that completing a
 * recurring task creates (FIXED and AFTER_COMPLETION, in the user's time zone, across DST changes).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MutableClockTestConfiguration::class)
@RecordApplicationEvents
@WithCockpitUser
class TaskRecurrenceWebTests(
	@Autowired private val mockMvc: MockMvc,
	@Autowired private val jsonMapper: JsonMapper,
	@Autowired private val projects: ProjectRepository,
	@Autowired private val contexts: ContextRepository,
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
		jdbc.execute("delete from context")
		jdbc.execute("delete from search_document")
		settings.update(mapOf("timeZone" to "Europe/Zurich"))
		clock.set(MutableClockTestConfiguration.START) // 2026-03-01 in Europe/Zurich
	}

	@AfterEach
	fun resetTimeZone() {
		settings.update(mapOf("timeZone" to null))
	}

	// --- helpers

	private fun post(body: String) =
		mockMvc.post(BASE) {
			with(csrf())
			contentType = MediaType.APPLICATION_JSON
			content = body
		}

	private fun create(body: String): JsonNode = post(body).andExpect { status { isCreated() } }.json()

	private fun ResultActionsDsl.json(): JsonNode = andReturn().response.contentAsString.let(jsonMapper::readTree)

	private fun JsonNode.id(): String = get("id").asString()

	private fun action(
		id: String,
		name: String,
	) = mockMvc.post("$BASE/$id/$name") { with(csrf()) }

	private fun complete(id: String): JsonNode = action(id, "complete").andExpect { status { isOk() } }.json()

	/** Completes the task at [at] and returns the only open task left (its next occurrence). */
	private fun completeAt(
		id: String,
		at: String,
	): JsonNode {
		clock.set(Instant.parse(at))
		complete(id)
		return openTasks().single()
	}

	private fun openTasks(): List<JsonNode> =
		mockMvc
			.get(BASE)
			.andExpect { status { isOk() } }
			.json()
			.get("items")
			.values()
			.toList()

	private fun taskCount(): Int? = jdbc.queryForObject("select count(*) from task", Int::class.java)

	private fun ResultActionsDsl.isProblem(
		status: Int,
		vararg fields: String,
	) = andExpect {
		status { isEqualTo(status) }
		content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
		fields.forEach { field -> jsonPath("$.errors[?(@.field == '$field')]") { isNotEmpty() } }
	}

	// --- validation

	@Test
	fun `POST and PATCH accept a valid RRULE with a mode`() {
		val task = create("""{"title":"Water the plants","recurrenceRule":"FREQ=WEEKLY;BYDAY=MO","recurrenceMode":"AFTER_COMPLETION"}""")
		task.get("recurrenceRule").asString() shouldBe "FREQ=WEEKLY;BYDAY=MO"
		task.get("recurrenceMode").asString() shouldBe "AFTER_COMPLETION"

		mockMvc
			.patch("$BASE/${task.id()}") {
				with(csrf())
				header(HttpHeaders.IF_MATCH, "\"${task.get("version").asLong()}\"")
				contentType = MediaType.APPLICATION_JSON
				content = """{"recurrenceRule":"FREQ=MONTHLY;BYMONTHDAY=1","recurrenceMode":"FIXED"}"""
			}.andExpect {
				status { isOk() }
				jsonPath("$.recurrenceRule") { value("FREQ=MONTHLY;BYMONTHDAY=1") }
				jsonPath("$.recurrenceMode") { value("FIXED") }
			}
	}

	@Test
	fun `invalid rules and a rule without a mode or a mode without a rule are 400`() {
		listOf("FREQ=FORTNIGHTLY", "RRULE:FREQ=DAILY", "FREQ=HOURLY", "BYDAY=MO", "FREQ=DAILY;COUNT=0").forEach { rule ->
			post("""{"title":"Water the plants","recurrenceRule":"$rule","recurrenceMode":"FIXED"}""").isProblem(400, "recurrenceRule")
		}
		post("""{"title":"Water the plants","recurrenceRule":"FREQ=DAILY"}""").isProblem(400, "recurrenceMode")
		post("""{"title":"Water the plants","recurrenceMode":"FIXED"}""").isProblem(400, "recurrenceMode")

		val task = create("""{"title":"Water the plants","recurrenceRule":"FREQ=DAILY","recurrenceMode":"FIXED"}""")
		mockMvc
			.patch("$BASE/${task.id()}") {
				with(csrf())
				header(HttpHeaders.IF_MATCH, "\"${task.get("version").asLong()}\"")
				contentType = MediaType.APPLICATION_JSON
				content = """{"recurrenceRule":"FREQ=DAILY;BYDAY=XX"}"""
			}.isProblem(400, "recurrenceRule")
		taskCount() shouldBe 1
	}

	// --- next occurrence

	@Test
	fun `a weekly Monday task completed on Monday is next due the following Monday, exactly once`(events: ApplicationEvents) {
		val task =
			create(
				"""{"title":"Take out the bins","list":"NEXT","dueDate":"2026-03-02","recurrenceRule":"FREQ=WEEKLY;BYDAY=MO","recurrenceMode":"FIXED"}""",
			)

		val next = completeAt(task.id(), "2026-03-02T07:00:00Z")

		next.get("dueDate").asString() shouldBe "2026-03-09"
		next.get("title").asString() shouldBe "Take out the bins"
		next.get("list").asString() shouldBe "NEXT"
		next.get("recurrenceRule").asString() shouldBe "FREQ=WEEKLY;BYDAY=MO"
		next.get("recurrenceMode").asString() shouldBe "FIXED"
		next.get("completedAt")?.isNull ?: true shouldBe true
		taskCount() shouldBe 2
		events
			.stream(ChangeEvent::class.java)
			.toList()
			.filter { it.type == "TASK" && it.op == ChangeOp.CREATED }
			.size shouldBe 2
	}

	@Test
	fun `an AFTER_COMPLETION weekly Monday task completed on Monday is next due the following Monday`() {
		val task =
			create(
				"""{"title":"Take out the bins","dueDate":"2026-03-02","recurrenceRule":"FREQ=WEEKLY;BYDAY=MO","recurrenceMode":"AFTER_COMPLETION"}""",
			)

		completeAt(task.id(), "2026-03-02T18:00:00Z").get("dueDate").asString() shouldBe "2026-03-09"
	}

	@Test
	fun `an AFTER_COMPLETION task completed late is due relative to the completion date`() {
		val task =
			create(
				"""{"title":"Water the plants","dueDate":"2026-03-02","recurrenceRule":"FREQ=DAILY;INTERVAL=7","recurrenceMode":"AFTER_COMPLETION"}""",
			)

		// Completed eight days late, on Tuesday 2026-03-10.
		completeAt(task.id(), "2026-03-10T12:00:00Z").get("dueDate").asString() shouldBe "2026-03-17"
	}

	@Test
	fun `a FIXED task completed late keeps its schedule, even if the next date has passed`() {
		val task =
			create("""{"title":"Water the plants","dueDate":"2026-03-02","recurrenceRule":"FREQ=DAILY;INTERVAL=7","recurrenceMode":"FIXED"}""")

		completeAt(task.id(), "2026-03-10T12:00:00Z").get("dueDate").asString() shouldBe "2026-03-09"
	}

	@Test
	fun `an AFTER_COMPLETION weekly Monday task completed on Wednesday is next due the following Monday`() {
		val task = create("""{"title":"Take out the bins","recurrenceRule":"FREQ=WEEKLY;BYDAY=MO","recurrenceMode":"AFTER_COMPLETION"}""")

		completeAt(task.id(), "2026-03-04T12:00:00Z").get("dueDate").asString() shouldBe "2026-03-09"
	}

	@Test
	fun `the completion day is the user's day across the spring DST change`() {
		val task = create("""{"title":"Water the plants","recurrenceRule":"FREQ=DAILY","recurrenceMode":"AFTER_COMPLETION"}""")

		// 2026-03-28T23:30Z is 00:30 CET on Sunday 2026-03-29 in Zurich, the day clocks go forward (UTC: still the 28th).
		completeAt(task.id(), "2026-03-28T23:30:00Z").get("dueDate").asString() shouldBe "2026-03-30"
	}

	@Test
	fun `the completion day is the user's day across the autumn DST change`() {
		val task = create("""{"title":"Water the plants","recurrenceRule":"FREQ=DAILY","recurrenceMode":"AFTER_COMPLETION"}""")

		// 2026-10-24T22:30Z is 00:30 CEST on Sunday 2026-10-25 in Zurich, the day clocks go back (UTC: still the 24th).
		completeAt(task.id(), "2026-10-24T22:30:00Z").get("dueDate").asString() shouldBe "2026-10-26"
	}

	@Test
	fun `a FIXED task with a due time over the DST change keeps its local due date and time`() {
		val task =
			create(
				"""{"title":"Call the plumber","dueDate":"2026-03-28","dueTime":"09:00:00","recurrenceRule":"FREQ=WEEKLY","recurrenceMode":"FIXED"}""",
			)

		val next = completeAt(task.id(), "2026-03-28T08:00:00Z")

		next.get("dueDate").asString() shouldBe "2026-04-04"
		next.get("dueTime").asString() shouldBe "09:00:00"
	}

	@Test
	fun `uncompleting and completing again creates no duplicate, nor does completing a done task`() {
		val task = create("""{"title":"Water the plants","dueDate":"2026-03-01","recurrenceRule":"FREQ=DAILY","recurrenceMode":"FIXED"}""")
		val id = task.id()

		complete(id)
		complete(id)
		taskCount() shouldBe 2
		action(id, "uncomplete").andExpect { status { isOk() } }
		openTasks().size shouldBe 2
		complete(id)

		taskCount() shouldBe 2
		openTasks().single().get("dueDate").asString() shouldBe "2026-03-02"
	}

	@Test
	fun `completing a task without a rule creates nothing`() {
		val task = create("""{"title":"Water the plants","dueDate":"2026-03-01"}""")

		complete(task.id())

		taskCount() shouldBe 1
	}

	@Test
	fun `COUNT counts down and the last occurrence creates none, nor does a passed UNTIL`() {
		val counted =
			create("""{"title":"Water the plants","dueDate":"2026-03-01","recurrenceRule":"FREQ=DAILY;COUNT=2","recurrenceMode":"FIXED"}""")
		val last = completeAt(counted.id(), "2026-03-01T08:00:00Z")
		last.get("recurrenceRule").asString() shouldBe "FREQ=DAILY;COUNT=1"
		complete(last.id())
		openTasks() shouldBe emptyList()

		val until =
			create("""{"title":"Feed the cat","dueDate":"2026-03-05","recurrenceRule":"FREQ=DAILY;UNTIL=20260305","recurrenceMode":"FIXED"}""")
		complete(until.id())
		openTasks() shouldBe emptyList()
		taskCount() shouldBe 3
	}

	@Test
	fun `the next occurrence copies the task's fields and an unchecked checklist`() {
		val project = projects.save(Project("Plan the garden"))
		val home = contexts.save(Context("@home"))
		val task =
			create(
				"""
				{"title":"Water the plants","notesMd":"Use rain water","projectId":"${project.id}","contextIds":["${home.id}"],
				 "energy":"LOW","creativity":"ROUTINE","timeEstimateMin":10,"list":"SCHEDULED","scheduledDate":"2026-03-01",
				 "dueDate":"2026-03-03","recurrenceRule":"FREQ=WEEKLY","recurrenceMode":"FIXED"}
				""".trimIndent(),
			)
		listOf("Fill the can", "Check the soil").forEach { text ->
			mockMvc
				.post("$BASE/${task.id()}/checklist") {
					with(csrf())
					contentType = MediaType.APPLICATION_JSON
					content = """{"text":"$text","done":true}"""
				}.andExpect { status { isCreated() } }
		}

		val next = completeAt(task.id(), "2026-03-01T08:00:00Z")

		next.get("notesMd").asString() shouldBe "Use rain water"
		next.get("projectId").asString() shouldBe project.id.toString()
		next.get("contextIds").values().map { it.asString() } shouldBe listOf(home.id.toString())
		next.get("energy").asString() shouldBe "LOW"
		next.get("creativity").asString() shouldBe "ROUTINE"
		next.get("timeEstimateMin").asInt() shouldBe 10
		next.get("list").asString() shouldBe "SCHEDULED"
		next.get("dueDate").asString() shouldBe "2026-03-10"
		next.get("scheduledDate").asString() shouldBe "2026-03-08"
		val checklist = mockMvc.get("$BASE/${next.id()}/checklist").andExpect { status { isOk() } }.json()
		val items = checklist.get("items")?.values()?.toList() ?: checklist.values().toList()
		items.map { it.get("text").asString() } shouldBe listOf("Fill the can", "Check the soil")
		items.map { it.get("done").asBoolean() } shouldBe listOf(false, false)
	}

	@Test
	fun `a waiting task's next occurrence waits since today`() {
		val task =
			create(
				"""{"title":"Contract","list":"WAITING","waitingForText":"Anna","waitingSince":"2026-02-01","recurrenceRule":"FREQ=MONTHLY","recurrenceMode":"AFTER_COMPLETION"}""",
			)

		val next = completeAt(task.id(), "2026-03-05T12:00:00Z")

		next.get("list").asString() shouldBe "WAITING"
		next.get("waitingForText").asString() shouldBe "Anna"
		next.get("waitingSince").asString() shouldBe "2026-03-05"
		next.get("dueDate").asString() shouldBe "2026-04-05"
	}

	private companion object {
		const val BASE = "/api/v1/tasks"
	}
}
