package ch.sonensei.cockpit.calendar.infra.caldav

import ch.sonensei.cockpit.calendar.sync.CalendarProviderException
import org.w3c.dom.Element
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import java.io.IOException
import java.io.StringReader
import java.net.URI
import java.net.URISyntaxException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.ParserConfigurationException

/**
 * WebDAV/CalDAV requests to one server with HTTP Basic authentication. Requests only go to the origin (scheme, host,
 * port) of the source's URL, redirects included, so the password never reaches another host. Network failures and
 * 401/403/5xx answers that the adapter does not handle become [CalendarProviderException]s whose messages hold no
 * password, no response body and no event content.
 */
internal class CalDavClient(
	val base: URI,
	username: String?,
	password: String?,
	private val http: HttpClient,
) {
	private val authorization: String? =
		username?.let { "Basic " + Base64.getEncoder().encodeToString("$it:${password.orEmpty()}".toByteArray(Charsets.UTF_8)) }

	class Response(
		val status: Int,
		val body: String,
		private val headers: java.net.http.HttpHeaders,
	) {
		val ok: Boolean get() = status in 200..299

		fun header(name: String): String? = headers.firstValue(name).orElse(null)
	}

	/** [href] (an encoded path or a full URI, as WebDAV sends it) as a URI at the server; null when it is elsewhere. */
	fun resolve(href: String): URI? {
		val uri =
			try {
				base.resolve(URI(href.trim().replace(" ", "%20")))
			} catch (e: URISyntaxException) {
				null
			} catch (e: IllegalArgumentException) {
				null
			}
		return uri?.takeIf { it.scheme != null && it.host != null && sameOrigin(it) }
	}

	private fun sameOrigin(uri: URI): Boolean =
		uri.scheme.equals(base.scheme, ignoreCase = true) &&
			uri.host.equals(base.host, ignoreCase = true) &&
			port(uri) == port(base)

	private fun port(uri: URI): Int =
		if (uri.port >= 0) {
			uri.port
		} else if (uri.scheme.equals("https", ignoreCase = true)) {
			HTTPS_PORT
		} else {
			HTTP_PORT
		}

	/**
	 * Sends [method] to [uri] with [body] and [headers]; follows redirects within the server's origin. 401 and 5xx
	 * answers and network failures throw.
	 */
	fun send(
		method: String,
		uri: URI,
		body: String? = null,
		contentType: String = XML,
		headers: Map<String, String> = emptyMap(),
		what: String,
	): Response {
		var target = uri
		repeat(MAX_REDIRECTS + 1) {
			if (!sameOrigin(target)) throw CalendarProviderException("$what: the CalDAV server pointed to another host")
			val request =
				HttpRequest
					.newBuilder(target)
					.timeout(READ_TIMEOUT)
					.method(method, body?.let { HttpRequest.BodyPublishers.ofString(it, Charsets.UTF_8) } ?: HttpRequest.BodyPublishers.noBody())
					.apply {
						if (body != null) header("Content-Type", contentType)
						authorization?.let { header("Authorization", it) }
						headers.forEach { (k, v) -> header(k, v) }
					}.build()
			val response =
				try {
					http.send(request, HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))
				} catch (e: IOException) {
					throw CalendarProviderException("$what: the CalDAV server could not be reached", e)
				} catch (e: InterruptedException) {
					Thread.currentThread().interrupt()
					throw CalendarProviderException("$what: interrupted", e)
				}
			val status = response.statusCode()
			if (status in REDIRECTS) {
				val location = response.headers().firstValue("Location").orElse(null)
				target =
					location?.let { runCatching { target.resolve(it) }.getOrNull() }
						?: throw CalendarProviderException("$what: the CalDAV server redirected without a target")
				return@repeat
			}
			when {
				status == 401 -> throw CalendarProviderException("$what: the CalDAV server rejected the user name or password")
				status >= 500 -> throw CalendarProviderException("$what failed at the CalDAV server (HTTP $status)")
			}
			return Response(status, response.body().orEmpty(), response.headers())
		}
		throw CalendarProviderException("$what: the CalDAV server redirected too often")
	}

	/** PROPFIND of [props] (XML elements, prefixes `D`, `C`, `CS`, `A`) on [uri] with [depth]. */
	fun propfind(
		uri: URI,
		depth: Int,
		props: String,
		what: String,
	): Response =
		send(
			"PROPFIND",
			uri,
			"""<?xml version="1.0" encoding="utf-8"?><D:propfind $NAMESPACES><D:prop>$props</D:prop></D:propfind>""",
			headers = mapOf("Depth" to depth.toString()),
			what = what,
		)

	/** REPORT [body] on [uri] with [depth]. */
	fun report(
		uri: URI,
		depth: Int,
		body: String,
		what: String,
	): Response = send("REPORT", uri, body, headers = mapOf("Depth" to depth.toString()), what = what)

	companion object {
		const val DAV = "DAV:"
		const val CALDAV = "urn:ietf:params:xml:ns:caldav"
		const val CALENDARSERVER = "http://calendarserver.org/ns/"
		const val APPLE = "http://apple.com/ns/ical/"
		const val NAMESPACES = """xmlns:D="DAV:" xmlns:C="$CALDAV" xmlns:CS="$CALENDARSERVER" xmlns:A="$APPLE""""
		const val XML = "application/xml; charset=utf-8"
		const val ICS = "text/calendar; charset=utf-8"

		private val REDIRECTS = setOf(301, 302, 303, 307, 308)
		private const val MAX_REDIRECTS = 5
		private const val HTTP_PORT = 80
		private const val HTTPS_PORT = 443
		val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(10)
		private val READ_TIMEOUT = Duration.ofSeconds(60)

		fun httpClient(): HttpClient =
			HttpClient
				.newBuilder()
				.connectTimeout(CONNECT_TIMEOUT)
				.followRedirects(HttpClient.Redirect.NEVER)
				.build()
	}
}

/** One `response` of a WebDAV multistatus: its [href], its own [status] (e.g. 404: removed) and its found properties. */
internal class DavResponse(
	val href: String,
	val status: Int?,
	private val props: List<Element>,
) {
	fun prop(
		namespace: String,
		name: String,
	): Element? = props.firstOrNull { it.namespaceURI == namespace && it.localName == name }

	fun text(
		namespace: String,
		name: String,
	): String? = prop(namespace, name)?.textContent?.trim()?.ifEmpty { null }
}

/** A parsed WebDAV `multistatus` (RFC 4918), with the `sync-token` of a sync-collection REPORT (RFC 6578). */
internal class MultiStatus(
	val responses: List<DavResponse>,
	val syncToken: String?,
) {
	companion object {
		private val factory: DocumentBuilderFactory =
			DocumentBuilderFactory.newInstance().apply {
				isNamespaceAware = true
				// No DTDs, no external entities (XXE).
				setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
				setFeature("http://xml.org/sax/features/external-general-entities", false)
				setFeature("http://xml.org/sax/features/external-parameter-entities", false)
				setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
				setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
				isXIncludeAware = false
				isExpandEntityReferences = false
			}

		/** @throws CalendarProviderException when [xml] is no multistatus */
		fun parse(
			xml: String,
			what: String,
		): MultiStatus {
			val root =
				try {
					factory.newDocumentBuilder().parse(InputSource(StringReader(xml))).documentElement
				} catch (e: SAXException) {
					throw CalendarProviderException("$what: the CalDAV server answered with invalid XML", e)
				} catch (e: IOException) {
					throw CalendarProviderException("$what: the CalDAV server answered with invalid XML", e)
				} catch (e: ParserConfigurationException) {
					throw IllegalStateException(e)
				}
			if (root.namespaceURI != CalDavClient.DAV || root.localName != "multistatus") {
				throw CalendarProviderException("$what: the CalDAV server answered without a multistatus")
			}
			val responses =
				root.children(CalDavClient.DAV, "response").mapNotNull { response ->
					val href = response.children(CalDavClient.DAV, "href").firstOrNull()?.textContent?.trim() ?: return@mapNotNull null
					val status = response.children(CalDavClient.DAV, "status").firstOrNull()?.let { statusCode(it.textContent) }
					val props =
						response
							.children(CalDavClient.DAV, "propstat")
							.filter { propstat ->
								val code = propstat.children(CalDavClient.DAV, "status").firstOrNull()?.let { statusCode(it.textContent) }
								code == null || code in 200..299
							}.flatMap { it.children(CalDavClient.DAV, "prop") }
							.flatMap { it.elements() }
					DavResponse(href, status, props)
				}
			val token = root.children(CalDavClient.DAV, "sync-token").firstOrNull()?.textContent?.trim()?.ifEmpty { null }
			return MultiStatus(responses, token)
		}

		/** The code of an HTTP status line (`HTTP/1.1 404 Not Found`). */
		private fun statusCode(line: String): Int? =
			line
				.trim()
				.split(' ')
				.getOrNull(1)
				?.toIntOrNull()
	}
}

internal fun Element.elements(): List<Element> {
	val nodes = childNodes
	return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
}

internal fun Element.children(
	namespace: String,
	name: String,
): List<Element> = elements().filter { it.namespaceURI == namespace && it.localName == name }
