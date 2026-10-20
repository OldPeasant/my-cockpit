package ch.sonensei.cockpit.core.trash.api

import ch.sonensei.cockpit.core.items.domain.ItemRef
import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.core.trash.domain.TrashEntry
import ch.sonensei.cockpit.core.trash.domain.TrashService
import ch.sonensei.cockpit.core.web.CursorRequest
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

/** The Trash of soft-deleted items of every module (SPEC.md §4.2). */
@RestController
@RequestMapping("/api/v1/trash")
@Tag(name = "Trash", description = "Deleted items: list, restore, delete permanently")
class TrashController(
	private val trash: TrashService,
) {
	@GetMapping(produces = [MediaType.APPLICATION_JSON_VALUE])
	@Operation(
		operationId = "listTrash",
		summary = "Deleted items of all types",
		description =
			"Most recently deleted first (ties by type, then id). Items are purged automatically at `purgeAt`, 30 days " +
				"after their deletion.",
	)
	@ApiResponse(responseCode = "200", description = "One page of the Trash")
	@ApiResponse(
		responseCode = "400",
		description = "Invalid cursor or limit",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
	)
	fun list(
		@Parameter(description = "Opaque cursor from the previous page's `nextCursor`") @RequestParam(required = false) cursor: String?,
		@Parameter(description = "Page size, 1..200 (default 50)") @RequestParam(required = false) limit: Int?,
	): TrashPageResponse {
		val page = trash.list(CursorRequest.of(cursor, limit))
		return TrashPageResponse(page.items.map { it.toResponse() }, page.nextCursor)
	}

	@PostMapping("/{type}/{id}/restore")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(operationId = "restoreTrashItem", summary = "Restore a deleted item")
	@ApiResponse(responseCode = "204", description = "Restored")
	@ApiResponse(
		responseCode = "400",
		description = "Invalid type or id",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
	)
	@ApiResponse(
		responseCode = "404",
		description = "No such item in the Trash",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
	)
	fun restore(
		@Parameter(description = "Type of the item") @PathVariable type: ItemType,
		@Parameter(description = "Id of the item") @PathVariable id: UUID,
	) = trash.restore(ItemRef(type, id))

	@DeleteMapping("/{type}/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(
		operationId = "purgeTrashItem",
		summary = "Delete an item permanently",
		description = "Only items in the Trash can be deleted permanently; their links are removed as well.",
	)
	@ApiResponse(responseCode = "204", description = "Deleted permanently")
	@ApiResponse(
		responseCode = "400",
		description = "Invalid type or id",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
	)
	@ApiResponse(
		responseCode = "404",
		description = "No such item in the Trash",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
	)
	fun purge(
		@Parameter(description = "Type of the item") @PathVariable type: ItemType,
		@Parameter(description = "Id of the item") @PathVariable id: UUID,
	) = trash.purge(ItemRef(type, id))
}

data class TrashItemResponse(
	@field:Schema(requiredMode = RequiredMode.REQUIRED)
	val type: ItemType,
	@field:Schema(requiredMode = RequiredMode.REQUIRED)
	val id: UUID,
	@field:Schema(requiredMode = RequiredMode.REQUIRED)
	val title: String,
	val subtitle: String? = null,
	@field:Schema(description = "Material Symbols icon name", requiredMode = RequiredMode.REQUIRED)
	val icon: String,
	@field:Schema(requiredMode = RequiredMode.REQUIRED)
	val deletedAt: Instant,
	@field:Schema(description = "When the item will be purged automatically", requiredMode = RequiredMode.REQUIRED)
	val purgeAt: Instant,
)

data class TrashPageResponse(
	@field:Schema(requiredMode = RequiredMode.REQUIRED)
	val items: List<TrashItemResponse>,
	@field:Schema(description = "Cursor of the next page; absent on the last page")
	val nextCursor: String? = null,
)

private fun TrashEntry.toResponse() =
	TrashItemResponse(
		type = type,
		id = item.id,
		title = item.title,
		subtitle = item.subtitle,
		icon = item.icon,
		deletedAt = item.deletedAt,
		purgeAt = purgeAt,
	)

private const val PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON_VALUE
