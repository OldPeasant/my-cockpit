package ch.sonensei.cockpit.calendar.infra.microsoft

import ch.sonensei.cockpit.calendar.api.CalendarApi
import ch.sonensei.cockpit.calendar.api.CalendarApi.Companion.etag
import ch.sonensei.cockpit.calendar.api.CalendarApi.Companion.id
import ch.sonensei.cockpit.calendar.api.CalendarApi.Companion.isProblem
import ch.sonensei.cockpit.calendar.infra.microsoft.MicrosoftCalendarAdapterTests.Companion.API
import ch.sonensei.cockpit.calendar.infra.microsoft.MicrosoftCalendarAdapterTests.Companion.CALENDAR
import ch.sonensei.cockpit.calendar.infra.microsoft.MicrosoftCalendarAdapterTests.Companion.CONNECTION
import ch.sonensei.cockpit.calendar.infra.microsoft.MicrosoftCalendarAdapterTests.Companion.DELTA
import ch.sonensei.cockpit.calendar.infra.microsoft.MicrosoftCalendarAdapterTests.Companion.EVENTS
import ch.sonensei.cockpit.calendar.infra.microsoft.MicrosoftCalendarAdapterTests.Companion.NOW
import ch.sonensei.cockpit.calendar.infra.microsoft.MicrosoftCalendarAdapterTests.Companion.at
import ch.sonensei.cockpit.calendar.infra.microsoft.MicrosoftCalendarAdapterTests.Companion.recording
import ch.sonensei.cockpit.calendar.infra.microsoft.MicrosoftCalendarAdapterTests.FixedTokens
import ch.sonensei.cockpit.calendar.model.CalendarEvent
import ch.sonensei.cockpit.calendar.model.CalendarEventRepository
import ch.sonensei.cockpit.calendar.model.CalendarRepository
import ch.sonensei.cockpit.calendar.model.CalendarSource
import ch.sonensei.cockpit.calendar.model.CalendarSourceRepository
import ch.sonensei.cockpit.calendar.model.CalendarSourceType
import ch.sonensei.cockpit.calendar.model.OccurrenceId
import ch.sonensei.cockpit.calendar.sync.CalendarSyncService
import ch.sonensei.cockpit.core.auth.WithCockpitUser
import ch.sonensei.cockpit.core.oauth.domain.OAuthTokenProvider
import ch.sonensei.cockpit.core.persistence.MutableClock
import ch.sonensei.cockpit.core.persistence.MutableClockTestConfiguration
import ch.sonensei.cockpit.support.PostgresTestBase
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.MappingBuilder
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.absent
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.patch
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import com.github.tomakehurst.wiremock.stubbing.Scenario
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
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
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.time.LocalDate
import java.util.UUID

/**
 * The sync engine with the real [MicrosoftCalendarAdapter] against WireMock playing Microsoft Graph, persisting to
 * Postgres: pulling recorded delta listings (complete, incremental, removals), and writes from the event API (create,
 * "this and following", 412).
 *
 * The WireMock URL gives this class its own Spring context; [DirtiesContext] closes it (and its pool) afterwards.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MutableClockTestConfiguration::class, MicrosoftCalendarSyncTests.TokensConfiguration::class)
@WithCockpitUser
@DirtiesContext
class MicrosoftCalendarSyncTests(
	@Autowired private val mockMvc: MockMvc,
	@Autowired private val jsonMapper: JsonMapper,
	@Autowired private val sources: CalendarSourceRepository,
	@Autowired private val calendars: CalendarRepository,
	@Autowired private val events: CalendarEventRepository,
	@Autowired private val sync: CalendarSyncService,
	@Autowired private val clock: MutableClock,
	@Autowired private val jdbc: JdbcTemplate,
	@Autowired private val tx: TransactionTemplate,
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
		clock.set(NOW)
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

	private fun calendarId(): UUID = calendars.findBySourceIdAndExternalId(source.id, CALENDAR)!!.id

	private fun local(graphId: String): CalendarEvent? = events.findByCalendarIdAndExternalIdAndParentEventIdIsNull(calendarId(), graphId)

	private fun masters(): List<CalendarEvent> = events.findAllById(events.findIdsByCalendarId(calendarId())).filter { !it.isOverride }

	private fun overridesOf(event: CalendarEvent) = events.findByParentEventIdOrderByIdAsc(event.id)

	private fun exdatesOf(id: UUID): Set<OccurrenceId> =
		tx.execute {
			events
				.findById(id)
				.get()
				.exdates
				.toSet()
		}!!

	private fun cursor(): String? =
		jdbc.queryForList("select cursor from calendar_sync_cursor where calendar_id = ?", String::class.java, calendarId()).singleOrNull()

	private fun syncOk() {
		sync
			.sync(source.id)
			.status!!
			.lastError
			.shouldBeNull()
	}

	// --- pull

	@Test
	fun `a first sync stores the calendar and its events, a later one merges the delta, a removal relists the calendar`() {
		stubFullListing()

		syncOk()

		calendars.findById(calendarId()).get().color shouldBe "#0078D4"
		masters().map { it.title } shouldContainExactlyInAnyOrder listOf("Dentist", "Offsite", "Team sync")
		val series = local("AAMkSeries1").shouldNotBeNull()
		series.rrule shouldBe "FREQ=WEEKLY;BYDAY=MO;UNTIL=20261228T225959Z"
		exdatesOf(series.id) shouldBe setOf(at("2026-11-09T08:00:00Z"))
		val override = overridesOf(series).single()
		override.recurrenceId shouldBe at("2026-10-19T07:00:00Z")
		override.title shouldBe "Team sync (moved)"
		override.externalEtag shouldBe "W/\"DwAAABYAAAA4\""
		local("AAMkAllDay1")!!.startDate shouldBe LocalDate.parse("2026-11-02")
		jsonMapper.readTree(cursor()).get("link").asString() shouldBe "/me/calendars/$CALENDAR/calendarView/delta?\$deltatoken=DELTA-1"

		stub(get(urlPathEqualTo(DELTA)).withQueryParam("\$deltatoken", equalTo("DELTA-1")), recording("delta-incremental.json"))

		syncOk()

		masters().map { it.title } shouldContainExactlyInAnyOrder listOf("Dentist (moved)", "Offsite", "Team sync", "Lunch with Sam")
		overridesOf(series).single().title shouldBe "Team sync (moved)"

		// Lunch is removed: Graph names only its id, so the calendar is listed again (now without the Offsite, too).
		stub(get(urlPathEqualTo(DELTA)).withQueryParam("\$deltatoken", equalTo("DELTA-2")), recording("delta-removed.json"))
		stub(
			get(urlPathEqualTo(DELTA)).withQueryParam("startDateTime", equalTo("2025-10-21T00:00:00Z")),
			recording("delta-full-page2.json").replace("DELTA-1", "DELTA-4"),
		)

		syncOk()

		masters().map { it.title } shouldContainExactlyInAnyOrder listOf("Team sync")
		overridesOf(series).single().title shouldBe "Team sync (moved)"
		jsonMapper.readTree(cursor()).get("link").asString() shouldBe "/me/calendars/$CALENDAR/calendarView/delta?\$deltatoken=DELTA-4"
	}

	// --- write-through

	@Test
	fun `an event created in the cockpit is created at Microsoft and keeps Graph's id and etag`() {
		stub(get(urlPathEqualTo(DELTA)), """{"value": [], "@odata.deltaLink": "https://graph.microsoft.com/v1.0/x?${'$'}deltatoken=D"}""")
		syncOk()
		stub(post(urlPathEqualTo("$API/me/calendars/$CALENDAR/events")), recording("event-created.json"), 201)

		val created =
			api.createEvent(
				"""{"calendarId":"${calendarId()}","title":"Planning","start":"2026-10-28T08:00:00Z","end":"2026-10-28T09:00:00Z"}""",
			)

		val stored = events.findById(UUID.fromString(created.id())).get()
		stored.externalId shouldBe "AAMkCreated1"
		stored.externalEtag shouldBe "W/\"DwAAABYAAAC1\""
		created.get("syncState").asString() shouldBe "SYNCED"
		writes() shouldContainExactly listOf("POST $API/me/calendars/$CALENDAR/events")
		jsonMapper.readTree(requestBody("POST")).get("transactionId").asString() shouldBe created.id()
	}

	@Test
	fun `changing this and following occurrences ends the series at Microsoft and creates the rest as a new one`() {
		stubFullListing()
		syncOk()
		val series = local("AAMkSeries1").shouldNotBeNull()
		// Graph answers with the shortened series once it is patched.
		val ended = master().apply { (get("recurrence").get("range") as ObjectNode).put("endDate", "2026-11-15") }
		wireMock.stubFor(
			get(urlPathEqualTo("$EVENTS/AAMkSeries1"))
				.inScenario("split")
				.whenScenarioStateIs("ended")
				.willReturn(json(jsonMapper.writeValueAsString(ended))),
		)
		wireMock.stubFor(
			patch(urlPathEqualTo("$EVENTS/AAMkSeries1"))
				.inScenario("split")
				.whenScenarioStateIs(Scenario.STARTED)
				.willSetStateTo("ended")
				.willReturn(json(jsonMapper.writeValueAsString(ended))),
		)
		val next =
			master().apply {
				put("id", "AAMkSeries2")
				put("subject", "Team sync (new)")
				put("@odata.etag", "W/\"new-series\"")
				set("start", jsonMapper.readTree("""{"dateTime": "2026-11-16T08:00:00.0000000", "timeZone": "UTC"}"""))
				set("end", jsonMapper.readTree("""{"dateTime": "2026-11-16T08:30:00.0000000", "timeZone": "UTC"}"""))
				(get("recurrence").get("range") as ObjectNode).put("startDate", "2026-11-16")
			}
		stub(post(urlPathEqualTo("$API/me/calendars/$CALENDAR/events")), jsonMapper.writeValueAsString(next), 201)
		stub(get(urlPathEqualTo("$EVENTS/AAMkSeries2")), jsonMapper.writeValueAsString(next))
		val instances =
			listOf("2026-11-16", "2026-11-23", "2026-11-30", "2026-12-07", "2026-12-14", "2026-12-21", "2026-12-28").map {
				mapOf(
					"id" to "AAMkSeries2-$it",
					"type" to "occurrence",
					"seriesMasterId" to "AAMkSeries2",
					"start" to mapOf("dateTime" to "${it}T08:00:00.0000000", "timeZone" to "UTC"),
					"end" to mapOf("dateTime" to "${it}T08:30:00.0000000", "timeZone" to "UTC"),
				)
			}
		stub(get(urlPathEqualTo("$EVENTS/AAMkSeries2/instances")), jsonMapper.writeValueAsString(mapOf("value" to instances)))

		val changed =
			api.patchEventOk(series.id.toString(), """{"title":"Team sync (new)"}""", "?scope=FOLLOWING&occurrence=2026-11-16T08:00:00Z")

		writes() shouldContainExactly listOf("PATCH $EVENTS/AAMkSeries1", "POST $API/me/calendars/$CALENDAR/events")
		val patchBody = jsonMapper.readTree(requestBody("PATCH"))
		patchBody.propertyNames().toList() shouldContainExactly listOf("recurrence")
		patchBody
			.get("recurrence")
			.get("range")
			.get("endDate")
			.asString() shouldBe "2026-11-15"
		val postBody = jsonMapper.readTree(requestBody("POST"))
		postBody.get("subject").asString() shouldBe "Team sync (new)"
		postBody.get("start").get("dateTime").asString() shouldBe "2026-11-16T09:00:00"
		postBody
			.get("recurrence")
			.get("range")
			.get("startDate")
			.asString() shouldBe "2026-11-16"
		postBody
			.get("recurrence")
			.get("range")
			.get("endDate")
			.asString() shouldBe "2026-12-28"

		val old = local("AAMkSeries1")!!
		old.rrule shouldBe "FREQ=WEEKLY;BYDAY=MO;UNTIL=20261115T225959Z"
		exdatesOf(old.id) shouldBe setOf(at("2026-11-09T08:00:00Z"))
		val continued = local("AAMkSeries2").shouldNotBeNull()
		continued.id.toString() shouldBe changed.id()
		continued.externalEtag shouldBe "W/\"new-series\""
		exdatesOf(continued.id) shouldBe emptySet()
	}

	@Test
	fun `412 from Graph fails the edit with 409 and shows Microsoft's version`() {
		stubFullListing()
		syncOk()
		val id = local("AAMkSingle1")!!.id.toString()
		val etag = etag(api.event(id))
		stub(patch(urlPathEqualTo("$EVENTS/AAMkSingle1")), recording("error-412-irresolvable-conflict.json"), 412)
		stub(get(urlPathEqualTo("$EVENTS/AAMkSingle1")), single("delta-full-page1.json", "AAMkSingle1"))
		stub(get(urlPathEqualTo(DELTA)).withQueryParam("\$deltatoken", equalTo("DELTA-1")), recording("delta-incremental.json"))

		api.patchEvent(id, """{"title":"Mine"}""", ifMatch = etag).isProblem(409).andExpect {
			jsonPath("$.detail") { value(org.hamcrest.Matchers.containsString("changed remotely")) }
		}

		val refreshed = local("AAMkSingle1")!!
		refreshed.title shouldBe "Dentist (moved)"
		refreshed.externalEtag shouldBe "W/\"DwAAABYAAAA9\""
		writes() shouldContainExactly listOf("PATCH $EVENTS/AAMkSingle1")
	}

	private fun master(): ObjectNode = jsonMapper.readTree(recording("series1-master.json")) as ObjectNode

	/** The event [id] of recording [name]. */
	private fun single(
		name: String,
		id: String,
	): String = jsonMapper.writeValueAsString(jsonMapper.readTree(recording(name)).get("value").first { it.get("id").asString() == id })

	/** The writes Graph got, oldest first: `METHOD path`. */
	private fun writes(): List<String> =
		wireMock.allServeEvents
			.map { it.request }
			.filter { it.method.value() != "GET" }
			.reversed()
			.map { "${it.method.value()} ${it.url.substringBefore('?')}" }

	private fun requestBody(method: String): String =
		wireMock.allServeEvents
			.map { it.request }
			.first { it.method.value() == method }
			.bodyAsString

	private fun stubFullListing() {
		stub(get(urlPathEqualTo(DELTA)).withQueryParam("startDateTime", equalTo("2025-10-21T00:00:00Z")), recording("delta-full-page1.json"))
		stub(get(urlPathEqualTo(DELTA)).withQueryParam("\$skiptoken", equalTo("SKIP-1")), recording("delta-full-page2.json"))
		wireMock.stubFor(
			get(urlPathEqualTo("$EVENTS/AAMkSeries1"))
				.inScenario("split")
				.whenScenarioStateIs(Scenario.STARTED)
				.willReturn(json(recording("series1-master.json"))),
		)
		stub(
			get(urlPathEqualTo("$EVENTS/AAMkSeries1/instances")).withQueryParam("\$skip", absent()),
			recording("series1-instances-page1.json"),
		)
		stub(
			get(urlPathEqualTo("$EVENTS/AAMkSeries1/instances")).withQueryParam("\$skip", equalTo("6")),
			recording("series1-instances-page2.json"),
		)
	}

	private fun json(
		body: String,
		status: Int = 200,
	) = aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(body)

	private fun stub(
		mapping: MappingBuilder,
		body: String,
		status: Int = 200,
	) = wireMock.stubFor(mapping.willReturn(json(body, status)))

	companion object {
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
