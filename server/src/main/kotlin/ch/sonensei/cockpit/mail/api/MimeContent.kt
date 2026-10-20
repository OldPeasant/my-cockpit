package ch.sonensei.cockpit.mail.api

import jakarta.mail.MessagingException
import jakarta.mail.Multipart
import jakarta.mail.Part
import jakarta.mail.internet.ContentType
import jakarta.mail.internet.MimePart
import jakarta.mail.internet.MimeUtility
import jakarta.mail.internet.ParseException
import java.io.IOException
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

/** The readable content of a message: the first plain and HTML text parts (HTML unsanitized) and the other parts. */
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
 * when it is a nested message, or when it has a name and is neither `inline` nor inside `multipart/related`. The first
 * text/plain and the first text/html part that are no attachment are the body; every other leaf is listed as an
 * attachment, `inline` when it is no real attachment (e.g. a `cid:` image).
 */
internal object MimeContent {
	fun read(
		message: Part,
		maxTextBytes: Int,
	): MailContent {
		var plain: String? = null
		var html: String? = null
		val attachments = mutableListOf<MailAttachment>()
		leaves(message, "", related = false) { part, section, attachment ->
			when {
				!attachment && plain == null && part.isMimeType("text/plain") -> plain = text(part, maxTextBytes)
				!attachment && html == null && part.isMimeType("text/html") -> html = text(part, maxTextBytes)
				else -> attachments += describe(part, section, inline = !attachment)
			}
		}
		return MailContent(plain, html, attachments)
	}

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

	private fun leaves(
		part: Part,
		section: String,
		related: Boolean,
		visit: (Part, String, Boolean) -> Unit,
	) {
		if (part.isMimeType("multipart/*")) {
			val multipart = part.content as? Multipart ?: return
			val inRelated = related || part.isMimeType("multipart/related")
			for (i in 0 until multipart.count) {
				leaves(multipart.getBodyPart(i), if (section.isEmpty()) "${i + 1}" else "$section.${i + 1}", inRelated, visit)
			}
			return
		}
		val disposition = safely { part.disposition }?.lowercase()
		val named = filename(part) != null
		val nested = part.isMimeType("message/*")
		val attachment = disposition == Part.ATTACHMENT || nested || (named && disposition != Part.INLINE && !related)
		// A single-part message has its body at section 1.
		visit(part, section.ifEmpty { "1" }, attachment)
	}

	private fun describe(
		part: Part,
		section: String,
		inline: Boolean,
	): MailAttachment {
		val contentType = mediaType(part)
		val encoded = safely { part.size }?.takeIf { it >= 0 }?.toLong()
		val encoding = safely { (part as? MimePart)?.encoding }?.lowercase()
		return MailAttachment(
			partId = section,
			filename = filename(part),
			contentType = contentType,
			sizeBytes = encoded?.let { if (encoding == "base64") it * 3 / 4 else it },
			inline = inline,
			contentId =
				safely {
					part.getHeader("Content-ID")?.firstOrNull()
				}?.trim()?.removePrefix("<")?.removeSuffix(">")?.takeIf { it.isNotBlank() },
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
	private val PART_ID = Regex("""^[1-9][0-9]{0,4}(\.[1-9][0-9]{0,4}){0,9}$""")
}
