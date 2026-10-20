package ch.sonensei.cockpit.gtd.infra

import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.core.search.domain.SearchIndexer
import ch.sonensei.cockpit.core.trash.domain.TrashContributor
import ch.sonensei.cockpit.core.trash.domain.TrashedItem
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Deleted projects in the Trash. A restored project is searchable again; a purge deletes the row, and its tasks (foreign
 * key `on delete set null`) no longer have a project.
 */
@Component
class ProjectTrashContributor(
	private val projects: ProjectRepository,
	private val indexer: SearchIndexer,
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
		projects.delete(project)
		projects.flush()
		return true
	}
}
