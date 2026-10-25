package ch.sonensei.cockpit.bookmarks.snapshot

import ch.sonensei.cockpit.bookmarks.domain.Bookmark
import ch.sonensei.cockpit.bookmarks.domain.BookmarkService
import ch.sonensei.cockpit.bookmarks.domain.indexBookmark
import ch.sonensei.cockpit.bookmarks.infra.BookmarkFetchProperties
import ch.sonensei.cockpit.bookmarks.infra.BookmarkRepository
import ch.sonensei.cockpit.bookmarks.infra.PageFetchException
import ch.sonensei.cockpit.bookmarks.infra.PageFetcher
import ch.sonensei.cockpit.core.events.domain.ChangeEventPublisher
import ch.sonensei.cockpit.core.events.domain.ChangeOp
import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.core.search.domain.SearchIndexer
import ch.sonensei.cockpit.core.web.ConflictException
import ch.sonensei.cockpit.core.web.NotFoundException
import ch.sonensei.cockpit.files.domain.FilesService
import ch.sonensei.cockpit.files.domain.SpecialFolder
import org.hibernate.Hibernate
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

/** Why no snapshot was saved; [message] is fixed and safe to log and send (no URL, no page content). */
class SnapshotException(
	val code: String,
	message: String,
	cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * Saves a readable copy of a bookmarked page (SPEC.md §8.4): fetches the page ([PageFetcher], SSRF protected, the
 * limits of `cockpit.bookmarks.fetch.*`), extracts its main content ([ReadableExtractor]) and stores it as a sanitized
 * HTML file in the Attachments folder of Files; the bookmark's `snapshotFileId` points to it. A new snapshot replaces
 * the bookmark's previous one, whose file goes to the Trash.
 *
 * The page is fetched outside a transaction, so no database connection waits for a slow site. `If-Match` is checked
 * before the fetch; the result is stored on the bookmark as it is then (e.g. after its metadata fetch finished), unless
 * its URL changed meanwhile (409) or it was deleted (404). Two snapshots saved at the same time: the second one to
 * commit fails with a conflict and its file is not kept.
 *
 * Logs name the bookmark id and the reason only, never the URL or page content.
 */
@Service
class BookmarkSnapshotService(
	private val bookmarks: BookmarkService,
	private val repository: BookmarkRepository,
	private val files: FilesService,
	private val indexer: SearchIndexer,
	private val changes: ChangeEventPublisher,
	private val transactions: TransactionTemplate,
	properties: BookmarkFetchProperties,
	private val fetcher: PageFetcher = PageFetcher(properties),
) {
	private val log = LoggerFactory.getLogger(javaClass)

	/**
	 * Saves a snapshot of bookmark [id]; [checkVersion] sees its current version first (If-Match).
	 *
	 * @throws NotFoundException (404) when the bookmark does not exist or is in the Trash
	 * @throws ConflictException (409) when the URL of the bookmark changed while its page was fetched
	 * @throws SnapshotException when the page could not be fetched or has no readable content
	 */
	fun save(
		id: UUID,
		checkVersion: (Long) -> Unit,
	): Bookmark {
		val bookmark = bookmarks.get(id)
		checkVersion(bookmark.version)
		val url = bookmark.url
		val page =
			try {
				fetcher.fetch(url)
			} catch (e: PageFetchException) {
				log.info("Bookmark {}: snapshot not saved: {}", id, e.error.message)
				throw SnapshotException(e.error.name, e.error.message, e)
			}
		val readable = ReadableExtractor.extract(page, bookmark.displayTitle)
		if (readable.text.isEmpty()) {
			log.info("Bookmark {}: snapshot not saved: no readable content", id)
			throw SnapshotException(NO_CONTENT, "The page has no readable content")
		}
		var previous: UUID? = null
		val saved =
			checkNotNull(
				transactions.execute {
					val current = repository.findActiveById(id) ?: throw NotFoundException("Bookmark not found")
					if (current.url != url) throw ConflictException("The URL of the bookmark changed while the page was fetched")
					val folder = files.specialFolder(SpecialFolder.ATTACHMENTS)
					val file = files.storeFile(folder.id, fileName(readable.title), HTML, readable.html.byteInputStream())
					previous = current.snapshotFileId
					current.snapshotFileId = file.id
					repository.saveAndFlush(current).also {
						Hibernate.initialize(it.tags)
						indexer.indexBookmark(it)
						changes.publish(ItemType.BOOKMARK, it.id, ChangeOp.UPDATED)
					}
				},
			)
		previous?.takeIf { it != saved.snapshotFileId }?.let(::trashPrevious)
		return saved
	}

	/** Moves the previous snapshot to the Trash; it may be gone already (deleted by the user). */
	private fun trashPrevious(fileId: UUID) {
		try {
			files.deleteFile(fileId)
		} catch (_: NotFoundException) {
			// Already deleted or in the Trash.
		} catch (e: RuntimeException) {
			log.warn("Previous snapshot file {} not moved to the Trash ({})", fileId, e.javaClass.name)
		}
	}

	private companion object {
		const val HTML = "text/html"
		const val NO_CONTENT = "NO_CONTENT"
		const val MAX_NAME_TITLE = 100

		/** `<title> (snapshot).html`, with what a file name must not contain ([ch.sonensei.cockpit.files.domain.FileNames]) replaced. */
		fun fileName(title: String): String {
			val safe =
				title
					.map { if (it == '/' || it == '\\' || it.isISOControl()) '_' else it }
					.joinToString("")
					.trim()
					.let {
						if (it.length >
							MAX_NAME_TITLE
						) {
							it.take(if (it[MAX_NAME_TITLE - 1].isHighSurrogate()) MAX_NAME_TITLE - 1 else MAX_NAME_TITLE)
						} else {
							it
						}
					}.trim()
					.ifEmpty { "Page" }
			return "$safe (snapshot).html"
		}
	}
}
