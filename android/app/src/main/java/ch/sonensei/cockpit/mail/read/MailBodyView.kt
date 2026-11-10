package ch.sonensei.cockpit.mail.read

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.wrapContentHeight
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

    /** The page has its layout: a [MailWebView] may now shrink to it (see [MailWebView.keepHeight]). */
    override fun onPageFinished(
        view: WebView,
        url: String?,
    ) {
        (view as? MailWebView)?.pageFinished()
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

/**
 * The height a [MailWebView] takes: the [measured] height of its content, but not less than [kept] while a reload of the
 * same mail (e.g. with its images) has not laid out yet, so the page around it keeps its scroll position.
 */
internal fun mailBodyHeight(
    measured: Int,
    kept: Int,
): Int = maxOf(measured, kept)

/**
 * The WebView of a mail body, as tall as its content: laid out with a wrap-content height in a scrolling page, the
 * WebView measures itself to the rendered content (and measures again when images load or the zoom changes), so it
 * never scrolls vertically by itself. [keepHeight] holds the current height across a reload until [pageFinished]
 * (or [forgetHeight], when another mail loads).
 */
class MailWebView(
    context: Context,
) : WebView(context) {
    /** The height kept while the next page loads; 0 for none. */
    internal var keptHeight = 0
        private set

    /** Before reloading the same mail: keep the current height so the page does not jump back to the top. */
    fun keepHeight() {
        keptHeight = height
    }

    /** Before loading another mail: drop a height kept for the previous one, so the new mail starts at its own. */
    fun forgetHeight() {
        if (keptHeight != 0) {
            keptHeight = 0
            requestLayout()
        }
    }

    /** The reloaded page has its layout: from now on the content height alone counts. */
    fun pageFinished() {
        if (keptHeight != 0) {
            keptHeight = 0
            requestLayout()
        }
    }

    override fun onMeasure(
        widthMeasureSpec: Int,
        heightMeasureSpec: Int,
    ) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val height = mailBodyHeight(measuredHeight, keptHeight)
        if (height != measuredHeight && MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            setMeasuredDimension(measuredWidth, height)
        }
    }
}

/** Creates the WebView of a mail body with [lockDownForMail] settings; [images] loads the server's images. */
fun mailWebView(
    context: Context,
    images: MailImageFetcher? = null,
    onLink: (Uri) -> Unit,
): MailWebView =
    MailWebView(context).apply {
        settings.lockDownForMail()
        webViewClient = MailWebViewClient(images, onLink)
        // Mails are written for a white page, also in the dark theme.
        setBackgroundColor(Color.WHITE)
        // As tall as its content (the page around it scrolls): no vertical scrolling or scroll bar of its own.
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        isVerticalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
    }

/**
 * The sanitized [html] of a mail at its full content height (it belongs in a vertically scrolling page); tapped links
 * open in the browser or mail app. Its images (inline parts, proxied remote images) load through [images]; without it
 * none load. [key] names the mail: when only its [html] changes (images loaded), the height is kept while it reloads.
 */
@Composable
fun MailBodyView(
    html: String,
    modifier: Modifier = Modifier,
    images: MailImageFetcher? = null,
    key: Any? = null,
) {
    val document = remember(html) { mailDocument(html) }
    AndroidView(
        factory = { context -> mailWebView(context, images) { openExternally(context, it) } },
        update = { view ->
            // Loaded again only when the mail changes, not on every recomposition (that would reset scroll and zoom).
            val loaded = view.tag as? LoadedMail
            if (loaded?.document != document) {
                if (loaded != null && loaded.key == key) view.keepHeight() else view.forgetHeight()
                view.tag = LoadedMail(key, document)
                // On the server's stand-in origin, so the server's image paths resolve and pass the CSP.
                view.loadDataWithBaseURL("$MAIL_ORIGIN/", document, "text/html", "utf-8", null)
            }
        },
        modifier = modifier.wrapContentHeight().testTag(MailReadTags.BODY),
    )
}

/** What a mail body's WebView shows: the document of the mail named [key]. */
private data class LoadedMail(
    val key: Any?,
    val document: String,
)

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
