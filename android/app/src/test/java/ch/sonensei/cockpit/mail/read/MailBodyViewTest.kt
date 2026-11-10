package ch.sonensei.cockpit.mail.read

import android.content.Intent
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/** The composable around the WebView: the mail goes in wrapped in its CSP page, and tapped web links leave the app. */
@RunWith(AndroidJUnit4::class)
class MailBodyViewTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun webView(): WebView {
        lateinit var found: WebView
        composeRule.runOnUiThread { found = find(composeRule.activity.window.decorView)!! }
        return found
    }

    private fun find(view: View): WebView? =
        when (view) {
            is WebView -> view
            is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { find(view.getChildAt(it)) }
            else -> null
        }

    @Test
    fun `the mail is loaded inside its locked-down page`() {
        composeRule.setContent { MailBodyView("<p>Hello Anna</p>") }
        composeRule.waitForIdle()

        val view = webView()
        val loaded = shadowOf(view).lastLoadDataWithBaseURL
        assertNotNull(loaded)
        assertEquals(mailDocument("<p>Hello Anna</p>"), loaded.data)
        assertEquals("https://server.invalid/", loaded.baseUrl)
        assertFalse(view.settings.javaScriptEnabled)
        assertFalse(view.settings.allowFileAccess)
        assertTrue(view.webViewClient is MailWebViewClient)
    }

    @Test
    fun `a tapped web link opens in another app, a file link does nothing`() {
        composeRule.setContent { MailBodyView("<a href=\"https://example.org/offer\">Offer</a>") }
        composeRule.waitForIdle()
        val view = webView()
        val app = shadowOf(composeRule.activity.application)

        composeRule.runOnUiThread {
            assertTrue(view.webViewClient.shouldOverrideUrlLoading(view, request("file:///data/data/secret")))
        }
        assertNull(app.nextStartedActivity)

        composeRule.runOnUiThread {
            assertTrue(view.webViewClient.shouldOverrideUrlLoading(view, request("https://example.org/offer")))
        }
        val started = app.nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals(Uri.parse("https://example.org/offer"), started.data)
        assertTrue(started.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    /** Shows [MailBodyView] with the html and key the test changes; returns its web view, given a laid-out height. */
    private fun showChanging(
        html: MutableState<String>,
        key: MutableState<Any?>,
    ): MailWebView {
        composeRule.setContent { MailBodyView(html.value, key = key.value) }
        composeRule.waitForIdle()
        val view = webView() as MailWebView
        // Robolectric renders no content: give the view the height a long mail would have on a device.
        composeRule.runOnUiThread { layOutWebView(view, 1800) }
        return view
    }

    @Test
    fun `the body is a web view as tall as its content, without its own vertical scroll`() {
        composeRule.setContent { MailBodyView("<p>Hello Anna</p>") }
        composeRule.waitForIdle()

        val view = webView()
        assertTrue(view is MailWebView)
        assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, view.layoutParams.height)
        assertFalse(view.isVerticalScrollBarEnabled)
        assertEquals(View.OVER_SCROLL_NEVER, view.overScrollMode)
    }

    @Test
    fun `the same mail reloaded with its images keeps its height until the page finished`() {
        val html = mutableStateOf("<p>Hello Anna</p>")
        val key = mutableStateOf<Any?>("mail-1")
        val view = showChanging(html, key)
        assertEquals(0, view.keptHeight)

        html.value = "<p>Hello Anna</p><img src=\"/api/v1/mail/image-proxy?url=x\">"
        composeRule.waitForIdle()

        assertEquals(mailDocument(html.value), shadowOf(view).lastLoadDataWithBaseURL.data)
        assertEquals(1800, view.keptHeight)
        composeRule.runOnUiThread { view.webViewClient.onPageFinished(view, "data:text/html") }
        assertEquals(0, view.keptHeight)
    }

    @Test
    fun `another mail starts at its own height`() {
        val html = mutableStateOf("<p>Hello Anna</p>")
        val key = mutableStateOf<Any?>("mail-1")
        val view = showChanging(html, key)

        Snapshot.withMutableSnapshot {
            key.value = "mail-2"
            html.value = "<p>Hello Ben</p>"
        }
        composeRule.waitForIdle()

        assertEquals(mailDocument("<p>Hello Ben</p>"), shadowOf(view).lastLoadDataWithBaseURL.data)
        assertEquals(0, view.keptHeight)
    }

    @Test
    fun `a recomposition with the same document neither reloads nor keeps a height`() {
        val html = mutableStateOf("<p>Hello Anna</p>")
        val key = mutableStateOf<Any?>("mail-1")
        val view = showChanging(html, key)
        val first = shadowOf(view).lastLoadDataWithBaseURL

        key.value = "mail-1b"
        composeRule.waitForIdle()

        assertTrue(first === shadowOf(view).lastLoadDataWithBaseURL)
        assertEquals(0, view.keptHeight)
    }

    private fun request(url: String) =
        object : WebResourceRequest {
            override fun getUrl(): Uri = Uri.parse(url)

            override fun isForMainFrame() = true

            override fun isRedirect() = false

            override fun hasGesture() = true

            override fun getMethod() = "GET"

            override fun getRequestHeaders(): Map<String, String> = emptyMap()
        }
}
