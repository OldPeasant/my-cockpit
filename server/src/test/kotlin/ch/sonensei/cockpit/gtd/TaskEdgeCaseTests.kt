package ch.sonensei.cockpit.gtd

import ch.sonensei.cockpit.core.auth.WithCockpitUser
import ch.sonensei.cockpit.core.events.domain.ChangeEvent
import ch.sonensei.cockpit.core.events.domain.ChangeOp
import ch.sonensei.cockpit.core.persistence.MutableClock
import ch.sonensei.cockpit.core.persistence.MutableClockTestConfiguration
import ch.sonensei.cockpit.core.persistence.UuidV7
import ch.sonensei.cockpit.core.settings.domain.SettingsService
import ch.sonensei.cockpit.gtd.domain.Context
import ch.sonensei.cockpit.gtd.domain.Project
import ch.sonensei.cockpit.gtd.infra.ContextRepository
import ch.sonensei.cockpit.gtd.infra.ProjectRepository
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.matchers.collections.shouldContainExactly
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
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * Edge cases of the Tasks API (gtd-tasks-api) beyond [TaskWebTests]: boundaries, the user's time zone, positions after
 * a move, the Trash round trip with cascades, and the ETag rules of the workflow actions.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MutableClockTestConfiguration::class)
@RecordApplicationEvents
@WithCockpitUser
class TaskEdgeCaseTests(
	@Autowired private val mockMvc: MockMvc,
	@Autowired private val jsonMapper: JsonMapper,
	@Autowired private val projects: ProjectRepository,
	@Autowired private val contexts: ContextRepository,
	@Autowired private val clock: MutableClock,
	@Autowired private val settings: SettingsService,
	@Autowired private val jdbc: JdbcTemplate,
	@Autowired private val dataSource: DataSource,
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

	private fun create(body: String): JsonNode =
		post(body)
			.andExpect { status { isCreated() } }
			.andReturn()
			.response.contentAsString
			.let(jsonMapper::readTree)

	private fun newTask(title: String = "Water the plants"): JsonNode = create("""{"title":"$title"}""")

	private fun JsonNode.id(): String = get("id").asString()

	private fun JsonNode.etag(): String = "\"${get("version").asLong()}\""

	/** Absent or `null`: both mean "not set" in a response. */
	private fun JsonNode.isAbsent(field: String): Boolean = get(field)?.isNull ?: true

	private fun fetch(id: String): JsonNode =
		mockMvc
			.get("$BASE/$id")
			.andExpect { status { isOk() } }
			.andReturn()
			.response.contentAsString
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

	private fun moved(
		id: String,
		body: String,
	): JsonNode =
		move(id, body)
			.andExpect { status { isOk() } }
			.andReturn()
			.response.contentAsString
			.let(jsonMapper::readTree)

	private fun action(
		id: String,
		name: String,
		ifMatch: String? = null,
	) = mockMvc.post("$BASE/$id/$name") {
		with(csrf())
		ifMatch?.let { header(HttpHeaders.IF_MATCH, it) }
	}

	private fun deleteTask(id: String) =
		mockMvc
			.delete("$BASE/$id") {
				with(csrf())
				header(HttpHeaders.IF_MATCH, fetch(id).etag())
			}.andExpect { status { isNoContent() } }

	private fun page(vararg params: Pair<String, String>): JsonNode =
		mockMvc
			.get(BASE) { params.forEach { (name, value) -> param(name, value) } }
			.andExpect { status { isOk() } }
			.andReturn()
			.response.contentAsString
			.let(jsonMapper::readTree)

	private fun listIds(vararg params: Pair<String, String>): List<String> = page(*params).get("items").values().map { it.id() }

	private fun ResultActionsDsl.isProblem(
		status: Int,
		vararg fields: String,
	) = andExpect {
		status { isEqualTo(status) }
		content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
		fields.forEach { field -> jsonPath("$.errors[?(@.field == '$field')]") { isNotEmpty() } }
	}

	private fun count(
		sql: String,
		vararg args: Any,
	): Int? = jdbc.queryForObject(sql, Int::class.java, *args)

	private fun ApplicationEvents.changes(): List<Pair<ChangeOp, String>> =
		stream(ChangeEvent::class.java).toList().filter { it.type == "TASK" }.map { it.op to it.id.toString() }

	// --- create: idempotency and limits

	@Test
	fun `POST with the id of a task in the Trash is 409 and does not restore it`() {
		val id = UuidV7.generate()
		create("""{"id":"$id","title":"Offline capture"}""")
		deleteTask(id.toString())

		post("""{"id":"$id","title":"Offline capture"}""").isProblem(409)
		mockMvc.get("$BASE/$id").isProblem(404)
	}

	@Test
	fun `POST that loses the insert race against a create with the same id answers like the idempotent repeat`() {
		val id = UuidV7.generate()
		val inserted = CountDownLatch(1)
		val pool = Executors.newSingleThreadExecutor()
		val competitor =
			pool.submit {
				// The competing create: inserted but not yet committed, so the POST does not find it and blocks on the insert.
				dataSource.connection.use { connection ->
					connection.autoCommit = false
					connection
						.prepareStatement(
							"insert into task (id, created_at, updated_at, version, title, list) values (?, now(), now(), 0, ?, 'INBOX')",
						).use {
							it.setObject(1, id)
							it.setString(2, "Offline capture")
							it.executeUpdate()
						}
					inserted.countDown()
					val deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos()
					val waiting = "select count(*) from pg_stat_activity where wait_event_type = 'Lock' and query ilike '%insert into task %'"
					while (count(waiting) == 0) {
						check(System.nanoTime() < deadline) { "the POST never waited for the competing insert" }
						Thread.sleep(20)
					}
					connection.commit()
				}
			}
		inserted.await(30, TimeUnit.SECONDS) shouldBe true

		post("""{"id":"$id","title":"Offline capture"}""").andExpect {
			status { isOk() }
			header { string(HttpHeaders.ETAG, "\"0\"") }
			jsonPath("$.id") { value(id.toString()) }
		}
		competitor.get(30, TimeUnit.SECONDS)
		pool.shutdown()
		count("select count(*) from task where id = ?", id) shouldBe 1
	}

	@Test
	fun `POST accepts values at the limits and rejects values just above them, all at once`() {
		val atLimits =
			create(
				"""
				{"title":"${"t".repeat(500)}","notesMd":"${"n".repeat(100_000)}","timeEstimateMin":10000,
				 "recurrenceRule":"${"r".repeat(500)}","list":"WAITING","waitingForText":"${"w".repeat(500)}"}
				""".trimIndent(),
			)
		atLimits.get("title").asString().length shouldBe 500
		atLimits.get("timeEstimateMin").asInt() shouldBe 10000
		create("""{"title":"Quick","timeEstimateMin":0}""").get("timeEstimateMin").asInt() shouldBe 0

		post(
			"""
			{"title":"${"t".repeat(501)}","notesMd":"${"n".repeat(100_001)}","timeEstimateMin":10001,
			 "recurrenceRule":"${"r".repeat(501)}","list":"WAITING","waitingForText":"${"w".repeat(501)}"}
			""".trimIndent(),
		).isProblem(400, "title", "notesMd", "timeEstimateMin", "recurrenceRule", "waitingForText")
	}

	@Test
	fun `POST keeps a recurrence rule and mode as given and an explicit position`() {
		val task = create("""{"title":"Pay rent","recurrenceRule":"FREQ=MONTHLY;BYMONTHDAY=1","recurrenceMode":"FIXED","position":7}""")
		task.get("recurrenceRule").asString() shouldBe "FREQ=MONTHLY;BYMONTHDAY=1"
		task.get("recurrenceMode").asString() shouldBe "FIXED"
		task.get("position").asInt() shouldBe 7
		newTask().get("position").asInt() shouldBe 8
	}

	// --- PATCH

	@Test
	fun `PATCH rejects an unknown context, null for uncleareable fields and values of the wrong type`() {
		val task = newTask()
		val id = task.id()
		patch(id, """{"contextIds":["${UuidV7.generate()}"]}""", task.etag()).isProblem(400, "contextIds")
		patch(id, """{"position":null,"contextIds":null}""", task.etag()).isProblem(400, "position", "contextIds")
		patch(id, """{"timeEstimateMin":"soon"}""", task.etag()).isProblem(400, "timeEstimateMin")
		patch(id, """{"timeEstimateMin":10001}""", task.etag()).isProblem(400, "timeEstimateMin")
		patch(id, """{"dueDate":"2026-02-30"}""", task.etag()).isProblem(400, "dueDate")
		fetch(id).get("version").asLong() shouldBe 0
	}

	@Test
	fun `PATCH clears enums and recurrence with null and updates the search index`() {
		val task =
			create("""{"title":"Pay rent","energy":"LOW","creativity":"ROUTINE","recurrenceRule":"FREQ=MONTHLY","recurrenceMode":"FIXED"}""")
		patch(
			task.id(),
			"""{"energy":null,"creativity":null,"recurrenceRule":null,"recurrenceMode":null,"notesMd":"Account of the landlord"}""",
			task.etag(),
		).andExpect {
			status { isOk() }
			jsonPath("$.energy") { doesNotExist() }
			jsonPath("$.creativity") { doesNotExist() }
			jsonPath("$.recurrenceRule") { doesNotExist() }
			jsonPath("$.recurrenceMode") { doesNotExist() }
		}
		jdbc.queryForObject(
			"select body from search_document where item_type = 'TASK' and item_id = ?::uuid",
			String::class.java,
			task.id(),
		) shouldBe "Account of the landlord"
	}

	@Test
	fun `PATCH into another project puts the task at the end of that project unless a position is sent`() {
		val project = projects.save(Project("Plan the garden"))
		val first = create("""{"title":"Buy seeds","projectId":"${project.id}"}""")
		val second = create("""{"title":"Dig beds","projectId":"${project.id}"}""")
		val loose = newTask("Order compost")

		patch(loose.id(), """{"projectId":"${project.id}"}""", loose.etag()).andExpect {
			status { isOk() }
			jsonPath("$.projectId") { value(project.id.toString()) }
		}
		listIds("projectId" to project.id.toString()) shouldContainExactly listOf(first.id(), second.id(), loose.id())

		val other = newTask("Rake leaves")
		patch(other.id(), """{"projectId":"${project.id}","position":0}""", other.etag()).andExpect {
			jsonPath("$.position") { value(0) }
		}
	}

	@Test
	fun `PATCH and DELETE of a task in the Trash are 404, DELETE twice is 404`() {
		val task = newTask()
		deleteTask(task.id())
		patch(task.id(), """{"title":"Again"}""", task.etag()).isProblem(404)
		mockMvc
			.delete("$BASE/${task.id()}") {
				with(csrf())
				header(HttpHeaders.IF_MATCH, "*")
			}.isProblem(404)
		action(task.id(), "complete").isProblem(404)
		move(task.id(), """{"list":"NEXT"}""").isProblem(404)
	}

	@Test
	fun `If-Match accepts a list of tags and the wildcard, but not a weak tag`() {
		val task = newTask()
		patch(task.id(), """{"title":"a"}""", "W/\"0\"").isProblem(412)
		patch(task.id(), """{"title":"a"}""", "\"5\", \"0\"").andExpect { status { isOk() } }
		patch(task.id(), """{"title":"b"}""", "*").andExpect { status { isOk() } }
	}

	// --- complete, uncomplete

	@Test
	fun `completing a done task keeps the first completion time, uncomplete returns it to its list`() {
		val task = create("""{"title":"Renew passport","list":"NEXT"}""")
		val id = task.id()
		action(id, "complete").andExpect { jsonPath("$.completedAt") { value("2026-03-01T08:00:00Z") } }
		clock.advance(Duration.ofHours(2))
		action(id, "complete").andExpect {
			status { isOk() }
			jsonPath("$.completedAt") { value("2026-03-01T08:00:00Z") }
		}
		action(id, "uncomplete", "\"0\"").isProblem(412)
		action(id, "uncomplete").andExpect {
			jsonPath("$.list") { value("NEXT") }
			jsonPath("$.completedAt") { doesNotExist() }
		}
		listIds("list" to "NEXT") shouldBe listOf(id)
	}

	@Test
	fun `a done task in the Trash is not in the Logbook and shows as Done in the Trash`() {
		val done = newTask("Book the flight").id()
		val keep = newTask("Pack the bags").id()
		action(done, "complete")
		action(keep, "complete")
		deleteTask(done)

		listIds("completed" to "true") shouldBe listOf(keep)
		mockMvc.get(TRASH).andExpect {
			jsonPath("$.items[?(@.id == '$done')].subtitle") { value("Done") }
		}
	}

	// --- move

	@Test
	fun `move to another list puts the task at the end of that list and returns its current ETag`() {
		val next = create("""{"title":"Call the bank","list":"NEXT"}""").id()
		val task = newTask("Fix the bike").id()

		val moved = moved(task, """{"list":"NEXT"}""")
		listIds("list" to "NEXT") shouldContainExactly listOf(next, task)
		val current = fetch(task)
		moved.get("version") shouldBe current.get("version")
		patch(task, """{"title":"Fix the bike tyre"}""", moved.etag()).andExpect { status { isOk() } }
	}

	@Test
	fun `move to another list keeps a project task at its position in the project`() {
		val project = projects.save(Project("Plan the garden"))
		val steps = listOf("Buy seeds", "Dig beds", "Sow").map { create("""{"title":"$it","list":"NEXT","projectId":"${project.id}"}""") }
		val middle = steps[1]

		val moved = moved(middle.id(), """{"list":"WAITING","waitingForText":"Anna"}""")
		moved.get("position").asInt() shouldBe middle.get("position").asInt()
		listIds("projectId" to project.id.toString()) shouldContainExactly steps.map { it.id() }

		val later = moved(middle.id(), """{"list":"SOMEDAY"}""")
		later.get("position").asInt() shouldBe middle.get("position").asInt()
		listIds("projectId" to project.id.toString()) shouldContainExactly steps.map { it.id() }
	}

	@Test
	fun `move reports every violation at once and rejects waitingForContactId outside WAITING`() {
		val id = newTask().id()
		move(
			id,
			"""{"list":"NEXT","scheduledDate":"2026-04-01","waitingForText":"Anna","waitingForContactId":"${UuidV7.generate()}","ticklerDate":"2026-02-01"}""",
		).isProblem(400, "scheduledDate", "waitingForText", "waitingForContactId", "ticklerDate")
		fetch(id).get("version").asLong() shouldBe 0
	}

	@Test
	fun `move keeps the tickler only while it is sent, tomorrow is the earliest tickler date`() {
		val id = newTask().id()
		moved(id, """{"list":"SOMEDAY","ticklerDate":"2026-03-02"}""").get("ticklerDate").asString() shouldBe "2026-03-02"
		moved(id, """{"list":"SOMEDAY"}""").isAbsent("ticklerDate") shouldBe true
	}

	@Test
	fun `WAITING with a contact, leaving clears it, waiting again starts a new waitingSince`() {
		val contact = UuidV7.generate()
		val id = newTask("Quote from the roofer").id()
		moved(id, """{"list":"WAITING","waitingForContactId":"$contact"}""").get("waitingForContactId").asString() shouldBe contact.toString()

		clock.advance(Duration.ofDays(2))
		val left = moved(id, """{"list":"NEXT"}""")
		left.isAbsent("waitingForContactId") shouldBe true
		left.isAbsent("waitingSince") shouldBe true

		clock.advance(Duration.ofDays(1))
		val again = moved(id, """{"list":"WAITING","waitingForText":"Roofer"}""")
		again.get("waitingSince").asString() shouldBe "2026-03-04"
		again.isAbsent("waitingForContactId") shouldBe true
	}

	@Test
	fun `an explicit waitingSince is kept on create, a cleared one is set again by a move within WAITING`() {
		val task = create("""{"title":"Invoice from the garage","list":"WAITING","waitingSince":"2026-02-20"}""")
		task.get("waitingSince").asString() shouldBe "2026-02-20"

		patch(task.id(), """{"waitingSince":null}""", task.etag()).andExpect { status { isOk() } }
		moved(task.id(), """{"list":"WAITING"}""").get("waitingSince").asString() shouldBe "2026-03-01"
	}

	@Test
	fun `move with a stale If-Match is 412 and changes nothing, the wildcard is accepted`() {
		val task = newTask()
		patch(task.id(), """{"title":"Changed elsewhere"}""", task.etag())
		move(task.id(), """{"list":"NEXT"}""", task.etag()).isProblem(412)
		fetch(task.id()).get("list").asString() shouldBe "INBOX"
		move(task.id(), """{"list":"NEXT"}""", "*").andExpect { status { isOk() } }
	}

	// --- time zone

	@Test
	fun `due windows and the tickler use today in the user's time zone, not in UTC`() {
		// 12:00 UTC on 1 March is 01:00 on 2 March in Auckland (UTC+13).
		settings.update(mapOf("timeZone" to "Pacific/Auckland"))
		clock.set(Instant.parse("2026-03-01T12:00:00Z"))
		val yesterday = create("""{"title":"Yesterday","dueDate":"2026-03-01"}""").id()
		val today = create("""{"title":"Today","dueDate":"2026-03-02"}""").id()
		val lastDayOfWeek = create("""{"title":"Sunday","dueDate":"2026-03-08"}""").id()
		create("""{"title":"Next week","dueDate":"2026-03-09"}""")

		listIds("due" to "overdue") shouldBe listOf(yesterday)
		listIds("due" to "today") shouldBe listOf(today)
		listIds("due" to "week") shouldContainExactly listOf(today, lastDayOfWeek)

		move(today, """{"list":"NEXT","ticklerDate":"2026-03-02"}""").isProblem(400, "ticklerDate")
		moved(today, """{"list":"WAITING"}""").get("waitingSince").asString() shouldBe "2026-03-02"
	}

	// --- list filters

	@Test
	fun `maxMinutes includes the limit itself and never matches tasks without an estimate`() {
		val zero = create("""{"title":"Glance at mail","timeEstimateMin":0}""").id()
		val fifteen = create("""{"title":"Tidy desk","timeEstimateMin":15}""").id()
		create("""{"title":"Sixteen","timeEstimateMin":16}""")
		newTask("No estimate")

		listIds("maxMinutes" to "15") shouldContainExactly listOf(zero, fifteen)
		listIds("maxMinutes" to "0") shouldBe listOf(zero)
	}

	@Test
	fun `filters combine with the Logbook, a done task does not appear in open lists`() {
		val home = contexts.save(Context("@home"))
		val doneAtHome = create("""{"title":"Hang the picture","list":"NEXT","contextIds":["${home.id}"]}""").id()
		val openAtHome = create("""{"title":"Fix the shelf","list":"NEXT","contextIds":["${home.id}"]}""").id()
		action(doneAtHome, "complete")

		listIds("context" to home.id.toString()) shouldBe listOf(openAtHome)
		listIds("context" to home.id.toString(), "completed" to "true") shouldBe listOf(doneAtHome)
		listIds("list" to "INBOX", "completed" to "true") shouldBe emptyList()
	}

	@Test
	fun `the last page has no cursor, a cursor of the open list is rejected for the Logbook`() {
		val ids = (1..3).map { newTask("Task $it").id() }
		val first = page("limit" to "2")
		val cursor = first.get("nextCursor").asString()
		val last = page("limit" to "2", "cursor" to cursor)
		last.get("items").values().map { it.id() } shouldBe listOf(ids[2])
		last.isAbsent("nextCursor") shouldBe true

		mockMvc
			.get(BASE) {
				param("completed", "true")
				param("cursor", cursor)
			}.isProblem(400, "cursor")
		mockMvc.get(BASE) { param("maxMinutes", "abc") }.isProblem(400)
		mockMvc.get(BASE) { param("limit", "201") }.isProblem(400, "limit")
		// Well-formed base64url, but not "<position>|<id>".
		mockMvc.get(BASE) { param("cursor", "YWJj") }.isProblem(400, "cursor")
	}

	// --- Trash: restore, purge

	@Test
	fun `restore announces the task, purge deletes its checklist and contexts and clears it as next step`(events: ApplicationEvents) {
		val project = projects.save(Project("Plan the garden"))
		val shed = contexts.save(Context("@shed"))
		val id = create("""{"title":"Sharpen the shears","projectId":"${project.id}","contextIds":["${shed.id}"]}""").id()
		mockMvc
			.post("$BASE/$id/checklist") {
				with(csrf())
				contentType = MediaType.APPLICATION_JSON
				content = """{"text":"Find the whetstone"}"""
			}.andExpect { status { isCreated() } }
		jdbc.update("update project set next_task_id = ?::uuid where id = ?", id, project.id)

		deleteTask(id)
		mockMvc.post("$TRASH/TASK/$id/restore") { with(csrf()) }.andExpect { status { isNoContent() } }
		events.changes().last() shouldBe (ChangeOp.UPDATED to id)
		fetch(id).get("contextIds").values().map { it.asString() } shouldBe listOf(shed.id.toString())

		deleteTask(id)
		mockMvc.delete("$TRASH/TASK/$id") { with(csrf()) }.andExpect { status { isNoContent() } }
		events.changes().last() shouldBe (ChangeOp.DELETED to id)
		count("select count(*) from task where id = ?::uuid", id) shouldBe 0
		count("select count(*) from checklist_item where task_id = ?::uuid", id) shouldBe 0
		count("select count(*) from task_context where task_id = ?::uuid", id) shouldBe 0
		count("select count(*) from search_document where item_type = 'TASK' and item_id = ?::uuid", id) shouldBe 0
		count("select count(*) from project where id = ? and next_task_id is null", project.id) shouldBe 1
		count("select count(*) from context where id = ?", shed.id) shouldBe 1
	}

	@Test
	fun `restore and purge of a task that is not in the Trash are 404`() {
		val active = newTask().id()
		mockMvc.post("$TRASH/TASK/$active/restore") { with(csrf()) }.isProblem(404)
		mockMvc.delete("$TRASH/TASK/$active") { with(csrf()) }.isProblem(404)
		mockMvc.delete("$TRASH/TASK/${UuidV7.generate()}") { with(csrf()) }.isProblem(404)
		fetch(active).get("title").asString() shouldBe "Water the plants"
	}

	private companion object {
		const val BASE = "/api/v1/tasks"
		const val TRASH = "/api/v1/trash"
	}
}
