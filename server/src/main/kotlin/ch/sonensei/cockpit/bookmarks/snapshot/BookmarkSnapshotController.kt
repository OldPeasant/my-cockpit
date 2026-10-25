package ch.sonensei.cockpit.bookmarks.snapshot

import ch.sonensei.cockpit.bookmarks.api.BookmarkOkResponse
import ch.sonensei.cockpit.bookmarks.api.BookmarkResponse
import ch.sonensei.cockpit.bookmarks.api.BookmarksIfMatchResponses
import ch.sonensei.cockpit.bookmarks.api.BookmarksNotFoundResponse
import ch.sonensei.cockpit.bookmarks.api.PROBLEM_JSON
import ch.sonensei.cockpit.core.web.ETags
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** "Save snapshot" of a bookmark (SPEC.md §8.4): a readable copy of its page as a file ([BookmarkSnapshotService]). */
@RestController
@RequestMapping("/api/v1/bookmarks")
@Tag(name = "Bookmarks")
class BookmarkSnapshotController(
	private val snapshots: BookmarkSnapshotService,
) {
	@PostMapping("/{id}/snapshot", produces = [MediaType.APPLICATION_JSON_VALUE, PROBLEM_JSON])
	@Operation(
		operationId = "saveBookmarkSnapshot",
		summary = "Save a readable copy of the page",
		description =
			"Fetches the page now, extracts its main content and stores it as a sanitized HTML file (no scripts, styles, " +
				"forms or images) in the Attachments folder; `snapshotFileId` points to it. A previous snapshot goes to the Trash.",
	)
	@BookmarkOkResponse
	@BookmarksNotFoundResponse
	@BookmarksIfMatchResponses
	@ApiResponse(
		responseCode = "502",
		description = "The page could not be fetched or has no readable content; `errorCode` says why",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
	)
	fun save(
		@PathVariable id: UUID,
		@Parameter(description = "ETag of the bookmark") @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
	): ResponseEntity<BookmarkResponse> {
		val bookmark = snapshots.save(id) { ETags.checkIfMatch(ifMatch, it) }
		return ETags.ok(BookmarkResponse.of(bookmark), bookmark.version)
	}

	@ExceptionHandler(SnapshotException::class)
	fun handleSnapshotFailure(e: SnapshotException): ResponseEntity<ProblemDetail> {
		val problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, e.message)
		problem.setProperty("errorCode", e.code)
		return ResponseEntity.status(HttpStatus.BAD_GATEWAY).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem)
	}
}
