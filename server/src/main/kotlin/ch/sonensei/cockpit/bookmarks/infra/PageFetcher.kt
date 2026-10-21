package ch.sonensei.cockpit.bookmarks.infra

import ch.sonensei.cockpit.mail.api.HostResolver
import ch.sonensei.cockpit.mail.api.PublicAddresses
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.URI
import java.net.URISyntaxException
import java.net.UnknownHostException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Flow
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.zip.GZIPInputStream

/** Why a page could not be fetched; [message] is fixed and safe to log (no URL, no server response). */
enum class PageFetchError(
	val message: String,
) {
	INVALID_URL("Not an http(s) URL without credentials"),
	BLOCKED_ADDRESS("The host is not a public internet address"),
	UNKNOWN_HOST("The host could not be found"),
	NOT_HTML("The page is not HTML"),
	HTTP_ERROR("The server answered with an error"),
	TOO_MANY_REDIRECTS("Too many redirects"),
	TIMEOUT("The server did not answer in time"),
	UNREACHABLE("The server could not be reached"),
}

class PageFetchException(
	val error: PageFetchError,
	cause: Throwable? = null,
) : RuntimeException(error.message, cause)

/** An HTML page: its [uri] after redirects, the charset of its Content-Type (if any) and the first bytes of its body. */
class FetchedPage(
	val uri: URI,
	val charset: String?,
	val bytes: ByteArray,
)

/**
 * Downloads web pages for bookmark metadata with SSRF protection: only `http`/`https` URLs without credentials on
 * ports 80/443; the host of the URL and of every redirect is resolved and all of its addresses must be public
 * ([PublicAddresses]: no loopback, private, link-local, ... addresses). [BookmarkFetchProperties.allowPrivateNetworks]
 * lifts the address and port checks. The HTTP client connects to the host by name again; it gets the address just
 * checked from the JVM's name cache, which narrows (but cannot close) the window for DNS rebinding (as the ICS feed
 * client does). No cookies or credentials are sent.
 *
 * The whole fetch (name resolution, redirects, bodies) must finish within [BookmarkFetchProperties.timeout]. Only the
 * first [BookmarkFetchProperties.maxSize] bytes of a page are read (also after gzip decompression); the rest is not
 * downloaded. Only `text/html` and `application/xhtml+xml` answers are accepted.
 */
class PageFetcher(
	private val properties: BookmarkFetchProperties,
	private val resolver: HostResolver = HostResolver { host -> InetAddress.getAllByName(host).toList() },
) {
	private val http: HttpClient =
		HttpClient
			.newBuilder()
			.connectTimeout(properties.connectTimeout)
			.followRedirects(HttpClient.Redirect.NEVER)
			.build()

	/** @throws PageFetchException when the page cannot be fetched or is not HTML */
	fun fetch(url: String): FetchedPage {
		val deadline = System.nanoTime() + properties.timeout.toNanos()
		var target = target(url)
		repeat(properties.maxRedirects + 1) {
			checkAddresses(target, deadline)
			val response = send(target, deadline)
			val status = response.statusCode()
			when {
				status in REDIRECTS -> {
					val location = response.headers().firstValue("Location").orElse(null) ?: throw PageFetchException(PageFetchError.HTTP_ERROR)
					target = redirect(target, location)
				}
				status in SUCCESS -> return page(target, response)
				else -> throw PageFetchException(PageFetchError.HTTP_ERROR)
			}
		}
		throw PageFetchException(PageFetchError.TOO_MANY_REDIRECTS)
	}

	private fun target(url: String): URI {
		val uri =
			try {
				URI(url.trim())
			} catch (e: URISyntaxException) {
				throw PageFetchException(PageFetchError.INVALID_URL, e)
			}
		return check(uri)
	}

	private fun check(uri: URI): URI {
		val scheme = uri.scheme?.lowercase()
		if ((scheme != "http" && scheme != "https") || uri.host.isNullOrEmpty() || uri.rawUserInfo != null) {
			throw PageFetchException(PageFetchError.INVALID_URL)
		}
		val port = if (uri.port == -1) (if (scheme == "https") HTTPS_PORT else HTTP_PORT) else uri.port
		if (!properties.allowPrivateNetworks && port != HTTP_PORT && port != HTTPS_PORT) {
			throw PageFetchException(PageFetchError.BLOCKED_ADDRESS)
		}
		return uri
	}

	private fun redirect(
		from: URI,
		location: String,
	): URI =
		try {
			check(from.resolve(URI(location.trim())))
		} catch (e: URISyntaxException) {
			throw PageFetchException(PageFetchError.HTTP_ERROR, e)
		} catch (e: IllegalArgumentException) {
			throw PageFetchException(PageFetchError.HTTP_ERROR, e)
		}

	private fun checkAddresses(
		target: URI,
		deadline: Long,
	) {
		if (properties.allowPrivateNetworks) return
		val addresses = resolve(target.host.removeSurrounding("[", "]"), deadline)
		// All of them: otherwise a host could list a public and a private address and hope for the second.
		if (addresses.isEmpty() || !addresses.all(PublicAddresses::isPublic)) {
			throw PageFetchException(PageFetchError.BLOCKED_ADDRESS)
		}
	}

	/**
	 * [resolver] within the time left. The system resolver has no timeout of its own, so the lookup runs on a helper
	 * thread that is abandoned (it ends with the resolver's own timeout) when the deadline passes.
	 */
	private fun resolve(
		host: String,
		deadline: Long,
	): List<InetAddress> {
		val lookup = CompletableFuture.supplyAsync({ resolver.resolve(host) }, LOOKUPS)
		return try {
			lookup.get(remaining(deadline), TimeUnit.NANOSECONDS)
		} catch (e: TimeoutException) {
			lookup.cancel(true)
			throw PageFetchException(PageFetchError.TIMEOUT, e)
		} catch (e: InterruptedException) {
			lookup.cancel(true)
			Thread.currentThread().interrupt()
			throw PageFetchException(PageFetchError.TIMEOUT, e)
		} catch (e: ExecutionException) {
			throw PageFetchException(if (e.cause is UnknownHostException) PageFetchError.UNKNOWN_HOST else PageFetchError.UNREACHABLE, e.cause)
		}
	}

	/** Sends one request and waits for its (limited) body, at most until [deadline] (a [System.nanoTime]). */
	private fun send(
		target: URI,
		deadline: Long,
	): HttpResponse<ByteArray> {
		val remaining = remaining(deadline)
		val future =
			try {
				val request =
					HttpRequest
						.newBuilder(target)
						.timeout(Duration.ofNanos(remaining))
						.header("Accept", "text/html, application/xhtml+xml;q=0.9")
						.header("Accept-Encoding", "gzip")
						.header("User-Agent", USER_AGENT)
						.GET()
						.build()
				http.sendAsync(request, ::body)
			} catch (e: IllegalArgumentException) {
				throw PageFetchException(PageFetchError.INVALID_URL, e)
			}
		return try {
			future.get(remaining, TimeUnit.NANOSECONDS)
		} catch (e: TimeoutException) {
			future.cancel(true)
			throw PageFetchException(PageFetchError.TIMEOUT, e)
		} catch (e: InterruptedException) {
			future.cancel(true)
			Thread.currentThread().interrupt()
			throw PageFetchException(PageFetchError.TIMEOUT, e)
		} catch (e: ExecutionException) {
			throw when (val cause = e.cause ?: e) {
				is PageFetchException -> cause
				is java.net.http.HttpTimeoutException -> PageFetchException(PageFetchError.TIMEOUT, cause)
				else -> PageFetchException(PageFetchError.UNREACHABLE, cause)
			}
		}
	}

	private fun remaining(deadline: Long): Long {
		val remaining = deadline - System.nanoTime()
		if (remaining <= 0) throw PageFetchException(PageFetchError.TIMEOUT)
		return remaining
	}

	/** The first [BookmarkFetchProperties.maxSize] bytes of an HTML answer (2xx); other bodies are discarded. */
	private fun body(info: HttpResponse.ResponseInfo): HttpResponse.BodySubscriber<ByteArray> {
		if (info.statusCode() !in SUCCESS || !html(info.headers().firstValue("Content-Type").orElse(""))) {
			return HttpResponse.BodySubscribers.replacing(ByteArray(0))
		}
		return TruncatedBody(properties.maxSize.toBytes())
	}

	private fun page(
		target: URI,
		response: HttpResponse<ByteArray>,
	): FetchedPage {
		val contentType = response.headers().firstValue("Content-Type").orElse("")
		if (!html(contentType)) throw PageFetchException(PageFetchError.NOT_HTML)
		val encoding =
			response
				.headers()
				.firstValue("Content-Encoding")
				.orElse("")
				.trim()
				.lowercase()
		val bytes =
			when (encoding) {
				"", "identity" -> response.body()
				"gzip", "x-gzip" -> gunzip(response.body())
				else -> throw PageFetchException(PageFetchError.UNREACHABLE)
			}
		val charset =
			contentType
				.split(';')
				.drop(1)
				.map { it.trim() }
				.firstOrNull { it.startsWith("charset=", ignoreCase = true) }
				?.substringAfter('=')
				?.trim()
				?.removeSurrounding("\"")
				?.ifEmpty { null }
		return FetchedPage(target, charset, bytes)
	}

	/** Decompresses up to [BookmarkFetchProperties.maxSize] bytes; a body cut off by the limit gives what it has. */
	private fun gunzip(bytes: ByteArray): ByteArray {
		val max = properties.maxSize.toBytes()
		val out = ByteArrayOutputStream()
		try {
			GZIPInputStream(ByteArrayInputStream(bytes)).use { input ->
				val buffer = ByteArray(BUFFER_SIZE)
				while (out.size() < max) {
					val read = input.read(buffer, 0, minOf(buffer.size.toLong(), max - out.size()).toInt())
					if (read < 0) break
					out.write(buffer, 0, read)
				}
			}
		} catch (e: IOException) {
			// The compressed body was cut off at the size limit (or is corrupt): keep what could be decompressed.
			if (out.size() == 0) throw PageFetchException(PageFetchError.UNREACHABLE, e)
		}
		return out.toByteArray()
	}

	private fun html(contentType: String): Boolean = contentType.substringBefore(';').trim().lowercase() in HTML_TYPES

	private companion object {
		const val HTTP_PORT = 80
		const val HTTPS_PORT = 443
		const val BUFFER_SIZE = 16 * 1024
		const val USER_AGENT = "Cockpit-Bookmarks/1"
		val SUCCESS = 200..299
		val REDIRECTS = setOf(301, 302, 303, 307, 308)
		val HTML_TYPES = setOf("text/html", "application/xhtml+xml")

		val LOOKUPS =
			Executors.newCachedThreadPool { runnable -> Thread(runnable, "bookmark-fetch-dns").apply { isDaemon = true } }
	}
}

/** Collects a response body up to [max] bytes, then cancels the download and completes with what it has. */
private class TruncatedBody(
	private val max: Long,
) : HttpResponse.BodySubscriber<ByteArray> {
	private val result = CompletableFuture<ByteArray>()
	private val out = ByteArrayOutputStream()
	private lateinit var subscription: Flow.Subscription

	override fun getBody(): CompletionStage<ByteArray> = result

	override fun onSubscribe(subscription: Flow.Subscription) {
		this.subscription = subscription
		subscription.request(Long.MAX_VALUE)
	}

	override fun onNext(item: List<ByteBuffer>) {
		if (result.isDone) return
		for (buffer in item) {
			val take = minOf(buffer.remaining().toLong(), max - out.size()).toInt()
			val bytes = ByteArray(take)
			buffer.get(bytes)
			out.write(bytes)
		}
		if (out.size() >= max) {
			// Complete first: cancelling makes the client fail the exchange too, and the first completion wins.
			result.complete(out.toByteArray())
			subscription.cancel()
		}
	}

	override fun onError(throwable: Throwable) {
		result.completeExceptionally(throwable)
	}

	override fun onComplete() {
		result.complete(out.toByteArray())
	}
}
