package ch.sonensei.cockpit.files.api

import ch.sonensei.cockpit.core.web.CursorRequest
import ch.sonensei.cockpit.files.domain.DriveService
import ch.sonensei.cockpit.files.domain.UploadLimits
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** Listing the drive's folders (SPEC.md §8.2) and its disk usage. */
@RestController
@RequestMapping("/api/v1/drive")
@Tag(name = "Files", description = "The drive: folders, files, uploads and downloads")
class DriveController(
	private val drive: DriveService,
	private val limits: UploadLimits,
) {
	@GetMapping(produces = [MediaType.APPLICATION_JSON_VALUE, PROBLEM_JSON])
	@Operation(
		operationId = "listFolder",
		summary = "List a folder",
		description = "The folders and files in a folder (not in the Trash): folders first, then files, each by name regardless of case.",
	)
	@ApiResponse(
		responseCode = "200",
		description = "One page of the folder's contents",
		content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = DrivePageResponse::class))],
	)
	@FilesBadRequestResponse
	@FilesNotFoundResponse
	fun list(
		@Parameter(description = "The folder; omitted: the drive root") @RequestParam(required = false) folderId: UUID?,
		@Parameter(description = "Cursor from the previous page") @RequestParam(required = false) cursor: String?,
		@Parameter(description = "Page size, 1-200 (default 50)") @RequestParam(required = false) limit: Int?,
	): DrivePageResponse {
		val page = drive.list(folderId, CursorRequest.of(cursor, limit))
		return DrivePageResponse(page.items.map(DriveEntryResponse::of), page.nextCursor)
	}

	@GetMapping("/usage", produces = [MediaType.APPLICATION_JSON_VALUE])
	@Operation(operationId = "getDriveUsage", summary = "Disk usage of the drive")
	@ApiResponse(responseCode = "200", description = "Bytes used by files (and the Trash), and the disk's size and free space")
	fun usage(): DriveUsageResponse = DriveUsageResponse.of(drive.usage(), limits.maxSize)
}
