package ch.sonensei.cockpit.mail.api

import jakarta.mail.MessagingException
import jakarta.mail.Multipart
import jakarta.mail.Part
import jakarta.mail.internet.ContentType
import jakarta.mail.internet.MimePart
import jakarta.mail.internet.MimeUtility
import jakarta.mail.internet.ParseException
import org.jsoup.parser.Parser
import java.io.IOException
import java.net.URLDecoder
import java.nio.charset.Charset

/**
 * One attachment (or inline part, e.g. an image an HTML body references by `cid:`) of a message. [partId] is the IMAP
 * section (`1`, `2.1`, ...); [sizeBytes] is the decoded size as estimated from the transfer encoding, `null` when
 * the server did not report it.
 */
data class MailAttachment(
	val partId: String,
	val filename: String?,
	val contentType: String,
	val sizeBytes: Long?,
	val inline: Boolean,
	val contentId: String?,
)

/**
 * The readable content of a message: the body text (HTML unsanitized) and the other parts. Body text parts (e.g. Apple
 * Mail's text, image, text) are joined in MIME order; the HTML may contain [MimeContent.partImage] references for inline
 * images that no `cid:` points to.
 */
data class MailContent(
	val textPlain: String?,
	val textHtml: String?,
	val attachments: List<MailAttachment>,
)

/**
 * Reads the MIME tree of a message (SPEC.md §7.3). Works on an `IMAPMessage` without downloading the attachments:
 * multipart structure, names and sizes come from its BODYSTRUCTURE, and only the text parts are fetched.
 *
 * Classification follows the header sync (`MimeStructure`): a part is an attachment when its disposition says so,
 * when it is a nested message, or when it has a name and is neither `inline` nor inside `multipart/related`. The body
 * is built like Thunderbird: the text parts that are no attachment are joined in MIME order (plain with plain, HTML
 * with HTML; of a `multipart/alternative` the first plain and the last HTML variant), and inline raster images the HTML
 * does not reference by `cid:` are shown at their position as [partImage] references. Without HTML but with such an
 * image, the HTML body is the escaped plain text plus the images. Every other leaf is listed as an attachment, `inline`
 * only when the HTML body references its Content-ID by `cid:`.
 */
internal object MimeContent {
	fun read(
		message: Part,
		maxTextBytes: Int,
	): MailContent {
		val leaves = mutableListOf<Leaf>()
		val items = collect(message, "", related = false, maxTextBytes, leaves)
		val plain = plainOf(items).takeIf { it.isNotEmpty() }?.joinToString(LINE_BREAK)
		val htmlOnly = htmlOf(items, withPlain = false)
		val referenced = cidReferences(htmlOnly.filterIsInstance<Item.Html>().joinToString("") { it.text })

		fun referenced(leaf: Leaf) = leaf.attachment.contentId?.lowercase() in referenced
		val images = leaves.filter { it.image && !referenced(it) }.map { it.attachment.partId }.toSet()
		val html =
			when {
				htmlOnly.any { it is Item.Html } -> render(htmlOf(items, withPlain = true), images)
				images.isNotEmpty() -> render(plainItems(items), images)
				else -> null
			}
		return MailContent(plain, html, leaves.map { it.attachment.copy(inline = referenced(it)) })
	}

	/** A reference in an HTML body to the inline download of part [partId]; resolved after sanitizing. */
	fun partImage(partId: String) = "cid:$PART_IMAGE_PREFIX$partId$PART_IMAGE_SUFFIX"

	/** The part id of a [partImage] reference (given without `cid:`), or `null` when [contentId] is none. */
	fun partImageId(contentId: String): String? =
		contentId
			.takeIf { it.startsWith(PART_IMAGE_PREFIX, ignoreCase = true) && it.endsWith(PART_IMAGE_SUFFIX, ignoreCase = true) }
			?.substring(PART_IMAGE_PREFIX.length, contentId.length - PART_IMAGE_SUFFIX.length)
			?.takeIf { PART_ID.matches(it) }

	/** The leaf part at IMAP section [partId], or `null` when there is none (or it is a multipart). */
	fun findPart(
		message: Part,
		partId: String,
	): Part? {
		if (!PART_ID.matches(partId)) return null
		var current = message
		if (!current.isMimeType("multipart/*")) return if (partId == "1") current else null
		for (index in partId.split('.').map(String::toInt)) {
			val multipart = (if (current.isMimeType("multipart/*")) current.content as? Multipart else null) ?: return null
			if (index < 1 || index > multipart.count) return null
			current = multipart.getBodyPart(index - 1)
		}
		return current.takeUnless { it.isMimeType("multipart/*") }
	}

	/** A body text part, an alternative, or an inline image candidate, in MIME order. */
	private sealed interface Item {
		data class Plain(
			val text: String,
		) : Item

		data class Html(
			val text: String,
		) : Item

		data class Image(
			val partId: String,
			val filename: String?,
		) : Item

		/** A `multipart/alternative`: the items of its plain and of its HTML variant. */
		data class Alternative(
			val plain: List<Item>,
			val html: List<Item>,
		) : Item
	}

	/** A non-body leaf: its description and whether it may be shown as an image in the body. */
	private class Leaf(
		val attachment: MailAttachment,
		val image: Boolean,
	)

	private fun collect(
		part: Part,
		section: String,
		related: Boolean,
		maxTextBytes: Int,
		leaves: MutableList<Leaf>,
	): List<Item> {
		if (part.isMimeType("multipart/*")) {
			val multipart = part.content as? Multipart ?: return emptyList()
			val inRelated = related || part.isMimeType("multipart/related")
			val children =
				(0 until multipart.count).map { i ->
					val child = if (section.isEmpty()) "${i + 1}" else "$section.${i + 1}"
					collect(multipart.getBodyPart(i), child, inRelated, maxTextBytes, leaves)
				}
			if (!part.isMimeType("multipart/alternative")) return children.flatten()
			val plain = children.firstOrNull { variant -> plainOf(variant).isNotEmpty() }.orEmpty()
			val html = children.lastOrNull { variant -> htmlOf(variant, withPlain = false).any { it is Item.Html } }.orEmpty()
			return listOf(Item.Alternative(plain, html))
		}
		val disposition = safely { part.disposition }?.lowercase()
		val filename = filename(part)
		val nested = part.isMimeType("message/*")
		val attachment = disposition == Part.ATTACHMENT || nested || (filename != null && disposition != Part.INLINE && !related)
		// A single-part message has its body at section 1.
		val id = section.ifEmpty { "1" }
		return when {
			!attachment && part.isMimeType("text/plain") -> listOf(Item.Plain(text(part, maxTextBytes)))
			!attachment && part.isMimeType("text/html") -> listOf(Item.Html(text(part, maxTextBytes)))
			else -> {
				val described = describe(part, id, inline = false)
				val image = !attachment && described.contentType in RASTER_TYPES
				leaves += Leaf(described, image)
				if (image) listOf(Item.Image(id, filename)) else emptyList()
			}
		}
	}

	/** The plain texts of [items] in order. */
	private fun plainOf(items: List<Item>): List<String> =
		items.flatMap {
			when (it) {
				is Item.Plain -> listOf(it.text)
				is Item.Alternative -> plainOf(it.plain)
				else -> emptyList()
			}
		}

	/** [items] for a plain rendering: texts and images, of an alternative its plain variant. */
	private fun plainItems(items: List<Item>): List<Item> =
		items.flatMap {
			when (it) {
				is Item.Alternative -> plainItems(it.plain)
				is Item.Html -> emptyList()
				else -> listOf(it)
			}
		}

	/**
	 * [items] for an HTML rendering, of an alternative its HTML variant (its plain one when it has none). Plain text
	 * outside alternatives is kept only [withPlain].
	 */
	private fun htmlOf(
		items: List<Item>,
		withPlain: Boolean,
	): List<Item> =
		items.flatMap {
			when (it) {
				is Item.Alternative ->
					when {
						it.html.isNotEmpty() -> htmlOf(it.html, withPlain)
						withPlain -> plainItems(it.plain)
						else -> emptyList()
					}
				is Item.Plain -> if (withPlain) listOf(it) else emptyList()
				else -> listOf(it)
			}
		}

	/** Joins flat [items] to HTML: a single HTML part as is, plain text escaped, [images] as [partImage] references. */
	private fun render(
		items: List<Item>,
		images: Set<String>,
	): String {
		val shown = items.filter { it !is Item.Image || it.partId in images }
		val single = shown.singleOrNull()
		if (single is Item.Html) return single.text
		return shown.joinToString(LINE_BREAK) {
			when (it) {
				is Item.Html -> it.text
				is Item.Plain -> "<div>" + escape(it.text).replace(CRLF, LINE_BREAK).replace(LINE_BREAK, "<br>") + "</div>"
				is Item.Image -> "<div><img src=" + quoted(partImage(it.partId)) + " alt=" + quoted(escape(it.filename.orEmpty())) + "></div>"
				is Item.Alternative -> ""
			}
		}
	}

	private fun quoted(value: String) = QUOTE + value + QUOTE

	private fun escape(text: String): String =
		buildString(text.length) {
			text.forEach { c ->
				when (c) {
					'&' -> append("&amp;")
					'<' -> append("&lt;")
					'>' -> append("&gt;")
					'"' -> append("&quot;")
					'\'' -> append("&#39;")
					else -> append(c)
				}
			}
		}

	/**
	 * The Content-IDs (lower case, without angle brackets) that [html] references by `cid:`: raw, with HTML character
	 * references unescaped (as a browser or the HTML parser reads the attribute), and both percent-decoded.
	 */
	private fun cidReferences(html: String): Set<String> =
		CID_REFERENCE
			.findAll(html)
			.flatMap { match ->
				val raw = match.groupValues[1]
				sequenceOf(raw, Parser.unescapeEntities(raw, true))
			}.flatMap { value ->
				val raw = value.removePrefix("<").removeSuffix(">")
				val decoded =
					try {
						URLDecoder.decode(raw.replace("+", "%2B"), Charsets.UTF_8).removePrefix("<").removeSuffix(">")
					} catch (_: IllegalArgumentException) {
						raw
					}
				sequenceOf(raw.lowercase(), decoded.lowercase())
			}.toSet()

	private fun describe(
		part: Part,
		section: String,
		inline: Boolean,
	): MailAttachment {
		val contentType = mediaType(part)
		val encoded = safely { part.size }?.takeIf { it >= 0 }?.toLong()
		val encoding = safely { (part as? MimePart)?.encoding }?.lowercase()
		// IMAPBodyPart answers getContentID from the BODYSTRUCTURE; getHeader would need another fetch.
		val contentId = safely { (part as? MimePart)?.contentID ?: part.getHeader("Content-ID")?.firstOrNull() }
		return MailAttachment(
			partId = section,
			filename = filename(part),
			contentType = contentType,
			sizeBytes = encoded?.let { if (encoding == "base64") it * 3 / 4 else it },
			inline = inline,
			contentId =
				contentId
					?.trim()
					?.removePrefix("<")
					?.removeSuffix(">")
					?.takeIf { it.isNotBlank() },
		)
	}

	/** `type/subtype` in lower case, without parameters; `application/octet-stream` when unparsable. */
	fun mediaType(part: Part): String =
		try {
			ContentType(part.contentType).baseType.lowercase()
		} catch (_: ParseException) {
			OCTET_STREAM
		} catch (_: NullPointerException) {
			OCTET_STREAM
		}

	/** The decoded file name (RFC 2231 or RFC 2047), without directories; `null` when the part has none. */
	private fun filename(part: Part): String? {
		val raw = safely { part.fileName } ?: return null
		val decoded =
			if (raw.contains("=?")) {
				try {
					MimeUtility.decodeText(raw)
				} catch (_: IOException) {
					raw
				}
			} else {
				raw
			}
		return decoded
			.substringAfterLast('/')
			.substringAfterLast('\\')
			.filter { !it.isISOControl() }
			.trim()
			.takeIf { it.isNotEmpty() }
	}

	/** At most [maxBytes] of a text part, decoded with its charset (UTF-8 when it is missing or unknown). */
	private fun text(
		part: Part,
		maxBytes: Int,
	): String {
		val bytes = part.inputStream.use { it.readNBytes(maxBytes) }
		val charset =
			try {
				ContentType(part.contentType).getParameter("charset")
			} catch (_: ParseException) {
				null
			}
		return String(bytes, charsetOf(charset))
	}

	private fun charsetOf(name: String?): Charset =
		name?.let {
			try {
				Charset.forName(MimeUtility.javaCharset(it))
			} catch (_: IllegalArgumentException) {
				null
			}
		} ?: Charsets.UTF_8

	/** Malformed headers make Angus throw; such a header counts as absent. */
	private fun <T> safely(read: () -> T?): T? =
		try {
			read()
		} catch (_: MessagingException) {
			null
		}

	const val OCTET_STREAM = "application/octet-stream"

	/** Inline images of these types are shown in the body when no `cid:` references them (no SVG: it can carry scripts). */
	private val RASTER_TYPES = setOf("image/png", "image/jpeg", "image/gif", "image/webp")
	private const val PART_IMAGE_PREFIX = "cockpit-part-"
	private const val PART_IMAGE_SUFFIX = "@cockpit.invalid"
	private const val LINE_BREAK = "\n"
	private const val CRLF = "\r\n"
	private const val QUOTE = "\""
	private val CID_REFERENCE = Regex("cid:([^\"'\\s>)]+)", RegexOption.IGNORE_CASE)
	private val PART_ID = Regex("""^[1-9][0-9]{0,4}(\.[1-9][0-9]{0,4}){0,9}$""")
}
