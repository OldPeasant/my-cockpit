package ch.sonensei.cockpit.mail.api

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.util.unit.DataSize
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.UnknownHostException
import java.security.KeyStore
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import kotlin.concurrent.thread

/**
 * The HTTP/1.1 client of the image proxy against a scripted local server: framing (Content-Length, chunked, until
 * close), malformed responses, redirects, name resolution and TLS.
 */
class RemoteImageFetcherProtocolTests {
	private val servers = mutableListOf<AutoCloseable>()

	@AfterEach
	fun stopServers() = servers.forEach { it.close() }

	/** Accepts connections on loopback; answers each request (after its head) with [respond]. Records request heads. */
	private inner class ScriptedServer(
		private val respond: (Socket) -> Unit,
	) : AutoCloseable {
		private val socket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
		val requests = CopyOnWriteArrayList<String>()
		val port get() = socket.localPort

		init {
			servers += this
			thread(isDaemon = true, name = "scripted-http") {
				while (!socket.isClosed) {
					val client = runCatching { socket.accept() }.getOrNull() ?: break
					client.use {
						requests += readHead(it)
						runCatching { respond(it) }
					}
				}
			}
		}

		private fun readHead(client: Socket): String {
			val head = ByteArrayOutputStream()
			val input = client.getInputStream()
			while (!head.toString(Charsets.ISO_8859_1).endsWith("\r\n\r\n")) {
				val b = input.read()
				if (b < 0) break
				head.write(b)
			}
			return head.toString(Charsets.ISO_8859_1)
		}

		fun url(path: String = "/img.png") = "http://127.0.0.1:$port$path"

		override fun close() = socket.close()
	}

	private fun answering(raw: ByteArray) = ScriptedServer { it.getOutputStream().apply { write(raw) }.flush() }

	private fun answering(raw: String) = answering(raw.toByteArray(Charsets.ISO_8859_1))

	private fun fetcher(
		maxBytes: Long = MAX_BYTES,
		timeout: Duration = Duration.ofSeconds(5),
		maxRedirects: Int = 2,
	) = RemoteImageFetcher(
		MailReadProperties(
			DataSize.ofMegabytes(1),
			MailReadProperties.ImageProxy(DataSize.ofBytes(maxBytes), Duration.ofSeconds(2), timeout, maxRedirects, true),
		),
	)

	private fun failure(block: () -> Unit) = shouldThrow<RemoteImageException>(block).error

	private fun head(
		status: String = "200 OK",
		vararg headers: String,
	) = "HTTP/1.1 $status\r\n" + headers.joinToString("") { "$it\r\n" } + "\r\n"

	@Test
	fun `a chunked body with chunk extensions is decoded`() {
		val server = answering(head("200 OK", "Content-Type: image/png", "Transfer-Encoding: chunked") + "3;ext=1\r\nabc\r\n2\r\nde\r\n0\r\n\r\n")

		fetcher().fetch(server.url()).bytes.toString(Charsets.US_ASCII) shouldBe "abcde"
	}

	@Test
	fun `a chunked body above the limit is refused, also when one chunk announces it`() {
		val announced = answering(head("200 OK", "Content-Type: image/png", "Transfer-Encoding: chunked") + "FFFF\r\nab")
		val many =
			answering(
				head("200 OK", "Content-Type: image/png", "Transfer-Encoding: chunked") +
					"10\r\n0123456789abcdef\r\n".repeat(4) + "0\r\n\r\n",
			)

		failure { fetcher(maxBytes = 50).fetch(announced.url()) } shouldBe RemoteImageError.TOO_LARGE
		failure { fetcher(maxBytes = 50).fetch(many.url()) } shouldBe RemoteImageError.TOO_LARGE
		fetcher(maxBytes = 64).fetch(many.url()).bytes.size shouldBe 64
	}

	@Test
	fun `a malformed or truncated chunked body fails`() {
		val malformed = answering(head("200 OK", "Content-Type: image/png", "Transfer-Encoding: chunked") + "zz\r\nabc\r\n0\r\n\r\n")
		val truncated = answering(head("200 OK", "Content-Type: image/png", "Transfer-Encoding: chunked") + "10\r\nabc")

		failure { fetcher().fetch(malformed.url()) } shouldBe RemoteImageError.UPSTREAM_FAILED
		failure { fetcher().fetch(truncated.url()) } shouldBe RemoteImageError.UPSTREAM_FAILED
	}

	@Test
	fun `a Content-Length above the limit is refused before reading the body`() {
		val server = answering(head("200 OK", "Content-Type: image/png", "Content-Length: 1001"))

		failure { fetcher().fetch(server.url()) } shouldBe RemoteImageError.TOO_LARGE
	}

	@Test
	fun `a body shorter than its Content-Length fails`() {
		val server = answering(head("200 OK", "Content-Type: image/png", "Content-Length: 10") + "abc")

		failure { fetcher().fetch(server.url()) } shouldBe RemoteImageError.UPSTREAM_FAILED
	}

	@Test
	fun `exactly the limit is accepted`() {
		val server = answering(head("200 OK", "Content-Type: image/png", "Content-Length: 1000") + "x".repeat(1000))

		fetcher().fetch(server.url()).bytes.size shouldBe 1000
	}

	@Test
	fun `without length or chunking the body is read until the server closes, within the limit`() {
		val small = answering(head("200 OK", "Content-Type: image/webp") + "webp-bytes")
		val big = answering(head("200 OK", "Content-Type: image/webp") + "x".repeat(1001))

		fetcher().fetch(small.url()).bytes.toString(Charsets.US_ASCII) shouldBe "webp-bytes"
		failure { fetcher().fetch(big.url()) } shouldBe RemoteImageError.TOO_LARGE
	}

	@Test
	fun `compressed bodies are refused`() {
		val server = answering(head("200 OK", "Content-Type: image/png", "Content-Encoding: gzip", "Content-Length: 3") + "abc")
		val identity = answering(head("200 OK", "Content-Type: image/png", "Content-Encoding: identity", "Content-Length: 3") + "abc")

		failure { fetcher().fetch(server.url()) } shouldBe RemoteImageError.UPSTREAM_FAILED
		fetcher().fetch(identity.url()).bytes.size shouldBe 3
	}

	@Test
	fun `header names are case-insensitive and bare LF line ends are accepted`() {
		val server = answering("HTTP/1.0 200 OK\nCONTENT-TYPE: IMAGE/JPEG; q=1\ncontent-length: 4\n\njpeg")

		val image = fetcher().fetch(server.url())

		image.contentType shouldBe "image/jpeg"
		image.bytes.toString(Charsets.US_ASCII) shouldBe "jpeg"
	}

	@Test
	fun `malformed response heads fail`() {
		listOf(
			"SSH-2.0-OpenSSH\r\n\r\n",
			"HTTP/2 200\r\n\r\n",
			"HTTP/1.1 OK\r\n\r\n",
			"HTTP/1.1\r\n\r\n",
			"HTTP/1.1 200 OK\r\nContent-Type: image/png\r\n",
			"",
			head("200 OK", *Array(101) { "X-Fictitious-$it: y" }),
			"HTTP/1.1 200 OK\r\nX-Long: ${"a".repeat(9000)}\r\n\r\n",
		).forEach { raw ->
			failure { fetcher().fetch(answering(raw).url()) } shouldBe RemoteImageError.UPSTREAM_FAILED
		}
	}

	@Test
	fun `the request has a minimal head with the path as written`() {
		val server = answering(head("200 OK", "Content-Type: image/png", "Content-Length: 1") + "x")

		fetcher().fetch("http://127.0.0.1:${server.port}")
		fetcher().fetch("http://127.0.0.1:${server.port}/a b.png?size=2&x=%41")

		server.requests[0] shouldStartWith "GET / HTTP/1.1\r\nHost: 127.0.0.1:${server.port}\r\n"
		server.requests[1] shouldStartWith "GET /a%20b.png?size=2&x=%41 HTTP/1.1\r\n"
		server.requests[1].lowercase().contains("cookie") shouldBe false
		server.requests[1].lowercase().contains("referer") shouldBe false
		server.requests[1].lowercase().contains("authorization") shouldBe false
	}

	@Test
	fun `redirects need a valid http location and count against the limit`() {
		val target = answering(head("200 OK", "Content-Type: image/png", "Content-Length: 1") + "x")
		val withoutLocation = answering(head("302 Found"))
		val toFtp = answering(head("302 Found", "Location: ftp://files.example.test/a.png"))
		val malformed = answering(head("301 Moved", "Location: http://[broken/"))
		val once = answering(head("307 Temporary Redirect", "Location: ${target.url()}"))

		failure { fetcher().fetch(withoutLocation.url()) } shouldBe RemoteImageError.UPSTREAM_FAILED
		failure { fetcher().fetch(toFtp.url()) } shouldBe RemoteImageError.INVALID_URL
		failure { fetcher().fetch(malformed.url()) } shouldBe RemoteImageError.UPSTREAM_FAILED
		fetcher(maxRedirects = 1).fetch(once.url()).bytes.size shouldBe 1
		failure { fetcher(maxRedirects = 0).fetch(once.url()) } shouldBe RemoteImageError.UPSTREAM_FAILED
	}

	@Test
	fun `a redirect to another port is checked against the port policy`() {
		val redirect = answering(head("302 Found", "Location: http://img.example.test:8080/a.png"))
		val strictPorts = fetcher()
		strictPorts.allowPort = { it == redirect.port }
		strictPorts.resolver = HostResolver { listOf(InetAddress.getLoopbackAddress()) }

		failure { strictPorts.fetch("http://img.example.test:${redirect.port}/") } shouldBe RemoteImageError.BLOCKED_ADDRESS
	}

	@Test
	fun `unknown hosts, empty lookups and refused connections fail upstream`() {
		val fetcher = fetcher()
		fetcher.resolver =
			HostResolver { host ->
				when (host) {
					"unknown.example.test" -> throw UnknownHostException(host)
					else -> emptyList()
				}
			}
		val closedPort = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }

		failure { fetcher.fetch("http://unknown.example.test/a.png") } shouldBe RemoteImageError.UPSTREAM_FAILED
		failure { fetcher.fetch("http://empty.example.test/a.png") } shouldBe RemoteImageError.UPSTREAM_FAILED
		failure { fetcher().fetch("http://127.0.0.1:$closedPort/a.png") } shouldBe RemoteImageError.UPSTREAM_FAILED
	}

	@Test
	fun `a server that stops sending in the middle of the body times out`() {
		val server =
			ScriptedServer {
				it.getOutputStream().apply { write(head("200 OK", "Content-Type: image/png", "Content-Length: 10").toByteArray()) }.flush()
				TimeUnit.SECONDS.sleep(3)
			}

		failure { fetcher(timeout = Duration.ofMillis(500)).fetch(server.url()) } shouldBe RemoteImageError.TIMEOUT
	}

	@Test
	fun `HTTPS checks the certificate against the host name`() {
		val tls = WireMockServer(wireMockConfig().dynamicHttpsPort().httpDisabled(true))
		tls.start()
		servers += AutoCloseable { tls.stop() }
		tls.stubFor(get(urlEqualTo("/a.png")).willReturn(aResponse().withHeader("Content-Type", "image/png").withBody("png")))
		val loopback = HostResolver { listOf(InetAddress.getLoopbackAddress()) }

		// The default trust store does not know the test certificate.
		val untrusted = fetcher().apply { resolver = loopback }
		failure { untrusted.fetch("https://localhost:${tls.httpsPort()}/a.png") } shouldBe RemoteImageError.UPSTREAM_FAILED

		val trusting = fetcher().apply { resolver = loopback }
		trusting.sslSocketFactory = trustingWireMock()
		trusting.fetch("https://localhost:${tls.httpsPort()}/a.png").bytes.toString(Charsets.US_ASCII) shouldBe "png"
		// Same server and certificate, but the URL names another host: the certificate does not match it.
		failure { trusting.fetch("https://images.example.test:${tls.httpsPort()}/a.png") } shouldBe RemoteImageError.UPSTREAM_FAILED
	}

	/** An SSL socket factory that trusts the certificate of WireMock's built-in keystore (issued to `localhost`). */
	private fun trustingWireMock(): javax.net.ssl.SSLSocketFactory {
		val keystore = KeyStore.getInstance("JKS")
		checkNotNull(WireMockServer::class.java.classLoader.getResourceAsStream("keystore")).use { keystore.load(it, "password".toCharArray()) }
		val trusted = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
		keystore.aliases().toList().forEach { trusted.setCertificateEntry(it, keystore.getCertificate(it)) }
		val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trusted) }
		return SSLContext.getInstance("TLS").apply { init(null, trust.trustManagers, null) }.socketFactory
	}

	private companion object {
		const val MAX_BYTES = 1000L
	}
}
