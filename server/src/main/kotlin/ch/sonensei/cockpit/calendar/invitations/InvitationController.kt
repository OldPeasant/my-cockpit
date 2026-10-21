package ch.sonensei.cockpit.calendar.invitations

import ch.sonensei.cockpit.calendar.api.EventResponse
import ch.sonensei.cockpit.core.web.ETags
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.headers.Header
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotNull
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

private const val PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON_VALUE

@Schema(description = "The answer to an invitation")
data class RespondToInvitationRequest(
	@field:NotNull
	@get:Schema(requiredMode = RequiredMode.REQUIRED)
	val response: InvitationResponse? = null,
)

/** Answering invitations to events of Google and Microsoft calendars (SPEC.md §6.2). */
@RestController
@RequestMapping("/api/v1/calendar/events")
@Tag(name = "Calendars", description = "Calendars and their events")
class InvitationController(
	private val invitations: InvitationService,
) {
	@PostMapping("/{id}/respond", consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE, PROBLEM_JSON])
	@Operation(
		operationId = "respondToInvitation",
		summary = "Answer an invitation",
		description =
			"Accepts, declines or tentatively accepts the invitation to an event of a Google or Microsoft calendar at the " +
				"provider, which tells the organizer. A series is answered for all its occurrences, an override for its " +
				"occurrence. Returns the event as the provider has it now: the user's attendee has the new status.",
	)
	@ApiResponse(
		responseCode = "200",
		description = "Answered; the event with its new ETag",
		headers = [Header(name = HttpHeaders.ETAG, description = "Version of the event, for If-Match", schema = Schema(type = "string"))],
		content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = EventResponse::class))],
	)
	@ApiResponse(
		responseCode = "400",
		description = "Missing or invalid response",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
	)
	@ApiResponse(
		responseCode = "404",
		description = "No such event, or it is in the Trash",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
	)
	@ApiResponse(
		responseCode = "409",
		description =
			"The event is not in a Google or Microsoft calendar, its calendar is read-only or not synced yet, the user is " +
				"not invited to it or organizes it, or it was changed at the provider (the cockpit fetched the new " +
				"version: reload and retry)",
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
	@ApiResponse(
		responseCode = "502",
		description = "The calendar provider failed; nothing was changed",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
	)
	fun respond(
		@PathVariable id: UUID,
		@Parameter(description = "ETag of the event") @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
		@Valid @RequestBody request: RespondToInvitationRequest,
	): ResponseEntity<EventResponse> {
		val event = invitations.respond(id, request.response!!) { ETags.checkIfMatch(ifMatch, it) }
		return ETags.ok(EventResponse.of(event), event.version)
	}
}
