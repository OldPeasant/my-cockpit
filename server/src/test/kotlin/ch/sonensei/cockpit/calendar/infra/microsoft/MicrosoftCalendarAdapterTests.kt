package ch.sonensei.cockpit.calendar.infra.microsoft

import ch.sonensei.cockpit.calendar.api.Timing
import ch.sonensei.cockpit.calendar.model.Attendee
import ch.sonensei.cockpit.calendar.model.AttendeeStatus
import ch.sonensei.cockpit.calendar.model.CalendarSource
import ch.sonensei.cockpit.calendar.model.CalendarSourceType
import ch.sonensei.cockpit.calendar.model.EventStatus
import ch.sonensei.cockpit.calendar.model.EventTransparency
import ch.sonensei.cockpit.calendar.model.OccurrenceId
import ch.sonensei.cockpit.calendar.recurrence.OccurrenceExpander
import ch.sonensei.cockpit.calendar.sync.CalendarProviderException
import ch.sonensei.cockpit.calendar.sync.RemoteCalendar
import ch.sonensei.cockpit.calendar.sync.RemoteEvent
import ch.sonensei.cockpit.calendar.sync.RemoteEventChangedException
import ch.sonensei.cockpit.calendar.sync.RemoteEventKey
import ch.sonensei.cockpit.core.oauth.domain.OAuthAccessToken
import ch.sonensei.cockpit.core.oauth.domain.OAuthConnectionInfo
import ch.sonensei.cockpit.core.oauth.domain.OAuthException
import ch.sonensei.cockpit.core.oauth.domain.OAuthReconnectRequiredException
import ch.sonensei.cockpit.core.oauth.domain.OAuthResource
import ch.sonensei.cockpit.core.oauth.domain.OAuthTokenProvider
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.MappingBuilder
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.absent
import com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.anyUrl
import com.github.tomakehurst.wiremock.client.WireMock.delete
import com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.patch
import com.github.tomakehurst.wiremock.client.WireMock.patchRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import com.github.tomakehurst.wiremock.http.Fault
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/**
 * [MicrosoftCalendarAdapter] without a Spring context against WireMock playing Microsoft Graph v1.0. The response bodies
 * are recordings of Graph's resources (`src/test/resources/.../calendar/infra/microsoft`, fictitious data); the clock
 * stands at 21 October 2026, so the sync window runs from 21 October 2025 to 20 October 2028.
 */
class MicrosoftCalendarAdapterTests {
	private val tokens = FixedTokens()
	private val json = JsonMapper.builder().build()
	private val clock = Clock.fixed(NOW, ZoneOffset.UTC)
	private val adapter = adapter(clock)
	private val source =
		CalendarSource(CalendarSourceType.MICROSOFT, "Microsoft").apply { config = """{"connectionId": "$CONNECTION"}""" }

	private fun adapter(clock: Clock) = MicrosoftCalendarAdapter(tokens, json, OccurrenceExpander(), clock, wireMock.baseUrl() + API)

	@BeforeEach
	fun reset() {
		wireMock.resetAll()
	}

	// --- calendars

	@Test
	fun `listCalendars follows the next links and maps names, colors and write access`() {
		stub(get(urlPathEqualTo("$API/me/calendars")).withQueryParam("\$skip", absent()), recording("calendars-page1.json"))
		stub(get(urlPathEqualTo("$API/me/calendars")).withQueryParam("\$skip", equalTo("2")), recording("calendars-page2.json"))

		adapter.listCalendars(source) shouldContainExactly
			listOf(
				RemoteCalendar("AAMkCalPrimary", "Calendar", null, readOnly = false),
				RemoteCalendar("AAMkCalHolidays", "Swiss holidays", "#FF8C00", readOnly = true),
				RemoteCalendar("AAMkCalTeam", "Team", "#2E8B57", readOnly = false),
			)
		wireMock.verify(
			2,
			getRequestedFor(urlPathEqualTo("$API/me/calendars"))
				.withHeader("Authorization", equalTo("Bearer $TOKEN"))
				.withHeader("Prefer", equalTo("outlook.timezone=\"UTC\"")),
		)
	}

	// --- pull

	@Test
	fun `the first fetch lists the window with calendarView delta, page by page, and keeps the delta link`() {
		stubFullListing()

		val first = adapter.fetchEvents(source, CALENDAR, null)
		first.complete shouldBe true
		first.hasMore shouldBe true
		val second = adapter.fetchEvents(source, CALENDAR, first.cursor)
		second.complete shouldBe true
		second.hasMore shouldBe false

		json.readTree(second.cursor).let {
			it.get("mode").asString() shouldBe "delta"
			it.get("anchor").asString() shouldBe "2026-10-21"
			it.get("link").asString() shouldBe "/me/calendars/$CALENDAR/calendarView/delta?\$deltatoken=DELTA-1"
		}
		wireMock.verify(
			getRequestedFor(urlPathEqualTo(DELTA))
				.withQueryParam("startDateTime", equalTo("2025-10-21T00:00:00Z"))
				.withQueryParam("endDateTime", equalTo("2028-10-20T00:00:00Z")),
		)
		wireMock.verify(getRequestedFor(urlPathEqualTo(DELTA)).withQueryParam("\$skiptoken", equalTo("SKIP-1")))
		// The series came with occurrences on both pages, but is read once.
		wireMock.verify(1, getRequestedFor(urlPathEqualTo("$API/me/events/AAMkSeries1")))
		first.events.map { it.key } shouldContainExactly
			listOf(
				RemoteEventKey("AAMkSingle1"),
				RemoteEventKey("AAMkSeries1"),
				RemoteEventKey("AAMkSeries1", at("2026-10-19T07:00:00Z")),
				RemoteEventKey("AAMkAllDay1"),
			)
		second.events.map { it.key } shouldContainExactly listOf(RemoteEventKey("AAMkSingle2"))
	}

	@Test
	fun `events map to remote events - zones, attendees, reminder, showAs, body, online meeting`() {
		stubFullListing()

		val events = adapter.fetchEvents(source, CALENDAR, null).events.associateBy { it.key }

		events.getValue(RemoteEventKey("AAMkSingle1")) shouldBe
			RemoteEvent(
				key = RemoteEventKey("AAMkSingle1"),
				etag = "W/\"DwAAABYAAAA1\"",
				title = "Dentist",
				timing = Timing.Timed(Instant.parse("2026-10-22T07:30:00Z"), Instant.parse("2026-10-22T08:15:00Z"), ZoneId.of("Europe/Berlin")),
				description = "Bring the insurance card.",
				location = "Dental practice Muster",
				attendees =
					listOf(
						Attendee("sam.sample@example.test", "Sam Sample", AttendeeStatus.ACCEPTED),
						Attendee("kim.demo@example.test", null, AttendeeStatus.TENTATIVE),
						Attendee("not-an-address", "Not an address", AttendeeStatus.NEEDS_ACTION),
					),
				reminderMinutes = listOf(15),
			)
		val offsite = events.getValue(RemoteEventKey("AAMkAllDay1"))
		offsite.timing shouldBe Timing.AllDay(LocalDate.parse("2026-11-02"), LocalDate.parse("2026-11-04"))
		offsite.transparency shouldBe EventTransparency.TRANSPARENT
		offsite.reminderMinutes shouldBe emptyList()
	}

	@Test
	fun `a series is its master with the RRULE, the occurrences Graph no longer has as EXDATEs, and its exceptions`() {
		stubFullListing()

		val events = adapter.fetchEvents(source, CALENDAR, null).events.associateBy { it.key }

		val series = events.getValue(RemoteEventKey("AAMkSeries1"))
		series.rrule shouldBe "FREQ=WEEKLY;BYDAY=MO;UNTIL=20261228T225959Z"
		series.timing shouldBe
			Timing.Timed(Instant.parse("2026-10-05T07:00:00Z"), Instant.parse("2026-10-05T07:30:00Z"), ZoneId.of("Europe/Berlin"))
		// 9 November is missing from the instances (deleted occurrence); 19 October is an exception, not missing.
		series.exdates shouldBe setOf(at("2026-11-09T08:00:00Z"))
		series.status shouldBe EventStatus.TENTATIVE
		series.conferenceUrl shouldBe "https://teams.example.test/l/meetup-join/fictitious"
		val exception = events.getValue(RemoteEventKey("AAMkSeries1", at("2026-10-19T07:00:00Z")))
		exception.title shouldBe "Team sync (moved)"
		exception.rrule.shouldBeNull()
		exception.etag shouldBe "W/\"DwAAABYAAAA4\""
		(exception.timing as Timing.Timed).start shouldBe Instant.parse("2026-10-19T08:00:00Z")
		wireMock.verify(2, getRequestedFor(urlPathEqualTo("$API/me/events/AAMkSeries1/instances")))
	}

	@Test
	fun `an incremental fetch follows the delta link and returns the changes only`() {
		stub(get(urlPathEqualTo(DELTA)).withQueryParam("\$deltatoken", equalTo("DELTA-1")), recording("delta-incremental.json"))

		val changes = adapter.fetchEvents(source, CALENDAR, deltaCursor("DELTA-1", "2026-10-20"))

		changes.complete shouldBe false
		changes.hasMore shouldBe false
		changes.events.map { it.title } shouldContainExactly listOf("Dentist (moved)", "Lunch with Sam")
		json.readTree(changes.cursor).get("link").asString() shouldBe "/me/calendars/$CALENDAR/calendarView/delta?\$deltatoken=DELTA-2"
		json.readTree(changes.cursor).get("anchor").asString() shouldBe "2026-10-20"
		wireMock.verify(0, getRequestedFor(urlPathEqualTo(DELTA)).withQueryParam("startDateTime", equalTo("2025-10-21T00:00:00Z")))
	}

	@Test
	fun `a delta that removed something lists the calendar completely in the same run`() {
		stub(get(urlPathEqualTo(DELTA)).withQueryParam("\$deltatoken", equalTo("DELTA-2")), recording("delta-removed.json"))
		stubFullListing()

		val removed = adapter.fetchEvents(source, CALENDAR, deltaCursor("DELTA-2", "2026-10-20"))
		removed.hasMore shouldBe true
		removed.complete shouldBe false
		removed.deleted shouldBe emptyList()

		val full = adapter.fetchEvents(source, CALENDAR, removed.cursor)
		full.complete shouldBe true
		wireMock.verify(getRequestedFor(urlPathEqualTo(DELTA)).withQueryParam("startDateTime", equalTo("2025-10-21T00:00:00Z")))
	}

	@Test
	fun `410 for the delta token and a delta link older than a week start a full listing`() {
		stub(
			get(urlPathEqualTo(DELTA)).withQueryParam("\$deltatoken", equalTo("EXPIRED")),
			recording("error-410-sync-state-not-found.json"),
			410,
		)
		stubFullListing()

		adapter.fetchEvents(source, CALENDAR, deltaCursor("EXPIRED", "2026-10-20")).complete shouldBe true
		adapter.fetchEvents(source, CALENDAR, deltaCursor("DELTA-1", "2026-10-14")).complete shouldBe true

		wireMock.verify(0, getRequestedFor(urlPathEqualTo(DELTA)).withQueryParam("\$deltatoken", equalTo("DELTA-1")))
		wireMock.verify(2, getRequestedFor(urlPathEqualTo(DELTA)).withQueryParam("startDateTime", equalTo("2025-10-21T00:00:00Z")))
	}

	@Test
	fun `a cursor that is not ours starts over`() {
		stubFullListing()

		adapter.fetchEvents(source, CALENDAR, "sync:google-token").complete shouldBe true
	}

	@Test
	fun `paging links to another API path are refused`() {
		stub(
			get(urlPathEqualTo(DELTA)),
			"""{"value": [], "@odata.deltaLink": "https://graph.example.test/beta/me/calendarView/delta?${'$'}deltatoken=X"}""",
		)

		shouldThrow<CalendarProviderException> { adapter.fetchEvents(source, CALENDAR, null) }.message shouldContain "outside the API"
	}

	// --- push

	@Test
	fun `createEvent posts the event with a transaction id and returns Graph's id and etag`() {
		stub(post(urlPathEqualTo("$API/me/calendars/$CALENDAR/events")), recording("event-created.json"), 201)

		val stored = adapter.createEvent(source, CALENDAR, planning())

		stored.key shouldBe RemoteEventKey("AAMkCreated1")
		stored.etag shouldBe "W/\"DwAAABYAAAC1\""
		val body = sentBody("POST")
		body.get("transactionId").asString() shouldBe NEW_ID
		body.get("subject").asString() shouldBe "Planning"
		body.get("start").get("dateTime").asString() shouldBe "2026-10-28T09:00:00"
		body.get("start").get("timeZone").asString() shouldBe "Europe/Zurich"
		body.get("isAllDay").asBoolean() shouldBe false
		body.get("isReminderOn").asBoolean() shouldBe true
		body.get("reminderMinutesBeforeStart").asInt() shouldBe 10
		body.get("showAs").asString() shouldBe "busy"
		body.has("recurrence") shouldBe false
	}

	@Test
	fun `createEvent of a series sends its pattern and deletes the instances of its EXDATEs`() {
		stub(post(urlPathEqualTo("$API/me/calendars/$CALENDAR/events")), recording("series1-master.json"), 201)
		stubSeries()
		stub(delete(urlPathEqualTo("$API/me/events/AAMkSeries1-20261109")), "", 204)
		stub(delete(urlPathEqualTo("$API/me/events/AAMkSeries1-20261116")), "", 204)

		val series =
			RemoteEvent(
				key = RemoteEventKey(NEW_ID),
				etag = null,
				title = "Team sync",
				timing = Timing.Timed(Instant.parse("2026-10-05T07:00:00Z"), Instant.parse("2026-10-05T07:30:00Z"), ZoneId.of("Europe/Berlin")),
				rrule = "FREQ=WEEKLY;BYDAY=MO;UNTIL=20261228T225959Z",
				exdates = setOf(at("2026-11-16T08:00:00Z")),
			)
		val stored = adapter.createEvent(source, CALENDAR, series)

		val recurrence = sentBody("POST").get("recurrence")
		recurrence.get("pattern").get("type").asString() shouldBe "weekly"
		recurrence
			.get("pattern")
			.get("daysOfWeek")
			.values()
			.map { it.asString() } shouldBe listOf("monday")
		recurrence.get("range").get("type").asString() shouldBe "endDate"
		recurrence.get("range").get("startDate").asString() shouldBe "2026-10-05"
		recurrence.get("range").get("endDate").asString() shouldBe "2026-12-28"
		recurrence.get("range").get("recurrenceTimeZone").asString() shouldBe "W. Europe Standard Time"
		wireMock.verify(deleteRequestedFor(urlPathEqualTo("$API/me/events/AAMkSeries1-20261116")))
		stored.key shouldBe RemoteEventKey("AAMkSeries1")
		stored.rrule shouldBe "FREQ=WEEKLY;BYDAY=MO;UNTIL=20261228T225959Z"
	}

	@Test
	fun `updateEvent sends only the changed fields with If-Match`() {
		stub(get(urlPathEqualTo("$API/me/events/AAMkSingle1")), dentist())
		stub(patch(urlPathEqualTo("$API/me/events/AAMkSingle1")), dentist(subject = "Dentist (new)", etag = "W/\\\"DwAAABYAAAA5\\\""))

		val current = adapter.fetchEventsOf(dentist())
		val stored = adapter.updateEvent(source, CALENDAR, current.copy(title = "Dentist (new)"), "W/\"DwAAABYAAAA1\"")

		stored.title shouldBe "Dentist (new)"
		stored.etag shouldBe "W/\"DwAAABYAAAA5\""
		wireMock.verify(patchRequestedFor(urlPathEqualTo("$API/me/events/AAMkSingle1")).withHeader("If-Match", equalTo("W/\"DwAAABYAAAA1\"")))
		sentBody("PATCH").propertyNames().toList() shouldContainExactly listOf("subject")
	}

	@Test
	fun `updateEvent without changes writes nothing, but refuses a stale etag`() {
		stub(get(urlPathEqualTo("$API/me/events/AAMkSingle1")), dentist())
		val current = adapter.fetchEventsOf(dentist())

		adapter.updateEvent(source, CALENDAR, current, "W/\"DwAAABYAAAA1\"").etag shouldBe "W/\"DwAAABYAAAA1\""
		shouldThrow<RemoteEventChangedException> { adapter.updateEvent(source, CALENDAR, current, "W/\"old\"") }

		wireMock.verify(0, patchRequestedFor(anyUrl()))
	}

	@Test
	fun `412 and 404 on update are changes at Microsoft`() {
		stub(get(urlPathEqualTo("$API/me/events/AAMkSingle1")), dentist())
		stub(patch(urlPathEqualTo("$API/me/events/AAMkSingle1")), recording("error-412-irresolvable-conflict.json"), 412)
		val current = adapter.fetchEventsOf(dentist())

		shouldThrow<RemoteEventChangedException> { adapter.updateEvent(source, CALENDAR, current.copy(title = "Mine"), "W/\"DwAAABYAAAA1\"") }

		stub(get(urlPathEqualTo("$API/me/events/AAMkSingle1")), """{"error": {"code": "ErrorItemNotFound"}}""", 404)
		shouldThrow<RemoteEventChangedException> { adapter.updateEvent(source, CALENDAR, current.copy(title = "Mine"), null) }
	}

	@Test
	fun `changing one occurrence patches its instance - without If-Match when first overridden`() {
		stubSeriesInstancesAround()
		stub(patch(urlPathEqualTo("$API/me/events/AAMkSeries1-20261116")), occurrenceJson("2026-11-16", "Team sync (this one)"))

		val override = occurrence("2026-11-16T08:00:00Z", "Team sync (this one)")
		val created = adapter.createEvent(source, CALENDAR, override)
		created.key shouldBe override.key
		created.title shouldBe "Team sync (this one)"
		wireMock.verify(patchRequestedFor(urlPathEqualTo("$API/me/events/AAMkSeries1-20261116")).withHeader("If-Match", absent()))
		sentBody("PATCH").get("subject").asString() shouldBe "Team sync (this one)"
		sentBody("PATCH").has("recurrence") shouldBe false

		wireMock.resetRequests()
		adapter.updateEvent(source, CALENDAR, override, "W/\"I6\"")
		wireMock.verify(patchRequestedFor(urlPathEqualTo("$API/me/events/AAMkSeries1-20261116")).withHeader("If-Match", equalTo("W/\"I6\"")))
	}

	@Test
	fun `a cancelled override and a deleted occurrence delete the instance`() {
		stubSeriesInstancesAround()
		stub(delete(urlPathEqualTo("$API/me/events/AAMkSeries1-20261116")), "", 204)

		val cancelled = occurrence("2026-11-16T08:00:00Z", "Team sync").copy(status = EventStatus.CANCELLED)
		adapter.updateEvent(source, CALENDAR, cancelled, "W/\"I6\"").etag.shouldBeNull()
		adapter.deleteEvent(source, CALENDAR, cancelled.key, "W/\"I6\"").shouldBeNull()

		wireMock.verify(2, deleteRequestedFor(urlPathEqualTo("$API/me/events/AAMkSeries1-20261116")).withHeader("If-Match", equalTo("W/\"I6\"")))
		wireMock.verify(0, patchRequestedFor(anyUrl()))
	}

	@Test
	fun `deleting an occurrence Graph does not have, or an event that is gone, succeeds`() {
		stubSeriesInstancesAround()
		stub(delete(urlPathEqualTo("$API/me/events/AAMkSingle1")), """{"error": {"code": "ErrorItemNotFound"}}""", 404)

		adapter.deleteEvent(source, CALENDAR, RemoteEventKey("AAMkSeries1", at("2026-11-09T08:00:00Z")), null).shouldBeNull()
		adapter.deleteEvent(source, CALENDAR, RemoteEventKey("AAMkSingle1"), "W/\"DwAAABYAAAA1\"").shouldBeNull()
	}

	@Test
	fun `ending a series before an occurrence (this and following) sends the shortened pattern`() {
		stubSeries()
		stub(patch(urlPathEqualTo("$API/me/events/AAMkSeries1")), recording("series1-master.json"))

		val ended =
			RemoteEvent(
				key = RemoteEventKey("AAMkSeries1"),
				etag = "W/\"DwAAABYAAAA3\"",
				title = "Team sync",
				timing = Timing.Timed(Instant.parse("2026-10-05T07:00:00Z"), Instant.parse("2026-10-05T07:30:00Z"), ZoneId.of("Europe/Berlin")),
				rrule = "FREQ=WEEKLY;BYDAY=MO;UNTIL=20261116T075959Z",
				status = EventStatus.TENTATIVE,
				attendees = emptyList(),
				reminderMinutes = listOf(15),
			)

		adapter.updateEvent(source, CALENDAR, ended, "W/\"DwAAABYAAAA3\"")

		val body = sentBody("PATCH")
		body.propertyNames().toList() shouldContainExactly listOf("recurrence")
		body
			.get("recurrence")
			.get("range")
			.get("endDate")
			.asString() shouldBe "2026-11-15"
	}

	@Test
	fun `a rule Graph cannot express is refused with a message`() {
		val hourly = planning().copy(rrule = "FREQ=HOURLY")

		shouldThrow<CalendarProviderException> { adapter.createEvent(source, CALENDAR, hourly) }.message shouldContain "hourly"
		wireMock.verify(0, anyRequestedFor(anyUrl()))
	}

	@Test
	fun `failures become provider exceptions without tokens or bodies`() {
		stub(get(urlPathEqualTo("$API/me/calendars")), recording("error-429-throttled.json"), 429)
		shouldThrow<CalendarProviderException> { adapter.listCalendars(source) }.message shouldContain "HTTP 429"

		wireMock.stubFor(get(urlPathEqualTo("$API/me/calendars")).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)))
		shouldThrow<CalendarProviderException> { adapter.listCalendars(source) }.message shouldContain "could not be reached"

		stub(get(urlPathEqualTo("$API/me/calendars")), """{"error": {"code": "InvalidAuthenticationToken"}}""", 401)
		shouldThrow<CalendarProviderException> { adapter.listCalendars(source) }.message.let {
			it shouldContain "401"
			it shouldNotContain TOKEN
		}

		tokens.failure = OAuthReconnectRequiredException("refresh token revoked")
		shouldThrow<CalendarProviderException> { adapter.listCalendars(source) }.message shouldBe
			"The Microsoft account has to be connected again"

		val unlinked = CalendarSource(CalendarSourceType.MICROSOFT, "Microsoft")
		shouldThrow<CalendarProviderException> { adapter.listCalendars(unlinked) }.message shouldContain "not linked"
	}

	// --- helpers

	/** [node] (a Graph event) as the adapter reads it. */
	private fun MicrosoftCalendarAdapter.fetchEventsOf(node: String): RemoteEvent {
		val parsed = json.readTree(node)
		return GraphEventMapping.toRemote(parsed, RemoteEventKey(parsed.get("id").asString()))
	}

	private fun planning() =
		RemoteEvent(
			key = RemoteEventKey(NEW_ID),
			etag = null,
			title = "Planning",
			timing = Timing.Timed(Instant.parse("2026-10-28T08:00:00Z"), Instant.parse("2026-10-28T09:00:00Z"), ZoneId.of("Europe/Zurich")),
			reminderMinutes = listOf(10),
		)

	private fun occurrence(
		at: String,
		title: String,
	) = RemoteEvent(
		key = RemoteEventKey("AAMkSeries1", at(at)),
		etag = null,
		title = title,
		timing = Timing.Timed(Instant.parse(at), Instant.parse(at).plusSeconds(1800), ZoneId.of("Europe/Berlin")),
		status = EventStatus.TENTATIVE,
		reminderMinutes = listOf(15),
	)

	private fun occurrenceJson(
		day: String,
		subject: String,
	): String {
		val node =
			json.readTree(recording("series1-instances-page1.json")).get("value").first {
				it.get("id").asString() ==
					"AAMkSeries1-${day.replace("-", "")}"
			}
		return json.writeValueAsString((node as tools.jackson.databind.node.ObjectNode).put("subject", subject))
	}

	private fun dentist(
		subject: String = "Dentist",
		etag: String = "W/\\\"DwAAABYAAAA1\\\"",
	) = """
		{"@odata.etag": "$etag", "id": "AAMkSingle1", "type": "singleInstance", "subject": "$subject",
		 "originalStartTimeZone": "W. Europe Standard Time", "isAllDay": false, "showAs": "busy",
		 "isReminderOn": true, "reminderMinutesBeforeStart": 15, "body": {"contentType": "text", "content": ""},
		 "location": {"displayName": ""}, "attendees": [],
		 "start": {"dateTime": "2026-10-22T07:30:00.0000000", "timeZone": "UTC"},
		 "end": {"dateTime": "2026-10-22T08:15:00.0000000", "timeZone": "UTC"}}
		""".trimIndent()

	private fun sentBody(method: String): JsonNode =
		json.readTree(
			wireMock.allServeEvents
				.map { it.request }
				.first { it.method.value() == method }
				.bodyAsString,
		)

	private fun deltaCursor(
		token: String,
		anchor: String,
	) = """{"mode":"delta","anchor":"$anchor","link":"/me/calendars/$CALENDAR/calendarView/delta?${'$'}deltatoken=$token"}"""

	private fun stubFullListing() {
		stub(get(urlPathEqualTo(DELTA)).withQueryParam("startDateTime", equalTo("2025-10-21T00:00:00Z")), recording("delta-full-page1.json"))
		stub(
			get(urlPathEqualTo(DELTA)).withQueryParam("\$skiptoken", equalTo("SKIP-1")),
			// The second page also brings a new single event.
			recording("delta-full-page2.json").replace(
				"\"value\": [",
				"\"value\": [{\"id\": \"AAMkSingle2\", \"type\": \"singleInstance\", \"subject\": \"Lunch with Sam\", " +
					"\"start\": {\"dateTime\": \"2026-10-23T10:30:00\", \"timeZone\": \"UTC\"}, " +
					"\"end\": {\"dateTime\": \"2026-10-23T11:30:00\", \"timeZone\": \"UTC\"}},",
			),
		)
		stubSeries()
	}

	private fun stubSeries() {
		stub(get(urlPathEqualTo("$API/me/events/AAMkSeries1")), recording("series1-master.json"))
		stub(
			get(urlPathEqualTo("$API/me/events/AAMkSeries1/instances")).withQueryParam("\$skip", absent()),
			recording("series1-instances-page1.json"),
		)
		stub(
			get(urlPathEqualTo("$API/me/events/AAMkSeries1/instances")).withQueryParam("\$skip", equalTo("6")),
			recording("series1-instances-page2.json"),
		)
	}

	/** The instances lookup around one occurrence: all instances of the series, in one page. */
	private fun stubSeriesInstancesAround() {
		val all =
			json.readTree(recording("series1-instances-page1.json")).get("value").toList() +
				json.readTree(recording("series1-instances-page2.json")).get("value").toList()
		stub(get(urlPathEqualTo("$API/me/events/AAMkSeries1/instances")), json.writeValueAsString(mapOf("value" to all)))
	}

	class FixedTokens : OAuthTokenProvider {
		var failure: OAuthException? = null

		override fun accessToken(
			connectionId: UUID,
			resource: OAuthResource,
		): OAuthAccessToken {
			failure?.let { throw it }
			require(connectionId == CONNECTION) { "unknown connection" }
			return OAuthAccessToken(TOKEN, Instant.parse("2030-01-01T00:00:00Z"))
		}

		override fun connection(connectionId: UUID): OAuthConnectionInfo? = null
	}

	companion object {
		const val API = "/v1.0"
		const val CALENDAR = "AAMkCalPrimary"
		const val DELTA = "$API/me/calendars/$CALENDAR/calendarView/delta"
		const val TOKEN = "fictitious-graph-access-token"
		const val NEW_ID = "0192a5f0-0000-7000-8000-0000000000aa"
		val CONNECTION: UUID = UUID.fromString("0192a5f0-0000-7000-8000-000000000002")
		val NOW: Instant = Instant.parse("2026-10-21T08:00:00Z")

		val wireMock: WireMockServer = WireMockServer(wireMockConfig().dynamicPort()).apply { start() }

		@JvmStatic
		@AfterAll
		fun stop() = wireMock.stop()

		fun at(instant: String) = OccurrenceId.of(Instant.parse(instant))

		/** The recorded response body [name]. */
		fun recording(name: String): String =
			checkNotNull(MicrosoftCalendarAdapterTests::class.java.getResource(name)) { "no recording $name" }.readText()

		fun stub(
			mapping: MappingBuilder,
			body: String,
			status: Int = 200,
		) {
			wireMock.stubFor(mapping.willReturn(aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(body)))
		}
	}
}
