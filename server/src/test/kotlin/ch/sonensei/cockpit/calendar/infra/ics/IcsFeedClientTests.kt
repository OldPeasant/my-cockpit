package ch.sonensei.cockpit.calendar.infra.ics

import ch.sonensei.cockpit.calendar.sync.CalendarProviderException
import ch.sonensei.cockpit.mail.api.HostResolver
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.absent
import com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.anyUrl
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import com.github.tomakehurst.wiremock.http.Fault
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.util.unit.DataSize
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.UnknownHostException
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.GZIPOutputStream
import javax.net.ssl.SSLException

/**
 * The feed client against WireMock (gzip of WireMock itself off, so the tests control the encoding): conditional
 * requests, redirects, decoding, limits, URL checks and the SSRF check with an injected [HostResolver].
 */
class IcsFeedClientTests {
	private val base get() = "http://localhost:${wireMock.port()}"
	private val resolved = CopyOnWriteArrayList<String>()

	@BeforeEach
	fun reset() {
		wireMock.resetAll()
		resolved.clear()
	}

	private fun properties(
		maxSize: DataSize = DataSize.ofMegabytes(10),
		timeout: Duration = Duration.ofSeconds(10),
		maxRedirects: Int = 5,
		allowPrivateNetworks: Boolean = false,
		allowedPrivateHosts: List<String> = emptyList(),
	) = IcsFeedProperties(
		pollInterval = Duration.ofMinutes(15),
		maxSize = maxSize,
		connectTimeout = Duration.ofSeconds(2),
		timeout = timeout,
		maxRedirects = maxRedirects,
		allowPrivateNetworks = allowPrivateNetworks,
		allowedPrivateHosts = allowedPrivateHosts,
	)

	/** A resolver that records the hosts asked for and answers with [addresses] for every host. */
	private fun resolving(vararg addresses: InetAddress) =
		HostResolver { host ->
			resolved += host
			addresses.toList()
		}

	private fun address(vararg bytes: Int): InetAddress = InetAddress.getByAddress(bytes.map { it.toByte() }.toByteArray())

	private val publicAddress = address(93, 184, 215, 14)

	/** A client whose resolver says every host is public: the HTTP behavior against WireMock on localhost. */
	private fun client(properties: IcsFeedProperties = properties()) = IcsFeedClient(properties, resolving(publicAddress))

	private fun serve(
		path: String = FEED,
		status: Int = 200,
		headers: Map<String, String> = mapOf("Content-Type" to "text/calendar; charset=utf-8"),
		body: ByteArray = CALENDAR.toByteArray(),
	) {
		wireMock.stubFor(
			get(urlPathEqualTo(path)).willReturn(
				aResponse().withStatus(status).withBody(body).apply { headers.forEach { (name, value) -> withHeader(name, value) } },
			),
		)
	}

	private fun redirect(
		from: String,
		to: String,
		status: Int = 302,
	) = wireMock.stubFor(get(urlPathEqualTo(from)).willReturn(aResponse().withStatus(status).withHeader("Location", to)))

	private fun fetched(response: FeedResponse): FeedResponse.Fetched = response.shouldBeInstanceOf<FeedResponse.Fetched>()

	private fun gzip(bytes: ByteArray): ByteArray =
		ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(bytes) } }.toByteArray()

	private fun refused(block: () -> Unit): CalendarProviderException = shouldThrow<CalendarProviderException>(block)

	// --- Conditional requests ---

	@Test
	fun `a first download has no validators and returns the text with the new validators`() {
		serve(headers = mapOf("ETag" to "\"v1\"", "Last-Modified" to "Wed, 21 Oct 2026 08:00:00 GMT"))

		val response = fetched(client().fetch("$base$FEED", null))

		response.text shouldBe CALENDAR
		response.validators shouldBe FeedValidators("\"v1\"", "Wed, 21 Oct 2026 08:00:00 GMT")
		wireMock.verify(
			getRequestedFor(urlPathEqualTo(FEED))
				.withHeader("If-None-Match", absent())
				.withHeader("If-Modified-Since", absent())
				.withHeader("Accept", equalTo("text/calendar, */*;q=0.5"))
				.withHeader("Accept-Encoding", equalTo("gzip")),
		)
	}

	@Test
	fun `the validators are sent back and 304 is not modified`() {
		serve(status = 304, body = ByteArray(0))

		val response = client().fetch("$base$FEED", FeedValidators("\"v1\"", "Wed, 21 Oct 2026 08:00:00 GMT"))

		response shouldBe FeedResponse.NotModified
		wireMock.verify(
			getRequestedFor(urlPathEqualTo(FEED))
				.withHeader("If-None-Match", equalTo("\"v1\""))
				.withHeader("If-Modified-Since", equalTo("Wed, 21 Oct 2026 08:00:00 GMT")),
		)
	}

	@Test
	fun `only the validators that exist are sent, and blank response validators are none`() {
		serve(headers = mapOf("ETag" to "  ", "Last-Modified" to ""))

		val response = fetched(client().fetch("$base$FEED", FeedValidators(lastModified = "Tue, 20 Oct 2026 08:00:00 GMT")))

		response.validators shouldBe FeedValidators(null, null)
		wireMock.verify(
			getRequestedFor(urlPathEqualTo(FEED))
				.withHeader("If-None-Match", absent())
				.withHeader("If-Modified-Since", equalTo("Tue, 20 Oct 2026 08:00:00 GMT")),
		)
	}

	@Test
	fun `the query of the URL is kept`() {
		serve()

		client().fetch("$base$FEED?token=fictitious-token", null)

		wireMock.verify(getRequestedFor(urlEqualTo("$FEED?token=fictitious-token")))
	}

	// --- Status codes ---

	@Test
	fun `error statuses are refusals, not found or failures, and their messages hold no URL or body`() {
		val expected =
			mapOf(
				401 to "refused access (HTTP 401)",
				403 to "refused access (HTTP 403)",
				404 to "not found (HTTP 404)",
				410 to "not found (HTTP 410)",
				500 to "failed (HTTP 500)",
				418 to "failed (HTTP 418)",
			)
		for ((status, message) in expected) {
			wireMock.resetAll()
			serve(status = status, body = "secret-body-text".toByteArray())

			val e = refused { client().fetch("$base$FEED?token=fictitious-token", null) }

			e.message!! shouldContain message
			e.message!! shouldNotContain "fictitious-token"
			e.message!! shouldNotContain "localhost"
			e.message!! shouldNotContain "secret-body-text"
		}
	}

	@Test
	fun `any 2xx status is a download`() {
		serve(status = 203)

		fetched(client().fetch("$base$FEED", null)).text shouldBe CALENDAR
	}

	// --- Redirects ---

	@Test
	fun `relative and absolute redirects are followed with every status, and their hosts are checked`() {
		for (status in listOf(301, 302, 303, 307, 308)) {
			wireMock.resetAll()
			resolved.clear()
			redirect("/old", "/middle", status)
			redirect("/middle", "$base$FEED", status)
			serve()

			fetched(client().fetch("$base/old", null)).text shouldBe CALENDAR

			resolved shouldContainExactly listOf("localhost", "localhost", "localhost")
		}
	}

	@Test
	fun `redirects up to the limit are followed, one more is refused`() {
		redirect("/r1", "/r2")
		redirect("/r2", "/r3")
		redirect("/r3", FEED)
		serve()

		fetched(client(properties(maxRedirects = 3)).fetch("$base/r1", null)).text shouldBe CALENDAR

		val e = refused { client(properties(maxRedirects = 2)).fetch("$base/r1", null) }
		e.message!! shouldContain "redirected more than 2 times"
	}

	@Test
	fun `with no redirects allowed, a redirect is refused`() {
		redirect("/old", FEED)
		serve()

		refused { client(properties(maxRedirects = 0)).fetch("$base/old", null) }
		wireMock.verify(0, getRequestedFor(urlPathEqualTo(FEED)))
	}

	@Test
	fun `a redirect loop ends at the limit`() {
		redirect("/loop", "/loop")

		refused { client().fetch("$base/loop", null) }.message!! shouldContain "redirected more than 5 times"
		wireMock.verify(6, getRequestedFor(urlPathEqualTo("/loop")))
	}

	@Test
	fun `a redirect without Location, to an invalid URL, to another scheme or with credentials is refused`() {
		wireMock.stubFor(get(urlPathEqualTo("/nowhere")).willReturn(aResponse().withStatus(302)))
		redirect("/invalid", "http://exa mple.test/feed")
		redirect("/file", "file:///etc/passwd")
		redirect("/creds", "http://alice:pw@localhost:${wireMock.port()}$FEED")
		serve()

		refused { client().fetch("$base/nowhere", null) }.message!! shouldContain "redirect without a target"
		refused { client().fetch("$base/invalid", null) }.message!! shouldContain "redirected to an invalid URL"
		refused { client().fetch("$base/file", null) }.message!! shouldContain "http(s)"
		refused { client().fetch("$base/creds", null) }.message!! shouldContain "credentials"
		wireMock.verify(0, getRequestedFor(urlPathEqualTo(FEED)))
	}

	// --- Decoding and limits ---

	@Test
	fun `a gzip-encoded feed is decompressed`() {
		serve(headers = mapOf("Content-Type" to "text/calendar", "Content-Encoding" to "gzip"), body = gzip(CALENDAR.toByteArray()))

		fetched(client().fetch("$base$FEED", null)).text shouldBe CALENDAR
	}

	@Test
	fun `a broken gzip body is a read failure`() {
		serve(headers = mapOf("Content-Encoding" to "gzip"), body = "not gzip at all".toByteArray())

		refused { client().fetch("$base$FEED", null) }.message!! shouldContain "could not be read"
	}

	@Test
	fun `a feed of exactly the maximum size is read, a larger Content-Length is refused before reading`() {
		val body = CALENDAR.toByteArray()
		serve(headers = mapOf("Content-Type" to "text/calendar", "Content-Length" to body.size.toString()), body = body)

		fetched(client(properties(maxSize = DataSize.ofBytes(body.size.toLong()))).fetch("$base$FEED", null)).text shouldBe CALENDAR
		refused { client(properties(maxSize = DataSize.ofBytes(body.size - 1L))).fetch("$base$FEED", null) }
			.message!! shouldContain "larger than"
	}

	@Test
	fun `a body without Content-Length larger than the maximum is refused while reading`() {
		val body = CALENDAR.toByteArray()
		serve(body = body)

		refused { client(properties(maxSize = DataSize.ofBytes(body.size - 1L))).fetch("$base$FEED", null) }
			.message!! shouldContain "larger than"
	}

	@Test
	fun `the timeout covers the body, so a feed that trickles in slowly is given up`() {
		// IcsFeedProperties.timeout: "Time for a whole fetch: from sending the first request, through any redirects, to the end of the
		// last body."
		wireMock.stubFor(
			get(urlPathEqualTo(FEED)).willReturn(
				aResponse().withStatus(200).withBody(CALENDAR.toByteArray()).withChunkedDribbleDelay(10, 4_000),
			),
		)

		val started = System.nanoTime()
		refused { client(properties(timeout = Duration.ofMillis(500))).fetch("$base$FEED", null) }
			.message!! shouldContain "did not answer in time"
		Duration.ofNanos(System.nanoTime() - started) shouldBeLessThanDuration Duration.ofSeconds(3)
	}

	private infix fun Duration.shouldBeLessThanDuration(limit: Duration) = (this < limit) shouldBe true

	@Test
	fun `the timeout covers the whole fetch, so redirects that are each fast enough but together too slow are given up`() {
		// Each answer takes 500 ms, well within the 1.2 s timeout; the three of them together do not.
		wireMock.stubFor(get(urlPathEqualTo("/r1")).willReturn(aResponse().withStatus(302).withHeader("Location", "/r2").withFixedDelay(500)))
		wireMock.stubFor(get(urlPathEqualTo("/r2")).willReturn(aResponse().withStatus(302).withHeader("Location", FEED).withFixedDelay(500)))
		wireMock.stubFor(get(urlPathEqualTo(FEED)).willReturn(aResponse().withStatus(200).withBody(CALENDAR).withFixedDelay(500)))

		val started = System.nanoTime()
		refused { client(properties(timeout = Duration.ofMillis(1_200))).fetch("$base/r1", null) }
			.message!! shouldContain "did not answer in time"
		Duration.ofNanos(System.nanoTime() - started) shouldBeLessThanDuration Duration.ofSeconds(3)
	}

	@Test
	fun `the bodies of error and redirect answers are discarded, so a large one is not refused as too large`() {
		val large = ByteArray(64 * 1024) { 'x'.code.toByte() }
		val small = properties(maxSize = DataSize.ofKilobytes(4))
		serve(path = "/down", status = 503, body = large)
		wireMock.stubFor(
			get(urlPathEqualTo("/moved")).willReturn(aResponse().withStatus(301).withHeader("Location", FEED).withBody(large)),
		)
		serve()

		refused { client(small).fetch("$base/down", null) }.message!! shouldContain "failed (HTTP 503)"
		fetched(client(small).fetch("$base/moved", null)).text shouldBe CALENDAR
	}

	@Test
	fun `a gzip feed of exactly the maximum size after decompression is read, one byte more is refused`() {
		val text = "BEGIN:VCALENDAR\r\n" + "X-FILLER:fictitious filler line for the size limit\r\n".repeat(200) + "END:VCALENDAR\r\n"
		val bytes = text.toByteArray()
		val compressed = gzip(bytes)
		// Content-Encoding is case-insensitive.
		serve(headers = mapOf("Content-Type" to "text/calendar", "Content-Encoding" to "GZIP"), body = compressed)

		compressed.size shouldBeLessThan bytes.size / 4
		fetched(client(properties(maxSize = DataSize.ofBytes(bytes.size.toLong()))).fetch("$base$FEED", null)).text shouldBe text
		refused { client(properties(maxSize = DataSize.ofBytes(bytes.size - 1L))).fetch("$base$FEED", null) }
			.message!! shouldContain "larger than"
	}

	@Test
	fun `a body larger than the maximum is refused when it is sent in chunks`() {
		wireMock.stubFor(
			get(urlPathEqualTo(FEED)).willReturn(
				aResponse()
					.withStatus(200)
					.withBody(ByteArray(64 * 1024) { 'A'.code.toByte() })
					.withChunkedDribbleDelay(8, 40),
			),
		)

		refused { client(properties(maxSize = DataSize.ofKilobytes(16))).fetch("$base$FEED", null) }.message!! shouldContain "larger than"
	}

	@Test
	fun `the size limit applies to the decompressed feed`() {
		val bomb = gzip(ByteArray(1024 * 1024) { 'A'.code.toByte() })
		serve(headers = mapOf("Content-Encoding" to "gzip"), body = bomb)

		bomb.size shouldBeLessThan 64 * 1024
		refused { client(properties(maxSize = DataSize.ofKilobytes(64))).fetch("$base$FEED", null) }.message!! shouldContain "larger than"
	}

	@Test
	fun `the charset of the Content-Type is used, UTF-8 without one or when it is unknown`() {
		val text = "BEGIN:VCALENDAR\r\nX-WR-CALNAME:Zürich Fêtes\r\nEND:VCALENDAR\r\n"
		val cases =
			listOf(
				"text/calendar; charset=ISO-8859-1" to text.toByteArray(Charsets.ISO_8859_1),
				"text/calendar;CHARSET=\"iso-8859-1\"" to text.toByteArray(Charsets.ISO_8859_1),
				"text/calendar" to text.toByteArray(Charsets.UTF_8),
				"text/calendar; charset=x-no-such-charset" to text.toByteArray(Charsets.UTF_8),
				"text/calendar; charset=\"bad name!\"" to text.toByteArray(Charsets.UTF_8),
			)
		for ((contentType, body) in cases) {
			wireMock.resetAll()
			serve(headers = mapOf("Content-Type" to contentType), body = body)

			fetched(client().fetch("$base$FEED", null)).text shouldBe text
		}
	}

	@Test
	fun `a feed without Content-Type is read as UTF-8`() {
		val text = "BEGIN:VCALENDAR\r\nX-WR-CALNAME:Zürich\r\nEND:VCALENDAR\r\n"
		serve(headers = emptyMap(), body = text.toByteArray(Charsets.UTF_8))

		fetched(client().fetch("$base$FEED", null)).text shouldBe text
	}

	// --- Transport failures ---

	@Test
	fun `a server that answers too late is a timeout`() {
		wireMock.stubFor(get(urlPathEqualTo(FEED)).willReturn(aResponse().withStatus(200).withFixedDelay(2_000)))

		refused { client(properties(timeout = Duration.ofMillis(200))).fetch("$base$FEED", null) }
			.message!! shouldContain "did not answer in time"
	}

	@Test
	fun `an interrupted fetch fails and keeps the thread's interrupt flag`() {
		wireMock.stubFor(get(urlPathEqualTo(FEED)).willReturn(aResponse().withStatus(200).withFixedDelay(2_000)))

		Thread.currentThread().interrupt()
		try {
			refused { client().fetch("$base$FEED", null) }.message!! shouldContain "interrupted"
			Thread.currentThread().isInterrupted shouldBe true
		} finally {
			Thread.interrupted()
		}
	}

	@Test
	fun `a dropped connection and a closed port are unreachable servers`() {
		wireMock.stubFor(get(urlPathEqualTo(FEED)).willReturn(aResponse().withFault(Fault.EMPTY_RESPONSE)))
		refused { client().fetch("$base$FEED", null) }.message!! shouldContain "could not be reached"

		val closedPort = ServerSocket(0).use { it.localPort }
		refused { client().fetch("http://localhost:$closedPort$FEED", null) }.message!! shouldContain "could not be reached"
	}

	// --- URLs ---

	@Test
	fun `webcal and webcals URLs are fetched over https`() {
		// WireMock's port speaks plain HTTP, so the TLS handshake https needs fails: proof that https was used.
		for (scheme in listOf("webcal", "WEBCALS")) {
			val e = refused { client().fetch("$scheme://localhost:${wireMock.port()}$FEED", null) }

			generateSequence<Throwable>(e) { it.cause }.any { it is SSLException } shouldBe true
		}
		resolved shouldContainExactly listOf("localhost", "localhost")
		wireMock.verify(0, getRequestedFor(urlPathEqualTo(FEED)))
	}

	@Test
	fun `a webcal URL is checked like an https one`() {
		val client = IcsFeedClient(properties(), resolving(address(192, 168, 1, 20)))

		refused { client.fetch("webcal://nas.example.test/calendar.ics", null) }.message!! shouldContain "not a public"
		resolved shouldContainExactly listOf("nas.example.test")
	}

	@Test
	fun `URLs with credentials are refused before any lookup, also webcal ones, without the secret in the message`() {
		for (url in listOf("http://alice:pw-fictitious@localhost:${wireMock.port()}$FEED", "webcal://alice@feeds.example.test/f.ics")) {
			val e = refused { client().fetch(url, null) }

			e.message!! shouldContain "credentials"
			e.message!! shouldNotContain "pw-fictitious"
		}
		resolved.shouldBeEmpty()
		wireMock.verify(0, anyRequestedFor(anyUrl()))
	}

	@Test
	fun `invalid URLs, other schemes and URLs without host are refused`() {
		val urls =
			listOf(
				"http://exa mple.test/feed.ics",
				"ftp://feeds.example.test/feed.ics",
				"file:///etc/hosts",
				"feeds.example.test/feed.ics",
				"http:///feed.ics",
				"",
			)
		for (url in urls) {
			refused { client().fetch(url, null) }
		}
		resolved.shouldBeEmpty()
	}

	@Test
	fun `scheme and surrounding whitespace of the URL do not matter`() {
		serve()

		fetched(client().fetch("  HTTP://localhost:${wireMock.port()}$FEED  ", null)).text shouldBe CALENDAR
	}

	// --- SSRF ---

	@Test
	fun `hosts resolving to loopback, private, link-local, CGNAT or unspecified addresses are refused`() {
		val blocked =
			listOf(
				address(127, 0, 0, 1),
				address(10, 1, 2, 3),
				address(172, 16, 0, 1),
				address(192, 168, 1, 20),
				address(169, 254, 169, 254),
				address(100, 64, 0, 1),
				address(0, 0, 0, 0),
				InetAddress.getByName("::1"),
				InetAddress.getByName("fd00::1"),
				InetAddress.getByName("::ffff:10.0.0.1"),
			)
		for (address in blocked) {
			val e = refused { IcsFeedClient(properties(), resolving(address)).fetch("$base$FEED", null) }

			e.message!! shouldContain "not a public internet address"
		}
		wireMock.verify(0, anyRequestedFor(anyUrl()))
	}

	@Test
	fun `IPv6 link-local and IPv4-mapped metadata addresses are refused`() {
		for (address in listOf(InetAddress.getByName("fe80::1"), InetAddress.getByName("::ffff:169.254.169.254"))) {
			refused { IcsFeedClient(properties(), resolving(address)).fetch("$base$FEED", null) }
				.message!! shouldContain "not a public internet address"
		}
		wireMock.verify(0, anyRequestedFor(anyUrl()))
	}

	@Test
	fun `numeric encodings of the loopback address are refused by the default resolver`() {
		serve()
		val client = IcsFeedClient(properties())

		// 2130706433 and 127.1 are 127.0.0.1 written differently; hex is not an address Java reads, so it is no host.
		for (host in listOf("2130706433", "127.1", "0x7f000001")) {
			refused { client.fetch("http://$host:${wireMock.port()}$FEED", null) }
		}
		wireMock.verify(0, anyRequestedFor(anyUrl()))
	}

	@Test
	fun `a host with a public and a private address is refused`() {
		val client = IcsFeedClient(properties(), resolving(publicAddress, address(10, 0, 0, 7)))

		refused { client.fetch("$base$FEED", null) }.message!! shouldContain "not a public internet address"
		wireMock.verify(0, anyRequestedFor(anyUrl()))
	}

	@Test
	fun `a host without addresses or that cannot be found is refused`() {
		refused { IcsFeedClient(properties(), resolving()).fetch("$base$FEED", null) }.message!! shouldContain "not a public"

		val unknown = IcsFeedClient(properties()) { throw UnknownHostException("feeds.example.test") }
		refused { unknown.fetch("http://feeds.example.test/f.ics", null) }.message!! shouldContain "could not be found"
	}

	@Test
	fun `a redirect from a public host to a private one is refused before it is requested`() {
		redirect("/old", "http://intranet.example.test/feed.ics")
		val client =
			IcsFeedClient(properties()) { host ->
				resolved += host
				listOf(if (host == "localhost") publicAddress else address(10, 0, 0, 9))
			}

		refused { client.fetch("$base/old", null) }.message!! shouldContain "not a public internet address"
		resolved shouldContainExactly listOf("localhost", "intranet.example.test")
	}

	@Test
	fun `IPv6 literals are resolved without brackets`() {
		val client = IcsFeedClient(properties(), resolving(InetAddress.getByName("::1")))

		refused { client.fetch("http://[::1]:${wireMock.port()}$FEED", null) }
		resolved shouldContainExactly listOf("::1")
	}

	@Test
	fun `the default resolver refuses loopback literals and localhost`() {
		serve()
		val client = IcsFeedClient(properties())

		refused { client.fetch("http://127.0.0.1:${wireMock.port()}$FEED", null) }.message!! shouldContain "not a public"
		refused { client.fetch("$base$FEED", null) }.message!! shouldContain "not a public"
		wireMock.verify(0, anyRequestedFor(anyUrl()))
	}

	@Test
	fun `allowed private hosts skip the check by name, case-insensitively`() {
		serve()
		val client =
			IcsFeedClient(properties(allowedPrivateHosts = listOf(" LocalHost ")), resolving(address(127, 0, 0, 1)))

		fetched(client.fetch("$base$FEED", null)).text shouldBe CALENDAR
		fetched(client.fetch("http://LOCALHOST:${wireMock.port()}$FEED", null)).text shouldBe CALENDAR
		resolved.shouldBeEmpty()
	}

	@Test
	fun `an allowed private host name does not allow other names of the same address`() {
		val client = IcsFeedClient(properties(allowedPrivateHosts = listOf("nas.example.test")), resolving(address(192, 168, 1, 20)))

		refused { client.fetch("http://other-nas.example.test/f.ics", null) }.message!! shouldContain "not a public"
		refused { client.fetch("http://192.168.1.20/f.ics", null) }.message!! shouldContain "not a public"
		resolved shouldContainExactly listOf("other-nas.example.test", "192.168.1.20")
	}

	@Test
	fun `an allowed private host does not allow other private hosts it redirects to`() {
		redirect("/old", "http://127.0.0.1:${wireMock.port()}$FEED")
		serve()
		val client = IcsFeedClient(properties(allowedPrivateHosts = listOf("localhost")), resolving(address(127, 0, 0, 1)))

		refused { client.fetch("$base/old", null) }.message!! shouldContain "not a public"
		resolved shouldContainExactly listOf("127.0.0.1")
		wireMock.verify(0, getRequestedFor(urlPathEqualTo(FEED)))
	}

	@Test
	fun `allow-private-networks lifts the check for every host`() {
		serve()
		val client = IcsFeedClient(properties(allowPrivateNetworks = true), resolving(address(127, 0, 0, 1)))

		fetched(client.fetch("http://127.0.0.1:${wireMock.port()}$FEED", null)).text shouldBe CALENDAR
		fetched(client.fetch("$base$FEED", null)).text shouldBe CALENDAR
		resolved.shouldBeEmpty()
	}

	// --- Properties ---

	@Test
	fun `properties refuse an empty or too large size, negative redirects and a non-positive poll interval or timeout`() {
		shouldThrow<IllegalArgumentException> { properties(maxSize = DataSize.ofBytes(0)) }
		shouldThrow<IllegalArgumentException> { properties(maxSize = DataSize.ofBytes(Int.MAX_VALUE + 1L)) }
		shouldThrow<IllegalArgumentException> { properties(maxRedirects = -1) }
		shouldThrow<IllegalArgumentException> {
			IcsFeedProperties(Duration.ZERO, DataSize.ofMegabytes(1), Duration.ofSeconds(1), Duration.ofSeconds(1), 0, false, emptyList())
		}
		shouldThrow<IllegalArgumentException> {
			IcsFeedProperties(Duration.ofMinutes(-1), DataSize.ofMegabytes(1), Duration.ofSeconds(1), Duration.ofSeconds(1), 0, false, emptyList())
		}
		shouldThrow<IllegalArgumentException> { properties(timeout = Duration.ZERO) }
			.message shouldBe "cockpit.calendar.ics.timeout must be positive"
		shouldThrow<IllegalArgumentException> { properties(timeout = Duration.ofSeconds(-1)) }
			.message shouldBe "cockpit.calendar.ics.timeout must be positive"
		shouldThrow<IllegalArgumentException> {
			IcsFeedProperties(Duration.ofMinutes(15), DataSize.ofMegabytes(1), Duration.ZERO, Duration.ofSeconds(1), 0, false, emptyList())
		}.message shouldBe "cockpit.calendar.ics.connect-timeout must be positive"
		shouldThrow<IllegalArgumentException> {
			IcsFeedProperties(Duration.ofMinutes(15), DataSize.ofMegabytes(1), Duration.ofSeconds(-1), Duration.ofSeconds(1), 0, false, emptyList())
		}.message shouldBe "cockpit.calendar.ics.connect-timeout must be positive"
		properties(maxSize = DataSize.ofBytes(1), maxRedirects = 0).maxRedirects shouldBe 0
		properties(timeout = Duration.ofMillis(1)).timeout shouldBe Duration.ofMillis(1)
	}

	companion object {
		const val FEED = "/calendars/team.ics"

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
