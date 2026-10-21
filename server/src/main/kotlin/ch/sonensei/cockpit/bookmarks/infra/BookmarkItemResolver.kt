package ch.sonensei.cockpit.bookmarks.infra

import ch.sonensei.cockpit.core.items.domain.ItemResolver
import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.core.items.domain.ResolvedItem
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Bookmarks in generic views (SPEC.md §4.3): the title (or host), subtitle "Read later" for an unread bookmark in the
 * read-later list, else "Bookmark", deep link `/bookmarks/{id}`.
 */
@Component
class BookmarkItemResolver(
	private val bookmarks: BookmarkRepository,
) : ItemResolver {
	override val type = ItemType.BOOKMARK

	@Transactional(readOnly = true)
	override fun resolve(id: UUID): ResolvedItem? =
		bookmarks.findActiveById(id)?.let {
			ResolvedItem(
				title = it.displayTitle,
				subtitle = if (it.readLater && it.readAt == null) "Read later" else "Bookmark",
				icon = "bookmark",
				deepLink = "/bookmarks/${it.id}",
			)
		}
}
