package ch.sonensei.cockpit.bookmarks

import ch.sonensei.cockpit.bookmarks.infra.BookmarkFetchProperties
import ch.sonensei.cockpit.bookmarks.infra.PageFetchError
import ch.sonensei.cockpit.bookmarks.infra.PageFetchException
import ch.sonensei.cockpit.bookmarks.infra.PageFetcher
import ch.sonensei.cockpit.mail.api.HostResolver
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.util.unit.DataSize
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.UnknownHostException
import java.time.Duration
import java.util.zip.GZIPOutputStream

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PageFetcherTests {
	private val server = WireMockServer(wireMockConfig().dynamicPort())

	@BeforeAll
	fun start() = server.start()

	@AfterAll
	fun stop() = server.stop()

	@BeforeEach
	fun reset() = server.resetAll()

	private fun properties(
		allowPrivateNetworks: Boolean = true,
		maxSize: DataSize = DataSize.ofKilobytes(4),
		timeout: Duration = Duration.ofSeconds(1),
		maxRedirects: Int = 2,
	) = BookmarkFetchProperties(maxSize, Duration.ofSeconds(1), timeout, maxRedirects, allowPrivateNetworks, 1, 10)

	private fun local(path: String) = "http://127.0.0.1:${server.port()}$path"

	private fun stubHtml(
		path: String,
		body: String,
	) = server.stubFor(get(urlEqualTo(path)).willReturn(aResponse().withHeader("Content-Type", "text/html; charset=utf-8").withBody(body)))

	private fun failure(block: () -> Unit): PageFetchError = shouldThrow<PageFetchException>(block).error

	// --- SSRF protection

	private fun guarded(vararg addresses: String): PageFetcher =
		PageFetcher(properties(allowPrivateNetworks = false)) { _ -> addresses.map { InetAddress.getByName(it) } }

	@Test
	fun `hosts with a private, loopback or link-local address are refused before any request`() {
		for (address in listOf("127.0.0.1", "10.1.2.3", "192.168.1.1", "169.254.169.254", "::1", "fd00::1", "::ffff:10.0.0.1")) {
			failure { guarded(address).fetch("http://intranet.example/") } shouldBe PageFetchError.BLOCKED_ADDRESS
		}
		// One private address among public ones is enough to refuse it.
		failure { guarded("93.184.215.14", "10.0.0.1").fetch("https://mixed.example/") } shouldBe PageFetchError.BLOCKED_ADDRESS
	}

	@Test
	fun `a loopback address in the URL itself is refused`() {
		stubHtml("/", "<title>Local</title>")

		failure { PageFetcher(properties(allowPrivateNetworks = false)).fetch("http://127.0.0.1/") } shouldBe PageFetchError.BLOCKED_ADDRESS
		failure { PageFetcher(properties(allowPrivateNetworks = false)).fetch("http://[::1]/") } shouldBe PageFetchError.BLOCKED_ADDRESS
		server.allServeEvents.size shouldBe 0
	}

	@Test
	fun `ports other than 80 and 443 are refused`() {
		failure { guarded("93.184.215.14").fetch("http://public.example:8080/") } shouldBe PageFetchError.BLOCKED_ADDRESS
		failure { guarded("93.184.215.14").fetch("https://public.example:22/") } shouldBe PageFetchError.BLOCKED_ADDRESS
	}

	@Test
	fun `only http(s) URLs without credentials are fetched`() {
		val fetcher = PageFetcher(properties())
		for (url in listOf(
			"ftp://example.org/",
			"file:///etc/passwd",
			"javascript:alert(1)",
			"http://user:pw@example.org/",
			"not a url",
			"/relative",
		)) {
			failure { fetcher.fetch(url) } shouldBe PageFetchError.INVALID_URL
		}
	}

	@Test
	fun `an unknown host is reported as such`() {
		val fetcher = PageFetcher(properties(allowPrivateNetworks = false)) { host -> throw UnknownHostException(host) }

		failure { fetcher.fetch("https://nowhere.invalid/") } shouldBe PageFetchError.UNKNOWN_HOST
	}

	@Test
	fun `a slow name resolution counts against the time limit`() {
		val slow = HostResolver { _ -> Thread.sleep(3_000).let { listOf(InetAddress.getByName("93.184.215.14")) } }
		val fetcher = PageFetcher(properties(allowPrivateNetworks = false, timeout = Duration.ofMillis(300)), slow)

		failure { fetcher.fetch("https://slow-dns.example/") } shouldBe PageFetchError.TIMEOUT
	}

	// --- fetching

	@Test
	fun `an HTML page is returned with its charset and final URL after redirects`() {
		server.stubFor(get(urlEqualTo("/old")).willReturn(aResponse().withStatus(301).withHeader("Location", "/new?x=1")))
		stubHtml("/new?x=1", "<title>New</title>")

		val page = PageFetcher(properties()).fetch(local("/old"))

		page.uri.toString() shouldBe local("/new?x=1")
		page.charset shouldBe "utf-8"
		String(page.bytes) shouldBe "<title>New</title>"
	}

	@Test
	fun `redirect loops end after the configured number of redirects`() {
		server.stubFor(get(urlEqualTo("/loop")).willReturn(aResponse().withStatus(302).withHeader("Location", "/loop")))

		failure { PageFetcher(properties(maxRedirects = 2)).fetch(local("/loop")) } shouldBe PageFetchError.TOO_MANY_REDIRECTS
		server.allServeEvents.size shouldBe 3
	}

	@Test
	fun `a redirect to a non-http URL is refused`() {
		server.stubFor(get(urlEqualTo("/r")).willReturn(aResponse().withStatus(302).withHeader("Location", "file:///etc/passwd")))

		failure { PageFetcher(properties()).fetch(local("/r")) } shouldBe PageFetchError.INVALID_URL
	}

	@Test
	fun `error answers and non-HTML answers fail`() {
		server.stubFor(get(urlEqualTo("/missing")).willReturn(aResponse().withStatus(404).withBody("<title>Not found</title>")))
		server.stubFor(get(urlEqualTo("/doc.pdf")).willReturn(aResponse().withHeader("Content-Type", "application/pdf").withBody("%PDF")))

		failure { PageFetcher(properties()).fetch(local("/missing")) } shouldBe PageFetchError.HTTP_ERROR
		failure { PageFetcher(properties()).fetch(local("/doc.pdf")) } shouldBe PageFetchError.NOT_HTML
	}

	@Test
	fun `only the first max-size bytes of a large page are read`() {
		stubHtml("/big", "<title>Big</title>" + "x".repeat(200_000))

		val page = PageFetcher(properties(maxSize = DataSize.ofKilobytes(4))).fetch(local("/big"))

		page.bytes.size shouldBeLessThanOrEqual 4_096
		String(page.bytes) shouldContain "<title>Big</title>"
	}

	@Test
	fun `a gzip page is decompressed, at most max-size bytes`() {
		val html = "<title>Zipped</title>" + "y".repeat(100_000)
		val gzipped = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(html.toByteArray()) } }.toByteArray()
		server.stubFor(
			get(urlEqualTo("/gz")).willReturn(
				aResponse().withHeader("Content-Type", "text/html").withHeader("Content-Encoding", "gzip").withBody(gzipped),
			),
		)

		val page = PageFetcher(properties(maxSize = DataSize.ofKilobytes(4))).fetch(local("/gz"))

		page.bytes.size shouldBeLessThanOrEqual 4_096
		String(page.bytes) shouldContain "<title>Zipped</title>"
	}

	@Test
	fun `a server that does not answer in time fails with a timeout`() {
		server.stubFor(get(urlEqualTo("/slow")).willReturn(aResponse().withHeader("Content-Type", "text/html").withFixedDelay(3_000)))

		val started = System.nanoTime()
		failure { PageFetcher(properties(timeout = Duration.ofMillis(500))).fetch(local("/slow")) } shouldBe PageFetchError.TIMEOUT
		Duration.ofNanos(System.nanoTime() - started) shouldBeLessThanOrEqualTo Duration.ofSeconds(2)
	}

	private infix fun Duration.shouldBeLessThanOrEqualTo(other: Duration) = (this <= other) shouldBe true
}
