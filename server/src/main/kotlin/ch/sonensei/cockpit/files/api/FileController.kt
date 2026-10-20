package ch.sonensei.cockpit.files.api

import ch.sonensei.cockpit.core.web.ETags
import ch.sonensei.cockpit.core.web.InvalidRequestException
import ch.sonensei.cockpit.files.domain.FileItem
import ch.sonensei.cockpit.files.domain.FilesService
import ch.sonensei.cockpit.files.domain.UploadLimits
import ch.sonensei.cockpit.files.infra.BlobStore
import ch.sonensei.cockpit.files.infra.BlobTooLargeException
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.headers.Header
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.core.io.FileSystemResource
import org.springframework.core.io.Resource
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.InvalidMediaTypeException
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
import java.util.UUID
import io.swagger.v3.oas.annotations.parameters.RequestBody as DocumentedBody

/**
 * Files of the drive (SPEC.md §8.2): multipart upload (files above 50 MB should use the resumable `/uploads`),
 * download with `Range` support, rename/move and delete (to the Trash). Metadata responses carry the file's `ETag`;
 * PATCH and DELETE need it in `If-Match`.
 */
@RestController
@RequestMapping("/api/v1/files")
@Tag(name = "Files", description = "The drive: folders, files, uploads and downloads")
class FileController(
	private val files: FilesService,
	private val blobs: BlobStore,
	private val limits: UploadLimits,
) {
	@PostMapping(consumes = [MediaType.MULTIPART_FORM_DATA_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE, PROBLEM_JSON])
	@Operation(
		operationId = "uploadFile",
		summary = "Upload a file (multipart)",
		description =
			"One file in the part `file`. The name is `name` or else the part's filename, the mime type the part's " +
				"content type (or guessed from the name). Use the resumable `/uploads` for files above 50 MB.",
	)
	@ApiResponse(
		responseCode = "201",
		description = "Stored",
		headers = [Header(name = HttpHeaders.ETAG, description = "Version of the file, for If-Match", schema = Schema(type = "string"))],
		content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = FileResponse::class))],
	)
	@ApiResponse(
		responseCode = "413",
		description = "Larger than the configured maximum file size",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
	)
	@FilesBadRequestResponse
	@FilesNotFoundResponse
	fun upload(
		@RequestPart("file") file: MultipartFile,
		@Parameter(description = "Target folder; omitted: the drive root") @RequestParam(required = false) folderId: UUID?,
		@Parameter(description = "Name of the file; default: the part's filename") @RequestParam(required = false) name: String?,
	): ResponseEntity<FileResponse> {
		val fileName = name ?: file.originalFilename ?: throw InvalidRequestException("name", "is required when the part has no filename")
		val mime =
			file.contentType?.takeIf { it.isNotBlank() && it != MediaType.APPLICATION_OCTET_STREAM_VALUE }
				?: MediaTypeFactory.getMediaType(fileName).map { it.toString() }.orElse(FilesService.DEFAULT_MIME)
		val stored = file.inputStream.use { files.storeFile(folderId, fileName, mime, it, limits.maxSize) }
		val location =
			ServletUriComponentsBuilder
				.fromCurrentRequestUri()
				.path("/{id}")
				.buildAndExpand(stored.id)
				.toUri()
		return ResponseEntity.created(location).eTag(ETags.of(stored.version)).body(FileResponse.of(stored))
	}

	@GetMapping("/{id}", produces = [MediaType.APPLICATION_JSON_VALUE, PROBLEM_JSON])
	@Operation(operationId = "getFile", summary = "Metadata of one file")
	@FileOkResponse
	@FilesNotFoundResponse
	fun get(
		@PathVariable id: UUID,
	): ResponseEntity<FileResponse> = respond(files.file(id))

	@GetMapping("/{id}/content", produces = [MediaType.ALL_VALUE])
	@Operation(
		operationId = "downloadFile",
		summary = "Download a file",
		description =
			"The contents with the file's mime type, as an attachment with its name. Supports `Range` " +
				"(`206 Partial Content`, `416` for an unsatisfiable range).",
	)
	@ApiResponse(
		responseCode = "200",
		description = "The contents",
		headers = [
			Header(
				name = HttpHeaders.CONTENT_DISPOSITION,
				description = "attachment; filename and RFC 6266 filename*",
				schema = Schema(type = "string"),
			),
			Header(name = HttpHeaders.ACCEPT_RANGES, description = "bytes", schema = Schema(type = "string")),
		],
		content = [Content(mediaType = MediaType.ALL_VALUE, schema = Schema(type = "string", format = "binary"))],
	)
	@ApiResponse(
		responseCode = "206",
		description = "The requested range(s)",
		content = [Content(mediaType = MediaType.ALL_VALUE, schema = Schema(type = "string", format = "binary"))],
	)
	@ApiResponse(responseCode = "416", description = "The range is not satisfiable")
	@FilesNotFoundResponse
	@Parameter(name = HttpHeaders.RANGE, `in` = ParameterIn.HEADER, description = "Byte range(s), e.g. `bytes=0-1023`")
	fun download(
		@PathVariable id: UUID,
	): ResponseEntity<Resource> {
		val file = files.file(id)
		return ResponseEntity
			.ok()
			.contentType(mediaType(file))
			.header(HttpHeaders.CONTENT_DISPOSITION, ContentDispositions.attachment(file.name))
			.body(FileSystemResource(blobs.path(file.storageKey)))
	}

	@PatchMapping("/{id}", consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE, PROBLEM_JSON])
	@Operation(
		operationId = "updateFile",
		summary = "Rename or move a file",
		description = "`name` renames, `folderId` moves the file (`null`: to the drive root). File names need not be unique.",
	)
	@DocumentedBody(content = [Content(schema = Schema(implementation = FilePatchRequest::class))])
	@FileOkResponse
	@FilesBadRequestResponse
	@FilesNotFoundResponse
	@FilesConflictResponse
	@FilesIfMatchResponses
	fun update(
		@PathVariable id: UUID,
		@Parameter(description = "ETag of the file") @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
		@RequestBody body: Map<String, Any?>,
	): ResponseEntity<FileResponse> {
		val patch = parseFilesPatch(body, "folderId")
		return respond(files.updateFile(id, patch.name, patch.target) { ETags.checkIfMatch(ifMatch, it) })
	}

	@DeleteMapping("/{id}", produces = [PROBLEM_JSON])
	@Operation(operationId = "deleteFile", summary = "Move a file to the Trash")
	@ApiResponse(responseCode = "204", description = "In the Trash")
	@FilesNotFoundResponse
	@FilesConflictResponse
	@FilesIfMatchResponses
	fun delete(
		@PathVariable id: UUID,
		@Parameter(description = "ETag of the file") @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
	): ResponseEntity<Void> {
		files.deleteFile(id) { ETags.checkIfMatch(ifMatch, it) }
		return ResponseEntity.status(HttpStatus.NO_CONTENT).build()
	}

	@ExceptionHandler(BlobTooLargeException::class)
	fun handleTooLarge(ex: BlobTooLargeException): ResponseEntity<ProblemDetail> {
		val status = HttpStatus.CONTENT_TOO_LARGE
		return ResponseEntity
			.status(
				status,
			).body(ProblemDetail.forStatusAndDetail(status, "The file exceeds the maximum size of ${ex.maxSize} bytes"))
	}

	private fun respond(file: FileItem): ResponseEntity<FileResponse> = ETags.ok(FileResponse.of(file), file.version)

	private fun mediaType(file: FileItem): MediaType =
		try {
			MediaType.parseMediaType(file.mime)
		} catch (_: InvalidMediaTypeException) {
			MediaType.APPLICATION_OCTET_STREAM
		}
}

/** `200` with a file's metadata and its ETag. */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@ApiResponse(
	responseCode = "200",
	description = "The file with its (new) ETag",
	headers = [Header(name = HttpHeaders.ETAG, description = "Version of the file, for If-Match", schema = Schema(type = "string"))],
	content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = FileResponse::class))],
)
annotation class FileOkResponse
