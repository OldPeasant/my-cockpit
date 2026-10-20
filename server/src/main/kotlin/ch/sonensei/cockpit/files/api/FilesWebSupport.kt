package ch.sonensei.cockpit.files.api

import ch.sonensei.cockpit.core.web.FieldError
import ch.sonensei.cockpit.core.web.InvalidRequestException
import ch.sonensei.cockpit.files.domain.MoveTarget
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import org.springframework.http.MediaType
import java.net.URLEncoder
import java.util.UUID

internal const val PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON_VALUE

/**
 * `Content-Disposition` values (RFC 6266): an ASCII `filename` fallback (non-ASCII characters, quotes and backslashes
 * replaced by `_`) for old clients, and the exact name as `filename*` (RFC 8187, UTF-8, percent-encoded).
 */
internal object ContentDispositions {
	fun attachment(name: String): String {
		val fallback = name.map { if (it.code in 0x20..0x7e && it != '"' && it != '\\') it else '_' }.joinToString("")
		// URLEncoder does form encoding: spaces become `+` and `*` stays, but neither is an RFC 8187 attr-char.
		val encoded = URLEncoder.encode(name, Charsets.UTF_8).replace("+", "%20").replace("*", "%2A")
		return "attachment; filename=\"$fallback\"; filename*=UTF-8''$encoded"
	}
}

/** A PATCH body of a folder or file: the new [name] and [target] folder, each `null` when it was not sent. */
internal data class FilesPatch(
	val name: String?,
	val target: MoveTarget?,
)

/**
 * Parses a PATCH body with the fields `name` (not `null`) and [parentField] (a folder id, `null`: the root).
 *
 * @throws InvalidRequestException (400) for unknown fields, a `null` or non-string name, or an invalid folder id
 */
internal fun parseFilesPatch(
	body: Map<String, Any?>,
	parentField: String,
): FilesPatch {
	val errors = (body.keys - setOf(NAME, parentField)).sorted().map { FieldError(it, "is not a field that PATCH can change") }.toMutableList()
	val name = body[NAME]
	if (NAME in body && name !is String) errors += FieldError(NAME, "must be a string")
	val parent = body[parentField]
	val parentId =
		when (parent) {
			null -> null
			is String -> runCatching { UUID.fromString(parent) }.getOrNull()
			else -> null
		}
	if (parent != null && parentId == null) errors += FieldError(parentField, "must be a folder id or null")
	if (errors.isNotEmpty()) throw InvalidRequestException(errors)
	return FilesPatch(name as String?, if (parentField in body) MoveTarget(parentId) else null)
}

private const val NAME = "name"

/** `400` Problem Details response of the files endpoints. */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@ApiResponse(
	responseCode = "400",
	description = "Invalid or unknown fields",
	content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
)
annotation class FilesBadRequestResponse

/** `404` Problem Details response of the files endpoints. */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@ApiResponse(
	responseCode = "404",
	description = "Not found or in the Trash (or, when given, the target folder)",
	content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
)
annotation class FilesNotFoundResponse

/** `409` Problem Details response of the files endpoints. */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@ApiResponse(
	responseCode = "409",
	description = "Changed concurrently, the folder name is taken, a special folder, or a move into the folder's own subtree",
	content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
)
annotation class FilesConflictResponse

/** The `If-Match` responses (412, 428) of a change. */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
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
annotation class FilesIfMatchResponses
