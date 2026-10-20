package ch.sonensei.cockpit.core.reminders.domain

import ch.sonensei.cockpit.core.items.domain.ItemRef
import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.core.persistence.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * How a reminder reaches the user. [NOTIFICATION]: the `reminder` SSE event (browser notification) and a local alarm
 * on Android, which schedules synced reminders itself (SPEC.md §4.6).
 */
enum class ReminderChannel {
	NOTIFICATION,
}

/**
 * A reminder for any item (SPEC.md §4.6): at [remindAt] the scheduler fires it once and sets [firedAt]. Only [firedAt]
 * changes after creation; a reminder at another time is a new reminder.
 */
@Entity
@Table(name = "reminder")
class Reminder(
	@Enumerated(EnumType.STRING)
	@Column(name = "item_type", nullable = false, updatable = false)
	val itemType: ItemType,
	@Column(name = "item_id", nullable = false, updatable = false)
	val itemId: UUID,
	@Column(name = "remind_at", nullable = false, updatable = false)
	val remindAt: Instant,
	@Enumerated(EnumType.STRING)
	@Column(name = "channel", nullable = false, updatable = false)
	val channel: ReminderChannel = ReminderChannel.NOTIFICATION,
	id: UUID? = null,
) : BaseEntity(id) {
	constructor(item: ItemRef, remindAt: Instant, channel: ReminderChannel, id: UUID? = null) :
		this(item.type, item.id, remindAt, channel, id)

	@Column(name = "fired_at")
	var firedAt: Instant? = null
		protected set

	val item: ItemRef get() = ItemRef(itemType, itemId)

	fun markFired(at: Instant) {
		check(firedAt == null) { "Reminder $id already fired" }
		firedAt = at
	}
}
