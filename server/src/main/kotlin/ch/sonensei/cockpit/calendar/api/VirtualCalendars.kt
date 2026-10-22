package ch.sonensei.cockpit.calendar.api

import java.time.LocalDate
import java.util.UUID

/**
 * One all-day occurrence of a virtual calendar: [id] is shown as its `eventId`, [seriesId] and [occurrenceDate] (the
 * original date) identify it within a yearly or other series. [endDate] is exclusive.
 */
data class VirtualOccurrence(
	val id: UUID,
	val title: String,
	val startDate: LocalDate,
	val endDate: LocalDate = startDate.plusDays(1),
	val seriesId: UUID? = null,
	val occurrenceDate: LocalDate? = null,
)

/**
 * Another module that computes the events of a read-only calendar instead of storing them (e.g. the birthdays of the
 * contacts, SPEC.md §8.3). Its calendar is a read-only calendar of the LOCAL source, created with
 * [CalendarService.ensureVirtualCalendar]; name, color and visibility change like any calendar's. Its occurrences are
 * part of `GET /calendar/events`; they have no stored event, so they cannot be read, changed or deleted one by one.
 */
interface VirtualCalendarProvider {
	/** Identifies the calendar of this provider; stable across releases. */
	val key: String

	/** The occurrences overlapping the days [fromDate, toDate) (exclusive end). */
	fun occurrences(
		fromDate: LocalDate,
		toDate: LocalDate,
	): List<VirtualOccurrence>
}
