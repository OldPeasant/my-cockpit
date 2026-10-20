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
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
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
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/**
 * Recurring series: `scope=THIS|FOLLOWING|ALL` edits and deletes. The weekly series used here is every Monday 09:00-10:00
 * in Europe/Zurich from 2 March 2026 (08:00Z; 07:00Z from 30 March, after the DST change).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MutableClockTestConfiguration::class)
@RecordApplicationEvents
@WithCockpitUser
class CalendarSeriesWebTests(
	@Autowired private val mockMvc: MockMvc,
	@Autowired jsonMapper: JsonMapper,
	@Autowired private val clock: MutableClock,
	@Autowired private val jdbc: JdbcTemplate,
) : PostgresTestBase() {
	private val api = CalendarApi(mockMvc, jsonMapper)

	@BeforeEach
	fun reset() {
		CalendarApi.resetCalendarTables(jdbc)
		clock.set(MutableClockTestConfiguration.START)
		api.createCalendar()
	}

	private fun weekly(rrule: String = "FREQ=WEEKLY"): JsonNode =
		api.createEvent(
			"""{"title":"Team meeting","start":"2026-03-02T08:00:00Z","end":"2026-03-02T09:00:00Z","timeZone":"Europe/Zurich","rrule":"$rrule"}""",
		)

	/** Occurrences from 1 March to 15 April 2026 (seven Mondays). */
	private fun spring(): List<JsonNode> = api.occurrences("2026-03-01T00:00:00Z", "2026-04-15T00:00:00Z")

	private fun List<JsonNode>.starts() = map { it.text("start") }

	private fun List<JsonNode>.titles() = map { it.text("title") }

	private fun rowCount() = jdbc.queryForObject("select count(*) from calendar_event", Int::class.java)

	@Test
	fun `a series is expanded at its wall-clock time across DST with series id and occurrence`() {
		val series = weekly()

		val occurrences = spring()

		occurrences.starts() shouldContainExactly
			listOf(
				"2026-03-02T08:00:00Z",
				"2026-03-09T08:00:00Z",
				"2026-03-16T08:00:00Z",
				"2026-03-23T08:00:00Z",
				"2026-03-30T07:00:00Z",
				"2026-04-06T07:00:00Z",
				"2026-04-13T07:00:00Z",
			)
		occurrences[1].text("eventId") shouldBe series.id()
		occurrences[1].text("seriesId") shouldBe series.id()
		occurrences[1].text("occurrence") shouldBe "2026-03-09T08:00:00Z"
		occurrences[1].text("end") shouldBe "2026-03-09T09:00:00Z"
	}

	// --- THIS

	@Test
	fun `PATCH THIS creates an override once and changes it afterwards`(events: ApplicationEvents) {
		val series = weekly()

		val override =
			api.patchEventOk(
				series.id(),
				"""{"title":"Team meeting (room B)","start":"2026-03-10T08:00:00Z"}""",
				"?scope=THIS&occurrence=2026-03-09T08:00:00Z",
			)
		override.id() shouldNotBe series.id()
		override.text("seriesId") shouldBe series.id()
		override.text("recurrenceId") shouldBe "2026-03-09T08:00:00Z"
		override.text("end") shouldBe "2026-03-10T09:00:00Z"

		// Addressed through the series again: the same override changes.
		val again = api.patchEventOk(series.id(), """{"location":"Room B"}""", "?scope=THIS&occurrence=2026-03-09T08:00:00Z")
		again.id() shouldBe override.id()
		again.text("title") shouldBe "Team meeting (room B)"
		// Addressed directly, an override stands for its occurrence.
		api.patchEventOk(override.id(), """{"title":"Moved meeting"}""").id() shouldBe override.id()

		val occurrences = spring()
		occurrences.starts().take(3) shouldContainExactly listOf("2026-03-02T08:00:00Z", "2026-03-10T08:00:00Z", "2026-03-16T08:00:00Z")
		occurrences[1].text("eventId") shouldBe override.id()
		occurrences[1].text("occurrence") shouldBe "2026-03-09T08:00:00Z"
		occurrences[1].text("title") shouldBe "Moved meeting"
		api.event(series.id()).get("version").asLong() shouldBe 0
		events.changes() shouldContainExactly
			listOf(
				ChangeOp.CREATED to series.id(),
				ChangeOp.CREATED to override.id(),
				ChangeOp.UPDATED to override.id(),
				ChangeOp.UPDATED to override.id(),
			)
	}

	@Test
	fun `PATCH THIS rejects series fields and needs a valid occurrence`() {
		val series = weekly()
		val id = series.id()

		api.patchEvent(id, """{"rrule":"FREQ=DAILY"}""", "?scope=THIS&occurrence=2026-03-09T08:00:00Z").isProblem(400, "rrule")
		api.patchEvent(id, """{"title":"x"}""", "?scope=THIS").isProblem(400, "occurrence")
		api.patchEvent(id, """{"title":"x"}""", "?scope=FOLLOWING").isProblem(400, "occurrence")
		api.patchEvent(id, """{"title":"x"}""", "?scope=THIS&occurrence=2026-03-09").isProblem(400, "occurrence")
		api.patchEvent(id, """{"title":"x"}""", "?scope=THIS&occurrence=2026-03-10T08:00:00Z").isProblem(404)
		api.patchEvent(id, """{"title":"x"}""", "?scope=THIS&occurrence=2026-02-23T08:00:00Z").isProblem(404)
		val override = api.patchEventOk(id, """{"title":"x"}""", "?scope=THIS&occurrence=2026-03-09T08:00:00Z")
		api.patchEvent(override.id(), """{"title":"y"}""", "?occurrence=2026-03-16T08:00:00Z").isProblem(400, "occurrence")
	}

	@Test
	fun `the occurrence may be given with an offset`() {
		val series = weekly()

		val override =
			api.read(
				mockMvc
					.patch("${CalendarApi.EVENTS}/${series.id()}") {
						with(csrf())
						contentType = MediaType.APPLICATION_JSON
						content = """{"title":"x"}"""
						header(HttpHeaders.IF_MATCH, "*")
						param("scope", "THIS")
						param("occurrence", "2026-03-09T09:00:00+01:00")
					}.andExpect { status { isOk() } },
			)

		override.text("recurrenceId") shouldBe "2026-03-09T08:00:00Z"
	}

	@Test
	fun `DELETE THIS excludes a generated occurrence and cancels an override`() {
		val series = weekly()
		val override = api.patchEventOk(series.id(), """{"title":"Special"}""", "?scope=THIS&occurrence=2026-03-16T08:00:00Z")

		api.deleteEvent(series.id(), "?scope=THIS&occurrence=2026-03-09T08:00:00Z").andExpect { status { isNoContent() } }
		api.deleteEvent(override.id()).andExpect { status { isNoContent() } }

		spring().starts().take(2) shouldContainExactly listOf("2026-03-02T08:00:00Z", "2026-03-23T08:00:00Z")
		api
			.event(series.id())
			.get("exdates")
			.values()
			.map { it.asString() } shouldContainExactly listOf("2026-03-09T08:00:00Z")
		api.event(override.id()).text("status") shouldBe "CANCELLED"
		// Excluded occurrences are no occurrences any more.
		api.patchEvent(series.id(), """{"title":"x"}""", "?scope=THIS&occurrence=2026-03-09T08:00:00Z").isProblem(404)
	}

	@Test
	fun `DELETE THIS of the first occurrence excludes it`() {
		val series = weekly()

		api.deleteEvent(series.id(), "?scope=THIS&occurrence=2026-03-02T08:00:00Z").andExpect { status { isNoContent() } }

		spring().starts().first() shouldBe "2026-03-09T08:00:00Z"
	}

	// --- FOLLOWING

	@Test
	fun `PATCH FOLLOWING splits the series and moves later exceptions to the new one`(events: ApplicationEvents) {
		val series = weekly()
		val early = api.patchEventOk(series.id(), """{"title":"Kick-off"}""", "?scope=THIS&occurrence=2026-03-09T08:00:00Z")
		val late = api.patchEventOk(series.id(), """{"location":"Room C"}""", "?scope=THIS&occurrence=2026-04-06T07:00:00Z")
		api.deleteEvent(series.id(), "?scope=THIS&occurrence=2026-04-13T07:00:00Z").andExpect { status { isNoContent() } }

		// From 23 March: 10:00 local and a new title.
		val next =
			api.patchEventOk(
				series.id(),
				"""{"title":"Team sync","start":"2026-03-23T09:00:00Z"}""",
				"?scope=FOLLOWING&occurrence=2026-03-23T08:00:00Z",
			)

		next.id() shouldNotBe series.id()
		next.text("rrule") shouldBe "FREQ=WEEKLY"
		next.text("start") shouldBe "2026-03-23T09:00:00Z"
		next.text("end") shouldBe "2026-03-23T10:00:00Z"
		next.get("exdates").values().map { it.asString() } shouldContainExactly listOf("2026-04-13T08:00:00Z")
		val old = api.event(series.id())
		old.text("rrule") shouldBe "FREQ=WEEKLY;UNTIL=20260323T075959Z"
		old.get("exdates").size() shouldBe 0

		val occurrences = api.occurrences("2026-03-01T00:00:00Z", "2026-04-22T00:00:00Z")
		occurrences.starts() shouldContainExactly
			listOf(
				"2026-03-02T08:00:00Z",
				"2026-03-09T08:00:00Z",
				"2026-03-16T08:00:00Z",
				"2026-03-23T09:00:00Z",
				"2026-03-30T08:00:00Z",
				"2026-04-06T08:00:00Z",
				"2026-04-20T08:00:00Z",
			)
		occurrences.titles() shouldContainExactly
			listOf("Team meeting", "Kick-off", "Team meeting", "Team sync", "Team sync", "Team sync", "Team sync")
		occurrences[1].text("eventId") shouldBe early.id()
		// The later override now belongs to the new series (recreated), moved along and took the new title.
		val moved = occurrences[5]
		moved.text("seriesId") shouldBe next.id()
		moved.text("occurrence") shouldBe "2026-04-06T08:00:00Z"
		api.event(moved.text("eventId")!!).text("location") shouldBe "Room C"
		mockMvc.get("${CalendarApi.EVENTS}/${late.id()}").andExpect { status { isNotFound() } }
		events.changes().takeLast(4) shouldContainExactly
			listOf(
				ChangeOp.UPDATED to series.id(),
				ChangeOp.DELETED to late.id(),
				ChangeOp.CREATED to moved.text("eventId")!!,
				ChangeOp.CREATED to next.id(),
			)
	}

	@Test
	fun `PATCH FOLLOWING of a counted series keeps the number of occurrences`() {
		val series = weekly("FREQ=WEEKLY;COUNT=6")

		val next = api.patchEventOk(series.id(), """{"title":"Later"}""", "?scope=FOLLOWING&occurrence=2026-03-16T08:00:00Z")

		next.text("rrule") shouldBe "FREQ=WEEKLY;COUNT=4"
		api.event(series.id()).text("rrule") shouldBe "FREQ=WEEKLY;UNTIL=20260316T075959Z"
		spring().titles() shouldContainExactly listOf("Team meeting", "Team meeting", "Later", "Later", "Later", "Later")
	}

	@Test
	fun `PATCH FOLLOWING of the first occurrence changes the whole series`() {
		val series = weekly()

		val result = api.patchEventOk(series.id(), """{"title":"Renamed"}""", "?scope=FOLLOWING&occurrence=2026-03-02T08:00:00Z")

		result.id() shouldBe series.id()
		rowCount() shouldBe 1
		spring().titles().distinct() shouldContainExactly listOf("Renamed")
	}

	@Test
	fun `DELETE FOLLOWING ends the series before the occurrence and removes later overrides`() {
		val series = weekly()
		val late = api.patchEventOk(series.id(), """{"title":"Special"}""", "?scope=THIS&occurrence=2026-03-30T07:00:00Z")

		api.deleteEvent(series.id(), "?scope=FOLLOWING&occurrence=2026-03-16T08:00:00Z").andExpect { status { isNoContent() } }

		spring().starts() shouldContainExactly listOf("2026-03-02T08:00:00Z", "2026-03-09T08:00:00Z")
		mockMvc.get("${CalendarApi.EVENTS}/${late.id()}").andExpect { status { isNotFound() } }
		rowCount() shouldBe 1
	}

	// --- ALL

	@Test
	fun `PATCH ALL with an occurrence moves the series like that occurrence, with its exceptions`() {
		val series = weekly()
		api.deleteEvent(series.id(), "?scope=THIS&occurrence=2026-03-09T08:00:00Z").andExpect { status { isNoContent() } }
		val titled = api.patchEventOk(series.id(), """{"title":"Retro"}""", "?scope=THIS&occurrence=2026-03-23T08:00:00Z")
		api.patchEventOk(series.id(), """{"start":"2026-03-31T12:00:00Z"}""", "?scope=THIS&occurrence=2026-03-30T07:00:00Z")

		// The occurrence of 16 March is dragged from 09:00 to 10:30 (local), 30 minutes long.
		val result =
			api.patchEventOk(
				series.id(),
				"""{"start":"2026-03-16T09:30:00Z","end":"2026-03-16T10:00:00Z"}""",
				"?scope=ALL&occurrence=2026-03-16T08:00:00Z",
			)

		result.id() shouldBe series.id()
		result.text("start") shouldBe "2026-03-02T09:30:00Z"
		result.text("end") shouldBe "2026-03-02T10:00:00Z"
		result.get("exdates").values().map { it.asString() } shouldContainExactly listOf("2026-03-09T09:30:00Z")
		val occurrences = spring()
		occurrences.starts() shouldContainExactly
			listOf(
				"2026-03-02T09:30:00Z",
				"2026-03-16T09:30:00Z",
				"2026-03-23T09:30:00Z",
				"2026-03-31T12:00:00Z",
				"2026-04-06T08:30:00Z",
				"2026-04-13T08:30:00Z",
			)
		occurrences[2].text("title") shouldBe "Retro"
		occurrences[2].text("end") shouldBe "2026-03-23T10:00:00Z"
		occurrences[2].text("occurrence") shouldBe "2026-03-23T09:30:00Z"
		occurrences[2].text("eventId") shouldNotBe titled.id()
		occurrences[3].text("occurrence") shouldBe "2026-03-30T08:30:00Z"
	}

	@Test
	fun `PATCH ALL passes changed fields on to overrides that had the old value`() {
		val series = weekly()
		val located = api.patchEventOk(series.id(), """{"location":"Room B"}""", "?scope=THIS&occurrence=2026-03-09T08:00:00Z")
		val titled = api.patchEventOk(series.id(), """{"title":"Retro"}""", "?scope=THIS&occurrence=2026-03-16T08:00:00Z")

		api.patchEventOk(located.id(), """{"title":"Weekly","location":"Room A"}""", "?scope=ALL")

		api.event(located.id()).let {
			it.text("title") shouldBe "Weekly"
			it.text("location") shouldBe "Room B"
		}
		api.event(titled.id()).let {
			it.text("title") shouldBe "Retro"
			it.text("location") shouldBe "Room A"
		}
		spring().titles() shouldContainExactly listOf("Weekly", "Weekly", "Retro", "Weekly", "Weekly", "Weekly", "Weekly")
	}

	@Test
	fun `PATCH ALL with a new rule keeps the exceptions that are still occurrences`() {
		val series = weekly()
		val kept = api.patchEventOk(series.id(), """{"title":"Kept"}""", "?scope=THIS&occurrence=2026-03-16T08:00:00Z")
		val dropped = api.patchEventOk(series.id(), """{"title":"Dropped"}""", "?scope=THIS&occurrence=2026-03-23T08:00:00Z")
		api.deleteEvent(series.id(), "?scope=THIS&occurrence=2026-03-09T08:00:00Z").andExpect { status { isNoContent() } }

		api.patchEventOk(series.id(), """{"rrule":"FREQ=WEEKLY;INTERVAL=2"}""")

		spring().titles() shouldContainExactly listOf("Team meeting", "Kept", "Team meeting", "Team meeting")
		api.event(kept.id()).text("title") shouldBe "Kept"
		mockMvc.get("${CalendarApi.EVENTS}/${dropped.id()}").andExpect { status { isNotFound() } }
		api.event(series.id()).get("exdates").size() shouldBe 0

		api.patchEventOk(series.id(), """{"rrule":null}""").text("rrule") shouldBe null
		rowCount() shouldBe 1
		spring().size shouldBe 1
	}

	@Test
	fun `DELETE through an override with scope ALL moves the whole series to the Trash`() {
		val series = weekly()
		val override = api.patchEventOk(series.id(), """{"title":"Special"}""", "?scope=THIS&occurrence=2026-03-16T08:00:00Z")

		api.deleteEvent(override.id(), "?scope=ALL").andExpect { status { isNoContent() } }

		spring().size shouldBe 0
		mockMvc.get("${CalendarApi.EVENTS}/${series.id()}").andExpect { status { isNotFound() } }
		jdbc.queryForObject("select count(*) from search_document where item_type = 'EVENT'", Int::class.java) shouldBe 0
	}

	// --- all-day series

	@Test
	fun `all-day series use dates as occurrences`() {
		val series =
			api.createEvent("""{"title":"Gym","allDay":true,"startDate":"2026-03-02","rrule":"FREQ=DAILY;COUNT=5"}""")

		val override = api.patchEventOk(series.id(), """{"startDate":"2026-03-08"}""", "?scope=THIS&occurrence=2026-03-03")
		override.text("recurrenceId") shouldBe "2026-03-03"
		override.text("endDate") shouldBe "2026-03-09"
		val next = api.patchEventOk(series.id(), """{"title":"Run"}""", "?scope=FOLLOWING&occurrence=2026-03-05")

		next.text("rrule") shouldBe "FREQ=DAILY;COUNT=2"
		api.event(series.id()).text("rrule") shouldBe "FREQ=DAILY;UNTIL=20260304"
		api.patchEvent(series.id(), """{"title":"x"}""", "?scope=THIS&occurrence=2026-03-02T00:00:00Z").isProblem(400, "occurrence")
		spring().map { it.text("startDate") to it.text("title") } shouldContainExactly
			listOf("2026-03-02" to "Gym", "2026-03-04" to "Gym", "2026-03-05" to "Run", "2026-03-06" to "Run", "2026-03-08" to "Gym")
	}
}
