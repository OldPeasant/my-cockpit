package ch.sonensei.cockpit.bookmarks.infra

import ch.sonensei.cockpit.bookmarks.domain.indexBookmark
import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.core.search.domain.SearchIndexer
import ch.sonensei.cockpit.core.trash.domain.TrashContributor
import ch.sonensei.cockpit.core.trash.domain.TrashedItem
import org.hibernate.Hibernate
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Deleted bookmarks in the Trash. A restored bookmark is searchable again (its links stayed while it was in the
 * Trash); a purge deletes the bookmark with its tags. Its snapshot file stays in Files.
 */
@Component
class BookmarkTrashContributor(
	private val bookmarks: BookmarkRepository,
	private val indexer: SearchIndexer,
) : TrashContributor {
	override val type = ItemType.BOOKMARK

	override fun listDeleted(): List<TrashedItem> =
		bookmarks.findAllDeleted().map {
			TrashedItem(
				id = it.id,
				title = it.displayTitle,
				deletedAt = checkNotNull(it.deletedAt),
				subtitle = "Bookmark",
				icon = "bookmark",
			)
		}

	override fun restore(id: UUID): Boolean {
		val bookmark = bookmarks.findById(id).orElse(null)?.takeIf { it.isDeleted } ?: return false
		bookmark.restore()
		Hibernate.initialize(bookmark.tags)
		bookmarks.saveAndFlush(bookmark)
		indexer.indexBookmark(bookmark)
		return true
	}

	override fun purge(id: UUID): Boolean {
		val bookmark = bookmarks.findById(id).orElse(null)?.takeIf { it.isDeleted } ?: return false
		// Tags go with the row (on delete cascade).
		bookmarks.delete(bookmark)
		bookmarks.flush()
		return true
	}
}
