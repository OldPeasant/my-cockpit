package ch.sonensei.cockpit.journal.entries.infra

import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.core.search.domain.SearchIndexer
import ch.sonensei.cockpit.core.trash.domain.TrashContributor
import ch.sonensei.cockpit.core.trash.domain.TrashedItem
import ch.sonensei.cockpit.core.web.ConflictException
import ch.sonensei.cockpit.journal.entries.domain.indexJournalEntry
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Deleted journal entries in the Trash. A restored entry is searchable again (its links stayed while it was in the
 * Trash), unless its date got a new entry in the meantime: then the restore is refused (one entry per date). A purge
 * deletes the entry with its tags; its images stay in `/Attachments/Journal`.
 */
@Component
class JournalEntryTrashContributor(
	private val entries: JournalEntryRepository,
	private val indexer: SearchIndexer,
) : TrashContributor {
	override val type = ItemType.JOURNAL_ENTRY

	override fun listDeleted(): List<TrashedItem> =
		entries.findAllDeleted().map {
			TrashedItem(
				id = it.id,
				title = it.displayTitle,
				deletedAt = checkNotNull(it.deletedAt),
				subtitle = JournalEntryItemResolver.SUBTITLE,
				icon = JournalEntryItemResolver.ICON,
			)
		}

	/** @throws ConflictException (409) when the entry's date has another entry (not in the Trash) */
	override fun restore(id: UUID): Boolean {
		val entry = entries.findById(id).orElse(null)?.takeIf { it.isDeleted } ?: return false
		if (entries.findActiveByDate(entry.date) != null) {
			throw ConflictException("${entry.date} already has a journal entry; delete it first to restore this one")
		}
		entry.restore()
		entries.saveAndFlush(entry)
		indexer.indexJournalEntry(entry)
		return true
	}

	override fun purge(id: UUID): Boolean {
		val entry = entries.findById(id).orElse(null)?.takeIf { it.isDeleted } ?: return false
		// Tags go with the row (on delete cascade).
		entries.delete(entry)
		entries.flush()
		return true
	}
}
