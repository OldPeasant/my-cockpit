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

	@Test
	fun `deleting a context with replaceWith gives all its tasks the other context, also in the tickler and the Trash`(
		events: ApplicationEvents,
	) {
		val home = created(CONTEXTS, """{"name":"@home"}""")
		val phone = created(CONTEXTS, """{"name":"@phone"}""")
		val errands = created(CONTEXTS, """{"name":"@errands"}""")
		val plain = created(TASKS, """{"title":"Call the plumber","contextIds":["${home.id()}"]}""").id()
		val both = created(TASKS, """{"title":"Book a table","contextIds":["${home.id()}","${phone.id()}"]}""").id()
		val tickler = created(TASKS, """{"title":"Renew the passport","contextIds":["${home.id()}"]}""").id()
		val trashed = created(TASKS, """{"title":"Water the plants","contextIds":["${home.id()}"]}""").id()
		val other = created(TASKS, """{"title":"Buy milk","contextIds":["${errands.id()}"]}""").id()
		jdbc.update("update task set tickler_date = date '2099-01-01' where id = ?::uuid", tickler)
		delete("$TASKS/$trashed", "*").andExpect { status { isNoContent() } }

		delete("$CONTEXTS/${home.id()}?replaceWith=${phone.id()}", home.etag()).andExpect { status { isNoContent() } }

		fun contextsOf(task: String) =
			jdbc.queryForList("select context_id::text from task_context where task_id = ?::uuid", String::class.java, task)
		listOf(plain, both, tickler, trashed).forEach { contextsOf(it) shouldContainExactly listOf(phone.id()) }
		contextsOf(other) shouldContainExactly listOf(errands.id())
		list(CONTEXTS).map { it.id() } shouldContainExactly listOf(phone.id(), errands.id())
		events.changes("CONTEXT").last() shouldBe (ChangeOp.DELETED to home.id())
		events
			.changes("TASK")
			.filter { it.first == ChangeOp.UPDATED }
			.map { it.second }
			.toSet() shouldBe setOf(plain, both, tickler)
	}

	@Test
	fun `deleting a context with an unknown or its own replaceWith is a 400 and changes nothing`() {
		val home = created(CONTEXTS, """{"name":"@home"}""")
		val task = created(TASKS, """{"title":"Call the plumber","contextIds":["${home.id()}"]}""")
		delete("$CONTEXTS/${home.id()}?replaceWith=${UuidV7.generate()}", home.etag()).isProblem(400, "replaceWith")
		delete("$CONTEXTS/${home.id()}?replaceWith=${home.id()}", home.etag()).isProblem(400, "replaceWith")
		delete("$CONTEXTS/${home.id()}?replaceWith=not-a-uuid", home.etag()).andExpect { status { isBadRequest() } }
		list(CONTEXTS).map { it.id() } shouldContainExactly listOf(home.id())
		mockMvc.get("$TASKS/${task.id()}").andExpect {
			jsonPath("$.version") { value(0) }
			jsonPath("$.contextIds[0]") { value(home.id()) }
		}
	}

	@Test
	fun `deleting an area with moveTo moves all its projects, also those in the Trash`(events: ApplicationEvents) {
		val home = created(AREAS, """{"title":"Home"}""")
		val family = created(AREAS, """{"title":"Family"}""")
		val project = created(PROJECTS, """{"title":"Renovate kitchen","areaId":"${home.id()}"}""").id()
		val trashed = created(PROJECTS, """{"title":"Paint the fence","areaId":"${home.id()}"}""").id()
		delete("$PROJECTS/$trashed", "*").andExpect { status { isNoContent() } }

		delete("$AREAS/${home.id()}?moveTo=${family.id()}", home.etag()).andExpect { status { isNoContent() } }

		listOf(project, trashed).forEach {
			jdbc.queryForObject("select area_id::text from project where id = ?::uuid", String::class.java, it) shouldBe family.id()
		}
		mockMvc.get("$PROJECTS/$project").andExpect { jsonPath("$.version") { value(1) } }
		list(AREAS).map { it.id() } shouldContainExactly listOf(family.id())
		events.changes("AREA").last() shouldBe (ChangeOp.DELETED to home.id())
		events.changes("PROJECT").last() shouldBe (ChangeOp.UPDATED to project)
	}

	@Test
	fun `deleting an area with an unknown or its own moveTo is a 400 and changes nothing`() {
		val home = created(AREAS, """{"title":"Home"}""")
		val project = created(PROJECTS, """{"title":"Renovate kitchen","areaId":"${home.id()}"}""")
		delete("$AREAS/${home.id()}?moveTo=${UuidV7.generate()}", home.etag()).isProblem(400, "moveTo")
		delete("$AREAS/${home.id()}?moveTo=${home.id()}", home.etag()).isProblem(400, "moveTo")
		list(AREAS).map { it.id() } shouldContainExactly listOf(home.id())
		mockMvc.get("$PROJECTS/${project.id()}").andExpect {
			jsonPath("$.version") { value(0) }
			jsonPath("$.areaId") { value(home.id()) }
		}
	}

	private fun versionOf(
		table: String,
		id: String,
	): Long = jdbc.queryForObject("select version from $table where id = ?::uuid", Long::class.java, id)!!

	private fun contextsOf(task: String): List<String?> =
		jdbc.queryForList("select context_id::text from task_context where task_id = ?::uuid", String::class.java, task)

	private fun areaOf(project: String): String? =
		jdbc.queryForObject("select area_id::text from project where id = ?::uuid", String::class.java, project)

	@Test
	fun `deleting a context with replaceWith also reaches done tasks and versions trashed tasks without announcing them`(
		events: ApplicationEvents,
	) {
		val home = created(CONTEXTS, """{"name":"@home"}""")
		val phone = created(CONTEXTS, """{"name":"@phone"}""")
		val done = created(TASKS, """{"title":"Fix the doorbell","contextIds":["${home.id()}"]}""").id()
		val trashed = created(TASKS, """{"title":"Sort the photos","contextIds":["${home.id()}"]}""").id()
		post("$TASKS/$done/complete", "").andExpect { status { isOk() } }
		delete("$TASKS/$trashed", "*").andExpect { status { isNoContent() } }
		val doneVersion = versionOf("task", done)
		val trashedVersion = versionOf("task", trashed)

		delete("$CONTEXTS/${home.id()}?replaceWith=${phone.id()}", home.etag()).andExpect { status { isNoContent() } }

		contextsOf(done) shouldContainExactly listOf(phone.id())
		contextsOf(trashed) shouldContainExactly listOf(phone.id())
		versionOf("task", done) shouldBe doneVersion + 1
		versionOf("task", trashed) shouldBe trashedVersion + 1
		mockMvc.get("$TASKS/$done").andExpect {
			status { isOk() }
			jsonPath("$.completedAt") { exists() }
			jsonPath("$.contextIds[0]") { value(phone.id()) }
		}
		events
			.changes("TASK")
			.filter { it.first == ChangeOp.UPDATED && it.second == trashed } shouldBe emptyList()
		events.changes("TASK").last() shouldBe (ChangeOp.UPDATED to done)
	}

	@Test
	fun `deleting a context with replaceWith checks If-Match before the target and reassigns nothing on 428 or 412`(
		events: ApplicationEvents,
	) {
		val home = created(CONTEXTS, """{"name":"@home"}""")
		val phone = created(CONTEXTS, """{"name":"@phone"}""")
		val task = created(TASKS, """{"title":"Call the plumber","contextIds":["${home.id()}"]}""").id()
		val path = "$CONTEXTS/${home.id()}"

		delete("$path?replaceWith=${phone.id()}", null).isProblem(428)
		delete("$path?replaceWith=${phone.id()}", "\"7\"").isProblem(412)
		// The version is checked first: a stale ETag wins over an invalid target.
		delete("$path?replaceWith=${UuidV7.generate()}", "\"7\"").isProblem(412)
		// An unknown context is a 404 even with a valid target.
		delete("$CONTEXTS/${UuidV7.generate()}?replaceWith=${phone.id()}", "*").isProblem(404)

		contextsOf(task) shouldContainExactly listOf(home.id())
		versionOf("task", task) shouldBe 0
		list(CONTEXTS).map { it.id() } shouldContainExactly listOf(home.id(), phone.id())
		events.changes("TASK").filter { it.first != ChangeOp.CREATED } shouldBe emptyList()
		events.changes("CONTEXT").filter { it.first != ChangeOp.CREATED } shouldBe emptyList()

		// "*" matches any version and the target is then validated.
		delete("$path?replaceWith=${home.id()}", "*").isProblem(400, "replaceWith")
		delete("$path?replaceWith=${phone.id()}", "*").andExpect { status { isNoContent() } }
		contextsOf(task) shouldContainExactly listOf(phone.id())
	}

	@Test
	fun `a 400 for the replacement context announces nothing`(events: ApplicationEvents) {
		val home = created(CONTEXTS, """{"name":"@home"}""")
		created(TASKS, """{"title":"Call the plumber","contextIds":["${home.id()}"]}""")
		delete("$CONTEXTS/${home.id()}?replaceWith=${UuidV7.generate()}", home.etag()).isProblem(400, "replaceWith")
		events.changes("TASK").filter { it.first != ChangeOp.CREATED } shouldBe emptyList()
		events.changes("CONTEXT").filter { it.first != ChangeOp.CREATED } shouldBe emptyList()
	}

	@Test
	fun `deleting a context without tasks with replaceWith leaves the replacement as it was`(events: ApplicationEvents) {
		val home = created(CONTEXTS, """{"name":"@home"}""")
		val phone = created(CONTEXTS, """{"name":"@phone"}""")
		delete("$CONTEXTS/${home.id()}?replaceWith=${phone.id()}", home.etag()).andExpect { status { isNoContent() } }
		mockMvc.get("$CONTEXTS/${phone.id()}").andExpect {
			status { isOk() }
			header { string(HttpHeaders.ETAG, phone.etag()) }
		}
		events.changes("TASK") shouldBe emptyList()
	}

	@Test
	fun `deleting an area with moveTo also moves completed projects and versions trashed ones without announcing them`(
		events: ApplicationEvents,
	) {
		val home = created(AREAS, """{"title":"Home"}""")
		val family = created(AREAS, """{"title":"Family"}""")
		val done = created(PROJECTS, """{"title":"Build a shed","areaId":"${home.id()}"}""")
		val trashed = created(PROJECTS, """{"title":"Paint the fence","areaId":"${home.id()}"}""").id()
		val untouched = created(PROJECTS, """{"title":"Plan the holidays","areaId":"${family.id()}"}""").id()
		patch("$PROJECTS/${done.id()}", """{"status":"COMPLETED"}""", done.etag()).andExpect { status { isOk() } }
		delete("$PROJECTS/$trashed", "*").andExpect { status { isNoContent() } }
		val doneVersion = versionOf("project", done.id())
		val trashedVersion = versionOf("project", trashed)
		val untouchedVersion = versionOf("project", untouched)

		delete("$AREAS/${home.id()}?moveTo=${family.id()}", home.etag()).andExpect { status { isNoContent() } }

		areaOf(done.id()) shouldBe family.id()
		areaOf(trashed) shouldBe family.id()
		areaOf(untouched) shouldBe family.id()
		versionOf("project", done.id()) shouldBe doneVersion + 1
		versionOf("project", trashed) shouldBe trashedVersion + 1
		versionOf("project", untouched) shouldBe untouchedVersion
		mockMvc.get("$PROJECTS/${done.id()}").andExpect {
			jsonPath("$.status") { value("COMPLETED") }
			jsonPath("$.areaId") { value(family.id()) }
		}
		val updated = events.changes("PROJECT").filter { it.first == ChangeOp.UPDATED }.map { it.second }
		updated.contains(trashed) shouldBe false
		updated.last() shouldBe done.id()
	}

	@Test
	fun `deleting an area with moveTo checks If-Match before the target and moves nothing on 428 or 412`(
		events: ApplicationEvents,
	) {
		val home = created(AREAS, """{"title":"Home"}""")
		val family = created(AREAS, """{"title":"Family"}""")
		val project = created(PROJECTS, """{"title":"Renovate kitchen","areaId":"${home.id()}"}""").id()
		val path = "$AREAS/${home.id()}"

		delete("$path?moveTo=${family.id()}", null).isProblem(428)
		delete("$path?moveTo=${family.id()}", "\"7\"").isProblem(412)
		delete("$path?moveTo=${UuidV7.generate()}", "\"7\"").isProblem(412)
		delete("$path?moveTo=not-a-uuid", home.etag()).andExpect { status { isBadRequest() } }
		delete("$AREAS/${UuidV7.generate()}?moveTo=${family.id()}", "*").isProblem(404)

		areaOf(project) shouldBe home.id()
		versionOf("project", project) shouldBe 0
		list(AREAS).map { it.id() } shouldContainExactly listOf(home.id(), family.id())
		events.changes("PROJECT").filter { it.first != ChangeOp.CREATED } shouldBe emptyList()
		events.changes("AREA").filter { it.first != ChangeOp.CREATED } shouldBe emptyList()
	}

	private companion object {
		const val AREAS = "/api/v1/areas"
		const val CONTEXTS = "/api/v1/contexts"
		const val PROJECTS = "/api/v1/projects"
		const val TASKS = "/api/v1/tasks"
	}
}
