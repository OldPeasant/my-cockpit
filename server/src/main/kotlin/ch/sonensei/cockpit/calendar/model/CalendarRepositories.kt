package ch.sonensei.cockpit.calendar.model

import ch.sonensei.cockpit.core.persistence.SoftDeletableRepository
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

interface CalendarSourceRepository : JpaRepository<CalendarSource, UUID> {
	fun findAllByOrderByIdAsc(): List<CalendarSource>
}

interface CalendarRepository : JpaRepository<Calendar, UUID> {
	fun findBySourceIdOrderByIdAsc(sourceId: UUID): List<Calendar>

	fun findBySourceIdAndExternalId(
		sourceId: UUID,
		externalId: String,
	): Calendar?

	fun findByIsDefaultTrue(): Calendar?
}

interface CalendarEventRepository : SoftDeletableRepository<CalendarEvent> {
	/**
	 * Events of [calendarIds] that may have an occurrence in [from, to): single events overlapping the range, every
	 * series master starting before [to] (its expansion decides), and all overrides of those calendars (an override
	 * can move an occurrence into the range). Timed events compare instants, all-day events the dates
	 * [fromDate, toDate) (the range in the user's zone). Not deleted; an override also not when its series master is
	 * deleted (it shares the master's Trash state).
	 */
	@Query(
		"""
		select e from CalendarEvent e
		where e.calendarId in :calendarIds and e.deletedAt is null and (
			(e.parentEventId is not null
				and exists (select 1 from CalendarEvent m where m.id = e.parentEventId and m.deletedAt is null))
			or (e.parentEventId is null and (
				(e.allDay = false and e.startAt < :to and (e.rrule is not null or e.endAt > :from or (e.endAt = e.startAt and e.startAt >= :from)))
				or (e.allDay = true and e.startDate < :toDate and (e.rrule is not null or e.endDate > :fromDate))
			))
		)
		order by e.id
		""",
	)
	fun findCandidatesInRange(
		calendarIds: Collection<UUID>,
		from: Instant,
		to: Instant,
		fromDate: LocalDate,
		toDate: LocalDate,
	): List<CalendarEvent>

	/** The overrides of a series, deleted or not. */
	fun findByParentEventIdOrderByIdAsc(parentEventId: UUID): List<CalendarEvent>

	/**
	 * The series master or single event with the provider id [externalId]. Overrides are not returned: in iCalendar
	 * they share their master's UID and are identified by parent and recurrence id.
	 */
	fun findByCalendarIdAndExternalIdAndParentEventIdIsNull(
		calendarId: UUID,
		externalId: String,
	): CalendarEvent?

	/** Ids of every event of a calendar, deleted or not (e.g. to unindex them before the calendar is removed). */
	@Query("select e.id from CalendarEvent e where e.calendarId = :calendarId")
	fun findIdsByCalendarId(calendarId: UUID): List<UUID>
}
