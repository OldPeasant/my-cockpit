package ch.sonensei.cockpit.calendar.api

import ch.sonensei.cockpit.calendar.model.CalendarEvent
import ch.sonensei.cockpit.core.web.ETags
import ch.sonensei.cockpit.core.web.FieldError
import ch.sonensei.cockpit.core.web.InvalidRequestException
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.headers.Header
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.support.ServletUriComponentsBuilder
import tools.jackson.core.JacksonException
import tools.jackson.databind.json.JsonMapper
import java.time.OffsetDateTime
import java.util.UUID
import io.swagger.v3.oas.annotations.parameters.RequestBody as DocumentedBody

/**
 * Calendar events (SPEC.md §6.4): the expanded occurrences in a range, and CRUD of events of local calendars. For a
 * recurring series, `scope` and `occurrence` say which occurrences a PATCH or DELETE applies to. Responses carry the
 * event's `ETag`; PATCH and DELETE need the addressed event's ETag in `If-Match`.
 */
@RestController
@RequestMapping("/api/v1/calendar/events")
@Tag(name = "Calendars", description = "Calendars and their events")
class CalendarEventController(
	private val events: CalendarEventService,
	private val jsonMapper: JsonMapper,
) {
	@GetMapping(produces = [MediaType.APPLICATION_JSON_VALUE, PROBLEM_JSON])
	@Operation(
		operationId = "listEventOccurrences",
		summary = "Occurrences in a range",
		description =
			"Every occurrence overlapping [from, to) of the events of the given calendars (default: the visible ones), " +
				"series expanded, ordered by start. All-day events count by the days the range touches in the user's " +
				"time zone. The range is at most 400 days.",
	)
	@ApiResponse(responseCode = "200", description = "The occurrences")
	@ApiResponse(
		responseCode = "400",
		description = "Missing or invalid range",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
	)
	fun list(
		@Parameter(description = "Start of the range (inclusive), ISO-8601 date-time")
		@RequestParam
		@DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
		from: OffsetDateTime,
		@Parameter(description = "End of the range (exclusive), ISO-8601 date-time")
		@RequestParam
		@DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
		to: OffsetDateTime,
		@Parameter(description = "Only these calendars (comma-separated or repeated); default the visible calendars")
		@RequestParam(required = false)
		calendarIds: List<UUID>?,
	): OccurrenceListResponse =
		OccurrenceListResponse(events.occurrences(from.toInstant(), to.toInstant(), calendarIds).map(OccurrenceResponse::of))

	@PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE, PROBLEM_JSON])
	@Operation(
		operationId = "createEvent",
		summary = "Create an event",
		description =
			"In a local calendar (default: the default calendar). With a client-generated `id` (UUIDv7) the create is " +
				"idempotent: repeating it returns the existing event (200) unchanged.",
	)
	@ApiResponse(
		responseCode = "201",
		description = "Created",
		headers = [Header(name = HttpHeaders.ETAG, description = "Version of the event, for If-Match", schema = Schema(type = "string"))],
		content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = EventResponse::class))],
	)
	@ApiResponse(
		responseCode = "200",
		description = "An event with this id already exists; it is returned unchanged",
		headers = [Header(name = HttpHeaders.ETAG, description = "Version of the event, for If-Match", schema = Schema(type = "string"))],
		content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = EventResponse::class))],
	)
	@ApiResponse(
		responseCode = "400",
		description = "Invalid fields, an unknown calendar, or no calendar given and no default calendar",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
	)
	@ApiResponse(
		responseCode = "409",
		description = "The id belongs to an event in the Trash, or the calendar is read-only or not local",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
	)
	fun create(
		@Valid @RequestBody request: CreateEventRequest,
	): ResponseEntity<EventResponse> {
		val result =
			try {
				events.create(request.id, request.toChanges())
			} catch (ex: DataIntegrityViolationException) {
				// A concurrent create with the same client id inserted first: answer like the idempotent repeat.
				request.id?.let { events.existingCreate(it) } ?: throw ex
			}
		val event = result.event
		if (!result.created) return respond(event)
		val location =
			ServletUriComponentsBuilder
				.fromCurrentRequestUri()
				.path("/{id}")
				.buildAndExpand(event.id)
				.toUri()
		return ResponseEntity
			.created(location)
			.eTag(ETags.of(event.version))
			.body(EventResponse.of(event))
	}

	@GetMapping("/{id}", produces = [MediaType.APPLICATION_JSON_VALUE, PROBLEM_JSON])
	@Operation(operationId = "getEvent", summary = "One event", description = "An event, series master or override.")
	@ApiResponse(
		responseCode = "200",
		description = "The event",
		headers = [Header(name = HttpHeaders.ETAG, description = "Version of the event, for If-Match", schema = Schema(type = "string"))],
		content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = EventResponse::class))],
	)
	@ApiResponse(
		responseCode = "404",
		description = "No such event, or it is in the Trash",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
	)
	fun get(
		@PathVariable id: UUID,
	): ResponseEntity<EventResponse> = respond(events.get(id))

	@PatchMapping("/{id}", consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE, PROBLEM_JSON])
	@Operation(
		operationId = "updateEvent",
		summary = "Change an event or occurrences of a series",
		description =
			"Only the fields sent change; `null` clears a field. For a series master `scope` defaults to ALL; THIS and " +
				"FOLLOWING need `occurrence`. THIS changes the occurrence's override (created on first change), " +
				"FOLLOWING splits the series and returns the new series, ALL changes the master (overrides that still " +
				"had the old value of a field take the new one). Addressing an override stands for its occurrence " +
				"(`scope` defaults to THIS). A single event ignores `scope`.",
	)
	@DocumentedBody(content = [Content(schema = Schema(implementation = EventPatchRequest::class))])
	@EventChangeResponses
	@ApiResponse(
		responseCode = "200",
		description = "Changed; the event holding the change (event, override or new series) with its ETag",
		headers = [Header(name = HttpHeaders.ETAG, description = "Version of the returned event", schema = Schema(type = "string"))],
		content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = EventResponse::class))],
	)
	fun update(
		@PathVariable id: UUID,
		@Parameter(description = "Which occurrences of a series to change") @RequestParam(required = false) scope: EditScope?,
		@Parameter(description = "Original start of the occurrence: an instant (timed series) or a date (all-day series)")
		@RequestParam(required = false)
		occurrence: String?,
		@Parameter(description = "ETag of the addressed event") @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
		@RequestBody body: Map<String, Any?>,
	): ResponseEntity<EventResponse> {
		val changes = parsePatch(body)
		return respond(events.update(id, scope, occurrence, changes) { ETags.checkIfMatch(ifMatch, it) })
	}

	@DeleteMapping("/{id}", produces = [PROBLEM_JSON])
	@Operation(
		operationId = "deleteEvent",
		summary = "Delete an event or occurrences of a series",
		description =
			"A single event or a whole series (ALL) goes to the Trash; THIS removes one occurrence (EXDATE, or its " +
				"override is cancelled); FOLLOWING ends the series before the occurrence. Scope defaults as for PATCH.",
	)
	@EventChangeResponses
	@ApiResponse(responseCode = "204", description = "Deleted")
	fun delete(
		@PathVariable id: UUID,
		@Parameter(description = "Which occurrences of a series to delete") @RequestParam(required = false) scope: EditScope?,
		@Parameter(description = "Original start of the occurrence: an instant (timed series) or a date (all-day series)")
		@RequestParam(required = false)
		occurrence: String?,
		@Parameter(description = "ETag of the addressed event") @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
	): ResponseEntity<Void> {
		events.delete(id, scope, occurrence) { ETags.checkIfMatch(ifMatch, it) }
		return ResponseEntity.status(HttpStatus.NO_CONTENT).build()
	}

	private fun respond(event: CalendarEvent): ResponseEntity<EventResponse> = ETags.ok(EventResponse.of(event), event.version)

	/**
	 * The PATCH body as [EventChanges]: [EventPatchRequest] converts the values, the JSON object tells which fields were
	 * sent (absent: unchanged, `null`: cleared).
	 */
	private fun parsePatch(body: Map<String, Any?>): EventChanges {
		val sent = body.keys
		val unknown = sent - PATCH_FIELDS
		if (unknown.isNotEmpty()) {
			throw InvalidRequestException(unknown.sorted().map { FieldError(it, "is not a field that PATCH can change") })
		}
		val request =
			try {
				jsonMapper.convertValue(body, EventPatchRequest::class.java)
			} catch (e: JacksonException) {
				val field = e.path.mapNotNull { it.propertyName }.firstOrNull() ?: "body"
				throw InvalidRequestException(field, "has an invalid value")
			}
		val notNullable = (PATCH_FIELDS - NULLABLE_FIELDS).filter { it in sent && body[it] == null }
		if (notNullable.isNotEmpty()) throw InvalidRequestException(notNullable.sorted().map { FieldError(it, "must not be null") })

		fun <T> change(
			name: String,
			value: T,
		): FieldChange<T>? = if (name in sent) FieldChange(value) else null

		return with(request) {
			EventChanges(
				calendarId = change("calendarId", calendarId)?.let { FieldChange(it.value!!) },
				title = change("title", title)?.let { FieldChange(it.value!!) },
				description = change("description", description),
				location = change("location", location),
				timing = TimingInput(allDay, start, end, timeZone, startDate, endDate).takeIf { it.isSent },
				rrule = change("rrule", rrule),
				attendees = change("attendees", attendees)?.let { FieldChange(it.value!!.map(AttendeeDto::toModel)) },
				reminderMinutes = change("reminderMinutes", reminderMinutes)?.let { FieldChange(it.value!!) },
				status = change("status", status)?.let { FieldChange(it.value!!) },
				transparency = change("transparency", transparency)?.let { FieldChange(it.value!!) },
				conferenceUrl = change("conferenceUrl", conferenceUrl),
			)
		}
	}

	private companion object {
		val PATCH_FIELDS =
			setOf(
				"calendarId",
				"title",
				"description",
				"location",
				"allDay",
				"start",
				"end",
				"timeZone",
				"startDate",
				"endDate",
				"rrule",
				"attendees",
				"reminderMinutes",
				"status",
				"transparency",
				"conferenceUrl",
			)
		val NULLABLE_FIELDS = setOf("description", "location", "rrule", "conferenceUrl")
	}
}

/** The error responses of PATCH and DELETE of an event. */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@ApiResponse(
	responseCode = "400",
	description = "Invalid or unknown fields, or a missing, malformed or mismatching occurrence",
	content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
)
@ApiResponse(
	responseCode = "404",
	description = "No such event or occurrence, or the event is in the Trash",
	content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
)
@ApiResponse(
	responseCode = "409",
	description = "Changed concurrently, or the calendar is read-only or not local",
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
annotation class EventChangeResponses
