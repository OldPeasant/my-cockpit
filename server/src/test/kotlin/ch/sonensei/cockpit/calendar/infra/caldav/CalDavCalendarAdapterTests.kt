package ch.sonensei.cockpit.calendar.infra.caldav

import ch.sonensei.cockpit.calendar.api.Timing
import ch.sonensei.cockpit.calendar.model.CalendarSource
import ch.sonensei.cockpit.calendar.model.CalendarSourceType
import ch.sonensei.cockpit.calendar.model.OccurrenceId
import ch.sonensei.cockpit.calendar.sync.CalendarProviderException
import ch.sonensei.cockpit.calendar.sync.RemoteCalendar
import ch.sonensei.cockpit.calendar.sync.RemoteEvent
import ch.sonensei.cockpit.calendar.sync.RemoteEventChangedException
import ch.sonensei.cockpit.calendar.sync.RemoteEventKey
import ch.sonensei.cockpit.core.crypto.EncryptedSecret
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.MappingBuilder
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.absent
import com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.anyUrl
import com.github.tomakehurst.wiremock.client.WireMock.containing
import com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.notContaining
import com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.request
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import com.github.tomakehurst.wiremock.http.Fault
import com.github.tomakehurst.wiremock.http.RequestMethod
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.throwables.shouldThrowExactly
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Base64

/**
 * [CalDavCalendarAdapter] without a Spring context against WireMock playing a CalDAV server, for what the Radicale tests
 * cannot show: servers without sync-collection (ctag/etag), truncated (507) and refused sync reports, servers that name
 * resources themselves, conditional writes that fail, authentication and redirect failures, and invalid configurations.
 * All data is fictitious.
 */
class CalDavCalendarAdapterTests {
	private val json = JsonMapper.builder().build()
	private val adapter = CalDavCalendarAdapter(json, Clock.fixed(NOW, ZoneOffset.UTC))
	private val source = source("""{"url": "${wireMock.baseUrl()}/dav/", "username": "$USER"}""")

	@BeforeEach
	fun reset() {
		wireMock.resetAll()
	}

	// --- discovery

	@Test
	fun `discovery goes through the principal to the calendar home and keeps the calendars that hold events`() {
		dav("PROPFIND", "/dav/").answer(
			207,
			multistatus(
				found(
					"/dav/",
					"<D:current-user-principal><D:href>/dav/principals/alice/</D:href></D:current-user-principal>" + COLLECTION,
					missing = "<C:calendar-home-set/>",
				),
			),
		)
		dav("PROPFIND", "/dav/principals/alice/").answer(
			207,
			multistatus(found("/dav/principals/alice/", "<C:calendar-home-set><D:href>$HOME</D:href></C:calendar-home-set>")),
		)
		dav("PROPFIND", HOME).answer(
			207,
			multistatus(
				found(HOME, COLLECTION + "<D:displayname>Home</D:displayname>"),
				found(
					CAL,
					CALENDAR + "<D:displayname>Work</D:displayname><A:calendar-color>#3A7BD5FF</A:calendar-color>" +
						privileges("read", "write") + components("VEVENT", "VTODO"),
				),
				found("${HOME}tasks/", CALENDAR + "<D:displayname>Tasks</D:displayname>" + components("VTODO")),
				found("${HOME}holidays/", CALENDAR + "<D:displayname>Swiss holidays</D:displayname>" + privileges("read")),
				found("${HOME}team%20b/", CALENDAR + privileges("all")),
				found("https://elsewhere.example.test/dav/cal/", CALENDAR + "<D:displayname>Elsewhere</D:displayname>"),
			),
		)

		adapter.listCalendars(source) shouldContainExactly
			listOf(
				RemoteCalendar(CAL, "Work", "#3A7BD5", readOnly = false),
				RemoteCalendar("${HOME}holidays/", "Swiss holidays", null, readOnly = true),
				RemoteCalendar("${HOME}team%20b/", "team b", null, readOnly = false),
			)
		wireMock.verify(davRequest("PROPFIND", HOME).withHeader("Depth", equalTo("1")))
		wireMock.verify(davRequest("PROPFIND", "/dav/").withHeader("Depth", equalTo("0")))
		wireMock.findAll(anyRequestedFor(anyUrl())).forEach { it.getHeader("Authorization") shouldBe BASIC }
	}

	@Test
	fun `a bare host is looked up at the well-known URL, redirects within the server are followed`() {
		val bare = source("""{"url": "${wireMock.baseUrl()}", "username": "$USER"}""")
		dav("PROPFIND", "/").answer(404)
		dav("PROPFIND", "/.well-known/caldav").answer(301, headers = arrayOf("Location" to "/dav/"))
		dav("PROPFIND", "/dav/").answer(
			207,
			multistatus(found("/dav/", "<C:calendar-home-set><D:href>$HOME</D:href></C:calendar-home-set>" + COLLECTION)),
		)
		dav("PROPFIND", HOME).answer(207, multistatus(found(CAL, CALENDAR + "<D:displayname>Work</D:displayname>")))

		adapter.listCalendars(bare) shouldContainExactly listOf(RemoteCalendar(CAL, "Work"))
		wireMock.verify(davRequest("PROPFIND", "/dav/").withHeader("Authorization", equalTo(BASIC)))
	}

	@Test
	fun `a URL that names a calendar is that calendar`() {
		val direct = source("""{"url": "${wireMock.baseUrl()}$CAL", "username": "$USER"}""")
		dav("PROPFIND", CAL).answer(207, multistatus(found(CAL, CALENDAR + "<D:displayname>Work</D:displayname>")))

		adapter.listCalendars(direct) shouldContainExactly listOf(RemoteCalendar(CAL, "Work"))
		wireMock.verify(0, davRequest("PROPFIND", CAL).withHeader("Depth", equalTo("1")))
	}

	@Test
	fun `discovery fails clearly without a CalDAV service, without a calendar home and when listing fails`() {
		dav("PROPFIND", "/dav/").answer(404)
		shouldThrow<CalendarProviderException> { adapter.listCalendars(source) }.message shouldBe "No CalDAV service found at the source's URL"

		dav("PROPFIND", "/dav/").answer(207, multistatus(found("/dav/", COLLECTION)))
		shouldThrow<CalendarProviderException> { adapter.listCalendars(source) }.message shouldBe
			"The CalDAV server names no calendar home for the user"

		dav("PROPFIND", "/dav/").answer(207, multistatus(found("/dav/", "<C:calendar-home-set><D:href>$HOME</D:href></C:calendar-home-set>")))
		dav("PROPFIND", HOME).answer(403)
		shouldThrow<CalendarProviderException> { adapter.listCalendars(source) }.message shouldBe
			"Listing the calendars failed at the CalDAV server (HTTP 403)"
	}

	@Test
	fun `403 during discovery says access was denied, without the password or the body`() {
		dav("PROPFIND", "/dav/").answer(403, "<html>$PASSWORD forbidden details</html>")
		val failure = shouldThrow<CalendarProviderException> { adapter.listCalendars(source) }
		failure.message shouldBe "Finding the calendars: the CalDAV server denied access (HTTP 403), the user lacks permission"
		failure.message shouldNotContain PASSWORD

		// At the principal too.
		dav("PROPFIND", "/dav/").answer(
			207,
			multistatus(found("/dav/", "<D:current-user-principal><D:href>/dav/principals/alice/</D:href></D:current-user-principal>")),
		)
		dav("PROPFIND", "/dav/principals/alice/").answer(403)
		shouldThrow<CalendarProviderException> { adapter.listCalendars(source) }.message shouldBe
			"Finding the calendars: the CalDAV server denied access (HTTP 403), the user lacks permission"
	}

	@Test
	fun `a bare host that refuses its root is still looked up at the well-known URL, a 403 there too is denied access`() {
		val bare = source("""{"url": "${wireMock.baseUrl()}", "username": "$USER"}""")
		dav("PROPFIND", "/").answer(403)
		dav("PROPFIND", "/.well-known/caldav").answer(404)
		shouldThrow<CalendarProviderException> { adapter.listCalendars(bare) }.message shouldBe
			"Finding the calendars: the CalDAV server denied access (HTTP 403), the user lacks permission"

		dav("PROPFIND", "/.well-known/caldav").answer(
			207,
			multistatus(found("/.well-known/caldav", "<C:calendar-home-set><D:href>$HOME</D:href></C:calendar-home-set>" + COLLECTION)),
		)
		dav("PROPFIND", HOME).answer(207, multistatus(found(CAL, CALENDAR + "<D:displayname>Work</D:displayname>")))
		adapter.listCalendars(bare) shouldContainExactly listOf(RemoteCalendar(CAL, "Work"))
	}

	// --- client: authentication, redirects, failures

	@Test
	fun `a redirect to another host is refused, the password is not sent there`() {
		dav("PROPFIND", "/dav/").answer(301, headers = arrayOf("Location" to "http://elsewhere.example.test/dav/"))

		shouldThrow<CalendarProviderException> { adapter.listCalendars(source) }.message shouldBe
			"Finding the calendars: the CalDAV server pointed to another host"
	}

	@Test
	fun `redirects without a target and redirect loops fail`() {
		dav("PROPFIND", "/dav/").answer(302)
		shouldThrow<CalendarProviderException> { adapter.listCalendars(source) }.message shouldContain "redirected without a target"

		wireMock.resetRequests()
		dav("PROPFIND", "/dav/").answer(307, headers = arrayOf("Location" to "/dav/"))
		shouldThrow<CalendarProviderException> { adapter.listCalendars(source) }.message shouldContain "redirected too often"
		wireMock.verify(6, davRequest("PROPFIND", "/dav/"))
	}

	@Test
	fun `401 says the user name or password was rejected, without the password`() {
		dav("PROPFIND", "/dav/").answer(401, "<html>$PASSWORD</html>")

		val failure = shouldThrow<CalendarProviderException> { adapter.listCalendars(source) }

		failure.message shouldBe "Finding the calendars: the CalDAV server rejected the user name or password"
		failure.message shouldNotContain PASSWORD
	}

	@Test
	fun `server errors and network failures are provider failures`() {
		dav("PROPFIND", "/dav/").answer(503, "<p>secret maintenance details</p>")
		shouldThrow<CalendarProviderException> { adapter.listCalendars(source) }.message shouldBe
			"Finding the calendars failed at the CalDAV server (HTTP 503)"

		wireMock.stubFor(dav("PROPFIND", "/dav/").willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)))
		shouldThrow<CalendarProviderException> { adapter.listCalendars(source) }.message shouldBe
			"Finding the calendars: the CalDAV server could not be reached"
	}

	@Test
	fun `XML with a DOCTYPE (XXE) and XML that is no multistatus are refused`() {
		dav("PROPFIND", "/dav/").answer(
			207,
			"""<?xml version="1.0"?><!DOCTYPE m [<!ENTITY x SYSTEM "file:///etc/passwd">]>""" +
				"""<D:multistatus xmlns:D="DAV:"><D:response><D:href>&x;</D:href></D:response></D:multistatus>""",
		)
		shouldThrow<CalendarProviderException> { adapter.listCalendars(source) }.message shouldBe
			"Finding the calendars: the CalDAV server answered with invalid XML"

		dav("PROPFIND", "/dav/").answer(207, """<?xml version="1.0"?><D:error xmlns:D="DAV:"/>""")
		shouldThrow<CalendarProviderException> { adapter.listCalendars(source) }.message shouldBe
			"Finding the calendars: the CalDAV server answered without a multistatus"
	}

	@Test
	fun `a source without a valid http(s) URL is refused before any request`() {
		listOf(
			"not json",
			"{}",
			"""{"url": 42}""",
			"""{"url": "   "}""",
			"""{"url": "ftp://caldav.example.test/dav/"}""",
			"""{"url": "/dav/only-a-path/"}""",
			"""{"url": "https://caldav.example.test/with space/"}""",
			"""{"url": "https://alice:pw@caldav.example.test/dav/"}""",
		).forEach { config ->
			shouldThrow<CalendarProviderException> { adapter.listCalendars(source(config)) }.message shouldBe
				"The source has no valid CalDAV URL"
		}
		wireMock.findAll(anyRequestedFor(anyUrl())).shouldBeEmpty()
	}

	@Test
	fun `without a user name no Authorization header is sent`() {
		dav("PROPFIND", CAL).answer(207, multistatus(found(CAL, CALENDAR)))

		adapter.listCalendars(source("""{"url": "${wireMock.baseUrl()}$CAL"}"""))

		val requests = wireMock.findAll(anyRequestedFor(anyUrl()))
		requests.size shouldBe 2
		requests.forEach { it.containsHeader("Authorization") shouldBe false }
	}

	@Test
	fun `a calendar that is not on the source's server is refused, one without a trailing slash gets one`() {
		shouldThrow<CalendarProviderException> { adapter.fetchEvents(source, "https://elsewhere.example.test/dav/cal/", null) }.message shouldBe
			"The calendar is not on the source's CalDAV server"

		dav("PROPFIND", CAL).answer(207, multistatus(found(CAL, "<CS:getctag>ctag-1</CS:getctag>")))
		adapter.fetchEvents(source, CAL.trimEnd('/'), null).complete shouldBe true
		wireMock.verify(davRequest("PROPFIND", CAL).withHeader("Depth", equalTo("1")))
	}

	// --- pull: ctag/etag (no sync-collection)

	@Test
	fun `a server without sync tokens is listed by etags, unchanged ctags fetch nothing, changes are found by etag`() {
		// Full listing.
		dav("PROPFIND", CAL)
			.withHeader("Depth", equalTo("0"))
			.answer(207, multistatus(found(CAL, "<CS:getctag>ctag-1</CS:getctag>", missing = "<D:sync-token/>")))
		dav("PROPFIND", CAL)
			.withHeader("Depth", equalTo("1"))
			.answer(207, multistatus(found(CAL, CALENDAR), member("a", "\"a1\""), member("b", "\"b1\""), member("broken", "\"x1\"")))
		multiget(
			resource("a", "\"a1\"", "Dentist"),
			resource("b", "\"b1\"", "Lunch"),
			found("${CAL}broken.ics", etag("\"x1\"") + data("not iCalendar")),
		)

		val full = adapter.fetchEvents(source, CAL, null)

		full.complete shouldBe true
		full.events.map { it.title } shouldContainExactlyInAnyOrder listOf("Dentist", "Lunch")
		full.events.single { it.title == "Dentist" }.etag shouldBe "\"a1\""
		json.readTree(full.cursor).apply {
			get("mode").asString() shouldBe "etag"
			get("ctag").asString() shouldBe "ctag-1"
			has("token") shouldBe false
			get("resources").get("${CAL}a.ics").get("etag").asString() shouldBe "\"a1\""
			get("resources").get("${CAL}a.ics").get("uid").asString() shouldBe "a"
			get("resources").get("${CAL}broken.ics").has("uid") shouldBe false
		}
		wireMock.verify(0, davRequest("REPORT", CAL).withRequestBody(containing("sync-collection")))
		wireMock.verify(davRequest("REPORT", CAL).withRequestBody(containing("<D:href>${CAL}a.ics</D:href>")))

		// The same ctag: nothing is listed or read.
		wireMock.resetRequests()
		val unchanged = adapter.fetchEvents(source, CAL, full.cursor)

		unchanged.events.shouldBeEmpty()
		unchanged.deleted.shouldBeEmpty()
		unchanged.complete shouldBe false
		json.readTree(unchanged.cursor) shouldBe json.readTree(full.cursor)
		wireMock.verify(0, davRequest("PROPFIND", CAL).withHeader("Depth", equalTo("1")))
		wireMock.verify(0, davRequest("REPORT", CAL))

		// A new ctag: a changed, b removed, c new; only a and c are read.
		wireMock.resetAll()
		dav("PROPFIND", CAL)
			.withHeader("Depth", equalTo("0"))
			.answer(207, multistatus(found(CAL, "<CS:getctag>ctag-2</CS:getctag>", missing = "<D:sync-token/>")))
		dav("PROPFIND", CAL)
			.withHeader("Depth", equalTo("1"))
			.answer(207, multistatus(found(CAL, CALENDAR), member("a", "\"a2\""), member("broken", "\"x1\""), member("c", "\"c1\"")))
		multiget(resource("a", "\"a2\"", "Dentist (moved)"), resource("c", "\"c1\"", "Brand new"))

		val changed = adapter.fetchEvents(source, CAL, full.cursor)

		changed.complete shouldBe false
		changed.events.map { it.title } shouldContainExactlyInAnyOrder listOf("Dentist (moved)", "Brand new")
		changed.deleted shouldContainExactly listOf(RemoteEventKey("b"))
		json.readTree(changed.cursor).apply {
			get("ctag").asString() shouldBe "ctag-2"
			get("resources").properties().map { it.key }.toSet() shouldBe setOf("${CAL}a.ics", "${CAL}broken.ics", "${CAL}c.ics")
		}
		wireMock.verify(
			davRequest("REPORT", CAL)
				.withRequestBody(containing("${CAL}a.ics"))
				.withRequestBody(containing("${CAL}c.ics"))
				.withRequestBody(notContaining("${CAL}b.ics"))
				.withRequestBody(notContaining("${CAL}broken.ics")),
		)
	}

	// --- pull: sync-collection

	@Test
	fun `a first sync follows truncated (507) sync-collection answers and keeps the last token`() {
		dav("PROPFIND", CAL).answer(207, multistatus(found(CAL, "<D:sync-token>tok-0</D:sync-token><CS:getctag>c</CS:getctag>")))
		syncReport(
			"",
			207,
			multistatus(member("a", "\"a1\""), status(CAL, "507 Insufficient Storage"), syncToken = "tok-1"),
		)
		syncReport("tok-1", 207, multistatus(member("b", "\"b1\""), syncToken = "tok-2"))
		multiget(resource("a", "\"a1\"", "Dentist"), resource("b", "\"b1\"", "Lunch"))

		val changes = adapter.fetchEvents(source, CAL, null)

		changes.complete shouldBe true
		changes.events.map { it.title } shouldContainExactlyInAnyOrder listOf("Dentist", "Lunch")
		json.readTree(changes.cursor).apply {
			get("mode").asString() shouldBe "sync"
			get("token").asString() shouldBe "tok-2"
		}
		wireMock.verify(2, davRequest("REPORT", CAL).withRequestBody(containing("sync-collection")))
		wireMock.verify(davRequest("REPORT", CAL).withRequestBody(containing("<D:sync-token>tok-1</D:sync-token>")))
	}

	@Test
	fun `an incremental sync reads changed resources only and reports removed ones`() {
		val cursor =
			"""{"mode":"sync","token":"tok-2","resources":{""" +
				listOf("a" to "a1", "b" to "b1", "d" to "d1", "e" to "e1").joinToString(",") { (uid, etag) ->
					""""${CAL}$uid.ics":{"etag":"\"$etag\"","uid":"$uid"}"""
				} + "}}"
		syncReport(
			"tok-2",
			207,
			multistatus(
				member("a", "\"a1\""),
				status("${CAL}b.ics", "404 Not Found"),
				member("c", "\"c1\""),
				member("d", "\"d2\""),
				member("e", "\"e2\""),
				syncToken = "tok-3",
			),
		)
		// e is gone between the report and the reading.
		multiget(resource("c", "\"c1\"", "Brand new"), resource("d", "\"d2\"", "Dentist (moved)"))

		val changes = adapter.fetchEvents(source, CAL, cursor)

		changes.complete shouldBe false
		changes.events.map { it.title } shouldContainExactlyInAnyOrder listOf("Brand new", "Dentist (moved)")
		changes.deleted shouldContainExactlyInAnyOrder listOf(RemoteEventKey("b"), RemoteEventKey("e"))
		json.readTree(changes.cursor).apply {
			get("token").asString() shouldBe "tok-3"
			get("resources").properties().map { it.key }.toSet() shouldBe setOf("${CAL}a.ics", "${CAL}c.ics", "${CAL}d.ics")
			get("resources").get("${CAL}a.ics").get("etag").asString() shouldBe "\"a1\""
		}
		wireMock.verify(
			davRequest("REPORT", CAL)
				.withRequestBody(containing("calendar-multiget"))
				.withRequestBody(notContaining("${CAL}a.ics")),
		)
		wireMock.verify(0, davRequest("PROPFIND", CAL))
	}

	@Test
	fun `a sync token the server refuses starts a complete listing`() {
		syncReport(
			"stale",
			403,
			"""<?xml version="1.0"?><D:error xmlns:D="DAV:"><D:valid-sync-token/></D:error>""",
		)
		dav("PROPFIND", CAL).answer(207, multistatus(found(CAL, "<D:sync-token>tok-9</D:sync-token>")))
		syncReport("", 207, multistatus(member("a", "\"a1\""), syncToken = "tok-10"))
		multiget(resource("a", "\"a1\"", "Dentist"))

		val changes = adapter.fetchEvents(source, CAL, """{"mode":"sync","token":"stale","resources":{"${CAL}old.ics":{"uid":"old"}}}""")

		changes.complete shouldBe true
		changes.events.map { it.title } shouldContainExactly listOf("Dentist")
		changes.deleted.shouldBeEmpty()
		val cursor = json.readTree(changes.cursor)
		cursor.get("token").asString() shouldBe "tok-10"
		cursor.get("resources").properties().map { it.key } shouldContainExactly listOf("${CAL}a.ics")
	}

	@Test
	fun `a calendar with a sync token whose server refuses sync-collection is listed by etags`() {
		dav("PROPFIND", CAL)
			.withHeader("Depth", equalTo("0"))
			.answer(207, multistatus(found(CAL, "<D:sync-token>tok-1</D:sync-token><CS:getctag>ctag-1</CS:getctag>")))
		dav("REPORT", CAL).withRequestBody(containing("sync-collection")).answer(403)
		dav("PROPFIND", CAL).withHeader("Depth", equalTo("1")).answer(207, multistatus(member("a", "\"a1\"")))
		multiget(resource("a", "\"a1\"", "Dentist"))

		val changes = adapter.fetchEvents(source, CAL, null)

		changes.complete shouldBe true
		changes.events.map { it.title } shouldContainExactly listOf("Dentist")
		json.readTree(changes.cursor).get("mode").asString() shouldBe "etag"
	}

	@Test
	fun `a cursor that is not the adapter's starts a complete listing`() {
		dav("PROPFIND", CAL).withHeader("Depth", equalTo("0")).answer(207, multistatus(found(CAL, "<CS:getctag>ctag-1</CS:getctag>")))
		dav("PROPFIND", CAL).withHeader("Depth", equalTo("1")).answer(207, multistatus(found(CAL, CALENDAR)))

		listOf("sync:google-token", "{}", """{"mode":"sync"}""", """{"mode":"delta","token":"x"}""").forEach { cursor ->
			adapter.fetchEvents(source, CAL, cursor).complete shouldBe true
		}
	}

	@Test
	fun `a failing listing or multiget is a provider failure`() {
		dav("PROPFIND", CAL).withHeader("Depth", equalTo("0")).answer(207, multistatus(found(CAL, "<CS:getctag>ctag-1</CS:getctag>")))
		dav("PROPFIND", CAL).withHeader("Depth", equalTo("1")).answer(403)
		shouldThrow<CalendarProviderException> { adapter.fetchEvents(source, CAL, null) }.message shouldBe
			"Fetching the events failed at the CalDAV server (HTTP 403)"

		dav("PROPFIND", CAL).withHeader("Depth", equalTo("1")).answer(207, multistatus(member("a", "\"a1\"")))
		dav("REPORT", CAL).withRequestBody(containing("calendar-multiget")).answer(400)
		shouldThrow<CalendarProviderException> { adapter.fetchEvents(source, CAL, null) }.message shouldBe
			"Fetching the events failed at the CalDAV server (HTTP 400)"
	}

	// --- push

	@Test
	fun `createEvent PUTs a new resource named after the UID with If-None-Match and returns the server's etag`() {
		val uid = "fictitious event@1 ü"
		val path = "${CAL}fictitious%20event@1%20%C3%BC.ics"
		dav("PUT", path).answer(201, headers = arrayOf("ETag" to "\"n1\""))

		val stored = adapter.createEvent(source, CAL, planning(uid))

		stored shouldBe planning(uid).copy(etag = "\"n1\"")
		wireMock.verify(
			putRequestedFor(urlPathEqualTo(path))
				.withHeader("If-None-Match", equalTo("*"))
				.withHeader("If-Match", absent())
				.withHeader("Content-Type", equalTo("text/calendar; charset=utf-8"))
				.withHeader("Authorization", equalTo(BASIC))
				.withRequestBody(containing("UID:$uid\r\n"))
				.withRequestBody(containing("SUMMARY:Planning\\, Q4\r\n")),
		)
	}

	@Test
	fun `createEvent reads the event back when the server answers without an etag`() {
		dav("PUT", "${CAL}new-1.ics").answer(201)
		dav("GET", "${CAL}new-1.ics").answer(200, ics("new-1", "Planning (as stored)"), "ETag" to "\"n2\"")

		val stored = adapter.createEvent(source, CAL, planning("new-1"))

		stored.etag shouldBe "\"n2\""
		stored.title shouldBe "Planning (as stored)"
	}

	@Test
	fun `createEvent of a resource that already exists (a lost answer) returns what the server has`() {
		dav("PUT", "${CAL}new-1.ics").answer(412)
		dav("GET", "${CAL}new-1.ics").answer(200, ics("new-1", "Planning"), "ETag" to "\"n1\"")

		adapter.createEvent(source, CAL, planning("new-1")).etag shouldBe "\"n1\""
	}

	@Test
	fun `createEvent of an override PUTs the series with it and If-Match, or fails when the series is gone`() {
		dav("GET", "${CAL}standup.ics").answer(200, SERIES, "ETag" to "\"s1\"")
		dav("PUT", "${CAL}standup.ics").answer(204, headers = arrayOf("ETag" to "\"s2\""))
		val occurrence = OccurrenceId.of(Instant.parse("2026-11-09T08:00:00Z"))
		val override =
			planning("standup").copy(
				key = RemoteEventKey("standup", occurrence),
				timing = Timing.Timed(Instant.parse("2026-11-09T10:00:00Z"), Instant.parse("2026-11-09T10:15:00Z"), ZURICH),
			)

		adapter.createEvent(source, CAL, override) shouldBe override.copy(etag = "\"s2\"")
		wireMock.verify(
			putRequestedFor(urlPathEqualTo("${CAL}standup.ics"))
				.withHeader("If-Match", equalTo("\"s1\""))
				.withRequestBody(containing("RECURRENCE-ID;TZID=Europe/Zurich:20261109T090000"))
				.withRequestBody(containing("RRULE:FREQ=WEEKLY;BYDAY=MO")),
		)

		wireMock.resetAll()
		dav("GET", "${CAL}standup.ics").answer(404)
		dav("REPORT", CAL).withRequestBody(containing("calendar-query")).answer(207, multistatus())
		shouldThrowExactly<RemoteEventChangedException> { adapter.createEvent(source, CAL, override) }
	}

	@Test
	fun `412 on an update is RemoteEventChangedException, the caller's If-Match is sent`() {
		dav("GET", "${CAL}dentist.ics").answer(200, ics("dentist", "Dentist"), "ETag" to "\"d2\"")
		dav("PUT", "${CAL}dentist.ics").answer(412)

		shouldThrowExactly<RemoteEventChangedException> {
			adapter.updateEvent(source, CAL, planning("dentist"), ifMatch = "\"d1\"")
		}
		wireMock.verify(putRequestedFor(urlPathEqualTo("${CAL}dentist.ics")).withHeader("If-Match", equalTo("\"d1\"")))
	}

	@Test
	fun `an update finds a resource the server named itself by a calendar-query and writes it in place`() {
		dav("GET", "${CAL}dentist.ics").answer(404)
		dav("REPORT", CAL).withRequestBody(containing("calendar-query")).answer(
			207,
			multistatus(found("${CAL}server-named-0001.ics", etag("\"s1\"") + data(ics("dentist", "Dentist", "X-CUSTOM:kept")))),
		)
		dav("PUT", "${CAL}server-named-0001.ics").answer(204, headers = arrayOf("ETag" to "\"s2\""))

		val stored = adapter.updateEvent(source, CAL, planning("dentist"), ifMatch = null)

		stored.etag shouldBe "\"s2\""
		wireMock.verify(
			davRequest("REPORT", CAL)
				.withHeader("Depth", equalTo("1"))
				.withRequestBody(containing("<C:text-match collation=\"i;octet\">dentist</C:text-match>")),
		)
		wireMock.verify(
			putRequestedFor(urlPathEqualTo("${CAL}server-named-0001.ics"))
				.withHeader("If-Match", equalTo("\"s1\""))
				.withRequestBody(containing("X-CUSTOM:kept"))
				.withRequestBody(containing("SUMMARY:Planning\\, Q4")),
		)
	}

	@Test
	fun `an update of an event the server no longer has, or that is deleted while writing, is RemoteEventChangedException`() {
		dav("GET", "${CAL}dentist.ics").answer(404)
		dav("REPORT", CAL).withRequestBody(containing("calendar-query")).answer(207, multistatus())
		shouldThrowExactly<RemoteEventChangedException> { adapter.updateEvent(source, CAL, planning("dentist"), "\"d1\"") }

		dav("GET", "${CAL}dentist.ics").answer(200, ics("dentist", "Dentist"), "ETag" to "\"d1\"")
		dav("PUT", "${CAL}dentist.ics").answer(404)
		shouldThrowExactly<RemoteEventChangedException> { adapter.updateEvent(source, CAL, planning("dentist"), "\"d1\"") }
	}

	@Test
	fun `a refused update is a provider failure, not a conflict`() {
		dav("GET", "${CAL}dentist.ics").answer(200, ics("dentist", "Dentist"), "ETag" to "\"d1\"")
		dav("PUT", "${CAL}dentist.ics").answer(403)

		shouldThrowExactly<CalendarProviderException> { adapter.updateEvent(source, CAL, planning("dentist"), "\"d1\"") }.message shouldBe
			"Changing an event failed at the CalDAV server (HTTP 403)"
	}

	@Test
	fun `a redirect to another host while reading an event is refused`() {
		dav("GET", "${CAL}dentist.ics").answer(302, headers = arrayOf("Location" to "https://elsewhere.example.test/dentist.ics"))

		shouldThrowExactly<CalendarProviderException> { adapter.updateEvent(source, CAL, planning("dentist"), "\"d1\"") }.message shouldBe
			"Reading an event: the CalDAV server pointed to another host"
	}

	@Test
	fun `deleteEvent DELETEs the resource with If-Match, 412 is a conflict, a missing one is fine`() {
		dav("GET", "${CAL}dentist.ics").answer(200, ics("dentist", "Dentist"), "ETag" to "\"d1\"")
		dav("DELETE", "${CAL}dentist.ics").answer(204)

		adapter.deleteEvent(source, CAL, RemoteEventKey("dentist"), "\"d1\"").shouldBeNull()
		wireMock.verify(deleteRequestedFor(urlPathEqualTo("${CAL}dentist.ics")).withHeader("If-Match", equalTo("\"d1\"")))

		dav("DELETE", "${CAL}dentist.ics").answer(412)
		shouldThrowExactly<RemoteEventChangedException> { adapter.deleteEvent(source, CAL, RemoteEventKey("dentist"), "\"d0\"") }

		dav("DELETE", "${CAL}dentist.ics").answer(404)
		adapter.deleteEvent(source, CAL, RemoteEventKey("dentist"), "\"d1\"").shouldBeNull()

		wireMock.resetAll()
		dav("GET", "${CAL}dentist.ics").answer(404)
		dav("REPORT", CAL).withRequestBody(containing("calendar-query")).answer(207, multistatus())
		adapter.deleteEvent(source, CAL, RemoteEventKey("dentist"), "\"d1\"").shouldBeNull()
		wireMock.verify(0, deleteRequestedFor(anyUrl()))
	}

	@Test
	fun `removing an occurrence PUTs the series with an EXDATE and returns its new etag`() {
		dav("GET", "${CAL}standup.ics").answer(200, SERIES, "ETag" to "\"s1\"")
		dav("PUT", "${CAL}standup.ics").answer(204, headers = arrayOf("ETag" to "\"s2\""))

		val etag = adapter.deleteEvent(source, CAL, RemoteEventKey("standup", OccurrenceId.of(Instant.parse("2026-11-16T08:00:00Z"))), null)

		etag shouldBe "\"s2\""
		wireMock.verify(
			putRequestedFor(urlPathEqualTo("${CAL}standup.ics"))
				.withHeader("If-Match", equalTo("\"s1\""))
				.withRequestBody(containing("EXDATE;TZID=Europe/Zurich:20261116T090000")),
		)
		wireMock.verify(0, deleteRequestedFor(anyUrl()))

		dav("PUT", "${CAL}standup.ics").answer(412)
		shouldThrowExactly<RemoteEventChangedException> {
			adapter.deleteEvent(source, CAL, RemoteEventKey("standup", OccurrenceId.of(Instant.parse("2026-11-16T08:00:00Z"))), "\"s0\"")
		}
	}

	// --- helpers

	private fun source(config: String) =
		CalendarSource(CalendarSourceType.CALDAV, "Fictitious CalDAV").apply {
			this.config = config
			credentials = EncryptedSecret(PASSWORD)
		}

	private fun planning(uid: String) =
		RemoteEvent(
			key = RemoteEventKey(uid),
			etag = null,
			title = "Planning, Q4",
			timing = Timing.Timed(Instant.parse("2026-10-28T08:00:00Z"), Instant.parse("2026-10-28T09:00:00Z"), ZURICH),
			reminderMinutes = listOf(15),
		)

	private fun dav(
		method: String,
		path: String,
	): MappingBuilder = request(method, urlPathEqualTo(path))

	private fun davRequest(
		method: String,
		path: String,
	): RequestPatternBuilder = RequestPatternBuilder.newRequestPattern(RequestMethod.fromString(method), urlPathEqualTo(path))

	private fun MappingBuilder.answer(
		status: Int,
		body: String = "",
		vararg headers: Pair<String, String>,
	) {
		val response = aResponse().withStatus(status).withBody(body)
		if (body.startsWith("<")) response.withHeader("Content-Type", "application/xml; charset=utf-8")
		if (body.startsWith("BEGIN:")) response.withHeader("Content-Type", "text/calendar; charset=utf-8")
		headers.forEach { (name, value) -> response.withHeader(name, value) }
		wireMock.stubFor(willReturn(response))
	}

	private fun syncReport(
		token: String,
		status: Int,
		body: String,
	) = dav("REPORT", CAL)
		.withHeader("Depth", equalTo("0"))
		.withRequestBody(containing("<D:sync-token>$token</D:sync-token>"))
		.answer(status, body)

	private fun multiget(vararg responses: String) =
		dav("REPORT", CAL)
			.withHeader("Depth", equalTo("1"))
			.withRequestBody(containing("calendar-multiget"))
			.answer(207, multistatus(*responses))

	companion object {
		private const val USER = "alice"
		private const val PASSWORD = "fictitious-secret"
		private val BASIC = "Basic " + Base64.getEncoder().encodeToString("$USER:$PASSWORD".toByteArray())
		private const val HOME = "/dav/calendars/alice/"
		private const val CAL = "${HOME}work/"
		private val NOW: Instant = Instant.parse("2026-10-21T08:00:00Z")
		private val ZURICH: ZoneId = ZoneId.of("Europe/Zurich")
		private const val COLLECTION = "<D:resourcetype><D:collection/></D:resourcetype>"
		private const val CALENDAR = "<D:resourcetype><D:collection/><C:calendar/></D:resourcetype>"

		val wireMock: WireMockServer = WireMockServer(wireMockConfig().dynamicPort()).apply { start() }

		@JvmStatic
		@AfterAll
		fun stop() = wireMock.stop()

		private fun multistatus(
			vararg responses: String,
			syncToken: String? = null,
		) = """<?xml version="1.0" encoding="utf-8"?><D:multistatus xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav" """ +
			"""xmlns:CS="http://calendarserver.org/ns/" xmlns:A="http://apple.com/ns/ical/">""" +
			responses.joinToString("") +
			(syncToken?.let { "<D:sync-token>$it</D:sync-token>" } ?: "") +
			"</D:multistatus>"

		/** A `response` with the found [props] and the [missing] ones (404). */
		private fun found(
			href: String,
			props: String,
			missing: String = "",
		) = "<D:response><D:href>$href</D:href><D:propstat><D:prop>$props</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat>" +
			(if (missing.isEmpty()) "" else "<D:propstat><D:prop>$missing</D:prop><D:status>HTTP/1.1 404 Not Found</D:status></D:propstat>") +
			"</D:response>"

		private fun status(
			href: String,
			status: String,
		) = "<D:response><D:href>$href</D:href><D:status>HTTP/1.1 $status</D:status></D:response>"

		/** The resource `<uid>.ics` of the calendar in a listing or sync report. */
		private fun member(
			uid: String,
			etag: String,
		) = found("$CAL$uid.ics", etag(etag) + "<D:resourcetype/>")

		/** The resource `<uid>.ics` of the calendar in a multiget. */
		private fun resource(
			uid: String,
			etag: String,
			title: String,
		) = found("$CAL$uid.ics", etag(etag) + data(ics(uid, title)))

		private fun etag(etag: String) = "<D:getetag>$etag</D:getetag>"

		private fun data(ics: String) = "<C:calendar-data>${ics.replace("&", "&amp;").replace("<", "&lt;")}</C:calendar-data>"

		private fun privileges(vararg names: String) =
			"<D:current-user-privilege-set>" + names.joinToString("") { "<D:privilege><D:$it/></D:privilege>" } + "</D:current-user-privilege-set>"

		private fun components(vararg names: String) =
			"<C:supported-calendar-component-set>" + names.joinToString("") { "<C:comp name=\"$it\"/>" } + "</C:supported-calendar-component-set>"

		private fun ics(
			uid: String,
			title: String,
			vararg extra: String,
		) = (
			listOf(
				"BEGIN:VCALENDAR",
				"VERSION:2.0",
				"PRODID:-//Example//Fictitious Server//EN",
				"BEGIN:VEVENT",
				"UID:$uid",
				"DTSTAMP:20261001T000000Z",
				"SUMMARY:$title",
				"DTSTART;TZID=Europe/Zurich:20261103T090000",
				"DTEND;TZID=Europe/Zurich:20261103T100000",
			) + extra + listOf("END:VEVENT", "END:VCALENDAR")
		).joinToString("\r\n", postfix = "\r\n")

		private val SERIES =
			listOf(
				"BEGIN:VCALENDAR",
				"VERSION:2.0",
				"PRODID:-//Example//Fictitious Server//EN",
				"BEGIN:VEVENT",
				"UID:standup",
				"DTSTAMP:20261001T000000Z",
				"SUMMARY:Standup",
				"DTSTART;TZID=Europe/Zurich:20261102T090000",
				"DTEND;TZID=Europe/Zurich:20261102T091500",
				"RRULE:FREQ=WEEKLY;BYDAY=MO",
				"END:VEVENT",
				"END:VCALENDAR",
			).joinToString("\r\n", postfix = "\r\n")
	}
}
