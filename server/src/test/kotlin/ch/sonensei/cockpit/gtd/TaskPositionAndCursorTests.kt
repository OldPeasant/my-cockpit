package ch.sonensei.cockpit.gtd

import ch.sonensei.cockpit.core.auth.WithCockpitUser
import ch.sonensei.cockpit.core.persistence.MutableClock
import ch.sonensei.cockpit.core.persistence.MutableClockTestConfiguration
import ch.sonensei.cockpit.core.persistence.UuidV7
import ch.sonensei.cockpit.gtd.domain.Context
import ch.sonensei.cockpit.gtd.domain.Project
import ch.sonensei.cockpit.gtd.infra.ContextRepository
import ch.sonensei.cockpit.gtd.infra.ProjectRepository
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.ints.shouldBeGreaterThan
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
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * Further Tasks API cases (gtd-tasks-api, test-writer): positions after a project or list change (CR-5), the
 * idempotent create race against a create that ends in the Trash (CR-6), cursor pages over equal sort keys, a blank
 * optional If-Match and duplicate context ids.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MutableClockTestConfiguration::class)
@WithCockpitUser
class TaskPositionAndCursorTests(
	@Autowired private val mockMvc: MockMvc,
	@Autowired private val jsonMapper: JsonMapper,
	@Autowired private val projects: ProjectRepository,
	@Autowired private val contexts: ContextRepository,
	@Autowired private val clock: MutableClock,
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
		clock.set(MutableClockTestConfiguration.START)
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

	private fun JsonNode.id(): String = get("id").asString()

	private fun JsonNode.etag(): String = "\"${get("version").asLong()}\""

	private fun moved(
		id: String,
		body: String,
	): JsonNode =
		mockMvc
			.post("$BASE/$id/move") {
				with(csrf())
				contentType = MediaType.APPLICATION_JSON
				content = body
			}.andExpect { status { isOk() } }
			.andReturn()
			.response.contentAsString
			.let(jsonMapper::readTree)

	private fun page(vararg params: Pair<String, String>): JsonNode =
		mockMvc
			.get(BASE) { params.forEach { (name, value) -> param(name, value) } }
			.andExpect { status { isOk() } }
			.andReturn()
			.response.contentAsString
			.let(jsonMapper::readTree)

	private fun listIds(vararg params: Pair<String, String>): List<String> = page(*params).get("items").values().map { it.id() }

	/** Every id of the listing, fetched one item per page by following `nextCursor`. */
	private fun pagedIds(vararg params: Pair<String, String>): List<String> {
		val ids = mutableListOf<String>()
		var cursor: String? = null
		do {
			val next = page(*params, "limit" to "1", *listOfNotNull(cursor?.let { "cursor" to it }).toTypedArray())
			ids += next.get("items").values().map { it.id() }
			cursor = next.get("nextCursor")?.takeUnless { it.isNull }?.asString()
			check(ids.size <= 20) { "paging does not end" }
		} while (cursor != null)
		return ids
	}

	private fun ResultActionsDsl.isProblem(status: Int) =
		andExpect {
			status { isEqualTo(status) }
			content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
		}

	private fun count(
		sql: String,
		vararg args: Any,
	): Int? = jdbc.queryForObject(sql, Int::class.java, *args)

	// --- positions (CR-5)

	@Test
	fun `move into another project puts the task at the end of that project, naming its own project keeps its position`() {
		val garden = projects.save(Project("Plan the garden"))
		val kitchen = projects.save(Project("Renovate kitchen"))
		val gardenSteps = listOf("Buy seeds", "Dig beds").map { create("""{"title":"$it","list":"NEXT","projectId":"${garden.id}"}""") }
		val kitchenSteps =
			listOf("Measure", "Order tiles", "Book tiler").map { create("""{"title":"$it","list":"NEXT","projectId":"${kitchen.id}"}""") }

		val movedOver = moved(gardenSteps[0].id(), """{"list":"NEXT","projectId":"${kitchen.id}"}""")
		movedOver.get("projectId").asString() shouldBe kitchen.id.toString()
		movedOver.get("position").asInt() shouldBeGreaterThan kitchenSteps.maxOf { it.get("position").asInt() }
		listIds("projectId" to kitchen.id.toString()) shouldContainExactly kitchenSteps.map { it.id() } + gardenSteps[0].id()

		val stays = moved(gardenSteps[1].id(), """{"list":"SOMEDAY","projectId":"${garden.id}"}""")
		stays.get("position").asInt() shouldBe gardenSteps[1].get("position").asInt()
	}

	@Test
	fun `a task without a project moving to another list goes after the loose tasks there, project positions do not count`() {
		val project = projects.save(Project("Plan the garden"))
		repeat(3) { create("""{"title":"Project step $it","list":"NEXT","projectId":"${project.id}"}""") }
		create("""{"title":"Call the bank","list":"NEXT"}""").get("position").asInt() shouldBe 0
		val inbox = create("""{"title":"Fix the bike"}""")

		moved(inbox.id(), """{"list":"NEXT"}""").get("position").asInt() shouldBe 1
	}

	@Test
	fun `PATCH projectId null puts the task at the end of the loose tasks of its list`() {
		val project = projects.save(Project("Plan the garden"))
		val first = create("""{"title":"Buy seeds","list":"NEXT","projectId":"${project.id}"}""")
		val second = create("""{"title":"Dig beds","list":"NEXT","projectId":"${project.id}"}""")
		val third = create("""{"title":"Sow","list":"NEXT","projectId":"${project.id}"}""")
		val bank = create("""{"title":"Call the bank","list":"NEXT","position":5}""")

		val loose =
			mockMvc
				.patch("$BASE/${second.id()}") {
					with(csrf())
					contentType = MediaType.APPLICATION_JSON
					content = """{"projectId":null}"""
					header(HttpHeaders.IF_MATCH, second.etag())
				}.andExpect {
					status { isOk() }
					jsonPath("$.projectId") { doesNotExist() }
				}.andReturn()
				.response.contentAsString
				.let(jsonMapper::readTree)
		loose.get("position").asInt() shouldBeGreaterThan bank.get("position").asInt()
		listIds("projectId" to project.id.toString()) shouldContainExactly listOf(first.id(), third.id())
	}

	// --- idempotent create race (CR-6)

	@Test
	fun `POST that loses the insert race against a create that is in the Trash is 409`() {
		val id = UuidV7.generate()
		val inserted = CountDownLatch(1)
		val pool = Executors.newSingleThreadExecutor()
		val competitor =
			pool.submit {
				dataSource.connection.use { connection ->
					connection.autoCommit = false
					connection
						.prepareStatement(
							"insert into task (id, created_at, updated_at, deleted_at, version, title, list) " +
								"values (?, now(), now(), now(), 0, ?, 'INBOX')",
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

		post("""{"id":"$id","title":"Offline capture"}""").isProblem(409)
		competitor.get(30, TimeUnit.SECONDS)
		pool.shutdown()
		count("select count(*) from task where id = ? and deleted_at is not null", id) shouldBe 1
	}

	// --- cursor pages over equal sort keys

	@Test
	fun `open tasks with the same position are paged without gaps or repeats`() {
		val ids = (1..4).map { create("""{"title":"Same position $it","position":0}""").id() }

		val paged = pagedIds()
		paged shouldContainExactly listIds()
		paged shouldContainExactlyInAnyOrder ids
	}

	@Test
	fun `done tasks completed at the same instant are paged without gaps or repeats, also per project`() {
		val project = projects.save(Project("Plan the garden"))
		val ids = (1..3).map { create("""{"title":"Garden $it","projectId":"${project.id}"}""").id() }
		val loose = create("""{"title":"Call the bank"}""").id()
		(ids + loose).forEach {
			mockMvc.post("$BASE/$it/complete") { with(csrf()) }.andExpect { status { isOk() } }
		}

		val paged = pagedIds("completed" to "true")
		paged shouldContainExactly listIds("completed" to "true")
		paged shouldContainExactlyInAnyOrder ids + loose
		pagedIds("completed" to "true", "projectId" to project.id.toString()) shouldContainExactlyInAnyOrder ids
	}

	// --- headers and contexts

	@Test
	fun `a blank If-Match on a workflow action counts as not sent`() {
		val id = create("""{"title":"Renew passport"}""").id()
		mockMvc
			.post("$BASE/$id/complete") {
				with(csrf())
				header(HttpHeaders.IF_MATCH, "")
			}.andExpect { status { isOk() } }
	}

	@Test
	fun `contexts sent twice are stored once`() {
		val home = contexts.save(Context("@home"))
		val task = create("""{"title":"Fix the shelf","contextIds":["${home.id}","${home.id}"]}""")
		task.get("contextIds").values().map { it.asString() } shouldBe listOf(home.id.toString())
		count("select count(*) from task_context where task_id = ?::uuid", task.id()) shouldBe 1
	}

	private companion object {
		const val BASE = "/api/v1/tasks"
	}
}
