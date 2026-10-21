package ch.sonensei.cockpit.mail.read

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Settings for untrusted mail HTML (SPEC.md §3 "HTML email is untrusted"): the server already sanitized it, and the
 * WebView adds a second wall: no JavaScript, no file or content access, no network (remote images were removed by
 * the server, so nothing has to be loaded), no popups, no storage.
 */
fun WebSettings.lockDownForMail() {
    javaScriptEnabled = false
    javaScriptCanOpenWindowsAutomatically = false
    allowFileAccess = false
    allowContentAccess = false
    blockNetworkLoads = true
    blockNetworkImage = true
    domStorageEnabled = false
    setGeolocationEnabled(false)
    setSupportMultipleWindows(false)
    // Newsletters are laid out for a desktop width: shown zoomed out to fit, and can be zoomed in.
    useWideViewPort = true
    loadWithOverviewMode = true
    setSupportZoom(true)
    builtInZoomControls = true
    displayZoomControls = false
}

/** Links are never followed inside the view: web and mail links go to [onLink] (another app), the rest is dropped. */
class MailWebViewClient(
    private val onLink: (Uri) -> Unit,
) : WebViewClient() {
    override fun shouldOverrideUrlLoading(
        view: WebView,
        request: WebResourceRequest,
    ): Boolean {
        val url = request.url
        if (url.scheme?.lowercase() in EXTERNAL_SCHEMES) onLink(url)
        return true
    }

    private companion object {
        val EXTERNAL_SCHEMES = setOf("http", "https", "mailto")
    }
}

/** The page around the sanitized HTML; its CSP blocks everything but inline styles and `data:` images. */
fun mailDocument(html: String): String =
    """<!DOCTYPE html><html><head><meta charset="utf-8">""" +
        """<meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; img-src data:">""" +
        """<meta name="viewport" content="width=device-width, initial-scale=1">""" +
        "<style>body{margin:0;padding:12px;overflow-wrap:anywhere;}img{max-width:100%;height:auto;}</style>" +
        "</head><body>$html</body></html>"

/** Creates the WebView of a mail body with [lockDownForMail] settings. */
fun mailWebView(
    context: Context,
    onLink: (Uri) -> Unit,
): WebView =
    WebView(context).apply {
        settings.lockDownForMail()
        webViewClient = MailWebViewClient(onLink)
        // Mails are written for a white page, also in the dark theme.
        setBackgroundColor(Color.WHITE)
    }

/** The sanitized [html] of a mail; tapped links open in the browser or mail app. */
@Composable
fun MailBodyView(
    html: String,
    modifier: Modifier = Modifier,
) {
    val document = remember(html) { mailDocument(html) }
    AndroidView(
        factory = { context -> mailWebView(context) { openExternally(context, it) } },
        update = { view ->
            // Loaded again only when the mail changes, not on every recomposition (that would reset scroll and zoom).
            if (view.tag != document) {
                view.tag = document
                view.loadDataWithBaseURL(null, document, "text/html", "utf-8", null)
            }
        },
        modifier = modifier.testTag(MailReadTags.BODY),
    )
}

private fun openExternally(
    context: Context,
    uri: Uri,
) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        // No app for this link: nothing happens, as in a browser without a handler.
    }
}
