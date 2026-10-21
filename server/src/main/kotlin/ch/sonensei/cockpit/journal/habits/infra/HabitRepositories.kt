package ch.sonensei.cockpit.journal.habits.infra

import ch.sonensei.cockpit.journal.habits.domain.Habit
import ch.sonensei.cockpit.journal.habits.domain.HabitCheck
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDate
import java.util.UUID

interface HabitRepository : JpaRepository<Habit, UUID> {
	/** Habits with the given archived flag, by name regardless of case, then id. */
	@Query("select h from Habit h where h.archived = :archived order by lower(h.name), h.id")
	fun findByArchived(
		@Param("archived") archived: Boolean,
	): List<Habit>

	/** The habit, locked until the end of the transaction, so changes of its checks run one after another. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select h from Habit h where h.id = :id")
	fun lockById(
		@Param("id") id: UUID,
	): Habit?

	/** Sets the habit's managed reminder, without a version check or bump; returns the number of updated rows. */
	@Modifying(flushAutomatically = true)
	@Query("update Habit h set h.reminderId = :reminderId where h.id = :id")
	fun setReminderId(
		@Param("id") id: UUID,
		@Param("reminderId") reminderId: UUID?,
	): Int
}

interface HabitCheckRepository : JpaRepository<HabitCheck, UUID> {
	fun findByHabitIdAndDate(
		habitId: UUID,
		date: LocalDate,
	): HabitCheck?

	/** Checks of a habit from [from] to [to] (both included), by date. */
	@Query("select c from HabitCheck c where c.habitId = :habitId and c.date between :from and :to order by c.date")
	fun findRange(
		@Param("habitId") habitId: UUID,
		@Param("from") from: LocalDate,
		@Param("to") to: LocalDate,
	): List<HabitCheck>

	/** Every check of the given habits. */
	fun findByHabitIdIn(habitIds: Collection<UUID>): List<HabitCheck>
}
