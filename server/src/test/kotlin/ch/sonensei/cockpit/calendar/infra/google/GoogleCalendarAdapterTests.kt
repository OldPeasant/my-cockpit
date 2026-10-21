package ch.sonensei.cockpit.calendar.infra.google

import ch.sonensei.cockpit.calendar.api.Timing
import ch.sonensei.cockpit.calendar.model.Attendee
import ch.sonensei.cockpit.calendar.model.AttendeeStatus
import ch.sonensei.cockpit.calendar.model.CalendarSource
import ch.sonensei.cockpit.calendar.model.CalendarSourceType
import ch.sonensei.cockpit.calendar.model.EventStatus
import ch.sonensei.cockpit.calendar.model.EventTransparency
import ch.sonensei.cockpit.calendar.model.OccurrenceId
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
import com.github.tomakehurst.wiremock.client.WireMock.absent
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.anyUrl
import com.github.tomakehurst.wiremock.client.WireMock.delete
import com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.equalToJson
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.matching
import com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath
import com.github.tomakehurst.wiremock.client.WireMock.patch
import com.github.tomakehurst.wiremock.client.WireMock.patchRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
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
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * [GoogleCalendarAdapter] without a Spring context against WireMock playing the Calendar API v3. The response bodies
 * are recordings of the API's resources (`src/test/resources/.../calendar/infra/google`, fictitious data).
 */
class GoogleCalendarAdapterTests {
	private val tokens = FixedTokens()
	private val json = JsonMapper.builder().build()
	private val adapter = GoogleCalendarAdapter(tokens, json, wireMock.baseUrl() + API)
	private val source =
		CalendarSource(CalendarSourceType.GOOGLE, "Google").apply { config = """{"connectionId": "$CONNECTION"}""" }

	@BeforeEach
	fun reset() {
		wireMock.resetAll()
	}

	// --- calendar list

	@Test
	fun `listCalendars reads every page, prefers the user's name and skips deleted and free-busy calendars`() {
		stub(get(urlPathEqualTo("$API/users/me/calendarList")).withQueryParam("pageToken", absent()), recording("calendar-list-page1.json"))
		stub(get(urlPathEqualTo("$API/users/me/calendarList")).withQueryParam("pageToken", equalTo("CL-page-2")), recording("calendar-list-page2.json"))

		adapter.listCalendars(source) shouldContainExactly
			listOf(
				RemoteCalendar("alex.example@example.test", "alex.example@example.test", "#9fe1e7", readOnly = false),
				RemoteCalendar("c_team0123456789@group.calendar.google.com", "Team", "#16a765", readOnly = false),
				RemoteCalendar("en.swiss#holiday@group.v.calendar.google.com", "Holidays in Switzerland", "#cd74e6", readOnly = true),
			)
		wireMock.verify(2, getRequestedFor(urlPathEqualTo("$API/users/me/calendarList")).withHeader("Authorization", equalTo("Bearer $TOKEN")))
	}

	// --- pull

	@Test
	fun `the first fetch lists the calendar completely, page by page, and ends with the sync token as cursor`() {
		stubFullListing()

		val first = adapter.fetchEvents(source, "primary", null)
		first.complete shouldBe true
		first.hasMore shouldBe true
		first.cursor shouldBe "full:EV-page-2"

		val second = adapter.fetchEvents(source, "primary", first.cursor)
		second.complete shouldBe true
		second.hasMore shouldBe false
		second.cursor shouldBe "sync:EV-sync-1"

		wireMock.verify(
			getRequestedFor(urlPathEqualTo(EVENTS))
				.withQueryParam("singleEvents", equalTo("false"))
				.withQueryParam("showDeleted", equalTo("true"))
				.withQueryParam("syncToken", absent()),
		)
		// A full listing needs no lookups of cancelled instances: they are in it.
		wireMock.verify(0, getRequestedFor(urlPathEqualTo(EVENTS)).withQueryParam("iCalUID", matching(".*")))
	}

	@Test
	fun `events map to remote events - times and zones, attendees, reminders, conference, recurrence and exceptions`() {
		stubFullListing()
		val first = adapter.fetchEvents(source, "primary", null)
		val second = adapter.fetchEvents(source, "primary", first.cursor)
		val zurich = ZoneId.of("Europe/Zurich")

		first.events.first() shouldBe
			RemoteEvent(
				key = RemoteEventKey("evt001"),
				etag = "\"3523456789012000\"",
				title = "Project kickoff",
				timing = Timing.Timed(Instant.parse("2026-10-22T08:00:00Z"), Instant.parse("2026-10-22T09:00:00Z"), zurich),
				description = "Agenda in the shared folder",
				location = "Room Aare",
				attendees =
					listOf(
						Attendee("alex.example@example.test", null, AttendeeStatus.ACCEPTED),
						Attendee("sam.sample@example.test", "Sam Sample", AttendeeStatus.TENTATIVE),
						Attendee("kim.test@example.test", null, AttendeeStatus.NEEDS_ACTION),
					),
				// useDefault: the calendar's default reminders
				reminderMinutes = listOf(10),
				conferenceUrl = "https://meet.google.com/abc-defg-hij",
			)
		val series = first.events[1]
		series.key shouldBe RemoteEventKey("series01")
		series.rrule shouldBe "FREQ=WEEKLY;BYDAY=MO"
		series.exdates shouldBe setOf(OccurrenceId.of(Instant.parse("2026-11-30T08:00:00Z")))
		series.reminderMinutes shouldBe listOf(1440, 5)
		series.transparency shouldBe EventTransparency.TRANSPARENT

		val override = second.events.first()
		override.key shouldBe RemoteEventKey("series01", OccurrenceId.of(Instant.parse("2026-11-02T08:00:00Z")))
		override.title shouldBe "Weekly sync (moved)"
		override.timing shouldBe Timing.Timed(Instant.parse("2026-11-02T09:00:00Z"), Instant.parse("2026-11-02T09:30:00Z"), zurich)
		override.rrule.shouldBeNull()

		val allDay = second.events[1]
		allDay.timing shouldBe Timing.AllDay(LocalDate.parse("2026-11-05"), LocalDate.parse("2026-11-07"))
		allDay.status shouldBe EventStatus.TENTATIVE
		allDay.reminderMinutes shouldBe emptyList()

		// The event without times cannot be read and is skipped; cancelled ones are deletions.
		second.events.map { it.key.externalId } shouldContainExactly listOf("series01", "allday01")
		second.deleted shouldContainExactly
			listOf(RemoteEventKey("series01", OccurrenceId.of(Instant.parse("2026-11-09T08:00:00Z"))), RemoteEventKey("gone01"))
	}

	@Test
	fun `an incremental fetch sends the sync token, and a changed series gets its cancelled occurrences as EXDATEs`() {
		stub(get(urlPathEqualTo(EVENTS)).withQueryParam("syncToken", equalTo("EV-sync-1")), recording("events-incremental.json"))
		stub(
			get(urlPathEqualTo(EVENTS)).withQueryParam("iCalUID", equalTo("series01@google.com")).withQueryParam("showDeleted", equalTo("true")),
			recording("events-by-icaluid-series01.json"),
		)

		val changes = adapter.fetchEvents(source, "primary", "sync:EV-sync-1")

		changes.complete shouldBe false
		changes.hasMore shouldBe false
		changes.cursor shouldBe "sync:EV-sync-2"
		changes.events.map { it.key.externalId } shouldContainExactly listOf("series01", "new001")
		changes.events[0].title shouldBe "Weekly sync (renamed)"
		changes.events[0].exdates shouldBe
			setOf(OccurrenceId.of(Instant.parse("2026-11-09T08:00:00Z")), OccurrenceId.of(Instant.parse("2026-11-16T08:00:00Z")))
		// A time without zone is in the calendar's zone.
		(changes.events[1].timing as Timing.Timed).zone shouldBe ZoneId.of("Europe/Zurich")
		changes.deleted shouldContainExactly listOf(RemoteEventKey("evt001"))
		// Only the series is looked up, not the single event.
		wireMock.verify(1, getRequestedFor(urlPathEqualTo(EVENTS)).withQueryParam("iCalUID", matching(".*")))
	}

	@Test
	fun `an incremental run continues with the page token and keeps the run incremental`() {
		val page =
			"""{"kind": "calendar#events", "timeZone": "UTC", "nextPageToken": "INC-2", "items": []}"""
		stub(get(urlPathEqualTo(EVENTS)).withQueryParam("syncToken", equalTo("EV-sync-1")), page)
		stub(get(urlPathEqualTo(EVENTS)).withQueryParam("pageToken", equalTo("INC-2")), """{"items": [], "nextSyncToken": "EV-sync-2"}""")

		val first = adapter.fetchEvents(source, "primary", "sync:EV-sync-1")
		first.cursor shouldBe "page:INC-2"
		first.hasMore shouldBe true
		first.complete shouldBe false

		val second = adapter.fetchEvents(source, "primary", first.cursor)
		second.cursor shouldBe "sync:EV-sync-2"
		second.complete shouldBe false
		wireMock.verify(getRequestedFor(urlPathEqualTo(EVENTS)).withQueryParam("pageToken", equalTo("INC-2")).withQueryParam("syncToken", absent()))
	}

	@Test
	fun `410 Gone for an expired sync token starts a complete listing`() {
		stub(get(urlPathEqualTo(EVENTS)).withQueryParam("syncToken", equalTo("EV-sync-old")), recording("error-410-full-sync-required.json"), 410)
		stubFullListing()

		val changes = adapter.fetchEvents(source, "primary", "sync:EV-sync-old")

		changes.complete shouldBe true
		changes.cursor shouldBe "full:EV-page-2"
		changes.events.map { it.key.externalId } shouldContainExactly listOf("evt001", "series01")
	}

	@Test
	fun `410 within an incremental run's pages also starts over completely, an unknown cursor too`() {
		stub(get(urlPathEqualTo(EVENTS)).withQueryParam("pageToken", equalTo("INC-old")), recording("error-410-full-sync-required.json"), 410)
		stubFullListing()

		adapter.fetchEvents(source, "primary", "page:INC-old").complete shouldBe true
		adapter.fetchEvents(source, "primary", "something-else").complete shouldBe true
	}

	@Test
	fun `410 during a complete listing is an error, not an endless restart`() {
		stub(get(urlPathEqualTo(EVENTS)), recording("error-410-full-sync-required.json"), 410)

		shouldThrow<CalendarProviderException> { adapter.fetchEvents(source, "primary", null) }.message shouldContain "HTTP 410"
		wireMock.verify(1, getRequestedFor(urlPathEqualTo(EVENTS)))
	}

	@Test
	fun `calendar ids are encoded in the path`() {
		stub(get(urlPathEqualTo("$API/calendars/en.swiss%23holiday%40group.v.calendar.google.com/events")), """{"items": [], "nextSyncToken": "H1"}""")

		adapter.fetchEvents(source, "en.swiss#holiday@group.v.calendar.google.com", null).cursor shouldBe "sync:H1"
	}

	// --- push

	@Test
	fun `createEvent inserts with an id derived from the cockpit id and returns Google's copy`() {
		stub(post(urlPathEqualTo(EVENTS)), recording("event-insert-response.json"))
		val event =
			RemoteEvent(
				key = RemoteEventKey("0192a5f0-c3e4-7b6a-8d1e-2f3a4b5c6d7e"),
				etag = null,
				title = "Planning",
				timing = Timing.Timed(Instant.parse("2026-10-28T09:00:00Z"), Instant.parse("2026-10-28T10:00:00Z"), ZoneId.of("Europe/Zurich")),
				rrule = "FREQ=WEEKLY;BYDAY=WE",
				exdates = setOf(OccurrenceId.of(Instant.parse("2026-11-04T09:00:00Z"))),
				attendees = listOf(Attendee("sam.sample@example.test", "Sam Sample", AttendeeStatus.DELEGATED)),
				reminderMinutes = listOf(15),
			)

		val stored = adapter.createEvent(source, "primary", event)

		stored.key shouldBe RemoteEventKey("0192a5f0c3e47b6a8d1e2f3a4b5c6d7e")
		stored.etag shouldBe "\"3523456789030000\""
		stored.exdates shouldBe event.exdates
		wireMock.verify(
			postRequestedFor(urlPathEqualTo(EVENTS))
				.withQueryParam("sendUpdates", equalTo("none"))
				.withHeader("If-Match", absent())
				.withRequestBody(
					equalToJson(
						"""
						{
						  "id": "0192a5f0c3e47b6a8d1e2f3a4b5c6d7e",
						  "summary": "Planning",
						  "description": null,
						  "location": null,
						  "start": {"date": null, "dateTime": "2026-10-28T10:00:00+01:00", "timeZone": "Europe/Zurich"},
						  "end": {"date": null, "dateTime": "2026-10-28T11:00:00+01:00", "timeZone": "Europe/Zurich"},
						  "recurrence": ["RRULE:FREQ=WEEKLY;BYDAY=WE", "EXDATE:20261104T090000Z"],
						  "attendees": [{"email": "sam.sample@example.test", "displayName": "Sam Sample", "responseStatus": "needsAction"}],
						  "reminders": {"useDefault": false, "overrides": [{"method": "popup", "minutes": 15}]},
						  "status": "confirmed",
						  "transparency": "opaque"
						}
						""",
					),
				),
		)
	}

	@Test
	fun `an all-day single event is sent with dates, clearing the timed fields`() {
		stub(post(urlPathEqualTo(EVENTS)), recording("event-insert-response.json"))
		val event =
			RemoteEvent(
				key = RemoteEventKey("not a uuid"),
				etag = null,
				title = "Offsite",
				timing = Timing.AllDay(LocalDate.parse("2026-11-05"), LocalDate.parse("2026-11-07")),
				status = EventStatus.TENTATIVE,
				transparency = EventTransparency.TRANSPARENT,
			)

		adapter.createEvent(source, "primary", event)

		wireMock.verify(
			postRequestedFor(urlPathEqualTo(EVENTS))
				.withRequestBody(matchingJsonPath("$.id", absent()))
				.withRequestBody(matchingJsonPath("$.recurrence", absent()))
				.withRequestBody(matchingJsonPath("$.start.date", equalTo("2026-11-05")))
				.withRequestBody(matchingJsonPath("$.end.date", equalTo("2026-11-07")))
				.withRequestBody(matchingJsonPath("$.status", equalTo("tentative")))
				.withRequestBody(matchingJsonPath("$.transparency", equalTo("transparent"))),
		)
	}

	@Test
	fun `a repeated insert whose id exists returns the stored event`() {
		stub(post(urlPathEqualTo(EVENTS)), """{"error": {"code": 409, "message": "The requested identifier already exists."}}""", 409)
		stub(get(urlPathEqualTo("$EVENTS/0192a5f0c3e47b6a8d1e2f3a4b5c6d7e")), recording("event-insert-response.json"))

		adapter.createEvent(source, "primary", planning()).etag shouldBe "\"3523456789030000\""
	}

	@Test
	fun `an exception of a series is a patch of its instance, without If-Match on creation`() {
		stub(patch(urlPathEqualTo("$EVENTS/series01_20261116T080000Z")), recording("event-instance-response.json"))
		val occurrence = OccurrenceId.of(Instant.parse("2026-11-16T08:00:00Z"))
		val event =
			RemoteEvent(
				key = RemoteEventKey("series01", occurrence),
				etag = null,
				title = "Weekly sync (one off)",
				timing = Timing.Timed(Instant.parse("2026-11-16T10:00:00Z"), Instant.parse("2026-11-16T10:30:00Z"), ZoneId.of("Europe/Zurich")),
			)

		val stored = adapter.createEvent(source, "primary", event)

		stored.key shouldBe RemoteEventKey("series01", occurrence)
		stored.etag shouldBe "\"3523456789031000\""
		wireMock.verify(
			patchRequestedFor(urlPathEqualTo("$EVENTS/series01_20261116T080000Z"))
				.withHeader("If-Match", absent())
				.withRequestBody(matchingJsonPath("$.recurrence", absent()))
				.withRequestBody(matchingJsonPath("$.summary", equalTo("Weekly sync (one off)"))),
		)
	}

	@Test
	fun `an exception of an all-day series patches the instance named by its date`() {
		stub(patch(urlPathEqualTo("$EVENTS/daily01_20261105")), "", 404)
		val event =
			RemoteEvent(
				key = RemoteEventKey("daily01", OccurrenceId.of(LocalDate.parse("2026-11-05"))),
				etag = null,
				title = "x",
				timing = Timing.AllDay(LocalDate.parse("2026-11-05"), LocalDate.parse("2026-11-06")),
			)

		shouldThrow<CalendarProviderException> { adapter.createEvent(source, "primary", event) }.message shouldContain "occurrence"
	}

	@Test
	fun `updateEvent patches with If-Match and an explicit recurrence`() {
		stub(patch(urlPathEqualTo("$EVENTS/0192a5f0c3e47b6a8d1e2f3a4b5c6d7e")), recording("event-insert-response.json"))
		val single = planning().copy(key = RemoteEventKey("0192a5f0c3e47b6a8d1e2f3a4b5c6d7e"), rrule = null)

		adapter.updateEvent(source, "primary", single, "\"3523456789029000\"")

		wireMock.verify(
			patchRequestedFor(urlPathEqualTo("$EVENTS/0192a5f0c3e47b6a8d1e2f3a4b5c6d7e"))
				.withHeader("If-Match", equalTo("\"3523456789029000\""))
				.withQueryParam("sendUpdates", equalTo("none"))
				// No rule (any more): an empty recurrence turns a series into a single event.
				.withRequestBody(matchingJsonPath("$.recurrence.length()", equalTo("0")))
				.withRequestBody(matchingJsonPath("$.id", absent())),
		)
	}

	@Test
	fun `412 on a write is a remote change, so is a 404 of the event`() {
		stub(patch(urlPathEqualTo("$EVENTS/evt001")), recording("error-412-condition-not-met.json"), 412)
		stub(patch(urlPathEqualTo("$EVENTS/evt404")), """{"error": {"code": 404, "message": "Not Found"}}""", 404)

		shouldThrow<RemoteEventChangedException> {
			adapter.updateEvent(source, "primary", planning().copy(key = RemoteEventKey("evt001")), "\"1\"")
		}
		shouldThrow<RemoteEventChangedException> {
			adapter.updateEvent(source, "primary", planning().copy(key = RemoteEventKey("evt404")), "\"1\"")
		}
	}

	@Test
	fun `deleteEvent deletes with If-Match, an occurrence through its instance, and something already gone succeeds`() {
		stub(delete(urlPathEqualTo("$EVENTS/evt001")), "", 204)
		stub(delete(urlPathEqualTo("$EVENTS/series01_20261102T080000Z")), "", 204)
		stub(delete(urlPathEqualTo("$EVENTS/gone01")), """{"error": {"code": 410, "message": "Resource has been deleted"}}""", 410)
		stub(delete(urlPathEqualTo("$EVENTS/missing")), """{"error": {"code": 404, "message": "Not Found"}}""", 404)
		stub(delete(urlPathEqualTo("$EVENTS/changed")), recording("error-412-condition-not-met.json"), 412)

		adapter.deleteEvent(source, "primary", RemoteEventKey("evt001"), "\"3523456789012000\"").shouldBeNull()
		adapter
			.deleteEvent(source, "primary", RemoteEventKey("series01", OccurrenceId.of(Instant.parse("2026-11-02T08:00:00Z"))), "\"e\"")
			.shouldBeNull()
		adapter.deleteEvent(source, "primary", RemoteEventKey("gone01"), null).shouldBeNull()
		adapter.deleteEvent(source, "primary", RemoteEventKey("missing"), null).shouldBeNull()
		shouldThrow<RemoteEventChangedException> { adapter.deleteEvent(source, "primary", RemoteEventKey("changed"), "\"1\"") }

		wireMock.verify(
			deleteRequestedFor(urlPathEqualTo("$EVENTS/evt001"))
				.withHeader("If-Match", equalTo("\"3523456789012000\""))
				.withQueryParam("sendUpdates", equalTo("none")),
		)
		wireMock.verify(deleteRequestedFor(urlPathEqualTo("$EVENTS/gone01")).withHeader("If-Match", absent()))
	}

	// --- failures

	@Test
	fun `HTTP failures become provider errors without token or response content`() {
		stub(get(urlPathEqualTo("$API/users/me/calendarList")), recording("error-403-rate-limit.json"), 403)
		shouldThrow<CalendarProviderException> { adapter.listCalendars(source) }.message shouldContain "rate limit"

		stub(get(urlPathEqualTo("$API/users/me/calendarList")), recording("error-403-forbidden.json"), 403)
		val forbidden = shouldThrow<CalendarProviderException> { adapter.listCalendars(source) }
		forbidden.message shouldContain "refused access"
		forbidden.message!! shouldNotContain "organizer"

		stub(get(urlPathEqualTo("$API/users/me/calendarList")), """{"error": {"code": 401}}""", 401)
		shouldThrow<CalendarProviderException> { adapter.listCalendars(source) }.message shouldContain "401"

		stub(get(urlPathEqualTo("$API/users/me/calendarList")), "busy", 429)
		shouldThrow<CalendarProviderException> { adapter.listCalendars(source) }.message shouldContain "rate limit"

		stub(get(urlPathEqualTo("$API/users/me/calendarList")), "<html>oops</html>", 503)
		val unavailable = shouldThrow<CalendarProviderException> { adapter.listCalendars(source) }
		unavailable.message shouldContain "HTTP 503"
		unavailable.message!! shouldNotContain TOKEN

		stub(get(urlPathEqualTo("$API/users/me/calendarList")), "not json")
		shouldThrow<CalendarProviderException> { adapter.listCalendars(source) }.message shouldContain "without a JSON object"

		wireMock.stubFor(get(anyUrl()).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)))
		shouldThrow<CalendarProviderException> { adapter.listCalendars(source) }.message shouldContain "could not be reached"
	}

	@Test
	fun `an answer to a write that cannot be read is a provider error`() {
		stub(post(urlPathEqualTo(EVENTS)), """{"id": "x", "summary": "no times"}""")

		shouldThrow<CalendarProviderException> { adapter.createEvent(source, "primary", planning()) }.message shouldContain "could not be read"
	}

	@Test
	fun `a source without connection or with a connection to reconnect fails before calling Google`() {
		val unlinked = CalendarSource(CalendarSourceType.GOOGLE, "Google")
		shouldThrow<CalendarProviderException> { adapter.listCalendars(unlinked) }.message shouldContain "not linked"
		unlinked.config = """{"connectionId": "not-a-uuid"}"""
		shouldThrow<CalendarProviderException> { adapter.listCalendars(unlinked) }.message shouldContain "not linked"
		unlinked.config = "not json"
		shouldThrow<CalendarProviderException> { adapter.listCalendars(unlinked) }.message shouldContain "not linked"

		tokens.failure = OAuthReconnectRequiredException()
		shouldThrow<CalendarProviderException> { adapter.listCalendars(source) }.message shouldContain "connected again"
		wireMock.verify(0, anyRequestedFor(anyUrl()))
	}

	private fun planning() =
		RemoteEvent(
			key = RemoteEventKey("0192a5f0-c3e4-7b6a-8d1e-2f3a4b5c6d7e"),
			etag = null,
			title = "Planning",
			timing = Timing.Timed(Instant.parse("2026-10-28T09:00:00Z"), Instant.parse("2026-10-28T10:00:00Z"), ZoneId.of("Europe/Zurich")),
		)

	private fun stubFullListing() {
		stub(
			get(urlPathEqualTo(EVENTS)).withQueryParam("syncToken", absent()).withQueryParam("pageToken", absent()),
			recording("events-full-page1.json"),
		)
		stub(get(urlPathEqualTo(EVENTS)).withQueryParam("pageToken", equalTo("EV-page-2")), recording("events-full-page2.json"))
	}

	/** Bearer tokens of [CONNECTION] only, or [failure]. */
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
		const val API = "/calendar/v3"
		const val EVENTS = "$API/calendars/primary/events"
		const val TOKEN = "fictitious-access-token"
		val CONNECTION: UUID = UUID.fromString("0192a5f0-0000-7000-8000-000000000001")

		val wireMock: WireMockServer = WireMockServer(wireMockConfig().dynamicPort()).apply { start() }

		@JvmStatic
		@AfterAll
		fun stop() = wireMock.stop()

		/** The recorded response body [name]. */
		fun recording(name: String): String =
			checkNotNull(GoogleCalendarAdapterTests::class.java.getResource(name)) { "no recording $name" }.readText()

		fun stub(
			mapping: MappingBuilder,
			body: String,
			status: Int = 200,
		) {
			wireMock.stubFor(mapping.willReturn(aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(body)))
		}
	}
}
