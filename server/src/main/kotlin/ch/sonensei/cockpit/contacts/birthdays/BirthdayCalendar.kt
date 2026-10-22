package ch.sonensei.cockpit.contacts.birthdays

import ch.sonensei.cockpit.calendar.api.VirtualCalendarProvider
import ch.sonensei.cockpit.calendar.api.VirtualOccurrence
import ch.sonensei.cockpit.contacts.domain.Contact
import org.springframework.data.repository.Repository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.util.UUID

/** The contacts with a birthday, read by the birthdays calendar. */
interface BirthdayContactRepository : Repository<Contact, UUID> {
	fun findByBirthdayIsNotNullAndDeletedAtIsNull(): List<Contact>

	fun existsByBirthdayIsNotNullAndDeletedAtIsNull(): Boolean

	fun existsByIdAndBirthdayIsNotNullAndDeletedAtIsNull(id: UUID): Boolean
}

/**
 * The virtual read-only birthdays calendar (SPEC.md §8.3): every contact not in the Trash with a birthday has an
 * all-day occurrence on it each year after the year of birth, titled with the name and the age reached. A birthday on
 * February 29 falls on February 28 in other years. The occurrences of a contact form a series: `eventId` and
 * `seriesId` are the contact's id, `occurrence` the date.
 */
@Component
@Transactional(readOnly = true)
class BirthdayCalendarProvider(
	private val contacts: BirthdayContactRepository,
) : VirtualCalendarProvider {
	override val key = KEY

	override fun occurrences(
		fromDate: LocalDate,
		toDate: LocalDate,
	): List<VirtualOccurrence> {
		if (!toDate.isAfter(fromDate)) return emptyList()
		val years = fromDate.year..toDate.minusDays(1).year
		return contacts.findByBirthdayIsNotNullAndDeletedAtIsNull().flatMap { contact ->
			val birthday = contact.birthday!!
			years
				.filter { it > birthday.year }
				.map { year -> birthday.withYear(year) }
				.filter { it >= fromDate && it < toDate }
				.map { date -> VirtualOccurrence(contact.id, title(contact, birthday, date), date, seriesId = contact.id, occurrenceDate = date) }
		}
	}

	private fun title(
		contact: Contact,
		birthday: LocalDate,
		date: LocalDate,
	): String {
		val age = date.year - birthday.year
		// vCards use placeholder years (e.g. 1604) for birthdays without a year: no age for them.
		return if (age <= MAX_AGE) "🎂 ${contact.displayName} ($age)" else "🎂 ${contact.displayName}"
	}

	companion object {
		const val KEY = "birthdays"
		const val NAME = "Birthdays"
		const val COLOR = "#d81b60"
		private const val MAX_AGE = 130
	}
}
