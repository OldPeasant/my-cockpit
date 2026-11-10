package ch.sonensei.cockpit.mail.read

import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.core.network.CockpitHttp
import ch.sonensei.cockpit.core.network.ServerUrlInterceptor
import io.mockk.mockk
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The WebView of a mail body: no JavaScript, no file or content access, no network; links leave the view. */
@RunWith(AndroidJUnit4::class)
class MailWebViewTest {
    private val opened = mutableListOf<Uri>()
    private val view = mailWebView(ApplicationProvider.getApplicationContext()) { opened += it }

    @Test
    fun `the settings lock the untrusted html down`() {
        val settings = view.settings
        assertFalse(settings.javaScriptEnabled)
        assertFalse(settings.javaScriptCanOpenWindowsAutomatically)
        assertFalse(settings.allowFileAccess)
        assertFalse(settings.allowContentAccess)
        assertFalse(settings.blockNetworkLoads)
        assertFalse(settings.blockNetworkImage)
        assertFalse(settings.domStorageEnabled)
        assertFalse(settings.supportMultipleWindows())
    }

    @Test
    fun `web and mail links go to another app, nothing loads inside the view`() {
        val client = view.webViewClient
        listOf(
            "https://example.org/a",
            "http://example.org",
            "mailto:anna@example.org",
            "file:///etc/hosts",
            "javascript:alert(1)",
            "intent://x",
        ).forEach { assertTrue(it, client.shouldOverrideUrlLoading(view, request(it))) }

        assertEquals(listOf("https://example.org/a", "http://example.org", "mailto:anna@example.org"), opened.map { it.toString() })
    }

    @Test
    fun `the page around the mail allows only inline styles and images from the server or data`() {
        val doc = mailDocument("<p>Hello</p>")
        assertTrue(doc.contains("default-src 'none'; style-src 'unsafe-inline'; img-src https://server.invalid data:"))
        assertTrue(doc.contains("<body><p>Hello</p></body>"))
    }

    @Test
    fun `links the page follows by itself or in a frame are dropped`() {
        val client = view.webViewClient
        val url = "https://example.org/track"
        assertTrue(client.shouldOverrideUrlLoading(view, request(url, gesture = false)))
        assertTrue(client.shouldOverrideUrlLoading(view, request(url, mainFrame = false)))
        assertTrue(client.shouldOverrideUrlLoading(view, request("mailto:anna@example.org", gesture = false, mainFrame = false)))

        assertEquals(emptyList<Uri>(), opened)
    }

    @Test
    fun `a tapped link with an upper-case scheme still leaves the view, one without a scheme is dropped`() {
        val client = view.webViewClient
        assertTrue(client.shouldOverrideUrlLoading(view, request("HTTPS://Example.org/Path")))
        assertTrue(client.shouldOverrideUrlLoading(view, request("MailTo:anna@example.org")))
        assertTrue(client.shouldOverrideUrlLoading(view, request("relative/path")))

        assertEquals(listOf("HTTPS://Example.org/Path", "MailTo:anna@example.org"), opened.map { it.toString() })
    }

    @Test
    fun `the page's directives allow images only from the server origin and data, and nothing else`() {
        val csp =
            Regex("""http-equiv="Content-Security-Policy" content="([^"]*)"""")
                .find(mailDocument("<p>Hello</p>"))
                ?.groupValues
                ?.get(1)
        assertNotNull(csp)
        val directives =
            csp!!
                .split(';')
                .map { it.trim().split(' ').filter(String::isNotEmpty) }
                .filter { it.isNotEmpty() }
                .associate { it.first() to it.drop(1) }

        assertEquals(setOf("default-src", "style-src", "img-src"), directives.keys)
        assertEquals(listOf("'none'"), directives["default-src"])
        assertEquals(listOf("'unsafe-inline'"), directives["style-src"])
        assertEquals(listOf(MAIL_ORIGIN, "data:"), directives["img-src"])
    }

    @Test
    fun `a request to another host is blocked, also with an image fetcher`() {
        // A strict mock: any network call through the app's client would fail the test.
        val fetcher = MailImageFetcher(mockk<OkHttpClient>())
        val withImages = mailWebView(ApplicationProvider.getApplicationContext(), fetcher) { opened += it }
        listOf(
            "https://tracker.example.org/pixel.gif",
            "https://cdn.example.org/api/v1/mail/image-proxy?url=x",
            "http://server.invalid/api/v1/mail/image-proxy",
            "https://server.invalid/api/v1/settings",
            "https://server.invalid/style.css",
            "file:///etc/hosts",
            "content://ch.sonensei.cockpit.files/x",
            "javascript:alert(1)",
        ).forEach {
            val response = withImages.webViewClient.shouldInterceptRequest(withImages, pageRequest(it))
            assertNotNull(it, response)
            assertEquals(it, 403, response!!.statusCode)
            assertEquals(it, 0, response.data.readBytes().size)
        }
    }

    @Test
    fun `the mail document and data images are left to the web view, with and without an image fetcher`() {
        // A strict mock: any network call through the app's client would fail the test.
        val withImages = mailWebView(ApplicationProvider.getApplicationContext(), MailImageFetcher(mockk<OkHttpClient>())) {}
        val document = "data:text/html;charset=utf-8;base64,PGh0bWw+PC9odG1sPg=="
        listOf(withImages, view).forEach { webView ->
            val client = webView.webViewClient
            assertNull(document, client.shouldInterceptRequest(webView, request(document, gesture = false)))
            listOf("data:image/png;base64,iVBORw0KGgo=", "DATA:image/gif;base64,R0lGODlh").forEach {
                assertNull(it, client.shouldInterceptRequest(webView, pageRequest(it)))
            }
        }
    }

    @Test
    fun `a scheme that only starts with data is still blocked`() {
        // A strict mock: any network call through the app's client would fail the test.
        val withImages = mailWebView(ApplicationProvider.getApplicationContext(), MailImageFetcher(mockk<OkHttpClient>())) {}
        listOf("database:x", "datax:image/png;base64,iVBORw0KGgo=", "https://server.invalid/data:image/png").forEach {
            val response = withImages.webViewClient.shouldInterceptRequest(withImages, pageRequest(it))
            assertEquals(it, 403, response?.statusCode)
        }
    }

    @Test
    fun `server images still go through the fetcher, other server paths and non-GET requests never reach it`() {
        val server = MockWebServer()
        server.start()
        try {
            val fetcher = MailImageFetcher(CockpitHttp.okHttpClient(ServerUrlInterceptor { server.url("/") }, null, null))
            val withImages = mailWebView(ApplicationProvider.getApplicationContext(), fetcher) {}
            val client = withImages.webViewClient
            val attachment = "$MAIL_ORIGIN/api/v1/mail/messages/0190f3a2-0000-7000-8000-000000000001/attachments/2"
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .addHeader("Content-Type", "image/png")
                    .body("fictitious-png-bytes")
                    .build(),
            )

            val image = client.shouldInterceptRequest(withImages, pageRequest(attachment))
            assertEquals(200, image?.statusCode)
            assertEquals("fictitious-png-bytes", image!!.data.use { it.readBytes().decodeToString() })
            assertEquals(1, server.requestCount)

            listOf(
                pageRequest("$MAIL_ORIGIN/api/v1/settings"),
                pageRequest("$MAIL_ORIGIN/"),
                pageRequest("$MAIL_ORIGIN/api/v1/mail/messages/0190f3a2-0000-7000-8000-000000000001"),
                pageRequest(attachment, method = "POST"),
                pageRequest("$MAIL_ORIGIN/api/v1/mail/image-proxy?url=x", method = "DELETE"),
            ).forEach {
                val response = client.shouldInterceptRequest(withImages, it)
                assertEquals("${it.method} ${it.url}", 403, response?.statusCode)
                assertEquals("${it.method} ${it.url}", 0, response!!.data.readBytes().size)
            }
            assertEquals(1, server.requestCount)
        } finally {
            server.close()
        }
    }

    @Test
    fun `without an image fetcher every request is blocked, also the server's images`() {
        listOf(
            "https://server.invalid/api/v1/mail/messages/0190f3a2-0000-7000-8000-000000000001/attachments/2",
            "https://server.invalid/api/v1/mail/image-proxy?url=x",
            "https://tracker.example.org/pixel.gif",
        ).forEach {
            val response = view.webViewClient.shouldInterceptRequest(view, pageRequest(it))
            assertEquals(it, 403, response?.statusCode)
        }
    }

    @Test
    fun `a tapped link to the server's stand-in origin is not opened`() {
        val client = view.webViewClient
        assertTrue(client.shouldOverrideUrlLoading(view, request("https://server.invalid/api/v1/mail/image-proxy?url=x")))
        assertTrue(client.shouldOverrideUrlLoading(view, request("HTTPS://SERVER.INVALID/somewhere")))
        assertTrue(client.shouldOverrideUrlLoading(view, request("http://server.invalid/")))

        assertEquals(emptyList<Uri>(), opened)
    }

    @Test
    fun `wide newsletters stay fitted to the width and zoomable`() {
        val settings = view.settings
        assertTrue(settings.useWideViewPort)
        assertTrue(settings.loadWithOverviewMode)
        assertTrue(settings.supportZoom())
        assertTrue(settings.builtInZoomControls)
        assertFalse(settings.displayZoomControls)
    }

    @Test
    fun `the web view is as tall as its content and does not scroll vertically by itself`() {
        val params = view.layoutParams
        assertNotNull(params)
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, params.width)
        assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, params.height)
        assertFalse(view.isVerticalScrollBarEnabled)
        assertEquals(View.OVER_SCROLL_NEVER, view.overScrollMode)
    }

    @Test
    fun `the body height is the content height, but not less than a kept height`() {
        assertEquals(1200, mailBodyHeight(measured = 1200, kept = 0))
        assertEquals(800, mailBodyHeight(measured = 300, kept = 800))
        assertEquals(1500, mailBodyHeight(measured = 1500, kept = 800))
        assertEquals(800, mailBodyHeight(measured = 800, kept = 800))
        assertEquals(0, mailBodyHeight(measured = 0, kept = 0))
    }

    @Test
    fun `a kept height holds the web view's height in a scrolling page until the page finished`() {
        val unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        val width = View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY)
        view.measure(width, unspecified)
        val content = view.measuredHeight
        view.layout(0, 0, 400, content + 900)
        layOutWebView(view, content + 900)
        assertEquals(0, view.keptHeight)

        view.keepHeight()
        assertEquals(content + 900, view.keptHeight)
        // The reload asks for a new layout (on a device Chromium does); without it View reuses the cached measure.
        view.forceLayout()
        view.measure(width, unspecified)
        assertEquals(content + 900, view.measuredHeight)

        view.layout(0, 0, 400, content + 900)
        assertFalse(view.isLayoutRequested)
        view.webViewClient.onPageFinished(view, "data:text/html")
        assertEquals(0, view.keptHeight)
        assertTrue("the content height counts again", view.isLayoutRequested)
        view.measure(width, unspecified)
        assertEquals(content, view.measuredHeight)
    }

    @Test
    fun `a kept height never overrides a height the parent fixes`() {
        val width = View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY)
        layOutWebView(view, 900)
        view.keepHeight()
        assertEquals(900, view.keptHeight)

        view.measure(width, View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY))
        assertEquals(300, view.measuredHeight)
        view.measure(width, View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.AT_MOST))
        assertTrue(view.measuredHeight <= 300)
    }

    @Test
    fun `a finished page without a kept height asks for no new layout`() {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        view.layout(0, 0, 400, 700)
        assertFalse(view.isLayoutRequested)

        view.pageFinished()

        assertFalse(view.isLayoutRequested)
        assertEquals(0, view.keptHeight)
    }

    @Test
    fun `a finished page in a plain web view is ignored`() {
        val plain = WebView(ApplicationProvider.getApplicationContext())
        // Must not fail: only a MailWebView keeps heights.
        MailWebViewClient(null) { opened += it }.onPageFinished(plain, "data:text/html")
        assertEquals(emptyList<Uri>(), opened)
    }

    private fun request(
        url: String,
        gesture: Boolean = true,
        mainFrame: Boolean = true,
    ) = object : WebResourceRequest {
        override fun getUrl(): Uri = Uri.parse(url)

        override fun isForMainFrame() = mainFrame

        override fun isRedirect() = false

        override fun hasGesture() = gesture

        override fun getMethod() = "GET"

        override fun getRequestHeaders(): Map<String, String> = emptyMap()
    }
}
