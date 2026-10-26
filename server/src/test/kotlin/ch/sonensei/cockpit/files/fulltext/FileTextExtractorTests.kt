package ch.sonensei.cockpit.files.fulltext

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.encryption.AccessPermission
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.springframework.util.unit.DataSize
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * [FileTextExtractor] without Spring (task pdf-fulltext): which mime types are read, the file size, character and page
 * limits for text files and PDFs, UTF-8 handling at the cut, and unreadable files.
 */
class FileTextExtractorTests {
	@TempDir
	lateinit var dir: Path

	private fun extractor(
		maxFileSize: Long = 20L * 1024 * 1024,
		maxChars: Int = 100_000,
		maxPages: Int = 500,
	) = FileTextExtractor(FileTextProperties(DataSize.ofBytes(maxFileSize), maxChars, maxPages, 20))

	private fun file(bytes: ByteArray): Path = Files.createTempFile(dir, "blob", ".bin").also { Files.write(it, bytes) }

	private fun FileTextExtractor.extract(
		bytes: ByteArray,
		mime: String,
	): String? = file(bytes).let { extract(it, mime, Files.size(it)) }

	@Test
	fun `PDFs and text types are supported in any case and with parameters, other types are not`() {
		val extractor = extractor()

		extractor.supports("application/pdf") shouldBe true
		extractor.supports("Application/PDF; version=1.7") shouldBe true
		extractor.supports("text/plain") shouldBe true
		extractor.supports("TEXT/Markdown; charset=UTF-8") shouldBe true
		extractor.supports(" text/csv ") shouldBe true
		extractor.supports("application/json") shouldBe true
		extractor.supports("image/png") shouldBe false
		extractor.supports("application/zip") shouldBe false
		extractor.supports("application/octet-stream") shouldBe false
		extractor.supports("") shouldBe false
	}

	@Test
	fun `an unsupported type is not read`() {
		extractor().extract("plain words".toByteArray(), "image/png").shouldBeNull()
	}

	@Test
	fun `a file exactly at the size limit is read, one byte more is not`() {
		val extractor = extractor(maxFileSize = 11)
		val path = file("plain words".toByteArray())

		extractor.extract(path, "text/plain", 11) shouldBe "plain words"
		extractor.extract(path, "text/plain", 12).shouldBeNull()
	}

	@Test
	fun `the size limit also applies to PDFs, which are then not opened`() {
		val path = file("not even a pdf".toByteArray())

		extractor(maxFileSize = 5).extract(path, "application/pdf", Files.size(path)).shouldBeNull()
	}

	@Test
	fun `a text file is cut to max-chars characters, and one of exactly max-chars is kept whole`() {
		val extractor = extractor(maxChars = 5)

		extractor.extract("abcdefghij".toByteArray(), "text/plain") shouldBe "abcde"
		extractor.extract("abcde".toByteArray(), "text/plain") shouldBe "abcde"
		extractor.extract("abcd".toByteArray(), "text/plain") shouldBe "abcd"
		extractor.extract(ByteArray(0), "text/plain") shouldBe ""
	}

	@Test
	fun `max-chars counts characters, not bytes, for multi-byte UTF-8 text`() {
		// Each umlaut has 2 bytes: 8 bytes fit below the 4 * 3 byte read limit, the cut is by characters.
		extractor(maxChars = 3).extract("äöüß".toByteArray(), "text/plain; charset=UTF-8") shouldBe "äöü"
	}

	@Test
	fun `a UTF-8 sequence cut by the byte limit is dropped, not decoded as a replacement character`() {
		// "€" has 3 bytes: max-chars 1 reads at most 4 bytes, which cut the second "€" after its first byte.
		val text = extractor(maxChars = 1).extract("€€".toByteArray(), "text/plain")

		text shouldBe "€"
		text.orEmpty() shouldNotContain "�"
	}

	@Test
	fun `invalid UTF-8 bytes become replacement characters instead of failing`() {
		val bytes = byteArrayOf('o'.code.toByte(), 0xFF.toByte(), 'k'.code.toByte())

		extractor().extract(bytes, "text/plain") shouldBe "o�k"
	}

	@Test
	fun `the text of a PDF is read, whatever the case of its mime type`() {
		extractor().extract(pdf("Fictitious receipt from Brambleton"), "Application/PDF").orEmpty() shouldContain
			"Fictitious receipt from Brambleton"
	}

	@Test
	fun `the text of a PDF is cut to max-chars characters`() {
		val text = extractor(maxChars = 10).extract(pdf("Alpha Bravo Charlie Delta"), "application/pdf")

		text shouldBe "Alpha Brav"
	}

	@Test
	fun `a multi-page PDF longer than max-chars keeps only the first max-chars characters`() {
		val text = extractor(maxChars = 8).extract(pdf("Firstpage words", "Secondpage words"), "application/pdf")

		text shouldBe "Firstpag"
	}

	@Test
	fun `only the first max-pages pages of a PDF are read`() {
		val text = extractor(maxPages = 2).extract(pdf("Pageone", "Pagetwo", "Pagethree"), "application/pdf").orEmpty()

		text shouldContain "Pageone"
		text shouldContain "Pagetwo"
		text shouldNotContain "Pagethree"
	}

	@Test
	fun `a PDF with fewer pages than max-pages is read completely`() {
		val text = extractor(maxPages = 1).extract(pdf("Onlypage"), "application/pdf").orEmpty()

		text shouldContain "Onlypage"
	}

	@Test
	fun `a broken PDF, an encrypted PDF and a missing file throw an IOException`() {
		val extractor = extractor()

		assertThrows<IOException> { extractor.extract("%PDF-1.7 broken".toByteArray(), "application/pdf") }
		assertThrows<IOException> { extractor.extract(encryptedPdf("Hidden text"), "application/pdf") }
		assertThrows<IOException> { extractor.extract(dir.resolve("missing.txt"), "text/plain", 10) }
	}

	@Test
	fun `limits below one are rejected`() {
		val ok = DataSize.ofBytes(1)

		assertThrows<IllegalArgumentException> { FileTextProperties(DataSize.ofBytes(0), 1, 1, 1) }
		assertThrows<IllegalArgumentException> { FileTextProperties(ok, 0, 1, 1) }
		assertThrows<IllegalArgumentException> { FileTextProperties(ok, 1, 0, 1) }
		assertThrows<IllegalArgumentException> { FileTextProperties(ok, 1, 1, 0) }
		FileTextProperties(ok, 1, 1, 1).maxChars shouldBe 1
	}

	private fun document(vararg pages: String): PDDocument =
		PDDocument().apply {
			pages.forEach { text ->
				val page = PDPage()
				addPage(page)
				PDPageContentStream(this, page).use {
					it.beginText()
					it.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 12f)
					it.newLineAtOffset(72f, 700f)
					it.showText(text)
					it.endText()
				}
			}
		}

	private fun pdf(vararg pages: String): ByteArray = document(*pages).use { ByteArrayOutputStream().also(it::save).toByteArray() }

	private fun encryptedPdf(text: String): ByteArray =
		document(text).use { document ->
			document.protect(StandardProtectionPolicy("owner-secret", "user-secret", AccessPermission()).apply { encryptionKeyLength = 128 })
			ByteArrayOutputStream().also(document::save).toByteArray()
		}
}
