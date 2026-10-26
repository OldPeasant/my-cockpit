package ch.sonensei.cockpit.files.fulltext

import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.core.persistence.storableInstant
import ch.sonensei.cockpit.core.search.domain.SearchIndexer
import ch.sonensei.cockpit.files.infra.BlobStore
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.io.FileNotFoundException
import java.io.InterruptedIOException
import java.nio.channels.ClosedByInterruptException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Makes the contents of PDFs and text files searchable (SPEC.md §4.4): extracts the text of files that have none for
 * their current contents yet ([FileTextExtractor], size-limited), stores it in `file_text` and adds it as the body of
 * the file's search document. [ch.sonensei.cockpit.files.domain.FilesService] keeps that body when it re-indexes the
 * file (rename, move, restore). Unsupported, too large and unparsable files (including a PDF that overflows the stack)
 * get a row too, so they are not tried again. A file whose blob cannot be read (missing, not accessible) gets no row: it
 * is left out for [UNREADABLE_RETRY_DELAY] and then tried again, so a transient disk problem or a data directory
 * swapped during a restore does not make its contents unsearchable for good. An extraction interrupted by the shutdown
 * ([FileTextJob.close]) stores nothing either, and the batch stops there. A file whose extraction throws an [Error] such
 * as an [OutOfMemoryError] stays pending (the error is logged with the file id and rethrown); after
 * [MAX_ERROR_ATTEMPTS] such failures it is left out until the next start, so it does not block the older files.
 *
 * Runs in the background ([FileTextJob]); the file is read outside a transaction, so no database connection waits for
 * PDFBox. The result is stored under a lock on the file row and only when the file still has the same contents (digest
 * and blob); the search document is written only for a file not in the Trash.
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

	/** Files whose blob could not be read, with the time from which they are tried again. */
	private val unreadable = ConcurrentHashMap<UUID, Instant>()

	/** Files whose extraction threw an [Error] (other than a [StackOverflowError]), with the number of times. */
	private val crashed = ConcurrentHashMap<UUID, Int>()

	/**
	 * Extracts the text of up to `batch-size` pending files; returns how many were looked at. Files whose blob could not
	 * be read recently are not counted, so a caller looping until `0` stops. Stops after the current file when the
	 * thread is interrupted (shutdown).
	 */
	fun indexPending(): Int {
		val now = clock.instant()
		unreadable.values.removeIf { !it.isAfter(now) }
		val waiting = unreadable.keys + crashed.filterValues { it >= MAX_ERROR_ATTEMPTS }.keys
		val pending =
			checkNotNull(transactions.execute { texts.findPending(properties.batchSize + waiting.size) })
				.filterNot { it.id in waiting }
				.take(properties.batchSize)
		var looked = 0
		for (file in pending) {
			if (Thread.currentThread().isInterrupted) break
			looked++
			if (!index(file)) break
		}
		return looked
	}

	/** `false` when the extraction was interrupted (shutdown), so the batch stops. */
	private fun index(file: PendingFile): Boolean {
		val path = blobs.path(file.storageKey)
		val text =
			try {
				extractor.extract(path, file.mime, file.size)
			} catch (e: Exception) {
				return failed(file, path, e)
			} catch (e: StackOverflowError) {
				// Deeply nested or recursive objects of a crafted PDF; the stack is unwound here, so this is safe to handle.
				return failed(file, path, e)
			} catch (e: Error) {
				crashed(file, e)
				throw e
			}
		crashed.remove(file.id)
		if (text == null) {
			store(file, FileTextStatus.SKIPPED, null)
		} else {
			store(file, FileTextStatus.EXTRACTED, text.replace("\u0000", ""))
		}
		return true
	}

	/**
	 * PDFBox throws IOExceptions and various runtime exceptions for broken or hostile PDFs. `false` when the extraction
	 * was interrupted.
	 */
	private fun failed(
		file: PendingFile,
		path: Path,
		e: Throwable,
	): Boolean {
		crashed.remove(file.id)
		if (Thread.currentThread().isInterrupted || interrupted(e)) {
			// The shutdown interrupted the read (an interruptible channel throws ClosedByInterruptException); the file
			// itself is fine and is extracted on the next start.
			log.info("File {}: text extraction interrupted ({})", file.id, e.javaClass.name)
			return false
		}
		if (e is FileSystemException || e is FileNotFoundException || !Files.isReadable(path)) {
			log.info("File {}: blob not readable, text extraction retried later ({})", file.id, e.javaClass.name)
			unreadable[file.id] = clock.instant().plus(UNREADABLE_RETRY_DELAY)
			return true
		}
		log.info("File {}: text not extracted ({})", file.id, e.javaClass.name)
		store(file, FileTextStatus.FAILED, null)
		return true
	}

	private fun interrupted(e: Throwable) =
		generateSequence(e) { it.cause.takeIf { cause -> cause !== it } }
			.take(MAX_CAUSES)
			.any { it is ClosedByInterruptException || it is InterruptedIOException }

	/** Logs [e] (out of memory, a missing class, ...) with the file id; after repeated ones the file is left out. */
	private fun crashed(
		file: PendingFile,
		e: Error,
	) {
		val attempts = crashed.merge(file.id, 1, Int::plus) ?: 1
		val skipped = attempts >= MAX_ERROR_ATTEMPTS
		log.error(
			"File {}: text extraction failed with {} (attempt {}{})",
			file.id,
			e.javaClass.name,
			attempts,
			if (skipped) ", left out until the next start" else "",
		)
	}

	private fun store(
		file: PendingFile,
		status: FileTextStatus,
		text: String?,
	) {
		transactions.executeWithoutResult {
			val current = texts.lockFile(file.id) ?: return@executeWithoutResult
			// The contents or the blob changed meanwhile: the next run extracts them again.
			if (current.sha256 != file.sha256 || current.storageKey != file.storageKey) return@executeWithoutResult
			texts.save(file.id, file.sha256, status, text, clock.storableInstant())
			if (current.active && text != null) search.upsert(ItemType.FILE, file.id, current.name, text, current.updatedAt)
		}
	}

	companion object {
		/** How long a file whose blob could not be read is left out before it is tried again. */
		val UNREADABLE_RETRY_DELAY: Duration = Duration.ofMinutes(15)

		/** After this many [Error]s (other than a [StackOverflowError]) a file is left out until the next start. */
		const val MAX_ERROR_ATTEMPTS = 3

		/** How deep the cause chain of an extraction failure is searched for an interruption. */
		private const val MAX_CAUSES = 10
	}
}
