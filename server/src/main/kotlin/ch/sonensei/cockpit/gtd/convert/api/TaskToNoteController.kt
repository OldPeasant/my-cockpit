package ch.sonensei.cockpit.gtd.convert.api

import ch.sonensei.cockpit.core.web.ETags
import ch.sonensei.cockpit.gtd.convert.domain.TaskToNoteService
import ch.sonensei.cockpit.notes.api.NoteResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.headers.Header
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.support.ServletUriComponentsBuilder
import java.util.UUID

private const val PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON_VALUE

/** "Move to reference" (SPEC.md §5.1): converts a task into a note. */
@RestController
@RequestMapping("/api/v1/tasks")
@Tag(name = "Tasks", description = "GTD tasks")
class TaskToNoteController(
	private val converter: TaskToNoteService,
) {
	@PostMapping("/{id}/convert-to-note", produces = [MediaType.APPLICATION_JSON_VALUE, PROBLEM_JSON])
	@Operation(
		operationId = "convertTaskToNote",
		summary = "Move a task to reference (convert it into a note)",
		description =
			"Creates a note with the task's title and its notes as body, links every existing item linked to the task " +
				"to the note (manual links, same direction and label) and moves the task to the Trash, in one " +
				"transaction. `If-Match` is optional.",
	)
	@ApiResponse(
		responseCode = "201",
		description = "The new note; `Location` points to it",
		headers = [Header(name = HttpHeaders.ETAG, description = "Version of the note, for If-Match", schema = Schema(type = "string"))],
		content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = NoteResponse::class))],
	)
	@ApiResponse(
		responseCode = "400",
		description = "The task's content is not a valid note",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
	)
	@ApiResponse(
		responseCode = "404",
		description = "No such task, or it is in the Trash",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
	)
	@ApiResponse(
		responseCode = "409",
		description = "Changed concurrently",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
	)
	@ApiResponse(
		responseCode = "412",
		description = "If-Match was sent and does not match the task's current ETag",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
	)
	fun convert(
		@PathVariable id: UUID,
		@Parameter(description = "ETag of the task (optional)") @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
	): ResponseEntity<NoteResponse> {
		val note = converter.convert(id) { if (!ifMatch.isNullOrBlank()) ETags.checkIfMatch(ifMatch, it) }
		val location =
			ServletUriComponentsBuilder
				.fromCurrentContextPath()
				.path("/api/v1/notes/{id}")
				.buildAndExpand(note.id)
				.toUri()
		return ResponseEntity
			.created(location)
			.eTag(ETags.of(note.version))
			.body(NoteResponse.of(note))
	}
}
