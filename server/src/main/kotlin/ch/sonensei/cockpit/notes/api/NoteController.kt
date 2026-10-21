package ch.sonensei.cockpit.notes.api

import ch.sonensei.cockpit.core.web.CursorRequest
import ch.sonensei.cockpit.core.web.ETags
import ch.sonensei.cockpit.core.web.InvalidRequestException
import ch.sonensei.cockpit.files.infra.BlobTooLargeException
import ch.sonensei.cockpit.notes.domain.Note
import ch.sonensei.cockpit.notes.domain.NoteFilter
import ch.sonensei.cockpit.notes.domain.NoteService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.headers.Header
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.MediaTypeFactory
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.servlet.support.ServletUriComponentsBuilder
import tools.jackson.databind.json.JsonMapper
import java.util.UUID
import io.swagger.v3.oas.annotations.parameters.RequestBody as DocumentedBody

/**
 * Notes (SPEC.md §8.1): list with filters, CRUD, tags, revisions and image upload. Responses carry the note's `ETag`;
 * PATCH, DELETE and restoring a revision need it in `If-Match`.
 */
@RestController
@RequestMapping("/api/v1/notes")
@Tag(name = "Notes", description = "Markdown notes with tags, pins, images and history")
class NoteController(
	private val notes: NoteService,
	private val jsonMapper: JsonMapper,
) {
	@GetMapping(produces = [MediaType.APPLICATION_JSON_VALUE])
	@Operation(
		operationId = "listNotes",
		summary = "List notes",
		description = "Notes matching every given filter, not in the Trash: pinned first, then the most recently changed.",
	)
	@ApiResponse(responseCode = "200", description = "One page of notes")
	@NotesBadRequestResponse
	fun list(
		@Parameter(description = "Only notes with this tag (regardless of case)") @RequestParam(required = false) tag: String?,
		@Parameter(description = "true: only pinned notes, false: only notes that are not pinned")
		@RequestParam(required = false)
		pinned: Boolean?,
		@Parameter(
			description = "false (default): only notes that are not archived, true: only archived notes, all: both",
			schema = Schema(type = "string", allowableValues = ["true", "false", "all"]),
		)
		@RequestParam(required = false)
		archived: String?,
		@Parameter(description = "Cursor from the previous page") @RequestParam(required = false) cursor: String?,
		@Parameter(description = "Page size, 1-200 (default 50)") @RequestParam(required = false) limit: Int?,
	): NotePageResponse {
		val archivedFilter =
			when (archived?.lowercase()) {
				null, "false" -> false
				"true" -> true
				"all" -> null
				else -> throw InvalidRequestException("archived", "must be true, false or all")
			}
		val filter = NoteFilter(tag?.takeIf { it.isNotBlank() }, pinned, archivedFilter)
		val page = notes.list(filter, CursorRequest.of(cursor, limit))
		return NotePageResponse(page.items.map(NoteResponse::of), page.nextCursor)
	}

	@PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE, PROBLEM_JSON])
	@Operation(operationId = "createNote", summary = "Create a note")
	@ApiResponse(
		responseCode = "201",
		description = "Created",
		headers = [Header(name = HttpHeaders.ETAG, description = "Version of the note, for If-Match", schema = Schema(type = "string"))],
		content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = NoteResponse::class))],
	)
	@NotesBadRequestResponse
	fun create(
		@RequestBody request: CreateNoteRequest,
	): ResponseEntity<NoteResponse> {
		val note = notes.create(request.toChanges())
		val location =
			ServletUriComponentsBuilder
				.fromCurrentRequestUri()
				.path("/{id}")
				.buildAndExpand(note.id)
				.toUri()
		return ResponseEntity
			.created(location)
			.eTag(ETags.of(note.version))
			.body(NoteResponse.of(note))
	}

	@GetMapping("/tags", produces = [MediaType.APPLICATION_JSON_VALUE])
	@Operation(operationId = "listNoteTags", summary = "Every tag in use, with its number of notes")
	@ApiResponse(responseCode = "200", description = "The tags")
	fun tags(): NoteTagListResponse = NoteTagListResponse(notes.tags().map(NoteTagResponse::of))

	@GetMapping("/{id}", produces = [MediaType.APPLICATION_JSON_VALUE, PROBLEM_JSON])
	@Operation(operationId = "getNote", summary = "One note")
	@NoteOkResponse
	@NotesNotFoundResponse
	fun get(
		@PathVariable id: UUID,
	): ResponseEntity<NoteResponse> = respond(notes.get(id))

	@PatchMapping("/{id}", consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE, PROBLEM_JSON])
	@Operation(
		operationId = "updateNote",
		summary = "Change a note",
		description =
			"Only the fields sent change; `null` clears the title. A change of the title or body keeps the previous " +
				"content as a revision (at most one per 5 minutes).",
	)
	@DocumentedBody(content = [Content(schema = Schema(implementation = NotePatchRequest::class))])
	@NoteOkResponse
	@NotesBadRequestResponse
	@NotesNotFoundResponse
	@NotesIfMatchResponses
	fun update(
		@PathVariable id: UUID,
		@Parameter(description = "ETag of the note") @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
		@RequestBody body: Map<String, Any?>,
	): ResponseEntity<NoteResponse> {
		val changes = jsonMapper.parseNotePatch(body)
		return respond(notes.update(id, changes) { ETags.checkIfMatch(ifMatch, it) })
	}

	@DeleteMapping("/{id}", produces = [PROBLEM_JSON])
	@Operation(operationId = "deleteNote", summary = "Move a note to the Trash")
	@ApiResponse(responseCode = "204", description = "In the Trash")
	@NotesNotFoundResponse
	@NotesIfMatchResponses
	fun delete(
		@PathVariable id: UUID,
		@Parameter(description = "ETag of the note") @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
	): ResponseEntity<Void> {
		notes.delete(id) { ETags.checkIfMatch(ifMatch, it) }
		return ResponseEntity.status(HttpStatus.NO_CONTENT).build()
	}

	@GetMapping("/{id}/revisions", produces = [MediaType.APPLICATION_JSON_VALUE, PROBLEM_JSON])
	@Operation(operationId = "listNoteRevisions", summary = "Earlier versions of a note (the last 20), newest first")
	@ApiResponse(responseCode = "200", description = "The revisions")
	@NotesNotFoundResponse
	fun revisions(
		@PathVariable id: UUID,
	): NoteRevisionListResponse = NoteRevisionListResponse(notes.revisions(id).map(NoteRevisionResponse::of))

	@PostMapping("/{id}/revisions/{number}/restore", produces = [MediaType.APPLICATION_JSON_VALUE, PROBLEM_JSON])
	@Operation(
		operationId = "restoreNoteRevision",
		summary = "Put an earlier version back",
		description =
			"Sets the title and body to those of the revision; tags, pin and archive state stay. The replaced content " +
				"becomes a new revision, so the restore can be undone.",
	)
	@NoteOkResponse
	@NotesNotFoundResponse
	@NotesIfMatchResponses
	fun restoreRevision(
		@PathVariable id: UUID,
		@Parameter(description = "Number of the revision") @PathVariable number: Int,
		@Parameter(description = "ETag of the note") @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
	): ResponseEntity<NoteResponse> = respond(notes.restoreRevision(id, number) { ETags.checkIfMatch(ifMatch, it) })

	@PostMapping("/{id}/images", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE, PROBLEM_JSON])
	@Operation(
		operationId = "uploadNoteImage",
		summary = "Store an image for a note",
		description =
			"One image in the part `file`, stored in `/Attachments/Notes`. The note does not change: insert the returned " +
				"`markdown` into its body. The mime type is the part's content type (or guessed from the filename).",
	)
	@ApiResponse(
		responseCode = "201",
		description = "Stored",
		content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = NoteImageResponse::class))],
	)
	@ApiResponse(
		responseCode = "413",
		description = "Larger than the configured maximum file size",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
	)
	@NotesBadRequestResponse
	@NotesNotFoundResponse
	fun uploadImage(
		@PathVariable id: UUID,
		@RequestPart("file") file: MultipartFile,
	): ResponseEntity<NoteImageResponse> {
		val name = file.originalFilename?.takeIf { it.isNotBlank() } ?: DEFAULT_IMAGE_NAME
		val mime =
			file.contentType?.takeIf { it.isNotBlank() && it != MediaType.APPLICATION_OCTET_STREAM_VALUE }
				?: MediaTypeFactory.getMediaType(name).map { it.toString() }.orElse(MediaType.APPLICATION_OCTET_STREAM_VALUE)
		val stored = file.inputStream.use { notes.addImage(id, name, mime, it) }
		val location =
			ServletUriComponentsBuilder
				.fromCurrentContextPath()
				.path("/api/v1/files/{id}")
				.buildAndExpand(stored.id)
				.toUri()
		return ResponseEntity.created(location).body(NoteImageResponse.of(stored))
	}

	@ExceptionHandler(BlobTooLargeException::class)
	fun handleTooLarge(ex: BlobTooLargeException): ResponseEntity<ProblemDetail> {
		val status = HttpStatus.CONTENT_TOO_LARGE
		return ResponseEntity
			.status(status)
			.body(ProblemDetail.forStatusAndDetail(status, "The image exceeds the maximum size of ${ex.maxSize} bytes"))
	}

	private fun respond(note: Note): ResponseEntity<NoteResponse> = ETags.ok(NoteResponse.of(note), note.version)

	private companion object {
		const val DEFAULT_IMAGE_NAME = "image"
	}
}

/** `200` with a note and its ETag. */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@ApiResponse(
	responseCode = "200",
	description = "The note with its (new) ETag",
	headers = [Header(name = HttpHeaders.ETAG, description = "Version of the note, for If-Match", schema = Schema(type = "string"))],
	content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = NoteResponse::class))],
)
annotation class NoteOkResponse
