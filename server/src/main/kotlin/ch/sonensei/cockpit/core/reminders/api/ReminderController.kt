package ch.sonensei.cockpit.core.reminders.api

import ch.sonensei.cockpit.core.events.domain.ClientNotification
import ch.sonensei.cockpit.core.items.domain.ItemRef
import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.core.reminders.domain.ReminderChannel
import ch.sonensei.cockpit.core.reminders.domain.ReminderFiredEvent
import ch.sonensei.cockpit.core.reminders.domain.ReminderService
import ch.sonensei.cockpit.core.reminders.domain.ResolvedReminder
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotNull
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.support.ServletUriComponentsBuilder
import java.time.Instant
import java.util.UUID

/** Reminders for any item (SPEC.md §4.6). */
@RestController
@RequestMapping("/api/v1/reminders")
@Tag(
	name = "Reminders",
	description =
		"Reminders for items of any type. When a reminder is due, the server fires it once and sends the SSE event " +
			"`reminder` on `GET /api/v1/events` with a `ReminderResponse` as data. Every change also sends a `change` " +
			"event of type `REMINDER`.",
)
class ReminderController(
	private val reminders: ReminderService,
) {
	@GetMapping(produces = [MediaType.APPLICATION_JSON_VALUE])
	@Operation(
		operationId = "listReminders",
		summary = "The reminders of an item",
		description = "All reminders of the item, fired or not, earliest first.",
	)
	@ApiResponse(responseCode = "200", description = "The item's reminders (empty when it has none or does not exist)")
	@ApiResponse(
		responseCode = "400",
		description = "Missing or invalid itemType or itemId",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
	)
	fun list(
		@Parameter(description = "Type of the item") @RequestParam itemType: ItemType,
		@Parameter(description = "Id of the item") @RequestParam itemId: UUID,
	) = ReminderListResponse(reminders.ofItem(ItemRef(itemType, itemId)).map { it.toResponse() })

	@GetMapping("/upcoming", produces = [MediaType.APPLICATION_JSON_VALUE])
	@Operation(
		operationId = "listUpcomingReminders",
		summary = "Reminders not fired yet, up to a time",
		description =
			"Reminders that have not fired yet and are due at or before `until`, overdue ones included, earliest " +
				"first, at most ${ReminderService.MAX_UPCOMING}. The Android app schedules local alarms from them.",
	)
	@ApiResponse(responseCode = "200", description = "The pending reminders")
	@ApiResponse(
		responseCode = "400",
		description = "Missing or invalid until",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
	)
	fun upcoming(
		@Parameter(description = "Latest reminder time to include (ISO-8601 instant)", example = "2026-10-27T00:00:00Z")
		@RequestParam until: Instant,
	) = ReminderListResponse(reminders.upcoming(until).map { it.toResponse() })

	@GetMapping("/{id}", produces = [MediaType.APPLICATION_JSON_VALUE])
	@Operation(operationId = "getReminder", summary = "One reminder")
	@ApiResponse(responseCode = "200", description = "The reminder")
	@ApiResponse(
		responseCode = "404",
		description = "No such reminder",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
	)
	fun get(
		@PathVariable id: UUID,
	) = reminders.get(id).toResponse()

	@PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE])
	@ResponseStatus(HttpStatus.CREATED)
	@Operation(
		operationId = "createReminder",
		summary = "Add a reminder to an item",
		description =
			"A time in the past is allowed: the reminder fires on the next scheduler run (within seconds). An item " +
				"that does not exist is rejected; an item whose type has no module yet is accepted.",
	)
	@ApiResponse(responseCode = "201", description = "Created; `Location` points to the reminder")
	@ApiResponse(
		responseCode = "400",
		description = "Invalid request or unknown item",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
	)
	fun create(
		@Valid @RequestBody request: CreateReminderRequest,
	): ResponseEntity<ReminderResponse> {
		val created =
			reminders.create(
				ItemRef(request.itemType!!, request.itemId!!),
				request.remindAt!!,
				request.channel ?: ReminderChannel.NOTIFICATION,
			)
		val location =
			ServletUriComponentsBuilder
				.fromCurrentRequestUri()
				.path("/{id}")
				.buildAndExpand(created.reminder.id)
				.toUri()
		return ResponseEntity.created(location).body(created.toResponse())
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(
		operationId = "deleteReminder",
		summary = "Remove a reminder",
		description = "Reminders never change after creation (only the server sets `firedAt`), so no `If-Match` is needed.",
	)
	@ApiResponse(responseCode = "204", description = "Removed")
	@ApiResponse(
		responseCode = "404",
		description = "No such reminder",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
	)
	fun delete(
		@PathVariable id: UUID,
	) = reminders.delete(id)
}

/** Sends each fired reminder to the connected clients as SSE event [EVENT_NAME] (after the firing transaction commits). */
@Component
class ReminderNotifier(
	private val events: ApplicationEventPublisher,
) {
	@EventListener
	fun onFired(event: ReminderFiredEvent) = events.publishEvent(ClientNotification(EVENT_NAME, event.reminder.toResponse()))

	companion object {
		/** The SSE `event:` name of a fired reminder. */
		const val EVENT_NAME = "reminder"
	}
}

// Nullable fields so a missing value reaches Bean Validation (400 with field errors) instead of failing in Jackson.
class CreateReminderRequest(
	@field:NotNull
	@field:Schema(requiredMode = RequiredMode.REQUIRED)
	val itemType: ItemType?,
	@field:NotNull
	@field:Schema(requiredMode = RequiredMode.REQUIRED)
	val itemId: UUID?,
	@field:NotNull
	@field:Schema(description = "When to remind (ISO-8601 instant)", requiredMode = RequiredMode.REQUIRED)
	val remindAt: Instant?,
	@field:Schema(description = "Defaults to NOTIFICATION")
	val channel: ReminderChannel? = null,
)

@Schema(description = "The reminded item. Without `available`, only type and id are set.")
data class ReminderItemResponse(
	@field:Schema(requiredMode = RequiredMode.REQUIRED)
	val type: ItemType,
	@field:Schema(requiredMode = RequiredMode.REQUIRED)
	val id: UUID,
	@field:Schema(
		description = "False when the item no longer exists or its type has no resolver",
		requiredMode = RequiredMode.REQUIRED,
	)
	val available: Boolean,
	val title: String? = null,
	@field:Schema(description = "Material Symbols icon name")
	val icon: String? = null,
	@field:Schema(description = "Client route of the item's detail view, e.g. /tasks/{id}")
	val deepLink: String? = null,
)

@Schema(description = "A reminder; also the data of the `reminder` SSE event")
data class ReminderResponse(
	@field:Schema(requiredMode = RequiredMode.REQUIRED)
	val id: UUID,
	@field:Schema(requiredMode = RequiredMode.REQUIRED)
	val item: ReminderItemResponse,
	@field:Schema(requiredMode = RequiredMode.REQUIRED)
	val remindAt: Instant,
	@field:Schema(requiredMode = RequiredMode.REQUIRED)
	val channel: ReminderChannel,
	@field:Schema(description = "When the server fired it; absent while pending")
	val firedAt: Instant? = null,
	@field:Schema(requiredMode = RequiredMode.REQUIRED)
	val createdAt: Instant,
)

data class ReminderListResponse(
	@field:Schema(requiredMode = RequiredMode.REQUIRED)
	val items: List<ReminderResponse>,
)

private fun ResolvedReminder.toResponse() =
	ReminderResponse(
		id = reminder.id,
		item =
			ReminderItemResponse(
				type = reminder.itemType,
				id = reminder.itemId,
				available = item != null,
				title = item?.title,
				icon = item?.icon,
				deepLink = item?.deepLink,
			),
		remindAt = reminder.remindAt,
		channel = reminder.channel,
		firedAt = reminder.firedAt,
		createdAt = checkNotNull(reminder.createdAt),
	)

private const val PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON_VALUE
