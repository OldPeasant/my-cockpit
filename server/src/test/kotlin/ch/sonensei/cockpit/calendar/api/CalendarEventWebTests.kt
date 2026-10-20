package ch.sonensei.cockpit.calendar.api

import ch.sonensei.cockpit.calendar.api.CalendarApi.Companion.EVENTS
import ch.sonensei.cockpit.calendar.api.CalendarApi.Companion.changes
import ch.sonensei.cockpit.calendar.api.CalendarApi.Companion.id
import ch.sonensei.cockpit.calendar.api.CalendarApi.Companion.isProblem
import ch.sonensei.cockpit.calendar.api.CalendarApi.Companion.text
import ch.sonensei.cockpit.calendar.model.Calendar
import ch.sonensei.cockpit.calendar.model.CalendarRepository
import ch.sonensei.cockpit.calendar.model.CalendarSource
import ch.sonensei.cockpit.calendar.model.CalendarSourceRepository
import ch.sonensei.cockpit.calendar.model.CalendarSourceType
import ch.sonensei.cockpit.core.auth.WithCockpitUser
import ch.sonensei.cockpit.core.events.domain.ChangeOp
import ch.sonensei.cockpit.core.persistence.MutableClock
import ch.sonensei.cockpit.core.persistence.MutableClockTestConfiguration
import ch.sonensei.cockpit.core.persistence.UuidV7
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
import org.springframework.http.HttpHeaders
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

/** Single events: create, read, change, delete, the range query, search and Trash. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MutableClockTestConfiguration::class)
@RecordApplicationEvents
@WithCockpitUser
class CalendarEventWebTests(
	@Autowired private val mockMvc: MockMvc,
	@Autowired private val jsonMapper: JsonMapper,
	@Autowired private val sources: CalendarSourceRepository,
	@Autowired private val calendars: CalendarRepository,
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

	private fun dentist(): JsonNode =
		api.createEvent("""{"title":"Dentist","start":"2026-03-02T13:00:00Z","end":"2026-03-02T14:00:00Z","location":"Main street 1"}""")

	private fun searchTitle(id: String): String? =
		jdbc
			.query("select title from search_document where item_type = 'EVENT' and item_id = ?::uuid", { rs, _ -> rs.getString(1) }, id)
			.singleOrNull()

	// --- create and read

	@Test
	fun `POST creates a timed event in the default calendar and user zone, indexes and announces it`(events: ApplicationEvents) {
		val result =
			api
				.postJson(EVENTS, """{"title":"Dentist","start":"2026-03-02T13:00:00Z","description":"Check-up"}""")
				.andExpect {
					status { isCreated() }
					header { string(HttpHeaders.ETAG, "\"0\"") }
					jsonPath("$.calendarId") { value(calendarId) }
					jsonPath("$.allDay") { value(false) }
					jsonPath("$.start") { value("2026-03-02T13:00:00Z") }
					jsonPath("$.end") { value("2026-03-02T14:00:00Z") }
					jsonPath("$.timeZone") { value("Europe/Zurich") }
					jsonPath("$.status") { value("CONFIRMED") }
					jsonPath("$.transparency") { value("OPAQUE") }
					jsonPath("$.syncState") { value("LOCAL") }
					jsonPath("$.exdates.length()") { value(0) }
				}.andReturn()
		val id = jsonMapper.readTree(result.response.contentAsString).id()

		result.response.getHeader(HttpHeaders.LOCATION) shouldBe "http://localhost$EVENTS/$id"
		api.event(id).text("description") shouldBe "Check-up"
		searchTitle(id) shouldBe "Dentist"
		events.changes() shouldContainExactly listOf(ChangeOp.CREATED to id)
	}

	@Test
	fun `POST creates an all-day event with every field`() {
		val event =
			api.createEvent(
				"""
				{"calendarId":"$calendarId","title":"Holidays","allDay":true,"startDate":"2026-07-06","endDate":"2026-07-11",
				 "attendees":[{"email":"anna@example.com","name":"Anna"}],"reminderMinutes":[1440],"status":"TENTATIVE",
				 "transparency":"TRANSPARENT","conferenceUrl":"https://meet.example.com/x","rrule":"RRULE:FREQ=YEARLY"}
				""".trimIndent(),
			)

		event.get("allDay").asBoolean() shouldBe true
		event.text("startDate") shouldBe "2026-07-06"
		event.text("endDate") shouldBe "2026-07-11"
		event.text("start") shouldBe null
		event.text("rrule") shouldBe "FREQ=YEARLY"
		event.get("attendees")[0].text("status") shouldBe "NEEDS_ACTION"
		event.get("reminderMinutes")[0].asInt() shouldBe 1440
		event.text("transparency") shouldBe "TRANSPARENT"
	}

	@Test
	fun `POST with a client id is idempotent and refuses an id in the Trash`() {
		val id = UuidV7.generate().toString()
		val body = """{"id":"$id","title":"Dentist","start":"2026-03-02T13:00:00Z"}"""
		api.postJson(EVENTS, body).andExpect { status { isCreated() } }
		api.postJson(EVENTS, body.replace("Dentist", "Other")).andExpect {
			status { isOk() }
			jsonPath("$.title") { value("Dentist") }
		}
		api.deleteEvent(id).andExpect { status { isNoContent() } }

		api.postJson(EVENTS, body).isProblem(409)
		api.postJson(EVENTS, """{"id":"${java.util.UUID.randomUUID()}","title":"x","start":"2026-03-02T13:00:00Z"}""").isProblem(400, "id")
	}

	@Test
	fun `POST rejects invalid fields`() {
		api.postJson(EVENTS, """{"title":"x"}""").isProblem(400, "start")
		api.postJson(EVENTS, """{"title":"x","allDay":true}""").isProblem(400, "startDate")
		api.postJson(EVENTS, """{"title":"x","start":"2026-03-02T13:00:00Z","end":"2026-03-02T12:00:00Z"}""").isProblem(400, "end")
		api.postJson(EVENTS, """{"title":"x","start":"2026-03-02T13:00:00Z","timeZone":"Mars/Base"}""").isProblem(400, "timeZone")
		api.postJson(EVENTS, """{"title":"x","start":"2026-03-02T13:00:00Z","startDate":"2026-03-02"}""").isProblem(400, "startDate")
		api.postJson(EVENTS, """{"title":"x","allDay":true,"startDate":"2026-03-02","endDate":"2026-03-02"}""").isProblem(400, "endDate")
		api.postJson(EVENTS, """{"title":"x","start":"2026-03-02T13:00:00Z","rrule":"FREQ=SOMETIMES"}""").isProblem(400, "rrule")
		api
			.postJson(EVENTS, """{"title":" ","start":"2026-03-02T13:00:00Z","conferenceUrl":"javascript:alert(1)"}""")
			.isProblem(400, "title", "conferenceUrl")
		api
			.postJson(EVENTS, """{"title":"x","start":"2026-03-02T13:00:00Z","attendees":[{"email":"nobody"}],"reminderMinutes":[-5]}""")
			.isProblem(400, "attendees[0].email", "reminderMinutes")
		api
			.postJson(EVENTS, """{"title":"x","start":"2026-03-02T13:00:00Z","calendarId":"${java.util.UUID.randomUUID()}"}""")
			.isProblem(400, "calendarId")
	}

	@Test
	fun `POST without a calendar needs a default calendar`() {
		jdbc.update("update calendar set is_default = false")
		api.postJson(EVENTS, """{"title":"x","start":"2026-03-02T13:00:00Z"}""").isProblem(400, "calendarId")
	}

	@Test
	fun `events of read-only and external calendars cannot be created`() {
		val readOnly = calendars.save(Calendar(sources.findAll().single().id, "Holidays CH").apply { readOnly = true })
		val google = sources.save(CalendarSource(CalendarSourceType.GOOGLE, "Work account"))
		val external = calendars.save(Calendar(google.id, "Work"))

		api.postJson(EVENTS, """{"title":"x","start":"2026-03-02T13:00:00Z","calendarId":"${readOnly.id}"}""").isProblem(409)
		api.postJson(EVENTS, """{"title":"x","start":"2026-03-02T13:00:00Z","calendarId":"${external.id}"}""").isProblem(409)
	}

	@Test
	fun `GET of an unknown event is 404`() {
		mockMvc.get("$EVENTS/${UuidV7.generate()}").andExpect { status { isNotFound() } }
	}

	// --- change and delete

	@Test
	fun `PATCH changes the given fields, keeps the duration when only the start moves, and re-indexes`(events: ApplicationEvents) {
		val event = dentist()

		val patched = api.patchEventOk(event.id(), """{"title":"Dentist (moved)","start":"2026-03-03T09:00:00Z","location":null}""")

		patched.text("start") shouldBe "2026-03-03T09:00:00Z"
		patched.text("end") shouldBe "2026-03-03T10:00:00Z"
		patched.text("location") shouldBe null
		patched.get("version").asLong() shouldBe 1
		searchTitle(event.id()) shouldBe "Dentist (moved)"
		events.changes() shouldContainExactly listOf(ChangeOp.CREATED to event.id(), ChangeOp.UPDATED to event.id())
	}

	@Test
	fun `PATCH turns a timed event into an all-day one on the day of its start and back`() {
		val event = dentist()

		val allDay = api.patchEventOk(event.id(), """{"allDay":true}""")
		allDay.text("startDate") shouldBe "2026-03-02"
		allDay.text("endDate") shouldBe "2026-03-03"
		allDay.text("timeZone") shouldBe null

		api.patchEvent(event.id(), """{"allDay":false}""").isProblem(400, "start")
		val timed = api.patchEventOk(event.id(), """{"allDay":false,"start":"2026-03-02T15:00:00Z"}""")
		timed.text("end") shouldBe "2026-03-02T16:00:00Z"
	}

	@Test
	fun `PATCH can make a single event a series and move it to another calendar`() {
		val event = dentist()
		val other = api.createCalendar("""{"name":"Health"}""").id()

		val patched = api.patchEventOk(event.id(), """{"rrule":"FREQ=MONTHLY","calendarId":"$other"}""")

		patched.text("rrule") shouldBe "FREQ=MONTHLY"
		patched.text("calendarId") shouldBe other
		api.occurrences("2026-03-01T00:00:00Z", "2026-06-01T00:00:00Z").size shouldBe 3
	}

	@Test
	fun `PATCH needs If-Match and rejects unknown, null and invalid fields`() {
		val event = dentist()
		api.patchEvent(event.id(), """{"title":"x"}""", ifMatch = null).isProblem(428)
		api.patchEvent(event.id(), """{"title":"x"}""", ifMatch = "\"9\"").isProblem(412)
		api.patchEvent(event.id(), """{"seriesId":"x","version":3}""").isProblem(400, "seriesId", "version")
		api.patchEvent(event.id(), """{"title":null,"start":null}""").isProblem(400, "title", "start")
		api.patchEvent(event.id(), """{"start":"tomorrow"}""").isProblem(400, "start")
		api.patchEvent(event.id(), """{"title":"x"}""", query = "?occurrence=2026-03-02T13:00:00Z").isProblem(400, "occurrence")
		api.patchEvent(event.id(), """{"title":"x"}""", query = "?scope=SOMETIMES").isProblem(400)
	}

	@Test
	fun `changes to events of a calendar that became read-only are refused`() {
		val event = dentist()
		jdbc.update("update calendar set read_only = true")

		api.patchEvent(event.id(), """{"title":"x"}""").isProblem(409)
		api.deleteEvent(event.id()).isProblem(409)
	}

	@Test
	fun `DELETE moves the event to the Trash, restore brings it back`(events: ApplicationEvents) {
		val event = dentist()

		api.deleteEvent(event.id(), ifMatch = null).isProblem(428)
		api.deleteEvent(event.id()).andExpect { status { isNoContent() } }

		mockMvc.get("$EVENTS/${event.id()}").andExpect { status { isNotFound() } }
		searchTitle(event.id()) shouldBe null
		api.occurrences("2026-03-01T00:00:00Z", "2026-03-08T00:00:00Z").shouldBeEmpty()
		val trash =
			api.read(mockMvc.get("/api/v1/trash").andExpect { status { isOk() } }).get("items").values().filter {
				it.text("type") ==
					"EVENT"
			}
		trash.map { it.get("type").asString() to it.get("title").asString() } shouldContainExactly listOf("EVENT" to "Dentist")
		trash.single().text("subtitle") shouldBe "Mon 2 Mar 2026"

		mockMvc.post("/api/v1/trash/EVENT/${event.id()}/restore") { with(csrf()) }.andExpect { status { isNoContent() } }

		api.event(event.id()).text("title") shouldBe "Dentist"
		searchTitle(event.id()) shouldBe "Dentist"
		events.changes().map { it.first } shouldContainExactly listOf(ChangeOp.CREATED, ChangeOp.DELETED, ChangeOp.UPDATED)
	}

	@Test
	fun `purging a series from the Trash deletes its overrides and their links`() {
		val series =
			api.createEvent("""{"title":"Standup","start":"2026-03-02T08:00:00Z","end":"2026-03-02T08:15:00Z","rrule":"FREQ=DAILY;COUNT=5"}""")
		val override = api.patchEventOk(series.id(), """{"title":"Standup (long)"}""", "?scope=THIS&occurrence=2026-03-03T08:00:00Z")
		val task = jdbc.queryForObject("select gen_random_uuid()", java.util.UUID::class.java)
		jdbc.update(
			"insert into link (id, created_at, updated_at, version, source_type, source_id, target_type, target_id, origin) " +
				"values (?, now(), now(), 0, 'EVENT', ?::uuid, 'TASK', ?, 'MANUAL')",
			UuidV7.generate(),
			override.id(),
			task,
		)
		api.deleteEvent(series.id()).andExpect { status { isNoContent() } }

		mockMvc.delete("/api/v1/trash/EVENT/${series.id()}") { with(csrf()) }.andExpect { status { isNoContent() } }

		jdbc.queryForObject("select count(*) from calendar_event", Int::class.java) shouldBe 0
		jdbc.queryForObject("select count(*) from link", Int::class.java) shouldBe 0
	}

	// --- range query and search

	@Test
	fun `GET returns the occurrences in the range of the visible or the given calendars`() {
		val hidden = api.createCalendar("""{"name":"Hidden","visible":false}""").id()
		dentist()
		api.createEvent("""{"calendarId":"$hidden","title":"Secret","start":"2026-03-02T10:00:00Z"}""")
		api.createEvent("""{"title":"Holidays","allDay":true,"startDate":"2026-03-02","endDate":"2026-03-04"}""")
		api.createEvent("""{"title":"Later","start":"2026-03-09T10:00:00Z"}""")

		api.occurrences("2026-03-01T23:00:00Z", "2026-03-02T23:00:00Z").map { it.text("title") } shouldContainExactly
			listOf("Holidays", "Dentist")
		api
			.occurrences("2026-03-02T00:00:00+01:00", "2026-03-03T00:00:00+01:00", "calendarIds" to "$hidden,$calendarId")
			.map { it.text("title") } shouldContainExactly listOf("Holidays", "Secret", "Dentist")
		val holidays = api.occurrences("2026-03-03T12:00:00Z", "2026-03-03T13:00:00Z").single()
		holidays.text("startDate") shouldBe "2026-03-02"
		holidays.text("endDate") shouldBe "2026-03-04"
		holidays.text("seriesId") shouldBe null
	}

	@Test
	fun `GET rejects a missing, inverted or too long range`() {
		mockMvc.get(EVENTS) { param("from", "2026-03-01T00:00:00Z") }.andExpect { status { isBadRequest() } }
		mockMvc
			.get(EVENTS) {
				param("from", "2026-03-02T00:00:00Z")
				param("to", "2026-03-01T00:00:00Z")
			}.isProblem(400, "to")
		mockMvc
			.get(EVENTS) {
				param("from", "2026-01-01T00:00:00Z")
				param("to", "2027-03-01T00:00:00Z")
			}.isProblem(400, "to")
		mockMvc
			.get(EVENTS) {
				param("from", "yesterday")
				param("to", "2027-03-01T00:00:00Z")
			}.andExpect { status { isBadRequest() } }
	}

	@Test
	fun `events are found by the global search`() {
		dentist()
		val hits = api.read(mockMvc.get("/api/v1/search") { param("q", "main street") }.andExpect { status { isOk() } })
		hits.toString().contains("Dentist") shouldBe true
	}
}
