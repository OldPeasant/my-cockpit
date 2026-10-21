package ch.sonensei.cockpit.calendar.invitations

import ch.sonensei.cockpit.calendar.infra.microsoft.MicrosoftCalendarAdapter
import ch.sonensei.cockpit.calendar.model.Attendee
import ch.sonensei.cockpit.calendar.model.AttendeeStatus
import ch.sonensei.cockpit.calendar.model.CalendarSource
import ch.sonensei.cockpit.calendar.model.CalendarSourceType
import ch.sonensei.cockpit.calendar.model.OccurrenceId
import ch.sonensei.cockpit.calendar.recurrence.OccurrenceExpander
import ch.sonensei.cockpit.calendar.sync.CalendarProviderException
import ch.sonensei.cockpit.calendar.sync.InvitationNotRespondableException
import ch.sonensei.cockpit.calendar.sync.RemoteEventChangedException
import ch.sonensei.cockpit.calendar.sync.RemoteEventKey
import ch.sonensei.cockpit.core.oauth.domain.OAuthAccessToken
import ch.sonensei.cockpit.core.oauth.domain.OAuthConnectionInfo
import ch.sonensei.cockpit.core.oauth.domain.OAuthConnectionStatus
import ch.sonensei.cockpit.core.oauth.domain.OAuthProvider
import ch.sonensei.cockpit.core.oauth.domain.OAuthResource
import ch.sonensei.cockpit.core.oauth.domain.OAuthTokenProvider
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.MappingBuilder
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.anyUrl
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.equalToJson
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import com.github.tomakehurst.wiremock.stubbing.Scenario
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** [MicrosoftCalendarAdapter.respondToInvitation] against WireMock playing Microsoft Graph v1.0 (fictitious data). */
class MicrosoftInvitationAdapterTests {
	private val tokens = Tokens()
	private val adapter =
		MicrosoftCalendarAdapter(
			tokens,
			JsonMapper.builder().build(),
			OccurrenceExpander(),
			Clock.fixed(Instant.parse("2026-10-21T08:00:00Z"), ZoneOffset.UTC),
			wireMock.baseUrl() + API,
		)
	private val source =
		CalendarSource(CalendarSourceType.MICROSOFT, "Microsoft").apply { config = """{"connectionId": "$CONNECTION"}""" }

	@BeforeEach
	fun reset() {
		wireMock.resetAll()
		tokens.email = "alex.example@example.test"
	}

	@Test
	fun `the adapter answers invitations`() {
		adapter.respondsToInvitations shouldBe true
	}

	@Test
	fun `accepting calls Graph's accept action with a response to the organizer and returns the event read again`() {
		stubAnswered("accept", "accepted")

		val stored = adapter.respondToInvitation(source, CALENDAR, RemoteEventKey("AAMkInv1"), AttendeeStatus.ACCEPTED, "W/\"inv-1\"")

		stored.etag shouldBe "W/\"inv-2\""
		stored.attendees shouldBe
			listOf(
				Attendee("sam.example@example.test", "Sam Example", AttendeeStatus.ACCEPTED),
				Attendee("Alex.Example@example.test", "Alex Example", AttendeeStatus.ACCEPTED),
			)
		wireMock.verify(postRequestedFor(urlPathEqualTo("$EVENTS/AAMkInv1/accept")).withRequestBody(equalToJson("""{"sendResponse": true}""")))
	}

	@Test
	fun `the user's attendee shows the answer even when Graph's copy does not list it yet`() {
		// Graph has not updated the attendee list of the user's copy: the answer still shows.
		stubAnswered("tentativelyAccept", "none")

		val stored = adapter.respondToInvitation(source, CALENDAR, RemoteEventKey("AAMkInv1"), AttendeeStatus.TENTATIVE, null)

		stored.attendees.single { it.email == "Alex.Example@example.test" }.status shouldBe AttendeeStatus.TENTATIVE
		stored.attendees.single { it.email == "sam.example@example.test" }.status shouldBe AttendeeStatus.ACCEPTED
	}

	@Test
	fun `a declined event Graph removed from the calendar right away is returned as it was, declined`() {
		stub(get(urlPathEqualTo("$EVENTS/AAMkInv1")).inScenario("decline").whenScenarioStateIs(Scenario.STARTED), invitation("inv-1", "none"))
		wireMock.stubFor(
			post(urlPathEqualTo("$EVENTS/AAMkInv1/decline"))
				.inScenario("decline")
				.willSetStateTo("declined")
				.willReturn(aResponse().withStatus(202)),
		)
		stub(get(urlPathEqualTo("$EVENTS/AAMkInv1")).inScenario("decline").whenScenarioStateIs("declined"), "{}", 404)

		val stored = adapter.respondToInvitation(source, CALENDAR, RemoteEventKey("AAMkInv1"), AttendeeStatus.DECLINED, null)

		stored.etag shouldBe "W/\"inv-1\""
		stored.attendees.single { it.email == "Alex.Example@example.test" }.status shouldBe AttendeeStatus.DECLINED
	}

	@Test
	fun `a stale etag or a deleted event is a change at Microsoft, nothing is answered`() {
		stub(get(urlPathEqualTo("$EVENTS/AAMkInv1")), invitation("inv-9", "none"))
		shouldThrow<RemoteEventChangedException> {
			adapter.respondToInvitation(source, CALENDAR, RemoteEventKey("AAMkInv1"), AttendeeStatus.ACCEPTED, "W/\"inv-1\"")
		}
		stub(get(urlPathEqualTo("$EVENTS/AAMkGone")), "{}", 404)
		shouldThrow<RemoteEventChangedException> {
			adapter.respondToInvitation(source, CALENDAR, RemoteEventKey("AAMkGone"), AttendeeStatus.ACCEPTED, null)
		}
		wireMock.verify(0, postRequestedFor(anyUrl()))
	}

	@Test
	fun `the organizer cannot answer, and a failing action is a provider error`() {
		stub(get(urlPathEqualTo("$EVENTS/AAMkOwn")), invitation("own-1", "organizer").replace("\"isOrganizer\": false", "\"isOrganizer\": true"))
		shouldThrow<InvitationNotRespondableException> {
			adapter.respondToInvitation(source, CALENDAR, RemoteEventKey("AAMkOwn"), AttendeeStatus.ACCEPTED, null)
		}.message shouldBe "You organize this event"
		wireMock.verify(0, postRequestedFor(anyUrl()))

		stub(get(urlPathEqualTo("$EVENTS/AAMkInv1")), invitation("inv-1", "none"))
		stub(post(urlPathEqualTo("$EVENTS/AAMkInv1/accept")), """{"error": {"code": "ErrorInvalidRequest"}}""", 400)
		shouldThrow<CalendarProviderException> {
			adapter.respondToInvitation(source, CALENDAR, RemoteEventKey("AAMkInv1"), AttendeeStatus.ACCEPTED, null)
		}.message shouldBe "Answering an invitation failed at Microsoft (HTTP 400)"
	}

	@Test
	fun `without the connection's address the event is returned as Graph has it`() {
		tokens.email = null
		stubAnswered("accept", "none")

		val stored = adapter.respondToInvitation(source, CALENDAR, RemoteEventKey("AAMkInv1"), AttendeeStatus.ACCEPTED, null)

		stored.attendees.single { it.email == "Alex.Example@example.test" }.status shouldBe AttendeeStatus.NEEDS_ACTION
	}

	@Test
	fun `an occurrence is answered through its instance, found among the series' instances`() {
		val key = RemoteEventKey(SERIES, OccurrenceId.of(Instant.parse("2026-11-09T09:00:00Z")))
		stub(
			get(urlPathEqualTo("$EVENTS/$SERIES/instances")),
			"""{"value": [${occurrence("2026-11-02", "occ-1", "none")}, ${occurrence("2026-11-09", "occ-1", "none")}]}""",
		)
		stub(post(urlPathEqualTo("$EVENTS/$SERIES-20261109/tentativelyAccept")), "", 202)
		stub(get(urlPathEqualTo("$EVENTS/$SERIES-20261109")), occurrence("2026-11-09", "occ-2", "tentativelyAccepted"))

		val stored = adapter.respondToInvitation(source, CALENDAR, key, AttendeeStatus.TENTATIVE, "W/\"occ-1\"")

		stored.key shouldBe key
		stored.etag shouldBe "W/\"occ-2\""
		stored.attendees.single { it.email == "Alex.Example@example.test" }.status shouldBe AttendeeStatus.TENTATIVE
		wireMock.verify(
			postRequestedFor(urlPathEqualTo("$EVENTS/$SERIES-20261109/tentativelyAccept"))
				.withRequestBody(equalToJson("""{"sendResponse": true}""")),
		)
		wireMock.verify(0, postRequestedFor(urlPathEqualTo("$EVENTS/$SERIES-20261102/tentativelyAccept")))
		wireMock.verify(0, postRequestedFor(urlPathEqualTo("$EVENTS/$SERIES/tentativelyAccept")))
	}

	@Test
	fun `an occurrence Graph no longer has, or of a series that is gone, is a change at Microsoft`() {
		val key = RemoteEventKey(SERIES, OccurrenceId.of(Instant.parse("2026-11-09T09:00:00Z")))
		stub(get(urlPathEqualTo("$EVENTS/$SERIES/instances")), """{"value": [${occurrence("2026-11-02", "occ-1", "none")}]}""")
		shouldThrow<RemoteEventChangedException> {
			adapter.respondToInvitation(source, CALENDAR, key, AttendeeStatus.ACCEPTED, null)
		}.message shouldBe "The occurrence was deleted at Microsoft"

		stub(get(urlPathEqualTo("$EVENTS/$SERIES/instances")), """{"error": {"code": "ErrorItemNotFound"}}""", 404)
		shouldThrow<RemoteEventChangedException> {
			adapter.respondToInvitation(source, CALENDAR, key, AttendeeStatus.ACCEPTED, null)
		}.message shouldBe "The occurrence was deleted at Microsoft"
		wireMock.verify(0, postRequestedFor(anyUrl()))
	}

	@Test
	fun `answering a series master returns the series read again, with its pattern and the user's answer`() {
		stub(get(urlPathEqualTo("$EVENTS/$SERIES")).inScenario("series").whenScenarioStateIs(Scenario.STARTED), master("ser-1", "none"))
		wireMock.stubFor(
			post(urlPathEqualTo("$EVENTS/$SERIES/accept"))
				.inScenario("series")
				.willSetStateTo("answered")
				.willReturn(aResponse().withStatus(202)),
		)
		stub(get(urlPathEqualTo("$EVENTS/$SERIES")).inScenario("series").whenScenarioStateIs("answered"), master("ser-2", "accepted"))
		stub(
			get(urlPathEqualTo("$EVENTS/$SERIES/instances")),
			"""{"value": [${listOf("2026-11-02", "2026-11-09", "2026-11-16").joinToString { occurrence(it, "occ-1", "accepted") }}]}""",
		)

		val stored = adapter.respondToInvitation(source, CALENDAR, RemoteEventKey(SERIES), AttendeeStatus.ACCEPTED, "W/\"ser-1\"")

		stored.key shouldBe RemoteEventKey(SERIES)
		stored.etag shouldBe "W/\"ser-2\""
		stored.rrule.shouldNotBeNull()
		stored.attendees.single { it.email == "Alex.Example@example.test" }.status shouldBe AttendeeStatus.ACCEPTED
		wireMock.verify(postRequestedFor(urlPathEqualTo("$EVENTS/$SERIES/accept")))
		// The series is read with its instances in the sync window (the clock stands at 21 October 2026).
		wireMock.verify(
			getRequestedFor(urlPathEqualTo("$EVENTS/$SERIES/instances")).withQueryParam("startDateTime", equalTo("2025-10-21T00:00:00Z")),
		)
	}

	@Test
	fun `an event deleted while answering is a change at Microsoft, a failing read afterwards a provider error`() {
		stub(get(urlPathEqualTo("$EVENTS/AAMkInv1")), invitation("inv-1", "none"))
		stub(post(urlPathEqualTo("$EVENTS/AAMkInv1/decline")), """{"error": {"code": "ErrorItemNotFound"}}""", 404)
		shouldThrow<RemoteEventChangedException> {
			adapter.respondToInvitation(source, CALENDAR, RemoteEventKey("AAMkInv1"), AttendeeStatus.DECLINED, null)
		}.message shouldBe "The event was deleted at Microsoft"

		stub(get(urlPathEqualTo("$EVENTS/AAMkInv1")).inScenario("read").whenScenarioStateIs(Scenario.STARTED), invitation("inv-1", "none"))
		wireMock.stubFor(
			post(urlPathEqualTo("$EVENTS/AAMkInv1/accept"))
				.inScenario("read")
				.willSetStateTo("answered")
				.willReturn(aResponse().withStatus(202)),
		)
		stub(get(urlPathEqualTo("$EVENTS/AAMkInv1")).inScenario("read").whenScenarioStateIs("answered"), "{}", 400)
		shouldThrow<CalendarProviderException> {
			adapter.respondToInvitation(source, CALENDAR, RemoteEventKey("AAMkInv1"), AttendeeStatus.ACCEPTED, null)
		}.message shouldBe "Fetching an event failed at Microsoft (HTTP 400)"
	}

	@Test
	fun `an event the user is not an attendee of and has not answered is not an invitation, nothing is sent`() {
		val foreign =
			invitation("inv-1", "none")
				.replace("Alex.Example@example.test", "kim.example@example.test")
		stub(get(urlPathEqualTo("$EVENTS/AAMkInv1")), foreign)

		shouldThrow<InvitationNotRespondableException> {
			adapter.respondToInvitation(source, CALENDAR, RemoteEventKey("AAMkInv1"), AttendeeStatus.ACCEPTED, null)
		}.message shouldBe "You are not invited to this event"
		wireMock.verify(0, postRequestedFor(anyUrl()))
	}

	@Test
	fun `an invitation Graph shows the user's answer to is answered even when no attendee has the connection's address`() {
		// E.g. the user is invited under another address of the mailbox.
		stub(
			get(urlPathEqualTo("$EVENTS/AAMkInv1")),
			invitation("inv-1", "notResponded").replace("Alex.Example@example.test", "alex.alias@example.test"),
		)
		stub(post(urlPathEqualTo("$EVENTS/AAMkInv1/accept")), "", 202)

		adapter.respondToInvitation(source, CALENDAR, RemoteEventKey("AAMkInv1"), AttendeeStatus.ACCEPTED, null)

		wireMock.verify(postRequestedFor(urlPathEqualTo("$EVENTS/AAMkInv1/accept")))
	}

	@Test
	fun `sync shows the user's answer from Graph's responseStatus when the attendee list does not show it yet`() {
		val delta = "$API/me/calendars/$CALENDAR/calendarView/delta"
		stub(
			get(urlPathEqualTo(delta)),
			"""{"value": [${answeredOnlyInResponseStatus(invitation("inv-2", "none"))}],
			 "@odata.deltaLink": "https://graph.example.test$delta?${'$'}deltatoken=D1"}""",
		)

		val synced = adapter.fetchEvents(source, CALENDAR, null).events.single()
		synced.attendees.single { it.email == "Alex.Example@example.test" }.status shouldBe AttendeeStatus.ACCEPTED
		// Others keep their own answers.
		synced.attendees.single { it.email == "sam.example@example.test" }.status shouldBe AttendeeStatus.ACCEPTED

		// Without the connection's address, the attendee list is all there is.
		tokens.email = null
		adapter
			.fetchEvents(source, CALENDAR, null)
			.events
			.single()
			.attendees
			.single { it.email == "Alex.Example@example.test" }
			.status shouldBe AttendeeStatus.NEEDS_ACTION
	}

	@Test
	fun `only ACCEPTED, DECLINED and TENTATIVE are answers, nothing is sent otherwise`() {
		shouldThrow<IllegalArgumentException> {
			adapter.respondToInvitation(source, CALENDAR, RemoteEventKey("AAMkInv1"), AttendeeStatus.NEEDS_ACTION, null)
		}
		wireMock.verify(0, anyRequestedFor(anyUrl()))
	}

	/** The invitation before (`none`) and, after the [action], at version `inv-2` with the user's [status] in its attendees. */
	private fun stubAnswered(
		action: String,
		status: String,
	) {
		stub(get(urlPathEqualTo("$EVENTS/AAMkInv1")).inScenario("answer").whenScenarioStateIs(Scenario.STARTED), invitation("inv-1", "none"))
		wireMock.stubFor(
			post(urlPathEqualTo("$EVENTS/AAMkInv1/$action"))
				.withHeader("Authorization", equalTo("Bearer $TOKEN"))
				.inScenario("answer")
				.willSetStateTo("answered")
				.willReturn(aResponse().withStatus(202)),
		)
		stub(get(urlPathEqualTo("$EVENTS/AAMkInv1")).inScenario("answer").whenScenarioStateIs("answered"), invitation("inv-2", status))
	}

	class Tokens : OAuthTokenProvider {
		var email: String? = null

		override fun accessToken(
			connectionId: UUID,
			resource: OAuthResource,
		) = OAuthAccessToken(TOKEN, Instant.parse("2030-01-01T00:00:00Z"))

		override fun connection(connectionId: UUID): OAuthConnectionInfo =
			OAuthConnectionInfo(
				id = connectionId,
				provider = OAuthProvider.MICROSOFT,
				email = email,
				displayName = null,
				scopes = emptyList(),
				status = OAuthConnectionStatus.CONNECTED,
				lastError = null,
				lastRefreshedAt = null,
				createdAt = Instant.parse("2026-01-01T00:00:00Z"),
				version = 0,
			)
	}

	companion object {
		const val API = "/v1.0"
		const val CALENDAR = "AAMkCalPrimary"
		const val EVENTS = "$API/me/calendars/$CALENDAR/events"
		const val TOKEN = "fictitious-graph-access-token"
		const val SERIES = "AAMkSeries2"
		val CONNECTION: UUID = UUID.fromString("0192a5f0-0000-7000-8000-000000000002")

		val wireMock: WireMockServer = WireMockServer(wireMockConfig().dynamicPort()).apply { start() }

		@JvmStatic
		@AfterAll
		fun stop() = wireMock.stop()

		/** Sam's invitation of Alex (the user) at version `W/"[etag]"`, Alex's attendee answer being [status]. */
		fun invitation(
			etag: String,
			status: String,
		) = """{"@odata.etag": "W/\"$etag\"", "id": "AAMkInv1", "type": "singleInstance", "subject": "Design review",
			"isOrganizer": false, "isAllDay": false, "isCancelled": false, "showAs": "busy",
			"responseStatus": {"response": "$status"}, "body": {"contentType": "text", "content": ""},
			"start": {"dateTime": "2026-10-28T09:00:00.0000000", "timeZone": "UTC"},
			"end": {"dateTime": "2026-10-28T10:00:00.0000000", "timeZone": "UTC"},
			"organizer": {"emailAddress": {"name": "Sam Example", "address": "sam.example@example.test"}},
			"attendees": [
			  {"type": "required", "status": {"response": "organizer"},
			   "emailAddress": {"name": "Sam Example", "address": "sam.example@example.test"}},
			  {"type": "required", "status": {"response": "$status"},
			   "emailAddress": {"name": "Alex Example", "address": "Alex.Example@example.test"}}]}"""

		/** Sam's weekly series (Mondays 09:00 UTC, 2 to 16 November 2026) at version `W/"[etag]"`, Alex's answer [status]. */
		fun master(
			etag: String,
			status: String,
		) = """{"@odata.etag": "W/\"$etag\"", "id": "$SERIES", "type": "seriesMaster", "subject": "Weekly sync",
			"isOrganizer": false, "isAllDay": false, "isCancelled": false, "showAs": "busy", "originalStartTimeZone": "UTC",
			"responseStatus": {"response": "$status"}, "body": {"contentType": "text", "content": ""},
			"start": {"dateTime": "2026-11-02T09:00:00.0000000", "timeZone": "UTC"},
			"end": {"dateTime": "2026-11-02T10:00:00.0000000", "timeZone": "UTC"},
			"recurrence": {"pattern": {"type": "weekly", "interval": 1, "daysOfWeek": ["monday"], "firstDayOfWeek": "sunday"},
			               "range": {"type": "endDate", "startDate": "2026-11-02", "endDate": "2026-11-16", "recurrenceTimeZone": "UTC"}},
			"organizer": {"emailAddress": {"name": "Sam Example", "address": "sam.example@example.test"}},
			"attendees": [${attendees(status)}]}"""

		/** The occurrence of [master]'s series on [date] (its instance `<series>-<yyyyMMdd>`) at version `W/"[etag]"`. */
		fun occurrence(
			date: String,
			etag: String,
			status: String,
		) = """{"@odata.etag": "W/\"$etag\"", "id": "$SERIES-${date.replace("-", "")}", "type": "occurrence",
			"seriesMasterId": "$SERIES", "subject": "Weekly sync", "isOrganizer": false, "isAllDay": false, "isCancelled": false,
			"showAs": "busy", "originalStart": "${date}T09:00:00Z", "body": {"contentType": "text", "content": ""},
			"start": {"dateTime": "${date}T09:00:00.0000000", "timeZone": "UTC"},
			"end": {"dateTime": "${date}T10:00:00.0000000", "timeZone": "UTC"},
			"organizer": {"emailAddress": {"name": "Sam Example", "address": "sam.example@example.test"}},
			"attendees": [${attendees(status)}]}"""

		/** [event] (with the user's answer `none`) as Graph may send it after the user accepted: only `responseStatus` shows it. */
		fun answeredOnlyInResponseStatus(event: String) =
			event.replace(""""responseStatus": {"response": "none"}""", """"responseStatus": {"response": "accepted"}""")

		private fun attendees(status: String) =
			"""{"type": "required", "status": {"response": "organizer"},
			   "emailAddress": {"name": "Sam Example", "address": "sam.example@example.test"}},
			  {"type": "required", "status": {"response": "$status"},
			   "emailAddress": {"name": "Alex Example", "address": "Alex.Example@example.test"}}"""

		fun stub(
			mapping: MappingBuilder,
			body: String,
			status: Int = 200,
		) {
			wireMock.stubFor(mapping.willReturn(aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(body)))
		}
	}
}
