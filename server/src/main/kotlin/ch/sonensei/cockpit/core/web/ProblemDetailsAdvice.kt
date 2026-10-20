package ch.sonensei.cockpit.core.web

import jakarta.validation.ConstraintViolationException
import org.slf4j.LoggerFactory
import org.springframework.beans.TypeMismatchException
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.AuthenticationException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingRequestHeaderException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.ServletRequestBindingException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.WebRequest
import org.springframework.web.method.annotation.HandlerMethodValidationException
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.multipart.support.MissingServletRequestPartException
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler

/**
 * Turns every error of the REST API into RFC 9457 Problem Details (SPEC.md §4.2).
 *
 * Spring MVC's own exceptions (unknown route, unreadable body, wrong method, ...) are handled by
 * [ResponseEntityExceptionHandler]. Validation errors add an `errors` list of `{field, message}`.
 * Unexpected exceptions become a generic 500: exception messages and stack traces never reach the
 * client, they are logged instead.
 */
@RestControllerAdvice
class ProblemDetailsAdvice : ResponseEntityExceptionHandler() {
	private val log = LoggerFactory.getLogger(javaClass)

	@ExceptionHandler(ApiException::class)
	fun handleApiException(ex: ApiException): ResponseEntity<ProblemDetail> {
		val problem = ProblemDetail.forStatusAndDetail(ex.status, ex.message)
		if (ex is InvalidRequestException) problem.setProperty(ERRORS, ex.fieldErrors)
		return ResponseEntity.status(ex.status).body(problem)
	}

	@ExceptionHandler(OptimisticLockingFailureException::class)
	fun handleOptimisticLock(ex: OptimisticLockingFailureException): ResponseEntity<ProblemDetail> = handleApiException(ConflictException())

	@ExceptionHandler(ConstraintViolationException::class)
	fun handleConstraintViolation(ex: ConstraintViolationException): ResponseEntity<ProblemDetail> =
		handleApiException(
			InvalidRequestException(
				ex.constraintViolations
					.map { FieldError(it.propertyPath.toString().substringAfterLast('.'), it.message) }
					.sortedBy { it.field },
			),
		)

	@ExceptionHandler(Exception::class)
	fun handleUnexpected(ex: Exception): ResponseEntity<ProblemDetail> {
		// Leave authentication/authorization failures to Spring Security (401/403).
		if (ex is AccessDeniedException || ex is AuthenticationException) throw ex
		log.error("Unhandled exception in REST request", ex)
		val status = HttpStatus.INTERNAL_SERVER_ERROR
		return ResponseEntity.status(status).body(ProblemDetail.forStatusAndDetail(status, "An unexpected error occurred"))
	}

	override fun handleMethodArgumentNotValid(
		ex: MethodArgumentNotValidException,
		headers: HttpHeaders,
		status: HttpStatusCode,
		request: WebRequest,
	): ResponseEntity<Any>? {
		val errors =
			ex.bindingResult.fieldErrors.map { FieldError(it.field, it.defaultMessage ?: "is invalid") } +
				ex.bindingResult.globalErrors.map { FieldError(it.objectName, it.defaultMessage ?: "is invalid") }
		return validationProblem(errors, headers)
	}

	override fun handleHandlerMethodValidationException(
		ex: HandlerMethodValidationException,
		headers: HttpHeaders,
		status: HttpStatusCode,
		request: WebRequest,
	): ResponseEntity<Any>? {
		val errors =
			ex.parameterValidationResults.flatMap { result ->
				val field = result.methodParameter.parameterName ?: "parameter${result.methodParameter.parameterIndex}"
				result.resolvableErrors.map { FieldError(field, it.defaultMessage ?: "is invalid") }
			}
		return validationProblem(errors, headers)
	}

	// Wrongly typed and missing request values are validation errors too: name the parameter or
	// header, but never echo the raw value or conversion internals.
	override fun handleTypeMismatch(
		ex: TypeMismatchException,
		headers: HttpHeaders,
		status: HttpStatusCode,
		request: WebRequest,
	): ResponseEntity<Any>? {
		val field = (ex as? MethodArgumentTypeMismatchException)?.name ?: ex.propertyName ?: "parameter"
		return validationProblem(listOf(FieldError(field, "has an invalid value")), headers)
	}

	override fun handleMissingServletRequestParameter(
		ex: MissingServletRequestParameterException,
		headers: HttpHeaders,
		status: HttpStatusCode,
		request: WebRequest,
	): ResponseEntity<Any>? = validationProblem(listOf(FieldError(ex.parameterName, "is required")), headers)

	override fun handleMissingServletRequestPart(
		ex: MissingServletRequestPartException,
		headers: HttpHeaders,
		status: HttpStatusCode,
		request: WebRequest,
	): ResponseEntity<Any>? = validationProblem(listOf(FieldError(ex.requestPartName, "is required")), headers)

	override fun handleServletRequestBindingException(
		ex: ServletRequestBindingException,
		headers: HttpHeaders,
		status: HttpStatusCode,
		request: WebRequest,
	): ResponseEntity<Any>? =
		if (ex is MissingRequestHeaderException) {
			validationProblem(listOf(FieldError(ex.headerName, "is required")), headers)
		} else {
			super.handleServletRequestBindingException(ex, headers, status, request)
		}

	private fun validationProblem(
		errors: List<FieldError>,
		headers: HttpHeaders,
	): ResponseEntity<Any> {
		val problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Request validation failed")
		problem.setProperty(ERRORS, errors.sortedBy { it.field })
		return ResponseEntity.status(HttpStatus.BAD_REQUEST).headers(headers).body(problem)
	}

	private companion object {
		const val ERRORS = "errors"
	}
}
