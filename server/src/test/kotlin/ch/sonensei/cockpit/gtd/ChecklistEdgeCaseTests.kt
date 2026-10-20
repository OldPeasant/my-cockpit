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

/** Edge cases of the checklist API (gtd-tasks-api) beyond [ChecklistWebTests]. */
@SpringBootTest
@AutoConfigureMockMvc
@RecordApplicationEvents
@WithCockpitUser
class ChecklistEdgeCaseTests(
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
		task = tasks.save(Task("Prepare the camping trip"))
	}

	private val base get() = "/api/v1/tasks/${task.id}/checklist"

	private fun post(body: String) =
		mockMvc.post(base) {
			with(csrf())
			contentType = MediaType.APPLICATION_JSON
			content = body
		}

	private fun add(text: String): JsonNode =
		post("""{"text":"$text"}""")
			.andExpect { status { isCreated() } }
			.andReturn()
			.response.contentAsString
			.let(jsonMapper::readTree)

	private fun patch(
		itemId: String,
		body: String,
		ifMatch: String,
	) = mockMvc.patch("$base/$itemId") {
		with(csrf())
		contentType = MediaType.APPLICATION_JSON
		content = body
		header(HttpHeaders.IF_MATCH, ifMatch)
	}

	private fun texts(): List<String> =
		mockMvc
			.get(base)
			.andExpect { status { isOk() } }
			.andReturn()
			.response.contentAsString
			.let(jsonMapper::readTree)
			.get("items")
			.values()
			.map { it.get("text").asString() }

	private fun ResultActionsDsl.isProblem(
		status: Int,
		field: String? = null,
	) = andExpect {
		status { isEqualTo(status) }
		content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
		field?.let { jsonPath("$.errors[?(@.field == '$it')]") { isNotEmpty() } }
	}

	@Test
	fun `POST returns Location and ETag and accepts done, text up to 1000 characters`() {
		val result =
			post("""{"text":"Tent","done":true}""")
				.andExpect {
					status { isCreated() }
					header { string(HttpHeaders.ETAG, "\"0\"") }
					jsonPath("$.done") { value(true) }
					jsonPath("$.taskId") { value(task.id.toString()) }
				}.andReturn()
		val id = jsonMapper.readTree(result.response.contentAsString).get("id").asString()
		result.response.getHeader(HttpHeaders.LOCATION) shouldBe "http://localhost$base/$id"

		add("x".repeat(1000)).get("text").asString().length shouldBe 1000
		post("""{"text":"${"x".repeat(1001)}"}""").isProblem(400, "text")
	}

	@Test
	fun `PATCH changes only the sent fields and can reorder items`() {
		val tent = add("Tent")
		add("Stove")
		val lamp = add("Lamp")

		patch(tent.get("id").asString(), """{"done":true}""", "\"0\"").andExpect {
			status { isOk() }
			jsonPath("$.text") { value("Tent") }
			jsonPath("$.done") { value(true) }
			jsonPath("$.position") { value(0) }
		}
		patch(tent.get("id").asString(), """{"text":"Tent and pegs"}""", "\"1\"").andExpect {
			jsonPath("$.done") { value(true) }
			jsonPath("$.text") { value("Tent and pegs") }
		}
		patch(lamp.get("id").asString(), """{"position":-1}""", "\"0\"").andExpect { status { isOk() } }
		texts() shouldContainExactly listOf("Lamp", "Tent and pegs", "Stove")
	}

	@Test
	fun `PATCH rejects blank and too long text and keeps the item`() {
		val id = add("Tent").get("id").asString()
		patch(id, """{"text":"  "}""", "\"0\"").isProblem(400, "text")
		patch(id, """{"text":"${"x".repeat(1001)}"}""", "\"0\"").isProblem(400, "text")
		texts() shouldBe listOf("Tent")
	}

	@Test
	fun `DELETE needs If-Match, unknown items are 404, a task in the Trash takes no new items`() {
		val id = add("Tent").get("id").asString()
		mockMvc.delete("$base/$id") { with(csrf()) }.isProblem(428)
		patch(UuidV7.generate().toString(), """{"done":true}""", "*").isProblem(404)
		mockMvc
			.delete("$base/${UuidV7.generate()}") {
				with(csrf())
				header(HttpHeaders.IF_MATCH, "*")
			}.isProblem(404)

		task.softDelete(clock)
		tasks.save(task)
		post("""{"text":"Stove"}""").isProblem(404)
		patch(id, """{"done":true}""", "\"0\"").isProblem(404)
		jdbc.queryForObject("select count(*) from checklist_item", Int::class.java) shouldBe 1
	}

	@Test
	fun `every checklist change announces the task as UPDATED and re-indexes it`(events: ApplicationEvents) {
		val id = add("Tent").get("id").asString()
		patch(id, """{"done":true}""", "\"0\"").andExpect { status { isOk() } }
		mockMvc
			.delete("$base/$id") {
				with(csrf())
				header(HttpHeaders.IF_MATCH, "\"1\"")
			}.andExpect { status { isNoContent() } }

		events
			.stream(ChangeEvent::class.java)
			.toList()
			.map { Triple(it.type, it.op, it.id) } shouldContainExactly
			List(3) { Triple("TASK", ChangeOp.UPDATED, task.id) }
		jdbc.queryForObject(
			"select title from search_document where item_type = 'TASK' and item_id = ?",
			String::class.java,
			task.id,
		) shouldBe "Prepare the camping trip"
	}
}
