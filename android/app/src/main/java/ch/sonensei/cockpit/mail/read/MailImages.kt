package ch.sonensei.cockpit.mail.read

import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import ch.sonensei.cockpit.core.network.PLACEHOLDER_BASE_URL
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The origin a mail page is loaded with. It stands for the configured Cockpit server: the server writes the images it
 * serves (inline `cid:` parts, proxied remote images) as paths on its own origin, so they resolve here, and
 * [MailImageFetcher] loads them from the real server with the app's authenticated client. The `.invalid` name never
 * resolves (RFC 2606), so nothing the WebView would load by itself could reach the network.
 */
val MAIL_ORIGIN: String = PLACEHOLDER_BASE_URL.removeSuffix("/")

/**
 * Answers every resource request of a mail page (`WebViewClient.shouldInterceptRequest`): the images of the Cockpit
 * server (attachment downloads and the image proxy) are fetched with the app's [client] (server address and access
 * token); anything else gets an empty 403, so it is never loaded. The WebView's own headers (cookies, referrer) are not
 * passed on, and only image answers reach the page.
 */
@Singleton
class MailImageFetcher
    @Inject
    constructor(
        private val client: OkHttpClient,
    ) {
        fun response(request: WebResourceRequest): WebResourceResponse {
            if (!request.method.equals("GET", ignoreCase = true) || !isServerImage(request.url)) return blockedResponse()
            return try {
                fetch(request.url.toString())
            } catch (_: IOException) {
                blockedResponse()
            }
        }

        private fun fetch(url: String): WebResourceResponse {
            val response =
                client
                    .newCall(
                        Request
                            .Builder()
                            .url(url)
                            .get()
                            .build(),
                    ).execute()
            val type = response.body.contentType()
            if (!response.isSuccessful || type?.type != "image") {
                response.close()
                return blockedResponse()
            }
            // The WebView reads and closes the stream (which closes the response).
            return WebResourceResponse("${type.type}/${type.subtype}", null, response.body.byteStream())
        }
    }

/**
 * Whether [url] is an image the Cockpit server serves for a mail: an attachment download
 * (`/api/v1/mail/messages/{id}/attachments/{partId}`) or the image proxy (`/api/v1/mail/image-proxy`), on [MAIL_ORIGIN].
 */
fun isServerImage(url: Uri): Boolean {
    val origin = Uri.parse(MAIL_ORIGIN)
    val sameOrigin =
        url.scheme.equals(origin.scheme, ignoreCase = true) &&
            url.host.equals(origin.host, ignoreCase = true) &&
            (url.port == -1 || url.port == HTTPS_PORT) &&
            url.encodedUserInfo == null
    if (!sameOrigin) return false
    val path = url.encodedPath ?: return false
    return path == IMAGE_PROXY_PATH || ATTACHMENT_PATH.matches(path)
}

/** An empty 403: the request is refused without touching the network. */
internal fun blockedResponse() =
    WebResourceResponse("text/plain", "utf-8", FORBIDDEN, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))

private const val HTTPS_PORT = 443
private const val FORBIDDEN = 403
private const val IMAGE_PROXY_PATH = "/api/v1/mail/image-proxy"

/** A message id (UUID) and an IMAP part id such as `2` or `1.2`; no escapes and no dot segments. */
private val ATTACHMENT_PATH = Regex("/api/v1/mail/messages/[0-9a-fA-F-]{36}/attachments/[0-9A-Za-z][0-9A-Za-z._-]*")
