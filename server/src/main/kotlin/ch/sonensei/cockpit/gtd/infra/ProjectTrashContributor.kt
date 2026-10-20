package ch.sonensei.cockpit.gtd.infra

import ch.sonensei.cockpit.core.events.domain.ChangeEventPublisher
import ch.sonensei.cockpit.core.events.domain.ChangeOp
import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.core.search.domain.SearchIndexer
import ch.sonensei.cockpit.core.trash.domain.TrashContributor
import ch.sonensei.cockpit.core.trash.domain.TrashedItem
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Deleted projects in the Trash. A restored project is searchable again; a purge first takes its tasks (also those in
 * the Trash) out of the project in code, so their versions bump and `TASK UPDATED` events fire for the ones not in the
 * Trash (they move to the end of their list), then deletes the row.
 */
@Component
class ProjectTrashContributor(
	private val projects: ProjectRepository,
	private val tasks: TaskRepository,
	private val indexer: SearchIndexer,
	private val changes: ChangeEventPublisher,
) : TrashContributor {
	override val type = ItemType.PROJECT

	override fun listDeleted(): List<TrashedItem> =
		projects.findAllDeleted().map {
			TrashedItem(
				id = it.id,
				title = it.title,
				deletedAt = checkNotNull(it.deletedAt),
				subtitle = "Project",
				icon = "folder_open",
			)
		}

	override fun restore(id: UUID): Boolean {
		val project = projects.findById(id).orElse(null)?.takeIf { it.isDeleted } ?: return false
		project.restore()
		projects.saveAndFlush(project)
		indexer.upsert(ItemType.PROJECT, project.id, project.title, project.descriptionMd)
		return true
	}

	override fun purge(id: UUID): Boolean {
		val project = projects.findById(id).orElse(null)?.takeIf { it.isDeleted } ?: return false
		tasks.findByProjectIdOrderByPositionAscIdAsc(id).forEach { task ->
			// The position query runs before the change: it auto-flushes, which would bump the version twice.
			if (!task.isDeleted) task.position = (tasks.maxPositionInList(task.list) ?: -1) + 1
			task.projectId = null
			tasks.saveAndFlush(task)
			if (!task.isDeleted) changes.publish(ItemType.TASK, task.id, ChangeOp.UPDATED)
		}
		projects.delete(project)
		projects.flush()
		return true
	}
}
