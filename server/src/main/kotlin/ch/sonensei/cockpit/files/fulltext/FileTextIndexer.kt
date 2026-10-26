package ch.sonensei.cockpit.files.fulltext

import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.core.persistence.storableInstant
import ch.sonensei.cockpit.core.search.domain.SearchIndexer
import ch.sonensei.cockpit.files.infra.BlobStore
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock

/**
 * Makes the contents of PDFs and text files searchable (SPEC.md §4.4): extracts the text of files that have none for
 * their current contents yet ([FileTextExtractor], size-limited), stores it in `file_text` and adds it as the body of
 * the file's search document. [ch.sonensei.cockpit.files.domain.FilesService] keeps that body when it re-indexes the
 * file (rename, move, restore). Unsupported, too large and unreadable files get a row too, so they are not tried again.
 *
 * Runs in the background ([FileTextJob]); the file is read outside a transaction, so no database connection waits for
 * PDFBox. The result is stored under a lock on the file row and only when the file still has the same contents; the
 * search document is written only for a file not in the Trash.
 *
 * Logs name the file id and the exception class only, never the file name or its contents.
 */
@Service
class FileTextIndexer(
	private val texts: FileTextRepository,
	private val extractor: FileTextExtractor,
	private val blobs: BlobStore,
	private val search: SearchIndexer,
	private val transactions: TransactionTemplate,
	private val properties: FileTextProperties,
	private val clock: Clock,
) {
	private val log = LoggerFactory.getLogger(javaClass)

	/** Extracts the text of up to `batch-size` pending files; returns how many were looked at. */
	fun indexPending(): Int {
		val pending = checkNotNull(transactions.execute { texts.findPending(properties.batchSize) })
		pending.forEach(::index)
		return pending.size
	}

	private fun index(file: PendingFile) {
		val text =
			try {
				extractor.extract(blobs.path(file.storageKey), file.mime, file.size)
			} catch (e: Exception) {
				// PDFBox throws IOExceptions and various runtime exceptions for broken or hostile PDFs.
				log.info("File {}: text not extracted ({})", file.id, e.javaClass.name)
				store(file, FileTextStatus.FAILED, null)
				return
			}
		if (text == null) {
			store(file, FileTextStatus.SKIPPED, null)
		} else {
			store(file, FileTextStatus.EXTRACTED, text.replace("\u0000", ""))
		}
	}

	private fun store(
		file: PendingFile,
		status: FileTextStatus,
		text: String?,
	) {
		transactions.executeWithoutResult {
			val current = texts.lockFile(file.id) ?: return@executeWithoutResult
			// The contents changed meanwhile: the next run extracts them again.
			if (current.sha256 != file.sha256) return@executeWithoutResult
			texts.save(file.id, file.sha256, status, text, clock.storableInstant())
			if (current.active && text != null) search.upsert(ItemType.FILE, file.id, current.name, text, current.updatedAt)
		}
	}
}
