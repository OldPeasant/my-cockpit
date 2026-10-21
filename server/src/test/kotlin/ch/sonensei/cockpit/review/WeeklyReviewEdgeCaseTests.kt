package ch.sonensei.cockpit.review

import ch.sonensei.cockpit.calendar.api.CalendarApi
import ch.sonensei.cockpit.core.auth.WithCockpitUser
import ch.sonensei.cockpit.core.persistence.MutableClock
import ch.sonensei.cockpit.core.persistence.MutableClockTestConfiguration
import ch.sonensei.cockpit.core.settings.domain.SettingsService
import ch.sonensei.cockpit.core.web.CursorRequest
import ch.sonensei.cockpit.gtd.domain.Change
import ch.sonensei.cockpit.gtd.domain.ProjectChanges
import ch.sonensei.cockpit.gtd.domain.ProjectService
import ch.sonensei.cockpit.gtd.domain.ProjectStatus
import ch.sonensei.cockpit.gtd.domain.TaskChanges
import ch.sonensei.cockpit.gtd.domain.TaskList
import ch.sonensei.cockpit.gtd.domain.TaskMove
import ch.sonensei.cockpit.gtd.domain.TaskService
import ch.sonensei.cockpit.review.domain.StartedReview
import ch.sonensei.cockpit.review.domain.WeeklyReviewService
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
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
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Edge cases of the weekly review (SPEC.md §8.6) against Postgres, with the test clock: concurrent starts, the one-open
 * review index, the status with several reviews, If-Match variants, items hidden from the steps (tickler, Trash, done),
 * paging beyond one service page, recurring and boundary-spanning events, and time zones other than Europe/Zurich.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MutableClockTestConfiguration::class)
@WithCockpitUser
class WeeklyReviewEdgeCaseTests(
	@Autowired private val mockMvc: MockMvc,
	@Autowired private val jsonMapper: JsonMapper,
	@Autowired private val jdbc: JdbcTemplate,
	@Autowired private val clock: MutableClock,
	@Autowired private val tasks: TaskService,
	@Autowired private val projects: ProjectService,
	@Autowired private val settings: SettingsService,
	@Autowired private val reviews: WeeklyReviewService,
) : PostgresTestBase() {
	private val calendar = CalendarApi(mockMvc, jsonMapper)

	@BeforeEach
	@AfterEach
	fun reset() {
		jdbc.execute("delete from weekly_review")
		jdbc.execute("delete from task_context")
		jdbc.execute("delete from task")
		jdbc.execute("delete from project")
		CalendarApi.resetCalendarTables(jdbc) // also resets the time zone to Europe/Zurich
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

	private fun status(): JsonNode = read(mockMvc.get("$REVIEWS/status").andExpect { status { isOk() } })

	private fun step(path: String): JsonNode = read(mockMvc.get("$REVIEWS/steps/$path").andExpect { status { isOk() } })

	private fun titles(
		node: JsonNode,
		field: String = "items",
	): List<String> = node.get(field).toList().map { it.get("title").asString() }

	private fun <T> atTime(
		instant: Instant,
		action: () -> T,
	): T {
		clock.set(instant)
		try {
			return action()
		} finally {
			clock.set(NOW)
		}
	}

	/** Creates a task at [at] (its last change), then sets the clock back to [NOW]. */
	private fun task(
		title: String,
		list: TaskList,
		at: Instant = NOW,
		fields: TaskChanges = TaskChanges(),
	): UUID = atTime(at) { tasks.create(null, list, fields.copy(title = Change(title))).task.id }

	private fun project(
		title: String,
		status: ProjectStatus = ProjectStatus.ACTIVE,
	): UUID = projects.create(ProjectChanges(title = Change(title), status = Change(status))).project.id

	/** Inserts a review row directly, bypassing the service (timestamps as ISO-8601 instants). */
	private fun insertReview(
		startedAt: String,
		completedAt: String?,
		step: String = "GET_CLEAR",
		id: UUID = UUID.randomUUID(),
	): UUID {
		jdbc.update(
			"""
			insert into weekly_review (id, created_at, updated_at, version, started_at, completed_at, step)
			values (?, cast(? as timestamptz), cast(? as timestamptz), 0, cast(? as timestamptz), cast(? as timestamptz), ?)
			""".trimIndent(),
			id,
			startedAt,
			startedAt,
			startedAt,
			completedAt,
			step,
		)
		return id
	}

	private fun openReviews(): Int =
		checkNotNull(jdbc.queryForObject("select count(*) from weekly_review where completed_at is null", Int::class.java))

	private fun <T> concurrently(
		threads: Int,
		action: () -> T,
	): List<Result<T>> {
		val barrier = CyclicBarrier(threads)
		val pool = Executors.newFixedThreadPool(threads)
		try {
			val futures =
				(0 until threads).map {
					pool.submit<Result<T>> {
						barrier.await(10, TimeUnit.SECONDS)
						runCatching { action() }
					}
				}
			return futures.map { it.get(60, TimeUnit.SECONDS) }
		} finally {
			pool.shutdownNow()
		}
	}

	// --- one open review

	@Test
	fun `concurrent starts create exactly one open review and all return it`() {
		repeat(10) {
			jdbc.execute("delete from weekly_review")
			val results: List<StartedReview> = concurrently(4) { reviews.start() }.map { it.getOrThrow() }

			results.map { it.review.id }.toSet() shouldHaveSize 1
			results.count { it.created } shouldBe 1
			openReviews() shouldBe 1
			jdbc.queryForObject("select count(*) from weekly_review", Int::class.java) shouldBe 1
		}
	}

	@Test
	fun `the database rejects a second open review but allows many completed ones`() {
		insertReview("2026-02-01T08:00:00Z", "2026-02-01T09:00:00Z")
		insertReview("2026-02-08T08:00:00Z", "2026-02-08T09:00:00Z")
		insertReview("2026-03-01T08:00:00Z", null)

		shouldThrow<DataIntegrityViolationException> { insertReview("2026-03-02T08:00:00Z", null) }
		openReviews() shouldBe 1
		jdbc.queryForObject("select count(*) from weekly_review", Int::class.java) shouldBe 3
	}

	@Test
	fun `the database rejects an unknown step`() {
		shouldThrow<DataIntegrityViolationException> { insertReview("2026-03-01T08:00:00Z", null, step = "NOPE") }
		openReviews() shouldBe 0
	}

	@Test
	fun `starting resumes an open review stored earlier, at its saved step`() {
		val id = insertReview("2026-03-01T08:00:00Z", null, step = "SOMEDAY")

		val resumed = read(start().andExpect { status { isOk() } })
		resumed.get("id").asString() shouldBe id.toString()
		resumed.get("step").asString() shouldBe "SOMEDAY"
		resumed.get("stepNumber").asInt() shouldBe 7
		resumed.get("startedAt").asString() shouldBe "2026-03-01T08:00:00Z"
		openReviews() shouldBe 1
	}

	@Test
	fun `a started review has a Location to itself and its version as ETag`() {
		val result = start().andExpect { status { isCreated() } }.andReturn()
		val body = jsonMapper.readTree(result.response.contentAsString)
		val id = body.get("id").asString()

		result.response.getHeader(HttpHeaders.LOCATION)!! shouldEndWith "$REVIEWS/$id"
		result.response.getHeader(HttpHeaders.ETAG) shouldBe "\"${body.get("version").asLong()}\""
		read(mockMvc.get("$REVIEWS/$id").andExpect { status { isOk() } }).get("id").asString() shouldBe id
	}

	// --- status

	@Test
	fun `the status picks the most recently completed review, not the last started or inserted one`() {
		val open = insertReview("2026-03-03T08:00:00Z", null, step = "PROJECTS")
		insertReview("2026-02-01T08:00:00Z", "2026-02-25T10:00:00Z")
		insertReview("2026-02-20T08:00:00Z", "2026-02-21T10:00:00Z")
		insertReview("2026-01-01T08:00:00Z", "2026-01-02T10:00:00Z")

		val status = status()
		status.get("current").get("id").asString() shouldBe open.toString()
		status.get("current").get("step").asString() shouldBe "PROJECTS"
		status.get("lastCompletedAt").asString() shouldBe "2026-02-25T10:00:00Z"
		status.get("daysSinceLastReview").asLong() shouldBe 7
	}

	@Test
	fun `a review completed today is 0 days ago`() {
		insertReview("2026-03-04T06:00:00Z", "2026-03-04T07:00:00Z")

		status().get("daysSinceLastReview").asLong() shouldBe 0
	}

	@Test
	fun `days since the last review count local days in the user's time zone`() {
		// Tuesday 23:30 in Los Angeles (UTC-8), Wednesday 08:30 in Zurich.
		insertReview("2026-03-04T07:00:00Z", "2026-03-04T07:30:00Z")
		// Wednesday 00:30 in Los Angeles, Wednesday 09:30 in Zurich.
		clock.set(Instant.parse("2026-03-04T08:30:00Z"))

		status().get("daysSinceLastReview").asLong() shouldBe 0
		settings.update(mapOf("timeZone" to "America/Los_Angeles"))
		status().get("daysSinceLastReview").asLong() shouldBe 1
	}

	// --- If-Match

	@Test
	fun `If-Match star moves and completes the open review`() {
		val id = read(start()).get("id").asString()

		val moved = read(move(id, """{"step":"WAITING_FOR"}""", "*").andExpect { status { isOk() } })
		moved.get("step").asString() shouldBe "WAITING_FOR"
		val completed = read(complete(id, "*").andExpect { status { isOk() } })
		completed.get("completedAt").asString() shouldBe NOW.toString()

		// A completed review stays completed, whatever version is claimed.
		complete(id, "*").andExpect { status { isConflict() } }
		move(id, """{"step":"SOMEDAY"}""", "*").andExpect { status { isConflict() } }
		val stored = read(mockMvc.get("$REVIEWS/$id").andExpect { status { isOk() } })
		stored.get("completedAt").asString() shouldBe NOW.toString()
	}

	@Test
	fun `If-Match accepts a list containing the current tag and rejects weak or blank tags`() {
		val review = read(start())
		val id = review.get("id").asString()
		val version = review.get("version").asLong()

		move(id, """{"step":"PROJECTS"}""", "W/\"$version\"").andExpect { status { isEqualTo(412) } }
		move(id, """{"step":"PROJECTS"}""", " ").andExpect { status { isEqualTo(428) } }
		read(move(id, """{"step":"PROJECTS"}""", "\"${version + 7}\", \"$version\"").andExpect { status { isOk() } })
			.get("version")
			.asLong() shouldBe version + 1
	}

	@Test
	fun `moving or completing an unknown review is a 404, also without If-Match`() {
		val unknown = UUID.randomUUID().toString()

		move(unknown, """{"step":"PROJECTS"}""", null).andExpect { status { isNotFound() } }
		complete(unknown, null).andExpect { status { isNotFound() } }
		complete(unknown, "*").andExpect { status { isNotFound() } }
	}

	@Test
	fun `an explicit null step is rejected`() {
		val review = read(start())
		val etag = "\"${review.get("version").asLong()}\""

		move(review.get("id").asString(), """{"step":null}""", etag).andExpect { status { isBadRequest() } }
		read(mockMvc.get("$REVIEWS/current")).get("step").asString() shouldBe "GET_CLEAR"
	}

	// --- items hidden from the steps

	@Test
	fun `tasks in a tickler are left out of the task steps until their date`() {
		val old = NOW.minus(Duration.ofDays(40))
		val inFiveDays = LocalDate.parse("2026-03-09")
		task("Visible waiting", TaskList.WAITING)
		val waiting = task("Tickled waiting", TaskList.WAITING)
		tasks.move(waiting, TaskMove(TaskList.WAITING, ticklerDate = inFiveDays)) {}
		task("Visible stale", TaskList.NEXT, old)
		atTime(old) {
			val next = tasks.create(null, TaskList.NEXT, TaskChanges(title = Change("Tickled stale"))).task.id
			tasks.move(next, TaskMove(TaskList.NEXT, ticklerDate = inFiveDays)) {}
		}
		task("Visible someday", TaskList.SOMEDAY)
		val someday = task("Tickled someday", TaskList.SOMEDAY)
		tasks.move(someday, TaskMove(TaskList.SOMEDAY, ticklerDate = inFiveDays)) {}

		titles(step("waiting-for")) shouldContainExactly listOf("Visible waiting")
		titles(step("stale-next-actions")) shouldContainExactly listOf("Visible stale")
		titles(step("someday"), "tasks") shouldContainExactly listOf("Visible someday")
	}

	@Test
	fun `tasks and projects in the Trash or done are left out of every step`() {
		val old = NOW.minus(Duration.ofDays(40))
		task("Kept waiting", TaskList.WAITING)
		val trashedWaiting = task("Trashed waiting", TaskList.WAITING)
		tasks.delete(trashedWaiting) {}
		val doneWaiting = task("Done waiting", TaskList.WAITING)
		tasks.complete(doneWaiting) {}

		task("Kept stale", TaskList.NEXT, old)
		// Deleted while it was still stale, so only the Trash keeps it out.
		val trashedStale = task("Trashed stale", TaskList.NEXT, old)
		atTime(old.plusSeconds(60)) { tasks.delete(trashedStale) {} }

		task("Kept someday", TaskList.SOMEDAY)
		val trashedSomeday = task("Trashed someday", TaskList.SOMEDAY)
		tasks.delete(trashedSomeday) {}

		project("Kept active")
		val trashedActive = project("Trashed active")
		projects.delete(trashedActive) {}
		project("Kept someday project", ProjectStatus.SOMEDAY)
		val trashedSomedayProject = project("Trashed someday project", ProjectStatus.SOMEDAY)
		projects.delete(trashedSomedayProject) {}
		project("Completed", ProjectStatus.COMPLETED)
		project("Dropped", ProjectStatus.DROPPED)

		titles(step("waiting-for")) shouldContainExactly listOf("Kept waiting")
		titles(step("stale-next-actions")) shouldContainExactly listOf("Kept stale")
		val someday = step("someday")
		titles(someday, "tasks") shouldContainExactly listOf("Kept someday")
		titles(someday, "projects") shouldContainExactly listOf("Kept someday project")
		titles(step("projects")) shouldContainExactly listOf("Kept active")
	}

	// --- stale next actions

	@Test
	fun `a next action is stale only after more than the given days without a change`() {
		task("Exactly 30 days", TaskList.NEXT, NOW.minus(Duration.ofDays(30)))
		task("Just over 30 days", TaskList.NEXT, NOW.minus(Duration.ofDays(30)).minusSeconds(1))
		val touched = task("Changed today", TaskList.NEXT, NOW.minus(Duration.ofDays(60)))
		tasks.update(touched, TaskChanges(notesMd = Change("Still relevant"))) {}
		task("A day and a second", TaskList.NEXT, NOW.minus(Duration.ofDays(1)).minusSeconds(1))

		titles(step("stale-next-actions")) shouldContainExactly listOf("Just over 30 days")
		titles(step("stale-next-actions?olderThanDays=1")) shouldContainExactly
			listOf("Just over 30 days", "Exactly 30 days", "A day and a second")
		titles(step("stale-next-actions?olderThanDays=3650")) shouldContainExactly emptyList<String>()
	}

	@Test
	fun `olderThanDays must be a number`() {
		mockMvc.get("$REVIEWS/steps/stale-next-actions?olderThanDays=abc").andExpect { status { isBadRequest() } }
		mockMvc.get("$REVIEWS/steps/stale-next-actions?olderThanDays=-1").andExpect { status { isBadRequest() } }
	}

	// --- more than one service page

	@Test
	fun `waiting for and stale next actions collect every page and sort across pages`() {
		val count = CursorRequest.MAX_LIMIT + 5
		// The later a task is created (the later in list order), the longer it has been waiting, so the
		// longest-waiting tasks are on the last service page.
		repeat(count) { i ->
			task(
				"Waiting %03d".format(i),
				TaskList.WAITING,
				fields = TaskChanges(waitingSince = Change(LocalDate.parse("2025-12-31").minusDays(i.toLong()))),
			)
		}
		// Same for stale next actions: the later created, the older its last change.
		repeat(count) { i ->
			task("Stale %03d".format(i), TaskList.NEXT, NOW.minus(Duration.ofDays(40)).minusSeconds(i.toLong()))
		}

		val waiting = titles(step("waiting-for"))
		waiting shouldHaveSize count
		waiting.first() shouldBe "Waiting %03d".format(count - 1)
		waiting shouldContainExactly (count - 1 downTo 0).map { "Waiting %03d".format(it) }

		val stale = titles(step("stale-next-actions"))
		stale shouldHaveSize count
		stale shouldContainExactly (count - 1 downTo 0).map { "Stale %03d".format(it) }
	}

	@Test
	fun `projects and someday collect every page`() {
		val count = CursorRequest.MAX_LIMIT + 3
		repeat(count) { i -> project("Project %03d".format(i)) }
		repeat(count) { i -> task("Someday %03d".format(i), TaskList.SOMEDAY) }

		val active = titles(step("projects"))
		active shouldContainExactly (0 until count).map { "Project %03d".format(it) }
		titles(step("someday"), "tasks") shouldContainExactly (0 until count).map { "Someday %03d".format(it) }
	}

	// --- projects

	@Test
	fun `a project whose next step was completed is stalled and listed first`() {
		val project = project("Alpha")
		val next = task("First step", TaskList.NEXT, fields = TaskChanges(projectId = Change(project)))
		projects.setNextStep(project, next) {}
		project("Beta")
		val withStep = project("Gamma")
		val gammaNext = task("Gamma step", TaskList.NEXT, fields = TaskChanges(projectId = Change(withStep)))
		projects.setNextStep(withStep, gammaNext) {}

		titles(step("projects")) shouldContainExactly listOf("Beta", "Alpha", "Gamma")
		tasks.complete(next) {}

		val result = step("projects")
		titles(result) shouldContainExactly listOf("Alpha", "Beta", "Gamma")
		val stalled: List<Boolean> = result.get("items").toList().map { it.get("stalled").asBoolean() }
		stalled shouldContainExactly listOf(true, true, false)
	}

	@Test
	fun `within the stalled and the other projects the order is the one of the Projects view`() {
		// Stalled: no next step. Mixed case and an umlaut, where code-point order differs from most collations.
		listOf("Zeta", "alpha", "Émile", "beta").forEach { project(it) }
		listOf("omega", "Delta", "Ärger").forEach { title ->
			val id = project(title)
			val next = task("$title step", TaskList.NEXT, fields = TaskChanges(projectId = Change(id)))
			projects.setNextStep(id, next) {}
		}

		val view = read(mockMvc.get("/api/v1/projects?status=ACTIVE").andExpect { status { isOk() } }).get("items").toList()
		val stalledInView = view.filter { it.get("stalled").asBoolean() }.map { it.get("title").asString() }
		val othersInView = view.filterNot { it.get("stalled").asBoolean() }.map { it.get("title").asString() }
		stalledInView shouldHaveSize 4
		othersInView shouldHaveSize 3

		titles(step("projects")) shouldContainExactly stalledInView + othersInView
	}

	// --- calendar steps

	@Test
	fun `without calendars the calendar steps are empty but keep their range`() {
		val past = step("past-events")
		past.get("from").asString() shouldBe "2026-02-24T23:00:00Z"
		past.get("to").asString() shouldBe "2026-03-03T23:00:00Z"
		past.get("items").toList() shouldHaveSize 0
		step("upcoming-events").get("items").toList() shouldHaveSize 0
	}

	@Test
	fun `events of hidden calendars are left out`() {
		calendar.createCalendar()
		val hidden = calendar.createCalendar("""{"name":"Hidden","visible":false}""").get("id").asString()
		calendar.createEvent("""{"title":"Shown","start":"2026-03-03T10:00:00Z"}""")
		calendar.createEvent("""{"calendarId":"$hidden","title":"Hidden past","start":"2026-03-03T11:00:00Z"}""")
		calendar.createEvent("""{"calendarId":"$hidden","title":"Hidden upcoming","start":"2026-03-05T11:00:00Z"}""")

		titles(step("past-events")) shouldContainExactly listOf("Shown")
		titles(step("upcoming-events")) shouldContainExactly emptyList<String>()
	}

	@Test
	fun `the occurrences of a recurring event are listed in both windows, without cancelled or deleted ones`() {
		calendar.createCalendar()
		val series =
			calendar.createEvent(
				"""
				{"title":"Standup","start":"2026-02-20T09:00:00Z","end":"2026-02-20T09:15:00Z",
				 "timeZone":"Europe/Zurich","rrule":"FREQ=DAILY"}
				""".trimIndent(),
			)
		val id = series.get("id").asString()

		val past = step("past-events").get("items").toList()
		past.map { it.get("start").asString() } shouldContainExactly
			(25..28).map { "2026-02-${it}T09:00:00Z" } + (1..3).map { "2026-03-0${it}T09:00:00Z" }
		past.map { it.get("seriesId").asString() }.toSet() shouldBe setOf(id)
		val upcoming = step("upcoming-events").get("items").toList()
		upcoming shouldHaveSize 14
		upcoming.first().get("start").asString() shouldBe "2026-03-04T09:00:00Z"
		upcoming.last().get("start").asString() shouldBe "2026-03-17T09:00:00Z"

		calendar.patchEventOk(id, """{"status":"CANCELLED"}""", "?scope=THIS&occurrence=2026-03-02T09:00:00Z")
		calendar.deleteEvent(id, "?scope=THIS&occurrence=2026-03-05T09:00:00Z").andExpect { status { isNoContent() } }

		val pastAfter = step("past-events").get("items").toList().map { it.get("start").asString() }
		pastAfter shouldHaveSize 6
		pastAfter.contains("2026-03-02T09:00:00Z") shouldBe false
		val upcomingAfter = step("upcoming-events").get("items").toList().map { it.get("start").asString() }
		upcomingAfter shouldHaveSize 13
		upcomingAfter.contains("2026-03-05T09:00:00Z") shouldBe false
	}

	@Test
	fun `events spanning a window boundary are listed in each window they overlap, ordered by start`() {
		calendar.createCalendar()
		calendar.createEvent("""{"title":"Ended before","allDay":true,"startDate":"2026-02-20","endDate":"2026-02-25"}""")
		calendar.createEvent("""{"title":"Ski week","allDay":true,"startDate":"2026-02-21","endDate":"2026-02-27"}""")
		calendar.createEvent("""{"title":"Conference","allDay":true,"startDate":"2026-03-02","endDate":"2026-03-06"}""")
		// 23:30 to 00:30 in Zurich, over midnight into today.
		calendar.createEvent("""{"title":"Night shift","start":"2026-03-03T22:30:00Z","end":"2026-03-03T23:30:00Z"}""")
		// Ends exactly where the past window starts.
		calendar.createEvent("""{"title":"Ends at the start","start":"2026-02-24T22:00:00Z","end":"2026-02-24T23:00:00Z"}""")
		calendar.createEvent("""{"title":"Starts after","allDay":true,"startDate":"2026-03-18","endDate":"2026-03-19"}""")

		titles(step("past-events")) shouldContainExactly listOf("Ski week", "Conference", "Night shift")
		titles(step("upcoming-events")) shouldContainExactly listOf("Conference", "Night shift")
	}

	@Test
	fun `the windows follow the user's time zone when it is not Zurich`() {
		// Thursday 2026-03-05, 01:00 in Auckland (UTC+13); still Wednesday 13:00 in Zurich.
		clock.set(Instant.parse("2026-03-04T12:00:00Z"))
		settings.update(mapOf("timeZone" to "Pacific/Auckland"))
		calendar.createCalendar()
		// Wednesday 23:00 in Auckland: yesterday there, today in Zurich.
		calendar.createEvent("""{"title":"Wednesday evening","start":"2026-03-04T10:00:00Z"}""")
		calendar.createEvent("""{"title":"Thursday night","start":"2026-03-04T11:30:00Z"}""")

		val past = step("past-events")
		past.get("from").asString() shouldBe "2026-02-25T11:00:00Z"
		past.get("to").asString() shouldBe "2026-03-04T11:00:00Z"
		titles(past) shouldContainExactly listOf("Wednesday evening")
		val upcoming = step("upcoming-events")
		upcoming.get("from").asString() shouldBe "2026-03-04T11:00:00Z"
		upcoming.get("to").asString() shouldBe "2026-03-18T11:00:00Z"
		titles(upcoming) shouldContainExactly listOf("Thursday night")
	}

	@Test
	fun `the upcoming window ends at local midnight across a daylight saving change`() {
		// 03:00 in New York (UTC-5); the US switches to UTC-4 on 2026-03-08.
		settings.update(mapOf("timeZone" to "America/New_York"))
		calendar.createCalendar()
		calendar.createEvent("""{"title":"Late on the 17th","start":"2026-03-18T03:30:00Z"}""")
		calendar.createEvent("""{"title":"Early on the 18th","start":"2026-03-18T04:30:00Z"}""")
		calendar.createEvent("""{"title":"All day 17th","allDay":true,"startDate":"2026-03-17","endDate":"2026-03-18"}""")
		calendar.createEvent("""{"title":"All day 18th","allDay":true,"startDate":"2026-03-18","endDate":"2026-03-19"}""")

		val upcoming = step("upcoming-events")
		upcoming.get("from").asString() shouldBe "2026-03-04T05:00:00Z"
		upcoming.get("to").asString() shouldBe "2026-03-18T04:00:00Z"
		titles(upcoming) shouldContainExactly listOf("All day 17th", "Late on the 17th")
		step("past-events").get("from").asString() shouldBe "2026-02-25T05:00:00Z"
	}

	@Test
	fun `the Zurich windows follow the change to summer time`() {
		// Friday 2026-03-20, 09:00 in Zurich; summer time (UTC+2) starts on 2026-03-29.
		clock.set(Instant.parse("2026-03-20T08:00:00Z"))
		calendar.createCalendar()
		calendar.createEvent("""{"title":"Late on 2 April","start":"2026-04-02T21:30:00Z"}""")
		calendar.createEvent("""{"title":"Early on 3 April","start":"2026-04-02T22:30:00Z"}""")

		val past = step("past-events")
		past.get("from").asString() shouldBe "2026-03-12T23:00:00Z"
		past.get("to").asString() shouldBe "2026-03-19T23:00:00Z"
		val upcoming = step("upcoming-events")
		upcoming.get("from").asString() shouldBe "2026-03-19T23:00:00Z"
		upcoming.get("to").asString() shouldBe "2026-04-02T22:00:00Z"
		titles(upcoming) shouldContainExactly listOf("Late on 2 April")
	}

	companion object {
		const val REVIEWS = "/api/v1/weekly-reviews"
		val NOW: Instant = Instant.parse("2026-03-04T08:00:00Z")
	}
}
