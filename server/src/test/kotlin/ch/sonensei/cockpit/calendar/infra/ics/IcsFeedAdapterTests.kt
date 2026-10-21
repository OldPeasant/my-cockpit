package ch.sonensei.cockpit.calendar.infra.ics

import ch.sonensei.cockpit.calendar.api.Timing
import ch.sonensei.cockpit.calendar.model.CalendarSource
import ch.sonensei.cockpit.calendar.model.CalendarSourceType
import ch.sonensei.cockpit.calendar.sync.CalendarProviderException
import ch.sonensei.cockpit.calendar.sync.RemoteCalendar
import ch.sonensei.cockpit.calendar.sync.RemoteEvent
import ch.sonensei.cockpit.calendar.sync.RemoteEventKey
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.absent
import com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.anyUrl
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.util.unit.DataSize
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/** The adapter without Spring: config and cursor handling, the read-only calendar and refused writes. */
class IcsFeedAdapterTests {
	private val json = JsonMapper.builder().build()
	private val properties =
		IcsFeedProperties(
			pollInterval = Duration.ofMinutes(20),
			maxSize = DataSize.ofMegabytes(1),
			connectTimeout = Duration.ofSeconds(2),
			timeout = Duration.ofSeconds(5),
			maxRedirects = 2,
			allowPrivateNetworks = false,
			allowedPrivateHosts = listOf("localhost"),
		)
	private val adapter = IcsFeedAdapter(json, properties, IcsFeedClient(properties))
	private val url get() = "http://localhost:${wireMock.port()}$FEED"

	@BeforeEach
	fun reset() = wireMock.resetAll()

	private fun source(config: String) = CalendarSource(CalendarSourceType.ICS_FEED, "Team holidays").apply { this.config = config }

	private fun serve(
		body: String,
		vararg headers: Pair<String, String>,
	) = wireMock.stubFor(
		get(urlPathEqualTo(FEED)).willReturn(
			aResponse().withStatus(200).withBody(body).apply { headers.forEach { (name, value) -> withHeader(name, value) } },
		),
	)

	private val event =
		RemoteEvent(
			key = RemoteEventKey("x@example.test"),
			etag = null,
			title = "Not allowed",
			timing = Timing.Timed(Instant.parse("2026-11-05T09:00:00Z"), Instant.parse("2026-11-05T10:00:00Z"), ZoneOffset.UTC),
		)

	@Test
	fun `the source type and the poll interval come from the configuration`() {
		adapter.sourceType shouldBe CalendarSourceType.ICS_FEED
		adapter.pollInterval shouldBe Duration.ofMinutes(20)
	}

	@Test
	fun `a feed has one read-only calendar named like the source, without a request`() {
		adapter.listCalendars(source("""{"url": "$url"}""")) shouldContainExactly
			listOf(RemoteCalendar(IcsFeedAdapter.FEED_CALENDAR, "Team holidays", readOnly = true))
		wireMock.verify(0, anyRequestedFor(anyUrl()))
	}

	@Test
	fun `a source without a usable URL is refused`() {
		val configs = listOf("{}", """{"url": "   "}""", """{"url": 42}""", "[]", "not json", """{"other": "$url"}""")
		for (config in configs) {
			shouldThrow<CalendarProviderException> { adapter.listCalendars(source(config)) }.message!! shouldContain "no feed URL"
			shouldThrow<CalendarProviderException> { adapter.fetchEvents(source(config), IcsFeedAdapter.FEED_CALENDAR, null) }
		}
		wireMock.verify(0, anyRequestedFor(anyUrl()))
	}

	@Test
	fun `another calendar id is refused`() {
		shouldThrow<CalendarProviderException> { adapter.fetchEvents(source("""{"url": "$url"}"""), "other", null) }
			.message!! shouldContain "only one calendar"
	}

	@Test
	fun `a download is the complete calendar and its validators become the cursor`() {
		serve(CALENDAR, "ETag" to "\"v7\"", "Last-Modified" to "Wed, 21 Oct 2026 08:00:00 GMT")

		val changes = adapter.fetchEvents(source("""{"url": " $url "}"""), IcsFeedAdapter.FEED_CALENDAR, null)

		changes.complete shouldBe true
		changes.deleted.shouldBeEmpty()
		changes.events.map { it.key.externalId } shouldContainExactly listOf("retro@example.test")
		val cursor = json.readTree(changes.cursor.shouldNotBeNull())
		cursor.get("etag").asString() shouldBe "\"v7\""
		cursor.get("lastModified").asString() shouldBe "Wed, 21 Oct 2026 08:00:00 GMT"
	}

	@Test
	fun `the cursor is sent back as validators and 304 keeps it with no changes`() {
		wireMock.stubFor(get(urlPathEqualTo(FEED)).willReturn(aResponse().withStatus(304)))
		val cursor = """{"etag":"\"v7\"","lastModified":"Wed, 21 Oct 2026 08:00:00 GMT"}"""

		val changes = adapter.fetchEvents(source("""{"url": "$url"}"""), IcsFeedAdapter.FEED_CALENDAR, cursor)

		changes.cursor shouldBe cursor
		changes.events.shouldBeEmpty()
		changes.complete shouldBe false
		wireMock.verify(
			getRequestedFor(urlPathEqualTo(FEED))
				.withHeader("If-None-Match", equalTo("\"v7\""))
				.withHeader("If-Modified-Since", equalTo("Wed, 21 Oct 2026 08:00:00 GMT")),
		)
	}

	@Test
	fun `a cursor with only one validator sends only that one, and the new cursor keeps only what the server sent`() {
		serve(CALENDAR, "Last-Modified" to "Thu, 22 Oct 2026 08:00:00 GMT")

		val changes =
			adapter.fetchEvents(source("""{"url": "$url"}"""), IcsFeedAdapter.FEED_CALENDAR, """{"etag":"\"v7\""}""")

		wireMock.verify(
			getRequestedFor(urlPathEqualTo(FEED))
				.withHeader("If-None-Match", equalTo("\"v7\""))
				.withHeader("If-Modified-Since", absent()),
		)
		val cursor = json.readTree(changes.cursor.shouldNotBeNull())
		cursor.has("etag") shouldBe false
		cursor.get("lastModified").asString() shouldBe "Thu, 22 Oct 2026 08:00:00 GMT"
	}

	@Test
	fun `a feed without validators gives no cursor, and an unreadable cursor is a full download`() {
		serve(CALENDAR)

		val changes = adapter.fetchEvents(source("""{"url": "$url"}"""), IcsFeedAdapter.FEED_CALENDAR, "garbage{")

		changes.cursor.shouldBeNull()
		changes.complete shouldBe true
		wireMock.verify(
			getRequestedFor(urlPathEqualTo(FEED))
				.withHeader("If-None-Match", absent())
				.withHeader("If-Modified-Since", absent()),
		)
	}

	@Test
	fun `unreadable events are left out, the readable ones are returned, and the download is not complete`() {
		serve(CALENDAR.replace("END:VCALENDAR", "BEGIN:VEVENT\r\nUID:broken@example.test\r\nDTSTART:soon\r\nEND:VEVENT\r\nEND:VCALENDAR"))

		val changes = adapter.fetchEvents(source("""{"url": "$url"}"""), IcsFeedAdapter.FEED_CALENDAR, null)

		changes.events.map { it.key.externalId } shouldContainExactly listOf("retro@example.test")
		// Not complete: the engine must not delete the local copy of the unreadable event.
		changes.complete shouldBe false
		changes.deleted.shouldBeEmpty()
	}

	@Test
	fun `a body that is not a complete iCalendar file is refused`() {
		val bodies =
			listOf(
				"",
				"<!DOCTYPE html><html><body>Please log in</body></html>",
				"BEGIN:VEVENT\r\nUID:loose@example.test\r\nDTSTART:20261105T090000Z\r\nEND:VEVENT\r\n",
				// Cut off: the parser would close the calendar itself.
				CALENDAR.substringBefore("END:VEVENT"),
				CALENDAR.substringBefore("END:VCALENDAR"),
				// A second calendar that was cut off.
				CALENDAR + "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\n",
			)
		for (body in bodies) {
			wireMock.resetAll()
			serve(body)

			shouldThrow<CalendarProviderException> {
				adapter.fetchEvents(source("""{"url": "$url"}"""), IcsFeedAdapter.FEED_CALENDAR, null)
			}.message!! shouldContain "not a complete iCalendar file"
		}
	}

	@Test
	fun `a complete calendar without events is a complete, empty listing`() {
		serve("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Fictitious Org//Test//EN\r\nEND:VCALENDAR\r\n")

		val changes = adapter.fetchEvents(source("""{"url": "$url"}"""), IcsFeedAdapter.FEED_CALENDAR, null)

		changes.complete shouldBe true
		changes.events.shouldBeEmpty()
	}

	@Test
	fun `the feed's host goes through the SSRF check`() {
		serve(CALENDAR)
		val strict =
			IcsFeedProperties(Duration.ofMinutes(15), DataSize.ofMegabytes(1), Duration.ofSeconds(2), Duration.ofSeconds(5), 2, false, emptyList())

		shouldThrow<CalendarProviderException> {
			IcsFeedAdapter(json, strict).fetchEvents(source("""{"url": "$url"}"""), IcsFeedAdapter.FEED_CALENDAR, null)
		}.message!! shouldContain "not a public"
		wireMock.verify(0, anyRequestedFor(anyUrl()))
	}

	@Test
	fun `creating, changing and deleting events is refused without a request`() {
		val source = source("""{"url": "$url"}""")

		shouldThrow<CalendarProviderException> { adapter.createEvent(source, IcsFeedAdapter.FEED_CALENDAR, event) }.message!! shouldContain
			"read-only"
		shouldThrow<CalendarProviderException> { adapter.updateEvent(source, IcsFeedAdapter.FEED_CALENDAR, event, "\"e1\"") }
		shouldThrow<CalendarProviderException> { adapter.deleteEvent(source, IcsFeedAdapter.FEED_CALENDAR, event.key, null) }
		wireMock.verify(0, anyRequestedFor(anyUrl()))
	}

	companion object {
		const val FEED = "/feeds/holidays.ics"

		val CALENDAR =
			"""
			BEGIN:VCALENDAR
			VERSION:2.0
			PRODID:-//Fictitious Org//Test//EN
			BEGIN:VEVENT
			UID:retro@example.test
			DTSTART:20261105T140000Z
			DURATION:PT1H
			SUMMARY:Retrospective
			END:VEVENT
			END:VCALENDAR
			""".trimIndent().replace("\n", "\r\n") + "\r\n"

		private val wireMock = WireMockServer(wireMockConfig().dynamicPort().gzipDisabled(true)).apply { start() }

		@JvmStatic
		@AfterAll
		fun stopWireMock() = wireMock.stop()
	}
}
