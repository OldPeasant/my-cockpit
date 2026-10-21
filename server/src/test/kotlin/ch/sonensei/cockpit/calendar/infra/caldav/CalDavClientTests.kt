package ch.sonensei.cockpit.calendar.infra.caldav

import ch.sonensei.cockpit.calendar.sync.CalendarProviderException
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.absent
import com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.anyUrl
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.request
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import com.github.tomakehurst.wiremock.http.RequestMethod
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.URI
import java.util.Base64

/**
 * [CalDavClient] and [MultiStatus] on their own: the origin check of hrefs a CalDAV server sends (they are only followed,
 * and get the password, on the source's scheme, host and port, default ports included), what [CalDavClient.send] hands
 * to the caller and what it refuses, and how a WebDAV multistatus is read. Hosts, users and passwords are fictitious.
 */
class CalDavClientTests {
	private val https = CalDavClient(URI("https://caldav.example.test/dav/"), "alice", "fictitious-secret", CalDavClient.httpClient())
	private val http = CalDavClient(URI("http://caldav.example.test/dav/"), "alice", "fictitious-secret", CalDavClient.httpClient())
	private val local = CalDavClient(URI("${wireMock.baseUrl()}/dav/"), "alice", PASSWORD, CalDavClient.httpClient())

	@BeforeEach
	fun reset() {
		wireMock.resetAll()
	}

	// --- resolve

	@Test
	fun `paths and full URIs on the source's origin resolve, default ports and host case included`() {
		https.resolve("/dav/calendars/alice/work/") shouldBe URI("https://caldav.example.test/dav/calendars/alice/work/")
		https.resolve("calendars/alice/") shouldBe URI("https://caldav.example.test/dav/calendars/alice/")
		https.resolve("https://caldav.example.test:443/dav/x/") shouldBe URI("https://caldav.example.test:443/dav/x/")
		https.resolve("HTTPS://CALDAV.Example.TEST/dav/x/")?.path shouldBe "/dav/x/"
		http.resolve("http://caldav.example.test:80/dav/x/") shouldBe URI("http://caldav.example.test:80/dav/x/")
		// Spaces some servers send unencoded.
		https.resolve(" /dav/team b/ ")?.path shouldBe "/dav/team b/"
	}

	@Test
	fun `hrefs on another scheme, host or port, and malformed ones, resolve to nothing`() {
		listOf(
			"http://caldav.example.test/dav/x/",
			"https://caldav.example.test:8443/dav/x/",
			"https://caldav.example.test:80/dav/x/",
			"https://elsewhere.example.test/dav/x/",
			"https://caldav.example.test.elsewhere.example.test/dav/x/",
			"//elsewhere.example.test/dav/x/",
			"mailto:alice@example.test",
			"http://[broken",
		).forEach { href -> https.resolve(href).shouldBeNull() }
		http.resolve("http://caldav.example.test:443/dav/x/").shouldBeNull()
		http.resolve("https://caldav.example.test/dav/x/").shouldBeNull()
	}

	// --- send

	@Test
	fun `a request to another host is refused before anything is sent`() {
		shouldThrow<CalendarProviderException> {
			local.send("GET", URI("http://elsewhere.example.test/dav/x.ics"), what = "Reading the event")
		}.message shouldBe "Reading the event: the CalDAV server pointed to another host"
		wireMock.findAll(anyRequestedFor(anyUrl())).shouldBeEmpty()
	}

	@Test
	fun `answers other than 401 and 5xx go to the caller with status, body and headers`() {
		listOf(403, 404, 409, 410, 412).forEach { status ->
			wireMock.resetAll()
			stub("PUT", "/dav/x.ics", status, "answer $status", "ETag" to "\"e-$status\"")

			val response = local.send("PUT", local.resolve("/dav/x.ics")!!, "BEGIN:VCALENDAR", CalDavClient.ICS, what = "Writing")

			response.status shouldBe status
			response.ok shouldBe false
			response.body shouldBe "answer $status"
			response.header("etag") shouldBe "\"e-$status\""
			response.header("X-Not-There").shouldBeNull()
		}
		stub("PUT", "/dav/x.ics", 201, "")
		local.send("PUT", local.resolve("/dav/x.ics")!!, "BEGIN:VCALENDAR", what = "Writing").ok shouldBe true
	}

	@Test
	fun `a body is sent with its content type and the caller's headers, a request without body has no content type`() {
		stub("PUT", "/dav/x.ics", 204)
		stub("DELETE", "/dav/x.ics", 204)

		local.send("PUT", local.resolve("/dav/x.ics")!!, "BEGIN:VCALENDAR", CalDavClient.ICS, mapOf("If-Match" to "\"e1\""), "Writing")
		local.send("DELETE", local.resolve("/dav/x.ics")!!, headers = mapOf("If-Match" to "\"e2\""), what = "Deleting")

		wireMock.verify(
			davRequest("PUT", "/dav/x.ics")
				.withHeader("Content-Type", equalTo(CalDavClient.ICS))
				.withHeader("If-Match", equalTo("\"e1\""))
				.withHeader("Authorization", equalTo(basic("alice:$PASSWORD")))
				.withRequestBody(equalTo("BEGIN:VCALENDAR")),
		)
		wireMock.verify(davRequest("DELETE", "/dav/x.ics").withHeader("Content-Type", absent()).withHeader("If-Match", equalTo("\"e2\"")))
	}

	@Test
	fun `PROPFIND and REPORT send their Depth and an XML body in the WebDAV namespaces`() {
		stub("PROPFIND", "/dav/", 207, "<x/>")
		stub("REPORT", "/dav/", 207, "<x/>")

		local.propfind(local.base, 1, "<D:getetag/>", "Listing")
		local.report(local.base, 0, "<C:calendar-query xmlns:C=\"urn:ietf:params:xml:ns:caldav\"/>", "Querying")

		val propfind = wireMock.findAll(davRequest("PROPFIND", "/dav/")).single()
		propfind.getHeader("Depth") shouldBe "1"
		propfind.getHeader("Content-Type") shouldBe CalDavClient.XML
		propfind.bodyAsString shouldContain """<D:propfind xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav""""
		propfind.bodyAsString shouldContain "<D:prop><D:getetag/></D:prop>"
		wireMock.verify(davRequest("REPORT", "/dav/").withHeader("Depth", equalTo("0")))
	}

	@Test
	fun `a user name without a password sends an empty password`() {
		val noPassword = CalDavClient(local.base, "alice", null, CalDavClient.httpClient())
		stub("GET", "/dav/x.ics", 200, "BEGIN:VCALENDAR")

		noPassword.send("GET", noPassword.resolve("x.ics")!!, what = "Reading")

		wireMock.verify(davRequest("GET", "/dav/x.ics").withHeader("Authorization", equalTo(basic("alice:"))))
	}

	@Test
	fun `a redirect within the server keeps the method, body and password, for permanent and temporary redirects`() {
		listOf(301, 302, 307, 308).forEach { status ->
			wireMock.resetAll()
			stub("REPORT", "/dav/old/", status, headers = arrayOf("Location" to "${wireMock.baseUrl()}/dav/new/"))
			stub("REPORT", "/dav/new/", 207, "<D:multistatus xmlns:D=\"DAV:\"/>")

			local.report(local.resolve("/dav/old/")!!, 1, "<q/>", "Querying").status shouldBe 207

			wireMock.verify(
				davRequest("REPORT", "/dav/new/")
					.withHeader("Authorization", equalTo(basic("alice:$PASSWORD")))
					.withRequestBody(equalTo("<q/>")),
			)
		}
	}

	@Test
	fun `a redirect whose Location is no URI fails as a redirect without a target`() {
		stub("GET", "/dav/x.ics", 302, headers = arrayOf("Location" to "http://[broken"))

		shouldThrow<CalendarProviderException> {
			local.send("GET", local.resolve("/dav/x.ics")!!, what = "Reading")
		}.message shouldBe "Reading: the CalDAV server redirected without a target"
	}

	@Test
	fun `a redirect chain of five hops is followed, the sixth fails`() {
		(0 until 6).forEach { stub("GET", "/dav/hop$it", 307, headers = arrayOf("Location" to "/dav/hop${it + 1}")) }
		stub("GET", "/dav/hop6", 200, "end")

		shouldThrow<CalendarProviderException> {
			local.send("GET", local.resolve("/dav/hop0")!!, what = "Reading")
		}.message shouldBe "Reading: the CalDAV server redirected too often"
		local.send("GET", local.resolve("/dav/hop1")!!, what = "Reading").body shouldBe "end"
	}

	@Test
	fun `401 and 5xx messages hold neither the password nor the answer`() {
		stub("GET", "/dav/a.ics", 401, "<p>$PASSWORD</p>")
		stub("GET", "/dav/b.ics", 500, "<p>$PASSWORD</p>")

		listOf("a", "b").forEach { name ->
			val message = shouldThrow<CalendarProviderException> { local.send("GET", local.resolve("/dav/$name.ics")!!, what = "Reading") }.message!!
			message shouldNotContain PASSWORD
			message shouldNotContain "<p>"
		}
	}

	@Test
	fun `an interrupted request is a provider failure and keeps the thread's interrupt flag`() {
		wireMock.stubFor(request("GET", urlPathEqualTo("/dav/slow.ics")).willReturn(aResponse().withStatus(200).withFixedDelay(5_000)))

		Thread.currentThread().interrupt()
		try {
			shouldThrow<CalendarProviderException> {
				local.send("GET", local.resolve("/dav/slow.ics")!!, what = "Reading")
			}.message shouldBe "Reading: interrupted"
			Thread.currentThread().isInterrupted shouldBe true
		} finally {
			Thread.interrupted()
		}
	}

	// --- MultiStatus

	@Test
	fun `a multistatus keeps only properties of 2xx propstats or of propstats without a status code`() {
		val status =
			MultiStatus.parse(
				multistatus(
					"""
					<D:response>
						<D:href> /dav/cal/ </D:href>
						<D:propstat><D:prop><D:displayname> Work </D:displayname></D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat>
						<D:propstat><D:prop><D:getetag>"hidden"</D:getetag></D:prop><D:status>HTTP/1.1 404 Not Found</D:status></D:propstat>
						<D:propstat><D:prop><D:owner>no status</D:owner></D:prop></D:propstat>
						<D:propstat><D:prop><D:getctag>unreadable</D:getctag></D:prop><D:status>broken</D:status></D:propstat>
						<D:propstat><D:prop><D:sync-token>   </D:sync-token><X:getetag xmlns:X="urn:example:other">other</X:getetag></D:prop></D:propstat>
					</D:response>
					""",
				),
				"Listing",
			)

		val response = status.responses.single()
		response.href shouldBe "/dav/cal/"
		response.status.shouldBeNull()
		response.text(CalDavClient.DAV, "displayname") shouldBe "Work"
		response.text(CalDavClient.DAV, "owner") shouldBe "no status"
		// A 404 propstat names a missing property; a status line without a code counts as no status.
		response.prop(CalDavClient.DAV, "getetag").shouldBeNull()
		response.text(CalDavClient.DAV, "getctag") shouldBe "unreadable"
		// Blank text is no value; a property of another namespace is not the DAV one.
		response.prop(CalDavClient.DAV, "sync-token")?.localName shouldBe "sync-token"
		response.text(CalDavClient.DAV, "sync-token").shouldBeNull()
		response.text("urn:example:other", "getetag") shouldBe "other"
		status.syncToken.shouldBeNull()
	}

	@Test
	fun `a multistatus reads response statuses and the sync token, and skips responses without href`() {
		val status =
			MultiStatus.parse(
				multistatus(
					"<D:response><D:href>/dav/cal/gone.ics</D:href><D:status>HTTP/1.1 404 Not Found</D:status></D:response>",
					"<D:response><D:status>HTTP/1.1 200 OK</D:status></D:response>",
					"<D:response><D:href>/dav/cal/odd.ics</D:href><D:status>HTTP/1.1 abc</D:status></D:response>",
					"<D:sync-token> http://example.test/sync/42 </D:sync-token>",
				),
				"Syncing",
			)

		status.responses.map { it.href } shouldContainExactly listOf("/dav/cal/gone.ics", "/dav/cal/odd.ics")
		status.responses.map { it.status } shouldContainExactly listOf(404, null)
		status.syncToken shouldBe "http://example.test/sync/42"
	}

	@Test
	fun `empty answers, HTML, entity declarations and other XML are no multistatus`() {
		listOf("", "<html><body>Login</body></html", "not xml").forEach { body ->
			shouldThrow<CalendarProviderException> { MultiStatus.parse(body, "Listing") }.message shouldBe
				"Listing: the CalDAV server answered with invalid XML"
		}
		// An internal entity expansion (billion laughs) is refused like an external one: no DOCTYPE at all.
		val laughs =
			"""<?xml version="1.0"?><!DOCTYPE m [<!ENTITY a "lol"><!ENTITY b "&a;&a;&a;&a;&a;&a;&a;&a;">]>""" +
				"""<D:multistatus xmlns:D="DAV:"><D:response><D:href>&b;</D:href></D:response></D:multistatus>"""
		shouldThrow<CalendarProviderException> { MultiStatus.parse(laughs, "Listing") }.message shouldBe
			"Listing: the CalDAV server answered with invalid XML"
		listOf("<multistatus/>", """<D:error xmlns:D="DAV:"/>""").forEach { body ->
			shouldThrow<CalendarProviderException> { MultiStatus.parse(body, "Listing") }.message shouldBe
				"Listing: the CalDAV server answered without a multistatus"
		}
	}

	// --- helpers

	private fun stub(
		method: String,
		path: String,
		status: Int,
		body: String = "",
		vararg headers: Pair<String, String>,
	) {
		val response = aResponse().withStatus(status).withBody(body)
		headers.forEach { (name, value) -> response.withHeader(name, value) }
		wireMock.stubFor(request(method, urlPathEqualTo(path)).willReturn(response))
	}

	private fun davRequest(
		method: String,
		path: String,
	): RequestPatternBuilder = RequestPatternBuilder.newRequestPattern(RequestMethod.fromString(method), urlPathEqualTo(path))

	private fun basic(credentials: String) = "Basic " + Base64.getEncoder().encodeToString(credentials.toByteArray())

	private fun multistatus(vararg parts: String) =
		"""<?xml version="1.0" encoding="utf-8"?><D:multistatus xmlns:D="DAV:">${parts.joinToString("")}</D:multistatus>"""

	companion object {
		private const val PASSWORD = "fictitious-secret"

		val wireMock: WireMockServer = WireMockServer(wireMockConfig().dynamicPort()).apply { start() }

		@JvmStatic
		@AfterAll
		fun stop() = wireMock.stop()
	}
}
