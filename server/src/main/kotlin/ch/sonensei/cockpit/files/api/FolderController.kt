package ch.sonensei.cockpit.files.api

import ch.sonensei.cockpit.core.web.ETags
import ch.sonensei.cockpit.files.domain.DriveService
import ch.sonensei.cockpit.files.domain.FilesService
import ch.sonensei.cockpit.files.domain.Folder
import ch.sonensei.cockpit.files.infra.BlobStore
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
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody
import org.springframework.web.servlet.support.ServletUriComponentsBuilder
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import io.swagger.v3.oas.annotations.parameters.RequestBody as DocumentedBody

/**
 * Folders of the drive (SPEC.md §8.2): create, rename/move, delete (to the Trash) and download as a zip. Responses
 * carry the folder's `ETag`; PATCH and DELETE need it in `If-Match`.
 */
@RestController
@RequestMapping("/api/v1/folders")
@Tag(name = "Files", description = "The drive: folders, files, uploads and downloads")
class FolderController(
	private val files: FilesService,
	private val drive: DriveService,
	private val blobs: BlobStore,
) {
	@PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE, PROBLEM_JSON])
	@Operation(operationId = "createFolder", summary = "Create a folder")
	@ApiResponse(
		responseCode = "201",
		description = "Created",
		headers = [Header(name = HttpHeaders.ETAG, description = "Version of the folder, for If-Match", schema = Schema(type = "string"))],
		content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = FolderResponse::class))],
	)
	@FilesBadRequestResponse
	@FilesNotFoundResponse
	@FilesConflictResponse
	fun create(
		@Valid @RequestBody request: CreateFolderRequest,
	): ResponseEntity<FolderResponse> {
		val folder = files.createFolder(request.name!!, request.parentId)
		val location =
			ServletUriComponentsBuilder
				.fromCurrentRequestUri()
				.path("/{id}")
				.buildAndExpand(folder.id)
				.toUri()
		return ResponseEntity.created(location).eTag(ETags.of(folder.version)).body(response(folder))
	}

	@GetMapping("/{id}", produces = [MediaType.APPLICATION_JSON_VALUE, PROBLEM_JSON])
	@Operation(operationId = "getFolder", summary = "One folder", description = "List its contents with `GET /drive?folderId=`.")
	@FolderOkResponse
	@FilesNotFoundResponse
	fun get(
		@PathVariable id: UUID,
	): ResponseEntity<FolderResponse> = respond(files.folder(id))

	@PatchMapping("/{id}", consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE, PROBLEM_JSON])
	@Operation(
		operationId = "updateFolder",
		summary = "Rename or move a folder",
		description =
			"`name` renames, `parentId` moves the folder with everything below it (`null`: to the drive root). A folder " +
				"cannot move into itself or below itself; special folders cannot be renamed or moved.",
	)
	@DocumentedBody(content = [Content(schema = Schema(implementation = FolderPatchRequest::class))])
	@FolderOkResponse
	@FilesBadRequestResponse
	@FilesNotFoundResponse
	@FilesConflictResponse
	@FilesIfMatchResponses
	fun update(
		@PathVariable id: UUID,
		@Parameter(description = "ETag of the folder") @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
		@RequestBody body: Map<String, Any?>,
	): ResponseEntity<FolderResponse> {
		val patch = parseFilesPatch(body, "parentId")
		return respond(files.updateFolder(id, patch.name, patch.target) { ETags.checkIfMatch(ifMatch, it) })
	}

	@DeleteMapping("/{id}", produces = [PROBLEM_JSON])
	@Operation(operationId = "deleteFolder", summary = "Move a folder with its contents to the Trash")
	@ApiResponse(responseCode = "204", description = "In the Trash")
	@FilesNotFoundResponse
	@FilesConflictResponse
	@FilesIfMatchResponses
	fun delete(
		@PathVariable id: UUID,
		@Parameter(description = "ETag of the folder") @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
	): ResponseEntity<Void> {
		files.deleteFolder(id) { ETags.checkIfMatch(ifMatch, it) }
		return ResponseEntity.status(HttpStatus.NO_CONTENT).build()
	}

	@GetMapping("/{id}/zip", produces = [ZIP, PROBLEM_JSON])
	@Operation(
		operationId = "downloadFolder",
		summary = "Download a folder as a zip",
		description =
			"Streams a zip of the folder and everything below it that is not in the Trash, below a top directory named " +
				"like the folder. Files with the same name in one folder get a number (`report (2).pdf`).",
	)
	@ApiResponse(
		responseCode = "200",
		description = "The zip",
		headers = [
			Header(
				name = HttpHeaders.CONTENT_DISPOSITION,
				description = "attachment with the folder name",
				schema = Schema(type = "string"),
			),
		],
		content = [Content(mediaType = ZIP, schema = Schema(type = "string", format = "binary"))],
	)
	@FilesNotFoundResponse
	fun zip(
		@PathVariable id: UUID,
	): ResponseEntity<StreamingResponseBody> {
		val folder = files.folder(id)
		// Collected in a short read transaction up front; the blobs are streamed afterwards without one.
		val entries = drive.archiveEntries(id)
		val body =
			StreamingResponseBody { out ->
				ZipOutputStream(out).use { zip ->
					entries.forEach { entry ->
						zip.putNextEntry(ZipEntry(entry.path))
						entry.storageKey?.let { key -> blobs.open(key).use { it.transferTo(zip) } }
						zip.closeEntry()
					}
				}
			}
		return ResponseEntity
			.ok()
			.contentType(MediaType.parseMediaType(ZIP))
			.header(HttpHeaders.CONTENT_DISPOSITION, ContentDispositions.attachment("${folder.name}.zip"))
			.body(body)
	}

	private fun response(folder: Folder) = FolderResponse.of(folder, files.pathOf(folder.id))

	private fun respond(folder: Folder): ResponseEntity<FolderResponse> = ETags.ok(response(folder), folder.version)

	private companion object {
		const val ZIP = "application/zip"
	}
}

/** `200` with a folder and its ETag. */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@ApiResponse(
	responseCode = "200",
	description = "The folder with its (new) ETag",
	headers = [Header(name = HttpHeaders.ETAG, description = "Version of the folder, for If-Match", schema = Schema(type = "string"))],
	content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = FolderResponse::class))],
)
annotation class FolderOkResponse
