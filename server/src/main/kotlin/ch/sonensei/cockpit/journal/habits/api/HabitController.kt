package ch.sonensei.cockpit.journal.habits.api

import ch.sonensei.cockpit.core.web.ETags
import ch.sonensei.cockpit.core.web.FieldError
import ch.sonensei.cockpit.core.web.InvalidRequestException
import ch.sonensei.cockpit.journal.habits.domain.HabitService
import ch.sonensei.cockpit.journal.habits.domain.HabitView
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.headers.Header
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
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
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.support.ServletUriComponentsBuilder
import tools.jackson.core.JacksonException
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.util.UUID
import io.swagger.v3.oas.annotations.parameters.RequestBody as DocumentedBody

/**
 * Habits and their daily checks (SPEC.md §8.5). Responses carry the habit's `ETag`; PATCH and DELETE need it in
 * `If-Match`. Setting a check needs no `If-Match`: it sets an absolute count, so repeating it is harmless.
 */
@RestController
@RequestMapping("/api/v1/habits")
@Tag(name = "Habits", description = "Habits with checks, streaks and completion stats")
class HabitController(
	private val habits: HabitService,
	private val jsonMapper: JsonMapper,
) {
	@GetMapping(produces = [MediaType.APPLICATION_JSON_VALUE])
	@Operation(operationId = "listHabits", summary = "List habits", description = "Active (or archived) habits by name, with today's state.")
	@ApiResponse(responseCode = "200", description = "The habits")
	fun list(
		@Parameter(description = "`true`: only archived habits; default: only active ones")
		@RequestParam(required = false, defaultValue = "false")
		archived: Boolean,
	): HabitListResponse = HabitListResponse(habits.list(archived).map(HabitResponse::of))

	@PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE, PROBLEM_JSON])
	@Operation(
		operationId = "createHabit",
		summary = "Create a habit",
		description =
			"With a `reminderTime`, a reminder (`/reminders`, item type HABIT) follows the habit's next open due day. " +
				"Unknown fields are rejected.",
	)
	@DocumentedBody(content = [Content(schema = Schema(implementation = HabitRequest::class))])
	@ApiResponse(
		responseCode = "201",
		description = "Created",
		headers = [Header(name = HttpHeaders.ETAG, description = "Version of the habit, for If-Match", schema = Schema(type = "string"))],
		content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = HabitResponse::class))],
	)
	@HabitBadRequestResponse
	fun create(
		@RequestBody body: Map<String, Any?>,
	): ResponseEntity<HabitResponse> {
		val view = habits.create(parse(body).toChanges(HabitRequest.FIELDS))
		val location =
			ServletUriComponentsBuilder
				.fromCurrentRequestUri()
				.path("/{id}")
				.buildAndExpand(view.habit.id)
				.toUri()
		return ResponseEntity.created(location).eTag(ETags.of(view.habit.version)).body(HabitResponse.of(view))
	}

	@GetMapping("/{id}", produces = [MediaType.APPLICATION_JSON_VALUE, PROBLEM_JSON])
	@Operation(operationId = "getHabit", summary = "One habit")
	@HabitOkResponse
	@HabitNotFoundResponse
	fun get(
		@PathVariable id: UUID,
	): ResponseEntity<HabitResponse> = respond(habits.get(id))

	@PatchMapping("/{id}", consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE, PROBLEM_JSON])
	@Operation(
		operationId = "updateHabit",
		summary = "Change a habit",
		description =
			"Only the fields sent change; `null` clears a field or resets it to its default. Archiving removes the pending " +
				"reminder, unarchiving brings it back. Unknown fields are rejected.",
	)
	@DocumentedBody(content = [Content(schema = Schema(implementation = HabitRequest::class))])
	@HabitOkResponse
	@HabitBadRequestResponse
	@HabitNotFoundResponse
	@HabitIfMatchResponses
	fun update(
		@PathVariable id: UUID,
		@Parameter(description = "ETag of the habit") @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
		@RequestBody body: Map<String, Any?>,
	): ResponseEntity<HabitResponse> {
		val changes = parse(body).toChanges(body.keys)
		return respond(habits.update(id, changes) { ETags.checkIfMatch(ifMatch, it) })
	}

	@DeleteMapping("/{id}", produces = [PROBLEM_JSON])
	@Operation(
		operationId = "deleteHabit",
		summary = "Delete a habit",
		description = "Deletes it for good, with its checks, links and reminders (habits do not go to the Trash; archive to keep one).",
	)
	@ApiResponse(responseCode = "204", description = "Deleted")
	@HabitNotFoundResponse
	@HabitIfMatchResponses
	fun delete(
		@PathVariable id: UUID,
		@Parameter(description = "ETag of the habit") @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
	): ResponseEntity<Void> {
		habits.delete(id) { ETags.checkIfMatch(ifMatch, it) }
		return ResponseEntity.status(HttpStatus.NO_CONTENT).build()
	}

	@GetMapping("/{id}/checks", produces = [MediaType.APPLICATION_JSON_VALUE, PROBLEM_JSON])
	@Operation(
		operationId = "listHabitChecks",
		summary = "Checks of a habit",
		description = "Checks from `from` to `to` (both included, at most 3660 days), by date.",
	)
	@ApiResponse(responseCode = "200", description = "The checks")
	@HabitBadRequestResponse
	@HabitNotFoundResponse
	fun checks(
		@PathVariable id: UUID,
		@Parameter(description = "First date (default: 29 days before today)")
		@RequestParam(required = false)
		@DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
		from: LocalDate?,
		@Parameter(description = "Last date (default: today)")
		@RequestParam(required = false)
		@DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
		to: LocalDate?,
	): HabitCheckListResponse {
		val today = habits.today()
		val list = habits.checks(id, from ?: today.minusDays(29), to ?: today)
		return HabitCheckListResponse(list.map(HabitCheckResponse::of))
	}

	@PutMapping(
		"/{id}/checks/{date}",
		consumes = [MediaType.APPLICATION_JSON_VALUE],
		produces = [MediaType.APPLICATION_JSON_VALUE, PROBLEM_JSON],
	)
	@Operation(
		operationId = "setHabitCheck",
		summary = "Set how often a habit was done on a date",
		description =
			"Sets the count of the (local) date, 0 removes the check; dates after today are rejected. One-tap check: " +
				"send `targetCount`. Returns the habit with its new stats.",
	)
	@DocumentedBody(content = [Content(schema = Schema(implementation = HabitCheckRequest::class))])
	@HabitOkResponse
	@HabitBadRequestResponse
	@HabitNotFoundResponse
	fun setCheck(
		@PathVariable id: UUID,
		@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDate,
		@RequestBody body: Map<String, Any?>,
	): ResponseEntity<HabitResponse> {
		val unknown = body.keys - setOf("count")
		if (unknown.isNotEmpty()) throw InvalidRequestException(unknown.sorted().map { FieldError(it, "is not a field of a check") })
		val count = body["count"] as? Int ?: throw InvalidRequestException("count", "must be a whole number")
		return respond(habits.setCheck(id, date, count))
	}

	/** Converts a create or PATCH body; unknown fields and values of the wrong type are a 400. */
	private fun parse(body: Map<String, Any?>): HabitRequest {
		val unknown = body.keys - HabitRequest.FIELDS
		if (unknown.isNotEmpty()) throw InvalidRequestException(unknown.sorted().map { FieldError(it, "is not a field of a habit") })
		return try {
			jsonMapper.convertValue(body, HabitRequest::class.java)
		} catch (e: JacksonException) {
			val field = e.path.mapNotNull { it.propertyName }.firstOrNull() ?: "body"
			throw InvalidRequestException(field, "has an invalid value")
		}
	}

	private fun respond(view: HabitView): ResponseEntity<HabitResponse> = ETags.ok(HabitResponse.of(view), view.habit.version)
}

private const val PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON_VALUE

/** `200` with a habit and its ETag. */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@ApiResponse(
	responseCode = "200",
	description = "The habit with its (new) ETag",
	headers = [Header(name = HttpHeaders.ETAG, description = "Version of the habit, for If-Match", schema = Schema(type = "string"))],
	content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = HabitResponse::class))],
)
annotation class HabitOkResponse

/** `400` Problem Details response of the habits endpoints. */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@ApiResponse(
	responseCode = "400",
	description = "Invalid or unknown fields",
	content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
)
annotation class HabitBadRequestResponse

/** `404` Problem Details response of the habits endpoints. */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@ApiResponse(
	responseCode = "404",
	description = "No such habit",
	content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
)
annotation class HabitNotFoundResponse

/** The optimistic locking responses (409, 412, 428) of a change that requires `If-Match`. */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
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
annotation class HabitIfMatchResponses
