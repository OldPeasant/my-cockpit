package ch.sonensei.cockpit.files.infra

import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.core.trash.domain.TrashContributor
import ch.sonensei.cockpit.core.trash.domain.TrashedItem
import ch.sonensei.cockpit.files.domain.FilesService
import org.springframework.stereotype.Component
import java.util.UUID

/** Deleted files in the Trash (SPEC.md §4.2); purging one deletes its blob. Subtitle is the folder path. */
@Component
class FileTrashContributor(
	private val service: FilesService,
) : TrashContributor {
	override val type = ItemType.FILE

	override fun listDeleted(): List<TrashedItem> =
		service.trashedFiles().map {
			TrashedItem(it.id, it.name, it.deletedAt!!, service.pathOf(it.folderId), fileIcon(it.mime))
		}

	override fun restore(id: UUID): Boolean = service.restoreFile(id)

	override fun purge(id: UUID): Boolean = service.purgeFile(id)
}

/**
 * Deleted folders in the Trash (SPEC.md §4.2), each with everything deleted with it; purging one deletes its whole
 * subtree with the blobs. Subtitle is the parent path.
 */
@Component
class FolderTrashContributor(
	private val service: FilesService,
) : TrashContributor {
	override val type = ItemType.FOLDER

	override fun listDeleted(): List<TrashedItem> =
		service.trashedFolders().map {
			TrashedItem(it.id, it.name, it.deletedAt!!, service.pathOf(it.parentId), FOLDER_ICON)
		}

	override fun restore(id: UUID): Boolean = service.restoreFolder(id)

	override fun purge(id: UUID): Boolean = service.purgeFolder(id)
}
