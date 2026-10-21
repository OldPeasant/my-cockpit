package ch.sonensei.cockpit.calendar.smoke

import ch.sonensei.cockpit.calendar.api.CalendarApi
import ch.sonensei.cockpit.calendar.api.CalendarApi.Companion.etag
import ch.sonensei.cockpit.calendar.api.CalendarApi.Companion.isProblem
import ch.sonensei.cockpit.calendar.model.CalendarEvent
import ch.sonensei.cockpit.calendar.model.CalendarEventRepository
import ch.sonensei.cockpit.calendar.model.CalendarRepository
import ch.sonensei.cockpit.calendar.model.CalendarSource
import ch.sonensei.cockpit.calendar.model.CalendarSourceRepository
import ch.sonensei.cockpit.calendar.model.CalendarSourceType
import ch.sonensei.cockpit.calendar.sync.CalendarSyncService
import ch.sonensei.cockpit.core.auth.WithCockpitUser
import ch.sonensei.cockpit.core.oauth.domain.OAuthAccessToken
import ch.sonensei.cockpit.core.oauth.domain.OAuthConnectionInfo
import ch.sonensei.cockpit.core.oauth.domain.OAuthConnectionStatus
import ch.sonensei.cockpit.core.oauth.domain.OAuthProvider
import ch.sonensei.cockpit.core.oauth.domain.OAuthResource
import ch.sonensei.cockpit.core.oauth.domain.OAuthTokenProvider
import ch.sonensei.cockpit.core.persistence.MutableClock
import ch.sonensei.cockpit.core.persistence.MutableClockTestConfiguration
import ch.sonensei.cockpit.support.ExternalTest
import ch.sonensei.cockpit.support.PostgresTestBase
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.MappingBuilder
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.absent
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.patch
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
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
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.time.Instant
import java.util.UUID

/**
 * Phase 4 demo script (SPEC.md §13.2), step 3: the sync logic for Google and Microsoft against WireMock serving the
 * recorded API answers (`calendar/infra/google`, `calendar/infra/microsoft`): a complete first sync, an incremental
 * sync with the stored sync token (Google) or delta link (Microsoft), and an edit refused with 412 that refetches the
 * provider's version and answers 409. The real accounts are the (H) part of the step. The detailed cases are in
 * `GoogleCalendarSyncTests` and `MicrosoftCalendarSyncTests`.
 *
 * The WireMock URL gives this class its own Spring context; [DirtiesContext] closes it (and its pool) afterwards.
 */
@ExternalTest
@SpringBootTest
@AutoConfigureMockMvc
@Import(MutableClockTestConfiguration::class, Phase4OAuthProvidersDemoTests.TokensConfiguration::class)
@WithCockpitUser
@DirtiesContext
class Phase4OAuthProvidersDemoTests(
	@Autowired mockMvc: MockMvc,
	@Autowired private val jsonMapper: JsonMapper,
	@Autowired private val sources: CalendarSourceRepository,
	@Autowired private val calendars: CalendarRepository,
	@Autowired private val events: CalendarEventRepository,
	@Autowired private val sync: CalendarSyncService,
	@Autowired private val clock: MutableClock,
	@Autowired private val jdbc: JdbcTemplate,
) : PostgresTestBase() {
	private val api = CalendarApi(mockMvc, jsonMapper)

	@TestConfiguration(proxyBeanMethods = false)
	class TokensConfiguration {
		@Bean
		@Primary
		fun demoTokens(): OAuthTokenProvider = DemoTokens()
	}

	@BeforeEach
	fun reset() {
		CalendarApi.resetCalendarTables(jdbc)
		clock.set(NOW)
		wireMock.resetAll()
	}

	@Test
	fun `step 3 - Google - first sync, incremental sync with the sync token, 412 refetches Google's version`() {
		stub(
			get(urlPathEqualTo("$GOOGLE/users/me/calendarList")),
			"""{"items": [{"id": "primary", "summary": "Alex", "accessRole": "owner", "backgroundColor": "#9fe1e7"}]}""",
		)
		stub(
			get(urlPathEqualTo(GOOGLE_EVENTS)).withQueryParam("syncToken", absent()).withQueryParam("pageToken", absent()),
			google("events-full-page1.json"),
		)
		stub(get(urlPathEqualTo(GOOGLE_EVENTS)).withQueryParam("pageToken", equalTo("EV-page-2")), google("events-full-page2.json"))
		val source = source(CalendarSourceType.GOOGLE, "Google", GOOGLE_CONNECTION)
		val calendarId = { calendars.findBySourceIdAndExternalId(source.id, "primary")!!.id }

		syncOk(source)

		titles(calendarId()) shouldContainExactlyInAnyOrder listOf("Project kickoff", "Weekly sync", "Offsite")
		cursor(calendarId()) shouldBe "sync:EV-sync-1"

		// Incremental: only the changes since the stored sync token.
		stub(get(urlPathEqualTo(GOOGLE_EVENTS)).withQueryParam("syncToken", equalTo("EV-sync-1")), google("events-incremental.json"))
		stub(
			get(urlPathEqualTo(GOOGLE_EVENTS)).withQueryParam("iCalUID", equalTo("series01@google.com")),
			google("events-by-icaluid-series01.json"),
		)

		syncOk(source)

		titles(calendarId()) shouldContainExactlyInAnyOrder listOf("Weekly sync (renamed)", "Offsite", "Dentist")
		cursor(calendarId()) shouldBe "sync:EV-sync-2"

		// Sam changed the Offsite at Google meanwhile: Google refuses the stale edit with 412.
		val offsite = local(calendarId(), "allday01")
		val etag = etag(api.event(offsite.id.toString()))
		stub(patch(urlPathEqualTo("$GOOGLE_EVENTS/allday01")), google("error-412-condition-not-met.json"), 412)
		stub(
			get(urlPathEqualTo(GOOGLE_EVENTS)).withQueryParam("syncToken", equalTo("EV-sync-2")),
			"""{"timeZone": "Europe/Zurich", "nextSyncToken": "EV-sync-3", "items": [$OFFSITE_BY_SAM]}""",
		)

		api.patchEvent(offsite.id.toString(), """{"title":"Mine"}""", ifMatch = etag).isProblem(409).andExpect {
			jsonPath("$.detail") { value(org.hamcrest.Matchers.containsString("changed remotely")) }
		}

		local(calendarId(), "allday01").apply {
			title shouldBe "Offsite (moved by Sam)"
			externalEtag shouldBe "\"3523456789099000\""
		}
		cursor(calendarId()) shouldBe "sync:EV-sync-3"
		writes() shouldContainExactly listOf("PATCH $GOOGLE_EVENTS/allday01")
	}

	@Test
	fun `step 3 - Microsoft - first sync, incremental sync with the delta link, 412 refetches Microsoft's version`() {
		stub(
			get(urlPathEqualTo("$GRAPH/me/calendars")),
			"""{"value": [{"id": "$GRAPH_CALENDAR", "name": "Calendar", "hexColor": "#0078D4", "canEdit": true}]}""",
		)
		stub(get(urlPathEqualTo(DELTA)).withQueryParam("startDateTime", equalTo("2025-10-21T00:00:00Z")), microsoft("delta-full-page1.json"))
		stub(get(urlPathEqualTo(DELTA)).withQueryParam("\$skiptoken", equalTo("SKIP-1")), microsoft("delta-full-page2.json"))
		stub(get(urlPathEqualTo("$GRAPH_EVENTS/AAMkSeries1")), microsoft("series1-master.json"))
		stub(
			get(urlPathEqualTo("$GRAPH_EVENTS/AAMkSeries1/instances")).withQueryParam("\$skip", absent()),
			microsoft("series1-instances-page1.json"),
		)
		stub(
			get(urlPathEqualTo("$GRAPH_EVENTS/AAMkSeries1/instances")).withQueryParam("\$skip", equalTo("6")),
			microsoft("series1-instances-page2.json"),
		)
		val source = source(CalendarSourceType.MICROSOFT, "Microsoft", GRAPH_CONNECTION)
		val calendarId = { calendars.findBySourceIdAndExternalId(source.id, GRAPH_CALENDAR)!!.id }

		syncOk(source)

		titles(calendarId()) shouldContainExactlyInAnyOrder listOf("Dentist", "Offsite", "Team sync")
		deltaLink(calendarId()) shouldBe "/me/calendars/$GRAPH_CALENDAR/calendarView/delta?\$deltatoken=DELTA-1"

		// Incremental: the stored delta link returns only the changes.
		stub(get(urlPathEqualTo(DELTA)).withQueryParam("\$deltatoken", equalTo("DELTA-1")), microsoft("delta-incremental.json"))

		syncOk(source)

		titles(calendarId()) shouldContainExactlyInAnyOrder listOf("Dentist (moved)", "Offsite", "Team sync", "Lunch with Sam")
		deltaLink(calendarId()) shouldBe "/me/calendars/$GRAPH_CALENDAR/calendarView/delta?\$deltatoken=DELTA-2"

		// Sam moved the dentist again in Outlook: Graph refuses the stale edit with 412.
		val dentist = local(calendarId(), "AAMkSingle1")
		val etag = etag(api.event(dentist.id.toString()))
		val bySam =
			(
				jsonMapper
					.readTree(microsoft("delta-incremental.json"))
					.get("value")
					.first { it.get("id").asString() == "AAMkSingle1" } as ObjectNode
			).put("subject", "Dentist (moved by Sam)").put("@odata.etag", "W/\"DwAAABYAAAB9\"")
		stub(patch(urlPathEqualTo("$GRAPH_EVENTS/AAMkSingle1")), microsoft("error-412-irresolvable-conflict.json"), 412)
		stub(get(urlPathEqualTo("$GRAPH_EVENTS/AAMkSingle1")), jsonMapper.writeValueAsString(bySam))
		stub(
			get(urlPathEqualTo(DELTA)).withQueryParam("\$deltatoken", equalTo("DELTA-2")),
			jsonMapper.writeValueAsString(
				mapOf(
					"@odata.deltaLink" to "https://graph.microsoft.com/v1.0/me/calendars/$GRAPH_CALENDAR/calendarView/delta?\$deltatoken=DELTA-3",
					"value" to listOf(bySam),
				),
			),
		)

		api.patchEvent(dentist.id.toString(), """{"title":"Mine"}""", ifMatch = etag).isProblem(409).andExpect {
			jsonPath("$.detail") { value(org.hamcrest.Matchers.containsString("changed remotely")) }
		}

		local(calendarId(), "AAMkSingle1").apply {
			title shouldBe "Dentist (moved by Sam)"
			externalEtag shouldBe "W/\"DwAAABYAAAB9\""
		}
		writes() shouldContainExactly listOf("PATCH $GRAPH_EVENTS/AAMkSingle1")
	}

	private fun source(
		type: CalendarSourceType,
		name: String,
		connection: UUID,
	): CalendarSource = sources.saveAndFlush(CalendarSource(type, name).apply { config = """{"connectionId": "$connection"}""" })

	private fun syncOk(source: CalendarSource) {
		sync
			.sync(source.id)
			.status!!
			.lastError
			.shouldBeNull()
	}

	private fun local(
		calendarId: UUID,
		externalId: String,
	): CalendarEvent = events.findByCalendarIdAndExternalIdAndParentEventIdIsNull(calendarId, externalId)!!

	/** The titles of the calendar's events, without the changed occurrences of a series. */
	private fun titles(calendarId: UUID): List<String> =
		events.findAllById(events.findIdsByCalendarId(calendarId)).filter { !it.isOverride }.map { it.title }

	private fun cursor(calendarId: UUID): String? =
		jdbc.queryForList("select cursor from calendar_sync_cursor where calendar_id = ?", String::class.java, calendarId).singleOrNull()

	private fun deltaLink(calendarId: UUID): String = jsonMapper.readTree(cursor(calendarId)).get("link").asString()

	/** The writes the providers got, oldest first: `METHOD path`. */
	private fun writes(): List<String> =
		wireMock.allServeEvents
			.map { it.request }
			.filter { it.method.value() != "GET" }
			.reversed()
			.map { "${it.method.value()} ${it.url.substringBefore('?')}" }

	private fun stub(
		mapping: MappingBuilder,
		body: String,
		status: Int = 200,
	) = wireMock.stubFor(mapping.willReturn(aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(body)))

	/** Bearer tokens for the two demo connections, each of its provider. */
	class DemoTokens : OAuthTokenProvider {
		private val providers = mapOf(GOOGLE_CONNECTION to OAuthProvider.GOOGLE, GRAPH_CONNECTION to OAuthProvider.MICROSOFT)

		override fun accessToken(
			connectionId: UUID,
			resource: OAuthResource,
		): OAuthAccessToken {
			require(connectionId in providers) { "unknown connection" }
			return OAuthAccessToken("fictitious-access-token", Instant.parse("2030-01-01T00:00:00Z"))
		}

		override fun connection(connectionId: UUID): OAuthConnectionInfo? =
			providers[connectionId]?.let {
				OAuthConnectionInfo(
					id = connectionId,
					provider = it,
					email = "alex.example@example.test",
					displayName = null,
					scopes = emptyList(),
					status = OAuthConnectionStatus.CONNECTED,
					lastError = null,
					lastRefreshedAt = null,
					createdAt = Instant.parse("2026-01-01T00:00:00Z"),
					version = 0,
				)
			}
	}

	companion object {
		private const val GOOGLE = "/calendar/v3"
		private const val GOOGLE_EVENTS = "$GOOGLE/calendars/primary/events"
		private const val GRAPH = "/v1.0"
		private const val GRAPH_CALENDAR = "AAMkCalPrimary"
		private const val GRAPH_EVENTS = "$GRAPH/me/calendars/$GRAPH_CALENDAR/events"
		private const val DELTA = "$GRAPH/me/calendars/$GRAPH_CALENDAR/calendarView/delta"
		private const val RECORDINGS = "/ch/sonensei/cockpit/calendar/infra"
		private val GOOGLE_CONNECTION: UUID = UUID.fromString("0192a5f0-0000-7000-8000-000000000001")
		private val GRAPH_CONNECTION: UUID = UUID.fromString("0192a5f0-0000-7000-8000-000000000002")

		/** The recordings' "now": the Microsoft listing starts a year before it. */
		private val NOW: Instant = Instant.parse("2026-10-21T08:00:00Z")

		/** `allday01` as Sam changed it at Google. */
		private const val OFFSITE_BY_SAM =
			"""{"etag": "\"3523456789099000\"", "id": "allday01", "status": "confirmed", "summary": "Offsite (moved by Sam)",
			"start": {"date": "2026-11-12"}, "end": {"date": "2026-11-14"}, "iCalUID": "allday01@google.com"}"""

		val wireMock: WireMockServer = WireMockServer(wireMockConfig().dynamicPort()).apply { start() }

		@JvmStatic
		@AfterAll
		fun stop() = wireMock.stop()

		@JvmStatic
		@DynamicPropertySource
		fun providerApis(registry: DynamicPropertyRegistry) {
			registry.add("cockpit.calendar.google.api-url") { wireMock.baseUrl() + GOOGLE }
			registry.add("cockpit.calendar.microsoft.api-url") { wireMock.baseUrl() + GRAPH }
		}

		private fun recording(path: String): String =
			checkNotNull(Phase4OAuthProvidersDemoTests::class.java.getResource("$RECORDINGS/$path")) { "no recording $path" }.readText()

		private fun google(name: String) = recording("google/$name")

		private fun microsoft(name: String) = recording("microsoft/$name")
	}
}
