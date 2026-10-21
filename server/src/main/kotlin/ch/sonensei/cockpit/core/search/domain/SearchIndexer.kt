package ch.sonensei.cockpit.core.search.domain

import ch.sonensei.cockpit.core.items.domain.ItemDeletedEvent
import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.core.persistence.storableInstant
import ch.sonensei.cockpit.core.search.infra.SearchDocumentRepository
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * How modules feed the global search (SPEC.md §4.4): each module calls [upsert] in the transaction that creates or
 * changes a searchable item, and [delete] when the item is deleted, including a move to the Trash (an item in the
 * Trash is not searchable; a restore calls [upsert] again). A permanent delete published as [ItemDeletedEvent] removes
 * the document as well.
 *
 * [title] is the item's short display text, [body] its long text (note content, mail body, description), may be
 * empty. Both are plain text; the module strips markup first. The document's recency is the time of the last [upsert],
 * or [upsert]'s `recency` when the item has a date of its own that should rank it (e.g. when a mail arrived).
 */
@Service
@Transactional
class SearchIndexer(
	private val documents: SearchDocumentRepository,
	private val clock: Clock,
) {
	fun upsert(
		itemType: ItemType,
		id: UUID,
		title: String,
		body: String?,
		recency: Instant? = null,
	) {
		val updatedAt = recency ?: clock.storableInstant()
		documents.upsert(itemType, id, clean(title, MAX_TITLE_LENGTH), clean(body.orEmpty(), MAX_BODY_LENGTH), updatedAt)
	}

	fun delete(
		itemType: ItemType,
		id: UUID,
	) {
		documents.delete(itemType, id)
	}

	/** Removes the document of a permanently deleted item, in the publisher's transaction. */
	@EventListener
	fun onItemDeleted(event: ItemDeletedEvent) = delete(event.item.type, event.item.id)

	// PostgreSQL text cannot hold NUL; very long bodies (mail) would exceed the tsvector size limit.
	private fun clean(
		text: String,
		maxLength: Int,
	): String = text.replace("\u0000", "").take(maxLength)

	companion object {
		const val MAX_TITLE_LENGTH = 1_000
		const val MAX_BODY_LENGTH = 100_000
	}
}
