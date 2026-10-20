package ch.sonensei.cockpit.calendar.api

import ch.sonensei.cockpit.calendar.api.CalendarApi.Companion.changes
import ch.sonensei.cockpit.calendar.api.CalendarApi.Companion.id
import ch.sonensei.cockpit.calendar.api.CalendarApi.Companion.isProblem
import ch.sonensei.cockpit.calendar.api.CalendarApi.Companion.text
import ch.sonensei.cockpit.core.auth.WithCockpitUser
import ch.sonensei.cockpit.core.events.domain.ChangeOp
import ch.sonensei.cockpit.core.persistence.MutableClock
import ch.sonensei.cockpit.core.persistence.MutableClockTestConfiguration
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.event.ApplicationEvents
import org.springframework.test.context.event.RecordApplicationEvents
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/**
 * Edge cases of series edits: DST, all-day toggles, moved overrides, COUNT/UNTIL, calendar moves, change events,
 * search and Trash. The weekly series is every Monday 09:00-10:00 in Europe/Zurich from 2 March 2026 (08:00Z; 07:00Z
 * from 30 March, after the DST change).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MutableClockTestConfiguration::class)
@RecordApplicationEvents
@WithCockpitUser
class CalendarSeriesEdgeCaseWebTests(
	@Autowired private val mockMvc: MockMvc,
	@Autowired jsonMapper: JsonMapper,
	@Autowired private val clock: MutableClock,
	@Autowired private val jdbc: JdbcTemplate,
) : PostgresTestBase() {
	private val api = CalendarApi(mockMvc, jsonMapper)
	private lateinit var calendarId: String

	@BeforeEach
	fun reset() {
		CalendarApi.resetCalendarTables(jdbc)
		clock.set(MutableClockTestConfiguration.START)
		calendarId = api.createCalendar().id()
	}

	private fun weekly(rrule: String = "FREQ=WEEKLY"): JsonNode =
		api.createEvent(
			"""{"title":"Team meeting","start":"2026-03-02T08:00:00Z","end":"2026-03-02T09:00:00Z","timeZone":"Europe/Zurich","rrule":"$rrule"}""",
		)

	/** Occurrences from 1 March to 15 April 2026 (seven Mondays). */
	private fun spring(vararg params: Pair<String, String>): List<JsonNode> =
		api.occurrences("2026-03-01T00:00:00Z", "2026-04-15T00:00:00Z", *params)

	private fun List<JsonNode>.starts() = map { it.text("start") }

	private fun List<JsonNode>.titles() = map { it.text("title") }

	private fun rowCount() = jdbc.queryForObject("select count(*) from calendar_event", Int::class.java)

	private fun searchTitle(id: String): String? =
		jdbc
			.query("select title from search_document where item_type = 'EVENT' and item_id = ?::uuid", { rs, _ -> rs.getString(1) }, id)
			.singleOrNull()

	private fun searchedIds(): List<String> =
		jdbc.queryForList("select item_id::text from search_document where item_type = 'EVENT'", String::class.java).filterNotNull()

	private fun exdates(event: JsonNode) = event.get("exdates").values().map { it.asString() }

	private fun assertGone(id: String) {
		mockMvc.get("${CalendarApi.EVENTS}/$id").andExpect { status { isNotFound() } }
	}

	private fun deleteThis(
		id: String,
		occurrence: String,
	) = api.deleteEvent(id, "?scope=THIS&occurrence=$occurrence").andExpect { status { isNoContent() } }

	private val mondaysAfterDst = listOf("2026-03-30", "2026-04-06", "2026-04-13")

	// --- ALL and DST

	@Test
	fun `PATCH ALL moving the series keeps its wall-clock time across DST and carries its exceptions`() {
		val series = weekly()
		deleteThis(series.id(), "2026-03-30T07:00:00Z")
		val planning = api.patchEventOk(series.id(), """{"title":"Planning"}""", "?scope=THIS&occurrence=2026-04-06T07:00:00Z")

		// From 09:00 to 10:00 local.
		val result = api.patchEventOk(series.id(), """{"start":"2026-03-02T09:00:00Z"}""")

		result.text("start") shouldBe "2026-03-02T09:00:00Z"
		result.text("end") shouldBe "2026-03-02T10:00:00Z"
		exdates(result) shouldContainExactly listOf("2026-03-30T08:00:00Z")
		val occurrences = spring()
		occurrences.starts() shouldContainExactly
			listOf(
				"2026-03-02T09:00:00Z",
				"2026-03-09T09:00:00Z",
				"2026-03-16T09:00:00Z",
				"2026-03-23T09:00:00Z",
				"2026-04-06T08:00:00Z",
				"2026-04-13T08:00:00Z",
			)
		occurrences[4].text("title") shouldBe "Planning"
		occurrences[4].text("occurrence") shouldBe "2026-04-06T08:00:00Z"
		// The recurrence id changed: the override was recreated.
		assertGone(planning.id())
	}

	@Test
	fun `PATCH ALL with an occurrence after the DST change shifts the series by the local offset`() {
		val series = weekly()

		val result =
			api.patchEventOk(series.id(), """{"start":"2026-03-30T08:00:00Z"}""", "?scope=ALL&occurrence=2026-03-30T07:00:00Z")

		result.text("start") shouldBe "2026-03-02T09:00:00Z"
		spring().starts() shouldContainExactly
			listOf(
				"2026-03-02T09:00:00Z",
				"2026-03-09T09:00:00Z",
				"2026-03-16T09:00:00Z",
				"2026-03-23T09:00:00Z",
				"2026-03-30T08:00:00Z",
				"2026-04-06T08:00:00Z",
				"2026-04-13T08:00:00Z",
			)
	}

	@Test
	fun `PATCH ALL with a new time zone keeps the local time in that zone`() {
		val series = weekly()

		// 09:00 in London: 09:00Z in winter, 08:00Z from 29 March (BST).
		val result = api.patchEventOk(series.id(), """{"start":"2026-03-02T09:00:00Z","timeZone":"Europe/London"}""")

		result.text("timeZone") shouldBe "Europe/London"
		spring().starts().takeLast(3) shouldContainExactly listOf("2026-03-30T08:00:00Z", "2026-04-06T08:00:00Z", "2026-04-13T08:00:00Z")
	}

	// --- all-day toggles

	@Test
	fun `PATCH ALL turns a timed series all-day, dropping exceptions of the old kind, and back`(events: ApplicationEvents) {
		val series = weekly()
		val kickOff = api.patchEventOk(series.id(), """{"title":"Kick-off"}""", "?scope=THIS&occurrence=2026-03-09T08:00:00Z")
		deleteThis(series.id(), "2026-03-16T08:00:00Z")

		val allDay = api.patchEventOk(series.id(), """{"allDay":true}""")

		allDay.get("allDay").asBoolean() shouldBe true
		allDay.text("startDate") shouldBe "2026-03-02"
		allDay.text("endDate") shouldBe "2026-03-03"
		allDay.text("timeZone") shouldBe null
		exdates(allDay).shouldBeEmpty()
		spring().map { it.text("startDate") to it.text("title") } shouldContainExactly
			(listOf("2026-03-02", "2026-03-09", "2026-03-16", "2026-03-23") + mondaysAfterDst).map { it to "Team meeting" }
		assertGone(kickOff.id())
		searchedIds() shouldContainExactly listOf(series.id())
		events.changes().takeLast(2) shouldContainExactly listOf(ChangeOp.DELETED to kickOff.id(), ChangeOp.UPDATED to series.id())

		val timed = api.patchEventOk(series.id(), """{"allDay":false,"start":"2026-03-02T08:00:00Z"}""")

		timed.text("timeZone") shouldBe "Europe/Zurich"
		timed.text("end") shouldBe "2026-03-02T09:00:00Z"
		spring().starts().takeLast(3) shouldContainExactly listOf("2026-03-30T07:00:00Z", "2026-04-06T07:00:00Z", "2026-04-13T07:00:00Z")
		rowCount() shouldBe 1
	}

	@Test
	fun `PATCH THIS can make one occurrence of a timed series all-day`() {
		val series = weekly()

		val override = api.patchEventOk(series.id(), """{"allDay":true}""", "?scope=THIS&occurrence=2026-03-09T08:00:00Z")

		override.get("allDay").asBoolean() shouldBe true
		override.text("startDate") shouldBe "2026-03-09"
		override.text("endDate") shouldBe "2026-03-10"
		override.text("recurrenceId") shouldBe "2026-03-09T08:00:00Z"
		val occurrences = spring()
		occurrences.size shouldBe 7
		val moved = occurrences.single { it.text("eventId") == override.id() }
		moved.get("allDay").asBoolean() shouldBe true
		moved.text("startDate") shouldBe "2026-03-09"
		moved.text("occurrence") shouldBe "2026-03-09T08:00:00Z"
	}

	@Test
	fun `PATCH ALL or FOLLOWING with dates through an all-day override of a timed series is rejected and changes nothing`() {
		val series = weekly()
		deleteThis(series.id(), "2026-03-16T08:00:00Z")
		val allDay = api.patchEventOk(series.id(), """{"allDay":true}""", "?scope=THIS&occurrence=2026-03-09T08:00:00Z")

		// Without "allDay" the request keeps the series timed, so all-day fields do not fit it.
		api.patchEvent(allDay.id(), """{"startDate":"2026-03-10"}""", "?scope=ALL").isProblem(400, "startDate")
		api.patchEvent(allDay.id(), """{"startDate":"2026-03-10"}""", "?scope=FOLLOWING").isProblem(400, "startDate")

		val unchanged = api.event(series.id())
		unchanged.get("allDay").asBoolean() shouldBe false
		unchanged.text("start") shouldBe "2026-03-02T08:00:00Z"
		exdates(unchanged) shouldContainExactly listOf("2026-03-16T08:00:00Z")
		api.event(allDay.id()).text("startDate") shouldBe "2026-03-09"
		spring().size shouldBe 6
		rowCount() shouldBe 2
	}

	@Test
	fun `PATCH ALL with a start through an all-day override moves the timed series and keeps its exceptions`() {
		val series = weekly()
		deleteThis(series.id(), "2026-03-16T08:00:00Z")
		val allDay = api.patchEventOk(series.id(), """{"allDay":true}""", "?scope=THIS&occurrence=2026-03-09T08:00:00Z")

		// The occurrence of 9 March now starts on the 10th at 09:00 Zurich time: the series moves by one day.
		api.patchEventOk(allDay.id(), """{"start":"2026-03-10T08:00:00Z"}""", "?scope=ALL")

		val moved = api.event(series.id())
		moved.get("allDay").asBoolean() shouldBe false
		moved.text("start") shouldBe "2026-03-03T08:00:00Z"
		exdates(moved) shouldContainExactly listOf("2026-03-17T08:00:00Z")
		val occurrences = spring()
		occurrences.size shouldBe 6
		occurrences.forEach { it.get("allDay").asBoolean() shouldBe false }
		occurrences.starts().take(3) shouldContainExactly listOf("2026-03-03T08:00:00Z", "2026-03-10T08:00:00Z", "2026-03-24T08:00:00Z")
	}

	@Test
	fun `PATCH FOLLOWING with a start through an all-day override splits the timed series and keeps it timed`() {
		val series = weekly()
		deleteThis(series.id(), "2026-03-23T08:00:00Z")
		val allDay = api.patchEventOk(series.id(), """{"allDay":true}""", "?scope=THIS&occurrence=2026-03-09T08:00:00Z")

		val next = api.patchEventOk(allDay.id(), """{"start":"2026-03-10T08:00:00Z"}""", "?scope=FOLLOWING")

		next.get("allDay").asBoolean() shouldBe false
		next.text("start") shouldBe "2026-03-10T08:00:00Z"
		next.text("end") shouldBe "2026-03-10T09:00:00Z"
		exdates(next) shouldContainExactly listOf("2026-03-24T08:00:00Z")
		val occurrences = spring()
		occurrences.forEach { it.get("allDay").asBoolean() shouldBe false }
		occurrences.starts() shouldContainExactly
			listOf(
				"2026-03-02T08:00:00Z",
				"2026-03-10T08:00:00Z",
				"2026-03-17T08:00:00Z",
				"2026-03-31T07:00:00Z",
				"2026-04-07T07:00:00Z",
				"2026-04-14T07:00:00Z",
			)
	}

	@Test
	fun `PATCH ALL with allDay sent through an all-day override still turns the timed series all-day`() {
		val series = weekly()
		deleteThis(series.id(), "2026-03-16T08:00:00Z")
		val allDay = api.patchEventOk(series.id(), """{"allDay":true}""", "?scope=THIS&occurrence=2026-03-09T08:00:00Z")

		// The occurrence of 9 March moves to the 10th: the series becomes all-day and moves one day.
		api.patchEventOk(allDay.id(), """{"allDay":true,"startDate":"2026-03-10"}""", "?scope=ALL")

		val changed = api.event(series.id())
		changed.get("allDay").asBoolean() shouldBe true
		changed.text("startDate") shouldBe "2026-03-03"
		changed.text("endDate") shouldBe "2026-03-04"
		// A change of kind drops the exceptions of the old kind (documented), the addressed override included.
		exdates(changed).shouldBeEmpty()
		assertGone(allDay.id())
		spring().map { it.text("startDate") } shouldContainExactly
			listOf("2026-03-03", "2026-03-10", "2026-03-17", "2026-03-24", "2026-03-31", "2026-04-07", "2026-04-14")
		rowCount() shouldBe 1
	}

	@Test
	fun `PATCH FOLLOWING with allDay sent through an all-day override continues the series all-day`() {
		val series = weekly()
		val allDay = api.patchEventOk(series.id(), """{"allDay":true}""", "?scope=THIS&occurrence=2026-03-16T08:00:00Z")

		val next = api.patchEventOk(allDay.id(), """{"allDay":true,"startDate":"2026-03-17"}""", "?scope=FOLLOWING")

		next.get("allDay").asBoolean() shouldBe true
		next.text("startDate") shouldBe "2026-03-17"
		val old = api.event(series.id())
		old.get("allDay").asBoolean() shouldBe false
		val occurrences = spring()
		occurrences.filter { !it.get("allDay").asBoolean() }.starts() shouldContainExactly
			listOf("2026-03-02T08:00:00Z", "2026-03-09T08:00:00Z")
		occurrences.filter { it.get("allDay").asBoolean() }.map { it.text("startDate") } shouldContainExactly
			listOf("2026-03-17", "2026-03-24", "2026-03-31", "2026-04-07", "2026-04-14")
	}

	@Test
	fun `PATCH ALL with allDay false and a start through an all-day override moves the series like without allDay`() {
		val series = weekly()
		val allDay = api.patchEventOk(series.id(), """{"allDay":true}""", "?scope=THIS&occurrence=2026-03-09T08:00:00Z")

		// allDay=false is the series' own kind: the occurrence of 9 March now starts on the 10th at 09:00 Zurich time,
		// so the series moves by one day and keeps its 09:00 start, as in the request without allDay.
		api.patchEventOk(
			allDay.id(),
			"""{"allDay":false,"start":"2026-03-10T08:00:00Z","timeZone":"Europe/Zurich"}""",
			"?scope=ALL",
		)

		val moved = api.event(series.id())
		moved.get("allDay").asBoolean() shouldBe false
		moved.text("start") shouldBe "2026-03-03T08:00:00Z"
		spring().starts().take(3) shouldContainExactly listOf("2026-03-03T08:00:00Z", "2026-03-10T08:00:00Z", "2026-03-17T08:00:00Z")
	}

	@Test
	fun `PATCH FOLLOWING with allDay false and a start through an all-day override splits like without allDay`() {
		val series = weekly()
		val allDay = api.patchEventOk(series.id(), """{"allDay":true}""", "?scope=THIS&occurrence=2026-03-09T08:00:00Z")

		val next =
			api.patchEventOk(
				allDay.id(),
				"""{"allDay":false,"start":"2026-03-10T08:00:00Z","timeZone":"Europe/Zurich"}""",
				"?scope=FOLLOWING",
			)

		next.get("allDay").asBoolean() shouldBe false
		next.text("start") shouldBe "2026-03-10T08:00:00Z"
		next.text("end") shouldBe "2026-03-10T09:00:00Z"
		spring().starts().take(3) shouldContainExactly listOf("2026-03-02T08:00:00Z", "2026-03-10T08:00:00Z", "2026-03-17T08:00:00Z")
	}

	@Test
	fun `PATCH ALL through a timed override of an all-day series keeps the series all-day`() {
		val gym = api.createEvent("""{"title":"Gym","allDay":true,"startDate":"2026-03-02","rrule":"FREQ=DAILY;COUNT=5"}""")
		val timed =
			api.patchEventOk(
				gym.id(),
				"""{"allDay":false,"start":"2026-03-04T17:00:00Z","timeZone":"Europe/Zurich"}""",
				"?scope=THIS&occurrence=2026-03-04",
			)

		// Without "allDay" the series stays all-day: a timed start does not fit it.
		api.patchEvent(timed.id(), """{"start":"2026-03-05T17:00:00Z"}""", "?scope=ALL").isProblem(400, "start")
		api.event(gym.id()).text("startDate") shouldBe "2026-03-02"

		// Dates move the series by days; the addressed occurrence takes the requested day.
		api.patchEventOk(timed.id(), """{"startDate":"2026-03-05"}""", "?scope=ALL")

		val moved = api.event(gym.id())
		moved.get("allDay").asBoolean() shouldBe true
		moved.text("startDate") shouldBe "2026-03-03"
		val occurrences = spring()
		occurrences.forEach { it.get("allDay").asBoolean() shouldBe true }
		occurrences.map { it.text("startDate") } shouldContainExactly listOf("2026-03-03", "2026-03-04", "2026-03-05", "2026-03-06", "2026-03-07")
	}

	@Test
	fun `PATCH ALL of an all-day series moves it by days with its exceptions`() {
		val gym = api.createEvent("""{"title":"Gym","allDay":true,"startDate":"2026-03-02","rrule":"FREQ=DAILY;COUNT=5"}""")
		deleteThis(gym.id(), "2026-03-03")
		api.patchEventOk(gym.id(), """{"title":"Swim"}""", "?scope=THIS&occurrence=2026-03-04")

		// The occurrence of 4 March moves to the 5th: the whole series moves one day.
		val result = api.patchEventOk(gym.id(), """{"startDate":"2026-03-05"}""", "?scope=ALL&occurrence=2026-03-04")

		result.text("startDate") shouldBe "2026-03-03"
		exdates(result) shouldContainExactly listOf("2026-03-04")
		spring().map { it.text("startDate") to it.text("title") } shouldContainExactly
			listOf("2026-03-03" to "Gym", "2026-03-05" to "Swim", "2026-03-06" to "Gym", "2026-03-07" to "Gym")
	}

	// --- FOLLOWING and ALL through a moved override

	@Test
	fun `PATCH FOLLOWING through a moved override with content only keeps its moved time`() {
		val series = weekly()
		val moved = api.patchEventOk(series.id(), """{"start":"2026-03-24T08:00:00Z"}""", "?scope=THIS&occurrence=2026-03-23T08:00:00Z")

		val next = api.patchEventOk(moved.id(), """{"title":"Team sync"}""", "?scope=FOLLOWING")

		next.text("start") shouldBe "2026-03-23T08:00:00Z"
		val occurrence = spring().single { it.text("occurrence") == "2026-03-23T08:00:00Z" }
		occurrence.text("seriesId") shouldBe next.id()
		occurrence.text("start") shouldBe "2026-03-24T08:00:00Z"
		occurrence.text("title") shouldBe "Team sync"
		spring().titles() shouldContainExactly
			listOf("Team meeting", "Team meeting", "Team meeting", "Team sync", "Team sync", "Team sync", "Team sync")
	}

	@Test
	fun `PATCH FOLLOWING through a moved override puts that occurrence at the requested time`() {
		val series = weekly()
		val moved = api.patchEventOk(series.id(), """{"start":"2026-03-24T08:00:00Z"}""", "?scope=THIS&occurrence=2026-03-23T08:00:00Z")

		// The moved occurrence (Tuesday 09:00 local) is dragged one hour later, for it and every later one.
		val next = api.patchEventOk(moved.id(), """{"start":"2026-03-24T09:00:00Z"}""", "?scope=FOLLOWING")

		next.text("start") shouldBe "2026-03-23T09:00:00Z"
		val occurrence = spring().single { it.text("occurrence") == "2026-03-23T09:00:00Z" }
		occurrence.text("start") shouldBe "2026-03-24T09:00:00Z"
		occurrence.text("end") shouldBe "2026-03-24T10:00:00Z"
	}

	@Test
	fun `PATCH ALL through a moved override puts that occurrence at the requested time`() {
		val series = weekly()
		val moved = api.patchEventOk(series.id(), """{"start":"2026-03-24T08:00:00Z"}""", "?scope=THIS&occurrence=2026-03-23T08:00:00Z")

		val result = api.patchEventOk(moved.id(), """{"start":"2026-03-24T09:00:00Z"}""", "?scope=ALL")

		result.text("start") shouldBe "2026-03-02T09:00:00Z"
		val occurrence = spring().single { it.text("occurrence") == "2026-03-23T09:00:00Z" }
		occurrence.text("start") shouldBe "2026-03-24T09:00:00Z"
	}

	@Test
	fun `PATCH FOLLOWING through the series and an occurrence with a moved override puts it at the requested time`() {
		val series = weekly()
		val moved = api.patchEventOk(series.id(), """{"start":"2026-03-24T08:00:00Z"}""", "?scope=THIS&occurrence=2026-03-23T08:00:00Z")
		// Another moved override later in the series: it is carried over and keeps its own moved time.
		api.patchEventOk(series.id(), """{"start":"2026-04-07T07:00:00Z","title":"Tuesday"}""", "?scope=THIS&occurrence=2026-04-06T07:00:00Z")

		// Addressed through the master and occurrence=, not through the override id: one hour later from there on.
		val next =
			api.patchEventOk(series.id(), """{"start":"2026-03-24T09:00:00Z"}""", "?scope=FOLLOWING&occurrence=2026-03-23T08:00:00Z")

		next.text("start") shouldBe "2026-03-23T09:00:00Z"
		api.event(series.id()).text("rrule") shouldBe "FREQ=WEEKLY;UNTIL=20260323T075959Z"
		assertGone(moved.id())
		spring().map { it.text("occurrence") to it.text("start") } shouldContainExactlyInAnyOrder
			listOf(
				"2026-03-02T08:00:00Z" to "2026-03-02T08:00:00Z",
				"2026-03-09T08:00:00Z" to "2026-03-09T08:00:00Z",
				"2026-03-16T08:00:00Z" to "2026-03-16T08:00:00Z",
				"2026-03-23T09:00:00Z" to "2026-03-24T09:00:00Z",
				"2026-03-30T08:00:00Z" to "2026-03-30T08:00:00Z",
				"2026-04-06T08:00:00Z" to "2026-04-07T07:00:00Z",
				"2026-04-13T08:00:00Z" to "2026-04-13T08:00:00Z",
			)
		spring().single { it.text("occurrence") == "2026-04-06T08:00:00Z" }.text("title") shouldBe "Tuesday"
	}

	@Test
	fun `PATCH ALL changing only the end through a moved override keeps it in place with the requested end`() {
		val series = weekly()
		val moved = api.patchEventOk(series.id(), """{"start":"2026-03-24T08:00:00Z"}""", "?scope=THIS&occurrence=2026-03-23T08:00:00Z")
		val titled = api.patchEventOk(series.id(), """{"title":"Kick-off"}""", "?scope=THIS&occurrence=2026-03-09T08:00:00Z")

		// Every occurrence lasts 90 minutes; the start of the series does not move.
		val result = api.patchEventOk(moved.id(), """{"end":"2026-03-24T09:30:00Z"}""", "?scope=ALL")

		result.text("start") shouldBe "2026-03-02T08:00:00Z"
		result.text("end") shouldBe "2026-03-02T09:30:00Z"
		// The recurrence ids did not change: both overrides were kept, not recreated.
		api.event(moved.id()).let {
			it.text("start") shouldBe "2026-03-24T08:00:00Z"
			it.text("end") shouldBe "2026-03-24T09:30:00Z"
		}
		api.event(titled.id()).text("end") shouldBe "2026-03-09T09:30:00Z"
		spring().map { it.text("start") to it.text("end") }.take(4) shouldContainExactly
			listOf(
				"2026-03-02T08:00:00Z" to "2026-03-02T09:30:00Z",
				"2026-03-09T08:00:00Z" to "2026-03-09T09:30:00Z",
				"2026-03-16T08:00:00Z" to "2026-03-16T09:30:00Z",
				"2026-03-24T08:00:00Z" to "2026-03-24T09:30:00Z",
			)
		rowCount() shouldBe 3
	}

	@Test
	fun `PATCH ALL with content only keeps a moved override at its moved time`() {
		val series = weekly()
		val moved = api.patchEventOk(series.id(), """{"start":"2026-03-24T08:00:00Z"}""", "?scope=THIS&occurrence=2026-03-23T08:00:00Z")

		api.patchEventOk(series.id(), """{"title":"Weekly"}""")

		api.event(moved.id()).let {
			it.text("title") shouldBe "Weekly"
			it.text("start") shouldBe "2026-03-24T08:00:00Z"
		}
		spring().single { it.text("occurrence") == "2026-03-23T08:00:00Z" }.text("start") shouldBe "2026-03-24T08:00:00Z"
	}

	@Test
	fun `an override may name its own occurrence`() {
		val series = weekly()
		val override = api.patchEventOk(series.id(), """{"title":"Special"}""", "?scope=THIS&occurrence=2026-03-09T08:00:00Z")

		api.patchEventOk(override.id(), """{"location":"Room B"}""", "?occurrence=2026-03-09T08:00:00Z").id() shouldBe override.id()
	}

	// --- COUNT and UNTIL

	@Test
	fun `PATCH FOLLOWING counts excluded occurrences before the split`() {
		val series = weekly("FREQ=WEEKLY;COUNT=5")
		deleteThis(series.id(), "2026-03-09T08:00:00Z")

		val next = api.patchEventOk(series.id(), """{"title":"Later"}""", "?scope=FOLLOWING&occurrence=2026-03-16T08:00:00Z")

		next.text("rrule") shouldBe "FREQ=WEEKLY;COUNT=3"
		spring().map { it.text("start") to it.text("title") } shouldContainExactly
			listOf(
				"2026-03-02T08:00:00Z" to "Team meeting",
				"2026-03-16T08:00:00Z" to "Later",
				"2026-03-23T08:00:00Z" to "Later",
				"2026-03-30T07:00:00Z" to "Later",
			)
	}

	@Test
	fun `PATCH FOLLOWING of the last counted occurrence leaves a series of one`() {
		val series = weekly("FREQ=WEEKLY;BYDAY=MO;COUNT=3")
		// Beyond the COUNT there is no occurrence.
		api.patchEvent(series.id(), """{"title":"x"}""", "?scope=THIS&occurrence=2026-03-23T08:00:00Z").isProblem(404)

		val next = api.patchEventOk(series.id(), """{"title":"Last"}""", "?scope=FOLLOWING&occurrence=2026-03-16T08:00:00Z")

		next.text("rrule") shouldBe "FREQ=WEEKLY;BYDAY=MO;COUNT=1"
		api.event(series.id()).text("rrule") shouldBe "FREQ=WEEKLY;BYDAY=MO;UNTIL=20260316T075959Z"
		spring().titles() shouldContainExactly listOf("Team meeting", "Team meeting", "Last")
	}

	@Test
	fun `PATCH FOLLOWING of a series with UNTIL keeps the end for the new series`() {
		val series = weekly("FREQ=WEEKLY;UNTIL=20260324T000000Z")

		val next = api.patchEventOk(series.id(), """{"title":"Later"}""", "?scope=FOLLOWING&occurrence=2026-03-16T08:00:00Z")

		next.text("rrule") shouldBe "FREQ=WEEKLY;UNTIL=20260324T000000Z"
		api.event(series.id()).text("rrule") shouldBe "FREQ=WEEKLY;UNTIL=20260316T075959Z"
		spring().titles() shouldContainExactly listOf("Team meeting", "Team meeting", "Later", "Later")
	}

	@Test
	fun `PATCH FOLLOWING with a rule takes that rule for the new series`() {
		val series = weekly("FREQ=WEEKLY;COUNT=6")

		val next =
			api.patchEventOk(series.id(), """{"rrule":"RRULE:FREQ=WEEKLY;COUNT=2"}""", "?scope=FOLLOWING&occurrence=2026-03-16T08:00:00Z")

		next.text("rrule") shouldBe "FREQ=WEEKLY;COUNT=2"
		spring().size shouldBe 4
	}

	@Test
	fun `DELETE FOLLOWING of a counted series ends it with UNTIL`() {
		val series = weekly("FREQ=WEEKLY;COUNT=5")
		deleteThis(series.id(), "2026-03-23T08:00:00Z")

		api.deleteEvent(series.id(), "?scope=FOLLOWING&occurrence=2026-03-16T08:00:00Z").andExpect { status { isNoContent() } }

		val master = api.event(series.id())
		master.text("rrule") shouldBe "FREQ=WEEKLY;UNTIL=20260316T075959Z"
		exdates(master).shouldBeEmpty()
		spring().starts() shouldContainExactly listOf("2026-03-02T08:00:00Z", "2026-03-09T08:00:00Z")
	}

	@Test
	fun `DELETE FOLLOWING of the first occurrence moves the whole series to the Trash`(events: ApplicationEvents) {
		val series = weekly()

		api.deleteEvent(series.id(), "?scope=FOLLOWING&occurrence=2026-03-02T08:00:00Z").andExpect { status { isNoContent() } }

		assertGone(series.id())
		spring().shouldBeEmpty()
		events.changes().last() shouldBe (ChangeOp.DELETED to series.id())
	}

	@Test
	fun `DELETE FOLLOWING of an all-day series ends it the day before`() {
		val gym = api.createEvent("""{"title":"Gym","allDay":true,"startDate":"2026-03-02","rrule":"FREQ=DAILY"}""")

		api.deleteEvent(gym.id(), "?scope=FOLLOWING&occurrence=2026-03-05").andExpect { status { isNoContent() } }

		api.event(gym.id()).text("rrule") shouldBe "FREQ=DAILY;UNTIL=20260304"
		spring().map { it.text("startDate") } shouldContainExactly listOf("2026-03-02", "2026-03-03", "2026-03-04")
	}

	// --- calendar moves

	@Test
	fun `PATCH ALL with a calendar moves the series with its overrides`() {
		val series = weekly()
		val override = api.patchEventOk(series.id(), """{"title":"Special"}""", "?scope=THIS&occurrence=2026-03-09T08:00:00Z")
		val work = api.createCalendar("""{"name":"Work"}""").id()

		api.patchEventOk(series.id(), """{"calendarId":"$work"}""").text("calendarId") shouldBe work

		api.event(override.id()).text("calendarId") shouldBe work
		spring("calendarIds" to work).map { it.text("calendarId") }.distinct() shouldContainExactly listOf(work)
		spring("calendarIds" to work).titles()[1] shouldBe "Special"
		spring("calendarIds" to calendarId).shouldBeEmpty()
	}

	@Test
	fun `PATCH FOLLOWING with a calendar moves only the new series and its overrides`() {
		val series = weekly()
		val early = api.patchEventOk(series.id(), """{"title":"Early"}""", "?scope=THIS&occurrence=2026-03-09T08:00:00Z")
		api.patchEventOk(series.id(), """{"title":"Late"}""", "?scope=THIS&occurrence=2026-04-06T07:00:00Z")
		val work = api.createCalendar("""{"name":"Work"}""").id()

		val next = api.patchEventOk(series.id(), """{"calendarId":"$work"}""", "?scope=FOLLOWING&occurrence=2026-03-23T08:00:00Z")

		next.text("calendarId") shouldBe work
		api.event(series.id()).text("calendarId") shouldBe calendarId
		api.event(early.id()).text("calendarId") shouldBe calendarId
		spring("calendarIds" to work).titles() shouldContainExactly listOf("Team meeting", "Team meeting", "Late", "Team meeting")
		spring("calendarIds" to calendarId).titles() shouldContainExactly listOf("Team meeting", "Early", "Team meeting")
	}

	@Test
	fun `a single occurrence cannot change its calendar, the same calendar is accepted`() {
		val series = weekly()
		val work = api.createCalendar("""{"name":"Work"}""").id()

		api.patchEvent(series.id(), """{"calendarId":"$work"}""", "?scope=THIS&occurrence=2026-03-09T08:00:00Z").isProblem(400, "calendarId")
		api
			.patchEventOk(series.id(), """{"calendarId":"$calendarId","title":"Same"}""", "?scope=THIS&occurrence=2026-03-09T08:00:00Z")
			.text("calendarId") shouldBe calendarId
	}

	@Test
	fun `a series cannot move to a read-only or unknown calendar`() {
		val series = weekly()
		val holidays = api.createCalendar("""{"name":"Holidays"}""").id()
		jdbc.update("update calendar set read_only = true where id = ?::uuid", holidays)

		api.patchEvent(series.id(), """{"calendarId":"$holidays"}""").isProblem(409)
		api.patchEvent(series.id(), """{"calendarId":"${java.util.UUID.randomUUID()}"}""").isProblem(400, "calendarId")
		api.event(series.id()).text("calendarId") shouldBe calendarId
	}

	// --- change events, search

	@Test
	fun `deletes of occurrences announce exactly the changed events`(events: ApplicationEvents) {
		val series = weekly()
		val override = api.patchEventOk(series.id(), """{"title":"Special"}""", "?scope=THIS&occurrence=2026-03-16T08:00:00Z")

		deleteThis(series.id(), "2026-03-09T08:00:00Z")
		api.deleteEvent(override.id()).andExpect { status { isNoContent() } }
		api.deleteEvent(series.id(), "?scope=FOLLOWING&occurrence=2026-03-16T08:00:00Z").andExpect { status { isNoContent() } }
		api.deleteEvent(series.id()).andExpect { status { isNoContent() } }

		events.changes() shouldContainExactly
			listOf(
				ChangeOp.CREATED to series.id(),
				ChangeOp.CREATED to override.id(),
				ChangeOp.UPDATED to series.id(),
				ChangeOp.UPDATED to override.id(),
				ChangeOp.DELETED to override.id(),
				ChangeOp.UPDATED to series.id(),
				ChangeOp.DELETED to series.id(),
			)
	}

	@Test
	fun `PATCH ALL of content announces and re-indexes the overrides it changed`(events: ApplicationEvents) {
		val series = weekly()
		val located = api.patchEventOk(series.id(), """{"location":"Room B"}""", "?scope=THIS&occurrence=2026-03-09T08:00:00Z")

		api.patchEventOk(series.id(), """{"title":"Weekly"}""")

		searchTitle(located.id()) shouldBe "Weekly"
		searchTitle(series.id()) shouldBe "Weekly"
		events.changes().takeLast(2) shouldContainExactly listOf(ChangeOp.UPDATED to located.id(), ChangeOp.UPDATED to series.id())
	}

	@Test
	fun `after a split the search finds the recreated overrides, not the old ones`() {
		val series = weekly()
		val planning = api.patchEventOk(series.id(), """{"title":"Planning"}""", "?scope=THIS&occurrence=2026-04-06T07:00:00Z")

		val next = api.patchEventOk(series.id(), """{"title":"Team sync"}""", "?scope=FOLLOWING&occurrence=2026-03-23T08:00:00Z")

		val recreated = spring().single { it.text("title") == "Planning" }.text("eventId")!!
		searchTitle(planning.id()) shouldBe null
		searchTitle(recreated) shouldBe "Planning"
		searchTitle(next.id()) shouldBe "Team sync"
		searchTitle(series.id()) shouldBe "Team meeting"
		searchedIds() shouldContainExactlyInAnyOrder listOf(series.id(), next.id(), recreated)
	}

	@Test
	fun `cancelled and removed overrides leave the search`() {
		val series = weekly()
		val cancelled = api.patchEventOk(series.id(), """{"title":"Cancelled"}""", "?scope=THIS&occurrence=2026-03-09T08:00:00Z")
		val later = api.patchEventOk(series.id(), """{"title":"Later"}""", "?scope=THIS&occurrence=2026-03-30T07:00:00Z")

		api.deleteEvent(cancelled.id()).andExpect { status { isNoContent() } }
		api.deleteEvent(series.id(), "?scope=FOLLOWING&occurrence=2026-03-23T08:00:00Z").andExpect { status { isNoContent() } }

		searchTitle(cancelled.id()) shouldBe null
		searchTitle(later.id()) shouldBe null
		searchedIds() shouldContainExactly listOf(series.id())
	}

	@Test
	fun `cancelling and un-cancelling the series with ALL does not bring back a deleted occurrence`() {
		val series = weekly()
		val removed = api.patchEventOk(series.id(), """{"title":"Removed"}""", "?scope=THIS&occurrence=2026-03-09T08:00:00Z")
		api.deleteEvent(removed.id()).andExpect { status { isNoContent() } }

		api.patchEventOk(series.id(), """{"status":"CANCELLED"}""")
		api.patchEventOk(series.id(), """{"status":"CONFIRMED"}""")

		api.event(removed.id()).text("status") shouldBe "CANCELLED"
		val occurrences = spring()
		occurrences.size shouldBe 6
		occurrences.none { it.text("occurrence") == "2026-03-09T08:00:00Z" } shouldBe true
		occurrences.forEach { it.text("status") shouldBe "CONFIRMED" }
		searchTitle(removed.id()) shouldBe null
	}

	@Test
	fun `un-cancelling the following occurrences does not bring back a deleted occurrence`() {
		val series = weekly()
		val removed = api.patchEventOk(series.id(), """{"title":"Removed"}""", "?scope=THIS&occurrence=2026-03-30T07:00:00Z")
		api.deleteEvent(removed.id()).andExpect { status { isNoContent() } }
		api.patchEventOk(series.id(), """{"status":"CANCELLED"}""")

		val next = api.patchEventOk(series.id(), """{"status":"CONFIRMED"}""", "?scope=FOLLOWING&occurrence=2026-03-23T08:00:00Z")

		next.text("status") shouldBe "CONFIRMED"
		val following = spring().filter { it.text("eventId") != series.id() }
		following.map { it.text("occurrence") } shouldContainExactly
			listOf("2026-03-23T08:00:00Z", "2026-04-06T07:00:00Z", "2026-04-13T07:00:00Z")
		following.forEach { it.text("status") shouldBe "CONFIRMED" }
		spring().none { it.text("title") == "Removed" } shouldBe true
		searchedIds() shouldContainExactlyInAnyOrder listOf(series.id(), next.id())
	}

	@Test
	fun `a failed occurrence change creates no override`() {
		val series = weekly()

		api
			.patchEvent(
				series.id(),
				"""{"reminderMinutes":[-1]}""",
				"?scope=THIS&occurrence=2026-03-09T08:00:00Z",
			).isProblem(400, "reminderMinutes")
		api.patchEvent(series.id(), """{"title":" "}""", "?scope=FOLLOWING&occurrence=2026-03-09T08:00:00Z").isProblem(400, "title")

		rowCount() shouldBe 1
		api.event(series.id()).text("rrule") shouldBe "FREQ=WEEKLY"
	}

	@Test
	fun `a THIS change racing another one for the same occurrence is a conflict`() {
		val series = weekly()
		val first = api.patchEventOk(series.id(), """{"title":"First tab"}""", "?scope=THIS&occurrence=2026-03-09T08:00:00Z")
		// Simulates the race: the other request's override exists in the database but this request did not see it
		// (the service looks up only overrides that are not deleted). Creating the override does not change the master,
		// so If-Match on the master still matches.
		jdbc.update("update calendar_event set deleted_at = now() where id = ?::uuid", first.id())

		api.patchEvent(series.id(), """{"title":"Second tab"}""", "?scope=THIS&occurrence=2026-03-09T08:00:00Z").isProblem(409)

		rowCount() shouldBe 2
	}

	// --- Trash

	@Test
	fun `a trashed series with overrides is listed once and restored with its overrides`() {
		val series = weekly()
		val special = api.patchEventOk(series.id(), """{"title":"Special","location":"Room S"}""", "?scope=THIS&occurrence=2026-03-16T08:00:00Z")
		api.deleteEvent(series.id()).andExpect { status { isNoContent() } }

		val trash =
			api.read(mockMvc.get("/api/v1/trash").andExpect { status { isOk() } }).get("items").values().filter {
				it.text("type") == "EVENT"
			}
		trash.map { it.text("id") to it.text("subtitle") } shouldContainExactly listOf(series.id() to "Mon 2 Mar 2026 (repeats)")
		searchedIds().shouldBeEmpty()
		mockMvc.post("/api/v1/trash/EVENT/${special.id()}/restore") { with(csrf()) }.andExpect { status { isNotFound() } }
		mockMvc.delete("/api/v1/trash/EVENT/${special.id()}") { with(csrf()) }.andExpect { status { isNotFound() } }

		mockMvc.post("/api/v1/trash/EVENT/${series.id()}/restore") { with(csrf()) }.andExpect { status { isNoContent() } }

		searchedIds() shouldContainExactlyInAnyOrder listOf(series.id(), special.id())
		searchTitle(special.id()) shouldBe "Special"
		spring().titles() shouldContainExactly
			listOf("Team meeting", "Team meeting", "Special", "Team meeting", "Team meeting", "Team meeting", "Team meeting")
		api.patchEventOk(special.id(), """{"title":"Special again"}""").text("title") shouldBe "Special again"
	}

	@Test
	fun `an override of a trashed series is not found`() {
		val series = weekly()
		val special = api.patchEventOk(series.id(), """{"title":"Special"}""", "?scope=THIS&occurrence=2026-03-16T08:00:00Z")

		api.deleteEvent(series.id()).andExpect { status { isNoContent() } }

		assertGone(special.id())
		api.patchEvent(special.id(), """{"title":"x"}""", ifMatch = "*").isProblem(404)
	}

	@Test
	fun `an override of a trashed series cannot be deleted and is reachable again after restore`(events: ApplicationEvents) {
		val series = weekly()
		val special = api.patchEventOk(series.id(), """{"title":"Special"}""", "?scope=THIS&occurrence=2026-03-16T08:00:00Z")
		val etag = CalendarApi.etag(special)
		api.deleteEvent(series.id()).andExpect { status { isNoContent() } }
		val announced = events.changes().size

		api.deleteEvent(special.id(), ifMatch = etag).isProblem(404)
		api.deleteEvent(special.id(), "?scope=ALL", ifMatch = etag).isProblem(404)
		api.patchEvent(special.id(), """{"title":"x"}""", "?scope=FOLLOWING", ifMatch = etag).isProblem(404)
		spring().shouldBeEmpty()
		events.changes().size shouldBe announced

		mockMvc.post("/api/v1/trash/EVENT/${series.id()}/restore") { with(csrf()) }.andExpect { status { isNoContent() } }

		api.event(special.id()).text("title") shouldBe "Special"
		api.deleteEvent(special.id()).andExpect { status { isNoContent() } }
		api.event(special.id()).text("status") shouldBe "CANCELLED"
		spring().size shouldBe 6
	}

	@Test
	fun `a trashed all-day series is listed with its first day`() {
		val gym = api.createEvent("""{"title":"Gym","allDay":true,"startDate":"2026-07-06","rrule":"FREQ=WEEKLY"}""")

		api.deleteEvent(gym.id()).andExpect { status { isNoContent() } }

		val item =
			api
				.read(mockMvc.get("/api/v1/trash").andExpect { status { isOk() } })
				.get("items")
				.values()
				.single { it.text("type") == "EVENT" }
		item.text("subtitle") shouldBe "Mon 6 Jul 2026 (repeats)"
	}
}
