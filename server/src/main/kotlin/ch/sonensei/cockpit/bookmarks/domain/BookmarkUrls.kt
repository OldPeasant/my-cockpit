package ch.sonensei.cockpit.bookmarks.domain

import java.net.IDN
import java.net.URI
import java.net.URISyntaxException

/**
 * Makes URLs as users copy them from a browser or share them from an app safe to store and fetch: a host in Unicode
 * (IDN) is converted to its ASCII (punycode) form, and characters a URI does not allow (spaces, `|`, `[`/`]` outside an
 * IPv6 host, `{`, non-ASCII characters, a `%` that does not start an escape, ...) are percent-encoded as UTF-8. A URL
 * that already is a valid URI stays as it is. Host names may contain `_` (used by real sites, although not allowed by
 * the URI grammar).
 */
internal object BookmarkUrls {
	private val URL = Regex("""^([A-Za-z][A-Za-z0-9+.\-]*)://([^/?#]*)([^?#]*)(\?[^#]*)?(#.*)?$""", RegexOption.DOT_MATCHES_ALL)
	private val HOST_NAME = Regex("""^[A-Za-z0-9_\-]+(\.[A-Za-z0-9_\-]+)*\.?$""")
	private val IPV6 = Regex("""^\[[0-9A-Fa-f:.]+]$""")
	private val PORT = Regex("""^\d{0,5}$""")
	private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
	private const val SUB_DELIMS = "!$&'()*+,;="
	private const val USER_INFO = UNRESERVED + SUB_DELIMS + ":"
	private const val PATH = USER_INFO + "@/"
	private const val QUERY = "$PATH?"

	/**
	 * The http(s) [url] (trimmed) in a form safe to store and fetch, or `null` when it is not an absolute http(s) URL
	 * with a valid host (or has control characters).
	 */
	fun normalize(url: String): String? {
		val trimmed = url.trim()
		if (trimmed.any { it.isISOControl() }) return null
		val match = URL.matchEntire(trimmed) ?: return null
		val (scheme, authority, path, query, fragment) = match.destructured
		if (scheme.lowercase() != "http" && scheme.lowercase() != "https") return null
		val userInfo = authority.substringBeforeLast('@', "")
		val hostPort = authority.substringAfterLast('@')
		// port is null when the URL has none (not even an empty one after a ':').
		val host: String
		val port: String?
		if (hostPort.startsWith("[")) {
			val end = hostPort.indexOf(']')
			if (end < 0) return null
			val rest = hostPort.substring(end + 1)
			if (rest.isNotEmpty() && !rest.startsWith(":")) return null
			host = hostPort.substring(0, end + 1)
			port = rest.takeIf { it.isNotEmpty() }?.substring(1)
		} else {
			host = hostPort.substringBefore(':')
			port = if (':' in hostPort) hostPort.substringAfter(':') else null
		}
		val asciiHost = asciiHost(host) ?: return null
		if (port != null && !PORT.matches(port)) return null
		val normalized =
			buildString {
				append(scheme).append("://")
				if ('@' in authority) append(encode(userInfo, USER_INFO)).append('@')
				append(asciiHost)
				if (port != null) append(':').append(port)
				append(encode(path, PATH))
				if (query.isNotEmpty()) append('?').append(encode(query.substring(1), QUERY))
				if (fragment.isNotEmpty()) append('#').append(encode(fragment.substring(1), QUERY))
			}
		return normalized.takeIf(::parses)
	}

	/** The ASCII form of [host] (an IPv6 literal unchanged), or `null` when it is no valid host. */
	private fun asciiHost(host: String): String? {
		if (host.startsWith("[")) return host.takeIf { IPV6.matches(it) }
		if (host.isEmpty()) return null
		val ascii =
			try {
				IDN.toASCII(host, IDN.ALLOW_UNASSIGNED)
			} catch (_: IllegalArgumentException) {
				return null
			}
		return ascii.takeIf { HOST_NAME.matches(it) }
	}

	/** [text] with every character not in [allowed] percent-encoded (UTF-8); valid `%XX` escapes are kept. */
	private fun encode(
		text: String,
		allowed: String,
	): String =
		buildString {
			var i = 0
			while (i < text.length) {
				val c = text[i]
				when {
					c == '%' && i + 2 < text.length && text[i + 1].isHexDigit() && text[i + 2].isHexDigit() -> append(c)
					c in allowed -> append(c)
					else -> {
						val codePoint = text.codePointAt(i)
						String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8).forEach { append("%%%02X".format(it.toInt() and 0xFF)) }
						i += Character.charCount(codePoint)
						continue
					}
				}
				i++
			}
		}

	private fun Char.isHexDigit() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

	private fun parses(url: String): Boolean =
		try {
			URI(url)
			true
		} catch (_: URISyntaxException) {
			false
		}
}
