package ch.sonensei.cockpit.gtd.infra

import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.core.search.domain.SearchIndexer
import ch.sonensei.cockpit.core.trash.domain.TrashContributor
import ch.sonensei.cockpit.core.trash.domain.TrashedItem
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Deleted tasks in the Trash. A restored task is searchable again; a purge deletes the row with its contexts and
 * checklist (foreign keys cascade) and clears it as a project's next step.
 */
@Component
class TaskTrashContributor(
	private val tasks: TaskRepository,
	private val indexer: SearchIndexer,
) : TrashContributor {
	override val type = ItemType.TASK

	override fun listDeleted(): List<TrashedItem> =
		tasks.findAllDeleted().map {
			TrashedItem(
				id = it.id,
				title = it.title,
				deletedAt = checkNotNull(it.deletedAt),
				subtitle = if (it.isCompleted) "Done" else it.list.label,
				icon = "task_alt",
			)
		}

	override fun restore(id: UUID): Boolean {
		val task = tasks.findById(id).orElse(null)?.takeIf { it.isDeleted } ?: return false
		task.restore()
		tasks.saveAndFlush(task)
		indexer.upsert(ItemType.TASK, task.id, task.title, task.notesMd)
		return true
	}

	override fun purge(id: UUID): Boolean {
		val task = tasks.findById(id).orElse(null)?.takeIf { it.isDeleted } ?: return false
		tasks.delete(task)
		tasks.flush()
		return true
	}
}
