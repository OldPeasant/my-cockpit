package ch.sonensei.cockpit.gtd

import ch.sonensei.cockpit.core.auth.WithCockpitUser
import ch.sonensei.cockpit.core.events.domain.ChangeEvent
import ch.sonensei.cockpit.core.events.domain.ChangeOp
import ch.sonensei.cockpit.core.persistence.MutableClock
import ch.sonensei.cockpit.core.persistence.MutableClockTestConfiguration
import ch.sonensei.cockpit.core.settings.domain.SettingsService
import ch.sonensei.cockpit.gtd.domain.Context
import ch.sonensei.cockpit.gtd.domain.Project
import ch.sonensei.cockpit.gtd.domain.TicklerJob
import ch.sonensei.cockpit.gtd.infra.ContextRepository
import ch.sonensei.cockpit.gtd.infra.ProjectRepository
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
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
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.util.UUID

/**
 * Edge cases of the tickler and scheduled tasks (gtd-tickler, SPEC.md §5.1): the tickler filter combined with the other
 * list filters, the Logbook and paging; project tasks keep their place; the single-task read releases too; a release is
 * idempotent and changes the version.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MutableClockTestConfiguration::class)
@RecordApplicationEvents
@WithCockpitUser
class TicklerEdgeCaseTests(
	@Autowired private val mockMvc: MockMvc,
	@Autowired private val jsonMapper: JsonMapper,
	@Autowired private val clock: MutableClock,
	@Autowired private val settings: SettingsService,
	@Autowired private val tickler: TicklerJob,
	@Autowired private val projects: ProjectRepository,
	@Autowired private val contexts: ContextRepository,
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
		clock.set(MutableClockTestConfiguration.START) // Sunday 2026-03-01, 09:00 in Europe/Zurich
	}

	@AfterEach
	fun resetTimeZone() {
		settings.update(mapOf("timeZone" to null))
	}

	// --- helpers

	private fun ResultActionsDsl.json(): JsonNode = andReturn().response.contentAsString.let(jsonMapper::readTree)

	private fun create(body: String): String =
		mockMvc
			.post(BASE) {
				with(csrf())
				contentType = MediaType.APPLICATION_JSON
				content = body
			}.andExpect { status { isCreated() } }
			.json()
			.get("id")
			.asString()

	private fun move(
		id: String,
		body: String,
	) = mockMvc
		.post("$BASE/$id/move") {
			with(csrf())
			contentType = MediaType.APPLICATION_JSON
			content = body
		}.andExpect { status { isOk() } }

	private fun page(query: String = ""): JsonNode =
		mockMvc
			.get("$BASE$query")
			.andExpect { status { isOk() } }
			.json()

	private fun ids(query: String = ""): List<String> = page(query).get("items").values().map { it.get("id").asString() }

	private fun task(id: String): JsonNode = mockMvc.get("$BASE/$id").andExpect { status { isOk() } }.json()

	/** The stored row, read from the database without the on-read check. */
	private fun stored(id: String): Map<String, Any?> =
		jdbc.queryForMap(
			"select list, position, project_id, tickler_date, scheduled_date, completed_at, version from task where id = ?",
			UUID.fromString(id),
		)

	private fun JsonNode.isAbsent(field: String): Boolean = get(field)?.isNull ?: true

	private fun taskEvents(events: ApplicationEvents): List<ChangeEvent> =
		events.stream(ChangeEvent::class.java).toList().filter { it.type == "TASK" }

	// --- the list filter combined with the other filters

	@Test
	fun `a task in a tickler is hidden from the context, due, estimate and energy filters until its date`() {
		val phone = contexts.save(Context("@phone"))
		val passport =
			create(
				"""{"title":"Renew passport","list":"NEXT","contextIds":["${phone.id}"],"dueDate":"2026-03-01",""" +
					""""timeEstimateMin":10,"energy":"LOW"}""",
			)
		move(passport, """{"list":"NEXT","ticklerDate":"2026-03-03"}""")
		val queries = listOf("?context=${phone.id}", "?due=today", "?due=week", "?maxMinutes=15", "?energy=LOW")

		queries.forEach { ids(it).shouldBeEmpty() }

		clock.advance(Duration.ofDays(2))

		// Released to the Inbox with its context, estimate and energy; the due date is now in the past.
		ids("?context=${phone.id}") shouldContainExactly listOf(passport)
		ids("?list=INBOX&context=${phone.id}&maxMinutes=15&energy=LOW") shouldContainExactly listOf(passport)
		ids("?due=overdue") shouldContainExactly listOf(passport)
		ids("?due=today").shouldBeEmpty()
	}

	@Test
	fun `a task in a tickler is skipped by paging without ending the page early`() {
		val first = create("""{"title":"Sort the mail"}""")
		val hidden = create("""{"title":"Renew passport"}""")
		val last = create("""{"title":"Buy stamps"}""")
		move(hidden, """{"list":"INBOX","ticklerDate":"2026-03-05"}""")

		val firstPage = page("?limit=1")
		firstPage.get("items").values().map { it.get("id").asString() } shouldContainExactly listOf(first)
		val cursor = firstPage.get("nextCursor").asString()
		val secondPage = page("?limit=1&cursor=$cursor")

		secondPage.get("items").values().map { it.get("id").asString() } shouldContainExactly listOf(last)
		secondPage.isAbsent("nextCursor") shouldBe true
	}

	@Test
	fun `a done task with a tickler date still to come is hidden from the Logbook, and stays done afterwards`() {
		val passport = create("""{"title":"Renew passport","list":"NEXT"}""")
		val stamps = create("""{"title":"Buy stamps","list":"NEXT"}""")
		move(passport, """{"list":"NEXT","ticklerDate":"2026-03-03"}""")
		listOf(passport, stamps).forEach {
			mockMvc.post("$BASE/$it/complete") { with(csrf()) }.andExpect { status { isOk() } }
		}

		ids("?completed=true") shouldContainExactly listOf(stamps)

		clock.advance(Duration.ofDays(2))
		tickler.run() shouldBe 0

		ids("?completed=true") shouldContainExactlyInAnyOrder listOf(passport, stamps)
		val row = stored(passport)
		row["list"] shouldBe "NEXT"
		row["completed_at"] shouldNotBe null
	}

	@Test
	fun `a task whose tickler date is today is listed (the date is inclusive)`() {
		val passport = create("""{"title":"Renew passport","list":"NEXT"}""")
		mockMvc.post("$BASE/$passport/complete") { with(csrf()) }.andExpect { status { isOk() } }
		// A done task is never moved, so its tickler date stays: today it no longer hides the task.
		jdbc.update("update task set tickler_date = date '2026-03-01' where id = ?", UUID.fromString(passport))

		ids("?completed=true") shouldContainExactly listOf(passport)

		jdbc.update("update task set tickler_date = date '2026-03-02' where id = ?", UUID.fromString(passport))
		ids("?completed=true").shouldBeEmpty()
	}

	// --- positions

	@Test
	fun `a released project task keeps its project and its place within the project`() {
		val project = projects.save(Project("Renovate kitchen"))
		val tiles = create("""{"title":"Choose tiles","list":"NEXT","projectId":"${project.id}"}""")
		val tiler = create("""{"title":"Call the tiler","list":"SOMEDAY","projectId":"${project.id}"}""")
		val paint = create("""{"title":"Buy paint","list":"NEXT","projectId":"${project.id}"}""")
		move(tiler, """{"list":"SOMEDAY","ticklerDate":"2026-03-02"}""")
		val position = stored(tiler)["position"]

		ids("?projectId=${project.id}") shouldContainExactly listOf(tiles, paint)

		clock.advance(Duration.ofDays(1))
		tickler.run() shouldBe 1

		val row = stored(tiler)
		row["list"] shouldBe "INBOX"
		row["project_id"] shouldBe project.id
		row["position"] shouldBe position
		ids("?projectId=${project.id}") shouldContainExactly listOf(tiles, tiler, paint)
	}

	@Test
	fun `a released scheduled project task keeps its place within the project`() {
		val project = projects.save(Project("Renovate kitchen"))
		val tiles = create("""{"title":"Choose tiles","list":"NEXT","projectId":"${project.id}"}""")
		val tiler =
			create("""{"title":"Call the tiler","list":"SCHEDULED","scheduledDate":"2026-03-02","projectId":"${project.id}"}""")
		val position = stored(tiler)["position"]

		clock.advance(Duration.ofDays(1))
		tickler.run() shouldBe 1

		val row = stored(tiler)
		row["list"] shouldBe "NEXT"
		row["scheduled_date"] shouldBe null
		row["position"] shouldBe position
		ids("?list=NEXT&projectId=${project.id}") shouldContainExactly listOf(tiles, tiler)
	}

	@Test
	fun `ticklers due on the same run go to the Inbox in tickler date order, after what is already there`() {
		val mail = create("""{"title":"Sort the mail"}""")
		val later = create("""{"title":"Renew passport","list":"NEXT"}""")
		val sooner = create("""{"title":"Book the dentist","list":"NEXT"}""")
		move(later, """{"list":"NEXT","ticklerDate":"2026-03-04"}""")
		move(sooner, """{"list":"NEXT","ticklerDate":"2026-03-02"}""")

		clock.advance(Duration.ofDays(3))
		tickler.run() shouldBe 2

		ids("?list=INBOX") shouldContainExactly listOf(mail, sooner, later)
	}

	@Test
	fun `a tickler put away from the Inbox comes back to its own place in the Inbox`() {
		val mail = create("""{"title":"Sort the mail"}""")
		val passport = create("""{"title":"Renew passport"}""")
		val stamps = create("""{"title":"Buy stamps"}""")
		move(passport, """{"list":"INBOX","ticklerDate":"2026-03-02"}""")
		val position = stored(passport)["position"]

		ids("?list=INBOX") shouldContainExactly listOf(mail, stamps)

		clock.advance(Duration.ofDays(1))
		tickler.run() shouldBe 1

		stored(passport)["position"] shouldBe position
		stored(passport)["tickler_date"] shouldBe null
		ids("?list=INBOX") shouldContainExactly listOf(mail, passport, stamps)
	}

	@Test
	fun `a scheduled task released to Next goes after the existing next actions`() {
		val existing = create("""{"title":"Buy stamps","list":"NEXT"}""")
		val dentist = create("""{"title":"Book the dentist","list":"SCHEDULED","scheduledDate":"2026-03-02"}""")

		clock.advance(Duration.ofDays(1))

		ids("?list=NEXT") shouldContainExactly listOf(existing, dentist)
		ids("?list=SCHEDULED").shouldBeEmpty()
	}

	// --- on-read check, events, versions

	@Test
	fun `reading a single task releases it when the job has not run`(events: ApplicationEvents) {
		val passport = create("""{"title":"Renew passport","list":"NEXT"}""")
		move(passport, """{"list":"NEXT","ticklerDate":"2026-03-03"}""")
		clock.advance(Duration.ofDays(2))
		events.clear()

		val read = task(passport)

		read.get("list").asString() shouldBe "INBOX"
		read.isAbsent("ticklerDate") shouldBe true
		stored(passport)["list"] shouldBe "INBOX"
		taskEvents(events).map { it.id.toString() to it.op } shouldContainExactly listOf(passport to ChangeOp.UPDATED)
	}

	@Test
	fun `reading a single task that is not due leaves it in the tickler`() {
		val passport = create("""{"title":"Renew passport","list":"NEXT"}""")
		move(passport, """{"list":"NEXT","ticklerDate":"2026-03-03"}""")
		clock.advance(Duration.ofDays(1))

		task(passport).get("ticklerDate").asString() shouldBe "2026-03-03"
		stored(passport)["list"] shouldBe "NEXT"
	}

	@Test
	fun `a second run moves nothing and announces nothing`(events: ApplicationEvents) {
		val passport = create("""{"title":"Renew passport","list":"NEXT"}""")
		move(passport, """{"list":"NEXT","ticklerDate":"2026-03-02"}""")
		create("""{"title":"Book the dentist","list":"SCHEDULED","scheduledDate":"2026-03-02"}""")
		clock.advance(Duration.ofDays(1))

		tickler.run() shouldBe 2
		events.clear()

		tickler.run() shouldBe 0
		ids()
		taskEvents(events).shouldBeEmpty()
	}

	@Test
	fun `a release changes the version, so an edit based on the tickler state is rejected`() {
		val passport = create("""{"title":"Renew passport","list":"NEXT"}""")
		val before = move(passport, """{"list":"NEXT","ticklerDate":"2026-03-02"}""").json().get("version").asLong()
		clock.advance(Duration.ofDays(1))

		val after = task(passport).get("version").asLong()

		after shouldNotBe before
		mockMvc
			.patch("$BASE/$passport") {
				with(csrf())
				contentType = MediaType.APPLICATION_JSON
				header(HttpHeaders.IF_MATCH, "\"$before\"")
				content = """{"title":"Renew passport now"}"""
			}.andExpect { status { isPreconditionFailed() } }
	}

	@Test
	fun `a task in the tickler reappears in every list query on its date without the job`() {
		val passport = create("""{"title":"Renew passport","list":"WAITING","waitingForText":"Passport office"}""")
		move(passport, """{"list":"WAITING","waitingForText":"Passport office","ticklerDate":"2026-03-03"}""")

		ids().shouldBeEmpty()
		ids("?list=WAITING").shouldBeEmpty()

		clock.advance(Duration.ofDays(2))

		ids() shouldContainExactly listOf(passport)
		ids("?list=WAITING").shouldBeEmpty()
		ids("?list=INBOX") shouldContainExactly listOf(passport)
	}

	companion object {
		const val BASE = "/api/v1/tasks"
	}
}
