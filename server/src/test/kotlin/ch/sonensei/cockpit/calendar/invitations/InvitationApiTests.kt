package ch.sonensei.cockpit.calendar.invitations

import ch.sonensei.cockpit.calendar.api.CalendarApi
import ch.sonensei.cockpit.calendar.api.CalendarApi.Companion.etag
import ch.sonensei.cockpit.calendar.api.CalendarApi.Companion.id
import ch.sonensei.cockpit.calendar.api.CalendarApi.Companion.isProblem
import ch.sonensei.cockpit.calendar.infra.google.GoogleCalendarAdapterTests.Companion.CONNECTION
import ch.sonensei.cockpit.calendar.infra.google.GoogleCalendarAdapterTests.FixedTokens
import ch.sonensei.cockpit.calendar.invitations.GoogleInvitationAdapterTests.Companion.invitation
import ch.sonensei.cockpit.calendar.model.AttendeeStatus
import ch.sonensei.cockpit.calendar.model.CalendarEvent
import ch.sonensei.cockpit.calendar.model.CalendarEventRepository
import ch.sonensei.cockpit.calendar.model.CalendarRepository
import ch.sonensei.cockpit.calendar.model.CalendarSource
import ch.sonensei.cockpit.calendar.model.CalendarSourceRepository
import ch.sonensei.cockpit.calendar.model.CalendarSourceType
import ch.sonensei.cockpit.calendar.sync.CalendarSyncService
import ch.sonensei.cockpit.core.auth.WithCockpitUser
import ch.sonensei.cockpit.core.oauth.domain.OAuthTokenProvider
import ch.sonensei.cockpit.core.persistence.MutableClockTestConfiguration
import ch.sonensei.cockpit.support.PostgresTestBase
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.MappingBuilder
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.absent
import com.github.tomakehurst.wiremock.client.WireMock.anyUrl
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.patch
import com.github.tomakehurst.wiremock.client.WireMock.patchRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/**
 * `POST /api/v1/calendar/events/{id}/respond` with the real [ch.sonensei.cockpit.calendar.infra.google.GoogleCalendarAdapter]
 * against WireMock playing Google, persisting to Postgres. [DirtiesContext] closes this class's own context afterwards.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MutableClockTestConfiguration::class, InvitationApiTests.TokensConfiguration::class)
@WithCockpitUser
@DirtiesContext
class InvitationApiTests(
	@Autowired private val mockMvc: MockMvc,
	@Autowired private val jsonMapper: JsonMapper,
	@Autowired private val sources: CalendarSourceRepository,
	@Autowired private val calendars: CalendarRepository,
	@Autowired private val events: CalendarEventRepository,
	@Autowired private val sync: CalendarSyncService,
	@Autowired private val jdbc: JdbcTemplate,
) : PostgresTestBase() {
	private val api = CalendarApi(mockMvc, jsonMapper)
	private lateinit var source: CalendarSource

	@TestConfiguration(proxyBeanMethods = false)
	class TokensConfiguration {
		@Bean
		@Primary
		fun fixedTokens(): OAuthTokenProvider = FixedTokens()
	}

	@BeforeEach
	fun reset() {
		CalendarApi.resetCalendarTables(jdbc)
		wireMock.resetAll()
		stub(get(urlPathEqualTo("$API/users/me/calendarList")), """{"items": [{"id": "primary", "summary": "Alex", "accessRole": "owner"}]}""")
		stub(
			get(urlPathEqualTo(EVENTS)).withQueryParam("syncToken", absent()),
			"""{"timeZone": "Europe/Zurich", "nextSyncToken": "S1", "items": [${invitation("\"inv-1\"", "needsAction")}]}""",
		)
		stub(get(urlPathEqualTo("$EVENTS/inv001")), invitation("\"inv-1\"", "needsAction"))
		source =
			sources.saveAndFlush(
				CalendarSource(CalendarSourceType.GOOGLE, "Google").apply { config = """{"connectionId": "$CONNECTION"}""" },
			)
		sync
			.sync(source.id)
			.status!!
			.lastError
			.shouldBeNull()
	}

	private fun invitationEvent(): CalendarEvent {
		val calendar = calendars.findBySourceIdAndExternalId(source.id, "primary")!!
		return events.findByCalendarIdAndExternalIdAndParentEventIdIsNull(calendar.id, "inv001")!!
	}

	private fun respond(
		id: Any,
		body: String,
		ifMatch: String?,
	) = mockMvc.post("/api/v1/calendar/events/$id/respond") {
		with(csrf())
		contentType = MediaType.APPLICATION_JSON
		content = body
		ifMatch?.let { header(HttpHeaders.IF_MATCH, it) }
	}

	@Test
	fun `accepting answers at Google and the event shows the user's attendee as accepted, with Google's new etag`() {
		val id = invitationEvent().id
		val before = api.event(id.toString())
		stub(patch(urlPathEqualTo("$EVENTS/inv001")), invitation("\"inv-2\"", "accepted"))

		val result =
			respond(id, """{"response":"ACCEPTED"}""", etag(before)).andExpect {
				status { isOk() }
				header { string(HttpHeaders.ETAG, "\"${before.get("version").asLong() + 1}\"") }
				jsonPath("$.attendees[1].email") { value("alex.example@example.test") }
				jsonPath("$.attendees[1].status") { value("ACCEPTED") }
			}

		api.read(result).id() shouldBe id.toString()
		val stored = invitationEvent()
		stored.attendees.single { it.email == "alex.example@example.test" }.status shouldBe AttendeeStatus.ACCEPTED
		stored.externalEtag shouldBe "\"inv-2\""
		wireMock.verify(
			patchRequestedFor(urlPathEqualTo("$EVENTS/inv001"))
				.withHeader("If-Match", equalTo("\"inv-1\""))
				.withQueryParam("sendUpdates", equalTo("all")),
		)
	}

	@Test
	fun `declining and answering tentatively are stored as well`() {
		val id = invitationEvent().id
		stub(patch(urlPathEqualTo("$EVENTS/inv001")), invitation("\"inv-2\"", "declined"))
		respond(id, """{"response":"DECLINED"}""", "*").andExpect { status { isOk() } }
		invitationEvent().attendees[1].status shouldBe AttendeeStatus.DECLINED

		stub(get(urlPathEqualTo("$EVENTS/inv001")), invitation("\"inv-2\"", "declined"))
		stub(patch(urlPathEqualTo("$EVENTS/inv001")), invitation("\"inv-3\"", "tentative"))
		respond(id, """{"response":"TENTATIVE"}""", "*").andExpect { jsonPath("$.attendees[1].status") { value("TENTATIVE") } }
	}

	@Test
	fun `If-Match is required and checked`() {
		val id = invitationEvent().id
		respond(id, """{"response":"ACCEPTED"}""", null).isProblem(428)
		respond(id, """{"response":"ACCEPTED"}""", "\"999\"").isProblem(412)
		wireMock.verify(0, patchRequestedFor(anyUrl()))
	}

	@Test
	fun `a missing or unknown response is a bad request, an unknown event not found`() {
		val id = invitationEvent().id
		respond(id, """{}""", "*").isProblem(400)
		respond(id, """{"response":"NEEDS_ACTION"}""", "*").isProblem(400)
		respond(UUID.randomUUID(), """{"response":"ACCEPTED"}""", "*").isProblem(404)
	}

	@Test
	fun `events of local calendars cannot be answered`() {
		val calendar = api.createCalendar()
		val event =
			api.createEvent(
				"""{"calendarId":"${calendar.id()}","title":"Lunch","start":"2026-10-28T11:00:00Z","end":"2026-10-28T12:00:00Z"}""",
			)

		respond(event.id(), """{"response":"ACCEPTED"}""", etag(event)).isProblem(409).andExpect {
			jsonPath("$.detail") { value(containsString("Google and Microsoft")) }
		}
	}

	@Test
	fun `an event the user is not invited to is a conflict and stays unchanged`() {
		val id = invitationEvent().id
		stub(get(urlPathEqualTo("$EVENTS/inv001")), invitation("\"inv-1\"", "needsAction").replace("\"self\": true, ", ""))

		respond(id, """{"response":"ACCEPTED"}""", "*").isProblem(409).andExpect {
			jsonPath("$.detail") { value("You are not invited to this event") }
		}
		invitationEvent().attendees[1].status shouldBe AttendeeStatus.NEEDS_ACTION
	}

	@Test
	fun `412 from Google is a conflict and the cockpit fetches Google's new version`() {
		val id = invitationEvent().id
		stub(get(urlPathEqualTo("$EVENTS/inv001")), invitation("\"inv-1\"", "needsAction"))
		stub(patch(urlPathEqualTo("$EVENTS/inv001")), "{}", 412)
		stub(
			get(urlPathEqualTo(EVENTS)).withQueryParam("syncToken", equalTo("S1")),
			"""{"nextSyncToken": "S2", "items": [${invitation("\"inv-5\"", "declined")}]}""",
		)

		respond(id, """{"response":"ACCEPTED"}""", "*").isProblem(409).andExpect {
			jsonPath("$.detail") { value(containsString("changed remotely")) }
		}
		invitationEvent().externalEtag shouldBe "\"inv-5\""
		invitationEvent().attendees[1].status shouldBe AttendeeStatus.DECLINED
	}

	@Test
	fun `a failing Google is a bad gateway and nothing changes`() {
		val id = invitationEvent().id
		stub(get(urlPathEqualTo("$EVENTS/inv001")), invitation("\"inv-1\"", "needsAction"))
		stub(patch(urlPathEqualTo("$EVENTS/inv001")), "{}", 503)

		respond(id, """{"response":"ACCEPTED"}""", "*").isProblem(502)
		invitationEvent().attendees[1].status shouldBe AttendeeStatus.NEEDS_ACTION
		invitationEvent().externalEtag shouldBe "\"inv-1\""
	}

	private fun stub(
		mapping: MappingBuilder,
		body: String,
		status: Int = 200,
	) = wireMock.stubFor(mapping.willReturn(aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(body)))

	companion object {
		const val API = "/calendar/v3"
		const val EVENTS = "$API/calendars/primary/events"

		val wireMock: WireMockServer = WireMockServer(wireMockConfig().dynamicPort()).apply { start() }

		@JvmStatic
		@AfterAll
		fun stop() = wireMock.stop()

		@JvmStatic
		@DynamicPropertySource
		fun googleApi(registry: DynamicPropertyRegistry) {
			registry.add("cockpit.calendar.google.api-url") { wireMock.baseUrl() + API }
		}
	}
}
