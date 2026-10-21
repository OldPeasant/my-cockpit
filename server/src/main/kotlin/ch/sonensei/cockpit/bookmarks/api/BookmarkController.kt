package ch.sonensei.cockpit.bookmarks.api

import ch.sonensei.cockpit.bookmarks.domain.Bookmark
import ch.sonensei.cockpit.bookmarks.domain.BookmarkFilter
import ch.sonensei.cockpit.bookmarks.domain.BookmarkService
import ch.sonensei.cockpit.core.web.CursorRequest
import ch.sonensei.cockpit.core.web.ETags
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
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.support.ServletUriComponentsBuilder
import tools.jackson.databind.json.JsonMapper
import java.util.UUID
import io.swagger.v3.oas.annotations.parameters.RequestBody as DocumentedBody

/**
 * Bookmarks and read-later (SPEC.md §8.4): list with filters (the read-later queue is `readLater=true&read=false`),
 * CRUD, tags and fetching the page metadata again. Responses carry the bookmark's `ETag`; PATCH, DELETE and the refresh
 * need it in `If-Match`.
 */
@RestController
@RequestMapping("/api/v1/bookmarks")
@Tag(name = "Bookmarks", description = "Bookmarks and the read-later queue, with title, description and favicon from the page")
class BookmarkController(
	private val bookmarks: BookmarkService,
	private val jsonMapper: JsonMapper,
) {
	@GetMapping(produces = [MediaType.APPLICATION_JSON_VALUE, PROBLEM_JSON])
	@Operation(
		operationId = "listBookmarks",
		summary = "List bookmarks",
		description =
			"Bookmarks matching every given filter, not in the Trash, newest first. The read-later queue is " +
				"`readLater=true&read=false`.",
	)
	@ApiResponse(responseCode = "200", description = "One page of bookmarks")
	@BookmarksBadRequestResponse
	fun list(
		@Parameter(description = "Only bookmarks with this tag (regardless of case)") @RequestParam(required = false) tag: String?,
		@Parameter(description = "true: only bookmarks in the read-later list, false: only those that are not")
		@RequestParam(required = false)
		readLater: Boolean?,
		@Parameter(description = "true: only read bookmarks, false: only unread ones") @RequestParam(required = false) read: Boolean?,
		@Parameter(description = "Cursor from the previous page") @RequestParam(required = false) cursor: String?,
		@Parameter(description = "Page size, 1-200 (default 50)") @RequestParam(required = false) limit: Int?,
	): BookmarkPageResponse {
		val filter = BookmarkFilter(tag?.takeIf { it.isNotBlank() }, readLater, read)
		val page = bookmarks.list(filter, CursorRequest.of(cursor, limit))
		return BookmarkPageResponse(page.items.map(BookmarkResponse::of), page.nextCursor)
	}

	@PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE, PROBLEM_JSON])
	@Operation(
		operationId = "createBookmark",
		summary = "Create a bookmark",
		description =
			"Saves the URL and fetches the page in the background (`metadataStatus` PENDING): its title, description and " +
				"favicon fill the fields left empty.",
	)
	@ApiResponse(
		responseCode = "201",
		description = "Created",
		headers = [Header(name = HttpHeaders.ETAG, description = "Version of the bookmark, for If-Match", schema = Schema(type = "string"))],
		content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = BookmarkResponse::class))],
	)
	@BookmarksBadRequestResponse
	fun create(
		@RequestBody request: CreateBookmarkRequest,
	): ResponseEntity<BookmarkResponse> {
		val bookmark = bookmarks.create(request.toChanges())
		val location =
			ServletUriComponentsBuilder
				.fromCurrentRequestUri()
				.path("/{id}")
				.buildAndExpand(bookmark.id)
				.toUri()
		return ResponseEntity
			.created(location)
			.eTag(ETags.of(bookmark.version))
			.body(BookmarkResponse.of(bookmark))
	}

	@GetMapping("/tags", produces = [MediaType.APPLICATION_JSON_VALUE])
	@Operation(operationId = "listBookmarkTags", summary = "Every bookmark tag in use, with its number of bookmarks")
	@ApiResponse(responseCode = "200", description = "The tags")
	fun tags(): BookmarkTagListResponse = BookmarkTagListResponse(bookmarks.tags().map(BookmarkTagResponse::of))

	@GetMapping("/{id}", produces = [MediaType.APPLICATION_JSON_VALUE, PROBLEM_JSON])
	@Operation(operationId = "getBookmark", summary = "One bookmark")
	@BookmarkOkResponse
	@BookmarksNotFoundResponse
	fun get(
		@PathVariable id: UUID,
	): ResponseEntity<BookmarkResponse> = respond(bookmarks.get(id))

	@PatchMapping("/{id}", consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE, PROBLEM_JSON])
	@Operation(
		operationId = "updateBookmark",
		summary = "Change a bookmark",
		description =
			"Only the fields sent change; `null` clears the title or description. `read` marks it as read or unread. A new " +
				"URL clears the favicon and fetches the page metadata again (filling empty fields).",
	)
	@DocumentedBody(content = [Content(schema = Schema(implementation = BookmarkPatchRequest::class))])
	@BookmarkOkResponse
	@BookmarksBadRequestResponse
	@BookmarksNotFoundResponse
	@BookmarksIfMatchResponses
	fun update(
		@PathVariable id: UUID,
		@Parameter(description = "ETag of the bookmark") @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
		@RequestBody body: Map<String, Any?>,
	): ResponseEntity<BookmarkResponse> {
		val changes = jsonMapper.parseBookmarkPatch(body)
		return respond(bookmarks.update(id, changes) { ETags.checkIfMatch(ifMatch, it) })
	}

	@PostMapping("/{id}/refresh-metadata", produces = [MediaType.APPLICATION_JSON_VALUE, PROBLEM_JSON])
	@Operation(
		operationId = "refreshBookmarkMetadata",
		summary = "Fetch the page metadata again",
		description =
			"Fetches the page in the background (`metadataStatus` PENDING); the fetched title, description and favicon " +
				"replace the current ones.",
	)
	@BookmarkOkResponse
	@BookmarksNotFoundResponse
	@BookmarksIfMatchResponses
	fun refreshMetadata(
		@PathVariable id: UUID,
		@Parameter(description = "ETag of the bookmark") @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
	): ResponseEntity<BookmarkResponse> = respond(bookmarks.refreshMetadata(id) { ETags.checkIfMatch(ifMatch, it) })

	@DeleteMapping("/{id}", produces = [PROBLEM_JSON])
	@Operation(operationId = "deleteBookmark", summary = "Move a bookmark to the Trash")
	@ApiResponse(responseCode = "204", description = "In the Trash")
	@BookmarksNotFoundResponse
	@BookmarksIfMatchResponses
	fun delete(
		@PathVariable id: UUID,
		@Parameter(description = "ETag of the bookmark") @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
	): ResponseEntity<Void> {
		bookmarks.delete(id) { ETags.checkIfMatch(ifMatch, it) }
		return ResponseEntity.status(HttpStatus.NO_CONTENT).build()
	}

	private fun respond(bookmark: Bookmark): ResponseEntity<BookmarkResponse> = ETags.ok(BookmarkResponse.of(bookmark), bookmark.version)
}

/** `200` with a bookmark and its ETag. */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@ApiResponse(
	responseCode = "200",
	description = "The bookmark with its (new) ETag",
	headers = [Header(name = HttpHeaders.ETAG, description = "Version of the bookmark, for If-Match", schema = Schema(type = "string"))],
	content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = BookmarkResponse::class))],
)
annotation class BookmarkOkResponse
