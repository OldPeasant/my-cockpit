package ch.sonensei.cockpit.gtd.api

import ch.sonensei.cockpit.core.web.CursorRequest
import ch.sonensei.cockpit.core.web.ETags
import ch.sonensei.cockpit.core.web.InvalidRequestException
import ch.sonensei.cockpit.gtd.domain.Creativity
import ch.sonensei.cockpit.gtd.domain.DueFilter
import ch.sonensei.cockpit.gtd.domain.Energy
import ch.sonensei.cockpit.gtd.domain.Task
import ch.sonensei.cockpit.gtd.domain.TaskChanges
import ch.sonensei.cockpit.gtd.domain.TaskFilter
import ch.sonensei.cockpit.gtd.domain.TaskList
import ch.sonensei.cockpit.gtd.domain.TaskService
import ch.sonensei.cockpit.gtd.domain.TicklerJob
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
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID
import io.swagger.v3.oas.annotations.parameters.RequestBody as DocumentedBody

/**
 * GTD tasks (SPEC.md §5.4): list with filters, CRUD and the workflow actions. Responses carry the task's `ETag`;
 * PATCH and DELETE need it in `If-Match`, the workflow actions check it when it is sent.
 */
@RestController
@RequestMapping("/api/v1/tasks")
@Tag(name = "Tasks", description = "GTD tasks")
class TaskController(
	private val tasks: TaskService,
	private val tickler: TicklerJob,
	private val jsonMapper: JsonMapper,
) {
	@GetMapping(produces = [MediaType.APPLICATION_JSON_VALUE])
	@Operation(
		operationId = "listTasks",
		summary = "List tasks",
		description =
			"Tasks matching every given filter, not in the Trash and not in a tickler (`ticklerDate` after today). " +
				"Due ticklers move back to the INBOX and due SCHEDULED tasks to NEXT before the list is read. " +
				"Open tasks (default) are ordered by position, then id; with `completed=true` the done tasks (Logbook) " +
				"are returned, newest first. `due` is relative to today in the user's time zone: OVERDUE before today, " +
				"TODAY today, WEEK today and the next 6 days. " +
				"`maxMinutes` only matches tasks with an estimate. " +
				"`from` and `to` (both or neither, inclusive, at most $MAX_RANGE_DAYS days) only match tasks whose due or " +
				"tickler date is in that range, including tasks still in a tickler (the calendar overlay).",
	)
	@ApiResponse(responseCode = "200", description = "One page of tasks")
	@ApiResponse(
		responseCode = "400",
		description = "Invalid filter, cursor or limit",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
	)
	fun list(
		@Parameter(description = "Only tasks on this list") @RequestParam(required = false) list: TaskList?,
		@Parameter(description = "Only tasks of this project") @RequestParam(required = false) projectId: UUID?,
		@Parameter(description = "Only tasks with this context (id)") @RequestParam(required = false) context: UUID?,
		@Parameter(description = "Only tasks with this energy") @RequestParam(required = false) energy: Energy?,
		@Parameter(description = "Only tasks with this creativity") @RequestParam(required = false) creativity: Creativity?,
		@Parameter(
			description = "Only tasks estimated at most this many minutes",
			schema = Schema(type = "integer", format = "int32", minimum = "0"),
		)
		@RequestParam(required = false)
		maxMinutes: Int?,
		@Parameter(
			description = "Only tasks due in this window (case-insensitive)",
			schema = Schema(type = "string", allowableValues = ["overdue", "today", "week"]),
		)
		@RequestParam(required = false)
		due: String?,
		@Parameter(description = "true: done tasks (Logbook) instead of open ones")
		@RequestParam(required = false, defaultValue = "false")
		completed: Boolean,
		@Parameter(description = "Only tasks due or in a tickler on or after this date")
		@RequestParam(required = false)
		@DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
		from: LocalDate?,
		@Parameter(description = "Only tasks due or in a tickler on or before this date")
		@RequestParam(required = false)
		@DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
		to: LocalDate?,
		@Parameter(description = "Cursor from the previous page") @RequestParam(required = false) cursor: String?,
		@Parameter(description = "Page size, 1-200 (default 50)") @RequestParam(required = false) limit: Int?,
	): TaskPageResponse {
		if (maxMinutes != null && maxMinutes < 0) throw InvalidRequestException("maxMinutes", "must not be negative")
		val dueFilter =
			due?.let { value ->
				DueFilter.entries.find { it.name.equals(value, ignoreCase = true) }
					?: throw InvalidRequestException("due", "must be one of overdue, today, week")
			}
		val dates = dateRange(from, to)
		val filter = TaskFilter(list, projectId, context, energy, creativity, maxMinutes, dueFilter, completed, dates)
		// The on-read check: what became due since the last tickler run is on its new list (SPEC.md §5.1).
		tickler.run()
		val page = tasks.list(filter, CursorRequest.of(cursor, limit))
		return TaskPageResponse(page.items.map(TaskResponse::of), page.nextCursor)
	}

	@PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE, PROBLEM_JSON])
	@Operation(
		operationId = "createTask",
		summary = "Create a task",
		description =
			"With a client-generated `id` (UUIDv7) the create is idempotent: repeating it returns the existing task " +
				"(200) unchanged.",
	)
	@ApiResponse(
		responseCode = "201",
		description = "Created",
		headers = [Header(name = HttpHeaders.ETAG, description = "Version of the task, for If-Match", schema = Schema(type = "string"))],
		content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = TaskResponse::class))],
	)
	@ApiResponse(
		responseCode = "200",
		description = "A task with this id already exists; it is returned unchanged",
		headers = [Header(name = HttpHeaders.ETAG, description = "Version of the task, for If-Match", schema = Schema(type = "string"))],
		content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = TaskResponse::class))],
	)
	@ApiResponse(
		responseCode = "400",
		description = "Invalid fields, an unknown project or context, or SCHEDULED without a date",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
	)
	@ApiResponse(
		responseCode = "409",
		description = "The id belongs to a task in the Trash",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
	)
	fun create(
		@Valid @RequestBody request: CreateTaskRequest,
	): ResponseEntity<TaskResponse> {
		val result =
			try {
				tasks.create(request.id, request.list ?: TaskList.INBOX, request.toChanges())
			} catch (ex: DataIntegrityViolationException) {
				// A concurrent create with the same client id inserted first (its transaction has rolled back here):
				// answer like the idempotent repeat.
				request.id?.let { tasks.existingCreate(it) } ?: throw ex
			}
		val task = result.task
		if (!result.created) return ETags.ok(TaskResponse.of(task), task.version)
		val location =
			ServletUriComponentsBuilder
				.fromCurrentRequestUri()
				.path("/{id}")
				.buildAndExpand(task.id)
				.toUri()
		return ResponseEntity
			.created(location)
			.eTag(ETags.of(task.version))
			.body(TaskResponse.of(task))
	}

	@GetMapping("/{id}", produces = [MediaType.APPLICATION_JSON_VALUE, PROBLEM_JSON])
	@Operation(
		operationId = "getTask",
		summary = "One task",
		description =
			"Due ticklers move back to the INBOX and due SCHEDULED tasks to NEXT before the task is read, " +
				"so its list, dates and ETag may differ from an earlier read.",
	)
	@ApiResponse(
		responseCode = "200",
		description = "The task",
		headers = [Header(name = HttpHeaders.ETAG, description = "Version of the task, for If-Match", schema = Schema(type = "string"))],
		content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = TaskResponse::class))],
	)
	@ApiResponse(
		responseCode = "404",
		description = "No such task, or it is in the Trash",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
	)
	fun get(
		@PathVariable id: UUID,
	): ResponseEntity<TaskResponse> {
		tickler.run()
		return respond(tasks.get(id))
	}

	@PatchMapping("/{id}", consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE, PROBLEM_JSON])
	@Operation(
		operationId = "updateTask",
		summary = "Change a task",
		description = "Only the fields sent change; `null` clears a field. Unknown fields (including `list`) are rejected.",
	)
	@DocumentedBody(content = [Content(schema = Schema(implementation = TaskPatchRequest::class))])
	@ApiResponse(
		responseCode = "200",
		description = "Changed; the task with its new ETag",
		headers = [Header(name = HttpHeaders.ETAG, description = "New version of the task", schema = Schema(type = "string"))],
		content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = TaskResponse::class))],
	)
	@ApiResponse(
		responseCode = "400",
		description = "Invalid or unknown fields",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
	)
	@ApiResponse(
		responseCode = "404",
		description = "No such task, or it is in the Trash",
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
		@Parameter(description = "ETag of the task") @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
		@RequestBody body: Map<String, Any?>,
	): ResponseEntity<TaskResponse> {
		val changes = parsePatch(body)
		return respond(tasks.update(id, changes) { ETags.checkIfMatch(ifMatch, it) })
	}

	@DeleteMapping("/{id}", produces = [PROBLEM_JSON])
	@Operation(operationId = "deleteTask", summary = "Move a task to the Trash")
	@ApiResponse(responseCode = "204", description = "In the Trash")
	@ApiResponse(
		responseCode = "404",
		description = "No such task, or it is already in the Trash",
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
	fun delete(
		@PathVariable id: UUID,
		@Parameter(description = "ETag of the task") @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
	): ResponseEntity<Void> {
		tasks.delete(id) { ETags.checkIfMatch(ifMatch, it) }
		return ResponseEntity.status(HttpStatus.NO_CONTENT).build()
	}

	@PostMapping("/{id}/complete", produces = [MediaType.APPLICATION_JSON_VALUE, PROBLEM_JSON])
	@Operation(
		operationId = "completeTask",
		summary = "Mark a task done",
		description =
			"Completing a done task keeps its completion time. `needsNextStep` is true when the task is the next step of " +
				"an ACTIVE project: the client then asks for a new next step (`POST /projects/{id}/next-step`). `If-Match` is optional.",
	)
	@ApiResponse(
		responseCode = "200",
		description = "The done task with its new ETag, and whether its project needs a new next step",
		headers = [Header(name = HttpHeaders.ETAG, description = "New version of the task", schema = Schema(type = "string"))],
		content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = CompletedTaskResponse::class))],
	)
	@WorkflowErrorResponses
	fun complete(
		@PathVariable id: UUID,
		@Parameter(description = "ETag of the task (optional)") @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
	): ResponseEntity<CompletedTaskResponse> {
		val result = tasks.complete(id) { checkOptionalIfMatch(ifMatch, it) }
		return ETags.ok(CompletedTaskResponse(TaskResponse.of(result.task), result.needsNextStep), result.task.version)
	}

	@PostMapping("/{id}/uncomplete", produces = [MediaType.APPLICATION_JSON_VALUE, PROBLEM_JSON])
	@Operation(
		operationId = "uncompleteTask",
		summary = "Make a done task open again",
		description = "The task returns to the list it was on. `If-Match` is optional.",
	)
	@WorkflowResponses
	fun uncomplete(
		@PathVariable id: UUID,
		@Parameter(description = "ETag of the task (optional)") @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
	): ResponseEntity<TaskResponse> = respond(tasks.uncomplete(id) { checkOptionalIfMatch(ifMatch, it) })

	@PostMapping("/{id}/move", consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE, PROBLEM_JSON])
	@Operation(
		operationId = "moveTask",
		summary = "Move a task to another list (processing)",
		description = "See `MoveTaskRequest` for the rules of each list. `If-Match` is optional.",
	)
	@WorkflowResponses
	fun move(
		@PathVariable id: UUID,
		@Parameter(description = "ETag of the task (optional)") @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
		@Valid @RequestBody request: MoveTaskRequest,
	): ResponseEntity<TaskResponse> = respond(tasks.move(id, request.toMove()) { checkOptionalIfMatch(ifMatch, it) })

	private fun respond(task: Task): ResponseEntity<TaskResponse> = ETags.ok(TaskResponse.of(task), task.version)

	private fun checkOptionalIfMatch(
		ifMatch: String?,
		version: Long,
	) {
		if (!ifMatch.isNullOrBlank()) ETags.checkIfMatch(ifMatch, version)
	}

	/** The PATCH body as [TaskChanges]. */
	private fun parsePatch(body: Map<String, Any?>): TaskChanges {
		val patch = jsonMapper.parsePatch(body, TaskPatchRequest::class.java, PATCH_FIELDS, NOT_NULLABLE)
		return with(patch.request) {
			TaskChanges(
				title = patch.required("title", title),
				notesMd = patch.change("notesMd", notesMd),
				projectId = patch.change("projectId", projectId),
				position = patch.required("position", position),
				energy = patch.change("energy", energy),
				creativity = patch.change("creativity", creativity),
				timeEstimateMin = patch.change("timeEstimateMin", timeEstimateMin),
				contextIds = patch.required("contextIds", contextIds?.toSet()),
				dueDate = patch.change("dueDate", dueDate),
				dueTime = patch.change("dueTime", dueTime),
				scheduledDate = patch.change("scheduledDate", scheduledDate),
				waitingForContactId = patch.change("waitingForContactId", waitingForContactId),
				waitingForText = patch.change("waitingForText", waitingForText),
				waitingSince = patch.change("waitingSince", waitingSince),
				recurrenceRule = patch.change("recurrenceRule", recurrenceRule),
				recurrenceMode = patch.change("recurrenceMode", recurrenceMode),
			)
		}
	}

	private companion object {
		val PATCH_FIELDS =
			setOf(
				"title",
				"notesMd",
				"projectId",
				"position",
				"energy",
				"creativity",
				"timeEstimateMin",
				"contextIds",
				"dueDate",
				"dueTime",
				"scheduledDate",
				"waitingForContactId",
				"waitingForText",
				"waitingSince",
				"recurrenceRule",
				"recurrenceMode",
			)
		val NOT_NULLABLE = setOf("title", "position", "contextIds")
	}
}

/** The longest `from`-`to` range of the task list: a year, more than any calendar view shows. */
private const val MAX_RANGE_DAYS = 366

/** The date range of the task list filter, `null` without one; both ends or none, at most [MAX_RANGE_DAYS] days. */
private fun dateRange(
	from: LocalDate?,
	to: LocalDate?,
): ClosedRange<LocalDate>? {
	if (from == null && to == null) return null
	if (from == null) throw InvalidRequestException("from", "is required with to")
	if (to == null) throw InvalidRequestException("to", "is required with from")
	if (to.isBefore(from)) throw InvalidRequestException("to", "must not be before from")
	if (ChronoUnit.DAYS.between(from, to) >= MAX_RANGE_DAYS) {
		throw InvalidRequestException("to", "the range must span at most $MAX_RANGE_DAYS days")
	}
	return from..to
}

/** The responses of the workflow actions that return the task (uncomplete, move). */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@ApiResponse(
	responseCode = "200",
	description = "The changed task with its new ETag",
	headers = [Header(name = HttpHeaders.ETAG, description = "New version of the task", schema = Schema(type = "string"))],
	content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = TaskResponse::class))],
)
@WorkflowErrorResponses
annotation class WorkflowResponses

/** The error responses of the workflow actions (complete, uncomplete, move). */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.ANNOTATION_CLASS)
@Retention(AnnotationRetention.RUNTIME)
@ApiResponse(
	responseCode = "400",
	description = "Invalid request",
	content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
)
@ApiResponse(
	responseCode = "404",
	description = "No such task, or it is in the Trash",
	content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
)
@ApiResponse(
	responseCode = "409",
	description = "Changed concurrently",
	content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
)
@ApiResponse(
	responseCode = "412",
	description = "If-Match was sent and does not match the current ETag",
	content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
)
annotation class WorkflowErrorResponses

internal const val PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON_VALUE
