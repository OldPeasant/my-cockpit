package ch.sonensei.cockpit.mail.compose

import ch.sonensei.cockpit.mail.model.MailAddress
import ch.sonensei.cockpit.mail.model.MailMessage
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** How a draft answers a message (SPEC.md §7.3). */
enum class ResponseMode {
	REPLY,
	REPLY_ALL,
	FORWARD,
}

/** The parts of a reply or forward that come from the original message. */
internal data class ResponseDraft(
	val to: List<MailAddress>,
	val cc: List<MailAddress>,
	val subject: String,
	val html: String,
	val text: String,
	val inReplyTo: String?,
	val references: String?,
)

/**
 * Reply, reply-all and forward (SPEC.md §7.3), without I/O:
 * - **Reply** goes to the original's `Reply-To`, else its `From`; when that is one of the user's own addresses (a reply
 *   to a sent mail) to the original's `To` instead.
 * - **Reply all** adds the original's `To` (to `To`) and `Cc` (to `Cc`), without the user's own addresses and without
 *   duplicates (addresses compare case-insensitively).
 * - **Forward** has no recipients and no threading headers.
 * - Replies set `In-Reply-To` to the original's Message-ID and `References` to the original's references plus that
 *   id (at most [MAX_REFERENCES] ids, the first one kept as the root).
 * - The subject gets `Re: ` / `Fwd: ` unless it already starts with such a prefix (also `AW:`, `SV:`, `WG:`, `Fw:`).
 * - The body is an empty first paragraph followed by the quote: for a reply an attribution line and the original in a
 *   `<blockquote type="cite">` (in the text, lines prefixed with `> `), for a forward a header block and the original.
 */
internal object ReplyComposer {
	/**
	 * @param quotedHtml the original's body as safe HTML (sanitized, or its plain text as HTML)
	 * @param quotedText the original's body as plain text
	 * @param ownAddresses the user's addresses (account and identities), lower case
	 */
	fun compose(
		mode: ResponseMode,
		original: MailMessage,
		quotedHtml: String,
		quotedText: String,
		ownAddresses: Set<String>,
	): ResponseDraft {
		val subject = original.subject.orEmpty().trim()
		return when (mode) {
			ResponseMode.REPLY, ResponseMode.REPLY_ALL -> {
				val (to, cc) = recipients(mode, original, ownAddresses)
				val attribution = "On ${date(original)}, ${original.from.firstOrNull()?.let(::full) ?: "unknown sender"} wrote:"
				ResponseDraft(
					to = to,
					cc = cc,
					subject = if (REPLY_PREFIX.containsMatchIn(subject)) subject else "Re: $subject".trimEnd(),
					html = "$EMPTY_PARAGRAPH<p>${ComposeText.escape(attribution)}</p><blockquote type=\"cite\">$quotedHtml</blockquote>",
					text = "\n\n$attribution\n${ComposeText.quote(quotedText)}\n",
					inReplyTo = original.messageIdHeader,
					references = references(original),
				)
			}
			ResponseMode.FORWARD -> {
				val header =
					buildList {
						add("---------- Forwarded message ----------")
						add("From: ${original.from.joinToString(", ", transform = ::full)}")
						add("Date: ${date(original)}")
						add("Subject: $subject")
						if (original.to.isNotEmpty()) add("To: ${original.to.joinToString(", ", transform = ::full)}")
						if (original.cc.isNotEmpty()) add("Cc: ${original.cc.joinToString(", ", transform = ::full)}")
					}
				ResponseDraft(
					to = emptyList(),
					cc = emptyList(),
					subject = if (FORWARD_PREFIX.containsMatchIn(subject)) subject else "Fwd: $subject".trimEnd(),
					html = EMPTY_PARAGRAPH + "<p>" + header.joinToString("<br>") { ComposeText.escape(it) } + "</p>" + quotedHtml,
					text = "\n\n" + header.joinToString("\n") + "\n\n" + quotedText.trimEnd('\n') + "\n",
					inReplyTo = null,
					references = null,
				)
			}
		}
	}

	private fun recipients(
		mode: ResponseMode,
		original: MailMessage,
		own: Set<String>,
	): Pair<List<MailAddress>, List<MailAddress>> {
		fun isOwn(address: MailAddress) = address.address.lowercase() in own
		val sender = original.replyTo.ifEmpty { original.from }
		val fromMe = sender.isNotEmpty() && sender.all(::isOwn)
		val primary = if (fromMe) original.to else sender
		if (mode == ResponseMode.REPLY) return distinct(primary) to emptyList()
		val to = distinct(primary + original.to).filterNot(::isOwn).ifEmpty { distinct(primary) }
		val taken = to.map { it.address.lowercase() }.toSet()
		val cc = distinct(original.cc).filterNot { isOwn(it) || it.address.lowercase() in taken }
		return to to cc
	}

	private fun distinct(addresses: List<MailAddress>) = addresses.distinctBy { it.address.lowercase() }

	private fun references(original: MailMessage): String? {
		val own = original.messageIdHeader ?: return original.referencesHeader
		val chain =
			(
				original.referencesHeader
					?.let(MESSAGE_ID::findAll)
					?.map { it.value }
					?.toList()
					.orEmpty() + own
			).distinct()
		val kept = if (chain.size <= MAX_REFERENCES) chain else listOf(chain.first()) + chain.takeLast(MAX_REFERENCES - 1)
		return kept.joinToString(" ")
	}

	private fun date(message: MailMessage): String = (message.sentAt ?: message.receivedAt)?.let { DATE.format(it) } ?: "an unknown date"

	private fun full(address: MailAddress): String =
		address.name?.takeIf(String::isNotBlank)?.let { "$it <${address.address}>" } ?: address.address

	private const val EMPTY_PARAGRAPH = "<p></p>"
	const val MAX_REFERENCES = 20
	private val REPLY_PREFIX = Regex("^(re|aw|sv)\\s*:", RegexOption.IGNORE_CASE)
	private val FORWARD_PREFIX = Regex("^(fwd?|wg)\\s*:", RegexOption.IGNORE_CASE)
	private val MESSAGE_ID = Regex("<[^<>\\s]+>")
	private val DATE: DateTimeFormatter =
		DateTimeFormatter
			.ofPattern(
				"EEE, d MMM yyyy 'at' HH:mm 'UTC'",
				Locale.ENGLISH,
			).withZone(ZoneOffset.UTC)
}
