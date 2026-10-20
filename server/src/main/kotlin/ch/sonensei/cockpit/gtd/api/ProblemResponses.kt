package ch.sonensei.cockpit.gtd.api

import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse

/** `400` Problem Details response of the projects, areas and contexts endpoints. */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@ApiResponse(
	responseCode = "400",
	description = "Invalid or unknown fields",
	content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
)
annotation class BadRequestResponse

/** `404` Problem Details response of the projects, areas and contexts endpoints. */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@ApiResponse(
	responseCode = "404",
	description = "Not found (projects: or in the Trash)",
	content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
)
annotation class NotFoundResponse

/** The optimistic locking responses (409, 412, 428) of a change that requires `If-Match`. */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@ApiResponse(
	responseCode = "409",
	description = "Changed concurrently, or (contexts) the name is taken",
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
annotation class IfMatchResponses
