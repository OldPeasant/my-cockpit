package ch.sonensei.cockpit.mail.api

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import jakarta.activation.DataHandler
import jakarta.mail.Session
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import jakarta.mail.util.ByteArrayDataSource
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.Base64
import java.util.Properties

/** The MIME tree reader on parsed messages (no IMAP server): body selection, attachment metadata and part lookup. */
class MimeContentTests {
	private fun parse(raw: String): MimeMessage =
		MimeMessage(
			Session.getInstance(Properties()),
			raw
				.trimIndent()
				.replace("\n", "\r\n")
				.toByteArray(Charsets.UTF_8)
				.inputStream(),
		)

	private fun parseBytes(raw: ByteArray): MimeMessage = MimeMessage(Session.getInstance(Properties()), raw.inputStream())

	private fun read(
		message: MimeMessage,
		maxTextBytes: Int = 1024 * 1024,
	) = MimeContent.read(message, maxTextBytes)

	private fun bytesOf(
		message: MimeMessage,
		partId: String,
	) = MimeContent
		.findPart(message, partId)
		.shouldNotBeNull()
		.inputStream
		.use { it.readAllBytes() }

	/** A multipart/mixed message with a text body followed by [parts] (each a raw part with headers and content). */
	private fun mixed(vararg parts: String): MimeMessage =
		parse(
			buildString {
				append("Subject: Fictitious\nMIME-Version: 1.0\nContent-Type: multipart/mixed; boundary=\"b\"\n\n")
				append("--b\nContent-Type: text/plain; charset=UTF-8\n\nBody text\n")
				parts.forEach { append("--b\n").append(it.trimIndent()).append("\n") }
				append("--b--\n")
			},
		)

	@Test
	fun `a single-part plain text message is the body at section 1`() {
		val message =
			parse(
				"""
				Subject: Plain
				MIME-Version: 1.0
				Content-Type: text/plain; charset=UTF-8
				Content-Transfer-Encoding: 8bit

				Grüezi Ann
				""",
			)

		val content = read(message)

		content.textPlain shouldBe "Grüezi Ann"
		content.textHtml.shouldBeNull()
		content.attachments.shouldBeEmpty()
		MimeContent.findPart(message, "1") shouldBeSameInstanceAs message
		MimeContent.findPart(message, "2").shouldBeNull()
		MimeContent.findPart(message, "1.1").shouldBeNull()
	}

	@Test
	fun `a single-part HTML message has no plain text`() {
		val content = read(parse("Content-Type: text/html; charset=UTF-8\n\n<p>Hi</p>"))

		content.textHtml shouldBe "<p>Hi</p>"
		content.textPlain.shouldBeNull()
		content.attachments.shouldBeEmpty()
	}

	@Test
	fun `a message without Content-Type is plain text`() {
		val content = read(parse("Subject: Old style\n\nJust text"))

		content.textPlain shouldBe "Just text"
		content.attachments.shouldBeEmpty()
	}

	@Test
	fun `mixed, related and alternative parts give the body, an inline cid image and an attachment`() {
		val message = parse(NEWSLETTER)

		val content = read(message)

		content.textPlain shouldBe "Plain body"
		content.textHtml shouldBe """<p>HTML body<img src="cid:logo@example.test"></p>"""
		content.attachments shouldContainExactly
			listOf(
				MailAttachment("1.2", "logo.png", "image/png", 12, inline = true, contentId = "logo@example.test"),
				MailAttachment("2", "report.pdf", "application/pdf", 21, inline = false, contentId = null),
			)
	}

	@Test
	fun `findPart returns leaves by IMAP section and decodes them`() {
		val message = parse(NEWSLETTER)

		bytesOf(message, "1.2").toList() shouldBe PNG.toList()
		bytesOf(message, "2").toString(Charsets.US_ASCII) shouldBe "%PDF-1.4\nfictitious\n"
		bytesOf(message, "1.1.1").toString(Charsets.UTF_8) shouldBe "Plain body"
		bytesOf(message, "1.1.2").toString(Charsets.UTF_8) shouldBe """<p>HTML body<img src="cid:logo@example.test"></p>"""
	}

	@Test
	fun `findPart does not return multiparts or sections past a leaf`() {
		val message = parse(NEWSLETTER)

		MimeContent.findPart(message, "1").shouldBeNull()
		MimeContent.findPart(message, "1.1").shouldBeNull()
		MimeContent.findPart(message, "3").shouldBeNull()
		MimeContent.findPart(message, "1.3").shouldBeNull()
		MimeContent.findPart(message, "2.1").shouldBeNull()
		MimeContent.findPart(message, "1.1.1.1").shouldBeNull()
	}

	@ParameterizedTest
	@ValueSource(strings = ["", "0", "01", "1.0", "-1", "a", "1.", ".1", "1..2", "1 ", " 1", "100000", "1/2", "1.2.3.4.5.6.7.8.9.10.11"])
	fun `malformed part ids find nothing`(partId: String) {
		MimeContent.findPart(parse(NEWSLETTER), partId).shouldBeNull()
	}

	@Test
	fun `a named part without disposition is an attachment in mixed but inline in related`() {
		val content =
			read(
				mixed(
					"""
					Content-Type: multipart/related; boundary="r"

					--r
					Content-Type: text/html; charset=UTF-8

					<img src="cid:a">
					--r
					Content-Type: image/gif; name="in-related.gif"

					GIF89a
					--r--
					""",
					"""
					Content-Type: image/gif; name="in-mixed.gif"

					GIF89a
					""",
				),
			)

		content.textHtml shouldBe """<img src="cid:a">"""
		content.attachments.map { Triple(it.partId, it.filename, it.inline) } shouldContainExactly
			listOf(Triple("2.2", "in-related.gif", true), Triple("3", "in-mixed.gif", false))
	}

	@Test
	fun `text parts that are attachments are not taken as the body`() {
		val content =
			read(
				mixed(
					"""
					Content-Type: text/plain; charset=UTF-8
					Content-Disposition: attachment

					attached without a name
					""",
					"""
					Content-Type: text/plain; charset=UTF-8; name="notes.txt"

					named notes
					""",
					"""
					Content-Type: text/html; charset=UTF-8
					Content-Disposition: attachment; filename="page.html"

					<p>attached page</p>
					""",
				),
			)

		content.textPlain shouldBe "Body text"
		content.textHtml.shouldBeNull()
		content.attachments.map { Triple(it.partId, it.filename, it.inline) } shouldContainExactly
			listOf(Triple("2", null, false), Triple("3", "notes.txt", false), Triple("4", "page.html", false))
	}

	@Test
	fun `a second plain text part is listed as an inline part, not appended to the body`() {
		val content = read(mixed("Content-Type: text/plain; charset=UTF-8\n\nSecond text"))

		content.textPlain shouldBe "Body text"
		content.attachments.map { Triple(it.partId, it.contentType, it.inline) } shouldContainExactly
			listOf(Triple("2", "text/plain", true))
	}

	@Test
	fun `an inline-disposition image with a name in mixed stays inline`() {
		val content =
			read(
				mixed(
					"""
					Content-Type: image/png; name="photo.png"
					Content-Disposition: inline; filename="photo.png"
					Content-ID: photo@example.test

					PNG
					""",
				),
			)

		content.attachments.single().inline shouldBe true
		content.attachments.single().contentId shouldBe "photo@example.test"
	}

	@Test
	fun `a forwarded message is one attachment and its text is not the body`() {
		val message =
			mixed(
				"""
				Content-Type: message/rfc822

				Subject: Inner
				Content-Type: text/plain; charset=UTF-8

				Inner text
				""",
			)

		val content = read(message)

		content.textPlain shouldBe "Body text"
		content.attachments.map { Triple(it.partId, it.contentType, it.inline) } shouldContainExactly
			listOf(Triple("2", "message/rfc822", false))
		MimeContent.findPart(message, "2").shouldNotBeNull().isMimeType("message/rfc822") shouldBe true
		MimeContent.findPart(message, "2.1").shouldBeNull()
	}

	@Test
	fun `text is decoded with its charset`() {
		val latin1 = "Content-Type: text/plain; charset=ISO-8859-1\n\nGrüezi Zürich".toByteArray(Charsets.ISO_8859_1)
		val windows = "Content-Type: text/plain; charset=windows-1252\n\n\u20ac 42".toByteArray(charset("windows-1252"))

		read(parseBytes(latin1)).textPlain shouldBe "Grüezi Zürich"
		read(parseBytes(windows)).textPlain shouldBe "\u20ac 42"
	}

	@Test
	fun `quoted-printable and base64 text parts are decoded`() {
		val qp = parse("Content-Type: text/plain; charset=UTF-8\nContent-Transfer-Encoding: quoted-printable\n\nGr=C3=BCezi")
		val base64 =
			parse(
				"Content-Type: text/html; charset=UTF-8\nContent-Transfer-Encoding: base64\n\n" +
					Base64.getEncoder().encodeToString("<p>Grüezi</p>".toByteArray()),
			)

		read(qp).textPlain shouldBe "Grüezi"
		read(base64).textHtml shouldBe "<p>Grüezi</p>"
	}

	@ParameterizedTest
	@ValueSource(strings = ["x-fictitious-charset", "not a charset!", "\"\""])
	fun `an unknown or malformed charset falls back to UTF-8`(charset: String) {
		read(parse("Content-Type: text/plain; charset=$charset\n\nGrüezi")).textPlain shouldBe "Grüezi"
	}

	@Test
	fun `an unparsable Content-Type parameter list still reads the text as UTF-8`() {
		read(parse("Content-Type: text/plain; charset=\"utf-8\n\nGrüezi")).textPlain shouldBe "Grüezi"
	}

	@Test
	fun `text parts are cut off after the byte limit`() {
		val message =
			parse(
				"""
				Content-Type: multipart/alternative; boundary="a"

				--a
				Content-Type: text/plain; charset=UTF-8

				0123456789abcdef
				--a
				Content-Type: text/html; charset=UTF-8

				<p>0123456789</p>
				--a--
				""",
			)

		val content = read(message, maxTextBytes = 10)

		content.textPlain shouldBe "0123456789"
		content.textHtml shouldBe "<p>0123456"
		read(message, maxTextBytes = 16).textPlain shouldBe "0123456789abcdef"
		read(message, maxTextBytes = 1).textPlain shouldBe "0"
	}

	@Test
	fun `RFC 2047 and RFC 2231 file names are decoded`() {
		val encoded = "=?UTF-8?B?" + Base64.getEncoder().encodeToString("Bericht März 2026.pdf".toByteArray()) + "?="
		val content =
			read(
				mixed(
					"Content-Type: application/pdf; name=\"$encoded\"\nContent-Disposition: attachment; filename=\"$encoded\"\n\nx",
					"Content-Type: application/pdf\nContent-Disposition: attachment; filename*=UTF-8''Offerte%20M%C3%BCller.pdf\n\nx",
					"Content-Type: application/pdf\nContent-Disposition: attachment; filename=\"=?ISO-8859-1?Q?R=E9sum=E9.pdf?=\"\n\nx",
				),
			)

		content.attachments.map { it.filename } shouldContainExactly listOf("Bericht März 2026.pdf", "Offerte Müller.pdf", "Résumé.pdf")
	}

	@Test
	fun `file names lose directories, control characters and surrounding blanks`() {
		val bell = "=?UTF-8?Q?evil=07name.txt?="
		val content =
			read(
				mixed(
					"Content-Type: application/octet-stream\nContent-Disposition: attachment; filename=\"../../etc/passwd\"\n\nx",
					"Content-Type: application/octet-stream\nContent-Disposition: attachment; filename=\"C:\\\\Users\\\\ann\\\\run.exe\"\n\nx",
					"Content-Type: application/octet-stream\nContent-Disposition: attachment; filename=\"$bell\"\n\nx",
					"Content-Type: application/octet-stream\nContent-Disposition: attachment; filename=\"  spaced.txt  \"\n\nx",
					"Content-Type: application/octet-stream\nContent-Disposition: attachment; filename=\"   \"\n\nx",
					"Content-Type: application/octet-stream\nContent-Disposition: attachment; filename=\"folder/\"\n\nx",
				),
			)

		content.attachments.map { it.filename } shouldContainExactly listOf("passwd", "run.exe", "evilname.txt", "spaced.txt", null, null)
	}

	@Test
	fun `a malformed encoded word in a file name does not fail reading`() {
		val content =
			read(mixed("Content-Type: application/octet-stream\nContent-Disposition: attachment; filename=\"=?x-unknown?Q?a?=.bin\"\n\nx"))

		content.attachments
			.single()
			.filename
			.shouldNotBeNull()
	}

	@Test
	fun `sizes are estimated from the transfer encoding`() {
		val content =
			read(
				mixed(
					"Content-Type: application/octet-stream; name=\"a.bin\"\nContent-Transfer-Encoding: base64\n\nAAAAAAAAAAAAAAAA",
					"Content-Type: application/octet-stream; name=\"b.bin\"\nContent-Transfer-Encoding: 7bit\n\n0123456789",
				),
			)

		content.attachments.map { it.sizeBytes } shouldContainExactly listOf(12L, 10L)
	}

	@Test
	fun `a part whose size is unknown reports none`() {
		val part =
			MimeBodyPart().apply {
				dataHandler = DataHandler(ByteArrayDataSource(byteArrayOf(1, 2, 3), "application/pdf"))
				fileName = "unsized.pdf"
				disposition = MimeBodyPart.ATTACHMENT
			}
		val message = MimeMessage(Session.getInstance(Properties()))
		message.setContent(MimeMultipart(MimeBodyPart().apply { setText("Body") }, part))
		message.saveChanges()

		val attachment = read(message).attachments.single()

		attachment.filename shouldBe "unsized.pdf"
		attachment.sizeBytes.shouldBeNull()
	}

	@Test
	fun `content ids lose their angle brackets and blank ones count as none`() {
		val content =
			read(
				mixed(
					"Content-Type: image/png\nContent-Disposition: inline\nContent-ID:   <a@example.test>  \n\nx",
					"Content-Type: image/png\nContent-Disposition: inline\nContent-ID: <>\n\nx",
					"Content-Type: image/png\nContent-Disposition: inline\n\nx",
				),
			)

		content.attachments.map { it.contentId } shouldContainExactly listOf("a@example.test", null, null)
	}

	@Test
	fun `media types are lower case without parameters, octet-stream when unparsable`() {
		val content =
			read(
				mixed(
					"Content-Type: IMAGE/PNG; name=\"upper.png\"\n\nx",
					"Content-Type: garbage\nContent-Disposition: attachment; filename=\"odd.bin\"\n\nx",
				),
			)

		content.attachments.map { it.contentType } shouldContainExactly listOf("image/png", MimeContent.OCTET_STREAM)
	}

	@Test
	fun `a part with a malformed disposition is still listed and does not fail reading`() {
		// On IMAP the server parses the disposition (BODYSTRUCTURE); a locally parsed header may throw, which counts as absent.
		val content = read(mixed("Content-Type: application/pdf; name=\"x.pdf\"\nContent-Disposition: ;;;\n\nx"))

		content.textPlain shouldBe "Body text"
		content.attachments.single().contentType shouldBe "application/pdf"
	}

	private companion object {
		val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3)

		val NEWSLETTER =
			"""
			Subject: Fictitious newsletter
			MIME-Version: 1.0
			Content-Type: multipart/mixed; boundary="mixed"

			--mixed
			Content-Type: multipart/related; boundary="rel"

			--rel
			Content-Type: multipart/alternative; boundary="alt"

			--alt
			Content-Type: text/plain; charset=UTF-8

			Plain body
			--alt
			Content-Type: text/html; charset=UTF-8

			<p>HTML body<img src="cid:logo@example.test"></p>
			--alt--
			--rel
			Content-Type: image/png; name="logo.png"
			Content-Transfer-Encoding: base64
			Content-ID: <logo@example.test>

			${Base64.getEncoder().encodeToString(PNG)}
			--rel--
			--mixed
			Content-Type: application/pdf; name="report.pdf"
			Content-Disposition: attachment; filename="report.pdf"
			Content-Transfer-Encoding: base64

			${Base64.getEncoder().encodeToString("%PDF-1.4\nfictitious\n".toByteArray())}
			--mixed--
			"""
	}
}
