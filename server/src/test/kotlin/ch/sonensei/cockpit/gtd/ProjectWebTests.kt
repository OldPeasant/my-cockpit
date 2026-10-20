package ch.sonensei.cockpit.gtd

import ch.sonensei.cockpit.core.auth.WithCockpitUser
import ch.sonensei.cockpit.core.events.domain.ChangeEvent
import ch.sonensei.cockpit.core.events.domain.ChangeOp
import ch.sonensei.cockpit.core.persistence.MutableClock
import ch.sonensei.cockpit.core.persistence.MutableClockTestConfiguration
import ch.sonensei.cockpit.core.persistence.UuidV7
import ch.sonensei.cockpit.gtd.domain.Area
import ch.sonensei.cockpit.gtd.infra.AreaRepository
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
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
import org.springframework.test.web.servlet.put
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

@SpringBootTest
@AutoConfigureMockMvc
@Import(MutableClockTestConfiguration::class)
@RecordApplicationEvents
@WithCockpitUser
class ProjectWebTests(
	@Autowired private val mockMvc: MockMvc,
	@Autowired private val jsonMapper: JsonMapper,
	@Autowired private val areas: AreaRepository,
	@Autowired private val clock: MutableClock,
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
		clock.set(MutableClockTestConfiguration.START) // 2026-03-01 in Europe/Zurich
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

	private fun ResultActionsDsl.json(): JsonNode = andReturn().response.contentAsString.let(jsonMapper::readTree)

	private fun newProject(body: String = """{"title":"Renovate kitchen"}"""): JsonNode =
		post(PROJECTS, body).andExpect { status { isCreated() } }.json()

	private fun newTask(
		projectId: String?,
		title: String = "Call the tiler",
	): String {
		val project = projectId?.let { ""","projectId":"$it"""" } ?: ""
		return post(TASKS, """{"title":"$title","list":"NEXT"$project}""").andExpect { status { isCreated() } }.json().id()
	}

	private fun JsonNode.id(): String = get("id").asString()

	private fun JsonNode.etag(): String = "\"${get("version").asLong()}\""

	private fun fetch(id: String): JsonNode = mockMvc.get("$PROJECTS/$id").andExpect { status { isOk() } }.json()

	private fun fetchTask(id: String): JsonNode = mockMvc.get("$TASKS/$id").andExpect { status { isOk() } }.json()

	private fun patch(
		id: String,
		body: String,
		ifMatch: String?,
	) = mockMvc.patch("$PROJECTS/$id") {
		with(csrf())
		contentType = MediaType.APPLICATION_JSON
		content = body
		ifMatch?.let { header(HttpHeaders.IF_MATCH, it) }
	}

	private fun nextStep(
		id: String,
		taskId: String?,
		ifMatch: String? = null,
	) = mockMvc.post("$PROJECTS/$id/next-step") {
		with(csrf())
		contentType = MediaType.APPLICATION_JSON
		content = """{"taskId":${taskId?.let { "\"$it\"" } ?: "null"}}"""
		ifMatch?.let { header(HttpHeaders.IF_MATCH, it) }
	}

	private fun taskAction(
		id: String,
		name: String,
	) = mockMvc.post("$TASKS/$id/$name") { with(csrf()) }

	private fun order(
		id: String,
		vararg taskIds: String,
	) = mockMvc.put("$PROJECTS/$id/order") {
		with(csrf())
		contentType = MediaType.APPLICATION_JSON
		content = """{"taskIds":[${taskIds.joinToString(",") { "\"$it\"" }}]}"""
	}

	private fun stalled(id: String): Boolean = fetch(id).get("stalled").asBoolean()

	private fun listIds(vararg params: Pair<String, String>): List<String> =
		mockMvc
			.get(PROJECTS) { params.forEach { (name, value) -> param(name, value) } }
			.andExpect { status { isOk() } }
			.json()
			.get("items")
			.values()
			.map { it.id() }

	private fun ResultActionsDsl.isProblem(
		status: Int,
		vararg fields: String,
	) = andExpect {
		status { isEqualTo(status) }
		content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
		fields.forEach { field -> jsonPath("$.errors[?(@.field == '$field')]") { isNotEmpty() } }
	}

	private fun searchTitle(id: String): String? =
		jdbc
			.query("select title from search_document where item_type = 'PROJECT' and item_id = ?::uuid", { rs, _ -> rs.getString(1) }, id)
			.singleOrNull()

	private fun ApplicationEvents.changes(type: String = "PROJECT"): List<Pair<ChangeOp, String>> =
		stream(ChangeEvent::class.java).toList().filter { it.type == type }.map { it.op to it.id.toString() }

	// --- CRUD

	@Test
	fun `POST creates an active, stalled project with ETag and Location, indexes it and announces it`(events: ApplicationEvents) {
		val area = areas.save(Area("Home"))
		val result =
			post(
				PROJECTS,
				"""{"title":"Renovate kitchen","descriptionMd":"New tiles","areaId":"${area.id}","dueDate":"2026-06-30","reviewDate":"2026-03-08"}""",
			).andExpect {
				status { isCreated() }
				header { string(HttpHeaders.ETAG, "\"0\"") }
				jsonPath("$.status") { value("ACTIVE") }
				jsonPath("$.stalled") { value(true) }
				jsonPath("$.areaId") { value(area.id.toString()) }
				jsonPath("$.dueDate") { value("2026-06-30") }
				jsonPath("$.reviewDate") { value("2026-03-08") }
				jsonPath("$.nextTaskId") { doesNotExist() }
				jsonPath("$.completedAt") { doesNotExist() }
			}.andReturn()
		val id = jsonMapper.readTree(result.response.contentAsString).id()
		result.response.getHeader(HttpHeaders.LOCATION) shouldBe "http://localhost$PROJECTS/$id"
		fetch(id).get("descriptionMd").asString() shouldBe "New tiles"
		searchTitle(id) shouldBe "Renovate kitchen"
		events.changes() shouldContainExactly listOf(ChangeOp.CREATED to id)
	}

	@Test
	fun `POST rejects a missing or blank title and an unknown area`() {
		post(PROJECTS, """{}""").isProblem(400, "title")
		post(PROJECTS, """{"title":"  "}""").isProblem(400, "title")
		post(PROJECTS, """{"title":"${"x".repeat(501)}"}""").isProblem(400, "title")
		post(PROJECTS, """{"title":"a","areaId":"${UuidV7.generate()}"}""").isProblem(400, "areaId")
		listIds() shouldBe emptyList()
	}

	@Test
	fun `GET of an unknown project is 404`() {
		mockMvc.get("$PROJECTS/${UuidV7.generate()}").isProblem(404)
	}

	@Test
	fun `PATCH changes and clears fields, needs If-Match and re-indexes`(events: ApplicationEvents) {
		val project = newProject("""{"title":"Renovate kitchen","dueDate":"2026-06-30"}""")
		val id = project.id()
		patch(id, """{"title":"Renovate bathroom"}""", null).isProblem(428)
		patch(id, """{"title":"Renovate bathroom"}""", "\"7\"").isProblem(412)
		patch(id, """{"title":"Renovate bathroom","dueDate":null,"reviewDate":"2026-03-15"}""", project.etag()).andExpect {
			status { isOk() }
			header { string(HttpHeaders.ETAG, "\"1\"") }
			jsonPath("$.title") { value("Renovate bathroom") }
			jsonPath("$.dueDate") { doesNotExist() }
			jsonPath("$.reviewDate") { value("2026-03-15") }
		}
		searchTitle(id) shouldBe "Renovate bathroom"
		events.changes() shouldContainExactly listOf(ChangeOp.CREATED to id, ChangeOp.UPDATED to id)
	}

	@Test
	fun `PATCH rejects unknown fields, nulls for title and status, and bad values`() {
		val project = newProject()
		val id = project.id()
		patch(id, """{"nextTaskId":"${UuidV7.generate()}"}""", project.etag()).isProblem(400, "nextTaskId")
		patch(id, """{"title":null,"status":null}""", project.etag()).isProblem(400, "title", "status")
		patch(id, """{"status":"FINISHED"}""", project.etag()).isProblem(400, "status")
		patch(id, """{"areaId":"${UuidV7.generate()}"}""", project.etag()).isProblem(400, "areaId")
		fetch(id).get("version").asLong() shouldBe 0
	}

	@Test
	fun `status COMPLETED records the completion time, another status clears it`() {
		val project = newProject()
		val id = project.id()
		clock.set(MutableClockTestConfiguration.START.plusSeconds(60))
		val done =
			patch(id, """{"status":"COMPLETED"}""", project.etag())
				.andExpect {
					status { isOk() }
					jsonPath("$.completedAt") { value("2026-03-01T08:01:00Z") }
					jsonPath("$.stalled") { value(false) }
				}.json()
		clock.set(MutableClockTestConfiguration.START.plusSeconds(120))
		// Changing other fields of a completed project keeps the first completion time.
		val renamed =
			patch(id, """{"status":"COMPLETED","title":"Kitchen done"}""", done.etag())
				.andExpect { jsonPath("$.completedAt") { value("2026-03-01T08:01:00Z") } }
				.json()
		patch(id, """{"status":"ACTIVE"}""", renamed.etag()).andExpect {
			status { isOk() }
			jsonPath("$.completedAt") { doesNotExist() }
		}
	}

	@Test
	fun `DELETE moves the project to the Trash, out of search and lists, and restore brings it back`(events: ApplicationEvents) {
		val project = newProject()
		val id = project.id()
		val task = newTask(id)
		mockMvc.delete("$PROJECTS/$id") { with(csrf()) }.isProblem(428)
		mockMvc
			.delete("$PROJECTS/$id") {
				with(csrf())
				header(HttpHeaders.IF_MATCH, project.etag())
			}.andExpect { status { isNoContent() } }
		mockMvc.get("$PROJECTS/$id").isProblem(404)
		listIds() shouldBe emptyList()
		searchTitle(id) shouldBe null
		events.changes() shouldContainExactly listOf(ChangeOp.CREATED to id, ChangeOp.DELETED to id)
		// Its tasks keep their project.
		fetchTask(task).get("projectId").asString() shouldBe id

		mockMvc.get("/api/v1/trash").andExpect {
			status { isOk() }
			jsonPath("$.items[?(@.type == 'PROJECT' && @.id == '$id')]") { isNotEmpty() }
		}
		mockMvc.post("/api/v1/trash/PROJECT/$id/restore") { with(csrf()) }.andExpect { status { is2xxSuccessful() } }
		fetch(id).get("title").asString() shouldBe "Renovate kitchen"
		searchTitle(id) shouldBe "Renovate kitchen"
	}

	@Test
	fun `purging a project from the Trash keeps its tasks without a project`() {
		val project = newProject()
		val id = project.id()
		val task = newTask(id)
		nextStep(id, task).andExpect { status { isOk() } }
		mockMvc
			.delete("$PROJECTS/$id") {
				with(csrf())
				header(HttpHeaders.IF_MATCH, fetch(id).etag())
			}.andExpect { status { isNoContent() } }
		mockMvc.delete("/api/v1/trash/PROJECT/$id") { with(csrf()) }.andExpect { status { is2xxSuccessful() } }
		jdbc.queryForObject("select count(*) from project", Int::class.java) shouldBe 0
		mockMvc.get("$TASKS/$task").andExpect {
			status { isOk() }
			jsonPath("$.projectId") { doesNotExist() }
		}
	}

	// --- list

	@Test
	fun `list filters by status, area and stalled, ordered by title`() {
		val home = areas.save(Area("Home"))
		val kitchen = newProject("""{"title":"Renovate kitchen","areaId":"${home.id}"}""").id()
		val garden = newProject("""{"title":"Garden shed","areaId":"${home.id}"}""").id()
		val book = newProject("""{"title":"Write a book","status":"SOMEDAY"}""").id()
		nextStep(kitchen, newTask(kitchen)).andExpect { status { isOk() } }

		listIds() shouldContainExactly listOf(garden, kitchen, book)
		listIds("status" to "SOMEDAY") shouldContainExactly listOf(book)
		listIds("areaId" to home.id.toString()) shouldContainExactly listOf(garden, kitchen)
		listIds("stalled" to "true") shouldContainExactly listOf(garden)
		listIds("stalled" to "false") shouldContainExactly listOf(kitchen, book)
		mockMvc.get(PROJECTS) { param("status", "DONE") }.isProblem(400)
	}

	@Test
	fun `list pages by title without gaps or repeats, also for equal titles and titles with a bar`() {
		val ids = listOf("B|x", "A", "B", "B", "A|b|c").map { newProject("""{"title":"$it"}""").id() }
		val seen = mutableListOf<String>()
		var cursor: String? = null
		do {
			val page =
				mockMvc
					.get(PROJECTS) {
						param("limit", "2")
						cursor?.let { param("cursor", it) }
					}.andExpect { status { isOk() } }
					.json()
			seen += page.get("items").values().map { it.id() }
			cursor = page.get("nextCursor").takeUnless { it == null || it.isNull }?.asString()
		} while (cursor != null)
		seen shouldContainExactly listIds("limit" to "200")
		seen.toSet() shouldBe ids.toSet()
		seen.size shouldBe ids.size
		mockMvc.get(PROJECTS) { param("cursor", "bm9wZQ") }.isProblem(400, "cursor")
	}

	// --- next step and stalled

	@Test
	fun `next-step designates an open task of the project, which is then not stalled`() {
		val project = newProject()
		val id = project.id()
		val task = newTask(id)
		nextStep(id, task, "\"9\"").isProblem(412)
		nextStep(id, task, project.etag()).andExpect {
			status { isOk() }
			header { string(HttpHeaders.ETAG, "\"1\"") }
			jsonPath("$.nextTaskId") { value(task) }
			jsonPath("$.stalled") { value(false) }
		}
		nextStep(id, null).andExpect {
			status { isOk() }
			jsonPath("$.nextTaskId") { doesNotExist() }
			jsonPath("$.stalled") { value(true) }
		}
	}

	@Test
	fun `next-step rejects tasks of other projects, without a project, done, deleted or unknown`() {
		val id = newProject().id()
		val other = newProject("""{"title":"Garden shed"}""").id()
		val foreign = newTask(other)
		val loose = newTask(null)
		val done = newTask(id).also { taskAction(it, "complete").andExpect { status { isOk() } } }
		val deleted = newTask(id)
		mockMvc
			.delete("$TASKS/$deleted") {
				with(csrf())
				header(HttpHeaders.IF_MATCH, "*")
			}.andExpect { status { isNoContent() } }

		listOf(foreign, loose, done, deleted, UuidV7.generate().toString()).forEach { nextStep(id, it).isProblem(400, "taskId") }
		fetch(id).get("version").asLong() shouldBe 0
		nextStep(UuidV7.generate().toString(), loose).isProblem(404)
	}

	@Test
	fun `a project is stalled when its next step is done, deleted or moved away, and only while ACTIVE`() {
		val project = newProject()
		val id = project.id()
		val task = newTask(id)
		nextStep(id, task).andExpect { status { isOk() } }
		stalled(id) shouldBe false

		taskAction(task, "complete").andExpect { status { isOk() } }
		stalled(id) shouldBe true
		taskAction(task, "uncomplete").andExpect { status { isOk() } }
		stalled(id) shouldBe false

		val other = newProject("""{"title":"Garden shed"}""").id()
		mockMvc
			.post("$TASKS/$task/move") {
				with(csrf())
				contentType = MediaType.APPLICATION_JSON
				content = """{"list":"NEXT","projectId":"$other"}"""
			}.andExpect { status { isOk() } }
		stalled(id) shouldBe true
		listIds("stalled" to "true") shouldContainExactly listOf(other, id)

		val second = newTask(id, "Buy tiles")
		nextStep(id, second).andExpect { status { isOk() } }
		stalled(id) shouldBe false
		mockMvc
			.delete("$TASKS/$second") {
				with(csrf())
				header(HttpHeaders.IF_MATCH, "*")
			}.andExpect { status { isNoContent() } }
		stalled(id) shouldBe true
		listIds("stalled" to "true") shouldContainExactly listOf(other, id)

		patch(id, """{"status":"ON_HOLD"}""", fetch(id).etag()).andExpect { jsonPath("$.stalled") { value(false) } }
		listIds("stalled" to "true") shouldContainExactly listOf(other)
	}

	@Test
	fun `completing the next step of an active project returns needsNextStep`() {
		val id = newProject().id()
		val next = newTask(id)
		val other = newTask(id, "Buy tiles")
		val loose = newTask(null, "Water the plants")
		nextStep(id, next).andExpect { status { isOk() } }

		taskAction(other, "complete").andExpect {
			status { isOk() }
			jsonPath("$.needsNextStep") { value(false) }
			jsonPath("$.id") { value(other) }
		}
		taskAction(loose, "complete").andExpect { jsonPath("$.needsNextStep") { value(false) } }
		taskAction(next, "complete").andExpect {
			status { isOk() }
			header { string(HttpHeaders.ETAG, "\"1\"") }
			jsonPath("$.needsNextStep") { value(true) }
			jsonPath("$.id") { value(next) }
			jsonPath("$.completedAt") { value("2026-03-01T08:00:00Z") }
		}
		stalled(id) shouldBe true
	}

	@Test
	fun `completing the next step of a project that is not active does not ask for a new one`() {
		val project = newProject()
		val id = project.id()
		val next = newTask(id)
		val withNext = nextStep(id, next).andExpect { status { isOk() } }.json()
		patch(id, """{"status":"ON_HOLD"}""", withNext.etag()).andExpect { status { isOk() } }
		taskAction(next, "complete").andExpect { jsonPath("$.needsNextStep") { value(false) } }
	}

	// --- task order

	@Test
	fun `PUT order puts the listed tasks first, then the others in their order, and announces changed tasks`(events: ApplicationEvents) {
		val id = newProject().id()
		val a = newTask(id, "a")
		val b = newTask(id, "b")
		val c = newTask(id, "c")
		val d = newTask(id, "d").also { taskAction(it, "complete").andExpect { status { isOk() } } }
		val before = events.changes("TASK").size

		val ordered =
			order(id, c, a)
				.andExpect { status { isOk() } }
				.json()
				.get("items")
				.values()
		ordered.map { it.id() } shouldContainExactly listOf(c, a, b, d)
		ordered.map { it.get("position").asInt() } shouldContainExactly listOf(0, 1, 2, 3)
		mockMvc
			.get(TASKS) { param("projectId", id) }
			.json()
			.get("items")
			.values()
			.map { it.id() } shouldContainExactly listOf(c, a, b)
		// a was at 0, b at 1, c at 2: all three moved; d stayed at 3.
		events
			.changes("TASK")
			.drop(before)
			.map { it.second }
			.toSet() shouldBe setOf(a, b, c)
		fetchTask(c).get("version").asLong() shouldBe 1
		fetchTask(d).get("version").asLong() shouldBe 1
	}

	@Test
	fun `PUT order rejects duplicates and tasks of other projects`() {
		val id = newProject().id()
		val a = newTask(id, "a")
		val foreign = newTask(null, "x")
		order(id, a, a).isProblem(400, "taskIds")
		order(id, foreign).isProblem(400, "taskIds")
		mockMvc
			.put("$PROJECTS/$id/order") {
				with(csrf())
				contentType = MediaType.APPLICATION_JSON
				content = "{}"
			}.isProblem(400)
		order(UuidV7.generate().toString(), a).isProblem(404)
	}

	private companion object {
		const val PROJECTS = "/api/v1/projects"
		const val TASKS = "/api/v1/tasks"
	}
}
