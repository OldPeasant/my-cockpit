package ch.sonensei.cockpit.mail.api

import ch.sonensei.cockpit.mail.model.MailMessage
import ch.sonensei.cockpit.mail.model.MailMessageLocator
import ch.sonensei.cockpit.mail.sanitize.HtmlMailSanitizer
import ch.sonensei.cockpit.mail.sanitize.RemoteImagePolicy
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import jakarta.mail.Session
import jakarta.mail.internet.MimeMessage
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.util.Properties
import java.util.UUID

/**
 * [MailReadService.read] for inline images shown by their position ([MimeContent.partImage] references): resolved after
 * sanitizing to the inline download, but only for a raster part of the same message that no `cid:` references.
 */
class MailReadServiceInlineImagesTests {
	private val locator = mockk<MailMessageLocator>()
	private val loader = mockk<MailContentLoader>()
	private val service = MailReadService(locator, mockk(), mockk(), mockk(), mockk(), loader, HtmlMailSanitizer())
	private val message = MailMessage(UUID.randomUUID(), UUID.randomUUID(), 42L)

	private fun read(content: MailContent): MailMessageView {
		every { locator.current(message.id) } returns message
		every { loader.load(message) } returns content
		return service.read(message.id, RemoteImagePolicy.BLOCK)
	}

	private fun parse(raw: String): MimeMessage =
		MimeMessage(
			Session.getInstance(Properties()),
			raw
				.trimIndent()
				.replace("\n", "\r\n")
				.toByteArray(Charsets.UTF_8)
				.inputStream(),
		)

	/** The `src` of each `<img>` in [html] by its `alt` (`null` when it has none). */
	private fun sources(html: String): Map<String, String?> =
		Jsoup.parseBodyFragment(html).select("img").associate { img ->
			img.attr("alt") to img.attr("src").takeIf { img.hasAttr("src") }
		}

	private fun inlineUrl(partId: String) = MailReadService.attachmentUrl(message.id, partId, inline = true)

	@Test
	fun `the reported Apple Mail message shows its image through the inline download, after the text`() {
		val content =
			MimeContent.read(
				parse(
					"""
					Subject: Fictitious invitation
					MIME-Version: 1.0
					Content-Type: multipart/mixed; boundary="Apple-Mail-5F1A2B3C-0000-4D5E-8F90-ABCDEF012345"

					--Apple-Mail-5F1A2B3C-0000-4D5E-8F90-ABCDEF012345
					Content-Type: text/html; charset=UTF-8

					<p>Invitation to the fictitious apero</p>
					--Apple-Mail-5F1A2B3C-0000-4D5E-8F90-ABCDEF012345
					Content-Type: image/jpeg; name=image0.jpeg; x-apple-part-url="5F1A2B3C-0000"
					Content-Disposition: inline; filename=image0.jpeg
					Content-Transfer-Encoding: base64

					/9j/
					--Apple-Mail-5F1A2B3C-0000-4D5E-8F90-ABCDEF012345--
					""",
				),
				1024,
			)

		val view = read(content)

		val html = view.body.html.shouldNotBeNull()
		sources(html) shouldContainExactly mapOf("image0.jpeg" to inlineUrl("2"))
		html.indexOf("apero") shouldBeLessThan html.indexOf("<img")
		html shouldNotContain "cid:"
		view.attachments.map { Triple(it.partId, it.filename, it.inline) } shouldContainExactly
			listOf(Triple("2", "image0.jpeg", false))
	}

	@Test
	fun `a forged part reference only resolves to a raster part no cid references`() {
		val html =
			listOf("pdf" to "2", "cid-image" to "3", "svg" to "4", "unknown" to "9", "malformed" to "0", "jpeg" to "5", "bmp" to "6")
				.joinToString("") { (alt, partId) -> "<img alt=\"$alt\" src=\"cid:cockpit-part-$partId@cockpit.invalid\">" }
		val content =
			MailContent(
				null,
				"<p>$html</p>",
				listOf(
					MailAttachment("2", "plan.pdf", "application/pdf", 10, inline = false, contentId = null),
					MailAttachment("3", "logo.png", "image/png", 10, inline = true, contentId = "logo@example.test"),
					MailAttachment("4", "logo.svg", "image/svg+xml", 10, inline = false, contentId = null),
					MailAttachment("5", "photo.jpeg", "image/jpeg", 10, inline = false, contentId = null),
					MailAttachment("6", "scan.bmp", "image/bmp", 10, inline = false, contentId = null),
				),
			)

		val resolved = read(content).body.html.shouldNotBeNull()

		sources(resolved) shouldContainExactly
			mapOf(
				"pdf" to null,
				"cid-image" to null,
				"svg" to null,
				"unknown" to null,
				"malformed" to null,
				"jpeg" to inlineUrl("5"),
				"bmp" to inlineUrl("6"),
			)
		resolved shouldNotContain "cid:"
	}

	@Test
	fun `a mail forging part references to its own PDF or cid image shows neither`() {
		val body =
			"<p><img alt=\"logo\" src=\"cid:logo@example.test\">" +
				"<img alt=\"forged-pdf\" src=\"cid:cockpit-part-2@cockpit.invalid\">" +
				"<img alt=\"forged-logo\" src=\"cid:cockpit-part-1.2@cockpit.invalid\"></p>"
		val content =
			MimeContent.read(
				parse(
					"""
					Subject: Fictitious forgery
					MIME-Version: 1.0
					Content-Type: multipart/mixed; boundary="b"

					--b
					Content-Type: multipart/related; boundary="r"

					--r
					Content-Type: text/html; charset=UTF-8

					$body
					--r
					Content-Type: image/png; name="logo.png"
					Content-ID: <logo@example.test>

					PNG
					--r--
					--b
					Content-Type: application/pdf; name="plan.pdf"
					Content-Disposition: inline; filename="plan.pdf"

					%PDF
					--b--
					""",
				),
				1024,
			)

		val view = read(content)

		sources(view.body.html.shouldNotBeNull()) shouldContainExactly
			mapOf("logo" to inlineUrl("1.2"), "forged-pdf" to null, "forged-logo" to null)
		view.attachments.map { Triple(it.partId, it.contentType, it.inline) } shouldContainExactly
			listOf(Triple("1.2", "image/png", true), Triple("2", "application/pdf", false))
	}

	@Test
	fun `a cid reference written with character references shows its image once, through the inline download`() {
		val content =
			MimeContent.read(
				parse(
					"""
					Subject: Fictitious entities
					MIME-Version: 1.0
					Content-Type: multipart/related; boundary="r"

					--r
					Content-Type: text/html; charset=UTF-8

					<p><img alt="logo" src="cid:logo%40example&#46;test"></p>
					--r
					Content-Type: image/png; name="logo.png"
					Content-ID: <logo@example.test>

					PNG
					--r--
					""",
				),
				1024,
			)

		val view = read(content)

		val html = view.body.html.shouldNotBeNull()
		Jsoup.parseBodyFragment(html).select("img").map { it.attr("src") } shouldContainExactly listOf(inlineUrl("2"))
		view.attachments.map { it.partId to it.inline } shouldContainExactly listOf("2" to true)
	}

	@Test
	fun `a plain-text-only mail with an image gets an escaped HTML body with the image below the text`() {
		val content =
			MimeContent.read(
				parse(
					"""
					Subject: Fictitious plain
					MIME-Version: 1.0
					Content-Type: multipart/mixed; boundary="b"

					--b
					Content-Type: text/plain; charset=UTF-8

					Hi <script>alert(1)</script> <img src=x onerror=alert(1)>
					--b
					Content-Type: image/png
					Content-Disposition: inline; filename="photo.png"

					PNG
					--b--
					""",
				),
				1024,
			)

		val view = read(content)

		val html = view.body.html.shouldNotBeNull()
		html shouldNotContain "<script"
		html shouldContain "&lt;img src=x onerror=alert(1)&gt;"
		sources(html) shouldContainExactly mapOf("photo.png" to inlineUrl("2"))
		html.indexOf("Hi ") shouldBeLessThan html.indexOf("<img")
		view.body.text shouldBe "Hi <script>alert(1)</script> <img src=x onerror=alert(1)>"
	}
}
