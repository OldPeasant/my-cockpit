package ch.sonensei.cockpit.calendar.infra.ics

import ch.sonensei.cockpit.calendar.sync.CalendarProviderException
import ch.sonensei.cockpit.mail.api.HostResolver
import ch.sonensei.cockpit.mail.api.PublicAddresses
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.URI
import java.net.URISyntaxException
import java.net.UnknownHostException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.charset.Charset
import java.nio.charset.IllegalCharsetNameException
import java.nio.charset.UnsupportedCharsetException
import java.util.zip.GZIPInputStream

/** A feed's validators from its last download, sent back to download it only when it changed. */
internal data class FeedValidators(
	val etag: String? = null,
	val lastModified: String? = null,
)

/** The result of a feed request: [NotModified] (validators still current) or the feed's [text] and new validators. */
internal sealed interface FeedResponse {
	data object NotModified : FeedResponse

	data class Fetched(
		val text: String,
		val validators: FeedValidators,
	) : FeedResponse
}

/**
 * Downloads ICS feeds over http(s) with conditional requests (`If-None-Match`/`If-Modified-Since`) and SSRF
 * protection: the host of the URL and of every redirect is resolved, and unless it is allowed in [IcsFeedProperties]
 * all of its addresses must be public ([PublicAddresses]: no loopback, private, link-local, ... addresses). URLs with
 * credentials are refused. The HTTP client connects to the host by name again; it gets the address just checked from
 * the JVM's name cache, which narrows (but cannot close) the window for DNS rebinding.
 *
 * Messages of the [CalendarProviderException]s hold no URL (feed URLs often carry a secret token) and no response body.
 */
internal class IcsFeedClient(
	private val properties: IcsFeedProperties,
	private val resolver: HostResolver = HostResolver { host -> InetAddress.getAllByName(host).toList() },
) {
	private val http: HttpClient =
		HttpClient
			.newBuilder()
			.connectTimeout(properties.connectTimeout)
			.followRedirects(HttpClient.Redirect.NEVER)
			.build()
	private val privateHosts = properties.allowedPrivateHosts.map { it.trim().lowercase() }.toSet()

	fun fetch(
		url: String,
		validators: FeedValidators?,
	): FeedResponse {
		var target = target(url)
		repeat(properties.maxRedirects + 1) {
			checkAddresses(target)
			val response = send(target, validators)
			response.body().use { body ->
				val status = response.statusCode()
				when {
					status in REDIRECTS -> {
						val location =
							response.headers().firstValue("Location").orElse(null)
								?: throw CalendarProviderException("The feed server sent a redirect without a target (HTTP $status)")
						target = redirect(target, location)
					}
					status == HTTP_NOT_MODIFIED -> return FeedResponse.NotModified
					status in 200..299 -> return FeedResponse.Fetched(text(response, body), validatorsOf(response))
					status == 401 || status == 403 -> throw CalendarProviderException("The feed server refused access (HTTP $status)")
					status == 404 || status == 410 -> throw CalendarProviderException("The feed was not found (HTTP $status)")
					else -> throw CalendarProviderException("The feed server failed (HTTP $status)")
				}
			}
		}
		throw CalendarProviderException("The feed server redirected more than ${properties.maxRedirects} times")
	}

	private fun target(url: String): URI {
		val uri =
			try {
				val parsed = URI(url.trim())
				when (parsed.scheme?.lowercase()) {
					// webcal:// is the subscription scheme of calendar apps; the feed itself is served over https.
					"webcal", "webcals" -> URI("https:" + parsed.rawSchemeSpecificPart)
					else -> parsed
				}
			} catch (e: URISyntaxException) {
				throw CalendarProviderException("The feed URL is not valid", e)
			}
		return check(uri)
	}

	private fun check(uri: URI): URI {
		val scheme = uri.scheme?.lowercase()
		if ((scheme != "http" && scheme != "https") || uri.host.isNullOrEmpty()) {
			throw CalendarProviderException("The feed URL must be an http(s) or webcal URL")
		}
		if (uri.rawUserInfo != null) throw CalendarProviderException("Feed URLs with credentials are not supported")
		return uri
	}

	private fun redirect(
		from: URI,
		location: String,
	): URI =
		try {
			check(from.resolve(URI(location.trim())))
		} catch (e: URISyntaxException) {
			throw CalendarProviderException("The feed server redirected to an invalid URL", e)
		} catch (e: IllegalArgumentException) {
			throw CalendarProviderException("The feed server redirected to an invalid URL", e)
		}

	private fun checkAddresses(target: URI) {
		val host = target.host.removeSurrounding("[", "]")
		if (properties.allowPrivateNetworks || host.lowercase().removeSuffix(".") in privateHosts) return
		val addresses =
			try {
				resolver.resolve(host)
			} catch (e: UnknownHostException) {
				throw CalendarProviderException("The feed host could not be found", e)
			}
		// All of them: otherwise a host could list a public and a private address and hope for the second.
		if (addresses.isEmpty() || !addresses.all(PublicAddresses::isPublic)) {
			throw CalendarProviderException("The feed host is not a public internet address")
		}
	}

	private fun send(
		target: URI,
		validators: FeedValidators?,
	): HttpResponse<InputStream> {
		val request =
			HttpRequest
				.newBuilder(target)
				.timeout(properties.timeout)
				.header("Accept", "text/calendar, */*;q=0.5")
				.header("Accept-Encoding", "gzip")
				.header("User-Agent", USER_AGENT)
				.apply {
					validators?.etag?.let { header("If-None-Match", it) }
					validators?.lastModified?.let { header("If-Modified-Since", it) }
				}.GET()
				.build()
		return try {
			http.send(request, HttpResponse.BodyHandlers.ofInputStream())
		} catch (e: HttpTimeoutException) {
			throw CalendarProviderException("The feed server did not answer in time", e)
		} catch (e: IOException) {
			throw CalendarProviderException("The feed server could not be reached (${e.javaClass.simpleName})", e)
		} catch (e: InterruptedException) {
			Thread.currentThread().interrupt()
			throw CalendarProviderException("Fetching the feed was interrupted", e)
		} catch (e: IllegalArgumentException) {
			throw CalendarProviderException("The feed URL is not valid", e)
		}
	}

	private fun text(
		response: HttpResponse<InputStream>,
		body: InputStream,
	): String {
		val max = properties.maxSize.toBytes()
		val length = response.headers().firstValueAsLong("Content-Length").orElse(-1)
		val gzip =
			response
				.headers()
				.firstValue("Content-Encoding")
				.orElse("")
				.trim()
				.equals("gzip", ignoreCase = true)
		if (!gzip && length > max) throw CalendarProviderException("The feed is larger than ${properties.maxSize}")
		val bytes =
			try {
				read(if (gzip) GZIPInputStream(body) else body, max)
			} catch (e: HttpTimeoutException) {
				throw CalendarProviderException("The feed server did not answer in time", e)
			} catch (e: IOException) {
				throw CalendarProviderException("The feed could not be read (${e.javaClass.simpleName})", e)
			}
		return String(bytes, charset(response))
	}

	private fun read(
		input: InputStream,
		max: Long,
	): ByteArray {
		val out = ByteArrayOutputStream()
		val buffer = ByteArray(BUFFER_SIZE)
		while (true) {
			val read = input.read(buffer)
			if (read < 0) break
			out.write(buffer, 0, read)
			if (out.size() > max) throw CalendarProviderException("The feed is larger than ${properties.maxSize}")
		}
		return out.toByteArray()
	}

	/** The charset of the Content-Type, UTF-8 when there is none or it is unknown (RFC 5545 §3.1.4). */
	private fun charset(response: HttpResponse<*>): Charset {
		val name =
			response
				.headers()
				.firstValue("Content-Type")
				.orElse("")
				.split(';')
				.drop(1)
				.map { it.trim() }
				.firstOrNull { it.startsWith("charset=", ignoreCase = true) }
				?.substringAfter('=')
				?.trim()
				?.removeSurrounding("\"")
				?: return Charsets.UTF_8
		return try {
			Charset.forName(name)
		} catch (e: IllegalCharsetNameException) {
			Charsets.UTF_8
		} catch (e: UnsupportedCharsetException) {
			Charsets.UTF_8
		}
	}

	private fun validatorsOf(response: HttpResponse<*>) =
		FeedValidators(
			etag =
				response
					.headers()
					.firstValue("ETag")
					.orElse(null)
					?.trim()
					?.ifEmpty { null },
			lastModified =
				response
					.headers()
					.firstValue("Last-Modified")
					.orElse(null)
					?.trim()
					?.ifEmpty { null },
		)

	private companion object {
		const val HTTP_NOT_MODIFIED = 304
		val REDIRECTS = setOf(301, 302, 303, 307, 308)
		const val BUFFER_SIZE = 16 * 1024
		const val USER_AGENT = "Cockpit-Calendar/1"
	}
}
