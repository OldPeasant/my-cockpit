package ch.sonensei.cockpit.files.fulltext

import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.core.search.domain.SearchService
import ch.sonensei.cockpit.files.domain.FileItem
import ch.sonensei.cockpit.files.domain.FilesService
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import java.io.ByteArrayOutputStream
import java.util.UUID

@SpringBootTest
class FileTextIndexerTests(
	@Autowired private val files: FilesService,
	@Autowired private val indexer: FileTextIndexer,
	@Autowired private val search: SearchService,
	@Autowired private val jdbc: JdbcTemplate,
) : PostgresTestBase() {
	@BeforeEach
	@AfterEach
	fun reset() {
		jdbc.execute("delete from search_document where item_type in ('FILE', 'FOLDER')")
		jdbc.execute("delete from file")
	}

	private fun indexAll() {
		while (indexer.indexPending() > 0) {
			// Until no file is pending.
		}
	}

	private fun found(query: String): List<UUID> =
		search.search(query, setOf(ItemType.FILE), 20).flatMap { group -> group.results.map { it.hit.item.id } }

	private fun status(file: FileItem): String? =
		jdbc.queryForList("select status from file_text where file_id = ?", String::class.java, file.id).firstOrNull()

	@Test
	fun `the text of a PDF is extracted in the background job and found by search`() {
		val file = files.storeFile(null, "scan.pdf", "application/pdf", pdf("Fictitious invoice from Zephyrwood Ltd").inputStream())
		found("zephyrwood").shouldBeEmpty()

		indexAll()

		status(file) shouldBe "EXTRACTED"
		found("zephyrwood") shouldContainExactly listOf(file.id)
		found("scan") shouldContainExactly listOf(file.id)
	}

	@Test
	fun `the text of a text file is found, and kept when the file is renamed`() {
		val file = files.storeFile(null, "notes.txt", "text/plain; charset=UTF-8", "Meeting about quillberry jam".byteInputStream())
		indexAll()

		files.renameFile(file.id, "minutes.txt")

		found("quillberry") shouldContainExactly listOf(file.id)
		found("minutes") shouldContainExactly listOf(file.id)
	}

	@Test
	fun `files above the size limit, of other types and broken PDFs are not searched by content and not tried again`() {
		val large = files.storeFile(null, "big.txt", "text/plain", ("glimmerstone ".repeat(2_000_000)).byteInputStream())
		val image = files.storeFile(null, "photo.png", "image/png", "glimmerstone".byteInputStream())
		val broken = files.storeFile(null, "broken.pdf", "application/pdf", "not a pdf glimmerstone".byteInputStream())

		indexAll()

		status(large) shouldBe "SKIPPED"
		status(image) shouldBe "SKIPPED"
		status(broken) shouldBe "FAILED"
		found("glimmerstone").shouldBeEmpty()
		indexer.indexPending() shouldBe 0
	}

	@Test
	fun `a file in the Trash is not indexed, and its text comes back with a restore`() {
		val file = files.storeFile(null, "letter.txt", "text/plain", "Dear Ottoline".byteInputStream())
		files.deleteFile(file.id)
		indexAll()
		found("ottoline").shouldBeEmpty()

		files.restoreFile(file.id)
		indexAll()

		found("ottoline") shouldContainExactly listOf(file.id)
	}

	private fun pdf(text: String): ByteArray =
		PDDocument().use { document ->
			val page = PDPage()
			document.addPage(page)
			PDPageContentStream(document, page).use {
				it.beginText()
				it.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 12f)
				it.newLineAtOffset(72f, 700f)
				it.showText(text)
				it.endText()
			}
			ByteArrayOutputStream().also(document::save).toByteArray()
		}
}
