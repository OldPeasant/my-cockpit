package ch.sonensei.cockpit.files.api

import ch.sonensei.cockpit.files.domain.DriveEntry
import ch.sonensei.cockpit.files.domain.DriveUsage
import ch.sonensei.cockpit.files.domain.FileItem
import ch.sonensei.cockpit.files.domain.Folder
import ch.sonensei.cockpit.files.domain.SpecialFolder
import ch.sonensei.cockpit.files.domain.UploadStatus
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode
import jakarta.validation.constraints.NotNull
import java.time.Instant
import java.util.UUID

@Schema(description = "A folder of the drive. `version` is also sent as the ETag.")
data class FolderResponse(
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val id: UUID,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val name: String,
	@get:Schema(description = "The parent folder; `null` at the drive root") val parentId: UUID?,
	@get:Schema(description = "Absolute path of the folder, e.g. `/Attachments/Tasks`", requiredMode = RequiredMode.REQUIRED)
	val path: String,
	@get:Schema(description = "Set for the auto-managed folders, which cannot be renamed, moved or deleted")
	val special: SpecialFolder?,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val createdAt: Instant,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val updatedAt: Instant,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val version: Long,
) {
	companion object {
		fun of(
			folder: Folder,
			path: String,
		) = FolderResponse(
			id = folder.id,
			name = folder.name,
			parentId = folder.parentId,
			path = path,
			special = folder.special,
			createdAt = checkNotNull(folder.createdAt),
			updatedAt = checkNotNull(folder.updatedAt),
			version = folder.version,
		)
	}
}

@Schema(description = "A file of the drive. `version` is also sent as the ETag.")
data class FileResponse(
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val id: UUID,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val name: String,
	@get:Schema(description = "The folder; `null` at the drive root") val folderId: UUID?,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val mime: String,
	@get:Schema(description = "Size in bytes", requiredMode = RequiredMode.REQUIRED) val size: Long,
	@get:Schema(description = "Lowercase hex SHA-256 of the contents", requiredMode = RequiredMode.REQUIRED) val sha256: String,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val createdAt: Instant,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val updatedAt: Instant,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val version: Long,
) {
	companion object {
		fun of(file: FileItem) =
			FileResponse(
				id = file.id,
				name = file.name,
				folderId = file.folderId,
				mime = file.mime,
				size = file.size,
				sha256 = file.sha256,
				createdAt = checkNotNull(file.createdAt),
				updatedAt = checkNotNull(file.updatedAt),
				version = file.version,
			)
	}
}

enum class DriveEntryType { FOLDER, FILE }

@Schema(description = "An entry of a folder listing: a folder (`folder` set) or a file (`file` set).")
data class DriveEntryResponse(
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val type: DriveEntryType,
	val folder: FolderSummary?,
	val file: FileResponse?,
) {
	companion object {
		fun of(entry: DriveEntry) =
			when (entry) {
				is DriveEntry.OfFolder -> DriveEntryResponse(DriveEntryType.FOLDER, FolderSummary.of(entry.folder), null)
				is DriveEntry.OfFile -> DriveEntryResponse(DriveEntryType.FILE, null, FileResponse.of(entry.file))
			}
	}
}

@Schema(description = "A folder in a listing (without its path, which is the listed folder's path plus the name).")
data class FolderSummary(
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val id: UUID,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val name: String,
	val parentId: UUID?,
	val special: SpecialFolder?,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val createdAt: Instant,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val updatedAt: Instant,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val version: Long,
) {
	companion object {
		fun of(folder: Folder) =
			FolderSummary(
				id = folder.id,
				name = folder.name,
				parentId = folder.parentId,
				special = folder.special,
				createdAt = checkNotNull(folder.createdAt),
				updatedAt = checkNotNull(folder.updatedAt),
				version = folder.version,
			)
	}
}

@Schema(description = "One page of a folder's contents: folders first, then files, each by name (regardless of case).")
data class DrivePageResponse(
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val items: List<DriveEntryResponse>,
	@get:Schema(description = "Cursor of the next page; `null` on the last page") val nextCursor: String?,
)

@Schema(description = "Disk usage of the drive")
data class DriveUsageResponse(
	@get:Schema(description = "Bytes of the files not in the Trash", requiredMode = RequiredMode.REQUIRED) val usedBytes: Long,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val fileCount: Long,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val folderCount: Long,
	@get:Schema(description = "Bytes of the files in the Trash", requiredMode = RequiredMode.REQUIRED) val trashBytes: Long,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val trashFileCount: Long,
	@get:Schema(description = "Size of the disk that holds the files", requiredMode = RequiredMode.REQUIRED) val totalSpaceBytes: Long,
	@get:Schema(description = "Free space on that disk", requiredMode = RequiredMode.REQUIRED) val usableSpaceBytes: Long,
	@get:Schema(description = "Largest file the drive accepts", requiredMode = RequiredMode.REQUIRED) val maxFileSize: Long,
) {
	companion object {
		fun of(
			usage: DriveUsage,
			maxFileSize: Long,
		) = with(usage) {
			DriveUsageResponse(usedBytes, fileCount, folderCount, trashBytes, trashFileCount, totalSpaceBytes, usableSpaceBytes, maxFileSize)
		}
	}
}

@Schema(description = "A new folder")
data class CreateFolderRequest(
	@field:NotNull
	@get:Schema(requiredMode = RequiredMode.REQUIRED, maxLength = 255)
	val name: String? = null,
	@get:Schema(description = "The parent folder; omitted or `null`: the drive root")
	val parentId: UUID? = null,
)

@Schema(description = "Changes of a folder: only the fields sent change. `parentId: null` moves it to the drive root.")
data class FolderPatchRequest(
	@get:Schema(maxLength = 255) val name: String? = null,
	val parentId: UUID? = null,
)

@Schema(description = "Changes of a file: only the fields sent change. `folderId: null` moves it to the drive root.")
data class FilePatchRequest(
	@get:Schema(maxLength = 255) val name: String? = null,
	val folderId: UUID? = null,
)

@Schema(description = "A new resumable upload")
data class CreateUploadRequest(
	@field:NotNull
	@get:Schema(description = "Name of the file", requiredMode = RequiredMode.REQUIRED, maxLength = 255)
	val name: String? = null,
	@get:Schema(description = "Target folder; omitted or `null`: the drive root")
	val folderId: UUID? = null,
	@get:Schema(description = "Mime type; default `application/octet-stream`")
	val mime: String? = null,
	@field:NotNull
	@get:Schema(description = "Size of the whole file in bytes", requiredMode = RequiredMode.REQUIRED)
	val size: Long? = null,
)

@Schema(description = "Completes an upload")
data class CompleteUploadRequest(
	@field:NotNull
	@get:Schema(description = "Hex SHA-256 of the whole file", requiredMode = RequiredMode.REQUIRED)
	val sha256: String? = null,
)

@Schema(
	description =
		"A resumable upload in progress. Send chunk `n` (0-based) with exactly `chunkSize` bytes (the last chunk: the rest) " +
			"to `PUT /uploads/{id}/chunks/{n}`; `receivedChunks` tells which arrived, so an interrupted upload resumes " +
			"with the missing ones.",
)
data class UploadResponse(
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val id: UUID,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val name: String,
	val folderId: UUID?,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val mime: String,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val size: Long,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val chunkSize: Int,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val chunkCount: Int,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val receivedChunks: List<Int>,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val createdAt: Instant,
) {
	companion object {
		fun of(status: UploadStatus) =
			with(status.session) {
				UploadResponse(id, name, folderId, mime, size, chunkSize, chunkCount, status.receivedChunks, checkNotNull(createdAt))
			}
	}
}
