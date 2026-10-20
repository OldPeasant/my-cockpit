package ch.sonensei.cockpit.core.reminders.infra

import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.core.reminders.domain.Reminder
import jakarta.persistence.LockModeType
import jakarta.persistence.QueryHint
import org.springframework.data.domain.Limit
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.jpa.repository.QueryHints
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

interface ReminderRepository : JpaRepository<Reminder, UUID> {
	/** Reminders of an item, earliest first. */
	fun findByItemTypeAndItemIdOrderByRemindAtAscIdAsc(
		itemType: ItemType,
		itemId: UUID,
	): List<Reminder>

	/** Reminders not fired yet that are due at or before [until] (also overdue ones), earliest first. */
	@Query("select r from Reminder r where r.firedAt is null and r.remindAt <= :until order by r.remindAt, r.id")
	fun findPending(
		@Param("until") until: Instant,
		limit: Limit,
	): List<Reminder>

	/**
	 * Like [findPending], locked for firing. Rows another transaction is firing right now are skipped (`SKIP LOCKED`),
	 * so concurrent schedulers never fire the same reminder twice.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@QueryHints(QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
	@Query("select r from Reminder r where r.firedAt is null and r.remindAt <= :now order by r.remindAt, r.id")
	fun lockDue(
		@Param("now") now: Instant,
		limit: Limit,
	): List<Reminder>

	/** Deletes one reminder by id without a version check; returns the number of deleted rows (0 or 1). */
	@Modifying(flushAutomatically = true)
	@Query("delete from Reminder r where r.id = :id")
	fun deleteReminder(
		@Param("id") id: UUID,
	): Int

	// No clearAutomatically: this runs in the publisher's transaction and must not detach the entities it loaded.
	@Modifying(flushAutomatically = true)
	@Query("delete from Reminder r where r.itemType = :type and r.itemId = :id")
	fun deleteByItem(
		@Param("type") type: ItemType,
		@Param("id") id: UUID,
	): Int
}
