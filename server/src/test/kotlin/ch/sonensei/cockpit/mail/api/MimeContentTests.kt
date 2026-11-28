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

		// The related image has no Content-ID, so no cid: can show it: it is listed and shown at its position.
		content.textHtml shouldBe
			"<div>Body text</div>\n<img src=\"cid:a\">\n" +
			"<div><img src=\"cid:cockpit-part-2.2@cockpit.invalid\" alt=\"in-related.gif\"></div>"
		content.attachments.map { Triple(it.partId, it.filename, it.inline) } shouldContainExactly
			listOf(Triple("2.2", "in-related.gif", false), Triple("3", "in-mixed.gif", false))
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
	fun `a second plain text part is appended to the body, not listed`() {
		val content = read(mixed("Content-Type: text/plain; charset=UTF-8\n\nSecond text"))

		content.textPlain shouldBe "Body text\nSecond text"
		content.textHtml.shouldBeNull()
		content.attachments.shouldBeEmpty()
	}

	@Test
	fun `the reported Apple Mail structure lists the image and shows it after the text`() {
		val content = read(parse(appleMail("Content-Type: text/html; charset=UTF-8\n\n<p>Invitation</p>")))

		content.textPlain.shouldBeNull()
		content.textHtml shouldBe
			"<p>Invitation</p>\n<div><img src=\"cid:cockpit-part-2@cockpit.invalid\" alt=\"image0.jpeg\"></div>"
		content.attachments shouldContainExactly
			listOf(MailAttachment("2", "image0.jpeg", "image/jpeg", 3, inline = false, contentId = null))
	}

	@Test
	fun `text after an image is in the body in order, plain only`() {
		val content =
			read(
				parse(
					appleMail(
						"Content-Type: text/plain; charset=UTF-8\n\nBefore <script>alert(1)</script>\nline two",
						"Content-Type: text/plain; charset=UTF-8\n\nAfter <img src=x onerror=alert(1)>",
					),
				),
			)

		content.textPlain shouldBe "Before <script>alert(1)</script>\r\nline two\nAfter <img src=x onerror=alert(1)>"
		content.textHtml shouldBe
			"<div>Before &lt;script&gt;alert(1)&lt;/script&gt;<br>line two</div>\n" +
			"<div><img src=\"cid:cockpit-part-2@cockpit.invalid\" alt=\"image0.jpeg\"></div>\n" +
			"<div>After &lt;img src=x onerror=alert(1)&gt;</div>"
		content.attachments.single().inline shouldBe false
	}

	@Test
	fun `text after an image is in the body in order, as alternatives`() {
		fun alternative(text: String) =
			"""
			Content-Type: multipart/alternative; boundary="alt"

			--alt
			Content-Type: text/plain; charset=UTF-8

			$text
			--alt
			Content-Type: text/html; charset=UTF-8

			<p>$text</p>
			--alt--
			""".trimIndent()
		val content = read(parse(appleMail(alternative("First"), alternative("Second"))))

		content.textPlain shouldBe "First\nSecond"
		content.textHtml shouldBe
			"<p>First</p>\n<div><img src=\"cid:cockpit-part-2@cockpit.invalid\" alt=\"image0.jpeg\"></div>\n<p>Second</p>"
		content.attachments.map { it.partId } shouldContainExactly listOf("2")
	}

	@Test
	fun `a plain-only mail with a non-raster inline part gets no HTML body`() {
		val content =
			read(
				mixed(
					"""
					Content-Type: application/pdf; name="plan.pdf"
					Content-Disposition: inline; filename="plan.pdf"

					%PDF
					""",
					"""
					Content-Type: image/heic; name="photo.heic"
					Content-Disposition: inline; filename="photo.heic"

					HEIC
					""",
				),
			)

		content.textHtml.shouldBeNull()
		content.attachments.map { Triple(it.partId, it.contentType, it.inline) } shouldContainExactly
			listOf(Triple("2", "application/pdf", false), Triple("3", "image/heic", false))
	}

	@Test
	fun `a non-raster inline part next to HTML is listed and not put into the body`() {
		val content =
			read(
				parse(
					appleMail(
						"Content-Type: text/html; charset=UTF-8\n\n<p>Hi</p>",
						image = "Content-Type: application/pdf; name=plan.pdf\nContent-Disposition: inline; filename=plan.pdf\n\n%PDF",
					),
				),
			)

		content.textHtml shouldBe "<p>Hi</p>"
		content.attachments.single().inline shouldBe false
	}

	@Test
	fun `an unreferenced image with a Content-ID in mixed is listed and shown, not inline`() {
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

		content.attachments.single().inline shouldBe false
		content.attachments.single().contentId shouldBe "photo@example.test"
		content.textPlain shouldBe "Body text"
		content.textHtml shouldBe
			"<div>Body text</div>\n<div><img src=\"cid:cockpit-part-2@cockpit.invalid\" alt=\"photo.png\"></div>"
	}

	@Test
	fun `cid references that are percent-encoded, bracketed or in another case keep their images inline, shown once`() {
		val html =
			"<p><img src=\"cid:one%40example.test\"><img src=\"cid:<two@example.test>\"><img src=\"CID:Three@Example.Test\">" +
				"<img src=\"cid:a+b@example.test\"><img src=\"cid:100%@example.test\"></p>"
		val content =
			read(
				parse(
					"""
					Subject: Fictitious
					MIME-Version: 1.0
					Content-Type: multipart/related; boundary="r"

					--r
					Content-Type: text/html; charset=UTF-8

					$html
					--r
					Content-Type: image/png; name="one.png"
					Content-ID: <one@example.test>

					PNG
					--r
					Content-Type: image/png; name="two.png"
					Content-ID: <two@example.test>

					PNG
					--r
					Content-Type: image/png; name="three.png"
					Content-ID: <three@example.test>

					PNG
					--r
					Content-Type: image/png; name="plus.png"
					Content-ID: <a+b@example.test>

					PNG
					--r
					Content-Type: image/png; name="percent.png"
					Content-ID: <100%@example.test>

					PNG
					--r--
					""",
				),
			)

		// "+" is no encoded space in a cid: URL; a malformed escape is compared as written.
		content.textHtml shouldBe html
		content.attachments.map { it.partId to it.inline } shouldContainExactly
			listOf("2" to true, "3" to true, "4" to true, "5" to true, "6" to true)
	}

	@Test
	fun `a cid reference written with HTML character references keeps its image inline, shown once`() {
		val html = "<p><img src=\"cid:pic&#64;example.test\"></p>"
		val content =
			read(
				parse(
					"""
					Subject: Fictitious
					MIME-Version: 1.0
					Content-Type: multipart/related; boundary="r"

					--r
					Content-Type: text/html; charset=UTF-8

					$html
					--r
					Content-Type: image/png; name="pic.png"
					Content-ID: <pic@example.test>

					PNG
					--r--
					""",
				),
			)

		// The browser (and MailReadService after parsing) reads the src as cid:pic@example.test.
		content.textHtml shouldBe html
		content.attachments.single().inline shouldBe true
	}

	@Test
	fun `a cid image in the related HTML variant of an alternative stays inline and is not appended`() {
		val content =
			read(
				parse(
					"""
					Subject: Fictitious
					MIME-Version: 1.0
					Content-Type: multipart/alternative; boundary="a"

					--a
					Content-Type: text/plain; charset=UTF-8

					Plain
					--a
					Content-Type: multipart/related; boundary="r"

					--r
					Content-Type: text/html; charset=UTF-8

					<p>Hi<img src="cid:pic@example.test"></p>
					--r
					Content-Type: image/jpeg; name="pic.jpg"
					Content-Disposition: inline; filename="pic.jpg"
					Content-ID: <pic@example.test>

					JPEG
					--r--
					--a--
					""",
				),
			)

		content.textPlain shouldBe "Plain"
		content.textHtml shouldBe "<p>Hi<img src=\"cid:pic@example.test\"></p>"
		content.attachments.map { Triple(it.partId, it.contentId, it.inline) } shouldContainExactly
			listOf(Triple("2.2", "pic@example.test", true))
	}

	@Test
	fun `an alternative without an HTML variant is shown as its escaped plain text next to HTML parts`() {
		val content =
			read(
				parse(
					appleMail(
						"Content-Type: text/html; charset=UTF-8\n\n<p>Before</p>",
						"""
						Content-Type: multipart/alternative; boundary="alt"

						--alt
						Content-Type: text/plain; charset=UTF-8

						After <b>
						--alt
						Content-Type: text/enriched

						<bold>After</bold>
						--alt--
						""".trimIndent(),
					),
				),
			)

		content.textPlain shouldBe "After <b>"
		content.textHtml shouldBe
			"<p>Before</p>\n<div><img src=\"cid:cockpit-part-2@cockpit.invalid\" alt=\"image0.jpeg\"></div>\n<div>After &lt;b&gt;</div>"
		content.attachments.map { Triple(it.partId, it.contentType, it.inline) } shouldContainExactly
			listOf(Triple("2", "image/jpeg", false), Triple("3.2", "text/enriched", false))
	}

	@Test
	fun `alternatives with only one text variant still frame a positioned image`() {
		fun alternative(variant: String) =
			"Content-Type: multipart/alternative; boundary=\"alt\"\n\n--alt\n$variant\n--alt\n" +
				"Content-Type: text/enriched\n\n<bold>x</bold>\n--alt--"

		val htmlOnly = read(parse(appleMail(alternative("Content-Type: text/html; charset=UTF-8\n\n<p>Hi</p>"))))
		val plainOnly = read(parse(appleMail(alternative("Content-Type: text/plain; charset=UTF-8\n\nHi & bye"))))

		htmlOnly.textPlain.shouldBeNull()
		htmlOnly.textHtml shouldBe "<p>Hi</p>\n<div><img src=\"cid:cockpit-part-2@cockpit.invalid\" alt=\"image0.jpeg\"></div>"
		plainOnly.textPlain shouldBe "Hi & bye"
		plainOnly.textHtml shouldBe
			"<div>Hi &amp; bye</div>\n<div><img src=\"cid:cockpit-part-2@cockpit.invalid\" alt=\"image0.jpeg\"></div>"
	}

	@Test
	fun `the size limit applies per text part, so joined text may exceed it`() {
		val content =
			read(
				parse(
					appleMail(
						"Content-Type: text/plain; charset=UTF-8\n\n0123456789abcdef",
						"Content-Type: text/plain; charset=UTF-8\n\nfedcba9876543210",
					),
				),
				maxTextBytes = 10,
			)

		content.textPlain shouldBe "0123456789\nfedcba9876"
		content.textHtml shouldBe
			"<div>0123456789</div>\n<div><img src=\"cid:cockpit-part-2@cockpit.invalid\" alt=\"image0.jpeg\"></div>\n<div>fedcba9876</div>"
	}

	@ParameterizedTest
	@ValueSource(strings = ["image/png", "image/gif", "image/webp", "IMAGE/JPEG"])
	fun `unreferenced inline raster images of every shown type are put into the body`(type: String) {
		val content = read(mixed("Content-Type: $type\nContent-Disposition: inline; filename=\"pic\"\n\nx"))

		content.textHtml shouldBe "<div>Body text</div>\n<div><img src=\"cid:cockpit-part-2@cockpit.invalid\" alt=\"pic\"></div>"
		content.attachments.single().inline shouldBe false
	}

	@Test
	fun `SVG and raster parts with attachment disposition are listed but not put into the body`() {
		val content =
			read(
				mixed(
					"Content-Type: image/svg+xml\nContent-Disposition: inline; filename=\"logo.svg\"\n\n<svg onload=\"alert(1)\"/>",
					"Content-Type: image/jpeg\nContent-Disposition: attachment; filename=\"photo.jpg\"\n\n/9j/",
				),
			)

		content.textHtml.shouldBeNull()
		content.textPlain shouldBe "Body text"
		content.attachments.map { Triple(it.partId, it.contentType, it.inline) } shouldContainExactly
			listOf(Triple("2", "image/svg+xml", false), Triple("3", "image/jpeg", false))
	}

	@Test
	fun `the file name of a positioned image is escaped in its alt text`() {
		val content =
			read(
				mixed(
					"Content-Type: image/png\nContent-Disposition: inline; filename*=UTF-8''x%22%20onerror%3D%22alert%281%29%3C%3E%26%27.png\n\nx",
				),
			)

		content.attachments.single().filename shouldBe "x\" onerror=\"alert(1)<>&'.png"
		content.textHtml shouldBe
			"<div>Body text</div>\n" +
			"<div><img src=\"cid:cockpit-part-2@cockpit.invalid\" alt=\"x&quot; onerror=&quot;alert(1)&lt;&gt;&amp;&#39;.png\"></div>"
	}

	@Test
	fun `an image before the first text and text with CRLF line ends keep their order and breaks`() {
		val message =
			parse(
				"""
				Subject: Fictitious
				MIME-Version: 1.0
				Content-Type: multipart/mixed; boundary="b"

				--b
				Content-Type: image/gif
				Content-Disposition: inline

				GIF89a
				--b
				Content-Type: text/plain; charset=UTF-8

				one
				two
				--b--
				""",
			)

		val content = read(message)

		content.textPlain shouldBe "one\r\ntwo"
		content.textHtml shouldBe "<div><img src=\"cid:cockpit-part-1@cockpit.invalid\" alt=\"\"></div>\n<div>one<br>two</div>"
	}

	@Test
	fun `partImage references round-trip and only well-formed part ids are accepted`() {
		MimeContent.partImage("1.2") shouldBe "cid:cockpit-part-1.2@cockpit.invalid"
		MimeContent.partImageId("cockpit-part-1.2@cockpit.invalid") shouldBe "1.2"
		MimeContent.partImageId("COCKPIT-PART-3@COCKPIT.INVALID") shouldBe "3"
		listOf(
			"cockpit-part-0@cockpit.invalid",
			"cockpit-part-../1@cockpit.invalid",
			"cockpit-part-@cockpit.invalid",
			"cockpit-part-1@example.test",
			"other-part-1@cockpit.invalid",
			"cockpit-part-1.2.3.4.5.6.7.8.9.10.11@cockpit.invalid",
			"logo@example.test",
		).forEach { MimeContent.partImageId(it).shouldBeNull() }
	}

	/** The reported Apple Mail message: [texts] around one inline JPEG without Content-ID (or [image] instead). */
	private fun appleMail(
		vararg texts: String,
		image: String = APPLE_IMAGE,
	): String =
		buildString {
			append("Subject: Fictitious invitation\nMIME-Version: 1.0\n")
			append("Content-Type: multipart/mixed; boundary=\"Apple-Mail-5F1A2B3C-0000-4D5E-8F90-ABCDEF012345\"\n\n")
			val boundary = "--Apple-Mail-5F1A2B3C-0000-4D5E-8F90-ABCDEF012345"
			texts.forEachIndexed { index, text ->
				append(boundary).append("\n").append(text).append("\n")
				if (index == 0) append(boundary).append("\n").append(image).append("\n")
			}
			append(boundary).append("--\n")
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

		/** The image part as Apple Mail on an iPhone sends it: inline with a file name, but no Content-ID. */
		const val APPLE_IMAGE =
			"Content-Type: image/jpeg; name=image0.jpeg; x-apple-part-url=\"5F1A2B3C-0000-4D5E-8F90-ABCDEF012345\"\n" +
				"Content-Disposition: inline; filename=image0.jpeg\nContent-Transfer-Encoding: base64\n\n/9j/"

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
