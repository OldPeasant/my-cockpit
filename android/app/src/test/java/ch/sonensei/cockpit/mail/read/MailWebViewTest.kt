package ch.sonensei.cockpit.mail.read

import android.net.Uri
import android.webkit.WebResourceRequest
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertTrue(settings.blockNetworkLoads)
        assertTrue(settings.blockNetworkImage)
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
    fun `the page around the mail allows only inline styles and data images`() {
        val doc = mailDocument("<p>Hello</p>")
        assertTrue(doc.contains("default-src 'none'; style-src 'unsafe-inline'; img-src data:"))
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
