package ch.sonensei.cockpit.mail.read

import android.net.Uri
import android.webkit.WebResourceRequest
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.mockk
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
