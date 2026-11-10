package ch.sonensei.cockpit.mail.read

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
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
 * WebView adds a second wall: no JavaScript, no file or content access, no popups, no storage. Network loads stay on
 * only so the page asks for its images at all: [MailWebViewClient] answers every network request itself (the server's
 * images through [MailImageFetcher], everything else refused; `data:` URLs, which never touch the network, are left to
 * the WebView), so the WebView never fetches anything from the network on its own.
 */
fun WebSettings.lockDownForMail() {
    javaScriptEnabled = false
    javaScriptCanOpenWindowsAutomatically = false
    allowFileAccess = false
    allowContentAccess = false
    blockNetworkLoads = false
    blockNetworkImage = false
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

/**
 * Links are never followed inside the view: web and mail links the user tapped in the page go to [onLink] (another
 * app); the rest, including navigations the page starts by itself or in a frame and links to [MAIL_ORIGIN], is dropped.
 * `data:` requests (the mail document and its inline images) are left to the WebView; other resource requests are
 * answered by [images] (the server's images); without it, or for anything else, they are refused.
 */
class MailWebViewClient(
    private val images: MailImageFetcher?,
    private val onLink: (Uri) -> Unit,
) : WebViewClient() {
    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest,
    ): WebResourceResponse? =
        when {
            // The mail document itself (loadDataWithBaseURL is a data: load) and data: images the CSP allows are
            // handled by the WebView locally; refusing them replaces the page with an error page.
            request.url.scheme.equals("data", ignoreCase = true) -> null
            else -> images?.response(request) ?: blockedResponse()
        }

    override fun shouldOverrideUrlLoading(
        view: WebView,
        request: WebResourceRequest,
    ): Boolean {
        val url = request.url
        val external = url.scheme?.lowercase() in EXTERNAL_SCHEMES && !url.host.equals(MAIL_HOST, ignoreCase = true)
        if (request.hasGesture() && request.isForMainFrame && external) onLink(url)
        return true
    }

    private companion object {
        val EXTERNAL_SCHEMES = setOf("http", "https", "mailto")
    }
}

/**
 * The page around the sanitized HTML; its CSP blocks everything but inline styles and images from the server
 * ([MAIL_ORIGIN]) or `data:`.
 */
fun mailDocument(html: String): String =
    """<!DOCTYPE html><html><head><meta charset="utf-8">""" +
        """<meta http-equiv="Content-Security-Policy" """ +
        """content="default-src 'none'; style-src 'unsafe-inline'; img-src $MAIL_ORIGIN data:">""" +
        """<meta name="viewport" content="width=device-width, initial-scale=1">""" +
        "<style>body{margin:0;padding:12px;overflow-wrap:anywhere;}img{max-width:100%;height:auto;}</style>" +
        "</head><body>$html</body></html>"

/** Creates the WebView of a mail body with [lockDownForMail] settings; [images] loads the server's images. */
fun mailWebView(
    context: Context,
    images: MailImageFetcher? = null,
    onLink: (Uri) -> Unit,
): WebView =
    WebView(context).apply {
        settings.lockDownForMail()
        webViewClient = MailWebViewClient(images, onLink)
        // Mails are written for a white page, also in the dark theme.
        setBackgroundColor(Color.WHITE)
    }

/**
 * The sanitized [html] of a mail; tapped links open in the browser or mail app. Its images (inline parts, proxied remote
 * images) load through [images]; without it none load.
 */
@Composable
fun MailBodyView(
    html: String,
    modifier: Modifier = Modifier,
    images: MailImageFetcher? = null,
) {
    val document = remember(html) { mailDocument(html) }
    AndroidView(
        factory = { context -> mailWebView(context, images) { openExternally(context, it) } },
        update = { view ->
            // Loaded again only when the mail changes, not on every recomposition (that would reset scroll and zoom).
            if (view.tag != document) {
                view.tag = document
                // On the server's stand-in origin, so the server's image paths resolve and pass the CSP.
                view.loadDataWithBaseURL("$MAIL_ORIGIN/", document, "text/html", "utf-8", null)
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
