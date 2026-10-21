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
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.util.unit.DataSize
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.ServerSocket
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * The size limit of [PageFetcher] on the wire. WireMock gzips answers on its own when the client accepts gzip (as
 * [PageFetcher] does), which would hide the limit on uncompressed bodies, so this server never compresses: a body is
 * sent exactly as stubbed.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PageFetcherSizeLimitTests {
	private val server = WireMockServer(wireMockConfig().dynamicPort().gzipDisabled(true))

	@BeforeAll
	fun start() = server.start()

	@AfterAll
	fun stop() = server.stop()

	@BeforeEach
	fun reset() = server.resetAll()

	private val fetcher =
		PageFetcher(BookmarkFetchProperties(DataSize.ofBytes(MAX), Duration.ofSeconds(1), Duration.ofSeconds(5), 2, true, 1, 10))

	private fun local(path: String) = "http://127.0.0.1:${server.port()}$path"

	private fun stub(
		path: String,
		body: ByteArray,
		gzip: Boolean = false,
	) = server.stubFor(
		get(urlEqualTo(path)).willReturn(
			aResponse()
				.withHeader("Content-Type", "text/html")
				.apply { if (gzip) withHeader("Content-Encoding", "gzip") }
				.withBody(body),
		),
	)

	/** Text that does not compress well (fixed seed, so every run sends the same page). */
	private fun noise(size: Int) = Random(SEED).nextBytes(size)

	private fun gzip(bytes: ByteArray) = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(bytes) } }.toByteArray()

	@Test
	fun `an uncompressed page larger than max-size is cut off at exactly max-size bytes`() {
		stub("/big", "<title>Big</title>".toByteArray() + noise(5_000_000))

		val started = System.nanoTime()
		val page = fetcher.fetch(local("/big"))

		page.bytes.size shouldBe MAX.toInt()
		String(page.bytes.copyOfRange(0, 18)) shouldBe "<title>Big</title>"
		// The download is cancelled at the limit instead of reading all 5 MB.
		(Duration.ofNanos(System.nanoTime() - started) < Duration.ofSeconds(5)) shouldBe true
	}

	/**
	 * The JDK client talks HTTP/2 to WireMock (h2c upgrade), as it does with most HTTPS sites (ALPN). Cancelling the
	 * download at the limit races with the client failing the exchange ("Stream N cancelled"), so a single fetch only
	 * sometimes shows it: every one of many fetches over the same client must return the page.
	 */
	@Test
	fun `pages larger than max-size are cut off reliably, also over a reused HTTP 2 connection`() {
		stub("/large", "<title>Large</title>".toByteArray() + noise(1_000_000))

		val outcomes =
			(1..REPEATS).map {
				try {
					"${fetcher.fetch(local("/large")).bytes.size} bytes"
				} catch (e: PageFetchException) {
					"${e.error} (${e.cause?.message})"
				}
			}

		outcomes.groupingBy { it }.eachCount() shouldBe mapOf("$MAX bytes" to REPEATS)
	}

	@Test
	fun `a page of exactly max-size bytes is returned whole, one byte less too`() {
		val exact = "<title>Exact</title>".toByteArray() + ByteArray(MAX.toInt() - 20) { 'e'.code.toByte() }
		stub("/exact", exact)
		stub("/below", exact.copyOf(MAX.toInt() - 1))

		fetcher.fetch(local("/exact")).bytes shouldBe exact
		fetcher.fetch(local("/below")).bytes shouldBe exact.copyOf(MAX.toInt() - 1)
	}

	@Test
	fun `a gzip page whose compressed body is cut off at max-size gives what could be decompressed`() {
		// Random bytes do not compress: max-size compressed bytes decompress to less than max-size, then the stream ends.
		stub("/cut", gzip("<title>Cut</title>".toByteArray() + noise(200_000)), gzip = true)

		val page = fetcher.fetch(local("/cut"))

		page.bytes.size shouldBeGreaterThan 0
		page.bytes.size shouldBeLessThan MAX.toInt()
		String(page.bytes.copyOfRange(0, 18)) shouldBe "<title>Cut</title>"
	}

	@Test
	fun `a connection closed in the middle of the body fails as unreachable`() {
		ServerSocket(0).use { socket ->
			val serving =
				thread(isDaemon = true) {
					socket.accept().use { connection ->
						connection.getInputStream().read(ByteArray(8_192))
						connection.getOutputStream().apply {
							write("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: 1000\r\n\r\n<title>Half".toByteArray())
							flush()
						}
					}
				}

			val error = shouldThrow<PageFetchException> { fetcher.fetch("http://127.0.0.1:${socket.localPort}/") }.error

			error shouldBe PageFetchError.UNREACHABLE
			serving.join(5_000)
		}
	}

	@Test
	fun `over HTTP 1_1 a page larger than max-size is cut off and its connection is closed, not left open`() {
		ServerSocket(0).use { socket ->
			val closedByClient = CountDownLatch(1)
			val serving =
				thread(isDaemon = true) {
					socket.accept().use { connection ->
						connection.getInputStream().read(ByteArray(8_192))
						val out = connection.getOutputStream()
						out.write("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: 100000000\r\n\r\n<title>Endless</title>".toByteArray())
						// Keeps sending until the client closes the connection (the write then fails) or 10 s are over.
						val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
						try {
							while (System.nanoTime() < deadline) {
								out.write(ByteArray(8_192) { 'x'.code.toByte() })
								out.flush()
								Thread.sleep(5)
							}
						} catch (_: IOException) {
							closedByClient.countDown()
						}
					}
				}

			val page = fetcher.fetch("http://127.0.0.1:${socket.localPort}/")

			page.bytes.size shouldBe MAX.toInt()
			String(page.bytes.copyOfRange(0, 22)) shouldBe "<title>Endless</title>"
			closedByClient.await(5, TimeUnit.SECONDS) shouldBe true
			serving.join(5_000)
		}
	}

	@Test
	fun `a large answer that is not HTML is not downloaded, it fails as not HTML at once`() {
		server.stubFor(
			get(urlEqualTo("/video")).willReturn(
				aResponse()
					.withHeader("Content-Type", "video/mp4")
					.withBody(noise(100_000))
					// The body takes longer than the fetch timeout (5 s) to arrive.
					.withChunkedDribbleDelay(50, 8_000),
			),
		)

		val started = System.nanoTime()
		val error = shouldThrow<PageFetchException> { fetcher.fetch(local("/video")) }.error

		error shouldBe PageFetchError.NOT_HTML
		(Duration.ofNanos(System.nanoTime() - started) < Duration.ofSeconds(3)) shouldBe true
	}

	@Test
	fun `a large error page is not downloaded, it fails as an HTTP error at once`() {
		server.stubFor(
			get(urlEqualTo("/broken")).willReturn(
				aResponse()
					.withStatus(500)
					.withHeader("Content-Type", "text/html")
					.withBody(noise(100_000))
					// The body takes longer than the fetch timeout (5 s) to arrive.
					.withChunkedDribbleDelay(50, 8_000),
			),
		)

		val started = System.nanoTime()
		val error = shouldThrow<PageFetchException> { fetcher.fetch(local("/broken")) }.error

		error shouldBe PageFetchError.HTTP_ERROR
		(Duration.ofNanos(System.nanoTime() - started) < Duration.ofSeconds(3)) shouldBe true
	}

	@Test
	fun `a redirect with a large slow body is followed without downloading that body`() {
		server.stubFor(
			get(urlEqualTo("/moved")).willReturn(
				aResponse()
					.withStatus(302)
					.withHeader("Location", "/target")
					.withHeader("Content-Type", "text/html")
					.withBody(noise(100_000))
					.withChunkedDribbleDelay(50, 8_000),
			),
		)
		stub("/target", "<title>Target</title>".toByteArray())

		val started = System.nanoTime()
		val page = fetcher.fetch(local("/moved"))

		String(page.bytes) shouldBe "<title>Target</title>"
		page.uri.path shouldBe "/target"
		(Duration.ofNanos(System.nanoTime() - started) < Duration.ofSeconds(3)) shouldBe true
	}

	private companion object {
		const val MAX = 4_096L
		const val SEED = 20261021
		const val REPEATS = 50
	}
}
