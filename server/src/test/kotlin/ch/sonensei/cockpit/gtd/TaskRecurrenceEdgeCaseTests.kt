package ch.sonensei.cockpit.gtd

import ch.sonensei.cockpit.core.auth.WithCockpitUser
import ch.sonensei.cockpit.core.events.domain.ChangeEvent
import ch.sonensei.cockpit.core.events.domain.ChangeOp
import ch.sonensei.cockpit.core.persistence.MutableClock
import ch.sonensei.cockpit.core.persistence.MutableClockTestConfiguration
import ch.sonensei.cockpit.core.settings.domain.SettingsService
import ch.sonensei.cockpit.gtd.domain.Project
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
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Instant

/**
 * Edge cases of recurring tasks (gtd-recurrence, SPEC.md §5.2): the Trash (of the project, of the next occurrence),
 * If-Match on complete, the search index of the new occurrence, the date anchors of FIXED and clearing a rule.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MutableClockTestConfiguration::class)
@RecordApplicationEvents
@WithCockpitUser
class TaskRecurrenceEdgeCaseTests(
	@Autowired private val mockMvc: MockMvc,
	@Autowired private val jsonMapper: JsonMapper,
	@Autowired private val projects: ProjectRepository,
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
		ifMatch: String? = null,
	) = mockMvc.post("$BASE/$id/$name") {
		with(csrf())
		ifMatch?.let { header(HttpHeaders.IF_MATCH, it) }
	}

	private fun complete(id: String): JsonNode = action(id, "complete").andExpect { status { isOk() } }.json()

	private fun patch(
		task: JsonNode,
		body: String,
	) = mockMvc.patch("$BASE/${task.id()}") {
		with(csrf())
		header(HttpHeaders.IF_MATCH, "\"${task.get("version").asLong()}\"")
		contentType = MediaType.APPLICATION_JSON
		content = body
	}

	private fun deleteTask(id: String) {
		val etag =
			mockMvc
				.get("$BASE/$id")
				.andExpect { status { isOk() } }
				.andReturn()
				.response
				.getHeader(HttpHeaders.ETAG)
		mockMvc
			.delete("$BASE/$id") {
				with(csrf())
				header(HttpHeaders.IF_MATCH, etag!!)
			}.andExpect { status { isNoContent() } }
	}

	private fun openTasks(): List<JsonNode> =
		mockMvc
			.get(BASE)
			.andExpect { status { isOk() } }
			.json()
			.get("items")
			.values()
			.toList()

	private fun count(
		sql: String,
		vararg args: Any,
	): Int? = jdbc.queryForObject(sql, Int::class.java, *args)

	private fun taskCount(): Int? = count("select count(*) from task")

	private fun nextOccurrenceId(id: String): String? =
		jdbc.queryForObject("select next_occurrence_id::text from task where id = ?::uuid", String::class.java, id)

	private fun ResultActionsDsl.isProblem(
		status: Int,
		vararg fields: String,
	) = andExpect {
		status { isEqualTo(status) }
		content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
		fields.forEach { field -> jsonPath("$.errors[?(@.field == '$field')]") { isNotEmpty() } }
	}

	// --- Trash

	@Test
	fun `a task whose project is in the Trash completes, and its next occurrence has no project`() {
		val project = projects.save(Project("Plan the garden"))
		val task =
			create(
				"""{"title":"Water the plants","projectId":"${project.id}","dueDate":"2026-03-01","recurrenceRule":"FREQ=DAILY","recurrenceMode":"FIXED"}""",
			)
		jdbc.update("update project set deleted_at = now() where id = ?", project.id)

		complete(task.id())

		val next = openTasks().single()
		next.get("projectId")?.isNull ?: true shouldBe true
		next.get("dueDate").asString() shouldBe "2026-03-02"
		taskCount() shouldBe 2
	}

	@Test
	fun `with the next occurrence in the Trash, uncompleting and completing again creates no other one`() {
		val task = create("""{"title":"Water the plants","dueDate":"2026-03-01","recurrenceRule":"FREQ=DAILY","recurrenceMode":"FIXED"}""")
		val id = task.id()
		complete(id)
		val next = openTasks().single()
		deleteTask(next.id())

		action(id, "uncomplete").andExpect { status { isOk() } }
		complete(id)

		taskCount() shouldBe 2
		openTasks() shouldBe emptyList()
		nextOccurrenceId(id) shouldBe next.id()
	}

	@Test
	fun `the next occurrence can be purged from the Trash, which clears the link to it`() {
		val task = create("""{"title":"Water the plants","dueDate":"2026-03-01","recurrenceRule":"FREQ=DAILY","recurrenceMode":"FIXED"}""")
		complete(task.id())
		val next = openTasks().single().id()
		deleteTask(next)

		mockMvc.delete("$TRASH/TASK/$next") { with(csrf()) }.andExpect { status { isNoContent() } }

		count("select count(*) from task where id = ?::uuid", next) shouldBe 0
		nextOccurrenceId(task.id()) shouldBe null
		action(task.id(), "uncomplete").andExpect { status { isOk() } }
	}

	@Test
	fun `a completed recurring task can be moved to the Trash and purged while its next occurrence stays`() {
		val task = create("""{"title":"Water the plants","dueDate":"2026-03-01","recurrenceRule":"FREQ=DAILY","recurrenceMode":"FIXED"}""")
		complete(task.id())
		deleteTask(task.id())

		mockMvc.delete("$TRASH/TASK/${task.id()}") { with(csrf()) }.andExpect { status { isNoContent() } }

		openTasks().single().get("dueDate").asString() shouldBe "2026-03-02"
	}

	// --- If-Match

	@Test
	fun `a stale If-Match on complete is 412 and creates no occurrence, a current one completes`() {
		val task = create("""{"title":"Water the plants","dueDate":"2026-03-01","recurrenceRule":"FREQ=DAILY","recurrenceMode":"FIXED"}""")
		val version = task.get("version").asLong()

		action(task.id(), "complete", "\"${version + 5}\"").isProblem(412)
		taskCount() shouldBe 1
		nextOccurrenceId(task.id()) shouldBe null

		action(task.id(), "complete", "\"$version\"").andExpect { status { isOk() } }
		taskCount() shouldBe 2
	}

	// --- search and events

	@Test
	fun `the next occurrence is in the search index and announced as CREATED`(events: ApplicationEvents) {
		val task =
			create(
				"""{"title":"Water the plants","notesMd":"Use rain water","dueDate":"2026-03-01","recurrenceRule":"FREQ=DAILY","recurrenceMode":"FIXED"}""",
			)

		complete(task.id())

		val next = openTasks().single().id()
		jdbc.queryForObject(
			"select title from search_document where item_type = 'TASK' and item_id = ?::uuid",
			String::class.java,
			next,
		) shouldBe "Water the plants"
		events
			.stream(ChangeEvent::class.java)
			.toList()
			.filter { it.type == "TASK" && it.id.toString() == next }
			.map { it.op } shouldBe listOf(ChangeOp.CREATED)
	}

	// --- date anchors

	@Test
	fun `a FIXED task with only a scheduled date moves that date and gets no due date`() {
		val task =
			create(
				"""{"title":"Water the plants","list":"SCHEDULED","scheduledDate":"2026-03-02","recurrenceRule":"FREQ=WEEKLY;BYDAY=MO","recurrenceMode":"FIXED"}""",
			)

		complete(task.id())

		val next = openTasks().single()
		next.get("scheduledDate").asString() shouldBe "2026-03-09"
		next.get("dueDate")?.isNull ?: true shouldBe true
	}

	@Test
	fun `a FIXED task without dates is next due after the completion day in the user's zone`() {
		val task = create("""{"title":"Water the plants","recurrenceRule":"FREQ=DAILY;INTERVAL=2","recurrenceMode":"FIXED"}""")
		// 2026-03-04T23:30Z is 00:30 on Thursday 2026-03-05 in Zurich.
		clock.set(Instant.parse("2026-03-04T23:30:00Z"))

		complete(task.id())

		openTasks().single().get("dueDate").asString() shouldBe "2026-03-07"
	}

	@Test
	fun `a FIXED weekly Monday task due on a Wednesday is next due on the following Monday`() {
		val task =
			create("""{"title":"Take out the bins","dueDate":"2026-03-04","recurrenceRule":"FREQ=WEEKLY;BYDAY=MO","recurrenceMode":"FIXED"}""")

		complete(task.id())

		openTasks().single().get("dueDate").asString() shouldBe "2026-03-09"
	}

	@Test
	fun `an AFTER_COMPLETION task moves its due and scheduled dates by the same number of days`() {
		val task =
			create(
				"""
				{"title":"Water the plants","list":"SCHEDULED","scheduledDate":"2026-03-01","dueDate":"2026-03-03",
				 "recurrenceRule":"FREQ=DAILY;INTERVAL=7","recurrenceMode":"AFTER_COMPLETION"}
				""".trimIndent(),
			)
		clock.set(Instant.parse("2026-03-10T12:00:00Z"))

		complete(task.id())

		val next = openTasks().single()
		next.get("dueDate").asString() shouldBe "2026-03-17"
		next.get("scheduledDate").asString() shouldBe "2026-03-15"
	}

	// --- clearing a rule

	@Test
	fun `PATCH can clear rule and mode together, but not the rule alone`() {
		val task = create("""{"title":"Water the plants","dueDate":"2026-03-01","recurrenceRule":"FREQ=DAILY","recurrenceMode":"FIXED"}""")

		patch(task, """{"recurrenceRule":null}""").isProblem(400, "recurrenceMode")
		val cleared =
			patch(task, """{"recurrenceRule":null,"recurrenceMode":null}""")
				.andExpect { status { isOk() } }
				.json()
		cleared.get("recurrenceRule")?.isNull ?: true shouldBe true

		complete(task.id())

		taskCount() shouldBe 1
	}

	private companion object {
		const val BASE = "/api/v1/tasks"
		const val TRASH = "/api/v1/trash"
	}
}
