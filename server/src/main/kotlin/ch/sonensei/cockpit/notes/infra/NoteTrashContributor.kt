package ch.sonensei.cockpit.notes.infra

import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.core.search.domain.SearchIndexer
import ch.sonensei.cockpit.core.trash.domain.TrashContributor
import ch.sonensei.cockpit.core.trash.domain.TrashedItem
import ch.sonensei.cockpit.notes.domain.indexNote
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Deleted notes in the Trash. A restored note is searchable again (its links stayed while it was in the Trash); a purge
 * deletes the note with its tags and revisions. Its images stay in `/Attachments/Notes`.
 */
@Component
class NoteTrashContributor(
	private val notes: NoteRepository,
	private val indexer: SearchIndexer,
) : TrashContributor {
	override val type = ItemType.NOTE

	override fun listDeleted(): List<TrashedItem> =
		notes.findAllDeleted().map {
			TrashedItem(
				id = it.id,
				title = it.displayTitle,
				deletedAt = checkNotNull(it.deletedAt),
				subtitle = "Note",
				icon = "description",
			)
		}

	override fun restore(id: UUID): Boolean {
		val note = notes.findById(id).orElse(null)?.takeIf { it.isDeleted } ?: return false
		note.restore()
		notes.saveAndFlush(note)
		indexer.indexNote(note)
		return true
	}

	override fun purge(id: UUID): Boolean {
		val note = notes.findById(id).orElse(null)?.takeIf { it.isDeleted } ?: return false
		// Tags and revisions go with the row (on delete cascade).
		notes.delete(note)
		notes.flush()
		return true
	}
}
