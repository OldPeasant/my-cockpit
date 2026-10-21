package ch.sonensei.cockpit.gtd.convert.domain

import ch.sonensei.cockpit.core.items.domain.ItemRef
import ch.sonensei.cockpit.core.items.domain.ItemResolverRegistry
import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.core.items.domain.LinkService
import ch.sonensei.cockpit.gtd.domain.TaskService
import ch.sonensei.cockpit.notes.domain.FieldChange
import ch.sonensei.cockpit.notes.domain.Note
import ch.sonensei.cockpit.notes.domain.NoteChanges
import ch.sonensei.cockpit.notes.domain.NoteService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * "Move to reference" (SPEC.md §5.1): converts a task into a note, in one transaction.
 *
 * The note gets the task's title and its notes as body (inline links in the body are synced as for any note). Each item
 * linked to the task is linked to the note as well, as a manual link with the same direction and label: links owned by
 * another item (sync links, e.g. a note's inline link to the task) become manual ones, because their owner's next sync
 * would remove a copied sync link. Linked items that no longer exist (or are in the Trash) are not carried over. The
 * task goes to the Trash with its own links, so restoring it brings it back as it was.
 */
@Service
@Transactional
class TaskToNoteService(
	private val tasks: TaskService,
	private val notes: NoteService,
	private val links: LinkService,
	private val resolvers: ItemResolverRegistry,
) {
	/**
	 * Converts the task [id] into a note and returns the note. [checkVersion] sees the task's current version before
	 * anything changes (If-Match).
	 *
	 * @throws ch.sonensei.cockpit.core.web.NotFoundException (404) when there is no such task or it is in the Trash
	 * @throws ch.sonensei.cockpit.core.web.InvalidRequestException (400) when the task's content is not a valid note
	 */
	fun convert(
		id: UUID,
		checkVersion: (Long) -> Unit,
	): Note {
		val task = tasks.get(id)
		val taskRef = ItemRef(ItemType.TASK, task.id)
		val linked = links.linksOf(taskRef).map { it.link }
		tasks.delete(id, checkVersion)
		val note = notes.create(NoteChanges(title = FieldChange(task.title), bodyMd = FieldChange(task.notesMd.orEmpty())))
		val noteRef = ItemRef(ItemType.NOTE, note.id)
		linked.forEach { link ->
			val fromTask = link.source == taskRef
			val other = if (fromTask) link.target else link.source
			// LinkService.create would reject it, and its exception would mark this transaction for rollback.
			if (!exists(other)) return@forEach
			if (fromTask) links.create(noteRef, other, link.label) else links.create(other, noteRef, link.label)
		}
		return note
	}

	/** Whether [ref] exists; an item of a type without a resolver counts as existing, as for [LinkService.create]. */
	private fun exists(ref: ItemRef): Boolean = resolvers.find(ref.type)?.exists(ref.id) ?: true
}
