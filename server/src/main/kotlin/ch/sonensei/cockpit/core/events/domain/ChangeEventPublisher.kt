package ch.sonensei.cockpit.core.events.domain

import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import java.time.Clock
import java.util.UUID

/**
 * How modules announce changes: a [ChangeEvent] as a Spring application event, stamped with the application clock.
 * Listeners that push to clients use `@TransactionalEventListener`, so an event published in a transaction that rolls
 * back is never delivered.
 */
@Service
class ChangeEventPublisher(
	private val events: ApplicationEventPublisher,
	private val clock: Clock,
) {
	fun publish(event: ChangeEvent) = events.publishEvent(event)

	fun publish(
		type: Enum<*>,
		id: UUID,
		op: ChangeOp,
	) = publish(ChangeEvent(type, id, op, clock.instant()))

	fun publish(
		type: String,
		id: UUID,
		op: ChangeOp,
	) = publish(ChangeEvent(type, id, op, clock.instant()))
}
