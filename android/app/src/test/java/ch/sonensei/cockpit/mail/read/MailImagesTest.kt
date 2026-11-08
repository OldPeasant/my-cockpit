package ch.sonensei.cockpit.mail.read

import android.net.Uri
import android.webkit.WebResourceResponse
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.core.network.CockpitHttp
import ch.sonensei.cockpit.core.network.ServerUrlInterceptor
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The images of a mail page: only the Cockpit server's attachment downloads and image proxy are fetched (through the
 * app's client, which points them at the configured server); everything else is answered with an empty 403.
 */
@RunWith(AndroidJUnit4::class)
class MailImagesTest {
    private val server = MockWebServer()
    private lateinit var fetcher: MailImageFetcher

    @Before
    fun setUp() {
        server.start()
        fetcher = MailImageFetcher(CockpitHttp.okHttpClient(ServerUrlInterceptor { server.url("/") }, null, null))
    }

    @After
    fun tearDown() = server.close()

    private fun image(
        type: String = "image/png",
        code: Int = 200,
        body: String = "fictitious-png-bytes",
    ) = MockResponse
        .Builder()
        .code(code)
        .addHeader("Content-Type", type)
        .body(body)
        .build()

    private fun assertBlocked(
        response: WebResourceResponse,
        message: String = "",
    ) {
        assertEquals(message, 403, response.statusCode)
        assertEquals(message, "text/plain", response.mimeType)
        assertEquals(message, 0, response.data.readBytes().size)
    }

    @Test
    fun `the server's attachment downloads and image proxy are server images`() {
        listOf(
            "$PROXY?url=https%3A%2F%2Fcdn.example.org%2Flogo.png&sig=fictitious",
            PROXY,
            "$ATTACHMENTS/2",
            "$ATTACHMENTS/1.2",
            "$MAIL_ORIGIN/api/v1/mail/messages/0190F3A2-0000-7000-8000-00000000ABCD/attachments/3",
            "https://server.invalid:443/api/v1/mail/image-proxy?url=x",
            "HTTPS://SERVER.INVALID/api/v1/mail/image-proxy?url=x",
        ).forEach { assertTrue(it, isServerImage(Uri.parse(it))) }
    }

    @Test
    fun `other hosts, schemes, ports, user info and paths are not server images`() {
        listOf(
            // Another host, also one that only starts or ends like the server's.
            "https://cdn.example.org/api/v1/mail/image-proxy?url=x",
            "https://server.invalid.example.org/api/v1/mail/image-proxy",
            "https://evil-server.invalid/api/v1/mail/image-proxy",
            "https://server.invalid@evil.example.org/api/v1/mail/image-proxy",
            // Not https, another port, user info.
            "http://server.invalid/api/v1/mail/image-proxy",
            "https://server.invalid:8443/api/v1/mail/image-proxy",
            "https://user@server.invalid/api/v1/mail/image-proxy",
            "https://user:secret@server.invalid/api/v1/mail/image-proxy",
            // Not a network URL.
            "data:image/png;base64,iVBORw0KGgo=",
            "cid:logo@example.org",
            "file:///etc/hosts",
            "about:blank",
            // Other server paths.
            "$MAIL_ORIGIN/",
            "$MAIL_ORIGIN/api/v1/settings",
            "$MAIL_ORIGIN/api/v1/mail/messages/$MESSAGE_ID",
            "$MAIL_ORIGIN/api/v1/mail/messages/$MESSAGE_ID/attachments",
            "$ATTACHMENTS/",
            "$PROXY/extra",
            "$MAIL_ORIGIN/api/v1/mail/image-proxyX",
            "$MAIL_ORIGIN/api/v1/mail/messages/not-a-uuid/attachments/2",
            "$MAIL_ORIGIN/api/v2/mail/image-proxy",
            // Dot segments and encoded slashes that would leave the allowed paths on the server.
            "$ATTACHMENTS/../../../../settings",
            "$ATTACHMENTS/..",
            "$ATTACHMENTS/.hidden",
            "$MAIL_ORIGIN/api/v1/mail/./image-proxy",
            "$MAIL_ORIGIN/api/v1/mail/x/../image-proxy",
            "$ATTACHMENTS/2%2F..%2F..%2Fsettings",
            "$ATTACHMENTS/%2e%2e",
            "$PROXY%2F..%2F..%2Fsettings",
            "$MAIL_ORIGIN/api%2Fv1%2Fmail%2Fimage-proxy",
        ).forEach { assertFalse(it, isServerImage(Uri.parse(it))) }
    }

    @Test
    fun `the page origin is the server's stand-in, without a trailing slash`() {
        assertEquals("https://server.invalid", MAIL_ORIGIN)
        assertEquals("server.invalid", MAIL_HOST)
    }

    @Test
    fun `a server image is fetched from the configured server and passed to the page`() {
        server.enqueue(image(type = "image/png; charset=binary"))

        val response =
            fetcher.response(
                pageRequest(
                    "$PROXY?url=https%3A%2F%2Fcdn.example.org%2Flogo.png&sig=fictitious",
                    headers = mapOf("Cookie" to "session=fictitious", "Referer" to "https://server.invalid/"),
                ),
            )

        assertEquals(200, response.statusCode)
        assertEquals("image/png", response.mimeType)
        assertEquals("fictitious-png-bytes", response.data.use { it.readBytes().decodeToString() })
        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("/api/v1/mail/image-proxy", recorded.url.encodedPath)
        assertEquals("https://cdn.example.org/logo.png", recorded.url.queryParameter("url"))
        assertEquals("fictitious", recorded.url.queryParameter("sig"))
        // The WebView's own headers are not passed on.
        assertNull(recorded.headers["Cookie"])
        assertNull(recorded.headers["Referer"])
    }

    @Test
    fun `an inline attachment is fetched from its download path`() {
        server.enqueue(image(type = "image/gif", body = "fictitious-gif"))

        val response = fetcher.response(pageRequest("$ATTACHMENTS/1.2"))

        assertEquals("image/gif", response.mimeType)
        assertEquals("fictitious-gif", response.data.use { it.readBytes().decodeToString() })
        assertEquals("/api/v1/mail/messages/$MESSAGE_ID/attachments/1.2", server.takeRequest().url.encodedPath)
    }

    @Test
    fun `an answer that is not an image is not passed to the page`() {
        server.enqueue(image(type = "text/html", body = "<script>alert(1)</script>"))
        server.enqueue(image(type = "application/octet-stream"))
        server.enqueue(
            MockResponse
                .Builder()
                .code(200)
                .body("no content type")
                .build(),
        )

        repeat(3) { assertBlocked(fetcher.response(pageRequest("$ATTACHMENTS/2")), "answer $it") }
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `a failed image answer is refused, also when it claims to be an image`() {
        server.enqueue(image(code = 404))
        server.enqueue(image(code = 401))
        server.enqueue(image(code = 502))

        repeat(3) { assertBlocked(fetcher.response(pageRequest("$PROXY?url=x")), "answer $it") }
    }

    @Test
    fun `an unreachable or unconfigured server gives a refused image, not a crash`() {
        val unconfigured = MailImageFetcher(CockpitHttp.okHttpClient(ServerUrlInterceptor { null }, null, null))
        assertBlocked(unconfigured.response(pageRequest("$ATTACHMENTS/2")))

        server.close()
        assertBlocked(fetcher.response(pageRequest("$ATTACHMENTS/2")))
    }

    @Test
    fun `only GET requests are fetched`() {
        listOf("POST", "PUT", "DELETE", "HEAD", "OPTIONS").forEach {
            assertBlocked(fetcher.response(pageRequest("$ATTACHMENTS/2", method = it)), it)
        }
        assertEquals(0, server.requestCount)

        // The method name is compared without case.
        server.enqueue(image())
        val lowerCase = fetcher.response(pageRequest("$ATTACHMENTS/2", method = "get"))
        assertEquals(200, lowerCase.statusCode)
        lowerCase.data.close()
    }

    @Test
    fun `a request to another host or path never reaches the server`() {
        // Without the check the app's client would point any URL at the configured server (with the access token).
        listOf(
            "https://cdn.example.org/api/v1/mail/image-proxy?url=x",
            "https://tracker.example.org/pixel.gif",
            "http://server.invalid/api/v1/mail/messages/$MESSAGE_ID/attachments/2",
            "$MAIL_ORIGIN/api/v1/settings",
            "$ATTACHMENTS/2%2F..%2F..%2F..%2Fsettings",
        ).forEach { assertBlocked(fetcher.response(pageRequest(it)), it) }

        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a server url with a malformed port is refused, not thrown`() {
        // android.net.Uri reports an unparsable port as -1 (no port); the request must still be refused, never crash
        // the page's request thread, and never reach the server.
        val response = fetcher.response(pageRequest("https://server.invalid:abc/api/v1/mail/image-proxy?url=x"))

        assertBlocked(response)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `the web view client answers server images through the fetcher`() {
        server.enqueue(image())
        val view = mailWebView(ApplicationProvider.getApplicationContext(), fetcher) {}

        val response = view.webViewClient.shouldInterceptRequest(view, pageRequest("$ATTACHMENTS/2"))

        assertEquals(200, response?.statusCode)
        assertEquals("image/png", response?.mimeType)
        response?.data?.close()
        assertBlocked(view.webViewClient.shouldInterceptRequest(view, pageRequest("https://tracker.example.org/pixel.gif"))!!)
        assertEquals(1, server.requestCount)
    }

    private companion object {
        const val MESSAGE_ID = "0190f3a2-0000-7000-8000-000000000001"
        const val PROXY = "https://server.invalid/api/v1/mail/image-proxy"
        const val ATTACHMENTS = "https://server.invalid/api/v1/mail/messages/$MESSAGE_ID/attachments"
    }
}
