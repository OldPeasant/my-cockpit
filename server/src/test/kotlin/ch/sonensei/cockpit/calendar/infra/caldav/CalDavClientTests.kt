package ch.sonensei.cockpit.calendar.infra.caldav

import ch.sonensei.cockpit.calendar.sync.CalendarProviderException
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.absent
import com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.anyUrl
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.request
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import com.github.tomakehurst.wiremock.http.Fault
import com.github.tomakehurst.wiremock.http.RequestMethod
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder
import com.github.tomakehurst.wiremock.stubbing.Scenario
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import java.io.EOFException
import java.net.Authenticator
import java.net.CookieHandler
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters

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

	// --- dropped connections

	@Test
	fun `reads and conditional writes are sent once more when the connection drops before an answer`() {
		listOf(
			Triple("GET", emptyMap<String, String>(), null),
			Triple("PROPFIND", mapOf("Depth" to "1"), "<x/>"),
			Triple("REPORT", mapOf("Depth" to "1"), "<x/>"),
			Triple("PUT", mapOf("If-Match" to "\"e1\""), "BEGIN:VCALENDAR"),
			Triple("PUT", mapOf("If-None-Match" to "*"), "BEGIN:VCALENDAR"),
			Triple("DELETE", mapOf("if-match" to "\"e1\""), null),
		).forEach { (method, headers, body) ->
			wireMock.resetAll()
			dropOnceThen(method, "/dav/x", Fault.EMPTY_RESPONSE, aResponse().withStatus(207).withBody("second"))

			val response = local.send(method, local.resolve("/dav/x")!!, body, headers = headers, what = "Working")

			response.status shouldBe 207
			response.body shouldBe "second"
			wireMock.findAll(davRequest(method, "/dav/x")) shouldHaveSize 2
		}
	}

	@Test
	fun `a connection reset before the answer is retried like a closed connection`() {
		dropOnceThen("GET", "/dav/x.ics", Fault.CONNECTION_RESET_BY_PEER, aResponse().withStatus(200).withBody("ok"))

		local.send("GET", local.resolve("/dav/x.ics")!!, what = "Reading").body shouldBe "ok"
		wireMock.findAll(davRequest("GET", "/dav/x.ics")) shouldHaveSize 2
	}

	@Test
	fun `a request is retried only once, then the failure is a provider failure`() {
		wireMock.stubFor(request("GET", urlPathEqualTo("/dav/x.ics")).willReturn(aResponse().withFault(Fault.EMPTY_RESPONSE)))

		shouldThrow<CalendarProviderException> {
			local.send("GET", local.resolve("/dav/x.ics")!!, what = "Reading")
		}.message shouldBe "Reading: the CalDAV server could not be reached"
		wireMock.findAll(davRequest("GET", "/dav/x.ics")) shouldHaveSize 2
	}

	@Test
	fun `writes without a precondition and other methods are not repeated`() {
		listOf("PUT", "DELETE", "MKCALENDAR", "POST").forEach { method ->
			wireMock.resetAll()
			dropOnceThen(method, "/dav/x", Fault.EMPTY_RESPONSE, aResponse().withStatus(201))

			shouldThrow<CalendarProviderException> {
				local.send(method, local.resolve("/dav/x")!!, "body", headers = mapOf("Depth" to "0"), what = "Writing")
			}.message shouldBe "Writing: the CalDAV server could not be reached"
			wireMock.findAll(davRequest(method, "/dav/x")) shouldHaveSize 1
		}
	}

	@Test
	fun `a connection that fails after the answer started is not repeated`() {
		dropOnceThen("GET", "/dav/x.ics", Fault.MALFORMED_RESPONSE_CHUNK, aResponse().withStatus(200).withBody("ok"))

		shouldThrow<CalendarProviderException> {
			local.send("GET", local.resolve("/dav/x.ics")!!, what = "Reading")
		}.message shouldBe "Reading: the CalDAV server could not be reached"
		wireMock.findAll(davRequest("GET", "/dav/x.ics")) shouldHaveSize 1
	}

	@Test
	fun `a timeout is not repeated`() {
		val impatient = CalDavClient(local.base, "alice", PASSWORD, CalDavClient.httpClient(), Duration.ofMillis(200))
		dropOnceThen("GET", "/dav/x.ics", null, aResponse().withStatus(200).withFixedDelay(2_000))

		shouldThrow<CalendarProviderException> {
			impatient.send("GET", impatient.resolve("/dav/x.ics")!!, what = "Reading")
		}.message shouldBe "Reading: the CalDAV server could not be reached"
		wireMock.findAll(davRequest("GET", "/dav/x.ics")) shouldHaveSize 1
	}

	@Test
	fun `the second attempt waits the backoff and its answer goes through the usual checks`() {
		dropOnceThen("GET", "/dav/x.ics", Fault.EMPTY_RESPONSE, aResponse().withStatus(503).withBody("<p>$PASSWORD</p>"))

		val started = System.nanoTime()
		shouldThrow<CalendarProviderException> {
			local.send("GET", local.resolve("/dav/x.ics")!!, what = "Reading")
		}.message shouldBe "Reading failed at the CalDAV server (HTTP 503)"

		Duration.ofNanos(System.nanoTime() - started) shouldBeGreaterThanOrEqualTo DROPPED_CONNECTION_BACKOFF
		wireMock.findAll(davRequest("GET", "/dav/x.ics")) shouldHaveSize 2
	}

	@Test
	fun `a drop at a redirect target is sent once more to that target with method, body and password`() {
		stub("REPORT", "/dav/old/", 307, headers = arrayOf("Location" to "/dav/new/"))
		dropOnceThen("REPORT", "/dav/new/", Fault.EMPTY_RESPONSE, aResponse().withStatus(207).withBody("<D:multistatus xmlns:D=\"DAV:\"/>"))

		local.report(local.resolve("/dav/old/")!!, 1, "<q/>", "Querying").status shouldBe 207

		wireMock.findAll(davRequest("REPORT", "/dav/old/")) shouldHaveSize 1
		val atTarget = wireMock.findAll(davRequest("REPORT", "/dav/new/"))
		atTarget shouldHaveSize 2
		atTarget.forEach {
			it.bodyAsString shouldBe "<q/>"
			it.getHeader("Authorization") shouldBe basic("alice:$PASSWORD")
			it.getHeader("Depth") shouldBe "1"
		}
	}

	@Test
	fun `an unconditional write that drops at a redirect target is not repeated`() {
		stub("PUT", "/dav/old.ics", 307, headers = arrayOf("Location" to "/dav/new.ics"))
		dropOnceThen("PUT", "/dav/new.ics", Fault.EMPTY_RESPONSE, aResponse().withStatus(201))

		shouldThrow<CalendarProviderException> {
			local.send("PUT", local.resolve("/dav/old.ics")!!, "BEGIN:VCALENDAR", CalDavClient.ICS, what = "Writing")
		}.message shouldBe "Writing: the CalDAV server could not be reached"
		wireMock.findAll(davRequest("PUT", "/dav/new.ics")) shouldHaveSize 1
	}

	@Test
	fun `an interrupt during the backoff is a provider failure, keeps the interrupt flag and sends nothing more`() {
		// The first send drops and the thread is interrupted at that moment, so the backoff sleep is interrupted.
		val scripted =
			ScriptedHttpClient { attempt ->
				if (attempt == 1) {
					Thread.currentThread().interrupt()
					throw EOFException("fictitious drop")
				}
			}
		val client = CalDavClient(local.base, "alice", PASSWORD, scripted)
		stub("GET", "/dav/x.ics", 200, "ok")

		try {
			shouldThrow<CalendarProviderException> {
				client.send("GET", client.resolve("/dav/x.ics")!!, what = "Reading")
			}.message shouldBe "Reading: interrupted"
			Thread.currentThread().isInterrupted shouldBe true
		} finally {
			Thread.interrupted()
		}
		scripted.sends shouldBe 1
		wireMock.findAll(anyRequestedFor(anyUrl())).shouldBeEmpty()
	}

	@Test
	@ExtendWith(OutputCaptureExtension::class)
	fun `a retry is logged with method and host only, never the password or the body`(output: CapturedOutput) {
		dropOnceThen("PUT", "/dav/x.ics", Fault.EMPTY_RESPONSE, aResponse().withStatus(204))

		local.send(
			"PUT",
			local.resolve("/dav/x.ics")!!,
			"BEGIN:VCALENDAR\nSUMMARY:Fictitious-Marker-Q9\nEND:VCALENDAR",
			CalDavClient.ICS,
			mapOf("If-Match" to "\"e1\""),
			"Writing",
		)

		val line = output.all.lines().single { "lost its connection before an answer" in it }
		line shouldContain "PUT"
		line shouldContain URI(wireMock.baseUrl()).host
		line shouldContain "INFO"
		// Only the client's own lines: WireMock's embedded server may log requests at DEBUG when logging is unconfigured.
		output.all.lines().filter { "CalDavClient" in it }.forEach {
			it shouldNotContain PASSWORD
			it shouldNotContain basic("alice:$PASSWORD").removePrefix("Basic ")
			it shouldNotContain "Fictitious-Marker-Q9"
		}
	}

	@Test
	fun `only reading methods and writes with a precondition are repeatable`() {
		listOf("GET", "head", "OPTIONS", "PROPFIND", "REPORT").forEach { CalDavClient.repeatable(it, emptyMap()) shouldBe true }
		CalDavClient.repeatable("PUT", mapOf("IF-MATCH" to "\"e\"")) shouldBe true
		CalDavClient.repeatable("delete", mapOf("If-None-Match" to "*")) shouldBe true
		CalDavClient.repeatable("PUT", mapOf("Content-Type" to CalDavClient.ICS)) shouldBe false
		CalDavClient.repeatable("DELETE", emptyMap()) shouldBe false
		CalDavClient.repeatable("MKCALENDAR", mapOf("If-Match" to "\"e\"")) shouldBe false
		CalDavClient.repeatable("POST", mapOf("If-Match" to "\"e\"")) shouldBe false
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

	/** The first [method] on [path] fails with [fault] (or answers late, without one), the next ones get [then]. */
	private fun dropOnceThen(
		method: String,
		path: String,
		fault: Fault?,
		then: ResponseDefinitionBuilder,
	) {
		val first = if (fault != null) aResponse().withFault(fault) else then
		wireMock.stubFor(
			request(method, urlPathEqualTo(path))
				.inScenario("drop")
				.whenScenarioStateIs(Scenario.STARTED)
				.willReturn(first)
				.willSetStateTo("dropped"),
		)
		wireMock.stubFor(request(method, urlPathEqualTo(path)).inScenario("drop").whenScenarioStateIs("dropped").willReturn(then))
	}

	/** A real [CalDavClient.httpClient] whose sends first run [beforeSend] (with the 1-based attempt), which may throw. */
	private class ScriptedHttpClient(
		private val beforeSend: (attempt: Int) -> Unit,
	) : HttpClient() {
		private val real = CalDavClient.httpClient()
		var sends = 0
			private set

		override fun cookieHandler(): Optional<CookieHandler> = real.cookieHandler()

		override fun connectTimeout(): Optional<Duration> = real.connectTimeout()

		override fun followRedirects(): HttpClient.Redirect = real.followRedirects()

		override fun proxy(): Optional<ProxySelector> = real.proxy()

		override fun sslContext(): SSLContext = real.sslContext()

		override fun sslParameters(): SSLParameters = real.sslParameters()

		override fun authenticator(): Optional<Authenticator> = real.authenticator()

		override fun version(): HttpClient.Version = real.version()

		override fun executor(): Optional<Executor> = real.executor()

		override fun <T> send(
			request: HttpRequest,
			responseBodyHandler: HttpResponse.BodyHandler<T>,
		): HttpResponse<T> {
			beforeSend(++sends)
			return real.send(request, responseBodyHandler)
		}

		override fun <T> sendAsync(
			request: HttpRequest,
			responseBodyHandler: HttpResponse.BodyHandler<T>,
		): CompletableFuture<HttpResponse<T>> = real.sendAsync(request, responseBodyHandler)

		override fun <T> sendAsync(
			request: HttpRequest,
			responseBodyHandler: HttpResponse.BodyHandler<T>,
			pushPromiseHandler: HttpResponse.PushPromiseHandler<T>?,
		): CompletableFuture<HttpResponse<T>> = real.sendAsync(request, responseBodyHandler, pushPromiseHandler)
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
