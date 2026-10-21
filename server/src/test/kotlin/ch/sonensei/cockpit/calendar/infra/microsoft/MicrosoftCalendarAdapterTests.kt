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
import ch.sonensei.cockpit.core.oauth.domain.OAuthConnectionNotFoundException
import ch.sonensei.cockpit.core.oauth.domain.OAuthException
import ch.sonensei.cockpit.core.oauth.domain.OAuthGrantRejectedException
import ch.sonensei.cockpit.core.oauth.domain.OAuthProviderUnavailableException
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
import com.github.tomakehurst.wiremock.stubbing.Scenario
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.throwables.shouldThrowExactly
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
		wireMock.verify(1, getRequestedFor(urlPathEqualTo("$EVENTS/AAMkSeries1")))
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
		wireMock.verify(2, getRequestedFor(urlPathEqualTo("$EVENTS/AAMkSeries1/instances")))
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
		stub(delete(urlPathEqualTo("$EVENTS/AAMkSeries1-20261109")), "", 204)
		stub(delete(urlPathEqualTo("$EVENTS/AAMkSeries1-20261116")), "", 204)

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
		wireMock.verify(deleteRequestedFor(urlPathEqualTo("$EVENTS/AAMkSeries1-20261116")))
		stored.key shouldBe RemoteEventKey("AAMkSeries1")
		stored.rrule shouldBe "FREQ=WEEKLY;BYDAY=MO;UNTIL=20261228T225959Z"
	}

	@Test
	fun `updateEvent sends only the changed fields with If-Match`() {
		stub(get(urlPathEqualTo("$EVENTS/AAMkSingle1")), dentist())
		stub(patch(urlPathEqualTo("$EVENTS/AAMkSingle1")), dentist(subject = "Dentist (new)", etag = "W/\\\"DwAAABYAAAA5\\\""))

		val current = adapter.fetchEventsOf(dentist())
		val stored = adapter.updateEvent(source, CALENDAR, current.copy(title = "Dentist (new)"), "W/\"DwAAABYAAAA1\"")

		stored.title shouldBe "Dentist (new)"
		stored.etag shouldBe "W/\"DwAAABYAAAA5\""
		wireMock.verify(patchRequestedFor(urlPathEqualTo("$EVENTS/AAMkSingle1")).withHeader("If-Match", equalTo("W/\"DwAAABYAAAA1\"")))
		sentBody("PATCH").propertyNames().toList() shouldContainExactly listOf("subject")
	}

	@Test
	fun `updateEvent without changes writes nothing and returns Graph's version, whatever the etag`() {
		stub(get(urlPathEqualTo("$EVENTS/AAMkSingle1")), dentist())
		val current = adapter.fetchEventsOf(dentist())

		adapter.updateEvent(source, CALENDAR, current, "W/\"DwAAABYAAAA1\"").etag shouldBe "W/\"DwAAABYAAAA1\""
		// Graph already has what the cockpit wants: no change at Microsoft can be lost.
		adapter.updateEvent(source, CALENDAR, current, "W/\"old\"").etag shouldBe "W/\"DwAAABYAAAA1\""

		wireMock.verify(0, patchRequestedFor(anyUrl()))
	}

	@Test
	fun `412 and 404 on update are changes at Microsoft`() {
		stub(get(urlPathEqualTo("$EVENTS/AAMkSingle1")), dentist())
		stub(patch(urlPathEqualTo("$EVENTS/AAMkSingle1")), recording("error-412-irresolvable-conflict.json"), 412)
		val current = adapter.fetchEventsOf(dentist())

		shouldThrow<RemoteEventChangedException> { adapter.updateEvent(source, CALENDAR, current.copy(title = "Mine"), "W/\"DwAAABYAAAA1\"") }

		stub(get(urlPathEqualTo("$EVENTS/AAMkSingle1")), """{"error": {"code": "ErrorItemNotFound"}}""", 404)
		shouldThrow<RemoteEventChangedException> { adapter.updateEvent(source, CALENDAR, current.copy(title = "Mine"), null) }
	}

	@Test
	fun `changing one occurrence patches its instance - without If-Match when first overridden`() {
		stubSeriesInstancesAround()
		stub(patch(urlPathEqualTo("$EVENTS/AAMkSeries1-20261116")), occurrenceJson("2026-11-16", "Team sync (this one)"))

		val override = occurrence("2026-11-16T08:00:00Z", "Team sync (this one)")
		val created = adapter.createEvent(source, CALENDAR, override)
		created.key shouldBe override.key
		created.title shouldBe "Team sync (this one)"
		wireMock.verify(patchRequestedFor(urlPathEqualTo("$EVENTS/AAMkSeries1-20261116")).withHeader("If-Match", absent()))
		sentBody("PATCH").get("subject").asString() shouldBe "Team sync (this one)"
		sentBody("PATCH").has("recurrence") shouldBe false

		wireMock.resetRequests()
		adapter.updateEvent(source, CALENDAR, override, "W/\"I6\"")
		wireMock.verify(patchRequestedFor(urlPathEqualTo("$EVENTS/AAMkSeries1-20261116")).withHeader("If-Match", equalTo("W/\"I6\"")))
	}

	@Test
	fun `a cancelled override and a deleted occurrence delete the instance`() {
		stubSeriesInstancesAround()
		stub(delete(urlPathEqualTo("$EVENTS/AAMkSeries1-20261116")), "", 204)

		val cancelled = occurrence("2026-11-16T08:00:00Z", "Team sync").copy(status = EventStatus.CANCELLED)
		adapter.updateEvent(source, CALENDAR, cancelled, "W/\"I6\"").etag.shouldBeNull()
		adapter.deleteEvent(source, CALENDAR, cancelled.key, "W/\"I6\"").shouldBeNull()

		wireMock.verify(2, deleteRequestedFor(urlPathEqualTo("$EVENTS/AAMkSeries1-20261116")).withHeader("If-Match", equalTo("W/\"I6\"")))
		wireMock.verify(0, patchRequestedFor(anyUrl()))
	}

	@Test
	fun `deleting an occurrence Graph does not have, or an event that is gone, succeeds`() {
		stubSeriesInstancesAround()
		stub(delete(urlPathEqualTo("$EVENTS/AAMkSingle1")), """{"error": {"code": "ErrorItemNotFound"}}""", 404)

		adapter.deleteEvent(source, CALENDAR, RemoteEventKey("AAMkSeries1", at("2026-11-09T08:00:00Z")), null).shouldBeNull()
		adapter.deleteEvent(source, CALENDAR, RemoteEventKey("AAMkSingle1"), "W/\"DwAAABYAAAA1\"").shouldBeNull()
	}

	@Test
	fun `ending a series before an occurrence (this and following) sends the shortened pattern`() {
		stubSeries()
		stub(patch(urlPathEqualTo("$EVENTS/AAMkSeries1")), recording("series1-master.json"))

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

	// --- pull: cursors and edge cases

	@Test
	fun `a next page cursor keeps the window, the full listing and the series already read`() {
		stubFullListing()

		val first = adapter.fetchEvents(source, CALENDAR, null)

		json.readTree(first.cursor).let {
			it.get("mode").asString() shouldBe "next"
			it.get("anchor").asString() shouldBe "2026-10-21"
			it.get("full").asBoolean() shouldBe true
			it.get("link").asString() shouldBe "/me/calendars/$CALENDAR/calendarView/delta?\$skiptoken=SKIP-1"
			it.get("seen").values().map { s -> s.asString() } shouldBe listOf("AAMkSeries1")
		}
	}

	@Test
	fun `a series read on an earlier page of the run is not read again`() {
		stub(get(urlPathEqualTo(DELTA)).withQueryParam("\$skiptoken", equalTo("SKIP-1")), recording("delta-full-page2.json"))

		val page = adapter.fetchEvents(source, CALENDAR, nextCursor("SKIP-1", full = true, seen = listOf("AAMkSeries1")))

		page.events shouldBe emptyList()
		page.complete shouldBe true
		page.hasMore shouldBe false
		json.readTree(page.cursor).get("mode").asString() shouldBe "delta"
		wireMock.verify(0, getRequestedFor(urlPathEqualTo("$EVENTS/AAMkSeries1")))
	}

	@Test
	fun `the next pages of an incremental fetch are not a full listing`() {
		stub(get(urlPathEqualTo(DELTA)).withQueryParam("\$skiptoken", equalTo("SKIP-1")), recording("delta-full-page2.json"))
		stubSeries()

		val page = adapter.fetchEvents(source, CALENDAR, nextCursor("SKIP-1", full = false, seen = emptyList()))

		page.complete shouldBe false
		page.events.map { it.key } shouldContainExactly
			listOf(RemoteEventKey("AAMkSeries1"), RemoteEventKey("AAMkSeries1", at("2026-10-19T07:00:00Z")))
	}

	@Test
	fun `a delta link six days old is still followed`() {
		stub(get(urlPathEqualTo(DELTA)).withQueryParam("\$deltatoken", equalTo("DELTA-1")), recording("delta-incremental.json"))

		adapter.fetchEvents(source, CALENDAR, deltaCursor("DELTA-1", "2026-10-15")).complete shouldBe false

		wireMock.verify(0, getRequestedFor(urlPathEqualTo(DELTA)).withQueryParam("startDateTime", equalTo("2025-10-21T00:00:00Z")))
	}

	@Test
	fun `410 on a page of a full listing fails instead of starting over again`() {
		stub(
			get(urlPathEqualTo(DELTA)).withQueryParam("\$skiptoken", equalTo("SKIP-1")),
			recording("error-410-sync-state-not-found.json"),
			410,
		)

		shouldThrowExactly<CalendarProviderException> {
			adapter.fetchEvents(source, CALENDAR, nextCursor("SKIP-1", full = true, seen = emptyList()))
		}.message shouldContain "HTTP 410"
		wireMock.verify(1, anyRequestedFor(anyUrl()))
	}

	@Test
	fun `a removal seen during a full listing does not start another one`() {
		stub(get(urlPathEqualTo(DELTA)).withQueryParam("\$skiptoken", equalTo("SKIP-2")), recording("delta-removed.json"))

		val page = adapter.fetchEvents(source, CALENDAR, nextCursor("SKIP-2", full = true, seen = emptyList()))

		page.complete shouldBe true
		page.hasMore shouldBe false
		json.readTree(page.cursor).get("link").asString() shouldBe "/me/calendars/$CALENDAR/calendarView/delta?\$deltatoken=DELTA-3"
	}

	@Test
	fun `an occurrence whose series master is gone yields nothing`() {
		stubDeltaPage(instanceJson("AAMkGone-20261026", "occurrence", "2026-10-26T08:00:00", seriesId = "AAMkGone"))
		stub(get(urlPathEqualTo("$EVENTS/AAMkGone")), """{"error": {"code": "ErrorItemNotFound"}}""", 404)

		val page = adapter.fetchEvents(source, CALENDAR, null)

		page.events shouldBe emptyList()
		page.complete shouldBe true
		json.readTree(page.cursor).get("mode").asString() shouldBe "delta"
		wireMock.verify(0, getRequestedFor(urlPathEqualTo("$EVENTS/AAMkGone/instances")))
	}

	@Test
	fun `an event that cannot be read is skipped, the others are returned`() {
		stubDeltaPage(
			"""{"id": "AAMkBroken", "type": "singleInstance", "subject": "No start"}""",
			"""{"id": "AAMkFine", "type": "singleInstance", "subject": "Fine",
			 "start": {"dateTime": "2026-10-23T10:30:00", "timeZone": "UTC"}, "end": {"dateTime": "2026-10-23T11:00:00", "timeZone": "UTC"}}""",
		)

		adapter.fetchEvents(source, CALENDAR, null).events.map { it.key } shouldContainExactly listOf(RemoteEventKey("AAMkFine"))
	}

	@Test
	fun `an exception without a matching occurrence of the pattern is keyed by its original start`() {
		stubDeltaPage(instanceJson("AAMkSeries1-20261026", "occurrence", "2026-10-26T08:00:00"))
		stub(get(urlPathEqualTo("$EVENTS/AAMkSeries1")), recording("series1-master.json"))
		val all =
			json.readTree(recording("series1-instances-page1.json")).get("value").toList() +
				json.readTree(recording("series1-instances-page2.json")).get("value").toList() +
				// A Wednesday: the Monday pattern has no occurrence within half a day of it.
				listOf(json.readTree(instanceJson("AAMkSeries1-x", "exception", "2026-10-21T12:00:00", originalStart = "2026-10-21T07:00:00Z")))
		stub(get(urlPathEqualTo("$EVENTS/AAMkSeries1/instances")), json.writeValueAsString(mapOf("value" to all)))

		val events = adapter.fetchEvents(source, CALENDAR, null).events.associateBy { it.key }

		val stray = events.getValue(RemoteEventKey("AAMkSeries1", at("2026-10-21T07:00:00Z")))
		(stray.timing as Timing.Timed).start shouldBe Instant.parse("2026-10-21T12:00:00Z")
		// It does not hide the occurrence that really is missing.
		events.getValue(RemoteEventKey("AAMkSeries1")).exdates shouldBe setOf(at("2026-11-09T08:00:00Z"))
	}

	@Test
	fun `an exception that cannot be read is skipped, its series is still returned`() {
		stubDeltaPage(instanceJson("AAMkSeries1-20261026", "occurrence", "2026-10-26T08:00:00"))
		stub(get(urlPathEqualTo("$EVENTS/AAMkSeries1")), recording("series1-master.json"))
		val broken = """{"id": "AAMkSeries1-y", "type": "exception", "seriesMasterId": "AAMkSeries1", "originalStart": "2026-11-23T08:00:00Z"}"""
		stub(get(urlPathEqualTo("$EVENTS/AAMkSeries1/instances")), """{"value": [$broken]}""")

		val events = adapter.fetchEvents(source, CALENDAR, null).events

		events.map { it.key } shouldContainExactly listOf(RemoteEventKey("AAMkSeries1"))
	}

	@Test
	fun `an all-day exception without a matching occurrence is keyed by the nearest UTC day`() {
		val master =
			"""
			{"@odata.etag": "W/\"AD1\"", "id": "AAMkAllDaySeries", "type": "seriesMaster", "subject": "Gym day",
			 "originalStartTimeZone": "Tokyo Standard Time", "isAllDay": true,
			 "recurrence": {"pattern": {"type": "weekly", "interval": 1, "daysOfWeek": ["monday"]},
			                "range": {"type": "noEnd", "startDate": "2026-10-05"}},
			 "start": {"dateTime": "2026-10-05T00:00:00", "timeZone": "UTC"}, "end": {"dateTime": "2026-10-06T00:00:00", "timeZone": "UTC"}}
			""".trimIndent()
		stubDeltaPage(instanceJson("AAMkAllDaySeries-20261026", "occurrence", "2026-10-26T00:00:00", seriesId = "AAMkAllDaySeries"))
		stub(get(urlPathEqualTo("$EVENTS/AAMkAllDaySeries")), master)
		// A Thursday that was planned at midnight in Tokyo (15:00 UTC the day before).
		val exception =
			"""
			{"@odata.etag": "W/\"AD2\"", "id": "AAMkAllDaySeries-x", "type": "exception", "seriesMasterId": "AAMkAllDaySeries",
			 "subject": "Gym day (moved)", "isAllDay": true, "originalStart": "2026-10-21T15:00:00Z",
			 "start": {"dateTime": "2026-10-23T00:00:00", "timeZone": "UTC"}, "end": {"dateTime": "2026-10-24T00:00:00", "timeZone": "UTC"}}
			""".trimIndent()
		stub(get(urlPathEqualTo("$EVENTS/AAMkAllDaySeries/instances")), """{"value": [$exception]}""")

		val events = adapter.fetchEvents(source, CALENDAR, null).events.associateBy { it.key }

		events.getValue(RemoteEventKey("AAMkAllDaySeries", OccurrenceId.of(LocalDate.parse("2026-10-22")))).title shouldBe "Gym day (moved)"
	}

	@Test
	fun `a page without next or delta link keeps no cursor`() {
		stub(get(urlPathEqualTo(DELTA)).withQueryParam("startDateTime", equalTo("2025-10-21T00:00:00Z")), """{"value": []}""")

		val page = adapter.fetchEvents(source, CALENDAR, null)

		page.cursor.shouldBeNull()
		page.hasMore shouldBe false
		page.complete shouldBe true
	}

	@Test
	fun `a cursor with an invalid anchor starts over`() {
		stubFullListing()

		val cursor = """{"mode":"delta","anchor":"21.10.2026","link":"/me/calendars/$CALENDAR/calendarView/delta?${'$'}deltatoken=DELTA-1"}"""
		adapter.fetchEvents(source, CALENDAR, cursor).complete shouldBe true

		wireMock.verify(0, getRequestedFor(urlPathEqualTo(DELTA)).withQueryParam("\$deltatoken", equalTo("DELTA-1")))
	}

	@Test
	fun `token, access and server failures become provider exceptions with a message for the user`() {
		stub(get(urlPathEqualTo("$API/me/calendars")), """{"error": {"code": "ErrorAccessDenied"}}""", 403)
		shouldThrow<CalendarProviderException> { adapter.listCalendars(source) }.message shouldContain "HTTP 403"
		stub(get(urlPathEqualTo("$API/me/calendars")), "Service Unavailable", 503)
		shouldThrow<CalendarProviderException> { adapter.listCalendars(source) }.message shouldContain "HTTP 503"
		// A 200 without a JSON object.
		stub(get(urlPathEqualTo("$API/me/calendars")), "<html>proxy login</html>")
		shouldThrow<CalendarProviderException> { adapter.listCalendars(source) }.message shouldContain "without a JSON object"

		tokens.failure = OAuthConnectionNotFoundException()
		shouldThrow<CalendarProviderException> { adapter.listCalendars(source) }.message shouldContain "removed"
		tokens.failure = OAuthProviderUnavailableException("timeout")
		shouldThrow<CalendarProviderException> { adapter.listCalendars(source) }.message shouldContain "could not be reached"
		tokens.failure = OAuthGrantRejectedException("invalid_grant")
		shouldThrow<CalendarProviderException> { adapter.listCalendars(source) }.message shouldContain "invalid_grant"

		val broken = CalendarSource(CalendarSourceType.MICROSOFT, "Microsoft").apply { config = "not json" }
		shouldThrow<CalendarProviderException> { adapter.listCalendars(broken) }.message shouldContain "not linked"
	}

	@Test
	fun `occurrences of a series beyond the page limit are refused`() {
		// Graph keeps sending a next link: the lookup stops after 100 pages.
		stub(
			get(urlPathEqualTo("$EVENTS/AAMkSeries1/instances")),
			"""{"value": [], "@odata.nextLink": "https://graph.microsoft.com/v1.0/me/calendars/$CALENDAR/events/AAMkSeries1/instances?${'$'}skip=1"}""",
		)

		shouldThrowExactly<CalendarProviderException> {
			adapter.deleteEvent(source, CALENDAR, RemoteEventKey("AAMkSeries1", at("2026-11-16T08:00:00Z")), null)
		}.message shouldContain "too many pages"
		wireMock.verify(100, getRequestedFor(urlPathEqualTo("$EVENTS/AAMkSeries1/instances")))
		wireMock.verify(0, deleteRequestedFor(anyUrl()))
	}

	// --- push: series and occurrences

	@Test
	fun `updating a series with new EXDATEs deletes those instances only, without changing the series`() {
		stubSeries()
		stub(delete(urlPathEqualTo("$EVENTS/AAMkSeries1-20261116")), "", 204)

		val series =
			teamSync().copy(
				exdates =
					setOf(
						// New: Graph still has it.
						at("2026-11-16T08:00:00Z"),
						// Already deleted at Graph.
						at("2026-11-09T08:00:00Z"),
						// Before the window.
						at("2024-10-07T07:00:00Z"),
					),
			)
		val stored = adapter.updateEvent(source, CALENDAR, series, "W/\"DwAAABYAAAA3\"")

		wireMock.verify(0, patchRequestedFor(anyUrl()))
		wireMock.verify(1, deleteRequestedFor(anyUrl()))
		wireMock.verify(deleteRequestedFor(urlPathEqualTo("$EVENTS/AAMkSeries1-20261116")).withHeader("If-Match", absent()))
		stored.key shouldBe RemoteEventKey("AAMkSeries1")
		stored.rrule shouldBe "FREQ=WEEKLY;BYDAY=MO;UNTIL=20261228T225959Z"
	}

	@Test
	fun `changing or overriding an occurrence Graph does not have fails`() {
		stubSeriesInstancesAround()
		val gone = occurrence("2026-11-09T08:00:00Z", "Team sync (moved)")

		shouldThrow<RemoteEventChangedException> { adapter.updateEvent(source, CALENDAR, gone, "W/\"I5\"") }
		shouldThrowExactly<CalendarProviderException> { adapter.createEvent(source, CALENDAR, gone) }.message shouldContain "no such occurrence"
		wireMock.verify(0, patchRequestedFor(anyUrl()))
	}

	@Test
	fun `an unchanged occurrence writes nothing and returns Graph's version, whatever the etag`() {
		stubSeriesInstancesAround()
		val instance = json.readTree(occurrenceJson("2026-11-16", "Team sync"))
		val unchanged = GraphEventMapping.toRemote(instance, RemoteEventKey("AAMkSeries1", at("2026-11-16T08:00:00Z")))
		val etag = instance.get("@odata.etag").asString()

		adapter.updateEvent(source, CALENDAR, unchanged, etag).etag shouldBe etag
		adapter.updateEvent(source, CALENDAR, unchanged, "W/\"old\"").etag shouldBe etag
		wireMock.verify(0, patchRequestedFor(anyUrl()))
	}

	@Test
	fun `a changed occurrence with a stale etag is sent with it, and Graph's 412 is a change at Microsoft`() {
		stubSeriesInstancesAround()
		stubPatchOnlyIfMatch("$EVENTS/AAMkSeries1-20261116", "W/\"I6\"", occurrenceJson("2026-11-16", "Mine"))

		shouldThrow<RemoteEventChangedException> {
			adapter.updateEvent(source, CALENDAR, occurrence("2026-11-16T08:00:00Z", "Mine"), "W/\"old\"")
		}

		wireMock.verify(patchRequestedFor(urlPathEqualTo("$EVENTS/AAMkSeries1-20261116")).withHeader("If-Match", equalTo("W/\"old\"")))
	}

	@Test
	fun `after a pattern change, an override is written against the exception Graph put back - empty diff or not`() {
		stubRestoredException()

		adapter.updateEvent(source, CALENDAR, teamSync().copy(rrule = "FREQ=WEEKLY;BYDAY=MO;UNTIL=20261116T075959Z"), "W/\"DwAAABYAAAA3\"")
		wireMock.resetRequests()

		// The cockpit still has the exception's etag from before the pattern change.
		val moved = movedOverride()
		adapter.updateEvent(source, CALENDAR, moved, "W/\"DwAAABYAAAA4\"").etag shouldBe "W/\"restored\""
		wireMock.verify(0, patchRequestedFor(anyUrl()))

		val renamed = adapter.updateEvent(source, CALENDAR, moved.copy(title = "Team sync (renamed)"), "W/\"DwAAABYAAAA4\"")
		renamed.etag shouldBe "W/\"after-rename\""
		wireMock.verify(
			patchRequestedFor(urlPathEqualTo("$EVENTS/AAMkSeries1-20261019")).withHeader("If-Match", equalTo("W/\"restored\"")),
		)
	}

	@Test
	fun `after a pattern change, an override with an etag other than the exception's still is a change at Microsoft`() {
		stubRestoredException()

		adapter.updateEvent(source, CALENDAR, teamSync().copy(rrule = "FREQ=WEEKLY;BYDAY=MO;UNTIL=20261116T075959Z"), "W/\"DwAAABYAAAA3\"")
		wireMock.resetRequests()

		shouldThrow<RemoteEventChangedException> {
			adapter.updateEvent(source, CALENDAR, movedOverride().copy(title = "Team sync (renamed)"), "W/\"other\"")
		}
		wireMock.verify(
			patchRequestedFor(urlPathEqualTo("$EVENTS/AAMkSeries1-20261019")).withHeader("If-Match", equalTo("W/\"other\"")),
		)
	}

	@Test
	fun `a series gone at Microsoft has no occurrences - delete succeeds, change is a change at Microsoft`() {
		stub(get(urlPathEqualTo("$EVENTS/AAMkSeries1/instances")), """{"error": {"code": "ErrorItemNotFound"}}""", 404)
		val key = RemoteEventKey("AAMkSeries1", at("2026-11-16T08:00:00Z"))

		adapter.deleteEvent(source, CALENDAR, key, "W/\"I6\"").shouldBeNull()
		shouldThrow<RemoteEventChangedException> { adapter.updateEvent(source, CALENDAR, occurrence("2026-11-16T08:00:00Z", "Mine"), "W/\"I6\"") }
		val cancelled = occurrence("2026-11-16T08:00:00Z", "Team sync").copy(status = EventStatus.CANCELLED)
		shouldThrow<RemoteEventChangedException> { adapter.updateEvent(source, CALENDAR, cancelled, "W/\"I6\"") }
		shouldThrowExactly<CalendarProviderException> {
			adapter.createEvent(source, CALENDAR, occurrence("2026-11-16T08:00:00Z", "Mine"))
		}.message shouldContain "no such occurrence"

		// The weeks around the occurrence answered 404: the whole window is not asked as well.
		wireMock.verify(4, getRequestedFor(urlPathEqualTo("$EVENTS/AAMkSeries1/instances")))
		wireMock.verify(0, deleteRequestedFor(anyUrl()))
		wireMock.verify(0, patchRequestedFor(anyUrl()))
	}

	@Test
	fun `deleting with a stale etag is a change at Microsoft, other failures are provider errors`() {
		stub(delete(urlPathEqualTo("$EVENTS/AAMkSingle1")), recording("error-412-irresolvable-conflict.json"), 412)
		shouldThrow<RemoteEventChangedException> { adapter.deleteEvent(source, CALENDAR, RemoteEventKey("AAMkSingle1"), "W/\"old\"") }

		stub(delete(urlPathEqualTo("$EVENTS/AAMkSingle1")), """{"error": {"code": "ErrorInvalidRequest"}}""", 400)
		shouldThrowExactly<CalendarProviderException> { adapter.deleteEvent(source, CALENDAR, RemoteEventKey("AAMkSingle1"), null) }
			.message shouldContain "HTTP 400"
	}

	@Test
	fun `404 on a patch is a change at Microsoft`() {
		stub(get(urlPathEqualTo("$EVENTS/AAMkSingle1")), dentist())
		stub(patch(urlPathEqualTo("$EVENTS/AAMkSingle1")), """{"error": {"code": "ErrorItemNotFound"}}""", 404)
		val current = adapter.fetchEventsOf(dentist())

		shouldThrow<RemoteEventChangedException> { adapter.updateEvent(source, CALENDAR, current.copy(title = "Mine"), null) }
	}

	@Test
	fun `a changed pattern puts back the exceptions Graph dropped, with the fields they had`() {
		val before = seriesInstances()
		// Exchange answers the pattern change by turning the 19 October exception back into a plain occurrence.
		val after =
			before.map {
				if (it.get("id").asString() == "AAMkSeries1-20261019") {
					json.readTree(instanceJson("AAMkSeries1-20261019", "occurrence", "2026-10-19T07:00:00"))
				} else {
					it
				}
			}
		stub(get(urlPathEqualTo("$EVENTS/AAMkSeries1")), recording("series1-master.json"))
		wireMock.stubFor(
			get(urlPathEqualTo("$EVENTS/AAMkSeries1/instances"))
				.inScenario("pattern")
				.whenScenarioStateIs(Scenario.STARTED)
				.willReturn(jsonResponse(json.writeValueAsString(mapOf("value" to before)))),
		)
		wireMock.stubFor(
			patch(urlPathEqualTo("$EVENTS/AAMkSeries1"))
				.inScenario("pattern")
				.willSetStateTo("changed")
				.willReturn(jsonResponse(recording("series1-master.json"))),
		)
		wireMock.stubFor(
			get(urlPathEqualTo("$EVENTS/AAMkSeries1/instances"))
				.inScenario("pattern")
				.whenScenarioStateIs("changed")
				.willReturn(jsonResponse(json.writeValueAsString(mapOf("value" to after)))),
		)
		stub(patch(urlPathEqualTo("$EVENTS/AAMkSeries1-20261019")), occurrenceJson("2026-10-19", "Team sync (moved)"))

		adapter.updateEvent(source, CALENDAR, teamSync().copy(rrule = "FREQ=WEEKLY;BYDAY=MO;UNTIL=20261116T075959Z"), "W/\"DwAAABYAAAA3\"")

		wireMock.verify(2, patchRequestedFor(anyUrl()))
		val restore =
			wireMock.allServeEvents
				.map { it.request }
				.single { it.method.value() == "PATCH" && it.url.startsWith("$EVENTS/AAMkSeries1-20261019") }
		restore.getHeader("If-Match").shouldBeNull()
		val body = json.readTree(restore.bodyAsString)
		body.get("subject").asString() shouldBe "Team sync (moved)"
		body.get("start").get("dateTime").asString() shouldBe "2026-10-19T10:00:00"
		body.get("end").get("dateTime").asString() shouldBe "2026-10-19T10:30:00"
		body.has("recurrence") shouldBe false
	}

	@Test
	fun `exceptions Graph kept after a pattern change, and changes without a new pattern, are not patched again`() {
		stubSeries()
		stub(patch(urlPathEqualTo("$EVENTS/AAMkSeries1")), recording("series1-master.json"))

		adapter.updateEvent(source, CALENDAR, teamSync().copy(rrule = "FREQ=WEEKLY;BYDAY=MO;UNTIL=20261116T075959Z"), null)
		wireMock.verify(1, patchRequestedFor(anyUrl()))

		wireMock.resetRequests()
		adapter.updateEvent(source, CALENDAR, teamSync().copy(title = "Team sync (renamed)"), null)
		wireMock.verify(1, patchRequestedFor(anyUrl()))
		// Without a pattern change the exceptions are not read before the patch.
		wireMock.verify(1, getRequestedFor(urlPathEqualTo("$EVENTS/AAMkSeries1/instances")).withQueryParam("\$skip", absent()))
	}

	@Test
	fun `an exception moved further than the weeks around its occurrence is found in the whole window`() {
		val moved =
			instanceJson("AAMkSeries1-moved", "exception", "2027-02-01T08:00:00", originalStart = "2026-11-16T08:00:00Z")
		// The weeks around 16 November: the moved exception is not among them.
		stub(
			get(urlPathEqualTo("$EVENTS/AAMkSeries1/instances")).withQueryParam("startDateTime", equalTo("2026-10-12T08:00:00Z")),
			"""{"value": []}""",
		)
		stub(
			get(urlPathEqualTo("$EVENTS/AAMkSeries1/instances")).withQueryParam("startDateTime", equalTo("2025-10-21T00:00:00Z")),
			"""{"value": [$moved]}""",
		)
		stub(delete(urlPathEqualTo("$EVENTS/AAMkSeries1-moved")), "", 204)

		adapter.deleteEvent(source, CALENDAR, RemoteEventKey("AAMkSeries1", at("2026-11-16T08:00:00Z")), "W/\"AAMkSeries1-moved\"").shouldBeNull()

		wireMock.verify(
			deleteRequestedFor(urlPathEqualTo("$EVENTS/AAMkSeries1-moved")).withHeader("If-Match", equalTo("W/\"AAMkSeries1-moved\"")),
		)
	}

	@Test
	fun `changed attendees keep the type Graph has for them, new ones are required`() {
		val graph = json.readTree(dentist()) as tools.jackson.databind.node.ObjectNode
		graph.set(
			"attendees",
			json.readTree(
				"""
				[{"type": "optional", "emailAddress": {"address": "Sam.Sample@example.test", "name": "Sam Sample"}},
				 {"type": "resource", "emailAddress": {"address": "room.1@example.test", "name": "Room 1"}}]
				""".trimIndent(),
			),
		)
		stub(get(urlPathEqualTo("$EVENTS/AAMkSingle1")), json.writeValueAsString(graph))
		stub(patch(urlPathEqualTo("$EVENTS/AAMkSingle1")), json.writeValueAsString(graph))
		val current = GraphEventMapping.toRemote(graph, RemoteEventKey("AAMkSingle1"))

		val kim = Attendee("kim.demo@example.test", null, AttendeeStatus.NEEDS_ACTION)
		adapter.updateEvent(source, CALENDAR, current.copy(attendees = current.attendees + kim), null)

		val types = mutableMapOf<String, String>()
		for (attendee in sentBody("PATCH").get("attendees")) {
			types[attendee.at("/emailAddress/address").asString()] = attendee.get("type").asString()
		}
		types shouldBe mapOf("Sam.Sample@example.test" to "optional", "room.1@example.test" to "resource", "kim.demo@example.test" to "required")
	}

	@Test
	fun `calendars beyond the page limit are refused`() {
		stub(
			get(urlPathEqualTo("$API/me/calendars")),
			"""{"value": [], "@odata.nextLink": "https://graph.microsoft.com/v1.0/me/calendars?${'$'}skip=2"}""",
		)

		shouldThrowExactly<CalendarProviderException> { adapter.listCalendars(source) }.message shouldContain "too many pages"
		wireMock.verify(100, getRequestedFor(urlPathEqualTo("$API/me/calendars")))
	}

	@Test
	fun `events are read, changed and deleted through their calendar`() {
		val other = "AAMkCalShared"
		stub(get(urlPathEqualTo("$API/me/calendars/$other/events/AAMkSingle1")), dentist())
		stub(patch(urlPathEqualTo("$API/me/calendars/$other/events/AAMkSingle1")), dentist(subject = "Dentist (new)"))
		stub(delete(urlPathEqualTo("$API/me/calendars/$other/events/AAMkSingle1")), "", 204)
		val current = adapter.fetchEventsOf(dentist())

		adapter.updateEvent(source, other, current.copy(title = "Dentist (new)"), null).title shouldBe "Dentist (new)"
		adapter.deleteEvent(source, other, RemoteEventKey("AAMkSingle1"), null)

		wireMock.verify(patchRequestedFor(urlPathEqualTo("$API/me/calendars/$other/events/AAMkSingle1")))
		wireMock.verify(deleteRequestedFor(urlPathEqualTo("$API/me/calendars/$other/events/AAMkSingle1")))
		wireMock.verify(0, anyRequestedFor(urlPathEqualTo("$API/me/events/AAMkSingle1")))
	}

	// --- review fixes: further cases (test-writer)

	@Test
	fun `a split keeps only the exceptions before it - the one the shortened pattern no longer has is not put back`() {
		val december = "AAMkSeries1-20261207"
		// Before the split: 19 October is moved (recorded), 7 December is renamed.
		val before =
			seriesInstances().map {
				if (it.get("id").asString() == december) {
					json.readTree(instanceJson(december, "exception", "2026-12-07T08:00:00", originalStart = "2026-12-07T08:00:00Z"))
				} else {
					it
				}
			}
		// After the series ends on 15 November, Graph has the occurrences up to 9 November, all plain again.
		val after =
			before
				.filter { Instant.parse(it.at("/start/dateTime").asString().take(19) + "Z") < Instant.parse("2026-11-15T00:00:00Z") }
				.map {
					if (it.get("id").asString() == "AAMkSeries1-20261019") {
						json.readTree(instanceJson("AAMkSeries1-20261019", "occurrence", "2026-10-19T07:00:00"))
					} else {
						it
					}
				}
		stubPatternChange(before, after)
		stub(patch(urlPathEqualTo("$EVENTS/AAMkSeries1-20261019")), occurrenceJson("2026-10-19", "Team sync (moved)"))

		val stored =
			adapter.updateEvent(source, CALENDAR, teamSync().copy(rrule = "FREQ=WEEKLY;BYDAY=MO;UNTIL=20261116T075959Z"), "W/\"DwAAABYAAAA3\"")

		stored.key shouldBe RemoteEventKey("AAMkSeries1")
		wireMock.verify(2, patchRequestedFor(anyUrl()))
		wireMock.verify(patchRequestedFor(urlPathEqualTo("$EVENTS/AAMkSeries1")).withHeader("If-Match", equalTo("W/\"DwAAABYAAAA3\"")))
		wireMock.verify(patchRequestedFor(urlPathEqualTo("$EVENTS/AAMkSeries1-20261019")).withHeader("If-Match", absent()))
		wireMock.verify(0, patchRequestedFor(urlPathEqualTo("$EVENTS/$december")))
	}

	@Test
	fun `an exception that cannot be put back after a pattern change does not fail the change of the series`() {
		val before = seriesInstances()
		val after =
			before.map {
				if (it.get("id").asString() == "AAMkSeries1-20261019") {
					json.readTree(instanceJson("AAMkSeries1-20261019", "occurrence", "2026-10-19T07:00:00"))
				} else {
					it
				}
			}
		stubPatternChange(before, after)
		stub(patch(urlPathEqualTo("$EVENTS/AAMkSeries1-20261019")), """{"error": {"code": "ErrorInternalServerError"}}""", 500)

		val stored =
			adapter.updateEvent(source, CALENDAR, teamSync().copy(rrule = "FREQ=WEEKLY;BYDAY=MO;UNTIL=20261116T075959Z"), "W/\"DwAAABYAAAA3\"")

		stored.key shouldBe RemoteEventKey("AAMkSeries1")
		wireMock.verify(patchRequestedFor(urlPathEqualTo("$EVENTS/AAMkSeries1-20261019")))
	}

	@Test
	fun `a moved exception found only in the whole window is changed with If-Match`() {
		val moved = instanceJson("AAMkSeries1-moved", "exception", "2027-02-01T08:00:00", originalStart = "2026-11-16T08:00:00Z")
		stub(
			get(urlPathEqualTo("$EVENTS/AAMkSeries1/instances")).withQueryParam("startDateTime", equalTo("2026-10-12T08:00:00Z")),
			"""{"value": []}""",
		)
		stub(
			get(urlPathEqualTo("$EVENTS/AAMkSeries1/instances")).withQueryParam("startDateTime", equalTo("2025-10-21T00:00:00Z")),
			"""{"value": [$moved]}""",
		)
		stub(patch(urlPathEqualTo("$EVENTS/AAMkSeries1-moved")), moved.replace("\"Team sync\"", "\"Team sync (far)\""))
		val far =
			occurrence("2026-11-16T08:00:00Z", "Team sync (far)").copy(
				timing =
					Timing.Timed(Instant.parse("2027-02-01T08:00:00Z"), Instant.parse("2027-02-01T08:30:00Z"), ZoneId.of("Europe/Berlin")),
			)

		val stored = adapter.updateEvent(source, CALENDAR, far, "W/\"AAMkSeries1-moved\"")

		stored.key shouldBe RemoteEventKey("AAMkSeries1", at("2026-11-16T08:00:00Z"))
		stored.title shouldBe "Team sync (far)"
		wireMock.verify(
			patchRequestedFor(urlPathEqualTo("$EVENTS/AAMkSeries1-moved")).withHeader("If-Match", equalTo("W/\"AAMkSeries1-moved\"")),
		)
		sentBody("PATCH").get("subject").asString() shouldBe "Team sync (far)"
	}

	@Test
	fun `an occurrence in neither the weeks around it nor the window is gone - delete succeeds, change fails`() {
		stub(get(urlPathEqualTo("$EVENTS/AAMkSeries1/instances")), """{"value": []}""")
		val key = RemoteEventKey("AAMkSeries1", at("2026-11-16T08:00:00Z"))

		adapter.deleteEvent(source, CALENDAR, key, "W/\"I6\"").shouldBeNull()
		wireMock.verify(
			getRequestedFor(urlPathEqualTo("$EVENTS/AAMkSeries1/instances")).withQueryParam("startDateTime", equalTo("2026-10-12T08:00:00Z")),
		)
		wireMock.verify(
			getRequestedFor(urlPathEqualTo("$EVENTS/AAMkSeries1/instances")).withQueryParam("startDateTime", equalTo("2025-10-21T00:00:00Z")),
		)
		wireMock.verify(0, deleteRequestedFor(anyUrl()))

		shouldThrow<RemoteEventChangedException> { adapter.updateEvent(source, CALENDAR, occurrence("2026-11-16T08:00:00Z", "Mine"), "W/\"I6\"") }
		wireMock.verify(0, patchRequestedFor(anyUrl()))
	}

	@Test
	fun `an occurrence found in the weeks around it is not looked up in the whole window`() {
		stubSeriesInstancesAround()
		stub(delete(urlPathEqualTo("$EVENTS/AAMkSeries1-20261116")), "", 204)

		adapter.deleteEvent(source, CALENDAR, RemoteEventKey("AAMkSeries1", at("2026-11-16T08:00:00Z")), "W/\"I6\"")

		wireMock.verify(1, getRequestedFor(urlPathEqualTo("$EVENTS/AAMkSeries1/instances")))
		wireMock.verify(
			getRequestedFor(urlPathEqualTo("$EVENTS/AAMkSeries1/instances")).withQueryParam("startDateTime", equalTo("2026-10-12T08:00:00Z")),
		)
		wireMock.verify(deleteRequestedFor(urlPathEqualTo("$EVENTS/AAMkSeries1-20261116")))
	}

	@Test
	fun `a changed occurrence keeps the attendee types Graph has, matched by email ignoring case`() {
		val instance =
			json.readTree(
				instanceJson("AAMkSeries1-20261116", "occurrence", "2026-11-16T08:00:00"),
			) as tools.jackson.databind.node.ObjectNode
		instance.set(
			"attendees",
			json.readTree("""[{"type": "optional", "emailAddress": {"address": "Sam.Sample@Example.TEST", "name": "Sam Sample"}}]"""),
		)
		stub(get(urlPathEqualTo("$EVENTS/AAMkSeries1/instances")), json.writeValueAsString(mapOf("value" to listOf(instance))))
		stub(patch(urlPathEqualTo("$EVENTS/AAMkSeries1-20261116")), json.writeValueAsString(instance))
		val sam = Attendee("sam.sample@example.test", "Sam Sample", AttendeeStatus.NEEDS_ACTION)
		val kim = Attendee("kim.demo@example.test", null, AttendeeStatus.NEEDS_ACTION)

		adapter.updateEvent(source, CALENDAR, occurrence("2026-11-16T08:00:00Z", "Team sync").copy(attendees = listOf(sam, kim)), "W/\"I6\"")

		val types = sentBody("PATCH").get("attendees").associate { it.at("/emailAddress/address").asString() to it.get("type").asString() }
		types shouldBe mapOf("sam.sample@example.test" to "optional", "kim.demo@example.test" to "required")
	}

	@Test
	fun `a series of a shared calendar is read, and its occurrences deleted, through that calendar`() {
		val other = "AAMkCalShared"
		val events = "$API/me/calendars/$other/events"
		stub(
			get(urlPathEqualTo("$API/me/calendars/$other/calendarView/delta")),
			"""{"value": [{"id": "AAMkSeries1-20261026", "type": "occurrence", "seriesMasterId": "AAMkSeries1"}],
			 "@odata.deltaLink": "https://graph.microsoft.com/v1.0/me/calendars/$other/calendarView/delta?${'$'}deltatoken=D-SHARED"}""",
		)
		stub(get(urlPathEqualTo("$events/AAMkSeries1")), recording("series1-master.json"))
		stub(get(urlPathEqualTo("$events/AAMkSeries1/instances")), json.writeValueAsString(mapOf("value" to seriesInstances())))
		stub(delete(urlPathEqualTo("$events/AAMkSeries1-20261116")), "", 204)

		val fetched = adapter.fetchEvents(source, other, null)
		adapter.deleteEvent(source, other, RemoteEventKey("AAMkSeries1", at("2026-11-16T08:00:00Z")), "W/\"I6\"")

		fetched.events.map { it.key } shouldContainExactly
			listOf(RemoteEventKey("AAMkSeries1"), RemoteEventKey("AAMkSeries1", at("2026-10-19T07:00:00Z")))
		fetched.events.first().rrule shouldBe "FREQ=WEEKLY;BYDAY=MO;UNTIL=20261228T225959Z"
		wireMock.verify(deleteRequestedFor(urlPathEqualTo("$events/AAMkSeries1-20261116")).withHeader("If-Match", equalTo("W/\"I6\"")))
		wireMock.verify(0, anyRequestedFor(urlPathEqualTo("$EVENTS/AAMkSeries1")))
		wireMock.verify(0, anyRequestedFor(urlPathEqualTo("$EVENTS/AAMkSeries1/instances")))
		wireMock.verify(0, anyRequestedFor(urlPathEqualTo("$API/me/events/AAMkSeries1")))
	}

	@Test
	fun `a series whose rule is removed is patched with no recurrence, without reading or restoring its exceptions`() {
		stub(get(urlPathEqualTo("$EVENTS/AAMkSeries1")), recording("series1-master.json"))
		stub(patch(urlPathEqualTo("$EVENTS/AAMkSeries1")), recording("series1-master.json").replace("\"seriesMaster\"", "\"singleInstance\""))

		val stored = adapter.updateEvent(source, CALENDAR, teamSync().copy(rrule = null), "W/\"DwAAABYAAAA3\"")

		val body = sentBody("PATCH")
		body.propertyNames().toList() shouldContainExactly listOf("recurrence")
		body.get("recurrence").isNull shouldBe true
		stored.key shouldBe RemoteEventKey("AAMkSeries1")
		stored.rrule.shouldBeNull()
		wireMock.verify(0, getRequestedFor(urlPathEqualTo("$EVENTS/AAMkSeries1/instances")))
		wireMock.verify(1, patchRequestedFor(anyUrl()))
	}

	@Test
	fun `a delta item without id is skipped`() {
		stubDeltaPage(
			"""{"type": "singleInstance", "subject": "No id",
			 "start": {"dateTime": "2026-10-23T09:00:00", "timeZone": "UTC"}, "end": {"dateTime": "2026-10-23T09:30:00", "timeZone": "UTC"}}""",
			"""{"id": "AAMkFine", "type": "singleInstance", "subject": "Fine",
			 "start": {"dateTime": "2026-10-23T10:30:00", "timeZone": "UTC"}, "end": {"dateTime": "2026-10-23T11:00:00", "timeZone": "UTC"}}""",
		)

		adapter.fetchEvents(source, CALENDAR, null).events.map { it.key } shouldContainExactly listOf(RemoteEventKey("AAMkFine"))
	}

	// --- round-2 fixes (restored etags, empty diffs, series gone): further cases (test-writer)

	@Test
	fun `after a pattern change, cancelling an override deletes the exception Graph put back with its current etag`() {
		stubRestoredException()
		stub(delete(urlPathEqualTo("$EVENTS/AAMkSeries1-20261019")).atPriority(9), recording("error-412-irresolvable-conflict.json"), 412)
		stub(delete(urlPathEqualTo("$EVENTS/AAMkSeries1-20261019")).withHeader("If-Match", equalTo("W/\"restored\"")).atPriority(1), "", 204)

		adapter.updateEvent(source, CALENDAR, teamSync().copy(rrule = "FREQ=WEEKLY;BYDAY=MO;UNTIL=20261116T075959Z"), "W/\"DwAAABYAAAA3\"")
		wireMock.resetRequests()

		val cancelled = movedOverride().copy(status = EventStatus.CANCELLED)
		adapter.updateEvent(source, CALENDAR, cancelled, "W/\"DwAAABYAAAA4\"").etag.shouldBeNull()

		wireMock.verify(1, deleteRequestedFor(anyUrl()))
		wireMock.verify(
			deleteRequestedFor(urlPathEqualTo("$EVENTS/AAMkSeries1-20261019")).withHeader("If-Match", equalTo("W/\"restored\"")),
		)
	}

	@Test
	fun `an exception put back after a pattern change and then changed in Outlook is a change at Microsoft`() {
		stubRestoredException()
		adapter.updateEvent(source, CALENDAR, teamSync().copy(rrule = "FREQ=WEEKLY;BYDAY=MO;UNTIL=20261116T075959Z"), "W/\"DwAAABYAAAA3\"")
		// Someone renames the put-back exception in Outlook before the cockpit writes it: Graph's etag moves on.
		val edited = movedOverrideJson().put("subject", "Team sync (edited in Outlook)").put("@odata.etag", "W/\"outlook-edit\"")
		wireMock.stubFor(
			get(urlPathEqualTo("$EVENTS/AAMkSeries1/instances"))
				.inScenario("pattern")
				.whenScenarioStateIs("restored")
				.willReturn(jsonResponse(json.writeValueAsString(mapOf("value" to listOf(edited))))),
		)
		wireMock.resetRequests()

		shouldThrow<RemoteEventChangedException> {
			adapter.updateEvent(source, CALENDAR, movedOverride().copy(title = "Team sync (renamed)"), "W/\"DwAAABYAAAA4\"")
		}
		// The cockpit's own etag goes out, not the one remembered for the put-back version.
		wireMock.verify(
			patchRequestedFor(urlPathEqualTo("$EVENTS/AAMkSeries1-20261019")).withHeader("If-Match", equalTo("W/\"DwAAABYAAAA4\"")),
		)
		wireMock.verify(0, patchRequestedFor(anyUrl()).withHeader("If-Match", equalTo("W/\"restored\"")))
	}

	@Test
	fun `an exception Graph kept unchanged through a pattern change is not patched, and its override is written with Graph's new etag`() {
		val before = seriesInstances()
		// Graph keeps the exception with all its fields, in a new version.
		val after =
			before.map {
				if (it.get("id").asString() == "AAMkSeries1-20261019") {
					(it.deepCopy() as tools.jackson.databind.node.ObjectNode).put("@odata.etag", "W/\"kept\"")
				} else {
					it
				}
			}
		stubPatternChange(before, after)
		stubPatchOnlyIfMatch(
			"$EVENTS/AAMkSeries1-20261019",
			"W/\"kept\"",
			json.writeValueAsString(movedOverrideJson().put("subject", "Team sync (renamed)").put("@odata.etag", "W/\"after-rename\"")),
		)

		adapter.updateEvent(source, CALENDAR, teamSync().copy(rrule = "FREQ=WEEKLY;BYDAY=MO;UNTIL=20261116T075959Z"), "W/\"DwAAABYAAAA3\"")
		// Only the series is patched: the exception already is what it was.
		wireMock.verify(1, patchRequestedFor(anyUrl()))
		wireMock.verify(patchRequestedFor(urlPathEqualTo("$EVENTS/AAMkSeries1")))
		wireMock.resetRequests()

		val renamed = adapter.updateEvent(source, CALENDAR, movedOverride().copy(title = "Team sync (renamed)"), "W/\"DwAAABYAAAA4\"")

		renamed.etag shouldBe "W/\"after-rename\""
		wireMock.verify(
			patchRequestedFor(urlPathEqualTo("$EVENTS/AAMkSeries1-20261019")).withHeader("If-Match", equalTo("W/\"kept\"")),
		)
	}

	@Test
	fun `an exception Graph kept but changed through a pattern change is left alone and its old etag stays stale`() {
		val before = seriesInstances()
		// Graph keeps the exception, but with other fields than the cockpit knows of (changed meanwhile).
		val after =
			before.map {
				if (it.get("id").asString() == "AAMkSeries1-20261019") {
					(it.deepCopy() as tools.jackson.databind.node.ObjectNode)
						.put("subject", "Team sync (edited in Outlook)")
						.put("@odata.etag", "W/\"kept-changed\"")
				} else {
					it
				}
			}
		stubPatternChange(before, after)
		stubPatchOnlyIfMatch("$EVENTS/AAMkSeries1-20261019", "W/\"kept-changed\"", json.writeValueAsString(movedOverrideJson()))

		adapter.updateEvent(source, CALENDAR, teamSync().copy(rrule = "FREQ=WEEKLY;BYDAY=MO;UNTIL=20261116T075959Z"), "W/\"DwAAABYAAAA3\"")
		wireMock.verify(1, patchRequestedFor(anyUrl()))
		wireMock.verify(0, patchRequestedFor(urlPathEqualTo("$EVENTS/AAMkSeries1-20261019")))
		wireMock.resetRequests()

		shouldThrow<RemoteEventChangedException> {
			adapter.updateEvent(source, CALENDAR, movedOverride().copy(title = "Team sync (renamed)"), "W/\"DwAAABYAAAA4\"")
		}
		wireMock.verify(
			patchRequestedFor(urlPathEqualTo("$EVENTS/AAMkSeries1-20261019")).withHeader("If-Match", equalTo("W/\"DwAAABYAAAA4\"")),
		)
	}

	@Test
	fun `an exception that could not be put back is not remembered - writing its override with the old etag is a change at Microsoft`() {
		val before = seriesInstances()
		val plain = json.readTree(instanceJson("AAMkSeries1-20261019", "occurrence", "2026-10-19T07:00:00"))
		val after = before.map { if (it.get("id").asString() == "AAMkSeries1-20261019") plain else it }
		stubPatternChange(before, after)
		wireMock.stubFor(
			patch(urlPathEqualTo("$EVENTS/AAMkSeries1-20261019"))
				.inScenario("pattern")
				.whenScenarioStateIs("changed")
				.willSetStateTo("failed")
				.willReturn(aResponse().withStatus(500).withBody("""{"error": {"code": "ErrorInternalServerError"}}""")),
		)
		wireMock.stubFor(
			get(urlPathEqualTo("$EVENTS/AAMkSeries1/instances"))
				.inScenario("pattern")
				.whenScenarioStateIs("failed")
				.willReturn(jsonResponse(json.writeValueAsString(mapOf("value" to after)))),
		)
		stubPatchOnlyIfMatch("$EVENTS/AAMkSeries1-20261019", "W/\"AAMkSeries1-20261019\"", json.writeValueAsString(movedOverrideJson()))

		adapter.updateEvent(source, CALENDAR, teamSync().copy(rrule = "FREQ=WEEKLY;BYDAY=MO;UNTIL=20261116T075959Z"), "W/\"DwAAABYAAAA3\"")
		wireMock.resetRequests()

		shouldThrow<RemoteEventChangedException> { adapter.updateEvent(source, CALENDAR, movedOverride(), "W/\"DwAAABYAAAA4\"") }
		wireMock.verify(
			patchRequestedFor(urlPathEqualTo("$EVENTS/AAMkSeries1-20261019")).withHeader("If-Match", equalTo("W/\"DwAAABYAAAA4\"")),
		)
	}

	@Test
	fun `a series gone at Microsoft by the time the whole window is asked has no such occurrence`() {
		// The weeks around 16 November have nothing; the whole window answers 404: the series was deleted meanwhile.
		stub(
			get(urlPathEqualTo("$EVENTS/AAMkSeries1/instances")).withQueryParam("startDateTime", equalTo("2026-10-12T08:00:00Z")),
			"""{"value": []}""",
		)
		stub(
			get(urlPathEqualTo("$EVENTS/AAMkSeries1/instances")).withQueryParam("startDateTime", equalTo("2025-10-21T00:00:00Z")),
			"""{"error": {"code": "ErrorItemNotFound"}}""",
			404,
		)
		val key = RemoteEventKey("AAMkSeries1", at("2026-11-16T08:00:00Z"))

		adapter.deleteEvent(source, CALENDAR, key, "W/\"I6\"").shouldBeNull()
		shouldThrow<RemoteEventChangedException> { adapter.updateEvent(source, CALENDAR, occurrence("2026-11-16T08:00:00Z", "Mine"), "W/\"I6\"") }

		val wholeWindow =
			getRequestedFor(urlPathEqualTo("$EVENTS/AAMkSeries1/instances")).withQueryParam("startDateTime", equalTo("2025-10-21T00:00:00Z"))
		wireMock.verify(2, wholeWindow)
		wireMock.verify(0, deleteRequestedFor(anyUrl()))
		wireMock.verify(0, patchRequestedFor(anyUrl()))
	}

	@Test
	fun `an unchanged series writes nothing and returns Graph's series, whatever the etag`() {
		stubSeries()

		val stored = adapter.updateEvent(source, CALENDAR, teamSync(), "W/\"old\"")

		stored.key shouldBe RemoteEventKey("AAMkSeries1")
		stored.etag shouldBe "W/\"DwAAABYAAAA3\""
		stored.rrule shouldBe teamSync().rrule
		wireMock.verify(0, patchRequestedFor(anyUrl()))
		wireMock.verify(0, deleteRequestedFor(anyUrl()))
	}

	@Test
	fun `a changed series with a stale etag is sent with it, and Graph's 412 is a change at Microsoft`() {
		stubSeries()
		stubPatchOnlyIfMatch("$EVENTS/AAMkSeries1", "W/\"DwAAABYAAAA3\"", recording("series1-master.json"))

		shouldThrow<RemoteEventChangedException> {
			adapter.updateEvent(source, CALENDAR, teamSync().copy(title = "Team sync (renamed)"), "W/\"old\"")
		}
		wireMock.verify(patchRequestedFor(urlPathEqualTo("$EVENTS/AAMkSeries1")).withHeader("If-Match", equalTo("W/\"old\"")))
	}

	// --- helpers

	/** The recorded 19 October exception of AAMkSeries1 (etag DwAAABYAAAA4), as a copy. */
	private fun movedOverrideJson(): tools.jackson.databind.node.ObjectNode =
		seriesInstances().single { it.get("id").asString() == "AAMkSeries1-20261019" }.deepCopy() as tools.jackson.databind.node.ObjectNode

	/**
	 * Series AAMkSeries1 whose `/instances` answer [before] until its master is patched (pattern change), and [after]
	 * from then on.
	 */
	private fun stubPatternChange(
		before: List<JsonNode>,
		after: List<JsonNode>,
	) {
		stub(get(urlPathEqualTo("$EVENTS/AAMkSeries1")), recording("series1-master.json"))
		wireMock.stubFor(
			get(urlPathEqualTo("$EVENTS/AAMkSeries1/instances"))
				.inScenario("pattern")
				.whenScenarioStateIs(Scenario.STARTED)
				.willReturn(jsonResponse(json.writeValueAsString(mapOf("value" to before)))),
		)
		wireMock.stubFor(
			patch(urlPathEqualTo("$EVENTS/AAMkSeries1"))
				.inScenario("pattern")
				.willSetStateTo("changed")
				.willReturn(jsonResponse(recording("series1-master.json"))),
		)
		wireMock.stubFor(
			get(urlPathEqualTo("$EVENTS/AAMkSeries1/instances"))
				.inScenario("pattern")
				.whenScenarioStateIs("changed")
				.willReturn(jsonResponse(json.writeValueAsString(mapOf("value" to after)))),
		)
	}

	/**
	 * Series AAMkSeries1 whose pattern change turns the 19 October exception (etag DwAAABYAAAA4) back into a plain
	 * occurrence, which the adapter puts back: Graph then has the exception again with etag `restored`. A PATCH of it with
	 * `If-Match: restored` succeeds (etag `after-rename`), any other If-Match gets 412.
	 */
	private fun stubRestoredException() {
		val before = seriesInstances()
		val exception = before.single { it.get("id").asString() == "AAMkSeries1-20261019" } as tools.jackson.databind.node.ObjectNode
		val restored = exception.deepCopy().put("@odata.etag", "W/\"restored\"")
		val after =
			before.map {
				if (it === exception) json.readTree(instanceJson("AAMkSeries1-20261019", "occurrence", "2026-10-19T07:00:00")) else it
			}
		stubPatternChange(before, after)
		wireMock.stubFor(
			patch(urlPathEqualTo("$EVENTS/AAMkSeries1-20261019"))
				.inScenario("pattern")
				.whenScenarioStateIs("changed")
				.willSetStateTo("restored")
				.willReturn(jsonResponse(json.writeValueAsString(restored))),
		)
		wireMock.stubFor(
			get(urlPathEqualTo("$EVENTS/AAMkSeries1/instances"))
				.inScenario("pattern")
				.whenScenarioStateIs("restored")
				.willReturn(jsonResponse(json.writeValueAsString(mapOf("value" to before.map { if (it === exception) restored else it })))),
		)
		stubPatchOnlyIfMatch(
			"$EVENTS/AAMkSeries1-20261019",
			"W/\"restored\"",
			json.writeValueAsString(restored.deepCopy().put("subject", "Team sync (renamed)").put("@odata.etag", "W/\"after-rename\"")),
		)
	}

	/** The 19 October exception of AAMkSeries1 as the cockpit has it after a sync. */
	private fun movedOverride(): RemoteEvent {
		val exception = seriesInstances().single { it.get("id").asString() == "AAMkSeries1-20261019" }
		return GraphEventMapping.toRemote(exception, RemoteEventKey("AAMkSeries1", at("2026-10-19T07:00:00Z")))
	}

	/** PATCH [path] answers [body] for `If-Match: [etag]` and 412 for any other If-Match. */
	private fun stubPatchOnlyIfMatch(
		path: String,
		etag: String,
		body: String,
	) {
		stub(patch(urlPathEqualTo(path)).atPriority(9), recording("error-412-irresolvable-conflict.json"), 412)
		stub(patch(urlPathEqualTo(path)).withHeader("If-Match", equalTo(etag)).atPriority(1), body)
	}

	/** All instances of series AAMkSeries1 as recorded, in one list. */
	private fun seriesInstances(): List<JsonNode> =
		json.readTree(recording("series1-instances-page1.json")).get("value").toList() +
			json.readTree(recording("series1-instances-page2.json")).get("value").toList()

	private fun jsonResponse(body: String) = aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(body)

	/** Series AAMkSeries1 as recorded in `series1-master.json`, as the cockpit has it. */
	private fun teamSync() =
		RemoteEvent(
			key = RemoteEventKey("AAMkSeries1"),
			etag = "W/\"DwAAABYAAAA3\"",
			title = "Team sync",
			timing = Timing.Timed(Instant.parse("2026-10-05T07:00:00Z"), Instant.parse("2026-10-05T07:30:00Z"), ZoneId.of("Europe/Berlin")),
			rrule = "FREQ=WEEKLY;BYDAY=MO;UNTIL=20261228T225959Z",
			status = EventStatus.TENTATIVE,
			reminderMinutes = listOf(15),
		)

	private fun nextCursor(
		skipToken: String,
		full: Boolean,
		seen: List<String>,
	) = json.writeValueAsString(
		mapOf(
			"mode" to "next",
			"anchor" to "2026-10-21",
			"link" to "/me/calendars/$CALENDAR/calendarView/delta?\$skiptoken=$skipToken",
			"full" to full,
			"seen" to seen,
		),
	)

	/** The first page of a full listing: [items] (Graph events as JSON) and a delta link. */
	private fun stubDeltaPage(vararg items: String) {
		stub(
			get(urlPathEqualTo(DELTA)).withQueryParam("startDateTime", equalTo("2025-10-21T00:00:00Z")),
			"""{"value": [${items.joinToString(",")}],
			 "@odata.deltaLink": "https://graph.microsoft.com/v1.0/me/calendars/$CALENDAR/calendarView/delta?${'$'}deltatoken=D-NEW"}""",
		)
	}

	/** An instance of series [seriesId] starting at [start] (UTC, 30 minutes). */
	private fun instanceJson(
		id: String,
		type: String,
		start: String,
		seriesId: String = "AAMkSeries1",
		originalStart: String? = null,
	) = """
		{"@odata.etag": "W/\"$id\"", "id": "$id", "type": "$type", "seriesMasterId": "$seriesId", "subject": "Team sync",
		 "originalStartTimeZone": "W. Europe Standard Time", "isAllDay": false, "showAs": "tentative",
		 ${originalStart?.let { "\"originalStart\": \"$it\"," } ?: ""}
		 "start": {"dateTime": "$start", "timeZone": "UTC"},
		 "end": {"dateTime": "${Instant.parse("${start}Z").plusSeconds(1800).toString().removeSuffix("Z")}", "timeZone": "UTC"}}
		""".trimIndent()

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
		stub(get(urlPathEqualTo("$EVENTS/AAMkSeries1")), recording("series1-master.json"))
		stub(
			get(urlPathEqualTo("$EVENTS/AAMkSeries1/instances")).withQueryParam("\$skip", absent()),
			recording("series1-instances-page1.json"),
		)
		stub(
			get(urlPathEqualTo("$EVENTS/AAMkSeries1/instances")).withQueryParam("\$skip", equalTo("6")),
			recording("series1-instances-page2.json"),
		)
	}

	/** The instances lookup around one occurrence: all instances of the series, in one page. */
	private fun stubSeriesInstancesAround() {
		val all =
			json.readTree(recording("series1-instances-page1.json")).get("value").toList() +
				json.readTree(recording("series1-instances-page2.json")).get("value").toList()
		stub(get(urlPathEqualTo("$EVENTS/AAMkSeries1/instances")), json.writeValueAsString(mapOf("value" to all)))
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

		/** The events of [CALENDAR], as the adapter addresses them. */
		const val EVENTS = "$API/me/calendars/$CALENDAR/events"
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
