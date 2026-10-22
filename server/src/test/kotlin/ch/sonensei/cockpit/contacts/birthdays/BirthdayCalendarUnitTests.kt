package ch.sonensei.cockpit.contacts.birthdays

import ch.sonensei.cockpit.calendar.api.CalendarService
import ch.sonensei.cockpit.calendar.api.VirtualOccurrence
import ch.sonensei.cockpit.core.events.domain.ChangeEvent
import ch.sonensei.cockpit.core.events.domain.ChangeOp
import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.core.persistence.UuidV7
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * The date logic of the birthdays calendar without Spring (task contacts-birthdays, SPEC.md §8.3): February 29 in
 * leap and other years, ranges over a year boundary, exclusive ends, empty and inverted ranges, the birth year and the
 * age limit; and when the initializer creates the calendar.
 */
class BirthdayCalendarUnitTests {
	private val repository = mockk<BirthdayContactRepository>()
	private val provider = BirthdayCalendarProvider(repository)

	private fun withContacts(vararg contacts: BirthdayContact) {
		every { repository.findBirthdays() } returns contacts.toList()
	}

	private fun contact(
		name: String,
		birthday: String,
	) = BirthdayContact(UuidV7.generate(), name, LocalDate.parse(birthday))

	private fun dates(
		from: String,
		to: String,
	) = provider.occurrences(LocalDate.parse(from), LocalDate.parse(to)).map { it.startDate.toString() }

	@Test
	fun `the provider identifies the birthdays calendar`() {
		provider.key shouldBe "birthdays"
	}

	@Test
	fun `an occurrence is a one-day series member of the contact titled with name and age`() {
		val ada = contact("Ada Lovelace", "1990-12-10")
		withContacts(ada)

		provider.occurrences(LocalDate.parse("2026-12-10"), LocalDate.parse("2026-12-11")) shouldContainExactly
			listOf(
				VirtualOccurrence(
					id = ada.id,
					title = "🎂 Ada Lovelace (36)",
					startDate = LocalDate.parse("2026-12-10"),
					endDate = LocalDate.parse("2026-12-11"),
					seriesId = ada.id,
					occurrenceDate = LocalDate.parse("2026-12-10"),
				),
			)
	}

	@Test
	fun `February 29 falls on February 28 in non-leap years and stays on February 29 in leap years`() {
		withContacts(contact("Leap Person", "2000-02-29"))

		dates("2025-01-01", "2030-01-01") shouldContainExactly
			listOf("2025-02-28", "2026-02-28", "2027-02-28", "2028-02-29", "2029-02-28")
		// 2100 is not a leap year (divisible by 100, not by 400); 2400 is.
		dates("2100-01-01", "2101-01-01") shouldContainExactly listOf("2100-02-28")
		dates("2400-01-01", "2401-01-01") shouldContainExactly listOf("2400-02-29")
	}

	@Test
	fun `a February 29 birthday is not on March 1 and not twice in a leap year`() {
		withContacts(contact("Leap Person", "2000-02-29"))

		dates("2027-03-01", "2027-03-02").shouldBeEmpty()
		dates("2028-02-28", "2028-02-29").shouldBeEmpty()
		dates("2028-02-28", "2028-03-02") shouldContainExactly listOf("2028-02-29")
	}

	@Test
	fun `a range over the year boundary yields the birthdays of both years`() {
		withContacts(contact("New Year", "1980-01-01"), contact("Old Year", "1985-12-31"))

		dates("2026-12-31", "2027-01-02") shouldContainExactlyInAnyOrder listOf("2026-12-31", "2027-01-01")
		dates("2026-12-30", "2027-01-01") shouldContainExactly listOf("2026-12-31")
		dates("2027-01-01", "2027-01-02") shouldContainExactly listOf("2027-01-01")
	}

	@Test
	fun `the end of the range is exclusive and its start inclusive`() {
		withContacts(contact("Ada Lovelace", "1990-12-10"))

		dates("2026-12-10", "2026-12-11") shouldContainExactly listOf("2026-12-10")
		dates("2026-12-01", "2026-12-10").shouldBeEmpty()
		dates("2026-12-11", "2026-12-31").shouldBeEmpty()
	}

	@Test
	fun `a range over several years yields one occurrence per year with the age reached`() {
		withContacts(contact("Ada Lovelace", "1990-12-10"))

		provider
			.occurrences(LocalDate.parse("2026-01-01"), LocalDate.parse("2029-01-01"))
			.map { it.title } shouldContainExactly listOf("🎂 Ada Lovelace (36)", "🎂 Ada Lovelace (37)", "🎂 Ada Lovelace (38)")
	}

	@Test
	fun `empty and inverted ranges yield nothing without reading contacts`() {
		provider.occurrences(LocalDate.parse("2026-12-10"), LocalDate.parse("2026-12-10")).shouldBeEmpty()
		provider.occurrences(LocalDate.parse("2027-01-01"), LocalDate.parse("2026-01-01")).shouldBeEmpty()

		verify(exactly = 0) { repository.findBirthdays() }
	}

	@Test
	fun `no contacts with a birthday yield nothing`() {
		withContacts()

		dates("2026-01-01", "2027-01-01").shouldBeEmpty()
	}

	@Test
	fun `there is no occurrence in the year of birth or before`() {
		withContacts(contact("Baby", "2026-05-01"))

		dates("2025-01-01", "2027-01-01").shouldBeEmpty()
		dates("2027-01-01", "2028-01-01") shouldContainExactly listOf("2027-05-01")
	}

	@Test
	fun `the age is shown up to 130 years and omitted above`() {
		withContacts(contact("Very Old", "1896-03-01"), contact("Placeholder", "1895-03-02"))

		provider
			.occurrences(LocalDate.parse("2026-03-01"), LocalDate.parse("2026-03-03"))
			.map { it.title } shouldContainExactly listOf("🎂 Very Old (130)", "🎂 Placeholder")
	}

	@Test
	fun `the initializer creates the calendar at startup only when a contact has a birthday`() {
		val calendars = mockk<CalendarService>(relaxed = true)
		val initializer = BirthdayCalendarInitializer(repository, calendars)

		every { repository.existsByBirthdayIsNotNullAndDeletedAtIsNull() } returns false
		initializer.onStartup()
		verify(exactly = 0) { calendars.ensureVirtualCalendar(any(), any(), any()) }

		every { repository.existsByBirthdayIsNotNullAndDeletedAtIsNull() } returns true
		initializer.onStartup()
		verify(exactly = 1) { calendars.ensureVirtualCalendar("birthdays", "Birthdays", "#d81b60") }
	}

	@Test
	fun `the initializer reacts only to created or updated contacts with a birthday`() {
		val calendars = mockk<CalendarService>(relaxed = true)
		val initializer = BirthdayCalendarInitializer(repository, calendars)
		val withBirthday = UUID.randomUUID()
		every { repository.existsByIdAndBirthdayIsNotNullAndDeletedAtIsNull(any()) } returns false
		every { repository.existsByIdAndBirthdayIsNotNullAndDeletedAtIsNull(withBirthday) } returns true

		initializer.onContactChanged(ChangeEvent(ItemType.TASK, withBirthday, ChangeOp.CREATED, AT))
		initializer.onContactChanged(ChangeEvent(ItemType.CONTACT, withBirthday, ChangeOp.DELETED, AT))
		initializer.onContactChanged(ChangeEvent(ItemType.CONTACT, UUID.randomUUID(), ChangeOp.UPDATED, AT))
		verify(exactly = 0) { calendars.ensureVirtualCalendar(any(), any(), any()) }
		verify(exactly = 1) { repository.existsByIdAndBirthdayIsNotNullAndDeletedAtIsNull(any()) }

		initializer.onContactChanged(ChangeEvent(ItemType.CONTACT, withBirthday, ChangeOp.CREATED, AT))
		initializer.onContactChanged(ChangeEvent(ItemType.CONTACT, withBirthday, ChangeOp.UPDATED, AT))
		verify(exactly = 2) { calendars.ensureVirtualCalendar("birthdays", "Birthdays", "#d81b60") }
	}

	private companion object {
		val AT: Instant = Instant.parse("2026-10-22T08:00:00Z")
	}
}
