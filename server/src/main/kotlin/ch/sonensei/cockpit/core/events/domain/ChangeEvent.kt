package ch.sonensei.cockpit.core.events.domain

import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode
import java.time.Instant
import java.util.UUID

/** What happened to an item. */
enum class ChangeOp { CREATED, UPDATED, DELETED }

/**
 * "Item [id] of [type] was [op] at [at]" (SPEC.md §2). Clients receive it over `GET /api/v1/events` and refetch the
 * item; the event never carries item contents. [type] is an `ItemType` name for user-facing items, or another
 * notification kind (e.g. `REMINDER`).
 *
 * Publish it with [ChangeEventPublisher] inside the transaction that makes the change; it reaches clients only after
 * that transaction commits.
 */
@Schema(description = "A change notification. Clients refetch the item; the event carries no item contents.")
data class ChangeEvent(
	@get:Schema(
		description = "Item type (an ItemType name such as TASK) or another notification kind",
		example = "TASK",
		requiredMode = RequiredMode.REQUIRED,
	)
	val type: String,
	@get:Schema(requiredMode = RequiredMode.REQUIRED)
	val id: UUID,
	@get:Schema(requiredMode = RequiredMode.REQUIRED)
	val op: ChangeOp,
	@get:Schema(requiredMode = RequiredMode.REQUIRED)
	val at: Instant,
) {
	init {
		require(type.isNotBlank()) { "type must not be blank" }
	}

	/** For enum-typed sources such as `ItemType.TASK`: the type is the constant's name. */
	constructor(type: Enum<*>, id: UUID, op: ChangeOp, at: Instant) : this(type.name, id, op, at)
}
