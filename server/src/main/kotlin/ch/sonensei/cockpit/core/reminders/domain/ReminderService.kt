package ch.sonensei.cockpit.core.reminders.domain

import ch.sonensei.cockpit.core.events.domain.ChangeEventPublisher
import ch.sonensei.cockpit.core.events.domain.ChangeOp
import ch.sonensei.cockpit.core.items.domain.ItemDeletedEvent
import ch.sonensei.cockpit.core.items.domain.ItemRef
import ch.sonensei.cockpit.core.items.domain.ItemResolverRegistry
import ch.sonensei.cockpit.core.items.domain.ResolvedItem
import ch.sonensei.cockpit.core.reminders.infra.ReminderRepository
import ch.sonensei.cockpit.core.web.InvalidRequestException
import ch.sonensei.cockpit.core.web.NotFoundException
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.data.domain.Limit
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/** A reminder with its item's display data; [item] is `null` when the item does not exist or has no resolver. */
data class ResolvedReminder(
	val reminder: Reminder,
	val item: ResolvedItem?,
)

/**
 * Published by [ReminderService.fireDue] inside its transaction for each fired reminder whose item still exists. The
 * API layer turns it into the `reminder` SSE event.
 */
data class ReminderFiredEvent(
	val reminder: ResolvedReminder,
)

/**
 * Reminders for any item (SPEC.md §4.6). Every change publishes a `REMINDER` [ch.sonensei.cockpit.core.events.domain.ChangeEvent],
 * so other clients (e.g. the Android sync) refetch.
 */
@Service
@Transactional
class ReminderService(
	private val reminders: ReminderRepository,
	private val resolvers: ItemResolverRegistry,
	private val items: ReminderItems,
	private val changes: ChangeEventPublisher,
	private val events: ApplicationEventPublisher,
	private val clock: Clock,
) {
	private val log = LoggerFactory.getLogger(javaClass)

	/**
	 * A reminder for [item] at [remindAt] (stored at microsecond precision). A time in the past is allowed: the
	 * reminder fires on the next scheduler run. An item whose resolver says it does not exist is rejected; an item
	 * without a resolver is accepted (its module may not exist yet). The existence check runs in this transaction (an
	 * item created in it counts); the returned display data is resolved in its own transaction ([ReminderItems]).
	 *
	 * @throws InvalidRequestException (400) for an unknown item
	 */
	fun create(
		item: ItemRef,
		remindAt: Instant,
		channel: ReminderChannel = ReminderChannel.NOTIFICATION,
	): ResolvedReminder {
		val resolver = resolvers.find(item.type)
		if (resolver != null && !resolver.exists(item.id)) throw InvalidRequestException("itemId", "does not exist")
		val reminder = reminders.save(Reminder(item, remindAt.truncatedTo(ChronoUnit.MICROS), channel))
		changes.publish(TYPE, reminder.id, ChangeOp.CREATED)
		return resolve(reminder)
	}

	/** @throws NotFoundException (404) when no such reminder exists */
	@Transactional(readOnly = true)
	fun get(id: UUID): ResolvedReminder = resolve(find(id))

	/**
	 * Deletes by id without a version check: reminders never change after creation except for `firedAt`, which the
	 * scheduler may set at the same moment; that must not make the delete fail.
	 *
	 * @throws NotFoundException (404) when no such reminder exists
	 */
	fun delete(id: UUID) {
		if (reminders.deleteReminder(id) == 0) throw NotFoundException("Reminder not found")
		changes.publish(TYPE, id, ChangeOp.DELETED)
	}

	/** All reminders of [item], fired or not, earliest first. */
	@Transactional(readOnly = true)
	fun ofItem(item: ItemRef): List<ResolvedReminder> =
		resolveAll(reminders.findByItemTypeAndItemIdOrderByRemindAtAscIdAsc(item.type, item.id))

	/**
	 * The reminders of [item] that have not fired yet, earliest first, without display data: for modules that manage
	 * their items' reminders themselves.
	 */
	@Transactional(readOnly = true)
	fun pendingOfItem(item: ItemRef): List<Reminder> =
		reminders.findByItemTypeAndItemIdAndFiredAtIsNullOrderByRemindAtAscIdAsc(item.type, item.id)

	/**
	 * Reminders not fired yet, due at or before [until] (overdue ones included), earliest first, at most
	 * [MAX_UPCOMING]: what the Android app schedules as local alarms.
	 */
	@Transactional(readOnly = true)
	fun upcoming(until: Instant): List<ResolvedReminder> = resolveAll(reminders.findPending(until, Limit.of(MAX_UPCOMING)))

	/**
	 * Fires the due reminders (at most [FIRE_BATCH] per call, earliest first): sets `firedAt` and publishes a
	 * [ReminderFiredEvent] for each, except when the item's resolver says the item is gone (deleted or in the Trash):
	 * that reminder is marked fired silently. Without a resolver, or when it fails, the event goes out without title; the
	 * resolvers run in their own transactions ([ReminderItems]), so a failing one cannot roll back the batch. Rows another
	 * transaction is firing are skipped, and a fired reminder is never due again, so each reminder fires exactly once,
	 * also across restarts.
	 *
	 * @return the number of reminders marked fired; [FIRE_BATCH] means more may be due
	 */
	fun fireDue(): Int {
		val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
		val due = reminders.lockDue(now, Limit.of(FIRE_BATCH))
		due.forEach { reminder ->
			reminder.markFired(now)
			changes.publish(TYPE, reminder.id, ChangeOp.UPDATED)
			val resolved = resolve(reminder)
			if (resolved.item == null && items.isGone(reminder.item)) {
				log.debug("Reminder {} fired without notification: {} no longer exists", reminder.id, reminder.item)
			} else {
				events.publishEvent(ReminderFiredEvent(resolved))
			}
		}
		reminders.flush()
		return due.size
	}

	/** Removes the reminders of a permanently deleted item, in the publisher's transaction. */
	@EventListener
	fun onItemDeleted(event: ItemDeletedEvent) {
		val ofItem = reminders.findByItemTypeAndItemIdOrderByRemindAtAscIdAsc(event.item.type, event.item.id)
		if (ofItem.isEmpty()) return
		reminders.deleteByItem(event.item.type, event.item.id)
		ofItem.forEach { changes.publish(TYPE, it.id, ChangeOp.DELETED) }
	}

	private fun find(id: UUID): Reminder = reminders.findById(id).orElseThrow { NotFoundException("Reminder not found") }

	private fun resolveAll(list: List<Reminder>): List<ResolvedReminder> {
		val cache = HashMap<ItemRef, ResolvedItem?>()
		return list.map { ResolvedReminder(it, cache.getOrPut(it.item) { items.resolve(it.item) }) }
	}

	private fun resolve(reminder: Reminder) = ResolvedReminder(reminder, items.resolve(reminder.item))

	companion object {
		/** The `type` of the reminders' change events. */
		const val TYPE = "REMINDER"
		const val MAX_UPCOMING = 1000
		const val FIRE_BATCH = 100
	}
}
