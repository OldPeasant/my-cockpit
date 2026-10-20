package ch.sonensei.cockpit.mail.sync

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.eclipse.angus.mail.iap.Protocol
import org.eclipse.angus.mail.imap.protocol.BODYSTRUCTURE
import org.eclipse.angus.mail.imap.protocol.FetchResponse
import org.eclipse.angus.mail.imap.protocol.IMAPResponse
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.io.ByteArrayInputStream
import java.io.OutputStream
import java.io.PrintStream
import java.util.Base64
import java.util.Properties

/** The pure helpers of [ImapRemoteMailbox]: UID sets, BODYSTRUCTURE reading and snippets. */
class ImapHelpersTests {
	// --- UidSets ---

	@ParameterizedTest
	@CsvSource(
		delimiter = '|',
		value = [
			"7|7",
			"1,2,3|1:3",
			"1,2,3,7,9,10|1:3,7,9:10",
			"10,3,2,1,9|1:3,9:10",
			"5,5,6,5|5:6",
			"2,4,6|2,4,6",
			"4294967294,4294967295|4294967294:4294967295",
		],
	)
	fun `UidSets formats sorted, de-duplicated ranges`(
		uids: String,
		expected: String,
	) {
		UidSets.format(uids.split(",").map(String::toLong)) shouldBe expected
	}

	// --- MimeStructure ---

	/** A protocol without a server, only for its parser settings. */
	private val protocol = Protocol(ByteArrayInputStream(ByteArray(0)), PrintStream(OutputStream.nullOutputStream()), Properties(), false)

	/** Parses a BODYSTRUCTURE the way Angus reads it from a server's FETCH response. */
	private fun structure(body: String): BODYSTRUCTURE =
		FetchResponse(IMAPResponse("* 1 FETCH (UID 1 BODYSTRUCTURE $body)"), null, protocol)
			.getItem(BODYSTRUCTURE::class.java)
			.shouldNotBeNull()

	private fun text(
		subtype: String,
		charset: String = "UTF-8",
		encoding: String = "7BIT",
		disposition: String = "NIL",
		extraParams: String = "",
	) = """("TEXT" "$subtype" ("CHARSET" "$charset"$extraParams) NIL NIL "$encoding" 120 4 NIL $disposition NIL NIL)"""

	private fun binary(
		type: String,
		subtype: String,
		params: String = "NIL",
		disposition: String = "NIL",
	) = """("$type" "$subtype" $params NIL NIL "BASE64" 3000 NIL $disposition NIL NIL)"""

	private fun multi(
		subtype: String,
		vararg parts: String,
	) = """(${parts.joinToString("")} "$subtype" ("BOUNDARY" "b-$subtype") NIL NIL NIL)"""

	@Test
	fun `a single-part text message is read from section TEXT and has no attachment`() {
		val s = structure(text("PLAIN", charset = "ISO-8859-1", encoding = "QUOTED-PRINTABLE"))

		MimeStructure.hasAttachments(s) shouldBe false
		MimeStructure.snippetPart(s) shouldBe SnippetPart("TEXT", "QUOTED-PRINTABLE", "ISO-8859-1", html = false)
	}

	@Test
	fun `multipart alternative prefers the plain part over the html part`() {
		val s = structure(multi("ALTERNATIVE", text("HTML"), text("PLAIN", encoding = "BASE64")))

		MimeStructure.hasAttachments(s) shouldBe false
		MimeStructure.snippetPart(s) shouldBe SnippetPart("2", "BASE64", "UTF-8", html = false)
	}

	@Test
	fun `an html-only message uses the html part`() {
		val s = structure(multi("ALTERNATIVE", text("HTML")))

		MimeStructure.snippetPart(s) shouldBe SnippetPart("1", "7BIT", "UTF-8", html = true)
	}

	@Test
	fun `a mixed message with an attachment numbers nested sections`() {
		val s =
			structure(
				multi(
					"MIXED",
					multi("ALTERNATIVE", text("PLAIN"), text("HTML")),
					binary("APPLICATION", "PDF", """("NAME" "invoice.pdf")""", """("ATTACHMENT" ("FILENAME" "invoice.pdf"))"""),
				),
			)

		MimeStructure.hasAttachments(s) shouldBe true
		MimeStructure.snippetPart(s)?.section shouldBe "1.1"
	}

	@Test
	fun `a text part marked as attachment is neither the snippet nor ignored as attachment`() {
		val s =
			structure(
				multi(
					"MIXED",
					text("PLAIN", disposition = """("ATTACHMENT" ("FILENAME" "notes.txt"))"""),
					text("HTML"),
				),
			)

		MimeStructure.hasAttachments(s) shouldBe true
		MimeStructure.snippetPart(s) shouldBe SnippetPart("2", "7BIT", "UTF-8", html = true)
	}

	@Test
	fun `a named image inside multipart related is an inline picture, not an attachment`() {
		val s =
			structure(
				multi(
					"RELATED",
					text("HTML"),
					binary("IMAGE", "PNG", """("NAME" "logo.png")"""),
				),
			)

		MimeStructure.hasAttachments(s) shouldBe false
	}

	@Test
	fun `a named part outside multipart related without disposition counts as attachment`() {
		val s = structure(multi("MIXED", text("PLAIN"), binary("IMAGE", "PNG", """("NAME" "photo.png")""")))

		MimeStructure.hasAttachments(s) shouldBe true
	}

	@Test
	fun `a named part explicitly inline outside multipart related is no attachment`() {
		val s =
			structure(
				multi("MIXED", text("PLAIN"), binary("IMAGE", "PNG", """("NAME" "photo.png")""", """("INLINE" ("FILENAME" "photo.png"))""")),
			)

		MimeStructure.hasAttachments(s) shouldBe false
	}

	@Test
	fun `an attachment disposition inside multipart related still counts`() {
		val s =
			structure(
				multi("RELATED", text("HTML"), binary("APPLICATION", "PDF", "NIL", """("ATTACHMENT" ("FILENAME" "a.pdf"))""")),
			)

		MimeStructure.hasAttachments(s) shouldBe true
	}

	@Test
	fun `a forwarded message part counts as attachment`() {
		val envelope = """("Mon, 19 Oct 2026 10:00:00 +0000" "Fwd" NIL NIL NIL NIL NIL NIL NIL "<fwd@example.test>")"""
		val inner = text("PLAIN")
		val forwarded = """("MESSAGE" "RFC822" NIL NIL NIL "7BIT" 500 $envelope $inner 12 NIL NIL NIL NIL)"""
		val s = structure(multi("MIXED", text("PLAIN"), forwarded))

		MimeStructure.hasAttachments(s) shouldBe true
		MimeStructure.snippetPart(s)?.section shouldBe "1"
	}

	@Test
	fun `a message without any text part has no snippet part`() {
		val s = structure(multi("MIXED", binary("IMAGE", "JPEG", "NIL", """("ATTACHMENT" ("FILENAME" "scan.jpg"))""")))

		MimeStructure.snippetPart(s).shouldBeNull()
		MimeStructure.hasAttachments(s) shouldBe true
	}

	@Test
	fun `a text part without charset has a null charset`() {
		val s = structure("""("TEXT" "PLAIN" NIL NIL NIL "8BIT" 10 1 NIL NIL NIL NIL)""")

		MimeStructure.snippetPart(s) shouldBe SnippetPart("TEXT", "8BIT", null, html = false)
	}

	// --- Snippets ---

	private val plain = SnippetPart("1", "7bit", "utf-8", html = false)

	@Test
	fun `a plain snippet drops quoted lines and collapses whitespace`() {
		val body = "Hi Ann,\r\n\r\n  thanks   for the\tplan.\r\n> On Monday Bob wrote:\r\n  > quoted\r\nCheers"

		Snippets.of(body.toByteArray(), plain) shouldBe "Hi Ann, thanks for the plan. Cheers"
	}

	@Test
	fun `a snippet is cut to 200 characters`() {
		val snippet = Snippets.of("x".repeat(500).toByteArray(), plain)

		snippet?.length shouldBe Snippets.MAX_LENGTH
	}

	@Test
	fun `a body that is empty, blank or only quotes has no snippet`() {
		Snippets.of(ByteArray(0), plain).shouldBeNull()
		Snippets.of(" \r\n\t ".toByteArray(), plain).shouldBeNull()
		Snippets.of("> only\r\n> quoted".toByteArray(), plain).shouldBeNull()
	}

	@Test
	fun `base64 is decoded`() {
		val bytes = Base64.getMimeEncoder().encode("Grüezi Ann, see you at the café.".toByteArray())

		Snippets.of(bytes, plain.copy(encoding = "BASE64")) shouldBe "Grüezi Ann, see you at the café."
	}

	@Test
	fun `base64 cut off in the middle keeps the decoded start`() {
		val encoded = Base64.getMimeEncoder().encodeToString("Quarterly numbers for the fictitious team are ready.".toByteArray())
		// 5 complete groups (15 bytes) and 2 characters of the next one.
		val truncated = encoded.substring(0, 22).toByteArray()

		Snippets.of(truncated, plain.copy(encoding = "base64")) shouldBe "Quarterly numbe"
	}

	@Test
	fun `quoted-printable with soft line breaks and escapes is decoded`() {
		val qp = "Gr=C3=BCezi, the meeting is=\r\n moved to Z=C3=BCrich.".toByteArray()

		Snippets.of(qp, plain.copy(encoding = "quoted-printable")) shouldBe "Grüezi, the meeting is moved to Zürich."
	}

	@Test
	fun `quoted-printable cut off inside an escape does not fail`() {
		val qp = "Caf=C3=A9 tomorrow =C3".toByteArray()

		Snippets.of(qp, plain.copy(encoding = "quoted-printable")).shouldNotBeNull() shouldStartWith "Café tomorrow"
	}

	@Test
	fun `a multi-byte character cut off at the end is dropped`() {
		val bytes = "Meet at the café".toByteArray()
		val truncated = bytes.copyOf(bytes.size - 1)

		Snippets.of(truncated, plain) shouldBe "Meet at the caf"
	}

	@Test
	fun `the part's charset is honoured`() {
		val latin1 = "Grüße aus Zürich".toByteArray(Charsets.ISO_8859_1)

		Snippets.of(latin1, plain.copy(charset = "ISO-8859-1")) shouldBe "Grüße aus Zürich"
	}

	@Test
	fun `MIME charset aliases are mapped to Java charsets`() {
		val latin1 = "Grüße".toByteArray(Charsets.ISO_8859_1)

		Snippets.of(latin1, plain.copy(charset = "latin1")) shouldBe "Grüße"
	}

	@Test
	fun `an unknown or missing charset falls back to UTF-8`() {
		Snippets.of("Grüezi".toByteArray(), plain.copy(charset = "x-fictitious-charset")) shouldBe "Grüezi"
		Snippets.of("Grüezi".toByteArray(), plain.copy(charset = null)) shouldBe "Grüezi"
	}

	@Test
	fun `an unknown or missing transfer encoding leaves the bytes as they are`() {
		Snippets.of("Plain words".toByteArray(), plain.copy(encoding = "x-fictitious")) shouldBe "Plain words"
		Snippets.of("Plain words".toByteArray(), plain.copy(encoding = null)) shouldBe "Plain words"
	}

	@Test
	fun `html is reduced to its text`() {
		val html =
			"<html><head><style>p { color: red; }</style><title>T</title></head>" +
				"<body><p>Hello&nbsp;<b>Ann</b>,</p><p>the &amp; plan</p><script>alert(1)</script></body></html>"

		Snippets.of(html.toByteArray(), plain.copy(html = true)) shouldBe "T Hello Ann, the & plan"
	}

	@Test
	fun `html cut off in the middle of a tag still yields text`() {
		val html = "<div>Budget review on Friday</div><a href=\"https://exa"

		Snippets.of(html.toByteArray(), plain.copy(html = true)) shouldBe "Budget review on Friday"
	}
}
