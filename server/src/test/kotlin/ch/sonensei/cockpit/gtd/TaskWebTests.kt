package ch.sonensei.cockpit.gtd

import ch.sonensei.cockpit.core.auth.WithCockpitUser
import ch.sonensei.cockpit.core.events.domain.ChangeEvent
import ch.sonensei.cockpit.core.events.domain.ChangeOp
import ch.sonensei.cockpit.core.persistence.MutableClock
import ch.sonensei.cockpit.core.persistence.MutableClockTestConfiguration
import ch.sonensei.cockpit.core.persistence.UuidV7
import ch.sonensei.cockpit.gtd.domain.Context
import ch.sonensei.cockpit.gtd.domain.Project
import ch.sonensei.cockpit.gtd.infra.ContextRepository
import ch.sonensei.cockpit.gtd.infra.ProjectRepository
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
import org.springframework.security.test.context.support.WithAnonymousUser
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
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
@Import(MutableClockTestConfiguration::class)
@RecordApplicationEvents
@WithCockpitUser
class TaskWebTests(
	@Autowired private val mockMvc: MockMvc,
	@Autowired private val jsonMapper: JsonMapper,
	@Autowired private val projects: ProjectRepository,
	@Autowired private val contexts: ContextRepository,
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
		jdbc.execute("delete from context")
		jdbc.execute("delete from search_document")
		clock.set(MutableClockTestConfiguration.START) // 2026-03-01 in Europe/Zurich
	}

	// --- helpers

	private fun create(body: String): JsonNode =
		mockMvc
			.post(BASE) {
				with(csrf())
				contentType = MediaType.APPLICATION_JSON
				content = body
			}.andExpect { status { isCreated() } }
			.andReturn()
			.response
			.contentAsString
			.let(jsonMapper::readTree)

	private fun newTask(title: String = "Call the plumber"): JsonNode = create("""{"title":"$title"}""")

	private fun JsonNode.id(): String = get("id").asString()

	private fun JsonNode.etag(): String = "\"${get("version").asLong()}\""

	private fun fetch(id: String): JsonNode =
		mockMvc
			.get("$BASE/$id")
			.andExpect { status { isOk() } }
			.andReturn()
			.response
			.contentAsString
			.let(jsonMapper::readTree)

	private fun patch(
		id: String,
		body: String,
		ifMatch: String?,
	) = mockMvc.patch("$BASE/$id") {
		with(csrf())
		contentType = MediaType.APPLICATION_JSON
		content = body
		ifMatch?.let { header(HttpHeaders.IF_MATCH, it) }
	}

	private fun move(
		id: String,
		body: String,
		ifMatch: String? = null,
	) = mockMvc.post("$BASE/$id/move") {
		with(csrf())
		contentType = MediaType.APPLICATION_JSON
		content = body
		ifMatch?.let { header(HttpHeaders.IF_MATCH, it) }
	}

	private fun action(
		id: String,
		name: String,
		ifMatch: String? = null,
	) = mockMvc.post("$BASE/$id/$name") {
		with(csrf())
		ifMatch?.let { header(HttpHeaders.IF_MATCH, it) }
	}

	private fun listIds(vararg params: Pair<String, String>): List<String> =
		mockMvc
			.get(BASE) { params.forEach { (name, value) -> param(name, value) } }
			.andExpect { status { isOk() } }
			.andReturn()
			.response
			.contentAsString
			.let(jsonMapper::readTree)
			.get("items")
			.ids()

	private fun JsonNode.ids(): List<String> = values().map { it.id() }

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
			.query("select title from search_document where item_type = 'TASK' and item_id = ?::uuid", { rs, _ -> rs.getString(1) }, id)
			.singleOrNull()

	private fun ApplicationEvents.changes(): List<Pair<ChangeOp, String>> =
		stream(ChangeEvent::class.java).toList().filter { it.type == "TASK" }.map { it.op to it.id.toString() }

	// --- create and read

	@Test
	fun `POST creates an inbox task with ETag and Location, indexes it and announces it`(events: ApplicationEvents) {
		val result =
			mockMvc
				.post(BASE) {
					with(csrf())
					contentType = MediaType.APPLICATION_JSON
					content = """{"title":"Call the plumber","notesMd":"About the leak"}"""
				}.andExpect {
					status { isCreated() }
					header { string(HttpHeaders.ETAG, "\"0\"") }
					jsonPath("$.list") { value("INBOX") }
					jsonPath("$.title") { value("Call the plumber") }
					jsonPath("$.contextIds.length()") { value(0) }
					jsonPath("$.completedAt") { doesNotExist() }
					jsonPath("$.createdAt") { value("2026-03-01T08:00:00Z") }
				}.andReturn()
		val id = jsonMapper.readTree(result.response.contentAsString).id()
		result.response.getHeader(HttpHeaders.LOCATION) shouldBe "http://localhost$BASE/$id"

		fetch(id).get("notesMd").asString() shouldBe "About the leak"
		searchTitle(id) shouldBe "Call the plumber"
		events.changes() shouldContainExactly listOf(ChangeOp.CREATED to id)
	}

	@Test
	fun `POST with every field and contexts`() {
		val project = projects.save(Project("Renovate kitchen"))
		val home = contexts.save(Context("@home"))
		val phone = contexts.save(Context("@phone"))
		val task =
			create(
				"""
				{"title":"Call the tiler","list":"NEXT","projectId":"${project.id}","energy":"LOW","creativity":"ROUTINE",
				 "timeEstimateMin":15,"contextIds":["${home.id}","${phone.id}"],"dueDate":"2026-03-05","dueTime":"14:30:00"}
				""".trimIndent(),
			)
		task.get("list").asString() shouldBe "NEXT"
		task.get("projectId").asString() shouldBe project.id.toString()
		task.get("energy").asString() shouldBe "LOW"
		task.get("timeEstimateMin").asInt() shouldBe 15
		task
			.get("contextIds")
			.values()
			.map { it.asString() }
			.toSet() shouldBe setOf(home.id.toString(), phone.id.toString())
		task.get("dueTime").asString() shouldBe "14:30:00"
		task.get("position").asInt() shouldBe 0
		create("""{"title":"Second","projectId":"${project.id}"}""").get("position").asInt() shouldBe 1
	}

	@Test
	fun `POST validates fields`() {
		listOf(
			"""{}""" to "title",
			"""{"title":"  "}""" to "title",
			"""{"title":"${"x".repeat(501)}"}""" to "title",
			"""{"title":"a","list":"SCHEDULED"}""" to "scheduledDate",
			"""{"title":"a","dueTime":"10:00:00"}""" to "dueTime",
			"""{"title":"a","timeEstimateMin":-5}""" to "timeEstimateMin",
			"""{"title":"a","projectId":"${UuidV7.generate()}"}""" to "projectId",
			"""{"title":"a","contextIds":["${UuidV7.generate()}"]}""" to "contextIds",
			"""{"title":"a","id":"${UUID.randomUUID()}"}""" to "id",
		).forEach { (body, field) ->
			mockMvc
				.post(BASE) {
					with(csrf())
					contentType = MediaType.APPLICATION_JSON
					content = body
				}.isProblem(400, field)
		}
	}

	@Test
	fun `POST into WAITING sets waitingSince to today, SCHEDULED with a date is accepted`() {
		create("""{"title":"Reply from Anna","list":"WAITING","waitingForText":"Anna"}""").get("waitingSince").asString() shouldBe "2026-03-01"
		create("""{"title":"Dentist","list":"SCHEDULED","scheduledDate":"2026-04-01"}""").get("scheduledDate").asString() shouldBe "2026-04-01"
	}

	@Test
	fun `POST with a client id is idempotent`() {
		val id = UuidV7.generate()
		val body = """{"id":"$id","title":"Offline capture"}"""
		create(body).id() shouldBe id.toString()
		mockMvc
			.post(BASE) {
				with(csrf())
				contentType = MediaType.APPLICATION_JSON
				content = """{"id":"$id","title":"Changed on retry"}"""
			}.andExpect {
				status { isOk() }
				jsonPath("$.title") { value("Offline capture") }
				header { string(HttpHeaders.ETAG, "\"0\"") }
			}
	}

	@Test
	fun `GET of an unknown task is 404`() {
		mockMvc.get("$BASE/${UuidV7.generate()}").isProblem(404)
	}

	// --- PATCH

	@Test
	fun `PATCH changes only the sent fields, null clears, and returns the new ETag`(events: ApplicationEvents) {
		val home = contexts.save(Context("@home"))
		val task = create("""{"title":"Call the plumber","notesMd":"Leak","energy":"HIGH","timeEstimateMin":30}""")
		val id = task.id()

		patch(id, """{"title":"Call the plumber today","notesMd":null,"contextIds":["${home.id}"]}""", task.etag()).andExpect {
			status { isOk() }
			header { string(HttpHeaders.ETAG, "\"1\"") }
			jsonPath("$.title") { value("Call the plumber today") }
			jsonPath("$.notesMd") { doesNotExist() }
			jsonPath("$.energy") { value("HIGH") }
			jsonPath("$.timeEstimateMin") { value(30) }
			jsonPath("$.contextIds[0]") { value(home.id.toString()) }
		}
		searchTitle(id) shouldBe "Call the plumber today"
		events.changes() shouldContainExactly listOf(ChangeOp.CREATED to id, ChangeOp.UPDATED to id)

		patch(id, """{"contextIds":[]}""", "\"1\"").andExpect { jsonPath("$.contextIds.length()") { value(0) } }
	}

	@Test
	fun `PATCH needs a current If-Match`() {
		val task = newTask()
		patch(task.id(), """{"title":"x"}""", null).isProblem(428)
		patch(task.id(), """{"title":"x"}""", "\"7\"").isProblem(412)
		patch(task.id(), """{"title":"x"}""", task.etag()).andExpect { status { isOk() } }
		// The ETag of the first version is stale now.
		patch(task.id(), """{"title":"y"}""", task.etag()).isProblem(412)
		fetch(task.id()).get("title").asString() shouldBe "x"
	}

	@Test
	fun `PATCH rejects unknown, uncleareable and invalid fields`() {
		val task = newTask()
		patch(task.id(), """{"list":"NEXT"}""", task.etag()).isProblem(400, "list")
		patch(task.id(), """{"title":null}""", task.etag()).isProblem(400, "title")
		patch(task.id(), """{"energy":"EXTREME"}""", task.etag()).isProblem(400, "energy")
		patch(task.id(), """{"dueTime":"10:00:00"}""", task.etag()).isProblem(400, "dueTime")
		patch(task.id(), """["title"]""", task.etag()).isProblem(400)
		fetch(task.id()).get("version").asLong() shouldBe 0
	}

	@Test
	fun `PATCH cannot remove the date of a SCHEDULED task`() {
		val task = create("""{"title":"Dentist","list":"SCHEDULED","scheduledDate":"2026-04-01"}""")
		patch(task.id(), """{"scheduledDate":null}""", task.etag()).isProblem(400, "scheduledDate")
	}

	@Test
	fun `create and PATCH enforce the list rules of move`() {
		listOf(
			"""{"title":"a","scheduledDate":"2026-04-01"}""" to "scheduledDate",
			"""{"title":"a","list":"NEXT","waitingForText":"Anna"}""" to "waitingForText",
			"""{"title":"a","list":"NEXT","waitingForContactId":"${UuidV7.generate()}"}""" to "waitingForContactId",
			"""{"title":"a","list":"SOMEDAY","waitingSince":"2026-02-01"}""" to "waitingSince",
		).forEach { (body, field) ->
			mockMvc
				.post(BASE) {
					with(csrf())
					contentType = MediaType.APPLICATION_JSON
					content = body
				}.isProblem(400, field)
		}

		val next = create("""{"title":"Call the plumber","list":"NEXT"}""")
		patch(next.id(), """{"scheduledDate":"2026-04-01"}""", next.etag()).isProblem(400, "scheduledDate")
		patch(next.id(), """{"waitingForText":"Anna"}""", next.etag()).isProblem(400, "waitingForText")
		patch(next.id(), """{"waitingForContactId":"${UuidV7.generate()}"}""", next.etag()).isProblem(400, "waitingForContactId")
		patch(next.id(), """{"waitingSince":"2026-02-01"}""", next.etag()).isProblem(400, "waitingSince")
		fetch(next.id()).get("version").asLong() shouldBe 0

		val waiting = create("""{"title":"Reply from Anna","list":"WAITING"}""")
		patch(waiting.id(), """{"waitingForText":"Anna","waitingSince":"2026-02-01"}""", waiting.etag()).andExpect {
			status { isOk() }
			jsonPath("$.waitingForText") { value("Anna") }
			jsonPath("$.waitingSince") { value("2026-02-01") }
		}
		val scheduled = create("""{"title":"Dentist","list":"SCHEDULED","scheduledDate":"2026-04-01"}""")
		patch(scheduled.id(), """{"scheduledDate":"2026-04-02"}""", scheduled.etag()).andExpect {
			status { isOk() }
			jsonPath("$.scheduledDate") { value("2026-04-02") }
		}
	}

	@Test
	fun `a task whose project is in the Trash can still be changed, completed and moved`() {
		val trashed = projects.save(Project("Old kitchen plan"))
		val active = projects.save(Project("Renovate kitchen"))
		val task = create("""{"title":"Call the tiler","list":"NEXT","projectId":"${trashed.id}"}""")
		val id = task.id()
		projects.save(projects.findById(trashed.id).orElseThrow().also { it.softDelete(clock) })

		patch(id, """{"title":"Call the tiler today"}""", task.etag()).andExpect {
			status { isOk() }
			jsonPath("$.projectId") { value(trashed.id.toString()) }
		}
		action(id, "complete").andExpect { status { isOk() } }
		action(id, "uncomplete").andExpect { status { isOk() } }
		move(id, """{"list":"SOMEDAY"}""").andExpect {
			status { isOk() }
			jsonPath("$.list") { value("SOMEDAY") }
			jsonPath("$.projectId") { value(trashed.id.toString()) }
		}

		// Putting a task into a project in the Trash is still rejected; moving it out of one works.
		val current = fetch(id)
		patch(id, """{"projectId":"${UuidV7.generate()}"}""", current.etag()).isProblem(400, "projectId")
		move(id, """{"list":"NEXT","projectId":"${active.id}"}""").andExpect {
			status { isOk() }
			jsonPath("$.projectId") { value(active.id.toString()) }
		}
		patch(id, """{"projectId":"${trashed.id}"}""", fetch(id).etag()).isProblem(400, "projectId")
		mockMvc
			.post(BASE) {
				with(csrf())
				contentType = MediaType.APPLICATION_JSON
				content = """{"title":"a","projectId":"${trashed.id}"}"""
			}.isProblem(400, "projectId")
	}

	// --- complete, uncomplete

	@Test
	fun `complete and uncomplete, If-Match optional but checked when sent`(events: ApplicationEvents) {
		val task = newTask()
		val id = task.id()
		action(id, "complete", "\"5\"").isProblem(412)
		action(id, "complete").andExpect {
			status { isOk() }
			header { string(HttpHeaders.ETAG, "\"1\"") }
			jsonPath("$.completedAt") { value("2026-03-01T08:00:00Z") }
		}
		listIds() shouldBe emptyList()
		listIds("completed" to "true") shouldBe listOf(id)

		action(id, "uncomplete", "\"1\"").andExpect {
			status { isOk() }
			jsonPath("$.completedAt") { doesNotExist() }
			jsonPath("$.list") { value("INBOX") }
		}
		listIds() shouldBe listOf(id)
		events.changes().map { it.first } shouldContainExactly listOf(ChangeOp.CREATED, ChangeOp.UPDATED, ChangeOp.UPDATED)
	}

	// --- move

	@Test
	fun `move validates the list rules`() {
		val id = newTask().id()
		move(id, """{"list":"SCHEDULED"}""").isProblem(400, "scheduledDate")
		move(id, """{"list":"NEXT","scheduledDate":"2026-04-01"}""").isProblem(400, "scheduledDate")
		move(id, """{"list":"NEXT","waitingForText":"Anna"}""").isProblem(400, "waitingForText")
		move(id, """{"list":"NEXT","ticklerDate":"2026-03-01"}""").isProblem(400, "ticklerDate")
		move(id, """{"list":"NEXT","projectId":"${UuidV7.generate()}"}""").isProblem(400, "projectId")
		move(id, """{}""").isProblem(400, "list")
		move(id, """{"list":"DONE"}""").isProblem(400)
		move(UuidV7.generate().toString(), """{"list":"NEXT"}""").isProblem(404)
		fetch(id).get("list").asString() shouldBe "INBOX"
	}

	@Test
	fun `move to SCHEDULED, WAITING and back`(events: ApplicationEvents) {
		val project = projects.save(Project("Renovate kitchen"))
		val task = newTask()
		val id = task.id()

		move(id, """{"list":"SCHEDULED","scheduledDate":"2026-04-01","projectId":"${project.id}"}""", task.etag()).andExpect {
			status { isOk() }
			jsonPath("$.list") { value("SCHEDULED") }
			jsonPath("$.scheduledDate") { value("2026-04-01") }
			jsonPath("$.projectId") { value(project.id.toString()) }
		}

		move(id, """{"list":"WAITING","waitingForText":"Anna"}""").andExpect {
			jsonPath("$.list") { value("WAITING") }
			jsonPath("$.scheduledDate") { doesNotExist() }
			jsonPath("$.waitingSince") { value("2026-03-01") }
			jsonPath("$.waitingForText") { value("Anna") }
			jsonPath("$.projectId") { value(project.id.toString()) }
		}

		// Staying in WAITING keeps the original waitingSince.
		clock.advance(java.time.Duration.ofDays(3))
		move(id, """{"list":"WAITING"}""").andExpect {
			jsonPath("$.waitingSince") { value("2026-03-01") }
			jsonPath("$.waitingForText") { value("Anna") }
		}

		move(id, """{"list":"NEXT","ticklerDate":"2026-03-10"}""").andExpect {
			jsonPath("$.list") { value("NEXT") }
			jsonPath("$.waitingSince") { doesNotExist() }
			jsonPath("$.waitingForText") { doesNotExist() }
			jsonPath("$.ticklerDate") { value("2026-03-10") }
		}
		move(id, """{"list":"NEXT"}""", "\"0\"").isProblem(412)
		events.changes().count { it.first == ChangeOp.UPDATED } shouldBe 4
	}

	// --- delete, Trash

	@Test
	fun `DELETE moves the task to the Trash, restore and purge go through the Trash`(events: ApplicationEvents) {
		val task = newTask()
		val id = task.id()
		mockMvc.delete("$BASE/$id") { with(csrf()) }.isProblem(428)
		mockMvc
			.delete("$BASE/$id") {
				with(csrf())
				header(HttpHeaders.IF_MATCH, "\"3\"")
			}.isProblem(412)
		mockMvc
			.delete("$BASE/$id") {
				with(csrf())
				header(HttpHeaders.IF_MATCH, task.etag())
			}.andExpect { status { isNoContent() } }

		mockMvc.get("$BASE/$id").isProblem(404)
		listIds() shouldBe emptyList()
		searchTitle(id) shouldBe null
		events.changes().last() shouldBe (ChangeOp.DELETED to id)
		mockMvc.get(TRASH).andExpect {
			status { isOk() }
			jsonPath("$.items[?(@.id == '$id')].type") { value("TASK") }
			jsonPath("$.items[?(@.id == '$id')].title") { value("Call the plumber") }
			jsonPath("$.items[?(@.id == '$id')].subtitle") { value("Inbox") }
		}

		mockMvc.post("$TRASH/TASK/$id/restore") { with(csrf()) }.andExpect { status { isNoContent() } }
		fetch(id).get("title").asString() shouldBe "Call the plumber"
		searchTitle(id) shouldBe "Call the plumber"

		val current = fetch(id)
		mockMvc.delete("$BASE/$id") {
			with(csrf())
			header(HttpHeaders.IF_MATCH, current.etag())
		}
		mockMvc.delete("$TRASH/TASK/$id") { with(csrf()) }.andExpect { status { isNoContent() } }
		jdbc.queryForObject("select count(*) from task where id = ?::uuid", Int::class.java, id) shouldBe 0
	}

	// --- list filters and pagination

	@Test
	fun `GET filters by list, project, context, energy, creativity and estimate`() {
		val project = projects.save(Project("Renovate kitchen"))
		val home = contexts.save(Context("@home"))
		val inbox = newTask("Inbox item").id()
		val next =
			create(
				"""{"title":"Low home","list":"NEXT","energy":"LOW","creativity":"ROUTINE","timeEstimateMin":10,"contextIds":["${home.id}"]}""",
			).id()
		val nextHigh = create("""{"title":"High","list":"NEXT","energy":"HIGH","creativity":"CREATIVE","timeEstimateMin":60}""").id()
		val inProject = create("""{"title":"Tiles","projectId":"${project.id}","list":"SOMEDAY"}""").id()

		listIds() shouldContainExactly listOf(inbox, next, inProject, nextHigh)
		listIds("list" to "NEXT").toSet() shouldBe setOf(next, nextHigh)
		listIds("projectId" to project.id.toString()) shouldBe listOf(inProject)
		listIds("context" to home.id.toString()) shouldBe listOf(next)
		listIds("energy" to "LOW") shouldBe listOf(next)
		listIds("creativity" to "CREATIVE") shouldBe listOf(nextHigh)
		listIds("maxMinutes" to "15") shouldBe listOf(next)
		listIds("list" to "NEXT", "maxMinutes" to "60", "energy" to "HIGH") shouldBe listOf(nextHigh)
	}

	@Test
	fun `GET filters by due window in the user's time zone`() {
		val overdue = create("""{"title":"Overdue","dueDate":"2026-02-27"}""").id()
		val today = create("""{"title":"Today","dueDate":"2026-03-01"}""").id()
		val inSixDays = create("""{"title":"In six days","dueDate":"2026-03-07"}""").id()
		create("""{"title":"In a week","dueDate":"2026-03-08"}""")
		newTask("No due date")

		listIds("due" to "overdue") shouldBe listOf(overdue)
		listIds("due" to "TODAY") shouldBe listOf(today)
		listIds("due" to "week").toSet() shouldBe setOf(today, inSixDays)
	}

	@Test
	fun `GET pages with a cursor, also for done tasks`() {
		val ids = (1..5).map { newTask("Task $it").id() }
		val first =
			jsonMapper.readTree(
				mockMvc
					.get(BASE) { param("limit", "2") }
					.andReturn()
					.response.contentAsString,
			)
		first.get("items").ids() shouldBe ids.take(2)
		val cursor = first.get("nextCursor").asString()
		listIds("limit" to "2", "cursor" to cursor) shouldBe ids.subList(2, 4)

		ids.forEach {
			action(it, "complete")
			clock.advance(java.time.Duration.ofMinutes(1))
		}
		val done =
			jsonMapper.readTree(
				mockMvc
					.get(BASE) {
						param("completed", "true")
						param("limit", "3")
					}.andReturn()
					.response.contentAsString,
			)
		done.get("items").ids() shouldBe ids.reversed().take(3)
		listIds("completed" to "true", "cursor" to done.get("nextCursor").asString()) shouldBe ids.reversed().drop(3)
	}

	@Test
	fun `GET rejects invalid parameters`() {
		listOf(
			mapOf("list" to "DONE"),
			mapOf("due" to "tomorrow"),
			mapOf("maxMinutes" to "-1"),
			mapOf("context" to "home"),
			mapOf("cursor" to "not-a-cursor"),
			mapOf("limit" to "0"),
		).forEach { params ->
			mockMvc.get(BASE) { params.forEach { (name, value) -> param(name, value) } }.isProblem(400)
		}
	}

	@Test
	@WithAnonymousUser
	fun `requires login`() {
		mockMvc.get(BASE).andExpect { status { isUnauthorized() } }
	}

	private companion object {
		const val BASE = "/api/v1/tasks"
		const val TRASH = "/api/v1/trash"
	}
}
