package ch.sonensei.cockpit.journal.entries.api

import ch.sonensei.cockpit.core.web.FieldError
import ch.sonensei.cockpit.core.web.InvalidRequestException
import ch.sonensei.cockpit.journal.entries.domain.JournalEntryContent
import io.swagger.v3.oas.annotations.headers.Header
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import tools.jackson.core.JacksonException
import tools.jackson.databind.json.JsonMapper

internal const val PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON_VALUE

/**
 * Parses a PUT body of a journal entry: omitted or `null` fields get their defaults.
 *
 * @throws InvalidRequestException (400) for unknown fields, values of the wrong type or `null` tags
 */
internal fun JsonMapper.parseJournalEntry(body: Map<String, Any?>): JournalEntryContent {
	val unknown = body.keys - JournalEntryRequest.FIELDS
	if (unknown.isNotEmpty()) throw InvalidRequestException(unknown.sorted().map { FieldError(it, "is not a field of a journal entry") })
	val request =
		try {
			convertValue(body, JournalEntryRequest::class.java)
		} catch (e: JacksonException) {
			val field = e.path.mapNotNull { it.propertyName }.firstOrNull() ?: "body"
			throw InvalidRequestException(field, "has an invalid value")
		}
	val tags = request.tags.orEmpty()
	if (tags.any { it == null }) throw InvalidRequestException("tags", "must not contain null")
	return JournalEntryContent(bodyMd = request.bodyMd.orEmpty(), mood = request.mood, tags = tags.filterNotNull())
}

/** `200` with a journal entry and its ETag. */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@ApiResponse(
	responseCode = "200",
	description = "The entry with its (new) ETag",
	headers = [Header(name = HttpHeaders.ETAG, description = "Version of the entry, for If-Match", schema = Schema(type = "string"))],
	content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = JournalEntryResponse::class))],
)
annotation class JournalEntryOkResponse

/** `400` Problem Details response of the journal endpoints. */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@ApiResponse(
	responseCode = "400",
	description = "Invalid or unknown fields or parameters",
	content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
)
annotation class JournalBadRequestResponse

/** `404` Problem Details response of the journal endpoints. */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@ApiResponse(
	responseCode = "404",
	description = "The date has no entry (or only one in the Trash)",
	content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
)
annotation class JournalNotFoundResponse

/** The optimistic locking responses (409, 412, 428) of a change that requires `If-Match`. */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@ApiResponse(
	responseCode = "409",
	description = "Changed (or created) concurrently",
	content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
)
@ApiResponse(
	responseCode = "412",
	description = "If-Match does not match the current ETag",
	content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
)
@ApiResponse(
	responseCode = "428",
	description = "If-Match is missing",
	content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
)
annotation class JournalIfMatchResponses
