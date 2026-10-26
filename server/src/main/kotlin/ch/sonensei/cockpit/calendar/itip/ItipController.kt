package ch.sonensei.cockpit.calendar.itip

import ch.sonensei.cockpit.mail.compose.MailSendRejectedException
import ch.sonensei.cockpit.mail.imap.MailConnectionError
import ch.sonensei.cockpit.mail.imap.MailConnectionException
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

private const val PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON_VALUE
private const val PROBLEM = "#/components/schemas/ProblemDetail"

/** The most recipients one request may name. */
const val MAX_ITIP_RECIPIENTS = 200

@Schema(description = "Invitations to send for an event of a local calendar")
data class SendInvitationsRequest(
	@field:NotNull
	@get:Schema(requiredMode = RequiredMode.REQUIRED, description = "REQUEST invites (or tells about a change), CANCEL calls the event off")
	val method: ItipMethod? = null,
	@field:NotNull
	@get:Schema(requiredMode = RequiredMode.REQUIRED, description = "The mail account to send over; its address is the organizer")
	val accountId: UUID? = null,
	@field:Size(min = 1, max = MAX_ITIP_RECIPIENTS)
	@get:ArraySchema(
		arraySchema =
			Schema(
				description =
					"Addresses to send to; default: every attendee but the organizer. A REQUEST only goes to attendees; " +
						"a CANCEL to people no longer invited tells them so without cancelling the event",
			),
		schema = Schema(type = "string", format = "email"),
	)
	val recipients: List<String>? = null,
)

@Schema(description = "What was sent")
data class SendInvitationsResponse(
	@get:Schema(requiredMode = RequiredMode.REQUIRED)
	val method: ItipMethod,
	@get:Schema(requiredMode = RequiredMode.REQUIRED, description = "The addresses the mail went to")
	val recipients: List<String>,
)

/** Sending invitations (iTIP REQUEST/CANCEL over iMIP) for events of local calendars (SPEC.md §6.2). */
@RestController
@RequestMapping("/api/v1/calendar/events")
@Tag(name = "Calendars", description = "Calendars and their events")
class ItipController(
	private val itip: ItipService,
) {
	private val log = LoggerFactory.getLogger(javaClass)

	@PostMapping(
		"/{id}/invitations",
		consumes = [MediaType.APPLICATION_JSON_VALUE],
		produces = [MediaType.APPLICATION_JSON_VALUE, PROBLEM_JSON],
	)
	@Operation(
		operationId = "sendEventInvitations",
		summary = "Send invitations for a local event",
		description =
			"Mails an iTIP REQUEST or CANCEL (RFC 5546/6047) for an event of a local calendar over one of the user's mail " +
				"accounts, from its default identity (else the account address). A series is sent with its overrides, an " +
				"override on its own. A CANCEL also works for an event in the Trash. Nothing in the event changes.",
	)
	@ApiResponse(responseCode = "200", description = "Sent")
	@ApiResponse(
		responseCode = "400",
		description =
			"Invalid request: missing method or account, an unknown mail account, an invalid address, a REQUEST to " +
				"non-attendees, or a CANCEL for an event that goes ahead to both attendees and people no longer invited",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = PROBLEM))],
	)
	@ApiResponse(
		responseCode = "404",
		description = "No such event (or, for a REQUEST, it is in the Trash)",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = PROBLEM))],
	)
	@ApiResponse(
		responseCode = "409",
		description = "The event is not in a local calendar (its provider invites), or there is nobody to send to",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = PROBLEM))],
	)
	@ApiResponse(
		responseCode = "422",
		description = "The SMTP server refused the message (`errorCode` SEND_REJECTED, `rejectedRecipients`); nothing was sent",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = PROBLEM))],
	)
	@ApiResponse(
		responseCode = "502",
		description = "The mail server failed (`errorCode`, e.g. CONNECTION_FAILED, AUTHENTICATION_FAILED)",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = PROBLEM))],
	)
	@ApiResponse(
		responseCode = "503",
		description = "All connections of the account are busy (`errorCode` POOL_EXHAUSTED); retry later",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = PROBLEM))],
	)
	fun send(
		@PathVariable id: UUID,
		@Valid @RequestBody request: SendInvitationsRequest,
	): SendInvitationsResponse {
		val sent = itip.send(id, request.method!!, request.accountId!!, request.recipients)
		return SendInvitationsResponse(sent.method, sent.recipients)
	}

	@ExceptionHandler(MailSendRejectedException::class)
	fun handleSendRejected(e: MailSendRejectedException): ResponseEntity<ProblemDetail> {
		log.info("The SMTP server refused an invitation ({} recipients rejected)", e.rejected.size)
		log.debug("SMTP refusal", e.cause)
		val problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, e.message ?: "")
		problem.setProperty("errorCode", "SEND_REJECTED")
		problem.setProperty("rejectedRecipients", e.rejected)
		return ResponseEntity.unprocessableContent().contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem)
	}

	/** Like the mail module's MailConnectionAdvice, which only covers mail controllers. */
	@ExceptionHandler(MailConnectionException::class)
	fun handleConnectionFailure(e: MailConnectionException): ResponseEntity<ProblemDetail> {
		// The cause may hold server responses; keep it at DEBUG, never with credentials.
		log.info("Sending an invitation failed: {}", e.error)
		log.debug("Mail server failure", e.cause)
		val status = if (e.error == MailConnectionError.POOL_EXHAUSTED) HttpStatus.SERVICE_UNAVAILABLE else HttpStatus.BAD_GATEWAY
		val problem = ProblemDetail.forStatusAndDetail(status, e.error.message)
		problem.setProperty("errorCode", e.error.name)
		return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem)
	}
}
