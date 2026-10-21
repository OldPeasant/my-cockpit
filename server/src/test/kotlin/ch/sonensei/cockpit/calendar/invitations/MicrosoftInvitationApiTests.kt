package ch.sonensei.cockpit.calendar.invitations

import ch.sonensei.cockpit.calendar.api.CalendarApi
import ch.sonensei.cockpit.calendar.api.CalendarApi.Companion.etag
import ch.sonensei.cockpit.calendar.invitations.MicrosoftInvitationAdapterTests.Companion.CALENDAR
import ch.sonensei.cockpit.calendar.invitations.MicrosoftInvitationAdapterTests.Companion.CONNECTION
import ch.sonensei.cockpit.calendar.invitations.MicrosoftInvitationAdapterTests.Companion.SERIES
import ch.sonensei.cockpit.calendar.invitations.MicrosoftInvitationAdapterTests.Companion.answeredOnlyInResponseStatus
import ch.sonensei.cockpit.calendar.invitations.MicrosoftInvitationAdapterTests.Companion.master
import ch.sonensei.cockpit.calendar.invitations.MicrosoftInvitationAdapterTests.Companion.occurrence
import ch.sonensei.cockpit.calendar.invitations.MicrosoftInvitationAdapterTests.Tokens
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
import ch.sonensei.cockpit.core.persistence.MutableClock
import ch.sonensei.cockpit.core.persistence.MutableClockTestConfiguration
import ch.sonensei.cockpit.support.PostgresTestBase
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.MappingBuilder
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import com.github.tomakehurst.wiremock.stubbing.Scenario
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
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
import java.time.Instant

/**
 * `POST /api/v1/calendar/events/{id}/respond` with the real [ch.sonensei.cockpit.calendar.infra.microsoft.MicrosoftCalendarAdapter]
 * against WireMock playing Microsoft Graph, persisting to Postgres. [DirtiesContext] closes this class's own context afterwards.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MutableClockTestConfiguration::class, MicrosoftInvitationApiTests.TokensConfiguration::class)
@WithCockpitUser
@DirtiesContext
class MicrosoftInvitationApiTests(
	@Autowired private val mockMvc: MockMvc,
	@Autowired private val jsonMapper: JsonMapper,
	@Autowired private val sources: CalendarSourceRepository,
	@Autowired private val calendars: CalendarRepository,
	@Autowired private val events: CalendarEventRepository,
	@Autowired private val sync: CalendarSyncService,
	@Autowired private val clock: MutableClock,
	@Autowired private val jdbc: JdbcTemplate,
) : PostgresTestBase() {
	private val api = CalendarApi(mockMvc, jsonMapper)
	private lateinit var source: CalendarSource

	@TestConfiguration(proxyBeanMethods = false)
	class TokensConfiguration {
		@Bean
		@Primary
		fun fixedTokens(): OAuthTokenProvider = Tokens().apply { email = "alex.example@example.test" }
	}

	@BeforeEach
	fun reset() {
		CalendarApi.resetCalendarTables(jdbc)
		clock.set(Instant.parse("2026-10-21T08:00:00Z"))
		wireMock.resetAll()
		stub(
			get(urlPathEqualTo("$API/me/calendars")),
			"""{"value": [{"id": "$CALENDAR", "name": "Calendar", "hexColor": "#0078D4", "canEdit": true}]}""",
		)
		source =
			sources.saveAndFlush(
				CalendarSource(CalendarSourceType.MICROSOFT, "Microsoft").apply { config = """{"connectionId": "$CONNECTION"}""" },
			)
	}

	private fun stored(): CalendarEvent {
		val calendar = calendars.findBySourceIdAndExternalId(source.id, CALENDAR)!!
		return events.findByCalendarIdAndExternalIdAndParentEventIdIsNull(calendar.id, SERIES)!!
	}

	@Test
	fun `accepting a series keeps the answer after the refetch, although Graph's attendee list does not show it yet`() {
		// Alex is invited to Sam's weekly series, not answered yet.
		stub(get(urlPathEqualTo(DELTA)).withQueryParam("startDateTime", equalTo("2025-10-21T00:00:00Z")), delta(master("ser-1", "none"), "D1"))
		stub(get(urlPathEqualTo("$EVENTS/$SERIES")).inScenario("series").whenScenarioStateIs(Scenario.STARTED), master("ser-1", "none"))
		stub(
			get(urlPathEqualTo("$EVENTS/$SERIES/instances")),
			"""{"value": [${listOf("2026-11-02", "2026-11-09", "2026-11-16").joinToString { occurrence(it, "occ-1", "none") }}]}""",
		)
		sync
			.sync(source.id)
			.status!!
			.lastError
			.shouldBeNull()
		val id = stored().id
		val before = api.event(id.toString())
		stored().attendees.single { it.email == "Alex.Example@example.test" }.status shouldBe AttendeeStatus.NEEDS_ACTION

		// After accepting, Graph shows the answer in the event's responseStatus only. By the refetch, it has a newer
		// version (ser-3) still without the answer among the attendees: the sync engine takes it over the stored one.
		val answered = answeredOnlyInResponseStatus(master("ser-2", "none"))
		val later = answeredOnlyInResponseStatus(master("ser-3", "none"))
		wireMock.stubFor(
			post(urlPathEqualTo("$EVENTS/$SERIES/accept"))
				.inScenario("series")
				.willSetStateTo("answered")
				.willReturn(aResponse().withStatus(202)),
		)
		wireMock.stubFor(
			get(urlPathEqualTo("$EVENTS/$SERIES"))
				.inScenario("series")
				.whenScenarioStateIs("answered")
				.willSetStateTo("later")
				.willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(answered)),
		)
		stub(get(urlPathEqualTo("$EVENTS/$SERIES")).inScenario("series").whenScenarioStateIs("later"), later)
		stub(get(urlPathEqualTo(DELTA)).withQueryParam("\$deltatoken", equalTo("D1")), delta(later, "D2"))

		val result =
			mockMvc
				.post("/api/v1/calendar/events/$id/respond") {
					with(csrf())
					contentType = MediaType.APPLICATION_JSON
					content = """{"response":"ACCEPTED"}"""
					header(HttpHeaders.IF_MATCH, etag(before))
				}.andExpect {
					status { isOk() }
					jsonPath("$.attendees[1].status") { value("ACCEPTED") }
				}.andReturn()

		// The calendar was fetched again (a series), and the stored event still shows the answer.
		wireMock.verify(postRequestedFor(urlPathEqualTo("$EVENTS/$SERIES/accept")))
		wireMock.verify(getRequestedFor(urlPathEqualTo(DELTA)).withQueryParam("\$deltatoken", equalTo("D1")))
		val after = stored()
		after.attendees.single { it.email == "Alex.Example@example.test" }.status shouldBe AttendeeStatus.ACCEPTED
		after.externalEtag shouldBe "W/\"ser-3\""
		// The ETag returned is the stored version's: the client's next If-Match matches.
		result.response.getHeader(HttpHeaders.ETAG) shouldBe "\"${after.version}\""
		etag(api.event(id.toString())) shouldBe "\"${after.version}\""
	}

	/** A delta page with [event] and the delta link with token [next]. */
	private fun delta(
		event: String,
		next: String,
	) = """{"value": [$event], "@odata.deltaLink": "https://graph.example.test$DELTA?${'$'}deltatoken=$next"}"""

	private fun stub(
		mapping: MappingBuilder,
		body: String,
		status: Int = 200,
	) = wireMock.stubFor(mapping.willReturn(aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(body)))

	companion object {
		const val API = MicrosoftInvitationAdapterTests.API
		const val EVENTS = MicrosoftInvitationAdapterTests.EVENTS
		const val DELTA = "$API/me/calendars/$CALENDAR/calendarView/delta"

		val wireMock: WireMockServer = WireMockServer(wireMockConfig().dynamicPort()).apply { start() }

		@JvmStatic
		@AfterAll
		fun stop() = wireMock.stop()

		@JvmStatic
		@DynamicPropertySource
		fun graphApi(registry: DynamicPropertyRegistry) {
			registry.add("cockpit.calendar.microsoft.api-url") { wireMock.baseUrl() + API }
		}
	}
}
