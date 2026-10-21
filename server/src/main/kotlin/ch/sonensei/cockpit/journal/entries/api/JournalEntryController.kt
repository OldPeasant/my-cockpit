package ch.sonensei.cockpit.journal.entries.api

import ch.sonensei.cockpit.core.web.CursorRequest
import ch.sonensei.cockpit.core.web.ETags
import ch.sonensei.cockpit.core.web.PreconditionFailedException
import ch.sonensei.cockpit.files.infra.BlobTooLargeException
import ch.sonensei.cockpit.journal.entries.domain.JournalEntry
import ch.sonensei.cockpit.journal.entries.domain.JournalEntryService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.headers.Header
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.MediaTypeFactory
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.servlet.support.ServletUriComponentsBuilder
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import io.swagger.v3.oas.annotations.parameters.RequestBody as DocumentedBody

/**
 * The journal (SPEC.md §8.5): one entry per date, addressed by its date (`yyyy-MM-dd`), the calendar heatmap, "on this
 * day" and image upload. Responses carry the entry's `ETag`; replacing an existing entry and DELETE need it in
 * `If-Match`, creating the entry of a date needs none.
 */
@RestController
@RequestMapping("/api/v1/journal")
@Tag(name = "Journal", description = "Daily journal entries with mood, tags and images")
class JournalEntryController(
	private val journal: JournalEntryService,
	private val jsonMapper: JsonMapper,
) {
	@GetMapping("/entries", produces = [MediaType.APPLICATION_JSON_VALUE, PROBLEM_JSON])
	@Operation(
		operationId = "listJournalEntries",
		summary = "List journal entries",
		description = "Entries not in the Trash, newest date first.",
	)
	@ApiResponse(responseCode = "200", description = "One page of entries")
	@JournalBadRequestResponse
	fun list(
		@Parameter(description = "First date (included)")
		@RequestParam(required = false)
		@DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
		from: LocalDate?,
		@Parameter(description = "Last date (included)")
		@RequestParam(required = false)
		@DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
		to: LocalDate?,
		@Parameter(description = "Only entries with this tag (regardless of case)") @RequestParam(required = false) tag: String?,
		@Parameter(description = "Cursor from the previous page") @RequestParam(required = false) cursor: String?,
		@Parameter(description = "Page size, 1-200 (default 50)") @RequestParam(required = false) limit: Int?,
	): JournalEntryPageResponse {
		val page = journal.list(from, to, tag?.takeIf { it.isNotBlank() }, CursorRequest.of(cursor, limit))
		return JournalEntryPageResponse(page.items.map(JournalEntryResponse::of), page.nextCursor)
	}

	@GetMapping("/entries/{date}", produces = [MediaType.APPLICATION_JSON_VALUE, PROBLEM_JSON])
	@Operation(operationId = "getJournalEntry", summary = "The entry of a date")
	@JournalEntryOkResponse
	@JournalBadRequestResponse
	@JournalNotFoundResponse
	fun get(
		@Parameter(description = "The date, yyyy-MM-dd") @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDate,
	): ResponseEntity<JournalEntryResponse> = respond(journal.get(date))

	@PutMapping("/entries/{date}", consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE, PROBLEM_JSON])
	@Operation(
		operationId = "putJournalEntry",
		summary = "Create or replace the entry of a date",
		description =
			"Upsert by date: creates the entry (201, no If-Match; an If-Match is answered with 412) or replaces its whole " +
				"content (200, If-Match required). Dates after today (in the user's time zone) are rejected.",
	)
	@DocumentedBody(content = [Content(schema = Schema(implementation = JournalEntryRequest::class))])
	@JournalEntryOkResponse
	@ApiResponse(
		responseCode = "201",
		description = "Created",
		headers = [Header(name = HttpHeaders.ETAG, description = "Version of the entry, for If-Match", schema = Schema(type = "string"))],
		content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = JournalEntryResponse::class))],
	)
	@JournalBadRequestResponse
	@JournalIfMatchResponses
	fun put(
		@Parameter(description = "The date, yyyy-MM-dd") @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDate,
		@Parameter(description = "ETag of the existing entry") @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
		@RequestBody body: Map<String, Any?>,
	): ResponseEntity<JournalEntryResponse> {
		val content = jsonMapper.parseJournalEntry(body)
		val (entry, created) =
			journal.put(date, content) { version ->
				// If-Match never matches a missing entry (RFC 9110 §13.1.1), not even `*`.
				if (version == null) {
					if (!ifMatch.isNullOrBlank()) throw PreconditionFailedException()
				} else {
					ETags.checkIfMatch(ifMatch, version)
				}
			}
		if (!created) return respond(entry)
		val location =
			ServletUriComponentsBuilder
				.fromCurrentRequestUri()
				.build()
				.toUri()
		return ResponseEntity
			.created(location)
			.eTag(ETags.of(entry.version))
			.body(JournalEntryResponse.of(entry))
	}

	@DeleteMapping("/entries/{date}", produces = [PROBLEM_JSON])
	@Operation(operationId = "deleteJournalEntry", summary = "Move the entry of a date to the Trash")
	@ApiResponse(responseCode = "204", description = "In the Trash")
	@JournalBadRequestResponse
	@JournalNotFoundResponse
	@JournalIfMatchResponses
	fun delete(
		@Parameter(description = "The date, yyyy-MM-dd") @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDate,
		@Parameter(description = "ETag of the entry") @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
	): ResponseEntity<Void> {
		journal.delete(date) { ETags.checkIfMatch(ifMatch, it) }
		return ResponseEntity.status(HttpStatus.NO_CONTENT).build()
	}

	@GetMapping("/heatmap", produces = [MediaType.APPLICATION_JSON_VALUE, PROBLEM_JSON])
	@Operation(
		operationId = "getJournalHeatmap",
		summary = "Dates with an entry",
		description =
			"For the calendar heatmap: the dates from `from` to `to` (both included, at most 3660 days) that have an " +
				"entry, with its mood.",
	)
	@ApiResponse(responseCode = "200", description = "The dates")
	@JournalBadRequestResponse
	fun heatmap(
		@Parameter(description = "First date (default: 364 days before `to`)")
		@RequestParam(required = false)
		@DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
		from: LocalDate?,
		@Parameter(description = "Last date (default: today)")
		@RequestParam(required = false)
		@DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
		to: LocalDate?,
	): JournalHeatmapResponse {
		val last = to ?: journal.today()
		val first = from ?: last.minusDays(DEFAULT_HEATMAP_DAYS - 1)
		return JournalHeatmapResponse(first, last, journal.days(first, last).map(JournalDayResponse::of))
	}

	@GetMapping("/on-this-day", produces = [MediaType.APPLICATION_JSON_VALUE, PROBLEM_JSON])
	@Operation(
		operationId = "getJournalOnThisDay",
		summary = "Entries of the same day in earlier years",
		description = "Newest first. On 28 February of a year that is not a leap year, entries of 29 February are included.",
	)
	@ApiResponse(responseCode = "200", description = "The entries")
	@JournalBadRequestResponse
	fun onThisDay(
		@Parameter(description = "The day (default: today)")
		@RequestParam(required = false)
		@DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
		date: LocalDate?,
	): JournalOnThisDayResponse {
		val day = date ?: journal.today()
		return JournalOnThisDayResponse(day, journal.onThisDay(day).map(JournalEntryResponse::of))
	}

	@PostMapping("/images", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE, PROBLEM_JSON])
	@Operation(
		operationId = "uploadJournalImage",
		summary = "Store an image for the journal",
		description =
			"One image in the part `file`, stored in `/Attachments/Journal`. No entry changes: insert the returned " +
				"`markdown` into an entry's body. The mime type is the part's content type (or guessed from the filename).",
	)
	@ApiResponse(
		responseCode = "201",
		description = "Stored",
		content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = JournalImageResponse::class))],
	)
	@ApiResponse(
		responseCode = "413",
		description = "Larger than the configured maximum file size",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
	)
	@JournalBadRequestResponse
	fun uploadImage(
		@RequestPart("file") file: MultipartFile,
	): ResponseEntity<JournalImageResponse> {
		val name = file.originalFilename?.takeIf { it.isNotBlank() } ?: DEFAULT_IMAGE_NAME
		val mime =
			file.contentType?.takeIf { it.isNotBlank() && it != MediaType.APPLICATION_OCTET_STREAM_VALUE }
				?: MediaTypeFactory.getMediaType(name).map { it.toString() }.orElse(MediaType.APPLICATION_OCTET_STREAM_VALUE)
		val stored = file.inputStream.use { journal.addImage(name, mime, it) }
		val location =
			ServletUriComponentsBuilder
				.fromCurrentContextPath()
				.path("/api/v1/files/{id}")
				.buildAndExpand(stored.id)
				.toUri()
		return ResponseEntity.created(location).body(JournalImageResponse.of(stored))
	}

	@ExceptionHandler(BlobTooLargeException::class)
	fun handleTooLarge(ex: BlobTooLargeException): ResponseEntity<ProblemDetail> {
		val status = HttpStatus.CONTENT_TOO_LARGE
		return ResponseEntity
			.status(status)
			.body(ProblemDetail.forStatusAndDetail(status, "The image exceeds the maximum size of ${ex.maxSize} bytes"))
	}

	private fun respond(entry: JournalEntry): ResponseEntity<JournalEntryResponse> = ETags.ok(JournalEntryResponse.of(entry), entry.version)

	private companion object {
		const val DEFAULT_IMAGE_NAME = "image"
		const val DEFAULT_HEATMAP_DAYS = 365L
	}
}
