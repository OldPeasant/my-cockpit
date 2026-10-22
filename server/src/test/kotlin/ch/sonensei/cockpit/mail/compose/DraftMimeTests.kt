package ch.sonensei.cockpit.mail.compose

import ch.sonensei.cockpit.mail.model.MailAddress
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import jakarta.mail.Flags
import jakarta.mail.Message
import jakarta.mail.Multipart
import jakarta.mail.Part
import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeUtility
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.Properties

/** The MIME form of a composed message ([DraftMime]) and the fixed Message-ID ([FixedIdMimeMessage]). */
class DraftMimeTests {
	private val date = Instant.parse("2026-03-05T14:07:00Z")

	private fun composed(
		html: String? = "<p>Hello <b>Bob</b></p>",
		text: String = "Hello Bob\n",
		subject: String? = "Project status",
		attachments: List<ComposedAttachment> = emptyList(),
		replyTo: MailAddress? = null,
		inReplyTo: String? = null,
		references: String? = null,
	) = ComposedMessage(
		messageId = MESSAGE_ID,
		from = MailAddress("jane.doe@example.test", "Jane Doe"),
		replyTo = replyTo,
		to = listOf(MailAddress("bob@example.test", "Bob Example")),
		cc = listOf(MailAddress("carol@example.test")),
		bcc = listOf(MailAddress("dave@example.test")),
		subject = subject,
		html = html,
		text = text,
		inReplyTo = inReplyTo,
		references = references,
		attachments = attachments,
	)

	/** Writes and parses [mime] again, like a server that stored it. */
	private fun roundTrip(mime: MimeMessage): MimeMessage {
		val bytes = ByteArrayOutputStream().also(mime::writeTo).toByteArray()
		return MimeMessage(Session.getInstance(Properties()), ByteArrayInputStream(bytes))
	}

	private fun raw(mime: MimeMessage): String = ByteArrayOutputStream().also(mime::writeTo).toString(Charsets.US_ASCII)

	private fun Part.parts(): List<Part> = (content as Multipart).let { multipart -> (0 until multipart.count).map(multipart::getBodyPart) }

	private fun Part.type(): String = contentType.substringBefore(';').trim().lowercase()

	@Test
	fun `HTML with a plain-text alternative is multipart alternative, plain text first`() {
		val mime = roundTrip(DraftMime.build(composed(), date, draft = true))

		mime.type() shouldBe "multipart/alternative"
		val parts = mime.parts()
		parts.map { it.type() } shouldContainExactly listOf("text/plain", "text/html")
		parts[0].content shouldBe "Hello Bob\n"
		parts[1].content shouldBe "<p>Hello <b>Bob</b></p>"
		parts.forEach { it.contentType.lowercase() shouldContain "charset=utf-8" }
	}

	@Test
	fun `inline cid parts go with the HTML into multipart related, real attachments into mixed`() {
		val logo = ComposedAttachment("logo.png", "image/png", byteArrayOf(1, 2, 3), inline = true, contentId = "logo-1@example.test")
		val report = ComposedAttachment("report.csv", "text/csv", "a,b\n".toByteArray())
		val html = "<p>Figures</p><img src=\"cid:logo-1@example.test\">"

		val mime = roundTrip(DraftMime.build(composed(html = html, attachments = listOf(logo, report)), date, draft = true))

		mime.type() shouldBe "multipart/mixed"
		val (body, attachment) = mime.parts()
		attachment.fileName shouldBe "report.csv"
		attachment.disposition shouldBe Part.ATTACHMENT
		body.type() shouldBe "multipart/alternative"
		val (plain, related) = body.parts()
		plain.type() shouldBe "text/plain"
		related.type() shouldBe "multipart/related"
		val (htmlPart, image) = related.parts()
		htmlPart.type() shouldBe "text/html"
		htmlPart.content shouldBe html
		image.type() shouldBe "image/png"
		image.disposition shouldBe Part.INLINE
		(image as MimeBodyPart).contentID shouldBe "<logo-1@example.test>"
	}

	@Test
	fun `with only inline parts there is no multipart mixed`() {
		val logo = ComposedAttachment("logo.png", "image/png", byteArrayOf(1, 2, 3), inline = true, contentId = "logo-1@example.test")

		val mime = roundTrip(DraftMime.build(composed(attachments = listOf(logo)), date, draft = true))

		mime.type() shouldBe "multipart/alternative"
		mime.parts().map { it.type() } shouldContainExactly listOf("text/plain", "multipart/related")
	}

	@Test
	fun `without HTML the message is plain text only`() {
		val mime = roundTrip(DraftMime.build(composed(html = null, text = "Only text\n"), date, draft = true))

		mime.type() shouldBe "text/plain"
		mime.content shouldBe "Only text\n"
	}

	@Test
	fun `attachments wrap the body in multipart mixed, inline parts stay with the HTML`() {
		val pdf = ComposedAttachment("agenda.pdf", "application/pdf", byteArrayOf(1, 2, 3, 4))
		val logo = ComposedAttachment("logo.png", "image/png", byteArrayOf(9, 8, 7), inline = true, contentId = "logo@example.test")
		val mime = roundTrip(DraftMime.build(composed(attachments = listOf(pdf, logo)), date, draft = true))

		mime.type() shouldBe "multipart/mixed"
		val parts = mime.parts()
		parts.map { it.type() } shouldContainExactly listOf("multipart/alternative", "application/pdf")
		parts[0].parts().map { it.type() } shouldContainExactly listOf("text/plain", "multipart/related")

		parts[1].disposition shouldBe Part.ATTACHMENT
		parts[1].fileName shouldBe "agenda.pdf"
		parts[1].inputStream.readAllBytes().toList() shouldBe listOf<Byte>(1, 2, 3, 4)
		parts[1].getHeader("Content-ID").shouldBeNull()

		val related = parts[0].parts()[1].parts()
		related.map { it.type() } shouldContainExactly listOf("text/html", "image/png")
		related[1].disposition shouldBe Part.INLINE
		related[1].getHeader("Content-ID").single() shouldBe "<logo@example.test>"
		related[1].inputStream.readAllBytes().toList() shouldBe listOf<Byte>(9, 8, 7)
	}

	@Test
	fun `plain text with attachments is mixed with a text part first`() {
		val file = ComposedAttachment("notes.txt", "text/plain", "Fictitious notes".toByteArray())
		val mime = roundTrip(DraftMime.build(composed(html = null, attachments = listOf(file)), date, draft = false))

		mime.parts().map { it.type() } shouldContainExactly listOf("text/plain", "text/plain")
		mime.parts()[0].content shouldBe "Hello Bob\n"
		mime.parts()[1].fileName shouldBe "notes.txt"
	}

	@Test
	fun `the given Message-ID is kept through saveChanges and storing`() {
		val built = DraftMime.build(composed(), date, draft = true)
		built.messageID shouldBe MESSAGE_ID

		built.saveChanges()
		built.messageID shouldBe MESSAGE_ID
		roundTrip(built).messageID shouldBe MESSAGE_ID
	}

	@Test
	fun `a copy of a stored message keeps the given Message-ID when changed`() {
		val stored = roundTrip(DraftMime.build(composed(), date, draft = true))
		val copy = FixedIdMimeMessage(stored, MESSAGE_ID)

		copy.subject = "Changed"
		copy.saveChanges()

		copy.messageID shouldBe MESSAGE_ID
		roundTrip(copy).subject shouldBe "Changed"
	}

	@Test
	fun `a plain MimeMessage would replace the id, which is why the fixed one exists`() {
		val stored = roundTrip(DraftMime.build(composed(), date, draft = true))
		val plain = MimeMessage(stored)
		plain.saveChanges()

		plain.messageID shouldStartWith "<"
		(plain.messageID == MESSAGE_ID) shouldBe false
	}

	@Test
	fun `a draft is flagged Draft and Seen, an outgoing message only Seen`() {
		val draft = DraftMime.build(composed(), date, draft = true)
		draft.isSet(Flags.Flag.DRAFT) shouldBe true
		draft.isSet(Flags.Flag.SEEN) shouldBe true

		val outgoing = DraftMime.build(composed(), date, draft = false)
		outgoing.isSet(Flags.Flag.DRAFT) shouldBe false
		outgoing.isSet(Flags.Flag.SEEN) shouldBe true
	}

	@Test
	fun `addresses, date and threading headers are set`() {
		val references = (1..8).joinToString(" ") { "<a-rather-long-message-id-number-$it@mail.example.test>" }
		val mime =
			roundTrip(
				DraftMime.build(
					composed(replyTo = MailAddress("help@example.test", "Help Desk"), inReplyTo = "<orig@example.test>", references = references),
					date,
					draft = true,
				),
			)

		(mime.from.single() as InternetAddress).let {
			it.address shouldBe "jane.doe@example.test"
			it.personal shouldBe "Jane Doe"
		}
		(mime.replyTo.single() as InternetAddress).address shouldBe "help@example.test"
		mime.getRecipients(Message.RecipientType.TO).map { (it as InternetAddress).address } shouldContainExactly listOf("bob@example.test")
		mime.getRecipients(Message.RecipientType.CC).map { (it as InternetAddress).address } shouldContainExactly listOf("carol@example.test")
		mime.getRecipients(Message.RecipientType.BCC).map { (it as InternetAddress).address } shouldContainExactly listOf("dave@example.test")
		mime.sentDate.toInstant() shouldBe date
		mime.getHeader("In-Reply-To").single() shouldBe "<orig@example.test>"
		// Folded to stay within the line length limit, the same ids after unfolding.
		val lines = raw(mime).lines().dropWhile { !it.startsWith("References:") }
		val header = listOf(lines.first()) + lines.drop(1).takeWhile { it.startsWith(" ") || it.startsWith("\t") }
		(header.size > 1) shouldBe true
		header.forEach { (it.length <= 78) shouldBe true }
		MimeUtility.unfold(mime.getHeader("References").single()).split(Regex("\\s+")) shouldContainExactly references.split(" ")
	}

	@Test
	fun `without Reply-To and threading the headers are absent`() {
		val mime = roundTrip(DraftMime.build(composed(), date, draft = true))

		mime.getHeader("Reply-To").shouldBeNull()
		mime.getHeader("In-Reply-To").shouldBeNull()
		mime.getHeader("References").shouldBeNull()
	}

	@Test
	fun `non-ASCII subject, names, body and file names are encoded as UTF-8`() {
		val attachment = ComposedAttachment("Übersicht März.pdf", "application/pdf", byteArrayOf(1))
		val message =
			composed(subject = "Grüezi – Café ✓", html = "<p>Schöne Grüsse</p>", text = "Schöne Grüsse\n", attachments = listOf(attachment))
				.copy(from = MailAddress("jane.doe@example.test", "Jäne Döe"))
		val built = DraftMime.build(message, date, draft = true)

		val raw = raw(built)
		raw shouldNotContain "Grüezi"
		raw shouldContain "=?UTF-8?"

		val stored = roundTrip(built)
		stored.subject shouldBe "Grüezi – Café ✓"
		(stored.from.single() as InternetAddress).personal shouldBe "Jäne Döe"
		val parts = stored.parts()
		// Quoted-printable text uses canonical CRLF line ends on the wire.
		(parts[0].parts()[0].content as String).replace("\r\n", "\n") shouldBe "Schöne Grüsse\n"
		MimeUtility.decodeText(parts[1].fileName) shouldBe "Übersicht März.pdf"
	}

	@Test
	fun `a missing subject is an empty Subject header`() {
		roundTrip(DraftMime.build(composed(subject = null), date, draft = true)).subject shouldBe ""
	}

	@Test
	fun `recipients and size add up all parts`() {
		val message =
			composed(html = "<p>x</p>", text = "x\n", attachments = listOf(ComposedAttachment("a.bin", "application/octet-stream", ByteArray(100))))

		message.recipients.map { it.address } shouldContainExactly listOf("bob@example.test", "carol@example.test", "dave@example.test")
		message.sizeBytes shouldBe 100L + "x\n".length + "<p>x</p>".length
	}

	/** No header line of [mime] (as sent) may start a new `Bcc:` header beyond the one with the real Bcc recipient. */
	private fun shouldHaveNoInjectedBcc(mime: MimeMessage) {
		val stored = roundTrip(mime)
		stored.getRecipients(Message.RecipientType.BCC).map { (it as InternetAddress).address } shouldContainExactly listOf("dave@example.test")
		stored.getHeader("Bcc").single() shouldNotContain "evil.test"
		val headerLines = raw(mime).split("\r\n").takeWhile { it.isNotEmpty() }
		headerLines.filter { it.startsWith("Bcc:", ignoreCase = true) } shouldContainExactly listOf("Bcc: dave@example.test")
	}

	@Test
	fun `a display name with CRLF cannot inject a Bcc header`() {
		val message =
			composed().copy(
				from = MailAddress("jane.doe@example.test", "Jane\r\nBcc: x@evil.test"),
				to = listOf(MailAddress("bob@example.test", "Bob\r\nBcc: x@evil.test")),
			)

		shouldHaveNoInjectedBcc(DraftMime.build(message, date, draft = false))
	}

	@Test
	fun `a subject with CRLF cannot inject a Bcc header`() {
		val mime = DraftMime.build(composed(subject = "Status\r\nBcc: x@evil.test"), date, draft = false)

		shouldHaveNoInjectedBcc(mime)
	}

	@Test
	fun `an attachment file name with CRLF cannot inject a Bcc header`() {
		val attachment = ComposedAttachment("report.pdf\r\nBcc: x@evil.test", "application/pdf", ByteArray(10))

		shouldHaveNoInjectedBcc(DraftMime.build(composed(attachments = listOf(attachment)), date, draft = false))
	}

	@Test
	fun `an attachment's toString shows no content`() {
		ComposedAttachment("secret.txt", "text/plain", "Fictitious secret".toByteArray()).toString() shouldNotContain "Fictitious"
	}

	private companion object {
		const val MESSAGE_ID = "<01900000-0000-7000-8000-000000000001@example.test>"
	}
}
