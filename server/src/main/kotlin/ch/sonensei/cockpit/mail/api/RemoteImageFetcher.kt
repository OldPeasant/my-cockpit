package ch.sonensei.cockpit.mail.api

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URISyntaxException
import java.net.UnknownHostException
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** Why proxying a remote image failed; [message] is fixed and safe to show (no URL, no server response). */
enum class RemoteImageError(
	val status: HttpStatus,
	val message: String,
) {
	INVALID_URL(HttpStatus.BAD_REQUEST, "Not an http(s) image URL"),
	BLOCKED_ADDRESS(HttpStatus.BAD_REQUEST, "The image host is not a public internet address"),
	TOO_LARGE(HttpStatus.BAD_GATEWAY, "The image is too large"),
	NOT_AN_IMAGE(HttpStatus.BAD_GATEWAY, "The remote resource is not a supported image"),
	TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "The image server did not answer in time"),
	UPSTREAM_FAILED(HttpStatus.BAD_GATEWAY, "The image could not be loaded"),
}

class RemoteImageException(
	val error: RemoteImageError,
	cause: Throwable? = null,
) : RuntimeException(error.message, cause)

class RemoteImage(
	/** One of [RemoteImageFetcher.IMAGE_TYPES]. */
	val contentType: String,
	val bytes: ByteArray,
)

/** Resolves a host name to its addresses; replaceable in tests (e.g. to simulate DNS rebinding). */
fun interface HostResolver {
	fun resolve(host: String): List<InetAddress>
}

/**
 * Which addresses the image proxy may connect to: public unicast internet addresses only. Rejects loopback,
 * unspecified, private (RFC 1918, ULA), CGNAT, link-local, multicast, broadcast, documentation and benchmarking
 * ranges, and IPv6 transition addresses that embed one of those IPv4 addresses (mapped, NAT64, 6to4).
 */
object PublicAddresses {
	fun isPublic(address: InetAddress): Boolean {
		if (address.isAnyLocalAddress ||
			address.isLoopbackAddress ||
			address.isLinkLocalAddress ||
			address.isSiteLocalAddress ||
			address.isMulticastAddress
		) {
			return false
		}
		val b = address.address.map { it.toInt() and 0xff }
		return when (address) {
			is Inet4Address -> isPublicV4(b)
			is Inet6Address -> isPublicV6(b)
			else -> false
		}
	}

	private fun isPublicV4(b: List<Int>): Boolean =
		!(
			b[0] == 0 ||
				b[0] == 10 ||
				b[0] == 127 ||
				(b[0] == 100 && b[1] in 64..127) ||
				(b[0] == 169 && b[1] == 254) ||
				(b[0] == 172 && b[1] in 16..31) ||
				(b[0] == 192 && b[1] == 0 && b[2] in setOf(0, 2)) ||
				(b[0] == 192 && b[1] == 88 && b[2] == 99) ||
				(b[0] == 192 && b[1] == 168) ||
				(b[0] == 198 && b[1] in 18..19) ||
				(b[0] == 198 && b[1] == 51 && b[2] == 100) ||
				(b[0] == 203 && b[1] == 0 && b[2] == 113) ||
				b[0] >= 224
		)

	private fun isPublicV6(b: List<Int>): Boolean {
		val embeddedV4 =
			when {
				// ::/96 (IPv4-compatible, also :: and ::1) and ::ffff:0:0/96 (IPv4-mapped).
				b.subList(0, 10).all { it == 0 } && ((b[10] == 0 && b[11] == 0) || (b[10] == 0xff && b[11] == 0xff)) -> b.subList(12, 16)
				// 64:ff9b::/96 NAT64.
				b[0] == 0 && b[1] == 0x64 && b[2] == 0xff && b[3] == 0x9b && b.subList(4, 12).all { it == 0 } -> b.subList(12, 16)
				// 2002::/16 6to4.
				b[0] == 0x20 && b[1] == 0x02 -> b.subList(2, 6)
				else -> null
			}
		if (embeddedV4 != null) return isPublicV4(embeddedV4)
		return !(
			(b[0] and 0xfe) == 0xfc ||
				// 2001:db8::/32 documentation, 2001::/32 Teredo.
				(b[0] == 0x20 && b[1] == 0x01 && ((b[2] == 0x0d && b[3] == 0xb8) || (b[2] == 0 && b[3] == 0))) ||
				// 100::/64 discard.
				(b[0] == 0x01 && b.subList(1, 8).all { it == 0 })
		)
	}
}

/**
 * Fetches remote images for the image proxy (SPEC.md §4.1) with SSRF protection: only `http`/`https` URLs on ports
 * 80/443 without credentials; the host is resolved once, every address must be public ([PublicAddresses]) and the
 * connection goes to exactly the checked address (no second DNS lookup, so DNS rebinding cannot redirect it). TLS
 * checks the certificate against the host name (SNI). Redirects are followed up to
 * [MailReadProperties.ImageProxy.maxRedirects], each one checked again. The whole fetch must finish within
 * [MailReadProperties.ImageProxy.timeout], the image must be at most [MailReadProperties.ImageProxy.maxSize] and of a
 * raster type in [IMAGE_TYPES] (no SVG: it can carry scripts). No cookies, referrer or credentials are sent.
 *
 * A minimal HTTP/1.1 client over a plain socket: the JDK HTTP client cannot be pinned to a resolved address.
 */
@Component
class RemoteImageFetcher(
	properties: MailReadProperties,
) {
	private val limits = properties.imageProxy
	internal var resolver: HostResolver = HostResolver { host -> InetAddress.getAllByName(host).toList() }
	internal var sslSocketFactory: SSLSocketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory

	fun fetch(url: String): RemoteImage {
		val deadline = System.nanoTime() + limits.timeout.toNanos()
		var target = target(url)
		for (hop in 0..limits.maxRedirects) {
			val response = request(target, deadline)
			try {
				val location = response.headers["location"]
				if (response.status in REDIRECTS && location != null) {
					target = target(resolveRedirect(target.uri, location))
					continue
				}
				return image(response, deadline)
			} finally {
				response.close()
			}
		}
		throw RemoteImageException(RemoteImageError.UPSTREAM_FAILED)
	}

	private class Target(
		val uri: URI,
		val https: Boolean,
		val host: String,
		val port: Int,
	) {
		/** The request target: path and query as written, `/` when empty. */
		val path: String get() = uri.rawPath.ifEmpty { "/" } + (uri.rawQuery?.let { "?$it" } ?: "")

		val hostHeader: String get() = if (port == if (https) HTTPS_PORT else HTTP_PORT) host else "$host:$port"
	}

	private fun target(url: String): Target {
		val uri =
			try {
				URI(url.trim().replace(" ", "%20"))
			} catch (e: URISyntaxException) {
				throw RemoteImageException(RemoteImageError.INVALID_URL, e)
			}
		val scheme = uri.scheme?.lowercase()
		val host = uri.host
		if ((scheme != "http" && scheme != "https") || host.isNullOrEmpty() || uri.rawUserInfo != null) {
			throw RemoteImageException(RemoteImageError.INVALID_URL)
		}
		val https = scheme == "https"
		val port = if (uri.port == -1) (if (https) HTTPS_PORT else HTTP_PORT) else uri.port
		if (!limits.allowPrivateNetworks && port != HTTP_PORT && port != HTTPS_PORT) {
			throw RemoteImageException(RemoteImageError.BLOCKED_ADDRESS)
		}
		return Target(uri, https, host, port)
	}

	private fun resolveRedirect(
		base: URI,
		location: String,
	): String =
		try {
			base.resolve(URI(location.trim().replace(" ", "%20"))).toString()
		} catch (e: URISyntaxException) {
			throw RemoteImageException(RemoteImageError.UPSTREAM_FAILED, e)
		} catch (e: IllegalArgumentException) {
			throw RemoteImageException(RemoteImageError.UPSTREAM_FAILED, e)
		}

	private fun address(host: String): InetAddress {
		val addresses =
			try {
				resolver.resolve(host)
			} catch (e: UnknownHostException) {
				throw RemoteImageException(RemoteImageError.UPSTREAM_FAILED, e)
			}
		if (addresses.isEmpty()) throw RemoteImageException(RemoteImageError.UPSTREAM_FAILED)
		// All of them: otherwise a host could list a public and a private address and hope for the second.
		if (!limits.allowPrivateNetworks && !addresses.all(PublicAddresses::isPublic)) {
			throw RemoteImageException(RemoteImageError.BLOCKED_ADDRESS)
		}
		return addresses.first()
	}

	private fun request(
		target: Target,
		deadline: Long,
	): Response {
		val address = address(target.host)
		val plain = Socket()
		try {
			plain.connect(InetSocketAddress(address, target.port), minOf(limits.connectTimeout.toMillis(), remainingMillis(deadline)).toInt())
			plain.soTimeout = remainingMillis(deadline).toInt()
			val socket = if (target.https) tls(plain, target) else plain
			socket.getOutputStream().apply {
				write(
					(
						"GET ${target.path} HTTP/1.1\r\nHost: ${target.hostHeader}\r\nUser-Agent: $USER_AGENT\r\n" +
							"Accept: image/*\r\nAccept-Encoding: identity\r\nConnection: close\r\n\r\n"
					).toByteArray(Charsets.US_ASCII),
				)
				flush()
			}
			val input = BufferedInputStream(socket.getInputStream())
			val status = statusOf(readLine(input, deadline))
			val headers = readHeaders(input, deadline)
			return Response(socket, input, status, headers)
		} catch (e: RemoteImageException) {
			plain.close()
			throw e
		} catch (e: SocketTimeoutException) {
			plain.close()
			throw RemoteImageException(RemoteImageError.TIMEOUT, e)
		} catch (e: IOException) {
			plain.close()
			throw RemoteImageException(RemoteImageError.UPSTREAM_FAILED, e)
		}
	}

	private fun tls(
		plain: Socket,
		target: Target,
	): SSLSocket {
		val socket = sslSocketFactory.createSocket(plain, target.host, target.port, true) as SSLSocket
		val parameters = socket.sslParameters
		parameters.endpointIdentificationAlgorithm = "HTTPS"
		if (!target.host.startsWith("[") && !IPV4_LITERAL.matches(target.host)) parameters.serverNames = listOf(SNIHostName(target.host))
		socket.sslParameters = parameters
		socket.startHandshake()
		return socket
	}

	private class Response(
		private val socket: Socket,
		val body: InputStream,
		val status: Int,
		val headers: Map<String, String>,
	) : AutoCloseable {
		override fun close() = runCatching { socket.close() }.let { }
	}

	private fun image(
		response: Response,
		deadline: Long,
	): RemoteImage {
		if (response.status != HTTP_OK) throw RemoteImageException(RemoteImageError.UPSTREAM_FAILED)
		val contentType =
			response.headers["content-type"]
				?.substringBefore(';')
				?.trim()
				?.lowercase()
		if (contentType !in IMAGE_TYPES) throw RemoteImageException(RemoteImageError.NOT_AN_IMAGE)
		val encoding = response.headers["content-encoding"]?.trim()?.lowercase()
		if (encoding != null && encoding != "identity") throw RemoteImageException(RemoteImageError.UPSTREAM_FAILED)
		val max = limits.maxSize.toBytes()
		val length = response.headers["content-length"]?.trim()?.toLongOrNull()
		if (length != null && length > max) throw RemoteImageException(RemoteImageError.TOO_LARGE)
		val chunked = response.headers["transfer-encoding"]?.lowercase()?.contains("chunked") == true
		val bytes =
			try {
				when {
					chunked -> readChunked(response.body, max, deadline)
					length != null -> readFully(response.body, length, max, deadline, exact = true)
					else -> readFully(response.body, Long.MAX_VALUE, max, deadline, exact = false)
				}
			} catch (e: SocketTimeoutException) {
				throw RemoteImageException(RemoteImageError.TIMEOUT, e)
			} catch (e: IOException) {
				throw RemoteImageException(RemoteImageError.UPSTREAM_FAILED, e)
			}
		return RemoteImage(contentType!!, bytes)
	}

	private fun readFully(
		input: InputStream,
		length: Long,
		max: Long,
		deadline: Long,
		exact: Boolean,
		into: ByteArrayOutputStream = ByteArrayOutputStream(),
	): ByteArray {
		val buffer = ByteArray(BUFFER_SIZE)
		var remaining = length
		while (remaining > 0) {
			checkDeadline(deadline)
			val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
			if (read < 0) {
				if (exact) throw IOException("Connection closed before the end of the body")
				break
			}
			into.write(buffer, 0, read)
			if (into.size() > max) throw RemoteImageException(RemoteImageError.TOO_LARGE)
			remaining -= read
		}
		return into.toByteArray()
	}

	private fun readChunked(
		input: InputStream,
		max: Long,
		deadline: Long,
	): ByteArray {
		val out = ByteArrayOutputStream()
		while (true) {
			val size =
				readLine(input, deadline).substringBefore(';').trim().toLongOrNull(HEX)
					?: throw IOException("Malformed chunk size")
			if (size == 0L) break
			if (size < 0 || out.size() + size > max) throw RemoteImageException(RemoteImageError.TOO_LARGE)
			readFully(input, size, max, deadline, exact = true, into = out)
			readLine(input, deadline)
		}
		return out.toByteArray()
	}

	private fun statusOf(line: String): Int {
		val parts = line.split(' ', limit = 3)
		if (parts.size < 2 || !parts[0].startsWith("HTTP/1.")) throw IOException("Not an HTTP/1.x response")
		return parts[1].toIntOrNull() ?: throw IOException("Malformed status line")
	}

	private fun readHeaders(
		input: InputStream,
		deadline: Long,
	): Map<String, String> {
		val headers = mutableMapOf<String, String>()
		repeat(MAX_HEADERS) {
			val line = readLine(input, deadline)
			if (line.isEmpty()) return headers
			val colon = line.indexOf(':')
			if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
		}
		throw IOException("Too many response headers")
	}

	/** One line without its CR LF (a bare LF also ends it), at most [MAX_LINE] bytes. */
	private fun readLine(
		input: InputStream,
		deadline: Long,
	): String {
		val line = ByteArrayOutputStream()
		while (true) {
			checkDeadline(deadline)
			when (val b = input.read()) {
				-1 -> throw IOException("Connection closed in the response head")
				'\n'.code -> break
				else -> line.write(b)
			}
			if (line.size() > MAX_LINE) throw IOException("Response line too long")
		}
		return line.toString(Charsets.ISO_8859_1).removeSuffix("\r")
	}

	private fun remainingMillis(deadline: Long): Long {
		val remaining = (deadline - System.nanoTime()) / NANOS_PER_MILLI
		if (remaining <= 0) throw RemoteImageException(RemoteImageError.TIMEOUT)
		return remaining
	}

	private fun checkDeadline(deadline: Long) {
		if (System.nanoTime() > deadline) throw RemoteImageException(RemoteImageError.TIMEOUT)
	}

	companion object {
		/** Raster formats browsers render in `<img>`; no SVG. */
		val IMAGE_TYPES =
			setOf(
				"image/png",
				"image/jpeg",
				"image/gif",
				"image/webp",
				"image/bmp",
				"image/avif",
				"image/x-icon",
				"image/vnd.microsoft.icon",
			)

		private const val HTTP_PORT = 80
		private const val HTTPS_PORT = 443
		private const val HTTP_OK = 200
		private val REDIRECTS = setOf(301, 302, 303, 307, 308)
		private const val USER_AGENT = "Cockpit-ImageProxy/1"
		private const val MAX_HEADERS = 100
		private const val MAX_LINE = 8192
		private const val BUFFER_SIZE = 16 * 1024
		private const val HEX = 16
		private const val NANOS_PER_MILLI = 1_000_000L
		private val IPV4_LITERAL = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")
	}
}
