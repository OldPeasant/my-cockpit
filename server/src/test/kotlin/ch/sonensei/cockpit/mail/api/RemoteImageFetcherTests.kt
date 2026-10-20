package ch.sonensei.cockpit.mail.api

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.absent
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.Options
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.util.unit.DataSize
import java.net.InetAddress
import java.time.Duration

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RemoteImageFetcherTests {
	private val server = WireMockServer(wireMockConfig().dynamicPort().useChunkedTransferEncoding(Options.ChunkedEncodingPolicy.NEVER))
	private val chunked = WireMockServer(wireMockConfig().dynamicPort().useChunkedTransferEncoding(Options.ChunkedEncodingPolicy.ALWAYS))

	@BeforeAll
	fun start() {
		server.start()
		chunked.start()
	}

	@AfterAll
	fun stop() {
		server.stop()
		chunked.stop()
	}

	@BeforeEach
	fun reset() {
		server.resetAll()
		chunked.resetAll()
	}

	private fun fetcher(
		allowPrivate: Boolean = true,
		maxBytes: Long = MAX_BYTES,
		timeout: Duration = Duration.ofSeconds(2),
	) = RemoteImageFetcher(
		MailReadProperties(
			DataSize.ofMegabytes(1),
			MailReadProperties.ImageProxy(DataSize.ofBytes(maxBytes), Duration.ofSeconds(1), timeout, 2, allowPrivate),
		),
	)

	private fun image(
		path: String,
		body: ByteArray = PNG,
		type: String = "image/png",
		target: WireMockServer = server,
	) = target.stubFor(get(urlEqualTo(path)).willReturn(aResponse().withHeader("Content-Type", type).withBody(body)))

	private fun url(
		path: String,
		target: WireMockServer = server,
	) = "http://127.0.0.1:${target.port()}$path"

	private fun failure(block: () -> Unit) = shouldThrow<RemoteImageException>(block).error

	@Test
	fun `an image is fetched with its type, without cookies or referrer`() {
		image("/a.png?x=1")

		val fetched = fetcher().fetch(url("/a.png?x=1"))

		fetched.contentType shouldBe "image/png"
		fetched.bytes.toList() shouldBe PNG.toList()
		server.verify(
			getRequestedFor(urlEqualTo("/a.png?x=1"))
				.withHeader("Cookie", absent())
				.withHeader("Referer", absent())
				.withHeader(
					"Accept",
					com.github.tomakehurst.wiremock.client.WireMock
						.equalTo("image/*"),
				),
		)
	}

	@Test
	fun `chunked responses are decoded`() {
		image("/c.gif", body = ByteArray(300) { it.toByte() }, type = "image/gif; charset=binary", target = chunked)

		val fetched = fetcher().fetch(url("/c.gif", chunked))

		fetched.contentType shouldBe "image/gif"
		fetched.bytes.toList() shouldBe ByteArray(300) { it.toByte() }.toList()
	}

	@Test
	fun `images above the size limit are refused, with and without Content-Length`() {
		image("/big.png", body = ByteArray(MAX_BYTES.toInt() + 1))
		image("/big.png", body = ByteArray(MAX_BYTES.toInt() + 1), target = chunked)

		failure { fetcher().fetch(url("/big.png")) } shouldBe RemoteImageError.TOO_LARGE
		failure { fetcher().fetch(url("/big.png", chunked)) } shouldBe RemoteImageError.TOO_LARGE
	}

	@Test
	fun `SVG, HTML and missing types are not proxied`() {
		image("/x.svg", body = "<svg onload=alert(1)/>".toByteArray(), type = "image/svg+xml")
		image("/x.html", body = "<script>alert(1)</script>".toByteArray(), type = "text/html")
		server.stubFor(get(urlEqualTo("/none")).willReturn(aResponse().withBody(PNG)))

		failure { fetcher().fetch(url("/x.svg")) } shouldBe RemoteImageError.NOT_AN_IMAGE
		failure { fetcher().fetch(url("/x.html")) } shouldBe RemoteImageError.NOT_AN_IMAGE
		failure { fetcher().fetch(url("/none")) } shouldBe RemoteImageError.NOT_AN_IMAGE
	}

	@Test
	fun `error statuses fail`() {
		server.stubFor(get(urlEqualTo("/missing.png")).willReturn(aResponse().withStatus(404)))

		failure { fetcher().fetch(url("/missing.png")) } shouldBe RemoteImageError.UPSTREAM_FAILED
	}

	@Test
	fun `redirects are followed up to the limit`() {
		image("/final.png")
		server.stubFor(get(urlEqualTo("/r1")).willReturn(aResponse().withStatus(302).withHeader("Location", "/final.png")))
		server.stubFor(get(urlEqualTo("/loop")).willReturn(aResponse().withStatus(301).withHeader("Location", "/loop")))

		fetcher().fetch(url("/r1")).bytes.toList() shouldBe PNG.toList()
		failure { fetcher().fetch(url("/loop")) } shouldBe RemoteImageError.UPSTREAM_FAILED
	}

	@Test
	fun `a slow server times out`() {
		server.stubFor(
			get(urlEqualTo("/slow.png")).willReturn(aResponse().withHeader("Content-Type", "image/png").withBody(PNG).withFixedDelay(1500)),
		)

		failure { fetcher(timeout = Duration.ofMillis(500)).fetch(url("/slow.png")) } shouldBe RemoteImageError.TIMEOUT
	}

	@Test
	fun `only http and https URLs without credentials are accepted`() {
		listOf("file:///etc/passwd", "ftp://example.test/a.png", "javascript:alert(1)", "http://user:pw@example.test/a.png", "http:///a", "::")
			.forEach { failure { fetcher().fetch(it) } shouldBe RemoteImageError.INVALID_URL }
	}

	@Test
	fun `by default private addresses and other ports are blocked`() {
		image("/a.png")
		val strict = fetcher(allowPrivate = false)

		failure { strict.fetch(url("/a.png")) } shouldBe RemoteImageError.BLOCKED_ADDRESS
		failure { strict.fetch("http://127.0.0.1/a.png") } shouldBe RemoteImageError.BLOCKED_ADDRESS
		failure { strict.fetch("http://[::1]/a.png") } shouldBe RemoteImageError.BLOCKED_ADDRESS
		failure { strict.fetch("http://169.254.169.254/latest/meta-data") } shouldBe RemoteImageError.BLOCKED_ADDRESS
		failure { strict.fetch("http://localhost/a.png") } shouldBe RemoteImageError.BLOCKED_ADDRESS
		failure { strict.fetch("https://[::ffff:10.0.0.1]/a.png") } shouldBe RemoteImageError.BLOCKED_ADDRESS
	}

	@Test
	fun `a host name resolving to a private address is blocked, also when it has public ones`() {
		val strict = fetcher(allowPrivate = false)
		strict.resolver =
			HostResolver { host ->
				when (host) {
					"rebind.example.test" -> listOf(InetAddress.getByName("10.1.2.3"))
					"mixed.example.test" -> listOf(InetAddress.getByName("93.184.216.34"), InetAddress.getByName("192.168.1.1"))
					else -> error("unexpected lookup of $host")
				}
			}

		failure { strict.fetch("http://rebind.example.test/a.png") } shouldBe RemoteImageError.BLOCKED_ADDRESS
		failure { strict.fetch("https://mixed.example.test/a.png") } shouldBe RemoteImageError.BLOCKED_ADDRESS
	}

	@Test
	fun `the connection goes to the checked address and redirects are checked again`() {
		image("/a.png")
		server.stubFor(
			get(
				urlEqualTo("/to-internal"),
			).willReturn(aResponse().withStatus(302).withHeader("Location", "http://internal.example.test:${server.port()}/a.png")),
		)
		val pinned = fetcher(allowPrivate = false)
		val lookups = mutableListOf<String>()
		pinned.allowPort = { true }
		pinned.allowAddress = { it.hostAddress == "127.0.0.1" }
		pinned.resolver =
			HostResolver { host ->
				lookups += host
				when (host) {
					"img.example.test" -> listOf(InetAddress.getByAddress(host, byteArrayOf(127, 0, 0, 1)))
					else -> listOf(InetAddress.getByAddress(host, byteArrayOf(127, 0, 0, 2)))
				}
			}

		pinned.fetch("http://img.example.test:${server.port()}/a.png").bytes.toList() shouldBe PNG.toList()
		// One lookup per connection: nothing else resolves the name again.
		lookups shouldBe listOf("img.example.test")
		server.verify(
			getRequestedFor(
				urlEqualTo("/a.png"),
			).withHeader(
				"Host",
				com.github.tomakehurst.wiremock.client.WireMock
					.equalTo("img.example.test:${server.port()}"),
			),
		)
		failure { pinned.fetch("http://img.example.test:${server.port()}/to-internal") } shouldBe RemoteImageError.BLOCKED_ADDRESS
	}

	private companion object {
		const val MAX_BYTES = 1000L
		val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3)
	}
}
