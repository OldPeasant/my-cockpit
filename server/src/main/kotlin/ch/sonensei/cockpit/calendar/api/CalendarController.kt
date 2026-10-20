package ch.sonensei.cockpit.calendar.api

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
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.support.ServletUriComponentsBuilder
import tools.jackson.core.JacksonException
import tools.jackson.databind.json.JsonMapper
import java.util.UUID
import io.swagger.v3.oas.annotations.parameters.RequestBody as DocumentedBody

/**
 * Calendars (SPEC.md §6.4): list, create (local calendars) and change. Responses carry the calendar's `ETag`; PATCH
 * needs it in `If-Match`.
 */
@RestController
@RequestMapping("/api/v1/calendar/calendars")
@Tag(name = "Calendars", description = "Calendars and their events")
class CalendarController(
	private val calendars: CalendarService,
	private val jsonMapper: JsonMapper,
) {
	@GetMapping(produces = [MediaType.APPLICATION_JSON_VALUE])
	@Operation(operationId = "listCalendars", summary = "List calendars", description = "Every calendar of every source, ordered by name.")
	@ApiResponse(responseCode = "200", description = "The calendars")
	fun list(): CalendarListResponse = CalendarListResponse(calendars.list().map(::response))

	@PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE, PROBLEM_JSON])
	@Operation(
		operationId = "createCalendar",
		summary = "Create a local calendar",
		description = "The calendar is stored in the cockpit. The first calendar becomes the default for new events.",
	)
	@ApiResponse(
		responseCode = "201",
		description = "Created",
		headers = [Header(name = HttpHeaders.ETAG, description = "Version of the calendar, for If-Match", schema = Schema(type = "string"))],
		content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = CalendarResponse::class))],
	)
	@ApiResponse(
		responseCode = "400",
		description = "Invalid fields",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
	)
	fun create(
		@Valid @RequestBody request: CreateCalendarRequest,
	): ResponseEntity<CalendarResponse> {
		val created = calendars.create(request.name!!, request.color, request.visible ?: true, request.isDefault ?: false)
		val location =
			ServletUriComponentsBuilder
				.fromCurrentRequestUri()
				.path("/{id}")
				.buildAndExpand(created.calendar.id)
				.toUri()
		return ResponseEntity
			.created(location)
			.eTag(ETags.of(created.calendar.version))
			.body(response(created))
	}

	@GetMapping("/{id}", produces = [MediaType.APPLICATION_JSON_VALUE, PROBLEM_JSON])
	@Operation(operationId = "getCalendar", summary = "One calendar")
	@ApiResponse(
		responseCode = "200",
		description = "The calendar",
		headers = [Header(name = HttpHeaders.ETAG, description = "Version of the calendar, for If-Match", schema = Schema(type = "string"))],
		content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = CalendarResponse::class))],
	)
	@ApiResponse(
		responseCode = "404",
		description = "No such calendar",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
	)
	fun get(
		@PathVariable id: UUID,
	): ResponseEntity<CalendarResponse> = respond(calendars.get(id))

	@PatchMapping("/{id}", consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE, PROBLEM_JSON])
	@Operation(
		operationId = "updateCalendar",
		summary = "Change a calendar",
		description = "Only the fields sent change. Also allowed for calendars of external sources (e.g. color, visibility).",
	)
	@DocumentedBody(content = [Content(schema = Schema(implementation = CalendarPatchRequest::class))])
	@ApiResponse(
		responseCode = "200",
		description = "Changed; the calendar with its new ETag",
		headers = [Header(name = HttpHeaders.ETAG, description = "New version of the calendar", schema = Schema(type = "string"))],
		content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = CalendarResponse::class))],
	)
	@ApiResponse(
		responseCode = "400",
		description = "Invalid or unknown fields",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
	)
	@ApiResponse(
		responseCode = "404",
		description = "No such calendar",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
	)
	@ApiResponse(
		responseCode = "409",
		description = "Changed concurrently",
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
	fun update(
		@PathVariable id: UUID,
		@Parameter(description = "ETag of the calendar") @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
		@RequestBody body: Map<String, Any?>,
	): ResponseEntity<CalendarResponse> {
		val changes = parsePatch(body)
		return respond(calendars.update(id, changes) { ETags.checkIfMatch(ifMatch, it) })
	}

	private fun respond(view: CalendarView): ResponseEntity<CalendarResponse> = ETags.ok(response(view), view.calendar.version)

	private fun response(view: CalendarView) = CalendarResponse.of(view.calendar, view.sourceType)

	private fun parsePatch(body: Map<String, Any?>): CalendarChanges {
		val sent = body.keys
		val unknown = sent - PATCH_FIELDS
		if (unknown.isNotEmpty()) {
			throw InvalidRequestException(unknown.sorted().map { FieldError(it, "is not a field that PATCH can change") })
		}
		val request =
			try {
				jsonMapper.convertValue(body, CalendarPatchRequest::class.java)
			} catch (e: JacksonException) {
				val field = e.path.mapNotNull { it.propertyName }.firstOrNull() ?: "body"
				throw InvalidRequestException(field, "has an invalid value")
			}
		val notNullable = listOf("name", "visible", "isDefault").filter { it in sent && body[it] == null }
		if (notNullable.isNotEmpty()) throw InvalidRequestException(notNullable.map { FieldError(it, "must not be null") })
		return with(request) {
			CalendarChanges(
				name = if ("name" in sent) FieldChange(name!!) else null,
				color = if ("color" in sent) FieldChange(color) else null,
				visible = if ("visible" in sent) FieldChange(visible!!) else null,
				isDefault = if ("isDefault" in sent) FieldChange(isDefault!!) else null,
			)
		}
	}

	private companion object {
		val PATCH_FIELDS = setOf("name", "color", "visible", "isDefault")
	}
}
