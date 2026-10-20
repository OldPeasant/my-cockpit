package ch.sonensei.cockpit.gtd

import ch.sonensei.cockpit.core.auth.WithCockpitUser
import ch.sonensei.cockpit.core.events.domain.ChangeEvent
import ch.sonensei.cockpit.core.events.domain.ChangeOp
import ch.sonensei.cockpit.core.persistence.UuidV7
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
import org.springframework.test.web.servlet.put
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

@SpringBootTest
@AutoConfigureMockMvc
@RecordApplicationEvents
@WithCockpitUser
class AreaContextWebTests(
	@Autowired private val mockMvc: MockMvc,
	@Autowired private val jsonMapper: JsonMapper,
	@Autowired private val jdbc: JdbcTemplate,
) : PostgresTestBase() {
	@BeforeEach
	fun reset() {
		jdbc.execute("update project set next_task_id = null")
		jdbc.execute("delete from checklist_item")
		jdbc.execute("delete from task_context")
		jdbc.execute("delete from task")
		jdbc.execute("delete from project")
		jdbc.execute("delete from area")
		jdbc.execute("delete from context")
		jdbc.execute("delete from search_document")
	}

	// --- helpers

	private fun post(
		path: String,
		body: String,
	) = mockMvc.post(path) {
		with(csrf())
		contentType = MediaType.APPLICATION_JSON
		content = body
	}

	private fun patch(
		path: String,
		body: String,
		ifMatch: String?,
	) = mockMvc.patch(path) {
		with(csrf())
		contentType = MediaType.APPLICATION_JSON
		content = body
		ifMatch?.let { header(HttpHeaders.IF_MATCH, it) }
	}

	private fun delete(
		path: String,
		ifMatch: String?,
	) = mockMvc.delete(path) {
		with(csrf())
		ifMatch?.let { header(HttpHeaders.IF_MATCH, it) }
	}

	private fun put(
		path: String,
		ids: List<String>,
	) = mockMvc.put(path) {
		with(csrf())
		contentType = MediaType.APPLICATION_JSON
		content = """{"ids":[${ids.joinToString(",") { "\"$it\"" }}]}"""
	}

	private fun ResultActionsDsl.json(): JsonNode = andReturn().response.contentAsString.let(jsonMapper::readTree)

	private fun created(
		path: String,
		body: String,
	): JsonNode = post(path, body).andExpect { status { isCreated() } }.json()

	private fun JsonNode.id(): String = get("id").asString()

	private fun JsonNode.etag(): String = "\"${get("version").asLong()}\""

	private fun list(path: String): List<JsonNode> =
		mockMvc
			.get(path)
			.andExpect { status { isOk() } }
			.json()
			.get("items")
			.values()
			.toList()

	private fun ResultActionsDsl.isProblem(
		status: Int,
		vararg fields: String,
	) = andExpect {
		status { isEqualTo(status) }
		content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
		fields.forEach { field -> jsonPath("$.errors[?(@.field == '$field')]") { isNotEmpty() } }
	}

	private fun ApplicationEvents.changes(type: String): List<Pair<ChangeOp, String>> =
		stream(ChangeEvent::class.java).toList().filter { it.type == type }.map { it.op to it.id.toString() }

	// --- areas

	@Test
	fun `areas are created at the end, listed in order, and announced`(events: ApplicationEvents) {
		val work = created(AREAS, """{"title":"Work"}""")
		val family = created(AREAS, """{"title":"Family"}""")
		val first = created(AREAS, """{"title":"Health","position":-1}""")
		work.get("position").asInt() shouldBe 0
		family.get("position").asInt() shouldBe 1
		list(AREAS).map { it.id() } shouldContainExactly listOf(first.id(), work.id(), family.id())
		mockMvc.get("$AREAS/${work.id()}").andExpect {
			status { isOk() }
			header { string(HttpHeaders.ETAG, "\"0\"") }
			jsonPath("$.title") { value("Work") }
		}
		events.changes("AREA") shouldContainExactly listOf(work, family, first).map { ChangeOp.CREATED to it.id() }
	}

	@Test
	fun `area validation, If-Match and 404`() {
		post(AREAS, """{}""").isProblem(400, "title")
		post(AREAS, """{"title":" "}""").isProblem(400, "title")
		post(AREAS, """{"title":"${"x".repeat(201)}"}""").isProblem(400, "title")
		val area = created(AREAS, """{"title":"Work"}""")
		val path = "$AREAS/${area.id()}"
		patch(path, """{"title":"Job"}""", null).isProblem(428)
		patch(path, """{"title":"Job"}""", "\"3\"").isProblem(412)
		patch(path, """{"title":""}""", area.etag()).isProblem(400, "title")
		patch(path, """{"title":"Job","position":5}""", area.etag()).andExpect {
			status { isOk() }
			header { string(HttpHeaders.ETAG, "\"1\"") }
			jsonPath("$.title") { value("Job") }
			jsonPath("$.position") { value(5) }
		}
		mockMvc.get("$AREAS/${UuidV7.generate()}").isProblem(404)
		delete("$AREAS/${UuidV7.generate()}", "*").isProblem(404)
	}

	@Test
	fun `area PATCH rejects nulls, unknown fields and bad values without changing or announcing the area`(events: ApplicationEvents) {
		val area = created(AREAS, """{"title":"Work"}""")
		val path = "$AREAS/${area.id()}"
		patch(path, """{"title":null}""", area.etag()).isProblem(400, "title")
		patch(path, """{"position":null}""", area.etag()).isProblem(400, "position")
		patch(path, """{"titel":"Job"}""", area.etag()).isProblem(400, "titel")
		patch(path, """{"position":"first"}""", area.etag()).isProblem(400, "position")
		mockMvc.get(path).andExpect {
			status { isOk() }
			header { string(HttpHeaders.ETAG, "\"0\"") }
			jsonPath("$.title") { value("Work") }
		}
		events.changes("AREA") shouldContainExactly listOf(ChangeOp.CREATED to area.id())
	}

	@Test
	fun `PUT areas order needs every area exactly once`() {
		val a = created(AREAS, """{"title":"A"}""").id()
		val b = created(AREAS, """{"title":"B"}""").id()
		val c = created(AREAS, """{"title":"C"}""").id()
		put("$AREAS/order", listOf(c, a, b))
			.andExpect { status { isOk() } }
			.json()
			.get("items")
			.values()
			.map { it.id() } shouldContainExactly
			listOf(c, a, b)
		list(AREAS).map { it.get("position").asInt() } shouldContainExactly listOf(0, 1, 2)
		put("$AREAS/order", listOf(c, a)).isProblem(400, "ids")
		put("$AREAS/order", listOf(c, a, b, b)).isProblem(400, "ids")
		put("$AREAS/order", listOf(c, a, b, UuidV7.generate().toString())).isProblem(400, "ids")
		list(AREAS).map { it.id() } shouldContainExactly listOf(c, a, b)
	}

	@Test
	fun `deleting an area takes its projects out of it, with a new version and a change event`(events: ApplicationEvents) {
		val area = created(AREAS, """{"title":"Home"}""")
		val project = created(PROJECTS, """{"title":"Renovate kitchen","areaId":"${area.id()}"}""")
		delete("$AREAS/${area.id()}", null).isProblem(428)
		delete("$AREAS/${area.id()}", area.etag()).andExpect { status { isNoContent() } }
		list(AREAS) shouldBe emptyList()
		mockMvc.get("$PROJECTS/${project.id()}").andExpect {
			status { isOk() }
			jsonPath("$.areaId") { doesNotExist() }
			jsonPath("$.version") { value(1) }
		}
		events.changes("AREA").last() shouldBe (ChangeOp.DELETED to area.id())
		events.changes("PROJECT").last() shouldBe (ChangeOp.UPDATED to project.id())
	}

	// --- contexts

	@Test
	fun `contexts are created trimmed at the end with an optional color and announced`(events: ApplicationEvents) {
		val home = created(CONTEXTS, """{"name":" @home ","color":"#3366ff"}""")
		val phone = created(CONTEXTS, """{"name":"@phone"}""")
		home.get("name").asString() shouldBe "@home"
		home.get("color").asString() shouldBe "#3366ff"
		home.get("position").asInt() shouldBe 0
		phone.get("position").asInt() shouldBe 1
		list(CONTEXTS).map { it.id() } shouldContainExactly listOf(home.id(), phone.id())
		events.changes("CONTEXT") shouldContainExactly listOf(ChangeOp.CREATED to home.id(), ChangeOp.CREATED to phone.id())
	}

	@Test
	fun `context names are unique regardless of case, also on rename`() {
		val home = created(CONTEXTS, """{"name":"@home"}""")
		post(CONTEXTS, """{"name":"@HOME"}""").isProblem(409)
		val phone = created(CONTEXTS, """{"name":"@phone"}""")
		patch("$CONTEXTS/${phone.id()}", """{"name":" @Home"}""", phone.etag()).isProblem(409)
		// Renaming a context to another spelling of its own name is fine.
		patch("$CONTEXTS/${home.id()}", """{"name":"@Home"}""", home.etag()).andExpect {
			status { isOk() }
			jsonPath("$.name") { value("@Home") }
		}
	}

	@Test
	fun `context validation, PATCH clears the color, If-Match and 404`() {
		post(CONTEXTS, """{}""").isProblem(400, "name")
		post(CONTEXTS, """{"name":"  "}""").isProblem(400, "name")
		post(CONTEXTS, """{"name":"@x","color":"blue"}""").isProblem(400, "color")
		post(CONTEXTS, """{"name":"@x","color":"#12345"}""").isProblem(400, "color")
		val home = created(CONTEXTS, """{"name":"@home","color":"#abc"}""")
		val path = "$CONTEXTS/${home.id()}"
		patch(path, """{"color":null}""", null).isProblem(428)
		patch(path, """{"color":null}""", "\"4\"").isProblem(412)
		patch(path, """{"name":null}""", home.etag()).isProblem(400, "name")
		patch(path, """{"icon":"x"}""", home.etag()).isProblem(400, "icon")
		patch(path, """{"color":null}""", home.etag()).andExpect {
			status { isOk() }
			jsonPath("$.color") { doesNotExist() }
			jsonPath("$.name") { value("@home") }
		}
		mockMvc.get("$CONTEXTS/${UuidV7.generate()}").isProblem(404)
	}

	@Test
	fun `PUT contexts order needs every context exactly once`() {
		val a = created(CONTEXTS, """{"name":"@a"}""").id()
		val b = created(CONTEXTS, """{"name":"@b"}""").id()
		put("$CONTEXTS/order", listOf(b, a)).andExpect { status { isOk() } }
		list(CONTEXTS).map { it.id() } shouldContainExactly listOf(b, a)
		put("$CONTEXTS/order", listOf(b)).isProblem(400, "ids")
	}

	@Test
	fun `deleting a context removes it from its tasks, with a new version and a change event`(events: ApplicationEvents) {
		val home = created(CONTEXTS, """{"name":"@home"}""")
		val phone = created(CONTEXTS, """{"name":"@phone"}""")
		val task = created(TASKS, """{"title":"Call the plumber","contextIds":["${home.id()}","${phone.id()}"]}""")
		val other = created(TASKS, """{"title":"Buy milk","contextIds":["${phone.id()}"]}""")
		delete("$CONTEXTS/${home.id()}", home.etag()).andExpect { status { isNoContent() } }

		val changed = mockMvc.get("$TASKS/${task.id()}").andExpect { status { isOk() } }.json()
		changed.get("contextIds").values().map { it.asString() } shouldContainExactly listOf(phone.id())
		changed.get("version").asLong() shouldBe 1
		mockMvc.get("$TASKS/${other.id()}").andExpect { jsonPath("$.version") { value(0) } }
		list(CONTEXTS).map { it.id() } shouldContainExactly listOf(phone.id())
		events.changes("CONTEXT").last() shouldBe (ChangeOp.DELETED to home.id())
		events.changes("TASK").last() shouldBe (ChangeOp.UPDATED to task.id())
	}

	private companion object {
		const val AREAS = "/api/v1/areas"
		const val CONTEXTS = "/api/v1/contexts"
		const val PROJECTS = "/api/v1/projects"
		const val TASKS = "/api/v1/tasks"
	}
}
