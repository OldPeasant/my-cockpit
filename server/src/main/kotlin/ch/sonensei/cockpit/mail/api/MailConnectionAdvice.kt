package ch.sonensei.cockpit.mail.api

import ch.sonensei.cockpit.mail.imap.MailConnectionError
import ch.sonensei.cockpit.mail.imap.MailConnectionException
import org.slf4j.LoggerFactory
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * Turns a failed mail server operation of any mail controller into Problem Details: 503 when the
 * connection pool is exhausted, 502 otherwise, with the [MailConnectionError] as `errorCode`.
 *
 * Ordered before the global advice, whose catch-all would otherwise answer 500.
 */
@RestControllerAdvice(basePackages = ["ch.sonensei.cockpit.mail"])
@Order(Ordered.HIGHEST_PRECEDENCE)
class MailConnectionAdvice {
	private val log = LoggerFactory.getLogger(javaClass)

	@ExceptionHandler(MailConnectionException::class)
	fun handleConnectionFailure(e: MailConnectionException): ResponseEntity<ProblemDetail> {
		// The cause may hold server responses; keep it at DEBUG, never with credentials.
		log.info("Mail server operation failed: {}", e.error)
		log.debug("Mail server failure", e.cause)
		val status = if (e.error == MailConnectionError.POOL_EXHAUSTED) HttpStatus.SERVICE_UNAVAILABLE else HttpStatus.BAD_GATEWAY
		val problem = ProblemDetail.forStatusAndDetail(status, e.error.message)
		problem.setProperty("errorCode", e.error.name)
		return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem)
	}
}
