package ch.sonensei.cockpit.files.fulltext

import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.core.persistence.MutableClock
import ch.sonensei.cockpit.core.search.domain.SearchIndexer
import ch.sonensei.cockpit.core.search.domain.SearchService
import ch.sonensei.cockpit.files.domain.FileItem
import ch.sonensei.cockpit.files.domain.FilesService
import ch.sonensei.cockpit.files.infra.BlobStore
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import java.nio.file.Files
import java.time.Clock
import java.time.Duration
import java.util.UUID

/**
 * Edge cases of [FileTextIndexer] against Postgres (task pdf-fulltext): NUL characters, the character limit end to end,
 * missing blobs and unexpected extractor exceptions and errors, batches, the rebuild after a backup restore, and files
 * that change, go to the Trash or are purged while their text is extracted (an indexer built with a mocked extractor
 * simulates the race deterministically).
 */
@SpringBootTest
class FileTextIndexerEdgeCaseTests(
	@Autowired private val files: FilesService,
	@Autowired private val indexer: FileTextIndexer,
	@Autowired private val texts: FileTextRepository,
	@Autowired private val extractor: FileTextExtractor,
	@Autowired private val blobs: BlobStore,
	@Autowired private val searchIndexer: SearchIndexer,
	@Autowired private val search: SearchService,
	@Autowired private val transactions: TransactionTemplate,
	@Autowired private val properties: FileTextProperties,
	@Autowired private val clock: Clock,
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

	private fun indexerWith(
		extractor: FileTextExtractor = this.extractor,
		properties: FileTextProperties = this.properties,
		clock: Clock = this.clock,
	) = FileTextIndexer(texts, extractor, blobs, searchIndexer, transactions, properties, clock)

	/** An extractor that runs [during] (the racing change) and then returns [text]. */
	private fun racingExtractor(
		text: String,
		during: () -> Unit,
	) = mockk<FileTextExtractor> {
		every { extract(any(), any(), any()) } answers {
			during()
			text
		}
	}

	private fun found(query: String): List<UUID> =
		search.search(query, setOf(ItemType.FILE), 20).flatMap { group -> group.results.map { it.hit.item.id } }

	private fun row(file: FileItem): Map<String, Any?>? =
		jdbc.queryForList("select sha256, status, text from file_text where file_id = ?", file.id).firstOrNull()

	private fun storeText(name: String) = files.storeFile(null, name, "text/plain", "plain words".byteInputStream())

	@Test
	fun `NUL characters are removed from the stored and indexed text`() {
		val file = files.storeFile(null, "data.txt", "text/plain", "Report\u0000 on\u0000 fennimore\u0000".byteInputStream())

		indexAll()

		row(file)!!["status"] shouldBe "EXTRACTED"
		row(file)!!["text"] shouldBe "Report on fennimore"
		found("fennimore") shouldContainExactly listOf(file.id)
	}

	@Test
	fun `only the first max-chars characters of a long text file are searchable`() {
		val filler = "x".repeat(properties.maxChars)
		val file =
			files.storeFile(null, "long.txt", "text/plain", "Wombleford $filler Quistrandel".byteInputStream())

		indexAll()

		(row(file)!!["text"] as String).length shouldBe properties.maxChars
		found("wombleford") shouldContainExactly listOf(file.id)
		found("quistrandel").shouldBeEmpty()
	}

	@Test
	fun `a file whose blob is missing gets no row, does not block other files, and is tried again later`() {
		val time = MutableClock(clock.instant())
		val retrying = indexerWith(clock = time)
		val file = files.storeFile(null, "lost.txt", "text/plain", "Vanished trellis".byteInputStream())
		val blob = blobs.path(file.storageKey)
		val saved = Files.readAllBytes(blob)
		Files.delete(blob)
		val older = files.storeFile(null, "kept.txt", "text/plain", "Present gooseberry".byteInputStream())
		jdbc.update("update file set created_at = created_at - interval '1 hour' where id = ?", older.id)

		retrying.indexPending() shouldBe 2

		row(file).shouldBeNull()
		row(older)!!["status"] shouldBe "EXTRACTED"
		retrying.indexPending() shouldBe 0
		found("lost") shouldContainExactly listOf(file.id)

		Files.write(blob, saved)
		time.advance(FileTextIndexer.UNREADABLE_RETRY_DELAY.minusSeconds(1))
		retrying.indexPending() shouldBe 0
		time.advance(Duration.ofSeconds(1))
		retrying.indexPending() shouldBe 1

		row(file)!!["status"] shouldBe "EXTRACTED"
		found("trellis") shouldContainExactly listOf(file.id)
	}

	@Test
	fun `a StackOverflowError of the extractor marks the file failed instead of stopping the batch`() {
		val failing = files.storeFile(null, "deep.pdf", "application/pdf", "fictitious".byteInputStream())
		val other = files.storeFile(null, "flat.pdf", "application/pdf", "fictitious".byteInputStream())
		val overflowing =
			mockk<FileTextExtractor> {
				every { extract(blobs.path(failing.storageKey), any(), any()) } throws StackOverflowError()
				every { extract(blobs.path(other.storageKey), any(), any()) } returns "Flat lindqvist"
			}

		indexerWith(extractor = overflowing).indexPending() shouldBe 2

		row(failing)!!["status"] shouldBe "FAILED"
		row(other)!!["status"] shouldBe "EXTRACTED"
		indexer.indexPending() shouldBe 0
	}

	@Test
	fun `other errors of the virtual machine are not swallowed and the file stays pending`() {
		val file = files.storeFile(null, "huge.pdf", "application/pdf", "fictitious".byteInputStream())
		val exhausted =
			mockk<FileTextExtractor> {
				every { extract(any(), any(), any()) } throws OutOfMemoryError("fictitious")
			}

		shouldThrow<OutOfMemoryError> { indexerWith(extractor = exhausted).indexPending() }

		row(file).shouldBeNull()
	}

	@Test
	fun `text extracted from a blob that was replaced meanwhile is dropped and extracted again on the next run`() {
		val file = files.storeFile(null, "moved.txt", "text/plain", "Moved words".byteInputStream())
		val copy = blobs.write("Moved words".byteInputStream(), Long.MAX_VALUE)
		val racing =
			racingExtractor("Stale abernethy") {
				jdbc.update("update file set storage_key = ? where id = ?", copy.key, file.id)
			}

		indexerWith(extractor = racing).indexPending() shouldBe 1

		row(file).shouldBeNull()
		indexer.indexPending() shouldBe 1
		row(file)!!["status"] shouldBe "EXTRACTED"
	}

	@Test
	fun `after the extracted texts are wiped (backup restore) they are rebuilt from the blobs and found again`() {
		val file = files.storeFile(null, "restored.txt", "text/plain", "Restored kittiwake".byteInputStream())
		indexAll()
		found("kittiwake") shouldContainExactly listOf(file.id)

		// What a restore leaves: file_text truncated, the search document rebuilt from the name only.
		jdbc.execute("truncate file_text")
		searchIndexer.upsert(ItemType.FILE, file.id, file.name, null)
		found("kittiwake").shouldBeEmpty()

		indexer.indexPending() shouldBe 1

		row(file)!!["status"] shouldBe "EXTRACTED"
		found("kittiwake") shouldContainExactly listOf(file.id)
		found("restored") shouldContainExactly listOf(file.id)
	}

	@Test
	fun `an unexpected runtime exception of the extractor marks the file failed instead of stopping the batch`() {
		val failing = files.storeFile(null, "odd.pdf", "application/pdf", "fictitious".byteInputStream())
		val throwing =
			mockk<FileTextExtractor> {
				every { extract(any(), any(), any()) } throws IllegalStateException("hostile PDF")
			}

		indexerWith(extractor = throwing).indexPending() shouldBe 1

		row(failing)!!["status"] shouldBe "FAILED"
		indexer.indexPending() shouldBe 0
	}

	@Test
	fun `pending files are taken batch-size at a time`() {
		repeat(3) { storeText("batch-$it.txt") }
		val batched = indexerWith(properties = FileTextProperties(properties.maxFileSize, properties.maxChars, properties.maxPages, 2))

		batched.indexPending() shouldBe 2
		batched.indexPending() shouldBe 1
		batched.indexPending() shouldBe 0
	}

	@Test
	fun `a file whose contents changed is extracted again`() {
		val file = files.storeFile(null, "draft.txt", "text/plain", "First draft about pemberly".byteInputStream())
		indexAll()

		jdbc.update("update file set sha256 = ? where id = ?", "0".repeat(64), file.id)

		indexer.indexPending() shouldBe 1
		row(file)!!["sha256"] shouldBe "0".repeat(64)
		indexer.indexPending() shouldBe 0
	}

	@Test
	fun `text extracted from contents that changed meanwhile is dropped and extracted again on the next run`() {
		val file = files.storeFile(null, "race.txt", "text/plain", "Current words".byteInputStream())
		val racing =
			racingExtractor("Stale halvorsen") {
				jdbc.update("update file set sha256 = ? where id = ?", "1".repeat(64), file.id)
			}

		indexerWith(extractor = racing).indexPending() shouldBe 1

		row(file).shouldBeNull()
		found("halvorsen").shouldBeEmpty()
		indexer.indexPending() shouldBe 1
		row(file)!!["sha256"] shouldBe "1".repeat(64)
	}

	@Test
	fun `a file purged while its text is extracted gets no row and no search document`() {
		val file = files.storeFile(null, "gone.txt", "text/plain", "Purged words".byteInputStream())
		val racing =
			racingExtractor("Purged ravenscar") {
				files.deleteFile(file.id)
				files.purgeFile(file.id) shouldBe true
			}

		indexerWith(extractor = racing).indexPending() shouldBe 1

		jdbc.queryForObject("select count(*) from file where id = ?", Int::class.java, file.id) shouldBe 0
		jdbc.queryForObject("select count(*) from file_text", Int::class.java) shouldBe 0
		found("ravenscar").shouldBeEmpty()
	}

	@Test
	fun `a file moved to the Trash while its text is extracted keeps the text but stays out of search until restored`() {
		val file = files.storeFile(null, "binned.txt", "text/plain", "Binned thistlewood".byteInputStream())
		val racing = racingExtractor("Binned thistlewood") { files.deleteFile(file.id) }

		indexerWith(extractor = racing).indexPending() shouldBe 1

		row(file)!!["status"] shouldBe "EXTRACTED"
		found("thistlewood").shouldBeEmpty()
		found("binned").shouldBeEmpty()

		files.restoreFile(file.id) shouldBe true

		found("thistlewood") shouldContainExactly listOf(file.id)
		indexer.indexPending() shouldBe 0
	}

	@Test
	fun `a file renamed while its text is extracted is indexed under its new name`() {
		val file = files.storeFile(null, "before.txt", "text/plain", "Renamed marrowby".byteInputStream())
		val racing = racingExtractor("Renamed marrowby") { files.renameFile(file.id, "afterwards.txt") }

		indexerWith(extractor = racing).indexPending() shouldBe 1

		found("marrowby") shouldContainExactly listOf(file.id)
		found("afterwards") shouldContainExactly listOf(file.id)
		found("before").shouldBeEmpty()
	}

	@Test
	fun `a skipped file stores no text, and its name stays searchable`() {
		val image = files.storeFile(null, "pic.png", "image/png", "pixels".byteInputStream())

		indexAll()

		row(image)!!["status"] shouldBe "SKIPPED"
		row(image)!!["text"].shouldBeNull()
		found("pic") shouldContainExactly listOf(image.id)
	}
}
