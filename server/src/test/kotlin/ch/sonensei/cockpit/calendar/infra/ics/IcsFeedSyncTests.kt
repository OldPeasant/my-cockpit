package ch.sonensei.cockpit.calendar.infra.ics

import ch.sonensei.cockpit.calendar.api.CalendarApi
import ch.sonensei.cockpit.calendar.api.CalendarApi.Companion.isProblem
import ch.sonensei.cockpit.calendar.api.Timing
import ch.sonensei.cockpit.calendar.model.CalendarEvent
import ch.sonensei.cockpit.calendar.model.CalendarEventRepository
import ch.sonensei.cockpit.calendar.model.CalendarRepository
import ch.sonensei.cockpit.calendar.model.CalendarSource
import ch.sonensei.cockpit.calendar.model.CalendarSourceRepository
import ch.sonensei.cockpit.calendar.model.CalendarSourceType
import ch.sonensei.cockpit.calendar.model.OccurrenceId
import ch.sonensei.cockpit.calendar.sync.CalendarSyncService
import ch.sonensei.cockpit.core.auth.WithCockpitUser
import ch.sonensei.cockpit.support.PostgresTestBase
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.absent
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * The sync engine with the real [IcsFeedAdapter] against WireMock serving an ICS feed, persisting to Postgres: the first
 * download, conditional requests (304), changed and removed events, the read-only calendar, and the SSRF check with
 * the protection on (this context allows private networks only for the WireMock host name `localhost`).
 *
 * The WireMock URL gives this class its own Spring context; [DirtiesContext] closes it (and its pool) afterwards.
 */
@SpringBootTest(properties = ["cockpit.calendar.ics.allowed-private-hosts=localhost"])
@AutoConfigureMockMvc
@WithCockpitUser
@DirtiesContext
class IcsFeedSyncTests(
	@Autowired private val mockMvc: MockMvc,
	@Autowired private val jsonMapper: JsonMapper,
	@Autowired private val sources: CalendarSourceRepository,
	@Autowired private val calendars: CalendarRepository,
	@Autowired private val events: CalendarEventRepository,
	@Autowired private val sync: CalendarSyncService,
	@Autowired private val jdbc: JdbcTemplate,
	@Autowired private val tx: TransactionTemplate,
) : PostgresTestBase() {
	private val api = CalendarApi(mockMvc, jsonMapper)
	private lateinit var source: CalendarSource

	@BeforeEach
	fun reset() {
		CalendarApi.resetCalendarTables(jdbc)
		wireMock.resetAll()
		source = feedSource("http://localhost:${wireMock.port()}$FEED?token=s3cret")
	}

	private fun feedSource(url: String) =
		sources.saveAndFlush(CalendarSource(CalendarSourceType.ICS_FEED, "Team holidays").apply { config = """{"url": "$url"}""" })

	private fun serve(
		body: String,
		etag: String? = null,
	) {
		wireMock.stubFor(
			get(urlPathEqualTo(FEED)).willReturn(
				aResponse().withStatus(200).withHeader("Content-Type", "text/calendar; charset=utf-8").withBody(body).apply {
					etag?.let { withHeader("ETag", it) }
				},
			),
		)
	}

	private fun calendarId(): UUID = calendars.findBySourceIdAndExternalId(source.id, IcsFeedAdapter.FEED_CALENDAR)!!.id

	private fun local(uid: String): CalendarEvent? = events.findByCalendarIdAndExternalIdAndParentEventIdIsNull(calendarId(), uid)

	private fun all(): List<CalendarEvent> = events.findAllById(events.findIdsByCalendarId(calendarId()))

	private fun exdatesOf(id: UUID): Set<OccurrenceId> =
		tx.execute {
			events
				.findById(id)
				.get()
				.exdates
				.toSet()
		}!!

	private fun syncOk() {
		sync
			.sync(source.id)
			.status!!
			.lastError
			.shouldBeNull()
	}

	@Test
	fun `the first sync creates a read-only calendar with the feed's events, series and exceptions`() {
		serve(feed(STANDUP, RETRO, STANDUP_MOVED, HOLIDAY), etag = "\"v1\"")

		syncOk()

		val calendar = calendars.findById(calendarId()).get()
		calendar.readOnly shouldBe true
		calendar.name shouldBe "Team holidays"
		val standup = local("standup@example.test").shouldNotBeNull()
		standup.title shouldBe "Stand-up"
		standup.rrule shouldBe "FREQ=WEEKLY;BYDAY=MO;COUNT=10"
		Timing.of(standup) shouldBe
			Timing.Timed(Instant.parse("2026-11-02T08:00:00Z"), Instant.parse("2026-11-02T08:15:00Z"), ZoneId.of("Europe/Zurich"))
		exdatesOf(standup.id) shouldContainExactly setOf(OccurrenceId.of(Instant.parse("2026-11-16T08:00:00Z")))
		val moved = events.findByParentEventIdOrderByIdAsc(standup.id).single()
		moved.recurrenceId shouldBe OccurrenceId.of(Instant.parse("2026-11-09T08:00:00Z"))
		moved.startAt shouldBe Instant.parse("2026-11-09T09:00:00Z")
		local("retro@example.test").shouldNotBeNull().description shouldBe "What went well, what not\nNotes; later"
		val holiday = local("holiday@example.test").shouldNotBeNull()
		Timing.of(holiday) shouldBe Timing.AllDay(LocalDate.parse("2026-12-24"), LocalDate.parse("2026-12-27"))
	}

	@Test
	fun `a later sync sends the validators and changes nothing on 304`() {
		serve(feed(STANDUP, RETRO), etag = "\"v1\"")
		syncOk()
		val before = all().associate { it.id to it.version }
		wireMock.stubFor(get(urlPathEqualTo(FEED)).withHeader("If-None-Match", equalTo("\"v1\"")).willReturn(aResponse().withStatus(304)))

		syncOk()

		wireMock.verify(1, getRequestedFor(urlPathEqualTo(FEED)).withHeader("If-None-Match", absent()))
		wireMock.verify(1, getRequestedFor(urlPathEqualTo(FEED)).withHeader("If-None-Match", equalTo("\"v1\"")))
		all().associate { it.id to it.version } shouldBe before
	}

	@Test
	fun `Last-Modified is sent back as If-Modified-Since`() {
		wireMock.stubFor(
			get(urlPathEqualTo(FEED)).willReturn(
				aResponse().withStatus(200).withHeader("Last-Modified", "Wed, 21 Oct 2026 07:28:00 GMT").withBody(feed(RETRO)),
			),
		)
		syncOk()
		syncOk()

		wireMock.verify(
			1,
			getRequestedFor(urlPathEqualTo(FEED)).withHeader("If-Modified-Since", equalTo("Wed, 21 Oct 2026 07:28:00 GMT")),
		)
	}

	@Test
	fun `a changed feed updates changed events, deletes removed ones and leaves unchanged ones alone`() {
		serve(feed(STANDUP, RETRO, HOLIDAY), etag = "\"v1\"")
		syncOk()
		val retro = local("retro@example.test")!!
		val holiday = local("holiday@example.test")!!

		// A new DTSTAMP alone is no change.
		serve(feed(STANDUP, RETRO.replace("Retrospective", "Retro (moved)"), HOLIDAY).replace("DTSTAMP:20261001", "DTSTAMP:20261020"), "\"v2\"")
		syncOk()

		all().map { it.externalId } shouldContainExactlyInAnyOrder listOf("standup@example.test", "retro@example.test", "holiday@example.test")
		local("retro@example.test")!!.title shouldBe "Retro (moved)"
		local("retro@example.test")!!.version shouldBe retro.version + 1
		local("holiday@example.test")!!.version shouldBe holiday.version

		serve(feed(RETRO), "\"v3\"")
		syncOk()
		all().map { it.externalId } shouldContainExactly listOf("retro@example.test")
	}

	@Test
	fun `events of the feed cannot be created or changed through the event API`() {
		serve(feed(RETRO))
		syncOk()

		api
			.postJson(
				CalendarApi.EVENTS,
				"""{"calendarId": "${calendarId()}", "title": "Mine", "start": "2026-11-03T10:00:00Z", "end": "2026-11-03T11:00:00Z"}""",
			).isProblem(409)
		api.patchEvent(local("retro@example.test")!!.id.toString(), """{"title":"Mine"}""").isProblem(409)
		local("retro@example.test")!!.title shouldBe "Retrospective"
	}

	@Test
	fun `a failing feed is recorded without its URL and keeps the events`() {
		serve(feed(RETRO))
		syncOk()
		wireMock.stubFor(get(urlPathEqualTo(FEED)).willReturn(aResponse().withStatus(500)))

		val error =
			sync
				.sync(source.id)
				.status!!
				.lastError
				.shouldNotBeNull()

		error shouldContain "HTTP 500"
		error shouldNotContain "s3cret"
		local("retro@example.test").shouldNotBeNull()
	}

	@Test
	fun `a 200 answer that is not an iCalendar file keeps the events and records an error`() {
		serve(feed(STANDUP, RETRO), etag = "\"v1\"")
		syncOk()
		wireMock.stubFor(
			get(urlPathEqualTo(FEED)).willReturn(
				aResponse().withStatus(200).withHeader("Content-Type", "text/html").withBody("<html><body>Feed disabled</body></html>"),
			),
		)

		val error =
			sync
				.sync(source.id)
				.status!!
				.lastError
				.shouldNotBeNull()

		error shouldContain "not a complete iCalendar file"
		error shouldNotContain "s3cret"
		all().map { it.externalId } shouldContainExactlyInAnyOrder listOf("standup@example.test", "retro@example.test")
	}

	@Test
	fun `an unreadable event keeps its local copy until the feed can be read again`() {
		serve(feed(STANDUP, RETRO), etag = "\"v1\"")
		syncOk()

		serve(feed(STANDUP, RETRO.replace("DTSTART:20261105T140000Z", "DTSTART:soon")), etag = "\"v2\"")
		syncOk()
		local("retro@example.test").shouldNotBeNull()

		serve(feed(STANDUP), etag = "\"v3\"")
		syncOk()
		all().map { it.externalId } shouldContainExactly listOf("standup@example.test")
	}

	@Test
	fun `an exception of a cancelled series is left out, so the sync has no error`() {
		serve(feed(STANDUP.replace("SUMMARY:Stand-up", "SUMMARY:Stand-up\r\nSTATUS:CANCELLED"), STANDUP_MOVED, RETRO), etag = "\"v1\"")

		syncOk()

		all().map { it.externalId } shouldContainExactly listOf("retro@example.test")
	}

	@Test
	fun `feeds on loopback and private addresses are refused unless their host is allowed`() {
		serve(feed(RETRO))
		listOf("http://127.0.0.1:${wireMock.port()}$FEED", "http://[::1]:${wireMock.port()}$FEED", "http://10.0.0.7/cal.ics").forEach { url ->
			val blocked = feedSource(url)

			sync.sync(blocked.id).status!!.lastError shouldContain "not a public internet address"
		}
		wireMock.verify(0, getRequestedFor(urlPathEqualTo(FEED)))
	}

	@Test
	fun `a redirect to a private address is refused`() {
		wireMock.stubFor(
			get(
				urlPathEqualTo(FEED),
			).willReturn(aResponse().withStatus(302).withHeader("Location", "http://127.0.0.1:${wireMock.port()}/other.ics")),
		)

		sync.sync(source.id).status!!.lastError shouldContain "not a public internet address"
		wireMock.verify(0, getRequestedFor(urlPathEqualTo("/other.ics")))
	}

	companion object {
		const val FEED = "/feeds/team.ics"

		val wireMock: WireMockServer = WireMockServer(wireMockConfig().dynamicPort()).apply { start() }

		@JvmStatic
		@AfterAll
		fun stop() = wireMock.stop()

		@JvmStatic
		@DynamicPropertySource
		fun icsProperties(registry: DynamicPropertyRegistry) {
			registry.add("cockpit.calendar.ics.max-redirects") { "2" }
		}

		fun feed(vararg events: String) =
			(
				listOf("BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Example//Feed//EN", "X-WR-TIMEZONE:Europe/Zurich") +
					events.toList() + "END:VCALENDAR"
			).joinToString("\r\n", postfix = "\r\n")

		val STANDUP =
			"""
			BEGIN:VEVENT
			UID:standup@example.test
			DTSTAMP:20261001T000000Z
			DTSTART;TZID=Europe/Zurich:20261102T090000
			DTEND;TZID=Europe/Zurich:20261102T091500
			RRULE:FREQ=WEEKLY;BYDAY=MO;COUNT=10
			EXDATE;TZID=Europe/Zurich:20261116T090000
			SUMMARY:Stand-up
			END:VEVENT
			""".trimIndent().replace("\n", "\r\n")

		val STANDUP_MOVED =
			"""
			BEGIN:VEVENT
			UID:standup@example.test
			DTSTAMP:20261001T000000Z
			RECURRENCE-ID;TZID=Europe/Zurich:20261109T090000
			DTSTART;TZID=Europe/Zurich:20261109T100000
			DTEND;TZID=Europe/Zurich:20261109T101500
			SUMMARY:Stand-up (later)
			END:VEVENT
			""".trimIndent().replace("\n", "\r\n")

		val RETRO =
			"""
			BEGIN:VEVENT
			UID:retro@example.test
			DTSTAMP:20261001T000000Z
			DTSTART:20261105T140000Z
			DURATION:PT1H
			SUMMARY:Retrospective
			DESCRIPTION:What went well\, what not\nNotes\; later
			END:VEVENT
			""".trimIndent().replace("\n", "\r\n")

		val HOLIDAY =
			"""
			BEGIN:VEVENT
			UID:holiday@example.test
			DTSTAMP:20261001T000000Z
			DTSTART;VALUE=DATE:20261224
			DTEND;VALUE=DATE:20261227
			SUMMARY:Christmas break
			TRANSP:TRANSPARENT
			END:VEVENT
			""".trimIndent().replace("\n", "\r\n")
	}
}
