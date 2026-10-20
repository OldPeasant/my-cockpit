package ch.sonensei.cockpit.gtd

import ch.sonensei.cockpit.core.auth.WithCockpitUser
import ch.sonensei.cockpit.core.events.domain.ChangeEvent
import ch.sonensei.cockpit.core.events.domain.ChangeOp
import ch.sonensei.cockpit.core.persistence.UuidV7
import ch.sonensei.cockpit.gtd.domain.Task
import ch.sonensei.cockpit.gtd.infra.TaskRepository
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
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
import java.time.Clock

@SpringBootTest
@AutoConfigureMockMvc
@RecordApplicationEvents
@WithCockpitUser
class ChecklistWebTests(
	@Autowired private val mockMvc: MockMvc,
	@Autowired private val jsonMapper: JsonMapper,
	@Autowired private val tasks: TaskRepository,
	@Autowired private val clock: Clock,
	@Autowired private val jdbc: JdbcTemplate,
) : PostgresTestBase() {
	private lateinit var task: Task

	@BeforeEach
	fun reset() {
		jdbc.execute("update project set next_task_id = null")
		jdbc.execute("delete from checklist_item")
		jdbc.execute("delete from task_context")
		jdbc.execute("delete from task")
		task = tasks.save(Task("Pack for the trip"))
	}

	private val base get() = "/api/v1/tasks/${task.id}/checklist"

	private fun add(text: String): JsonNode =
		mockMvc
			.post(base) {
				with(csrf())
				contentType = MediaType.APPLICATION_JSON
				content = """{"text":"$text"}"""
			}.andExpect { status { isCreated() } }
			.andReturn()
			.response.contentAsString
			.let(jsonMapper::readTree)

	private fun ResultActionsDsl.isProblem(status: Int) =
		andExpect {
			status { isEqualTo(status) }
			content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
		}

	@Test
	fun `items are added at the end, listed in order and announce the task as updated`(events: ApplicationEvents) {
		val first = add("Passport")
		val second = add("Charger")
		first.get("position").asInt() shouldBe 0
		second.get("position").asInt() shouldBe 1
		first.get("done").asBoolean() shouldBe false

		mockMvc.get(base).andExpect {
			status { isOk() }
			jsonPath("$.items[0].text") { value("Passport") }
			jsonPath("$.items[1].text") { value("Charger") }
		}
		events
			.stream(ChangeEvent::class.java)
			.toList()
			.map { it.type to it.op }
			.distinct() shouldContainExactly
			listOf("TASK" to ChangeOp.UPDATED)
	}

	@Test
	fun `PATCH changes an item with If-Match, DELETE removes it`() {
		val item = add("Passport")
		val id = item.get("id").asString()
		mockMvc
			.patch("$base/$id") {
				with(csrf())
				contentType = MediaType.APPLICATION_JSON
				content = """{"done":true}"""
			}.isProblem(428)
		mockMvc
			.patch("$base/$id") {
				with(csrf())
				contentType = MediaType.APPLICATION_JSON
				content = """{"done":true}"""
				header(HttpHeaders.IF_MATCH, "\"9\"")
			}.isProblem(412)
		mockMvc
			.patch("$base/$id") {
				with(csrf())
				contentType = MediaType.APPLICATION_JSON
				content = """{"done":true,"text":"Passport and ID"}"""
				header(HttpHeaders.IF_MATCH, "\"0\"")
			}.andExpect {
				status { isOk() }
				header { string(HttpHeaders.ETAG, "\"1\"") }
				jsonPath("$.done") { value(true) }
				jsonPath("$.text") { value("Passport and ID") }
			}
		mockMvc
			.delete("$base/$id") {
				with(csrf())
				header(HttpHeaders.IF_MATCH, "\"0\"")
			}.isProblem(412)
		mockMvc
			.delete("$base/$id") {
				with(csrf())
				header(HttpHeaders.IF_MATCH, "\"1\"")
			}.andExpect { status { isNoContent() } }
		mockMvc.get(base).andExpect { jsonPath("$.items.length()") { value(0) } }
	}

	@Test
	fun `validation and not found`() {
		mockMvc
			.post(base) {
				with(csrf())
				contentType = MediaType.APPLICATION_JSON
				content = """{"text":" "}"""
			}.isProblem(400)
		mockMvc
			.post(base) {
				with(csrf())
				contentType = MediaType.APPLICATION_JSON
				content = """{}"""
			}.isProblem(400)
		mockMvc.get("/api/v1/tasks/${UuidV7.generate()}/checklist").isProblem(404)

		// An item is only reachable under its own task.
		val item = add("Passport")
		val other = tasks.save(Task("Other"))
		mockMvc
			.delete("/api/v1/tasks/${other.id}/checklist/${item.get("id").asString()}") {
				with(csrf())
				header(HttpHeaders.IF_MATCH, "*")
			}.isProblem(404)

		// A task in the Trash has no checklist.
		task.softDelete(clock)
		tasks.save(task)
		mockMvc.get(base).isProblem(404)
	}
}
