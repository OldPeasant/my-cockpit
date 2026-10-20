package ch.sonensei.cockpit.files.infra

import ch.sonensei.cockpit.core.items.domain.ItemResolver
import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.core.items.domain.ResolvedItem
import ch.sonensei.cockpit.files.domain.FilesService
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/** Files in generic views (SPEC.md §4.3): subtitle is the folder path, icon by mime type, deep link `/files/{id}`. */
@Component
class FileItemResolver(
	private val files: FileRepository,
	private val service: FilesService,
) : ItemResolver {
	override val type = ItemType.FILE

	@Transactional(readOnly = true)
	override fun resolve(id: UUID): ResolvedItem? =
		files.findActiveById(id)?.let {
			ResolvedItem(
				title = it.name,
				subtitle = service.pathOf(it.folderId),
				icon = fileIcon(it.mime),
				deepLink = "/files/${it.id}",
			)
		}
}

/** Folders in generic views (SPEC.md §4.3): subtitle is the parent path, deep link `/files/folders/{id}`. */
@Component
class FolderItemResolver(
	private val folders: FolderRepository,
	private val service: FilesService,
) : ItemResolver {
	override val type = ItemType.FOLDER

	@Transactional(readOnly = true)
	override fun resolve(id: UUID): ResolvedItem? =
		folders.findActiveById(id)?.let {
			ResolvedItem(
				title = it.name,
				subtitle = service.pathOf(it.parentId),
				icon = if (it.isSpecial) FOLDER_SPECIAL_ICON else FOLDER_ICON,
				deepLink = "/files/folders/${it.id}",
			)
		}
}

internal const val FOLDER_ICON = "folder"
internal const val FOLDER_SPECIAL_ICON = "folder_special"

/** The Material Symbols icon of a file by its mime type. */
internal fun fileIcon(mime: String): String {
	val type = mime.substringBefore(';').trim().lowercase()
	return when {
		type.startsWith("image/") -> "image"
		type == "application/pdf" -> "picture_as_pdf"
		type.startsWith("audio/") -> "audio_file"
		type.startsWith("video/") -> "video_file"
		type.startsWith("text/") -> "description"
		else -> "draft"
	}
}
