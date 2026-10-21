package ch.sonensei.cockpit.journal.entries.infra

import ch.sonensei.cockpit.core.persistence.SoftDeletableRepository
import ch.sonensei.cockpit.journal.entries.domain.JournalDay
import ch.sonensei.cockpit.journal.entries.domain.JournalEntry
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.repository.Query
import java.time.LocalDate

interface JournalEntryRepository :
	SoftDeletableRepository<JournalEntry>,
	JpaSpecificationExecutor<JournalEntry> {
	/** The entry of [date] that is not in the Trash. */
	@Query("select e from JournalEntry e where e.date = :date and e.deletedAt is null")
	fun findActiveByDate(date: LocalDate): JournalEntry?

	/** The dates from [from] to [to] (both included) with an entry not in the Trash, by date. */
	@Query(
		"select new ch.sonensei.cockpit.journal.entries.domain.JournalDay(e.date, e.mood) from JournalEntry e " +
			"where e.deletedAt is null and e.date between :from and :to order by e.date",
	)
	fun findDays(
		from: LocalDate,
		to: LocalDate,
	): List<JournalDay>

	/** The entries (not in the Trash) before [before] on [month]/one of [days] of any year, newest first. */
	@Query(
		value =
			"select * from journal_entry where deleted_at is null and entry_date < :before " +
				"and extract(month from entry_date) = :month and extract(day from entry_date) in (:days) " +
				"order by entry_date desc",
		nativeQuery = true,
	)
	fun findOnDayOfYear(
		before: LocalDate,
		month: Int,
		days: Collection<Int>,
	): List<JournalEntry>
}
