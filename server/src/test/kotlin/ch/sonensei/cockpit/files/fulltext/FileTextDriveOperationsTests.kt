package ch.sonensei.cockpit.files.fulltext

import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.core.search.domain.SearchIndexer
import ch.sonensei.cockpit.core.search.domain.SearchService
import ch.sonensei.cockpit.files.domain.FileItem
import ch.sonensei.cockpit.files.domain.FilesService
import ch.sonensei.cockpit.files.domain.MoveTarget
import ch.sonensei.cockpit.files.infra.BlobStore
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
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
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.Files
import java.time.Clock
import java.util.UUID

/**
 * The extracted text across the drive operations of [FilesService] that the task changed (task pdf-fulltext): moving a
 * file, deleting and restoring a whole folder (the subtree is trashed and restored with the text kept), purging, and
 * blobs that cannot be read whatever exception the extractor throws for them.
 */
@SpringBootTest
class FileTextDriveOperationsTests(
	@Autowired private val files: FilesService,
	@Autowired private val indexer: FileTextIndexer,
	@Autowired private val texts: FileTextRepository,
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
		// Leaves first: parent_id references folder.
		while ((jdbc.queryForObject("select count(*) from folder", Long::class.java) ?: 0) > 0) {
			jdbc.execute("delete from folder f where not exists (select 1 from folder c where c.parent_id = f.id)")
		}
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

	private fun text(
		folderId: UUID?,
		name: String,
		content: String,
	) = files.storeFile(folderId, name, "text/plain", content.byteInputStream())

	@Test
	fun `a file moved to another folder keeps its extracted text searchable`() {
		val target = files.createFolder("Archive", null)
		val file = text(null, "memo.txt", "Memo about corvinbrook")
		indexAll()

		files.updateFile(file.id, null, MoveTarget(target.id))

		found("corvinbrook") shouldContainExactly listOf(file.id)
	}

	@Test
	fun `deleting a folder hides the text of its files, restoring it brings the text back without a new extraction`() {
		val top = files.createFolder("Projects", null)
		val nested = files.createFolder("Drafts", top.id)
		val direct = text(top.id, "plan.txt", "Plan for lindenhollow")
		val deep = text(nested.id, "draft.txt", "Draft for lindenhollow")
		indexAll()
		found("lindenhollow") shouldContainExactlyInAnyOrder listOf(direct.id, deep.id)

		files.deleteFolder(top.id)

		found("lindenhollow").shouldBeEmpty()
		found("plan").shouldBeEmpty()
		indexer.indexPending() shouldBe 0

		files.restoreFolder(top.id) shouldBe true

		// The search documents are rebuilt from file_text by FilesService, before any further run of the job.
		found("lindenhollow") shouldContainExactlyInAnyOrder listOf(direct.id, deep.id)
		indexer.indexPending() shouldBe 0
	}

	@Test
	fun `a folder deleted before the job ran gets its files extracted only after it is restored`() {
		val folder = files.createFolder("Inbox", null)
		val file = text(folder.id, "note.txt", "Note on brackenmere")
		files.deleteFolder(folder.id)

		indexer.indexPending() shouldBe 0
		status(file).shouldBeNull()

		files.restoreFolder(folder.id) shouldBe true
		found("brackenmere").shouldBeEmpty()
		indexAll()

		status(file) shouldBe "EXTRACTED"
		found("brackenmere") shouldContainExactly listOf(file.id)
	}

	@Test
	fun `purging a file removes its extracted text`() {
		val file = text(null, "old.txt", "Old ledger of fairhaven")
		indexAll()
		status(file) shouldBe "EXTRACTED"

		files.deleteFile(file.id)
		files.purgeFile(file.id) shouldBe true

		jdbc.queryForObject("select count(*) from file_text where file_id = ?", Int::class.java, file.id) shouldBe 0
		found("fairhaven").shouldBeEmpty()
	}

	@Test
	fun `a PDF whose blob is missing gets no row and stays pending`() {
		val file = files.storeFile(null, "gone.pdf", "application/pdf", "%PDF-1.7 fictitious".byteInputStream())
		Files.delete(blobs.path(file.storageKey))

		indexer.indexPending() shouldBe 1

		status(file).shouldBeNull()
		// Left out until the retry delay passed (the shared indexer remembers it; the reset deletes the file).
		indexer.indexPending() shouldBe 0
	}

	@Test
	fun `any extractor exception for a blob that is no longer readable is treated as a read error, not as a broken file`() {
		val file = text(null, "vanishing.txt", "Vanishing words")
		val vanishing =
			mockk<FileTextExtractor> {
				every { extract(any(), any(), any()) } answers {
					Files.delete(blobs.path(file.storageKey))
					throw IOException("fictitious read failure")
				}
			}

		FileTextIndexer(texts, vanishing, blobs, searchIndexer, transactions, properties, clock).indexPending() shouldBe 1

		status(file).shouldBeNull()
	}

	@Test
	fun `a FileNotFoundException of the extractor is treated as a read error even when the blob looks readable`() {
		val file = text(null, "flaky.txt", "Flaky words")
		val flaky =
			mockk<FileTextExtractor> {
				every { extract(any(), any(), any()) } throws FileNotFoundException("fictitious")
			}

		FileTextIndexer(texts, flaky, blobs, searchIndexer, transactions, properties, clock).indexPending() shouldBe 1

		status(file).shouldBeNull()
		indexer.indexPending() shouldBe 1
		status(file) shouldBe "EXTRACTED"
	}
}
