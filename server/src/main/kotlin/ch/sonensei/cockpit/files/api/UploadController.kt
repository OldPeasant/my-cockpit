package ch.sonensei.cockpit.files.api

import ch.sonensei.cockpit.core.web.ETags
import ch.sonensei.cockpit.files.domain.UploadService
import ch.sonensei.cockpit.files.domain.UploadStatus
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.headers.Header
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.support.ServletUriComponentsBuilder
import java.util.UUID
import io.swagger.v3.oas.annotations.parameters.RequestBody as DocumentedBody

/**
 * Resumable chunked uploads (SPEC.md §8.2) for large files: create the upload, `PUT` its chunks (in any order, again
 * after a failure), check which arrived with `GET`, then `complete` it with the file's SHA-256.
 */
@RestController
@RequestMapping("/api/v1/uploads")
@Tag(name = "Files", description = "The drive: folders, files, uploads and downloads")
class UploadController(
	private val uploads: UploadService,
) {
	@PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE, PROBLEM_JSON])
	@Operation(
		operationId = "createUpload",
		summary = "Start a resumable upload",
		description = "The response tells the chunk size and count. Uploads not completed within 7 days are deleted.",
	)
	@ApiResponse(
		responseCode = "201",
		description = "Created",
		content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = UploadResponse::class))],
	)
	@FilesBadRequestResponse
	@FilesNotFoundResponse
	fun create(
		@Valid @RequestBody request: CreateUploadRequest,
	): ResponseEntity<UploadResponse> {
		val session = uploads.create(request.name!!, request.folderId, request.mime, request.size!!)
		val location =
			ServletUriComponentsBuilder
				.fromCurrentRequestUri()
				.path("/{id}")
				.buildAndExpand(session.id)
				.toUri()
		return ResponseEntity.created(location).body(UploadResponse.of(UploadStatus(session, emptyList())))
	}

	@GetMapping("/{id}", produces = [MediaType.APPLICATION_JSON_VALUE, PROBLEM_JSON])
	@Operation(operationId = "getUpload", summary = "State of an upload", description = "Which chunks arrived, to resume an upload.")
	@ApiResponse(responseCode = "200", description = "The upload")
	@FilesNotFoundResponse
	fun get(
		@PathVariable id: UUID,
	): UploadResponse = UploadResponse.of(uploads.status(id))

	@PutMapping("/{id}/chunks/{n}", consumes = [MediaType.APPLICATION_OCTET_STREAM_VALUE], produces = [PROBLEM_JSON])
	@Operation(
		operationId = "putUploadChunk",
		summary = "Send one chunk",
		description = "Chunk `n` (0-based) with exactly `chunkSize` bytes, the last chunk with the rest. Sending a chunk again replaces it.",
	)
	@DocumentedBody(
		required = true,
		content = [Content(mediaType = MediaType.APPLICATION_OCTET_STREAM_VALUE, schema = Schema(type = "string", format = "binary"))],
	)
	@ApiResponse(responseCode = "204", description = "Stored")
	@FilesBadRequestResponse
	@FilesNotFoundResponse
	fun putChunk(
		@PathVariable id: UUID,
		@PathVariable n: Int,
		request: HttpServletRequest,
	): ResponseEntity<Void> {
		uploads.putChunk(id, n, request.inputStream)
		return ResponseEntity.status(HttpStatus.NO_CONTENT).build()
	}

	@PostMapping("/{id}/complete", consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE, PROBLEM_JSON])
	@Operation(
		operationId = "completeUpload",
		summary = "Complete an upload",
		description =
			"Assembles the chunks into a file, after checking that all arrived (409 lists the missing ones) and that the " +
				"content has the declared size and the given SHA-256 (400 otherwise; the upload stays open). Ends the upload.",
	)
	@ApiResponse(
		responseCode = "201",
		description = "The new file",
		headers = [Header(name = HttpHeaders.ETAG, description = "Version of the file, for If-Match", schema = Schema(type = "string"))],
		content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = FileResponse::class))],
	)
	@FilesBadRequestResponse
	@FilesNotFoundResponse
	@ApiResponse(
		responseCode = "409",
		description = "Chunks are missing",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
	)
	fun complete(
		@PathVariable id: UUID,
		@Valid @RequestBody request: CompleteUploadRequest,
	): ResponseEntity<FileResponse> {
		val file = uploads.complete(id, request.sha256!!)
		val location =
			ServletUriComponentsBuilder
				.fromCurrentContextPath()
				.path("/api/v1/files/{id}")
				.buildAndExpand(file.id)
				.toUri()
		return ResponseEntity.created(location).eTag(ETags.of(file.version)).body(FileResponse.of(file))
	}

	@DeleteMapping("/{id}", produces = [PROBLEM_JSON])
	@Operation(operationId = "abortUpload", summary = "Abort an upload", description = "Deletes the chunks sent so far.")
	@ApiResponse(responseCode = "204", description = "Aborted")
	@FilesNotFoundResponse
	fun abort(
		@PathVariable id: UUID,
	): ResponseEntity<Void> {
		uploads.abort(id)
		return ResponseEntity.status(HttpStatus.NO_CONTENT).build()
	}
}
