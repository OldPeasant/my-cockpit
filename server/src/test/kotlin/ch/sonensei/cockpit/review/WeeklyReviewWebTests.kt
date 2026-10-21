package ch.sonensei.cockpit.review

import ch.sonensei.cockpit.calendar.api.CalendarApi
import ch.sonensei.cockpit.core.auth.WithCockpitUser
import ch.sonensei.cockpit.core.persistence.MutableClock
import ch.sonensei.cockpit.core.persistence.MutableClockTestConfiguration
import ch.sonensei.cockpit.gtd.domain.Change
import ch.sonensei.cockpit.gtd.domain.ProjectChanges
import ch.sonensei.cockpit.gtd.domain.ProjectService
import ch.sonensei.cockpit.gtd.domain.ProjectStatus
import ch.sonensei.cockpit.gtd.domain.TaskChanges
import ch.sonensei.cockpit.gtd.domain.TaskList
import ch.sonensei.cockpit.gtd.domain.TaskService
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
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
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * `/api/v1/weekly-reviews` over HTTP (SPEC.md §8.6), against Postgres, with the test clock (Europe/Zurich): starting,
 * resuming, moving and completing a review, and the data of its steps.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MutableClockTestConfiguration::class)
@WithCockpitUser
class WeeklyReviewWebTests(
	@Autowired private val mockMvc: MockMvc,
	@Autowired private val jsonMapper: JsonMapper,
	@Autowired private val jdbc: JdbcTemplate,
	@Autowired private val clock: MutableClock,
	@Autowired private val tasks: TaskService,
	@Autowired private val projects: ProjectService,
) : PostgresTestBase() {
	private val calendar = CalendarApi(mockMvc, jsonMapper)

	@BeforeEach
	@AfterEach
	fun reset() {
		jdbc.execute("delete from weekly_review")
		jdbc.execute("delete from task_context")
		jdbc.execute("delete from task")
		jdbc.execute("delete from project")
		CalendarApi.resetCalendarTables(jdbc)
	}

	@BeforeEach
	fun setUp() {
		// Wednesday 2026-03-04, 09:00 in Zurich (UTC+1).
		clock.set(NOW)
	}

	// --- helpers

	private fun read(actions: ResultActionsDsl): JsonNode = jsonMapper.readTree(actions.andReturn().response.contentAsString)

	private fun start() = mockMvc.post(REVIEWS) { with(csrf()) }

	private fun move(
		id: String,
		body: String,
		ifMatch: String?,
	) = mockMvc.patch("$REVIEWS/$id") {
		with(csrf())
		contentType = MediaType.APPLICATION_JSON
		content = body
		ifMatch?.let { header(HttpHeaders.IF_MATCH, it) }
	}

	private fun complete(
		id: String,
		ifMatch: String?,
	) = mockMvc.post("$REVIEWS/$id/complete") {
		with(csrf())
		ifMatch?.let { header(HttpHeaders.IF_MATCH, it) }
	}

	private fun etag(node: JsonNode) = "\"${node.get("version").asLong()}\""

	private fun step(path: String): JsonNode = read(mockMvc.get("$REVIEWS/steps/$path").andExpect { status { isOk() } })

	private fun titles(
		node: JsonNode,
		field: String = "items",
	): List<String> = node.get(field).toList().map { it.get("title").asString() }

	/** Creates a task at [at] (its last change), then sets the clock back to [NOW]. */
	private fun task(
		title: String,
		list: TaskList,
		at: Instant = NOW,
		fields: TaskChanges = TaskChanges(),
	): UUID {
		clock.set(at)
		try {
			return tasks.create(null, list, fields.copy(title = Change(title))).task.id
		} finally {
			clock.set(NOW)
		}
	}

	private fun project(
		title: String,
		status: ProjectStatus = ProjectStatus.ACTIVE,
	): UUID = projects.create(ProjectChanges(title = Change(title), status = Change(status))).project.id

	// --- review lifecycle

	@Test
	fun `start creates a review at the first step, starting again resumes it`() {
		val started = read(start().andExpect { status { isCreated() } })
		started.get("step").asString() shouldBe "GET_CLEAR"
		started.get("stepNumber").asInt() shouldBe 1
		started.get("startedAt").asString() shouldBe NOW.toString()
		started.get("completedAt").isNull shouldBe true

		val resumed = read(start().andExpect { status { isOk() } })
		resumed.get("id") shouldBe started.get("id")

		val current = read(mockMvc.get("$REVIEWS/current").andExpect { status { isOk() } })
		current.get("id") shouldBe started.get("id")
		val status = read(mockMvc.get("$REVIEWS/status").andExpect { status { isOk() } })
		status.get("current").get("id") shouldBe started.get("id")
		status.get("lastCompletedAt").isNull shouldBe true
		status.get("daysSinceLastReview").isNull shouldBe true
	}

	@Test
	fun `no open review is a 404, an unknown id too`() {
		mockMvc.get("$REVIEWS/current").andExpect { status { isNotFound() } }
		mockMvc.get("$REVIEWS/${UUID.randomUUID()}").andExpect { status { isNotFound() } }
		read(mockMvc.get("$REVIEWS/status").andExpect { status { isOk() } }).get("current").isNull shouldBe true
	}

	@Test
	fun `the step is saved with If-Match and can go back`() {
		val review = read(start())
		val id = review.get("id").asString()

		move(id, """{"step":"PROJECTS"}""", null).andExpect { status { isEqualTo(428) } }
		move(id, """{"step":"PROJECTS"}""", "\"99\"").andExpect { status { isEqualTo(412) } }
		move(id, """{"step":"NOPE"}""", etag(review)).andExpect { status { isBadRequest() } }
		move(id, """{}""", etag(review)).andExpect { status { isBadRequest() } }

		val moved =
			read(
				move(id, """{"step":"PROJECTS"}""", etag(review)).andExpect {
					status { isOk() }
					header { string(HttpHeaders.ETAG, "\"${review.get("version").asLong() + 1}\"") }
				},
			)
		moved.get("step").asString() shouldBe "PROJECTS"
		moved.get("stepNumber").asInt() shouldBe 5
		move(id, """{"step":"PROJECTS"}""", etag(review)).andExpect { status { isEqualTo(412) } }

		val back = read(move(id, """{"step":"PAST_CALENDAR"}""", etag(moved)).andExpect { status { isOk() } })
		back.get("stepNumber").asInt() shouldBe 2
		read(mockMvc.get("$REVIEWS/$id")).get("step").asString() shouldBe "PAST_CALENDAR"
	}

	@Test
	fun `completing ends the review, the status counts the days since, a new one can start`() {
		val review = read(start())
		val id = review.get("id").asString()
		complete(id, null).andExpect { status { isEqualTo(428) } }

		clock.advance(Duration.ofMinutes(30))
		val completed = read(complete(id, etag(review)).andExpect { status { isOk() } })
		completed.get("completedAt").asString() shouldBe NOW.plus(Duration.ofMinutes(30)).toString()

		complete(id, etag(completed)).andExpect { status { isConflict() } }
		move(id, """{"step":"SOMEDAY"}""", etag(completed)).andExpect { status { isConflict() } }
		mockMvc.get("$REVIEWS/current").andExpect { status { isNotFound() } }

		// Thursday 23:30 in Zurich: still 1 day after Wednesday.
		clock.set(Instant.parse("2026-03-05T22:30:00Z"))
		val status = read(mockMvc.get("$REVIEWS/status"))
		status.get("current").isNull shouldBe true
		status.get("lastCompletedAt").asString() shouldBe completed.get("completedAt").asString()
		status.get("daysSinceLastReview").asLong() shouldBe 1

		// Friday 00:30 in Zurich.
		clock.set(Instant.parse("2026-03-05T23:30:00Z"))
		read(mockMvc.get("$REVIEWS/status")).get("daysSinceLastReview").asLong() shouldBe 2

		val next = read(start().andExpect { status { isCreated() } })
		next.get("id") shouldNotBe review.get("id")
		next.get("step").asString() shouldBe "GET_CLEAR"
	}

	// --- step data

	@Test
	fun `stale next actions are open Next tasks unchanged for more than 30 days, oldest first`() {
		val old = task("Old", TaskList.NEXT, NOW.minus(Duration.ofDays(40)))
		val older = task("Older", TaskList.NEXT, NOW.minus(Duration.ofDays(50)))
		task("Recent", TaskList.NEXT, NOW.minus(Duration.ofDays(10)))
		task("Old inbox", TaskList.INBOX, NOW.minus(Duration.ofDays(40)))
		val done = task("Old done", TaskList.NEXT, NOW.minus(Duration.ofDays(40)))
		clock.set(NOW.minus(Duration.ofDays(35)))
		tasks.complete(done) {}
		clock.set(NOW)

		val stale = step("stale-next-actions")
		titles(stale) shouldContainExactly listOf("Older", "Old")
		stale.get("items").toList().map { it.get("id").asString() } shouldContainExactly listOf(older.toString(), old.toString())

		titles(step("stale-next-actions?olderThanDays=5")) shouldContainExactly listOf("Older", "Old", "Recent")
		mockMvc.get("$REVIEWS/steps/stale-next-actions?olderThanDays=0").andExpect { status { isBadRequest() } }
		mockMvc.get("$REVIEWS/steps/stale-next-actions?olderThanDays=3651").andExpect { status { isBadRequest() } }
	}

	@Test
	fun `waiting for lists open Waiting For tasks, waiting longest first`() {
		task("Since Monday", TaskList.WAITING, fields = TaskChanges(waitingSince = Change(LocalDate.parse("2026-03-02"))))
		task("Since February", TaskList.WAITING, fields = TaskChanges(waitingSince = Change(LocalDate.parse("2026-02-10"))))
		task("Next action", TaskList.NEXT)

		titles(step("waiting-for")) shouldContainExactly listOf("Since February", "Since Monday")
	}

	@Test
	fun `projects are the active ones, stalled first, each by title`() {
		val withStep = project("Alpha with step")
		val next = task("Do it", TaskList.NEXT, fields = TaskChanges(projectId = Change(withStep)))
		projects.setNextStep(withStep, next) {}
		project("Zeta stalled")
		project("Beta stalled")
		project("On hold", ProjectStatus.ON_HOLD)
		project("Some day", ProjectStatus.SOMEDAY)

		val result = step("projects")
		titles(result) shouldContainExactly listOf("Beta stalled", "Zeta stalled", "Alpha with step")
		val stalled: List<Boolean> = result.get("items").toList().map { it.get("stalled").asBoolean() }
		stalled shouldContainExactly listOf(true, true, false)
	}

	@Test
	fun `someday lists Someday tasks and SOMEDAY projects`() {
		task("Learn the cello", TaskList.SOMEDAY)
		task("Not someday", TaskList.NEXT)
		project("Sail around the world", ProjectStatus.SOMEDAY)
		project("Active", ProjectStatus.ACTIVE)

		val someday = step("someday")
		titles(someday, "tasks") shouldContainExactly listOf("Learn the cello")
		titles(someday, "projects") shouldContainExactly listOf("Sail around the world")
	}

	@Test
	fun `past events are the 7 days before today, upcoming ones today and the next 13 days, without cancelled ones`() {
		calendar.createCalendar()
		// Zurich is UTC+1 in March 2026 (until the 29th).
		calendar.createEvent("""{"title":"Too old","start":"2026-02-24T21:00:00Z"}""")
		calendar.createEvent("""{"title":"First past day","start":"2026-02-24T23:30:00Z"}""")
		calendar.createEvent("""{"title":"Yesterday","start":"2026-03-03T10:00:00Z"}""")
		calendar.createEvent("""{"title":"Cancelled","start":"2026-03-03T11:00:00Z","status":"CANCELLED"}""")
		calendar.createEvent("""{"title":"Earlier today","start":"2026-03-04T06:00:00Z"}""")
		calendar.createEvent("""{"title":"Holiday","allDay":true,"startDate":"2026-03-10","endDate":"2026-03-11"}""")
		calendar.createEvent("""{"title":"Last upcoming day","start":"2026-03-17T22:30:00Z"}""")
		calendar.createEvent("""{"title":"Too far","start":"2026-03-17T23:00:00Z"}""")

		val past = step("past-events")
		past.get("from").asString() shouldBe "2026-02-24T23:00:00Z"
		past.get("to").asString() shouldBe "2026-03-03T23:00:00Z"
		titles(past) shouldContainExactly listOf("First past day", "Yesterday")

		val upcoming = step("upcoming-events")
		upcoming.get("from").asString() shouldBe "2026-03-03T23:00:00Z"
		upcoming.get("to").asString() shouldBe "2026-03-17T23:00:00Z"
		titles(upcoming) shouldContainExactly listOf("Earlier today", "Holiday", "Last upcoming day")
		upcoming
			.get("items")
			.first()
			.get("eventId")
			.shouldNotBeNull()
	}

	companion object {
		const val REVIEWS = "/api/v1/weekly-reviews"
		val NOW: Instant = Instant.parse("2026-03-04T08:00:00Z")
	}
}
