package ch.sonensei.cockpit.gtd.convert.api

import ch.sonensei.cockpit.calendar.api.EventResponse
import ch.sonensei.cockpit.calendar.api.TimingInput
import ch.sonensei.cockpit.core.web.ETags
import ch.sonensei.cockpit.gtd.convert.domain.TaskToEventService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.headers.Header
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.support.ServletUriComponentsBuilder
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

private const val PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON_VALUE

@Schema(
	description =
		"Where and when the event takes place. Timed: `start`, `end` (default `start` + 1 hour) and `timeZone` (default " +
			"the user's); all-day (`allDay: true`): `startDate` and the exclusive `endDate` (default the day after). " +
			"`calendarId` defaults to the default calendar.",
)
data class ConvertToEventRequest(
	val calendarId: UUID? = null,
	@get:Schema(description = "Default false")
	val allDay: Boolean? = null,
	val start: Instant? = null,
	val end: Instant? = null,
	@get:Schema(example = "Europe/Zurich") val timeZone: String? = null,
	val startDate: LocalDate? = null,
	val endDate: LocalDate? = null,
) {
	fun timing() = TimingInput(allDay, start, end, timeZone, startDate, endDate)
}

/** "To calendar" in inbox processing (SPEC.md §5.3): converts a task into a calendar event. */
@RestController
@RequestMapping("/api/v1/tasks")
@Tag(name = "Tasks", description = "GTD tasks")
class TaskToEventController(
	private val converter: TaskToEventService,
) {
	@PostMapping(
		"/{id}/convert-to-event",
		consumes = [MediaType.APPLICATION_JSON_VALUE],
		produces = [MediaType.APPLICATION_JSON_VALUE, PROBLEM_JSON],
	)
	@Operation(
		operationId = "convertTaskToEvent",
		summary = "Move a task to the calendar (convert it into an event)",
		description =
			"Creates an event with the task's title and its notes as description at the given times, links every existing " +
				"item linked to the task to the event (manual links, same direction and label) and moves the task to the " +
				"Trash, in one transaction. `If-Match` is optional.",
	)
	@ApiResponse(
		responseCode = "201",
		description = "The new event; `Location` points to it",
		headers = [Header(name = HttpHeaders.ETAG, description = "Version of the event, for If-Match", schema = Schema(type = "string"))],
		content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = EventResponse::class))],
	)
	@ApiResponse(
		responseCode = "400",
		description = "Invalid or missing times, an unknown calendar or no calendar given and no default calendar",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
	)
	@ApiResponse(
		responseCode = "404",
		description = "No such task, or it is in the Trash",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
	)
	@ApiResponse(
		responseCode = "409",
		description = "Changed concurrently, or the calendar is read-only or cannot be changed",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
	)
	@ApiResponse(
		responseCode = "412",
		description = "If-Match was sent and does not match the task's current ETag",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
	)
	@ApiResponse(
		responseCode = "502",
		description = "The calendar provider of a synced calendar failed; nothing was changed",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
	)
	fun convert(
		@PathVariable id: UUID,
		@RequestBody request: ConvertToEventRequest,
		@Parameter(description = "ETag of the task (optional)") @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
	): ResponseEntity<EventResponse> {
		val event =
			converter.convert(id, request.calendarId, request.timing()) {
				if (!ifMatch.isNullOrBlank()) ETags.checkIfMatch(ifMatch, it)
			}
		val location =
			ServletUriComponentsBuilder
				.fromCurrentContextPath()
				.path("/api/v1/calendar/events/{id}")
				.buildAndExpand(event.id)
				.toUri()
		return ResponseEntity
			.created(location)
			.eTag(ETags.of(event.version))
			.body(EventResponse.of(event))
	}
}
