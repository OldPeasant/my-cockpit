package ch.sonensei.cockpit.calendar.api

import ch.sonensei.cockpit.calendar.api.CalendarApi.Companion.etag
import ch.sonensei.cockpit.calendar.api.CalendarApi.Companion.id
import ch.sonensei.cockpit.calendar.api.CalendarApi.Companion.isProblem
import ch.sonensei.cockpit.calendar.api.CalendarApi.Companion.text
import ch.sonensei.cockpit.core.auth.WithCockpitUser
import ch.sonensei.cockpit.core.persistence.MutableClock
import ch.sonensei.cockpit.core.persistence.MutableClockTestConfiguration
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.event.RecordApplicationEvents
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/**
 * Request handling around series that the other calendar API tests leave out: If-Match of the addressed event for
 * series scopes, malformed query parameters, unknown and trashed events, overrides moved across range borders and
 * `FOLLOWING` addressed through an override. The weekly series is every Monday 09:00-10:00 in Europe/Zurich from
 * 2 March 2026 (08:00Z; 07:00Z from 30 March, after the DST change).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MutableClockTestConfiguration::class)
@RecordApplicationEvents
@WithCockpitUser
class CalendarSeriesRequestWebTests(
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

	private fun exdates(event: JsonNode) = event.get("exdates").values().map { it.asString() }

	private fun rowCount() = jdbc.queryForObject("select count(*) from calendar_event", Int::class.java)

	// --- If-Match for series scopes

	@Test
	fun `series changes and deletes check the If-Match of the addressed event and change nothing on a mismatch`() {
		val series = weekly()
		// Version 1 for the master; the override created afterwards starts at version 0.
		val master = api.patchEventOk(series.id(), """{"location":"Room A"}""")
		master.get("version").asLong() shouldBe 1
		val override = api.patchEventOk(series.id(), """{"title":"Special"}""", "?scope=THIS&occurrence=2026-03-16T08:00:00Z")
		override.get("version").asLong() shouldBe 0
		val stale = "\"0\""

		api.patchEvent(series.id(), """{"title":"x"}""", "?scope=ALL", ifMatch = stale).isProblem(412)
		api.patchEvent(series.id(), """{"title":"x"}""", "?scope=THIS&occurrence=2026-03-09T08:00:00Z", ifMatch = stale).isProblem(412)
		api.patchEvent(series.id(), """{"title":"x"}""", "?scope=FOLLOWING&occurrence=2026-03-23T08:00:00Z", ifMatch = stale).isProblem(412)
		api.deleteEvent(series.id(), "?scope=THIS&occurrence=2026-03-09T08:00:00Z", ifMatch = stale).isProblem(412)
		api.deleteEvent(series.id(), "?scope=FOLLOWING&occurrence=2026-03-23T08:00:00Z", ifMatch = null).isProblem(428)
		// An override is checked against its own version, not against its master's.
		api.patchEvent(override.id(), """{"title":"x"}""", ifMatch = etag(master)).isProblem(412)
		api.deleteEvent(override.id(), "?scope=ALL", ifMatch = etag(master)).isProblem(412)

		val unchanged = api.event(series.id())
		unchanged.get("version").asLong() shouldBe 1
		unchanged.text("title") shouldBe "Team meeting"
		unchanged.text("rrule") shouldBe "FREQ=WEEKLY"
		exdates(unchanged).shouldBeEmpty()
		api.event(override.id()).text("title") shouldBe "Special"
		rowCount() shouldBe 2

		api.patchEvent(override.id(), """{"title":"Moved"}""", ifMatch = etag(override)).andExpect { status { isOk() } }
	}

	// --- malformed parameters

	@Test
	fun `an unknown scope and malformed range parameters are validation problems`() {
		val series = weekly()

		api.patchEvent(series.id(), """{"title":"x"}""", "?scope=SOMETIMES").isProblem(400, "scope")
		api.deleteEvent(series.id(), "?scope=NEVER&occurrence=2026-03-09T08:00:00Z").isProblem(400, "scope")
		mockMvc
			.get(CalendarApi.EVENTS) {
				param("from", "2026-03-01T00:00:00Z")
				param("to", "2026-04-01T00:00:00Z")
				param("calendarIds", "not-a-uuid")
			}.andExpect {
				status { isBadRequest() }
				content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
				jsonPath("$.errors[?(@.field == 'calendarIds')]") { isNotEmpty() }
			}
		mockMvc
			.get(CalendarApi.EVENTS) {
				param("from", "1 March 2026")
				param("to", "2026-04-01T00:00:00Z")
			}.andExpect {
				status { isBadRequest() }
				content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
				jsonPath("$.errors[?(@.field == 'from')]") { isNotEmpty() }
			}
		api.event(series.id()).text("title") shouldBe "Team meeting"
	}

	@Test
	fun `the calendarIds parameter may be repeated or comma-separated`() {
		val calendars = api.read(mockMvc.get(CalendarApi.CALENDARS))
		val first = calendars.get("items").values().single()
		val second = api.createCalendar("""{"name":"Club"}""")
		api.createEvent("""{"title":"Dentist","start":"2026-03-03T08:00:00Z"}""")
		api.createEvent("""{"title":"Training","start":"2026-03-04T17:00:00Z","calendarId":"${second.id()}"}""")
		val from = "2026-03-01T00:00:00Z"
		val to = "2026-03-08T00:00:00Z"

		api.occurrences(from, to, "calendarIds" to "${first.id()},${second.id()}").map { it.text("title") } shouldContainExactly
			listOf("Dentist", "Training")
		api.occurrences(from, to, "calendarIds" to first.id(), "calendarIds" to second.id()).size shouldBe 2
		api.occurrences(from, to, "calendarIds" to second.id()).map { it.text("title") } shouldContainExactly listOf("Training")
		api.occurrences(from, to, "calendarIds" to UUID.randomUUID().toString()).shouldBeEmpty()
	}

	// --- unknown and trashed events

	@Test
	fun `unknown and trashed events cannot be changed or deleted`() {
		val unknown = UUID.randomUUID().toString()
		api.patchEvent(unknown, """{"title":"x"}""", ifMatch = "*").isProblem(404)
		api.deleteEvent(unknown, ifMatch = "*").isProblem(404)

		val series = weekly()
		api.deleteEvent(series.id()).andExpect { status { isNoContent() } }

		api.patchEvent(series.id(), """{"title":"x"}""", "?scope=THIS&occurrence=2026-03-09T08:00:00Z", ifMatch = "*").isProblem(404)
		api.deleteEvent(series.id(), "?scope=FOLLOWING&occurrence=2026-03-09T08:00:00Z", ifMatch = "*").isProblem(404)
		api.deleteEvent(series.id(), ifMatch = "*").isProblem(404)
	}

	// --- range expansion with moved overrides

	@Test
	fun `an override moved out of its series' span is listed where it now is, not where it was`() {
		val series = weekly("FREQ=WEEKLY;COUNT=2")
		val moved = api.patchEventOk(series.id(), """{"start":"2026-04-20T07:00:00Z"}""", "?scope=THIS&occurrence=2026-03-09T08:00:00Z")

		val april = api.occurrences("2026-04-15T00:00:00Z", "2026-04-25T00:00:00Z")
		april.map { it.text("eventId") } shouldContainExactly listOf(moved.id())
		april.single().text("seriesId") shouldBe series.id()
		april.single().text("occurrence") shouldBe "2026-03-09T08:00:00Z"
		april.single().text("start") shouldBe "2026-04-20T07:00:00Z"
		api.occurrences("2026-03-08T00:00:00Z", "2026-03-10T00:00:00Z").shouldBeEmpty()
	}

	// --- FOLLOWING through an override

	@Test
	fun `DELETE FOLLOWING through an override ends the series before its occurrence`() {
		val series = weekly()
		val override = api.patchEventOk(series.id(), """{"title":"Special"}""", "?scope=THIS&occurrence=2026-03-16T08:00:00Z")

		api.deleteEvent(override.id(), "?scope=FOLLOWING").andExpect { status { isNoContent() } }

		api.event(series.id()).text("rrule") shouldBe "FREQ=WEEKLY;UNTIL=20260316T075959Z"
		mockMvc.get("${CalendarApi.EVENTS}/${override.id()}").andExpect { status { isNotFound() } }
		api.occurrences("2026-03-01T00:00:00Z", "2026-04-15T00:00:00Z").map { it.text("start") } shouldContainExactly
			listOf("2026-03-02T08:00:00Z", "2026-03-09T08:00:00Z")
	}

	@Test
	fun `PATCH FOLLOWING through an override splits the series there`() {
		val series = weekly()
		val override = api.patchEventOk(series.id(), """{"location":"Room B"}""", "?scope=THIS&occurrence=2026-03-16T08:00:00Z")

		val next = api.patchEventOk(override.id(), """{"title":"Team sync"}""", "?scope=FOLLOWING")

		next.text("start") shouldBe "2026-03-16T08:00:00Z"
		api.event(series.id()).text("rrule") shouldBe "FREQ=WEEKLY;UNTIL=20260316T075959Z"
		val occurrences = api.occurrences("2026-03-01T00:00:00Z", "2026-03-31T00:00:00Z")
		occurrences.map { it.text("title") } shouldContainExactly listOf("Team meeting", "Team meeting", "Team sync", "Team sync", "Team sync")
		// The addressed override belongs to the new series now and keeps its own location.
		occurrences[2].text("seriesId") shouldBe next.id()
		api.event(occurrences[2].text("eventId")!!).text("location") shouldBe "Room B"
	}
}
