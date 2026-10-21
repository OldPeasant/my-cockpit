package ch.sonensei.cockpit.mail.read

import android.content.Intent
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import androidx.activity.ComponentActivity
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
        assertNull(loaded.baseUrl)
        assertFalse(view.settings.javaScriptEnabled)
        assertFalse(view.settings.allowFileAccess)
        assertTrue(view.settings.blockNetworkLoads)
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
