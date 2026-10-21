package ch.sonensei.cockpit.notes.api

import ch.sonensei.cockpit.core.web.FieldError
import ch.sonensei.cockpit.core.web.InvalidRequestException
import ch.sonensei.cockpit.notes.domain.FieldChange
import ch.sonensei.cockpit.notes.domain.NoteChanges
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import org.springframework.http.MediaType
import tools.jackson.core.JacksonException
import tools.jackson.databind.json.JsonMapper

internal const val PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON_VALUE

private val PATCH_FIELDS = setOf("title", "bodyMd", "tags", "pinned", "archived")
private val NOT_NULLABLE = setOf("bodyMd", "tags", "pinned", "archived")

/**
 * Parses a PATCH body of a note: fields not sent stay unchanged, `null` clears the title.
 *
 * @throws InvalidRequestException (400) for unknown fields, values of the wrong type, or `null` for a field other than
 *   `title`
 */
internal fun JsonMapper.parseNotePatch(body: Map<String, Any?>): NoteChanges {
	val errors = (body.keys - PATCH_FIELDS).sorted().map { FieldError(it, "is not a field that PATCH can change") }.toMutableList()
	errors += NOT_NULLABLE.filter { it in body && body[it] == null }.sorted().map { FieldError(it, "must not be null") }
	if (errors.isNotEmpty()) throw InvalidRequestException(errors)
	val request =
		try {
			convertValue(body, NotePatchRequest::class.java)
		} catch (e: JacksonException) {
			val field = e.path.mapNotNull { it.propertyName }.firstOrNull() ?: "body"
			throw InvalidRequestException(field, "has an invalid value")
		}
	return with(request) {
		NoteChanges(
			title = if ("title" in body) FieldChange(title) else null,
			bodyMd = bodyMd?.let(::FieldChange),
			tags = tags?.let { FieldChange(it.withoutNulls()) },
			pinned = pinned?.let(::FieldChange),
			archived = archived?.let(::FieldChange),
		)
	}
}

/**
 * The tags of a request, which JSON allows to contain `null` elements.
 *
 * @throws InvalidRequestException (400) when one of them is `null`
 */
internal fun List<String?>.withoutNulls(): List<String> =
	if (any { it == null }) throw InvalidRequestException("tags", "must not contain null") else filterNotNull()

/** `400` Problem Details response of the notes endpoints. */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@ApiResponse(
	responseCode = "400",
	description = "Invalid or unknown fields",
	content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
)
annotation class NotesBadRequestResponse

/** `404` Problem Details response of the notes endpoints. */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@ApiResponse(
	responseCode = "404",
	description = "Not found or in the Trash",
	content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
)
annotation class NotesNotFoundResponse

/** The optimistic locking responses (409, 412, 428) of a change that requires `If-Match`. */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@ApiResponse(
	responseCode = "409",
	description = "Changed concurrently",
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
annotation class NotesIfMatchResponses
