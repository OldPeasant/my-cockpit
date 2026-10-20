package ch.sonensei.cockpit.gtd

import ch.sonensei.cockpit.core.auth.WithCockpitUser
import ch.sonensei.cockpit.core.events.domain.ChangeEvent
import ch.sonensei.cockpit.core.events.domain.ChangeOp
import ch.sonensei.cockpit.core.persistence.MutableClock
import ch.sonensei.cockpit.core.persistence.MutableClockTestConfiguration
import ch.sonensei.cockpit.core.persistence.UuidV7
import ch.sonensei.cockpit.core.web.CursorRequest
import ch.sonensei.cockpit.gtd.domain.Change
import ch.sonensei.cockpit.gtd.domain.ProjectChanges
import ch.sonensei.cockpit.gtd.domain.ProjectFilter
import ch.sonensei.cockpit.gtd.domain.ProjectService
import ch.sonensei.cockpit.gtd.infra.ProjectTrashContributor
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.dao.OptimisticLockingFailureException
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
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * Edge cases of the projects, areas and contexts API (gtd-projects-api) beyond [ProjectWebTests] and
 * [AreaContextWebTests]: the Trash contributor itself, the stalled rule in lists and filters, the next-step prompt on
 * repeated completes, cursor boundaries, deletes that reach into the Trash, search through the API, and optimistic
 * locking.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MutableClockTestConfiguration::class)
@RecordApplicationEvents
@WithCockpitUser
class ProjectEdgeCaseTests(
	@Autowired private val mockMvc: MockMvc,
	@Autowired private val jsonMapper: JsonMapper,
	@Autowired private val clock: MutableClock,
	@Autowired private val jdbc: JdbcTemplate,
	@Autowired private val dataSource: DataSource,
	@Autowired private val projectService: ProjectService,
	@Autowired private val trash: ProjectTrashContributor,
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

	private fun newProject(body: String = """{"title":"Renovate kitchen"}"""): String =
		post(PROJECTS, body).andExpect { status { isCreated() } }.json().id()

	private fun newTask(
		projectId: String?,
		title: String = "Call the tiler",
		extra: String = "",
	): String {
		val project = projectId?.let { ""","projectId":"$it"""" } ?: ""
		return post(TASKS, """{"title":"$title","list":"NEXT"$project$extra}""").andExpect { status { isCreated() } }.json().id()
	}

	private fun JsonNode.id(): String = get("id").asString()

	private fun JsonNode.isNullOrAbsent(field: String): Boolean = get(field).let { it == null || it.isNull }

	private fun JsonNode.etag(): String = "\"${get("version").asLong()}\""

	private fun fetch(id: String): JsonNode = mockMvc.get("$PROJECTS/$id").andExpect { status { isOk() } }.json()

	private fun fetchTask(id: String): JsonNode = mockMvc.get("$TASKS/$id").andExpect { status { isOk() } }.json()

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

	private fun deleteWithAnyVersion(path: String) =
		mockMvc
			.delete(path) {
				with(csrf())
				header(HttpHeaders.IF_MATCH, "*")
			}.andExpect { status { isNoContent() } }

	private fun complete(taskId: String): JsonNode =
		mockMvc
			.post("$TASKS/$taskId/complete") {
				with(csrf())
			}.andExpect { status { isOk() } }
			.json()

	private fun restore(
		type: String,
		id: String,
	) = mockMvc.post("/api/v1/trash/$type/$id/restore") { with(csrf()) }.andExpect { status { is2xxSuccessful() } }

	private fun order(
		id: String,
		vararg taskIds: String,
	) = mockMvc.put("$PROJECTS/$id/order") {
		with(csrf())
		contentType = MediaType.APPLICATION_JSON
		content = """{"taskIds":[${taskIds.joinToString(",") { "\"$it\"" }}]}"""
	}

	private fun page(vararg params: Pair<String, String>): JsonNode =
		mockMvc
			.get(PROJECTS) { params.forEach { (name, value) -> param(name, value) } }
			.andExpect { status { isOk() } }
			.json()

	private fun listIds(vararg params: Pair<String, String>): List<String> = page(*params).get("items").values().map { it.id() }

	private fun JsonNode.nextCursor(): String? = get("nextCursor").takeUnless { it == null || it.isNull }?.asString()

	/** Every page of the list with [params], following the cursor. */
	private fun allPages(
		limit: Int,
		vararg params: Pair<String, String>,
	): List<String> {
		val seen = mutableListOf<String>()
		var cursor: String? = null
		do {
			val page = page("limit" to "$limit", *params, *listOfNotNull(cursor?.let { "cursor" to it }).toTypedArray())
			seen += page.get("items").values().map { it.id() }
			cursor = page.nextCursor()
		} while (cursor != null)
		return seen
	}

	private fun ResultActionsDsl.isProblem(
		status: Int,
		vararg fields: String,
	) = andExpect {
		status { isEqualTo(status) }
		content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
		fields.forEach { field -> jsonPath("$.errors[?(@.field == '$field')]") { isNotEmpty() } }
	}

	private fun searchDocument(id: String): Pair<String, String>? =
		jdbc
			.query(
				"select title, body from search_document where item_type = 'PROJECT' and item_id = ?::uuid",
				{ rs, _ -> rs.getString(1) to rs.getString(2) },
				id,
			).singleOrNull()

	private fun ApplicationEvents.changes(type: String): List<Pair<ChangeOp, String>> =
		stream(ChangeEvent::class.java).toList().filter { it.type == type }.map { it.op to it.id.toString() }

	/** Changes a project's row in its own committed transaction, as a concurrent request would. */
	private fun concurrentRename(
		id: String,
		title: String,
	) {
		dataSource.connection.use { connection ->
			connection.autoCommit = true
			connection.prepareStatement("update project set title = ?, version = version + 1 where id = ?").use {
				it.setString(1, title)
				it.setObject(2, UUID.fromString(id))
				it.executeUpdate() shouldBe 1
			}
		}
	}

	// --- ProjectTrashContributor

	@Test
	fun `the Trash contributor lists deleted projects with their deletion time, and only those`() {
		val kept = newProject("""{"title":"Garden shed"}""")
		val deleted = newProject()
		clock.set(MutableClockTestConfiguration.START.plusSeconds(90))
		projectService.delete(UUID.fromString(deleted)) {}

		val items = trash.listDeleted()
		items.map { it.id.toString() } shouldContainExactly listOf(deleted)
		with(items.single()) {
			title shouldBe "Renovate kitchen"
			deletedAt shouldBe MutableClockTestConfiguration.START.plusSeconds(90)
			subtitle shouldBe "Project"
			icon shouldBe "folder_open"
		}
		fetch(kept).get("title").asString() shouldBe "Garden shed"
	}

	@Test
	fun `the Trash contributor restores and purges only projects that are in the Trash`() {
		val active = UUID.fromString(newProject("""{"title":"Garden shed"}"""))
		val unknown = UuidV7.generate()
		trash.restore(active) shouldBe false
		trash.purge(active) shouldBe false
		trash.restore(unknown) shouldBe false
		trash.purge(unknown) shouldBe false
		fetch(active.toString()).get("title").asString() shouldBe "Garden shed"

		val deleted = UUID.fromString(newProject("""{"title":"Renovate kitchen","descriptionMd":"New tiles"}"""))
		projectService.delete(deleted) {}
		searchDocument(deleted.toString()) shouldBe null
		trash.restore(deleted) shouldBe true
		// Restored: searchable again with title and description, and no longer in the Trash.
		searchDocument(deleted.toString()) shouldBe ("Renovate kitchen" to "New tiles")
		trash.restore(deleted) shouldBe false
		trash.purge(deleted) shouldBe false
		trash.listDeleted().shouldBeEmpty()

		projectService.delete(deleted) {}
		trash.purge(deleted) shouldBe true
		trash.purge(deleted) shouldBe false
		trash.restore(deleted) shouldBe false
		jdbc.queryForObject("select count(*) from project where id = ?", Int::class.java, deleted) shouldBe 0
	}

	@Test
	fun `restoring a project from the Trash announces it and keeps its next step and stalled state`(events: ApplicationEvents) {
		val id = newProject()
		val task = newTask(id)
		nextStep(id, task).andExpect { status { isOk() } }
		deleteWithAnyVersion("$PROJECTS/$id")
		restore("PROJECT", id)

		val restored = fetch(id)
		restored.get("nextTaskId").asString() shouldBe task
		restored.get("stalled").asBoolean() shouldBe false
		events.changes("PROJECT").last() shouldBe (ChangeOp.UPDATED to id)
	}

	// --- needsNextStep

	@Test
	fun `completing the next step again keeps asking for a new one and keeps the first completion time`() {
		val id = newProject()
		val next = newTask(id)
		nextStep(id, next).andExpect { status { isOk() } }

		val first = complete(next)
		first.get("needsNextStep").asBoolean() shouldBe true
		clock.set(MutableClockTestConfiguration.START.plusSeconds(300))
		val second = complete(next)
		// nextTaskId still points to the done task (see the recorded assumption), so the prompt repeats.
		second.get("needsNextStep").asBoolean() shouldBe true
		second.get("completedAt").asString() shouldBe first.get("completedAt").asString()
		fetch(id).get("stalled").asBoolean() shouldBe true
	}

	@Test
	fun `uncomplete restores the next step, and completing it again asks again`() {
		val id = newProject()
		val next = newTask(id)
		nextStep(id, next).andExpect { status { isOk() } }
		complete(next).get("needsNextStep").asBoolean() shouldBe true
		mockMvc.post("$TASKS/$next/uncomplete") { with(csrf()) }.andExpect { status { isOk() } }
		fetch(id).get("stalled").asBoolean() shouldBe false
		complete(next).get("needsNextStep").asBoolean() shouldBe true
	}

	@Test
	fun `completing a former next step or the next step of a project in the Trash does not ask for a new one`() {
		val id = newProject()
		val former = newTask(id, "Call the tiler")
		val current = newTask(id, "Buy tiles")
		nextStep(id, former).andExpect { status { isOk() } }
		nextStep(id, current).andExpect { status { isOk() } }
		complete(former).get("needsNextStep").asBoolean() shouldBe false
		fetch(id).get("stalled").asBoolean() shouldBe false

		deleteWithAnyVersion("$PROJECTS/$id")
		complete(current).get("needsNextStep").asBoolean() shouldBe false
	}

	@Test
	fun `completing the next step of a project in each non-active status does not ask for a new one`() {
		listOf("SOMEDAY", "ON_HOLD", "COMPLETED", "DROPPED").forEach { projectStatus ->
			val id = newProject("""{"title":"Project $projectStatus"}""")
			val next = newTask(id)
			val withNext = nextStep(id, next).andExpect { status { isOk() } }.json()
			patch("$PROJECTS/$id", """{"status":"$projectStatus"}""", withNext.etag()).andExpect { status { isOk() } }
			complete(next).get("needsNextStep").asBoolean() shouldBe false
		}
	}

	// --- stalled rule in lists and filters

	@Test
	fun `the stalled flag of every listed project matches the stalled filter`() {
		val noNext = newProject("""{"title":"A no next step"}""")
		val open = newProject("""{"title":"B open next step"}""").also { nextStep(it, newTask(it)).andExpect { status { isOk() } } }
		val done = newProject("""{"title":"C done next step"}""")
		newTask(done).also {
			nextStep(done, it).andExpect { status { isOk() } }
			complete(it)
		}
		val trashed = newProject("""{"title":"D next step in Trash"}""")
		newTask(trashed).also {
			nextStep(trashed, it).andExpect { status { isOk() } }
			deleteWithAnyVersion("$TASKS/$it")
		}
		val someday = newProject("""{"title":"E someday","status":"SOMEDAY"}""")
		// An open task without being the designated next step does not keep a project from being stalled.
		val openTaskOnly = newProject("""{"title":"F open task only"}""").also { newTask(it) }

		val items = page().get("items").values().toList()
		val flagged = items.filter { it.get("stalled").asBoolean() }.map { it.id() }
		flagged shouldContainExactly listOf(noNext, done, trashed, openTaskOnly)
		listIds("stalled" to "true") shouldContainExactly flagged
		listIds("stalled" to "false") shouldContainExactly listOf(open, someday)
	}

	@Test
	fun `stalled combines with the other filters and never lists projects in the Trash`() {
		val area = post(AREAS, """{"title":"Home"}""").andExpect { status { isCreated() } }.json().id()
		val inArea = newProject("""{"title":"Renovate kitchen","areaId":"$area"}""")
		val elsewhere = newProject("""{"title":"Garden shed"}""")
		val trashed = newProject("""{"title":"Paint the fence","areaId":"$area"}""")
		deleteWithAnyVersion("$PROJECTS/$trashed")

		listIds("stalled" to "true") shouldContainExactly listOf(elsewhere, inArea)
		listIds("stalled" to "true", "areaId" to area) shouldContainExactly listOf(inArea)
		listIds("stalled" to "true", "status" to "ACTIVE") shouldContainExactly listOf(elsewhere, inArea)
		listIds("stalled" to "true", "status" to "SOMEDAY").shouldBeEmpty()
		listIds("stalled" to "false", "areaId" to area).shouldBeEmpty()
		mockMvc.get(PROJECTS) { param("stalled", "maybe") }.isProblem(400)
	}

	@Test
	fun `a next step restored from the Trash un-stalls the project, a purged one leaves it without a next step`() {
		val id = newProject()
		val task = newTask(id)
		nextStep(id, task).andExpect { status { isOk() } }
		deleteWithAnyVersion("$TASKS/$task")
		fetch(id).get("stalled").asBoolean() shouldBe true

		restore("TASK", task)
		fetch(id).get("stalled").asBoolean() shouldBe false
		listIds("stalled" to "false") shouldContainExactly listOf(id)

		deleteWithAnyVersion("$TASKS/$task")
		mockMvc.delete("/api/v1/trash/TASK/$task") { with(csrf()) }.andExpect { status { is2xxSuccessful() } }
		val purged = fetch(id)
		purged.isNullOrAbsent("nextTaskId") shouldBe true
		purged.get("stalled").asBoolean() shouldBe true
	}

	@Test
	fun `create with a status other than ACTIVE is never stalled, COMPLETED records the completion time`() {
		val done = post(PROJECTS, """{"title":"Kitchen done","status":"COMPLETED"}""").andExpect { status { isCreated() } }.json()
		done.get("stalled").asBoolean() shouldBe false
		done.get("completedAt").asString() shouldBe "2026-03-01T08:00:00Z"
		val someday = post(PROJECTS, """{"title":"Write a book","status":"SOMEDAY"}""").andExpect { status { isCreated() } }.json()
		someday.get("stalled").asBoolean() shouldBe false
		someday.isNullOrAbsent("completedAt") shouldBe true
	}

	@Test
	fun `title, description, area title and context name are accepted up to their maximum length`() {
		post(PROJECTS, """{"title":"${"x".repeat(500)}"}""").andExpect { status { isCreated() } }
		post(PROJECTS, """{"title":"Long notes","descriptionMd":"${"x".repeat(100_000)}"}""").andExpect { status { isCreated() } }
		post(PROJECTS, """{"title":"Too long notes","descriptionMd":"${"x".repeat(100_001)}"}""").isProblem(400, "descriptionMd")
		post(AREAS, """{"title":"${"a".repeat(200)}"}""").andExpect { status { isCreated() } }
		post(CONTEXTS, """{"name":"@${"c".repeat(99)}"}""").andExpect { status { isCreated() } }
		post(CONTEXTS, """{"name":"@${"d".repeat(100)}"}""").isProblem(400, "name")
		// Surrounding blanks are trimmed before the length check.
		post(CONTEXTS, """{"name":"  @${"e".repeat(99)}  "}""").andExpect { status { isCreated() } }
	}

	@Test
	fun `GET returns a context with its ETag, and PATCH changes only the position when only it is sent`() {
		val home = post(CONTEXTS, """{"name":"@home","color":"#abc","position":7}""").andExpect { status { isCreated() } }.json()
		home.get("position").asInt() shouldBe 7
		mockMvc.get("$CONTEXTS/${home.id()}").andExpect {
			status { isOk() }
			header { string(HttpHeaders.ETAG, "\"0\"") }
			jsonPath("$.name") { value("@home") }
			jsonPath("$.color") { value("#abc") }
		}
		patch("$CONTEXTS/${home.id()}", """{"position":2}""", home.etag()).andExpect {
			status { isOk() }
			jsonPath("$.position") { value(2) }
			jsonPath("$.name") { value("@home") }
			jsonPath("$.color") { value("#abc") }
		}
		val area = post(AREAS, """{"title":"Home"}""").andExpect { status { isCreated() } }.json()
		patch("$AREAS/${area.id()}", """{"position":4}""", area.etag()).andExpect {
			status { isOk() }
			jsonPath("$.position") { value(4) }
			jsonPath("$.title") { value("Home") }
		}
	}

	@Test
	fun `a context create that loses the race for its name is a 409, not a 500`() {
		val inserted = CountDownLatch(1)
		val pool = Executors.newSingleThreadExecutor()
		val competitor =
			pool.submit {
				// The competing create: inserted but not yet committed, so the POST passes the name check and blocks on the index.
				dataSource.connection.use { connection ->
					connection.autoCommit = false
					connection
						.prepareStatement("insert into context (id, created_at, updated_at, version, name) values (?, now(), now(), 0, ?)")
						.use {
							it.setObject(1, UuidV7.generate())
							it.setString(2, "@phone")
							it.executeUpdate()
						}
					inserted.countDown()
					val deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos()
					while (waitingInserts() == 0) {
						check(System.nanoTime() < deadline) { "the POST never waited for the competing insert" }
						Thread.sleep(20)
					}
					connection.commit()
				}
			}
		try {
			inserted.await(30, TimeUnit.SECONDS) shouldBe true
			post(CONTEXTS, """{"name":"@Phone"}""").isProblem(409)
			competitor.get(30, TimeUnit.SECONDS)
		} finally {
			pool.shutdownNow()
		}
		jdbc.queryForObject("select count(*) from context", Int::class.java) shouldBe 1
	}

	private fun waitingInserts(): Int =
		checkNotNull(
			jdbc.queryForObject(
				"select count(*) from pg_stat_activity where wait_event_type = 'Lock' and query ilike '%insert into context %'",
				Int::class.java,
			),
		)

	// --- cursor pagination

	@Test
	fun `list limits outside 1 to 200 and malformed cursors are rejected`() {
		newProject()
		mockMvc.get(PROJECTS) { param("limit", "0") }.isProblem(400, "limit")
		mockMvc.get(PROJECTS) { param("limit", "201") }.isProblem(400, "limit")
		page("limit" to "200").get("items").size() shouldBe 1
		page("limit" to "1").get("items").size() shouldBe 1
		// Valid base64url, but no "<id>|<title>" inside.
		listOf("not-a-uuid|Renovate kitchen", "|Renovate kitchen", "Renovate kitchen").forEach { position ->
			mockMvc.get(PROJECTS) { param("cursor", CursorRequest.encodeCursor(position)) }.isProblem(400, "cursor")
		}
		mockMvc.get(PROJECTS) { param("cursor", "%%%") }.isProblem(400, "cursor")
	}

	@Test
	fun `an empty cursor is the first page, and a full last page has no next cursor`() {
		val ids = listOf("A", "B", "C", "D").map { newProject("""{"title":"$it"}""") }
		page("cursor" to "").get("items").values().map { it.id() } shouldContainExactly ids
		val first = page("limit" to "2")
		first.get("items").values().map { it.id() } shouldContainExactly ids.take(2)
		val second = page("limit" to "2", "cursor" to checkNotNull(first.nextCursor()))
		second.get("items").values().map { it.id() } shouldContainExactly ids.drop(2)
		second.nextCursor() shouldBe null
		page("limit" to "4").nextCursor() shouldBe null
		listIds().size shouldBe 4
	}

	@Test
	fun `paging through a filtered list returns exactly the filtered projects`() {
		val stalled = (1..5).map { newProject("""{"title":"Stalled $it"}""") }
		(1..3).forEach { n -> newProject("""{"title":"Moving $n"}""").also { nextStep(it, newTask(it)).andExpect { status { isOk() } } } }
		allPages(2, "stalled" to "true") shouldContainExactly stalled
		allPages(1, "stalled" to "false").size shouldBe 3
	}

	@Test
	fun `a cursor stays valid when the project it points to is deleted or renamed`() {
		val ids = listOf("A", "B", "C").map { newProject("""{"title":"$it"}""") }
		val first = page("limit" to "1")
		first.get("items").values().map { it.id() } shouldContainExactly listOf(ids[0])
		deleteWithAnyVersion("$PROJECTS/${ids[0]}")
		page("limit" to "5", "cursor" to checkNotNull(first.nextCursor())).get("items").values().map { it.id() } shouldContainExactly
			ids.drop(1)
	}

	// --- task order

	@Test
	fun `PUT order with an empty list keeps the order and changes nothing`(events: ApplicationEvents) {
		val id = newProject()
		val a = newTask(id, "a")
		val b = newTask(id, "b")
		val before = events.changes("TASK").size
		order(id)
			.andExpect { status { isOk() } }
			.json()
			.get("items")
			.values()
			.map { it.id() } shouldContainExactly listOf(a, b)
		events.changes("TASK").size shouldBe before
		fetchTask(a).get("version").asLong() shouldBe 0
		fetch(id).get("version").asLong() shouldBe 0
	}

	@Test
	fun `PUT order skips and rejects tasks in the Trash, and 404s for a project in the Trash`() {
		val id = newProject()
		val a = newTask(id, "a")
		val trashed = newTask(id, "b").also { deleteWithAnyVersion("$TASKS/$it") }
		val c = newTask(id, "c")
		order(id, trashed).isProblem(400, "taskIds")
		order(id, c)
			.andExpect { status { isOk() } }
			.json()
			.get("items")
			.values()
			.map { it.id() } shouldContainExactly listOf(c, a)
		// Ordering tasks does not change the project.
		fetch(id).get("version").asLong() shouldBe 0
		deleteWithAnyVersion("$PROJECTS/$id")
		order(id, a).isProblem(404)
		nextStep(id, a).isProblem(404)
	}

	// --- deletes that reach into the Trash

	@Test
	fun `deleting a context also strips it from done and trashed tasks, announcing only tasks outside the Trash`(events: ApplicationEvents) {
		val home = post(CONTEXTS, """{"name":"@home"}""").andExpect { status { isCreated() } }.json()
		val done = newTask(null, "Fix the tap", ""","contextIds":["${home.id()}"]""").also { complete(it) }
		val trashed = newTask(null, "Water the plants", ""","contextIds":["${home.id()}"]""")
		deleteWithAnyVersion("$TASKS/$trashed")
		val trashedVersion = checkNotNull(jdbc.queryForObject("select version from task where id = ?::uuid", Long::class.java, trashed))
		val before = events.changes("TASK").size

		mockMvc
			.delete("$CONTEXTS/${home.id()}") {
				with(csrf())
				header(HttpHeaders.IF_MATCH, home.etag())
			}.andExpect { status { isNoContent() } }

		jdbc.queryForObject("select count(*) from task_context", Int::class.java) shouldBe 0
		events.changes("TASK").drop(before) shouldContainExactly listOf(ChangeOp.UPDATED to done)
		jdbc.queryForObject("select version from task where id = ?::uuid", Long::class.java, trashed) shouldBe trashedVersion + 1
		restore("TASK", trashed)
		fetchTask(trashed)
			.get("contextIds")
			.values()
			.toList()
			.shouldBeEmpty()
		fetchTask(done)
			.get("contextIds")
			.values()
			.toList()
			.shouldBeEmpty()
	}

	@Test
	fun `deleting an area also takes trashed projects out of it, announcing only projects outside the Trash`(events: ApplicationEvents) {
		val area = post(AREAS, """{"title":"Home"}""").andExpect { status { isCreated() } }.json()
		val active = newProject("""{"title":"Renovate kitchen","areaId":"${area.id()}"}""")
		val trashed = newProject("""{"title":"Garden shed","areaId":"${area.id()}"}""")
		deleteWithAnyVersion("$PROJECTS/$trashed")
		val before = events.changes("PROJECT").size

		mockMvc
			.delete("$AREAS/${area.id()}") {
				with(csrf())
				header(HttpHeaders.IF_MATCH, area.etag())
			}.andExpect { status { isNoContent() } }

		events.changes("PROJECT").drop(before) shouldContainExactly listOf(ChangeOp.UPDATED to active)
		restore("PROJECT", trashed)
		fetch(trashed).isNullOrAbsent("areaId") shouldBe true
		fetch(active).isNullOrAbsent("areaId") shouldBe true
		listIds("areaId" to area.id()).shouldBeEmpty()
	}

	@Test
	fun `PATCH areaId null takes a project out of its area`() {
		val area = post(AREAS, """{"title":"Home"}""").andExpect { status { isCreated() } }.json().id()
		val id = newProject("""{"title":"Renovate kitchen","areaId":"$area"}""")
		patch("$PROJECTS/$id", """{"areaId":null}""", "\"0\"").andExpect {
			status { isOk() }
			jsonPath("$.areaId") { doesNotExist() }
		}
		listIds("areaId" to area).shouldBeEmpty()
	}

	// --- optimistic locking

	@Test
	fun `next-step and task order bump versions, so stale ETags are refused afterwards`() {
		val id = newProject()
		val task = newTask(id)
		nextStep(id, task, "*").andExpect {
			status { isOk() }
			header { string(HttpHeaders.ETAG, "\"1\"") }
		}
		patch("$PROJECTS/$id", """{"title":"Renovate bathroom"}""", "\"0\"").isProblem(412)
		nextStep(id, null, "\"0\"").isProblem(412)
		mockMvc
			.delete("$PROJECTS/$id") {
				with(csrf())
				header(HttpHeaders.IF_MATCH, "\"0\"")
			}.isProblem(412)
		// A weak ETag never matches (RFC 9110 strong comparison).
		patch("$PROJECTS/$id", """{"title":"Renovate bathroom"}""", "W/\"1\"").isProblem(412)
		patch("$PROJECTS/$id", """{"title":"Renovate bathroom"}""", "\"0\", \"1\"").andExpect { status { isOk() } }
		fetch(id).get("title").asString() shouldBe "Renovate bathroom"
	}

	@Test
	fun `a project changed concurrently after the If-Match check is not overwritten`() {
		val id = newProject()
		assertThrows<OptimisticLockingFailureException> {
			projectService.update(UUID.fromString(id), ProjectChanges(title = Change("Renovate bathroom"))) { version ->
				version shouldBe 0
				concurrentRename(id, "Renovate attic")
			}
		}
		val current = fetch(id)
		current.get("title").asString() shouldBe "Renovate attic"
		current.get("version").asLong() shouldBe 1
		searchDocument(id) shouldBe ("Renovate kitchen" to "")
	}

	@Test
	fun `a next step set concurrently with another change is not lost silently`() {
		val id = newProject()
		val task = newTask(id)
		assertThrows<OptimisticLockingFailureException> {
			projectService.setNextStep(UUID.fromString(id), UUID.fromString(task)) { concurrentRename(id, "Renovate attic") }
		}
		val current = fetch(id)
		current.isNullOrAbsent("nextTaskId") shouldBe true
		current.get("title").asString() shouldBe "Renovate attic"
	}

	@Test
	fun `list uses the stalled rule with the next steps of a whole page loaded at once`() {
		val ids = (1..4).map { newProject("""{"title":"Project $it"}""") }
		ids.forEachIndexed { index, id -> if (index % 2 == 0) nextStep(id, newTask(id)).andExpect { status { isOk() } } }
		val flags = projectService.list(ProjectFilter(), CursorRequest(null, 10)).items.map { it.stalled }
		flags shouldContainExactly listOf(false, true, false, true)
	}

	// --- search through the API

	private fun searchProjects(q: String): List<JsonNode> =
		mockMvc
			.get("/api/v1/search") {
				param("q", q)
				param("types", "PROJECT")
			}.andExpect { status { isOk() } }
			.json()
			.get("groups")
			.values()
			.flatMap { it.get("items").values() }

	@Test
	fun `the search API finds a project by title and description, follows renames and loses it in the Trash`() {
		val id = newProject("""{"title":"Renovate kitchen","descriptionMd":"Order terracotta tiles","status":"ON_HOLD"}""")
		with(searchProjects("kitchen").single()) {
			id() shouldBe id
			get("type").asString() shouldBe "PROJECT"
			get("title").asString() shouldBe "Renovate kitchen"
			get("subtitle").asString() shouldBe "On hold"
			get("deepLink").asString() shouldBe "/projects/$id"
		}
		searchProjects("terracotta").map { it.id() } shouldContainExactly listOf(id)

		patch("$PROJECTS/$id", """{"title":"Renovate bathroom","descriptionMd":null}""", "\"0\"").andExpect { status { isOk() } }
		searchProjects("kitchen").shouldBeEmpty()
		searchProjects("terracotta").shouldBeEmpty()
		searchProjects("bathroom").map { it.id() } shouldContainExactly listOf(id)

		deleteWithAnyVersion("$PROJECTS/$id")
		searchProjects("bathroom").shouldBeEmpty()
		restore("PROJECT", id)
		searchProjects("bathroom").map { it.id() } shouldContainExactly listOf(id)
	}

	@Test
	fun `next-step treats a blank If-Match like an absent one`() {
		val id = newProject()
		val task = newTask(id)
		nextStep(id, task, "").andExpect {
			status { isOk() }
			jsonPath("$.nextTaskId") { value(task) }
		}
	}

	private companion object {
		const val PROJECTS = "/api/v1/projects"
		const val TASKS = "/api/v1/tasks"
		const val AREAS = "/api/v1/areas"
		const val CONTEXTS = "/api/v1/contexts"
	}
}
