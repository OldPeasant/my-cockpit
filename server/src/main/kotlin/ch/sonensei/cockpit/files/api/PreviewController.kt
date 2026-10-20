package ch.sonensei.cockpit.files.api

import ch.sonensei.cockpit.core.web.InvalidRequestException
import ch.sonensei.cockpit.files.domain.Preview
import ch.sonensei.cockpit.files.domain.PreviewNotSupportedException
import ch.sonensei.cockpit.files.domain.PreviewService
import ch.sonensei.cockpit.files.infra.ThumbnailStore
import ch.sonensei.cockpit.files.infra.UndecodableImageException
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
import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Duration
import java.util.UUID

/**
 * Previews of drive files (SPEC.md §8.2): server-side image thumbnails, the start of text files, and PDFs, audio,
 * video and images served inline for the client's own viewer.
 */
@RestController
@RequestMapping("/api/v1/files")
@Tag(name = "Files", description = "The drive: folders, files, uploads and downloads")
class PreviewController(
	private val previews: PreviewService,
) {
	@GetMapping("/{id}/thumbnail", produces = [MediaType.IMAGE_JPEG_VALUE, MediaType.IMAGE_PNG_VALUE, PROBLEM_JSON])
	@Operation(
		operationId = "getFileThumbnail",
		summary = "Thumbnail of an image file",
		description =
			"For JPEG, PNG, WebP and GIF images (the first frame): the image scaled so that its longer side is at most " +
				"`size` pixels (never enlarged), EXIF orientation applied. JPEG for JPEG images, PNG otherwise. " +
				"Thumbnails are cached on the server; the contents of a file never change, so clients may cache them too.",
	)
	@ApiResponse(
		responseCode = "200",
		description = "The thumbnail",
		content = [
			Content(mediaType = MediaType.IMAGE_JPEG_VALUE, schema = Schema(type = "string", format = "binary")),
			Content(mediaType = MediaType.IMAGE_PNG_VALUE, schema = Schema(type = "string", format = "binary")),
		],
	)
	@ApiResponse(
		responseCode = "415",
		description = "Not a JPEG, PNG, WebP or GIF image",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
	)
	@ApiResponse(
		responseCode = "422",
		description = "The contents could not be decoded as an image (or it is too large to thumbnail)",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
	)
	@FilesBadRequestResponse
	@FilesNotFoundResponse
	fun thumbnail(
		@PathVariable id: UUID,
		@Parameter(description = "Longer side in pixels: 256 or 1024", schema = Schema(allowableValues = ["256", "1024"]))
		@RequestParam(defaultValue = "256") size: Int,
	): ResponseEntity<Resource> {
		if (size !in ThumbnailStore.SIZES) throw InvalidRequestException("size", "must be one of ${ThumbnailStore.SIZES.joinToString()}")
		val thumbnail = previews.thumbnail(id, size)
		return ResponseEntity
			.ok()
			.contentType(MediaType.parseMediaType(thumbnail.mime))
			.cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePrivate())
			.body(FileSystemResource(thumbnail.path))
	}

	@GetMapping("/{id}/preview", produces = [MediaType.ALL_VALUE])
	@Operation(
		operationId = "previewFile",
		summary = "Preview of a file",
		description =
			"Text files (`text/*`, JSON, XML, YAML): the first 256 KB decoded as UTF-8 (never cut inside a character; " +
				"invalid bytes become U+FFFD), as `text/markdown` for Markdown and `text/plain` otherwise, with " +
				"`X-Preview-Truncated`. PDFs, audio, video and JPEG/PNG/WebP/GIF images: the contents with their mime type, " +
				"`inline`, with `Range` support, for the client's own viewer.",
	)
	@ApiResponse(
		responseCode = "200",
		description = "The preview",
		headers = [
			Header(
				name = HttpHeaders.CONTENT_DISPOSITION,
				description = "inline; filename and RFC 6266 filename*",
				schema = Schema(type = "string"),
			),
			Header(
				name = TRUNCATED_HEADER,
				description = "Text only: `true` when the file is longer than 256 KB",
				schema = Schema(type = "boolean"),
			),
		],
		content = [Content(mediaType = MediaType.ALL_VALUE, schema = Schema(type = "string", format = "binary"))],
	)
	@ApiResponse(
		responseCode = "206",
		description = "The requested range(s) of an inline preview",
		content = [Content(mediaType = MediaType.ALL_VALUE, schema = Schema(type = "string", format = "binary"))],
	)
	@ApiResponse(
		responseCode = "415",
		description = "No preview for this file type",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
	)
	@FilesNotFoundResponse
	@Parameter(
		name = HttpHeaders.RANGE,
		`in` = ParameterIn.HEADER,
		description = "Byte range(s) of an inline preview, e.g. `bytes=0-1023`",
		schema = Schema(type = "string"),
	)
	fun preview(
		@PathVariable id: UUID,
	): ResponseEntity<*> {
		val preview = previews.preview(id)
		val builder =
			ResponseEntity
				.ok()
				.header(HttpHeaders.CONTENT_DISPOSITION, ContentDispositions.inline(preview.name))
		return when (preview) {
			is Preview.Text ->
				builder
					.contentType(MediaType("text", preview.mime.substringAfter('/'), Charsets.UTF_8))
					.header(TRUNCATED_HEADER, preview.truncated.toString())
					.body(preview.text)
			is Preview.Inline -> builder.contentType(MediaType.parseMediaType(preview.mime)).body(FileSystemResource(preview.path))
		}
	}

	@ExceptionHandler(PreviewNotSupportedException::class)
	fun handleNotSupported(ex: PreviewNotSupportedException): ResponseEntity<ProblemDetail> =
		problem(HttpStatus.UNSUPPORTED_MEDIA_TYPE, ex.message)

	@ExceptionHandler(UndecodableImageException::class)
	fun handleUndecodable(ex: UndecodableImageException): ResponseEntity<ProblemDetail> = problem(HttpStatus.UNPROCESSABLE_CONTENT, ex.message)

	private fun problem(
		status: HttpStatus,
		detail: String?,
	): ResponseEntity<ProblemDetail> =
		ResponseEntity
			.status(status)
			.contentType(MediaType.APPLICATION_PROBLEM_JSON)
			.body(ProblemDetail.forStatusAndDetail(status, detail))

	companion object {
		const val TRUNCATED_HEADER = "X-Preview-Truncated"
	}
}
