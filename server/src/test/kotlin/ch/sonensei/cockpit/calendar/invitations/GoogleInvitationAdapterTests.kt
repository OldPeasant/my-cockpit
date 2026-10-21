package ch.sonensei.cockpit.calendar.invitations

import ch.sonensei.cockpit.calendar.infra.google.GoogleCalendarAdapter
import ch.sonensei.cockpit.calendar.infra.google.GoogleCalendarAdapterTests.Companion.CONNECTION
import ch.sonensei.cockpit.calendar.infra.google.GoogleCalendarAdapterTests.FixedTokens
import ch.sonensei.cockpit.calendar.model.Attendee
import ch.sonensei.cockpit.calendar.model.AttendeeStatus
import ch.sonensei.cockpit.calendar.model.CalendarSource
import ch.sonensei.cockpit.calendar.model.CalendarSourceType
import ch.sonensei.cockpit.calendar.model.OccurrenceId
import ch.sonensei.cockpit.calendar.sync.CalendarProviderException
import ch.sonensei.cockpit.calendar.sync.InvitationNotRespondableException
import ch.sonensei.cockpit.calendar.sync.RemoteEventChangedException
import ch.sonensei.cockpit.calendar.sync.RemoteEventKey
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.MappingBuilder
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.anyUrl
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.equalToJson
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.patch
import com.github.tomakehurst.wiremock.client.WireMock.patchRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.time.Instant

/** [GoogleCalendarAdapter.respondToInvitation] against WireMock playing the Calendar API v3 (fictitious data). */
class GoogleInvitationAdapterTests {
	private val adapter = GoogleCalendarAdapter(FixedTokens(), JsonMapper.builder().build(), wireMock.baseUrl() + API)
	private val source =
		CalendarSource(CalendarSourceType.GOOGLE, "Google").apply { config = """{"connectionId": "$CONNECTION"}""" }

	@BeforeEach
	fun reset() {
		wireMock.resetAll()
	}

	@Test
	fun `the adapter answers invitations`() {
		adapter.respondsToInvitations shouldBe true
	}

	@Test
	fun `accepting patches the attendees with the user's new status, conditionally, and notifies the organizer`() {
		stub(get(urlPathEqualTo("$EVENTS/inv001")), invitation("\"inv-1\"", "needsAction"))
		stub(patch(urlPathEqualTo("$EVENTS/inv001")), invitation("\"inv-2\"", "accepted"))

		val stored = adapter.respondToInvitation(source, "primary", RemoteEventKey("inv001"), AttendeeStatus.ACCEPTED, "\"inv-1\"")

		stored.etag shouldBe "\"inv-2\""
		stored.attendees shouldBe
			listOf(
				Attendee("sam.example@example.test", "Sam Example", AttendeeStatus.ACCEPTED),
				Attendee("alex.example@example.test", null, AttendeeStatus.ACCEPTED),
			)
		wireMock.verify(
			patchRequestedFor(urlPathEqualTo("$EVENTS/inv001"))
				.withQueryParam("sendUpdates", equalTo("all"))
				.withHeader("If-Match", equalTo("\"inv-1\""))
				.withRequestBody(
					equalToJson(
						"""{"attendees": [
						{"email": "sam.example@example.test", "displayName": "Sam Example", "organizer": true, "responseStatus": "accepted"},
						{"email": "alex.example@example.test", "self": true, "responseStatus": "accepted"}]}""",
					),
				),
		)
	}

	@Test
	fun `declining and tentatively accepting send Google's status names`() {
		stub(get(urlPathEqualTo("$EVENTS/inv001")), invitation("\"inv-1\"", "needsAction"))
		stub(patch(urlPathEqualTo("$EVENTS/inv001")), invitation("\"inv-2\"", "declined"))

		adapter.respondToInvitation(source, "primary", RemoteEventKey("inv001"), AttendeeStatus.DECLINED, null)
		adapter.respondToInvitation(source, "primary", RemoteEventKey("inv001"), AttendeeStatus.TENTATIVE, null)

		wireMock.verify(patchRequestedFor(urlPathEqualTo("$EVENTS/inv001")).withRequestBody(declinedOrTentative("declined")))
		wireMock.verify(patchRequestedFor(urlPathEqualTo("$EVENTS/inv001")).withRequestBody(declinedOrTentative("tentative")))
	}

	@Test
	fun `an occurrence is answered through its instance`() {
		stub(get(urlPathEqualTo("$EVENTS/series01_20261102T080000Z")), invitation("\"inst-1\"", "needsAction"))
		stub(patch(urlPathEqualTo("$EVENTS/series01_20261102T080000Z")), invitation("\"inst-2\"", "accepted"))

		adapter.respondToInvitation(
			source,
			"primary",
			RemoteEventKey("series01", OccurrenceId.of(Instant.parse("2026-11-02T08:00:00Z"))),
			AttendeeStatus.ACCEPTED,
			"\"inst-1\"",
		)

		wireMock.verify(patchRequestedFor(urlPathEqualTo("$EVENTS/series01_20261102T080000Z")))
	}

	@Test
	fun `a stale etag, a 412 and a deleted event are changes at Google`() {
		stub(get(urlPathEqualTo("$EVENTS/inv001")), invitation("\"inv-9\"", "needsAction"))
		shouldThrow<RemoteEventChangedException> {
			adapter.respondToInvitation(source, "primary", RemoteEventKey("inv001"), AttendeeStatus.ACCEPTED, "\"inv-1\"")
		}
		wireMock.verify(0, patchRequestedFor(anyUrl()))

		stub(patch(urlPathEqualTo("$EVENTS/inv001")), "{}", 412)
		shouldThrow<RemoteEventChangedException> {
			adapter.respondToInvitation(source, "primary", RemoteEventKey("inv001"), AttendeeStatus.ACCEPTED, "\"inv-9\"")
		}

		stub(get(urlPathEqualTo("$EVENTS/gone01")), "{}", 404)
		shouldThrow<RemoteEventChangedException> {
			adapter.respondToInvitation(source, "primary", RemoteEventKey("gone01"), AttendeeStatus.ACCEPTED, null)
		}
	}

	@Test
	fun `an event without the user as attendee or organized by the user cannot be answered`() {
		stub(
			get(urlPathEqualTo("$EVENTS/own01")),
			"""{"etag": "\"own-1\"", "id": "own01", "status": "confirmed", "summary": "Focus time",
			"organizer": {"email": "alex.example@example.test", "self": true},
			"start": {"dateTime": "2026-10-28T10:00:00+01:00"}, "end": {"dateTime": "2026-10-28T11:00:00+01:00"}}""",
		)
		stub(
			get(urlPathEqualTo("$EVENTS/other01")),
			"""{"etag": "\"o-1\"", "id": "other01", "status": "confirmed", "summary": "Team lunch",
			"organizer": {"email": "sam.example@example.test"}, "attendees": [{"email": "kim.example@example.test"}],
			"start": {"dateTime": "2026-10-28T12:00:00+01:00"}, "end": {"dateTime": "2026-10-28T13:00:00+01:00"}}""",
		)

		shouldThrow<InvitationNotRespondableException> {
			adapter.respondToInvitation(source, "primary", RemoteEventKey("own01"), AttendeeStatus.ACCEPTED, null)
		}.message shouldBe "You organize this event"
		shouldThrow<InvitationNotRespondableException> {
			adapter.respondToInvitation(source, "primary", RemoteEventKey("other01"), AttendeeStatus.ACCEPTED, null)
		}.message shouldBe "You are not invited to this event"
		wireMock.verify(0, patchRequestedFor(anyUrl()))
	}

	@Test
	fun `a failing Google is a provider error`() {
		stub(get(urlPathEqualTo("$EVENTS/inv001")), invitation("\"inv-1\"", "needsAction"))
		stub(patch(urlPathEqualTo("$EVENTS/inv001")), "{}", 400)

		shouldThrow<CalendarProviderException> {
			adapter.respondToInvitation(source, "primary", RemoteEventKey("inv001"), AttendeeStatus.ACCEPTED, null)
		}.message shouldBe "Answering an invitation failed at Google (HTTP 400)"
		wireMock.verify(2, anyRequestedFor(anyUrl()))
	}

	private fun declinedOrTentative(status: String) =
		equalToJson(
			"""{"attendees": [{"email": "sam.example@example.test", "displayName": "Sam Example", "organizer": true,
			"responseStatus": "accepted"}, {"email": "alex.example@example.test", "self": true, "responseStatus": "$status"}]}""",
		)

	companion object {
		const val API = "/calendar/v3"
		const val EVENTS = "$API/calendars/primary/events"

		val wireMock: WireMockServer = WireMockServer(wireMockConfig().dynamicPort()).apply { start() }

		@JvmStatic
		@AfterAll
		fun stop() = wireMock.stop()

		/** Sam's invitation of Alex (the user) at version [etag], Alex's answer being [status]. */
		fun invitation(
			etag: String,
			status: String,
		) = """{"etag": ${"\"" + etag.replace("\"", "\\\"") + "\""}, "id": "inv001", "status": "confirmed", "summary": "Design review",
			"start": {"dateTime": "2026-10-28T10:00:00+01:00", "timeZone": "Europe/Zurich"},
			"end": {"dateTime": "2026-10-28T11:00:00+01:00", "timeZone": "Europe/Zurich"}, "iCalUID": "inv001@google.com",
			"organizer": {"email": "sam.example@example.test", "displayName": "Sam Example"},
			"attendees": [
			  {"email": "sam.example@example.test", "displayName": "Sam Example", "organizer": true, "responseStatus": "accepted"},
			  {"email": "alex.example@example.test", "self": true, "responseStatus": "$status"}]}"""

		fun stub(
			mapping: MappingBuilder,
			body: String,
			status: Int = 200,
		) {
			wireMock.stubFor(mapping.willReturn(aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(body)))
		}
	}
}
