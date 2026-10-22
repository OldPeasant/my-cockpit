package ch.sonensei.cockpit.mail.compose

import ch.sonensei.cockpit.mail.model.MailAddress
import jakarta.activation.DataHandler
import jakarta.mail.Flags
import jakarta.mail.Message
import jakarta.mail.Part
import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import jakarta.mail.internet.MimePart
import jakarta.mail.internet.MimeUtility
import jakarta.mail.util.ByteArrayDataSource
import java.time.Instant
import java.util.Date
import java.util.Properties

/** One attachment of a message being composed, with its decoded bytes. */
class ComposedAttachment(
	val filename: String?,
	val contentType: String,
	val bytes: ByteArray,
	/** A part of the body (e.g. a `cid:` image) rather than a real attachment. */
	val inline: Boolean = false,
	/** Without angle brackets. */
	val contentId: String? = null,
) {
	override fun toString(): String = "ComposedAttachment(filename=$filename, contentType=$contentType, size=${bytes.size})"
}

/**
 * Everything a draft or an outgoing message consists of (SPEC.md §7.3). [messageId] (with angle brackets) stays the
 * same for every saved version of a draft and for the message sent from it, so the cache keeps one row for it.
 */
data class ComposedMessage(
	val messageId: String,
	val from: MailAddress,
	val replyTo: MailAddress?,
	val to: List<MailAddress>,
	val cc: List<MailAddress>,
	val bcc: List<MailAddress>,
	val subject: String?,
	val html: String?,
	val text: String,
	val inReplyTo: String?,
	val references: String?,
	val attachments: List<ComposedAttachment>,
) {
	val recipients: List<MailAddress> get() = to + cc + bcc

	val sizeBytes: Long get() = attachments.sumOf { it.bytes.size.toLong() } + text.length + (html?.length ?: 0)
}

/**
 * Builds the MIME form of a [ComposedMessage]: `multipart/alternative` (plain text, then HTML) when there is HTML,
 * the HTML in `multipart/related` with the inline (`cid:`) parts, wrapped in `multipart/mixed` with the attachments.
 * Text is UTF-8, quoted-printable where needed.
 */
internal object DraftMime {
	private val session: Session = Session.getInstance(Properties())

	fun build(
		message: ComposedMessage,
		date: Instant,
		draft: Boolean,
	): MimeMessage {
		val mime = FixedIdMimeMessage(session, message.messageId)
		mime.setFrom(internet(message.from))
		message.replyTo?.let { mime.replyTo = arrayOf(internet(it)) }
		mime.setRecipients(Message.RecipientType.TO, message.to.map(::internet).toTypedArray())
		mime.setRecipients(Message.RecipientType.CC, message.cc.map(::internet).toTypedArray())
		mime.setRecipients(Message.RecipientType.BCC, message.bcc.map(::internet).toTypedArray())
		mime.setSubject(message.subject.orEmpty(), UTF_8)
		mime.sentDate = Date.from(date)
		message.inReplyTo?.let { mime.setHeader("In-Reply-To", it) }
		message.references?.let { mime.setHeader("References", MimeUtility.fold("References: ".length, it)) }
		// With HTML, `cid:` parts belong next to it in multipart/related (RFC 2387), not among the attachments.
		val (related, attachments) =
			if (message.html == null) {
				emptyList<ComposedAttachment>() to message.attachments
			} else {
				message.attachments.partition { it.inline && it.contentId != null }
			}
		val body = body(message, related)
		if (attachments.isEmpty()) {
			setContent(mime, body)
		} else {
			val mixed = MimeMultipart("mixed")
			mixed.addBodyPart(MimeBodyPart().also { setContent(it, body) })
			attachments.forEach { mixed.addBodyPart(attachment(it)) }
			mime.setContent(mixed)
		}
		if (draft) mime.setFlags(Flags(Flags.Flag.DRAFT), true)
		mime.setFlags(Flags(Flags.Flag.SEEN), true)
		mime.saveChanges()
		return mime
	}

	private sealed interface Body {
		data class Text(
			val text: String,
		) : Body

		data class Alternative(
			val multipart: MimeMultipart,
		) : Body
	}

	/** The text body: `alternative(plain, html)`, or `alternative(plain, related(html, inline...))` with [inline] parts. */
	private fun body(
		message: ComposedMessage,
		inline: List<ComposedAttachment>,
	): Body {
		val html = message.html ?: return Body.Text(message.text)
		val alternative = MimeMultipart("alternative")
		alternative.addBodyPart(MimeBodyPart().apply { setText(message.text, UTF_8, "plain") })
		val htmlPart = MimeBodyPart().apply { setText(html, UTF_8, "html") }
		if (inline.isEmpty()) {
			alternative.addBodyPart(htmlPart)
		} else {
			val related = MimeMultipart("related")
			related.addBodyPart(htmlPart)
			inline.forEach { related.addBodyPart(attachment(it)) }
			alternative.addBodyPart(MimeBodyPart().apply { setContent(related) })
		}
		return Body.Alternative(alternative)
	}

	private fun setContent(
		part: MimePart,
		body: Body,
	) {
		when (body) {
			is Body.Text -> part.setText(body.text, UTF_8, "plain")
			is Body.Alternative -> part.setContent(body.multipart)
		}
	}

	private fun attachment(attachment: ComposedAttachment): MimeBodyPart =
		MimeBodyPart().apply {
			dataHandler = DataHandler(ByteArrayDataSource(attachment.bytes, attachment.contentType))
			disposition = if (attachment.inline) Part.INLINE else Part.ATTACHMENT
			attachment.filename?.let { fileName = MimeUtility.encodeText(it, UTF_8, null) }
			attachment.contentId?.let { contentID = "<$it>" }
		}

	fun internet(address: MailAddress): InternetAddress = InternetAddress(address.address, address.name, UTF_8)

	private const val UTF_8 = "UTF-8"
}

/** A message that keeps the given `Message-ID` instead of generating a new one on `saveChanges`. */
internal class FixedIdMimeMessage : MimeMessage {
	private val fixedId: String

	constructor(session: Session, messageId: String) : super(session) {
		fixedId = messageId
	}

	/** A copy of [source] (fully read) that keeps [messageId]. */
	constructor(source: MimeMessage, messageId: String) : super(source) {
		fixedId = messageId
	}

	override fun updateMessageID() {
		setHeader("Message-ID", fixedId)
	}
}
