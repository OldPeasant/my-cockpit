package ch.sonensei.cockpit.bookmarks.infra

import ch.sonensei.cockpit.bookmarks.domain.PageMetadata
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.ByteArrayInputStream
import java.net.URI
import java.nio.charset.Charset

/**
 * Reads the metadata of an HTML page: the title from `og:title`, `twitter:title` or `<title>`, the description from
 * `og:description`, `<meta name="description">` or `twitter:description`, and the favicon from `<link rel="icon">`
 * (also `shortcut icon`, else `apple-touch-icon`), else `/favicon.ico` of the site. Relative links are resolved
 * against the page's `<base>` or its URL; only http(s) favicons are kept (no `data:` URLs).
 */
object PageMetadataParser {
	fun parse(page: FetchedPage): PageMetadata {
		// An unknown charset name: let jsoup detect it from the page (meta charset, BOM) instead.
		val charset = page.charset?.takeIf { runCatching { Charset.isSupported(it) }.getOrDefault(false) }
		val document = Jsoup.parse(ByteArrayInputStream(page.bytes), charset, page.uri.toString())
		return PageMetadata(
			title = meta(document, "og:title", "twitter:title") ?: document.title().ifBlank { null },
			description = meta(document, "og:description", "description", "twitter:description"),
			favicon = favicon(document, page.uri),
		)
	}

	/** The first non-blank content of a `<meta>` whose `property` or `name` is one of [names], in that order. */
	private fun meta(
		document: Document,
		vararg names: String,
	): String? =
		names.firstNotNullOfOrNull { name ->
			document
				.select("meta[content]")
				.firstOrNull { it.attr("property").equals(name, true) || it.attr("name").equals(name, true) }
				?.attr("content")
				?.takeIf { it.isNotBlank() }
		}

	private fun favicon(
		document: Document,
		page: URI,
	): String? {
		val links = document.select("link[rel][href]")

		fun rel(vararg wanted: String) =
			links.firstOrNull { link ->
				val tokens =
					link
						.attr("rel")
						.lowercase()
						.split(Regex("\\s+"))
						.toSet()
				wanted.any { it in tokens }
			}
		val icon =
			listOfNotNull(rel("icon"), rel("apple-touch-icon", "apple-touch-icon-precomposed"))
				.map { it.absUrl("href") }
				.firstOrNull(::isHttp)
		return icon ?: runCatching { page.resolve("/favicon.ico").toString() }.getOrNull()?.takeIf(::isHttp)
	}

	private fun isHttp(url: String): Boolean {
		val uri = runCatching { URI(url) }.getOrNull() ?: return false
		val scheme = uri.scheme?.lowercase()
		return (scheme == "http" || scheme == "https") && !uri.host.isNullOrEmpty() && uri.rawUserInfo == null
	}
}
