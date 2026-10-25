package ch.sonensei.cockpit.bookmarks.backup

import ch.sonensei.cockpit.backup.BackupFixture
import ch.sonensei.cockpit.bookmarks.domain.Bookmark
import ch.sonensei.cockpit.bookmarks.domain.indexBookmark
import ch.sonensei.cockpit.bookmarks.infra.BookmarkRepository
import ch.sonensei.cockpit.core.search.domain.SearchIndexer
import ch.sonensei.cockpit.files.domain.FilesService
import java.time.Clock

/**
 * Bookmarks with every field set (tags with unicode, a comma and mixed case, a favicon, a snapshot file, a multi-line
 * description), a minimal one, one in the read-later list and read, one with a failed and one with a pending metadata
 * fetch, and one in the Trash.
 *
 * Saved through the repository rather than [ch.sonensei.cockpit.bookmarks.domain.BookmarkService]: the service would
 * start a metadata fetch, whose result could change the rows while the round trip compares them.
 */
class BookmarksBackupFixture(
	private val bookmarks: BookmarkRepository,
	private val indexer: SearchIndexer,
	private val files: FilesService,
	private val clock: Clock,
) : BackupFixture {
	override val contributor = BookmarksBackupContributor.ID

	override fun seed() {
		val snapshots = files.createFolder("Bookmark snapshots", null)
		val snapshot = files.storeFile(snapshots.id, "Grüezi «Welt».html", "text/html", "<p>Grüezi</p>".byteInputStream())
		save("https://example.test/articles/grüezi?lang=de&x=\"1\"") {
			title = "Grüezi «Welt» & <friends>"
			description = "A long read\nover two lines ☕"
			favicon = "https://example.test/favicon.ico"
			tags = mutableSetOf("Reading", "zürich", "a, b")
			readLater = true
			snapshotFileId = snapshot.id
			metadataFetched(requestMetadata(), fetched = true)
		}
		save("https://example.test/")
		save("https://read.example.test/done") {
			title = "Already read"
			readLater = true
			readAt = clock.instant()
			tags = mutableSetOf("reading")
			metadataFetched(requestMetadata(), fetched = false)
		}
		save("https://pending.example.test/") { requestMetadata() }
		save("https://gone.example.test/") {
			title = "In the Trash"
			tags = mutableSetOf("old")
			softDelete(clock)
		}
	}

	private fun save(
		url: String,
		configure: Bookmark.() -> Unit = {},
	) {
		val bookmark = bookmarks.saveAndFlush(Bookmark(url).apply(configure))
		if (!bookmark.isDeleted) indexer.indexBookmark(bookmark)
	}
}
