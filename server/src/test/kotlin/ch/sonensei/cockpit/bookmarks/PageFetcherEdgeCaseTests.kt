package ch.sonensei.cockpit.bookmarks

import ch.sonensei.cockpit.bookmarks.infra.BookmarkFetchProperties
import ch.sonensei.cockpit.bookmarks.infra.PageFetchError
import ch.sonensei.cockpit.bookmarks.infra.PageFetchException
import ch.sonensei.cockpit.bookmarks.infra.PageFetcher
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.util.unit.DataSize
import java.net.ServerSocket
import java.time.Duration

/** Answers and failures of [PageFetcher] beyond [PageFetcherTests]: odd redirects, encodings, content types, config. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PageFetcherEdgeCaseTests {
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
		connectTimeout: Duration = Duration.ofSeconds(1),
		timeout: Duration = Duration.ofSeconds(2),
		maxRedirects: Int = 2,
		threads: Int = 1,
		queueSize: Int = 10,
	) = BookmarkFetchProperties(maxSize, connectTimeout, timeout, maxRedirects, allowPrivateNetworks, threads, queueSize)

	private val fetcher = PageFetcher(properties())

	private fun local(path: String) = "http://127.0.0.1:${server.port()}$path"

	private fun stub(
		path: String,
		status: Int = 200,
		headers: Map<String, String> = emptyMap(),
		body: ByteArray = ByteArray(0),
	) = server.stubFor(
		get(urlEqualTo(path)).willReturn(
			headers.entries.fold(aResponse().withStatus(status).withBody(body)) { response, (name, value) -> response.withHeader(name, value) },
		),
	)

	private fun failure(block: () -> Unit): PageFetchError = shouldThrow<PageFetchException>(block).error

	// --- SSRF

	@Test
	fun `a host without any address is refused`() {
		val fetcher = PageFetcher(properties(allowPrivateNetworks = false)) { _ -> emptyList() }

		failure { fetcher.fetch("https://empty.example/") } shouldBe PageFetchError.BLOCKED_ADDRESS
	}

	@Test
	fun `a failing name resolution other than an unknown host is reported as unreachable`() {
		val fetcher = PageFetcher(properties(allowPrivateNetworks = false)) { _ -> throw IllegalStateException("resolver broken") }

		failure { fetcher.fetch("https://broken.example/") } shouldBe PageFetchError.UNREACHABLE
	}

	@Test
	fun `a redirect to a URL with credentials is refused`() {
		stub("/r", 302, mapOf("Location" to "http://user:secret@127.0.0.1:${server.port()}/in"))

		failure { fetcher.fetch(local("/r")) } shouldBe PageFetchError.INVALID_URL
		server.allServeEvents.size shouldBe 1
	}

	@Test
	fun `a redirect without or with a malformed Location fails`() {
		stub("/no-location", 302)
		stub("/bad-location", 301, mapOf("Location" to "http://exa mple.org/"))

		failure { fetcher.fetch(local("/no-location")) } shouldBe PageFetchError.HTTP_ERROR
		failure { fetcher.fetch(local("/bad-location")) } shouldBe PageFetchError.HTTP_ERROR
	}

	@Test
	fun `with zero redirects allowed the first redirect already fails`() {
		stub("/r", 307, mapOf("Location" to "/target"))
		stub("/target", headers = mapOf("Content-Type" to "text/html"), body = "<title>T</title>".toByteArray())

		failure { PageFetcher(properties(maxRedirects = 0)).fetch(local("/r")) } shouldBe PageFetchError.TOO_MANY_REDIRECTS
	}

	// --- answers

	@Test
	fun `server errors and other 3xx answers fail`() {
		stub("/error", 500, mapOf("Content-Type" to "text/html"), "<title>Oops</title>".toByteArray())
		stub("/not-modified", 304)

		failure { fetcher.fetch(local("/error")) } shouldBe PageFetchError.HTTP_ERROR
		failure { fetcher.fetch(local("/not-modified")) } shouldBe PageFetchError.HTTP_ERROR
	}

	@Test
	fun `XHTML is accepted, a missing Content-Type is not HTML`() {
		stub("/x", headers = mapOf("Content-Type" to "Application/XHTML+XML; charset=\"ISO-8859-1\""), body = "<title>X</title>".toByteArray())
		stub("/plain", body = "<title>No type</title>".toByteArray())

		val page = fetcher.fetch(local("/x"))
		page.charset shouldBe "ISO-8859-1"
		String(page.bytes) shouldBe "<title>X</title>"
		failure { fetcher.fetch(local("/plain")) } shouldBe PageFetchError.NOT_HTML
	}

	@Test
	fun `an empty charset parameter counts as none`() {
		stub("/c", headers = mapOf("Content-Type" to "text/html; charset="), body = "<title>C</title>".toByteArray())

		fetcher.fetch(local("/c")).charset shouldBe null
	}

	@Test
	fun `an unsupported or corrupt Content-Encoding fails as unreachable`() {
		stub("/br", headers = mapOf("Content-Type" to "text/html", "Content-Encoding" to "br"), body = "compressed".toByteArray())
		stub("/bad-gzip", headers = mapOf("Content-Type" to "text/html", "Content-Encoding" to "gzip"), body = "not gzip at all".toByteArray())

		failure { fetcher.fetch(local("/br")) } shouldBe PageFetchError.UNREACHABLE
		failure { fetcher.fetch(local("/bad-gzip")) } shouldBe PageFetchError.UNREACHABLE
	}

	@Test
	fun `a closed port fails as unreachable`() {
		val port = ServerSocket(0).use { it.localPort }

		failure { fetcher.fetch("http://127.0.0.1:$port/") } shouldBe PageFetchError.UNREACHABLE
	}

	// --- configuration

	@Test
	fun `invalid fetch settings are refused at startup`() {
		for (invalid in listOf(
			{ properties(maxSize = DataSize.ofBytes(0)) },
			{ properties(maxSize = DataSize.ofGigabytes(3)) },
			{ properties(maxRedirects = -1) },
			{ properties(timeout = Duration.ZERO) },
			{ properties(connectTimeout = Duration.ofSeconds(-1)) },
			{ properties(threads = 0) },
			{ properties(queueSize = 0) },
		)) {
			shouldThrow<IllegalArgumentException> { invalid() }
		}
		properties(maxSize = DataSize.ofBytes(1), maxRedirects = 0, threads = 1, queueSize = 1).maxRedirects shouldBe 0
	}
}
