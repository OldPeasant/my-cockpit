package ch.sonensei.cockpit.calendar.infra.google

import ch.sonensei.cockpit.calendar.api.CalendarApi
import ch.sonensei.cockpit.calendar.api.CalendarApi.Companion.etag
import ch.sonensei.cockpit.calendar.api.CalendarApi.Companion.id
import ch.sonensei.cockpit.calendar.api.CalendarApi.Companion.isProblem
import ch.sonensei.cockpit.calendar.api.Timing
import ch.sonensei.cockpit.calendar.infra.google.GoogleCalendarAdapterTests.Companion.API
import ch.sonensei.cockpit.calendar.infra.google.GoogleCalendarAdapterTests.Companion.CONNECTION
import ch.sonensei.cockpit.calendar.infra.google.GoogleCalendarAdapterTests.Companion.recording
import ch.sonensei.cockpit.calendar.infra.google.GoogleCalendarAdapterTests.FixedTokens
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
import com.github.tomakehurst.wiremock.client.WireMock.delete
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.patch
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
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
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * The sync engine with the real [GoogleCalendarAdapter] against WireMock playing Google, persisting to Postgres:
 * pulling recorded listings (complete, incremental, 410), and writes from the event API (create, "this and
 * following", 412). WireMock answers writes by echoing the request with Google's id and etag (response templates).
 *
 * The WireMock URL gives this class its own Spring context; [DirtiesContext] closes it (and its pool) afterwards.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MutableClockTestConfiguration::class, GoogleCalendarSyncTests.TokensConfiguration::class)
@WithCockpitUser
@DirtiesContext
class GoogleCalendarSyncTests(
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
		clock.set(MutableClockTestConfiguration.START)
		wireMock.resetAll()
		stub(
			get(urlPathEqualTo("$API/users/me/calendarList")),
			"""{"items": [{"id": "primary", "summary": "Alex", "accessRole": "owner", "backgroundColor": "#9fe1e7"}]}""",
		)
		source =
			sources.saveAndFlush(
				CalendarSource(CalendarSourceType.GOOGLE, "Google").apply { config = """{"connectionId": "$CONNECTION"}""" },
			)
	}

	private fun calendarId(): UUID = calendars.findBySourceIdAndExternalId(source.id, "primary")!!.id

	private fun local(googleId: String): CalendarEvent? = events.findByCalendarIdAndExternalIdAndParentEventIdIsNull(calendarId(), googleId)

	private fun masters(): List<CalendarEvent> = events.findAllById(events.findIdsByCalendarId(calendarId())).filter { !it.isOverride }

	private fun overridesOf(event: CalendarEvent) = events.findByParentEventIdOrderByIdAsc(event.id)

	private fun exdatesOf(id: UUID): Set<OccurrenceId> =
		tx.execute {
			events
				.findById(id)
				.get()
				.exdates
				.toSet()
		}

	private fun cursor(): String? =
		jdbc.queryForList("select cursor from calendar_sync_cursor where calendar_id = ?", String::class.java, calendarId()).singleOrNull()

	private fun at(instant: String) = OccurrenceId.of(Instant.parse(instant))

	private fun syncOk() {
		sync
			.sync(source.id)
			.status!!
			.lastError
			.shouldBeNull()
	}

	// --- pull

	@Test
	fun `a first sync stores Google's calendar and events, a later one merges the changes since the sync token`() {
		stubFullListing()

		syncOk()

		val calendar = calendars.findById(calendarId()).get()
		calendar.name shouldBe "Alex"
		masters().map { it.title } shouldContainExactlyInAnyOrder listOf("Project kickoff", "Weekly sync", "Offsite")
		val series = local("series01").shouldNotBeNull()
		series.rrule shouldBe "FREQ=WEEKLY;BYDAY=MO"
		// The EXDATE of the rule and the cancelled instance.
		exdatesOf(series.id) shouldBe setOf(at("2026-11-30T08:00:00Z"), at("2026-11-09T08:00:00Z"))
		val override = overridesOf(series).single()
		override.recurrenceId shouldBe at("2026-11-02T08:00:00Z")
		override.title shouldBe "Weekly sync (moved)"
		override.externalEtag shouldBe "\"3523456789014000\""
		local("allday01")!!.startDate shouldBe LocalDate.parse("2026-11-05")
		cursor() shouldBe "sync:EV-sync-1"

		stub(get(urlPathEqualTo(EVENTS)).withQueryParam("syncToken", equalTo("EV-sync-1")), recording("events-incremental.json"))
		stub(get(urlPathEqualTo(EVENTS)).withQueryParam("iCalUID", equalTo("series01@google.com")), recording("events-by-icaluid-series01.json"))

		syncOk()

		masters().map { it.title } shouldContainExactlyInAnyOrder listOf("Weekly sync (renamed)", "Offsite", "Dentist")
		local("evt001").shouldBeNull()
		// Google's cancelled instances stay removed although the changed series has no EXDATE.
		exdatesOf(series.id) shouldBe setOf(at("2026-11-09T08:00:00Z"), at("2026-11-16T08:00:00Z"))
		overridesOf(series).single().title shouldBe "Weekly sync (moved)"
		cursor() shouldBe "sync:EV-sync-2"
	}

	@Test
	fun `410 for the stored sync token lists the calendar again and removes what Google no longer has`() {
		stubFullListing()
		syncOk()
		stub(get(urlPathEqualTo(EVENTS)).withQueryParam("syncToken", equalTo("EV-sync-1")), recording("error-410-full-sync-required.json"), 410)
		stub(
			get(urlPathEqualTo(EVENTS)).withQueryParam("syncToken", absent()).withQueryParam("pageToken", absent()),
			"""{"timeZone": "Europe/Zurich", "nextSyncToken": "EV-sync-3", "items": [$OFFSITE]}""",
		)

		syncOk()

		masters().map { it.title } shouldContainExactly listOf("Offsite")
		cursor() shouldBe "sync:EV-sync-3"
	}

	// --- write-through

	@Test
	fun `an event created in the cockpit is inserted at Google and keeps Google's id and etag`() {
		stub(get(urlPathEqualTo(EVENTS)), """{"items": [], "nextSyncToken": "S1"}""")
		syncOk()
		stubInsert()

		val created =
			api.createEvent(
				"""{"calendarId":"${calendarId()}","title":"Planning","start":"2026-10-28T09:00:00Z","end":"2026-10-28T10:00:00Z"}""",
			)

		val googleId = created.id().replace("-", "")
		val stored = events.findById(UUID.fromString(created.id())).get()
		stored.externalId shouldBe googleId
		stored.externalEtag shouldBe "\"new-1\""
		created.get("syncState").asString() shouldBe "SYNCED"
		writes() shouldContainExactly listOf("POST $EVENTS")
	}

	@Test
	fun `changing this and following occurrences ends the series at Google and continues it as a new one with its exceptions`() {
		stub(get(urlPathEqualTo(EVENTS)), """{"timeZone": "Europe/Zurich", "nextSyncToken": "S1", "items": [$WEEKLY, $WEEKLY_EXCEPTION]}""")
		syncOk()
		val series = local("weekly01").shouldNotBeNull()
		stub(delete(urlPathEqualTo("$EVENTS/weekly01_20261116T080000Z")), "", 204)
		stubEcho(patch(urlPathEqualTo("$EVENTS/weekly01")), """{"id": "weekly01", "etag": "\"weekly-2\""}""")
		stubInsert()
		stubEcho(
			patch(urlPathMatching("$EVENTS/[0-9a-f]{32}_20261116T080000Z")),
			"""
			{"id": "{{request.pathSegments.[5]}}", "etag": "\"exception-1\"",
			 "recurringEventId": "{{regexExtract request.pathSegments.[5] '[0-9a-f]+'}}",
			 "originalStartTime": {"dateTime": "2026-11-16T09:00:00+01:00", "timeZone": "Europe/Zurich"}}
			""",
		)

		val changed =
			api.patchEventOk(series.id.toString(), """{"title":"Weekly sync (new)"}""", "?scope=FOLLOWING&occurrence=2026-11-09T08:00:00Z")

		val next = changed.id()
		val nextGoogleId = next.replace("-", "")
		writes() shouldContainExactly
			listOf(
				"DELETE $EVENTS/weekly01_20261116T080000Z",
				"PATCH $EVENTS/weekly01",
				"POST $EVENTS",
				"PATCH $EVENTS/${nextGoogleId}_20261116T080000Z",
			)
		val requests = wireMock.allServeEvents.map { it.request }
		requests.single { it.method.value() == "DELETE" }.getHeader("If-Match") shouldBe "\"exception-0\""
		val ended = requests.single { it.url.contains("/weekly01?") }
		ended.getHeader("If-Match") shouldBe "\"weekly-1\""
		jsonMapper
			.readTree(ended.bodyAsString)
			.get("recurrence")
			.values()
			.map { it.asString() } shouldContainExactly
			listOf("RRULE:FREQ=WEEKLY;BYDAY=MO;UNTIL=20261109T075959Z")
		val inserted = jsonMapper.readTree(requests.single { it.method.value() == "POST" }.bodyAsString)
		inserted.get("id").asString() shouldBe nextGoogleId
		inserted.get("summary").asString() shouldBe "Weekly sync (new)"
		inserted.get("start").get("dateTime").asString() shouldBe "2026-11-09T09:00:00+01:00"
		inserted.get("recurrence").values().map { it.asString() } shouldContainExactly listOf("RRULE:FREQ=WEEKLY;BYDAY=MO")
		val exception =
			jsonMapper.readTree(
				requests.single { it.url.contains("_20261116T080000Z?") && it.method.value() == "PATCH" }.bodyAsString,
			)
		exception.get("summary").asString() shouldBe "Weekly sync (new)"
		exception.get("start").get("dateTime").asString() shouldBe "2026-11-16T10:00:00+01:00"

		// The local copies carry Google's ids and etags.
		val old = local("weekly01")!!
		old.rrule shouldBe "FREQ=WEEKLY;BYDAY=MO;UNTIL=20261109T075959Z"
		old.externalEtag shouldBe "\"weekly-2\""
		overridesOf(old) shouldBe emptyList()
		val continued = local(nextGoogleId).shouldNotBeNull()
		continued.id.toString() shouldBe next
		continued.externalEtag shouldBe "\"new-1\""
		val moved = overridesOf(continued).single()
		moved.recurrenceId shouldBe at("2026-11-16T08:00:00Z")
		moved.externalId shouldBe nextGoogleId
		moved.externalEtag shouldBe "\"exception-1\""
		Timing.of(moved) shouldBe
			Timing.Timed(Instant.parse("2026-11-16T09:00:00Z"), Instant.parse("2026-11-16T09:30:00Z"), java.time.ZoneId.of("Europe/Zurich"))
	}

	@Test
	fun `412 from Google fails the edit with 409 and shows Google's version`() {
		stubFullListing()
		syncOk()
		val id = local("evt001")!!.id.toString()
		val etag = etag(api.event(id))
		stub(patch(urlPathEqualTo("$EVENTS/evt001")), recording("error-412-condition-not-met.json"), 412)
		stub(
			get(urlPathEqualTo(EVENTS)).withQueryParam("syncToken", equalTo("EV-sync-1")),
			"""{"timeZone": "Europe/Zurich", "nextSyncToken": "EV-sync-2", "items": [$KICKOFF_MOVED]}""",
		)

		api.patchEvent(id, """{"title":"Mine"}""", ifMatch = etag).isProblem(409).andExpect {
			jsonPath("$.detail") { value(org.hamcrest.Matchers.containsString("changed remotely")) }
		}

		val refreshed = local("evt001")!!
		refreshed.title shouldBe "Project kickoff (moved by Sam)"
		refreshed.externalEtag shouldBe "\"3523456789099000\""
		writes() shouldContainExactly listOf("PATCH $EVENTS/evt001")
	}

	/** The writes Google got, oldest first: `METHOD path`. */
	private fun writes(): List<String> =
		wireMock.allServeEvents
			.map { it.request }
			.filter { it.method.value() != "GET" }
			.reversed()
			.map { "${it.method.value()} ${it.url.substringBefore('?')}" }

	private fun stubFullListing() {
		stub(
			get(urlPathEqualTo(EVENTS)).withQueryParam("syncToken", absent()).withQueryParam("pageToken", absent()),
			recording("events-full-page1.json"),
		)
		stub(get(urlPathEqualTo(EVENTS)).withQueryParam("pageToken", equalTo("EV-page-2")), recording("events-full-page2.json"))
	}

	/** Inserts answer with the sent event, etag `"new-1"`. */
	private fun stubInsert() = stubEcho(post(urlPathEqualTo(EVENTS)), """{"etag": "\"new-1\""}""")

	/** Answers with the request's JSON body merged with [fields] (a response template). */
	private fun stubEcho(
		mapping: MappingBuilder,
		fields: String,
	) {
		wireMock.stubFor(
			mapping.willReturn(
				aResponse()
					.withHeader("Content-Type", "application/json")
					.withBody("{{#jsonMerge request.body}}$fields{{/jsonMerge}}")
					.withTransformers("response-template"),
			),
		)
	}

	private fun stub(
		mapping: MappingBuilder,
		body: String,
		status: Int = 200,
	) = wireMock.stubFor(mapping.willReturn(aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(body)))

	companion object {
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

		/** `evt001` as Sam changed it at Google. */
		private const val KICKOFF_MOVED =
			"""{"etag": "\"3523456789099000\"", "id": "evt001", "status": "confirmed", "summary": "Project kickoff (moved by Sam)",
			"start": {"dateTime": "2026-10-22T11:00:00+02:00", "timeZone": "Europe/Zurich"},
			"end": {"dateTime": "2026-10-22T12:00:00+02:00", "timeZone": "Europe/Zurich"}, "iCalUID": "evt001@google.com"}"""

		private const val OFFSITE =
			"""{"etag": "\"3523456789016000\"", "id": "allday01", "status": "tentative", "summary": "Offsite",
			"start": {"date": "2026-11-05"}, "end": {"date": "2026-11-07"}, "iCalUID": "allday01@google.com"}"""

		/** Mondays 09:00 in Zurich from 26 October 2026. */
		private const val WEEKLY =
			"""{"etag": "\"weekly-1\"", "id": "weekly01", "status": "confirmed", "summary": "Weekly sync",
			"start": {"dateTime": "2026-10-26T09:00:00+01:00", "timeZone": "Europe/Zurich"},
			"end": {"dateTime": "2026-10-26T09:30:00+01:00", "timeZone": "Europe/Zurich"},
			"recurrence": ["RRULE:FREQ=WEEKLY;BYDAY=MO"], "iCalUID": "weekly01@google.com"}"""

		/** The occurrence of 16 November an hour later. */
		private const val WEEKLY_EXCEPTION =
			"""{"etag": "\"exception-0\"", "id": "weekly01_20261116T080000Z", "status": "confirmed", "summary": "Weekly sync",
			"start": {"dateTime": "2026-11-16T10:00:00+01:00", "timeZone": "Europe/Zurich"},
			"end": {"dateTime": "2026-11-16T10:30:00+01:00", "timeZone": "Europe/Zurich"},
			"recurringEventId": "weekly01", "iCalUID": "weekly01@google.com",
			"originalStartTime": {"dateTime": "2026-11-16T09:00:00+01:00", "timeZone": "Europe/Zurich"}}"""
	}
}
