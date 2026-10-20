package ch.sonensei.cockpit.gtd.api

import ch.sonensei.cockpit.core.web.ETags
import ch.sonensei.cockpit.gtd.domain.CaptureService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.headers.Header
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.support.ServletUriComponentsBuilder

/** Quick capture (SPEC.md §4.5, §5.4): an idempotent create of an Inbox task from a text, and a preview of its syntax. */
@RestController
@RequestMapping("/api/v1/capture")
@Tag(name = "Capture", description = "Quick capture to the Inbox")
class CaptureController(
	private val captures: CaptureService,
) {
	@PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE, PROBLEM_JSON])
	@Operation(
		operationId = "capture",
		summary = "Capture a task",
		description =
			"Creates a task in the INBOX (WAITING with a `^` token) from `text`, applying the capture syntax unless " +
				"`parse` is false, and links `fileIds` to it. With a client-generated `id` (UUIDv7) the capture is " +
				"idempotent: repeating it returns the existing task (200) unchanged.",
	)
	@ApiResponse(
		responseCode = "201",
		description = "Created",
		headers = [Header(name = HttpHeaders.ETAG, description = "Version of the task, for If-Match", schema = Schema(type = "string"))],
		content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = TaskResponse::class))],
	)
	@ApiResponse(
		responseCode = "200",
		description = "A task with this id already exists; it is returned unchanged",
		headers = [Header(name = HttpHeaders.ETAG, description = "Version of the task, for If-Match", schema = Schema(type = "string"))],
		content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = TaskResponse::class))],
	)
	@ApiResponse(
		responseCode = "400",
		description = "Invalid fields, a text without a title, an id that is not a UUIDv7 or an unknown file",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
	)
	@ApiResponse(
		responseCode = "409",
		description = "The id belongs to a task in the Trash",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
	)
	fun capture(
		@Valid @RequestBody request: CaptureRequest,
	): ResponseEntity<TaskResponse> {
		val result =
			try {
				captures.capture(request.toCommand())
			} catch (ex: DataIntegrityViolationException) {
				// A concurrent capture with the same client id inserted first (its transaction has rolled back here):
				// answer like the idempotent repeat.
				request.id?.let { captures.existing(it) } ?: throw ex
			}
		val task = result.task
		if (!result.created) return ETags.ok(TaskResponse.of(task), task.version)
		val location =
			ServletUriComponentsBuilder
				.fromCurrentContextPath()
				.path("/api/v1/tasks/{id}")
				.buildAndExpand(task.id)
				.toUri()
		return ResponseEntity
			.created(location)
			.eTag(ETags.of(task.version))
			.body(TaskResponse.of(task))
	}

	@PostMapping("/preview", consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE, PROBLEM_JSON])
	@Operation(
		operationId = "previewCapture",
		summary = "Preview the capture syntax",
		description = "What `POST /capture` would create from the text (with `parse`); nothing is stored.",
	)
	@ApiResponse(responseCode = "200", description = "The parse result")
	@ApiResponse(
		responseCode = "400",
		description = "Missing or too long text",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
	)
	fun preview(
		@Valid @RequestBody request: CapturePreviewRequest,
	): CapturePreviewResponse = CapturePreviewResponse.of(captures.preview(checkNotNull(request.text)))
}
