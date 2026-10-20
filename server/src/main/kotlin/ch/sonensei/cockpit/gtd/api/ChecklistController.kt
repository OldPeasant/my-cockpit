package ch.sonensei.cockpit.gtd.api

import ch.sonensei.cockpit.core.web.ETags
import ch.sonensei.cockpit.gtd.domain.ChecklistItemChanges
import ch.sonensei.cockpit.gtd.domain.ChecklistService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.headers.Header
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.support.ServletUriComponentsBuilder
import java.util.UUID

/** The checklist of a task. Each item has its own `ETag`; PATCH and DELETE need it in `If-Match`. */
@RestController
@RequestMapping("/api/v1/tasks/{taskId}/checklist")
@Tag(name = "Tasks", description = "GTD tasks")
class ChecklistController(
	private val checklist: ChecklistService,
) {
	@GetMapping(produces = [MediaType.APPLICATION_JSON_VALUE, PROBLEM_JSON])
	@Operation(operationId = "listChecklistItems", summary = "A task's checklist")
	@ApiResponse(responseCode = "200", description = "The items in order")
	@ApiResponse(
		responseCode = "404",
		description = "No such task, or it is in the Trash",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
	)
	fun list(
		@PathVariable taskId: UUID,
	): ChecklistResponse = ChecklistResponse(checklist.list(taskId).map(ChecklistItemResponse::of))

	@PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE, PROBLEM_JSON])
	@Operation(operationId = "createChecklistItem", summary = "Add a checklist item at the end")
	@ApiResponse(
		responseCode = "201",
		description = "Created",
		headers = [Header(name = HttpHeaders.ETAG, description = "Version of the item, for If-Match", schema = Schema(type = "string"))],
		content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ChecklistItemResponse::class))],
	)
	@ApiResponse(
		responseCode = "400",
		description = "Missing, blank or too long text",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
	)
	@ApiResponse(
		responseCode = "404",
		description = "No such task, or it is in the Trash",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
	)
	fun create(
		@PathVariable taskId: UUID,
		@Valid @RequestBody request: CreateChecklistItemRequest,
	): ResponseEntity<ChecklistItemResponse> {
		val item = checklist.add(taskId, request.text!!, request.done ?: false)
		val location =
			ServletUriComponentsBuilder
				.fromCurrentRequestUri()
				.path("/{id}")
				.buildAndExpand(item.id)
				.toUri()
		return ResponseEntity
			.created(location)
			.eTag(ETags.of(item.version))
			.body(ChecklistItemResponse.of(item))
	}

	@PatchMapping("/{itemId}", consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE, PROBLEM_JSON])
	@Operation(operationId = "updateChecklistItem", summary = "Change a checklist item (text, done, position)")
	@ApiResponse(
		responseCode = "200",
		description = "Changed; the item with its new ETag",
		headers = [Header(name = HttpHeaders.ETAG, description = "New version of the item", schema = Schema(type = "string"))],
		content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ChecklistItemResponse::class))],
	)
	@ApiResponse(
		responseCode = "400",
		description = "Blank or too long text",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
	)
	@ApiResponse(
		responseCode = "404",
		description = "No such task or item",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
	)
	@ApiResponse(
		responseCode = "409",
		description = "Changed concurrently",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
	)
	@ApiResponse(
		responseCode = "412",
		description = "If-Match does not match the current ETag",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
	)
	@ApiResponse(
		responseCode = "428",
		description = "If-Match is missing",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
	)
	fun update(
		@PathVariable taskId: UUID,
		@PathVariable itemId: UUID,
		@Parameter(description = "ETag of the item") @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
		@RequestBody request: UpdateChecklistItemRequest,
	): ResponseEntity<ChecklistItemResponse> {
		val changes = ChecklistItemChanges(request.text, request.done, request.position)
		val item = checklist.update(taskId, itemId, changes) { ETags.checkIfMatch(ifMatch, it) }
		return ETags.ok(ChecklistItemResponse.of(item), item.version)
	}

	@DeleteMapping("/{itemId}", produces = [PROBLEM_JSON])
	@Operation(operationId = "deleteChecklistItem", summary = "Remove a checklist item")
	@ApiResponse(responseCode = "204", description = "Removed")
	@ApiResponse(
		responseCode = "404",
		description = "No such task or item",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
	)
	@ApiResponse(
		responseCode = "412",
		description = "If-Match does not match the current ETag",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
	)
	@ApiResponse(
		responseCode = "428",
		description = "If-Match is missing",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
	)
	fun delete(
		@PathVariable taskId: UUID,
		@PathVariable itemId: UUID,
		@Parameter(description = "ETag of the item") @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
	): ResponseEntity<Void> {
		checklist.delete(taskId, itemId) { ETags.checkIfMatch(ifMatch, it) }
		return ResponseEntity.status(HttpStatus.NO_CONTENT).build()
	}
}
