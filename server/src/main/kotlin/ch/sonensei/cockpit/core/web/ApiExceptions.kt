package ch.sonensei.cockpit.core.web

import org.springframework.http.HttpStatus

/**
 * Exceptions that services and controllers throw to produce a specific Problem Details response
 * (SPEC.md §4.2). [ProblemDetailsAdvice] maps them; [message] becomes the `detail`, so it must be
 * safe to show to the client (no internals, no secrets, no user content).
 */
sealed class ApiException(
	val status: HttpStatus,
	message: String,
) : RuntimeException(message)

/** 404: the requested resource does not exist (or is not visible to the caller). */
class NotFoundException(
	message: String = "Resource not found",
) : ApiException(HttpStatus.NOT_FOUND, message)

/** 409: the request conflicts with the current state (e.g. a concurrent update, a duplicate). */
class ConflictException(
	message: String = "The resource was changed concurrently",
) : ApiException(HttpStatus.CONFLICT, message)

/** 412: the `If-Match` precondition does not match the current version. */
class PreconditionFailedException(
	message: String = "If-Match does not match the current version",
) : ApiException(HttpStatus.PRECONDITION_FAILED, message)

/** 428: a conditional request (`If-Match`) is required but was not sent. */
class PreconditionRequiredException(
	message: String = "If-Match header is required",
) : ApiException(HttpStatus.PRECONDITION_REQUIRED, message)

/** 400 with field errors, for validation done in code rather than by Bean Validation. */
class InvalidRequestException(
	val fieldErrors: List<FieldError>,
	message: String = "Request validation failed",
) : ApiException(HttpStatus.BAD_REQUEST, message) {
	constructor(field: String, message: String) : this(listOf(FieldError(field, message)))
}

/** One entry of the `errors` extension of a 400 Problem Details response. */
data class FieldError(
	val field: String,
	val message: String,
)
