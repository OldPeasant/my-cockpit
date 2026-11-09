package ch.sonensei.cockpit.review.domain

import ch.sonensei.cockpit.core.items.domain.ItemRef
import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.core.reminders.domain.ReminderFiredEvent
import ch.sonensei.cockpit.core.reminders.domain.ReminderService
import ch.sonensei.cockpit.core.settings.domain.CoreSettings
import ch.sonensei.cockpit.core.settings.domain.SettingsChangedEvent
import ch.sonensei.cockpit.core.settings.domain.SettingsService
import ch.sonensei.cockpit.review.infra.ReviewSettings
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * The weekly review reminder (SPEC.md §8.6): while the `weeklyReviewReminder` setting is not `off`, the review module
 * keeps exactly one pending reminder for [ITEM], at the setting's next occurrence in the owner's time zone. It is moved
 * when the setting or the time zone changes, re-armed after it fired, and checked at startup. The reminder pipeline
 * (SSE, push, Android alarms) delivers it like any other reminder.
 */
@Service
class WeeklyReviewReminders(
	private val settings: SettingsService,
	private val reminders: ReminderService,
	private val jdbc: JdbcTemplate,
	private val clock: Clock,
	transactionManager: PlatformTransactionManager,
) {
	private val log = LoggerFactory.getLogger(javaClass)

	private val isolated =
		TransactionTemplate(transactionManager).apply {
			propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
		}

	/** When the reminder should fire next, from the current settings; `null` when it is off. */
	@Transactional(readOnly = true)
	fun next(): Instant? =
		WeeklyReviewSchedule
			.parse(settings.get(ReviewSettings.WEEKLY_REVIEW_REMINDER))
			?.nextAfter(clock.instant(), settings.timeZone())
			?.truncatedTo(ChronoUnit.MICROS)

	/**
	 * Makes the pending reminders of [ITEM] exactly the one at [next] (none when off). Serialized with an advisory lock,
	 * so concurrent syncs never leave two pending reminders.
	 */
	@Transactional
	fun sync() {
		jdbc.queryForList("select pg_advisory_xact_lock(?)", SYNC_LOCK)
		val wanted = next()
		val pending = reminders.pendingOfItem(ITEM)
		if (pending.map { it.remindAt } == listOfNotNull(wanted)) return
		pending.forEach { reminders.delete(it.id) }
		wanted?.let { reminders.create(ITEM, it) }
		log.debug("Weekly review reminder moved to {}", wanted)
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	fun onSettingsChanged(event: SettingsChangedEvent) {
		if (event.keys.none { it == ReviewSettings.WEEKLY_REVIEW_REMINDER.key || it == CoreSettings.TIME_ZONE.key }) return
		syncLogged("after a settings change")
	}

	/** A fired weekly review reminder arms the next one, after the firing transaction committed. */
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	fun onReminderFired(event: ReminderFiredEvent) {
		if (event.reminder.reminder.itemType != ItemType.WEEKLY_REVIEW) return
		syncLogged("after it fired")
	}

	/** Catches up at startup, e.g. after the server was down while the reminder fired or a release added the setting. */
	@EventListener(ApplicationReadyEvent::class)
	fun onStartup() = syncLogged("at startup")

	/** A failure must neither roll back the caller nor stop the firing batch; the next trigger moves the reminder again. */
	private fun syncLogged(context: String) {
		try {
			isolated.executeWithoutResult { sync() }
		} catch (e: RuntimeException) {
			log.warn("Scheduling the weekly review reminder {} failed", context, e)
		}
	}

	companion object {
		/** The single weekly review item that its reminders point at. */
		val ITEM = ItemRef(ItemType.WEEKLY_REVIEW, UUID.fromString("00000000-0000-7000-8000-00000000a001"))

		/** `pg_advisory_xact_lock` key of [sync] (any constant unique within the application). */
		private const val SYNC_LOCK = 0x5745_4b52_4556L
	}
}
