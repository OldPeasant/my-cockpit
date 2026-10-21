package ch.sonensei.cockpit.mail.gtd

import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.mail.imap.MailConnectionError
import ch.sonensei.cockpit.mail.imap.MailConnectionException
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.headers.Header
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.support.ServletUriComponentsBuilder
import java.util.UUID

/** Mail → GTD (SPEC.md §7.3): "→ Inbox task", "→ Waiting For" and "Create note", each linked to the mail. */
@RestController
@RequestMapping("/api/v1/mail/messages/{id}")
@Tag(name = "Mail messages")
class MailGtdController(
	private val service: MailGtdService,
) {
	private val log = LoggerFactory.getLogger(javaClass)

	@PostMapping("/to-task", produces = [MediaType.APPLICATION_JSON_VALUE, PROBLEM_JSON])
	@Operation(
		operationId = "mailToTask",
		summary = "Create an Inbox task from a message",
		description =
			"The task is titled with the subject and linked to the message (a manual link, shown on both). A message " +
				"that moved resolves to its current copy, which the link points at; the link keeps working after later moves.",
	)
	@CreatedResponses
	fun toTask(
		@PathVariable id: UUID,
	): ResponseEntity<MailGtdItemResponse> = created(service.toTask(id), "/api/v1/tasks")

	@PostMapping("/to-waiting", produces = [MediaType.APPLICATION_JSON_VALUE, PROBLEM_JSON])
	@Operation(
		operationId = "mailToWaiting",
		summary = "Create a Waiting For task from a message",
		description =
			"The task is titled with the subject, waits since the mail's date and is linked to the message. For a " +
				"sent mail (in the Sent folder, or from the account's address or one of its identities) `waitingForText` " +
				"names the recipients (To, else Cc, else Bcc); for a received mail its sender.",
	)
	@CreatedResponses
	fun toWaiting(
		@PathVariable id: UUID,
	): ResponseEntity<MailGtdItemResponse> = created(service.toWaiting(id), "/api/v1/tasks")

	@PostMapping("/to-note", produces = [MediaType.APPLICATION_JSON_VALUE, PROBLEM_JSON])
	@Operation(
		operationId = "mailToNote",
		summary = "Create a note from a message",
		description =
			"The note is titled with the subject; its body lists sender, recipients and date, then the mail's plain " +
				"text (the HTML part as text when there is none), and it is linked to the message. Loads the body from " +
				"the mail server when it is not cached.",
	)
	@CreatedResponses
	@ApiResponse(
		responseCode = "502",
		description = "The mail server failed",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = PROBLEM))],
	)
	@ApiResponse(
		responseCode = "503",
		description = "All connections of the account are busy (`errorCode` POOL_EXHAUSTED); retry later",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = PROBLEM))],
	)
	fun toNote(
		@PathVariable id: UUID,
	): ResponseEntity<MailGtdItemResponse> = created(service.toNote(id), "/api/v1/notes")

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

	private fun created(
		item: MailGtdItem,
		collection: String,
	): ResponseEntity<MailGtdItemResponse> {
		val location =
			ServletUriComponentsBuilder
				.fromCurrentContextPath()
				.path("$collection/{id}")
				.buildAndExpand(item.item.id)
				.toUri()
		return ResponseEntity
			.created(location)
			.body(MailGtdItemResponse(item.item.type, item.item.id, item.title, item.messageId, item.linkId))
	}

	@Target(AnnotationTarget.FUNCTION)
	@Retention(AnnotationRetention.RUNTIME)
	@ApiResponse(
		responseCode = "201",
		description = "Created and linked; `Location` is the new item",
		headers = [Header(name = HttpHeaders.LOCATION, schema = Schema(type = "string"))],
		content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = MailGtdItemResponse::class))],
	)
	@ApiResponse(
		responseCode = "400",
		description = "Malformed id",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = PROBLEM))],
	)
	@ApiResponse(
		responseCode = "404",
		description = "No such message, or it is no longer on the server",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = PROBLEM))],
	)
	private annotation class CreatedResponses

	private companion object {
		const val PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON_VALUE
		const val PROBLEM = "#/components/schemas/ProblemDetail"
	}
}

data class MailGtdItemResponse(
	@field:Schema(requiredMode = RequiredMode.REQUIRED, description = "TASK or NOTE")
	val type: ItemType,
	@field:Schema(requiredMode = RequiredMode.REQUIRED, description = "Id of the new task or note")
	val id: UUID,
	@field:Schema(requiredMode = RequiredMode.REQUIRED)
	val title: String,
	@field:Schema(requiredMode = RequiredMode.REQUIRED, description = "The message linked (its current copy)")
	val messageId: UUID,
	@field:Schema(requiredMode = RequiredMode.REQUIRED, description = "The link between the item and the message")
	val linkId: UUID,
)
