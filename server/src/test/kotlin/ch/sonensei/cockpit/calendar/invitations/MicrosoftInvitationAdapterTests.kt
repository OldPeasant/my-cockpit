package ch.sonensei.cockpit.calendar.invitations

import ch.sonensei.cockpit.calendar.infra.microsoft.MicrosoftCalendarAdapter
import ch.sonensei.cockpit.calendar.model.Attendee
import ch.sonensei.cockpit.calendar.model.AttendeeStatus
import ch.sonensei.cockpit.calendar.model.CalendarSource
import ch.sonensei.cockpit.calendar.model.CalendarSourceType
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
import com.github.tomakehurst.wiremock.client.WireMock.anyUrl
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.equalToJson
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import com.github.tomakehurst.wiremock.stubbing.Scenario
import io.kotest.assertions.throwables.shouldThrow
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

		fun stub(
			mapping: MappingBuilder,
			body: String,
			status: Int = 200,
		) {
			wireMock.stubFor(mapping.willReturn(aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(body)))
		}
	}
}
