package ch.sonensei.cockpit.calendar.api

import ch.sonensei.cockpit.calendar.model.Attendee
import ch.sonensei.cockpit.calendar.model.CalendarEvent
import ch.sonensei.cockpit.calendar.model.EventStatus
import ch.sonensei.cockpit.calendar.model.EventTransparency
import ch.sonensei.cockpit.calendar.model.OccurrenceId
import ch.sonensei.cockpit.core.web.FieldError
import ch.sonensei.cockpit.core.web.InvalidRequestException
import java.time.DateTimeException
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID

/** A field that a request sets to [value] (`null` clears it); a `null` [FieldChange] means the field was not sent. */
data class FieldChange<T>(
	val value: T,
)

/** The fields of an event create or PATCH; a `null` property was not sent. */
data class EventChanges(
	val calendarId: FieldChange<UUID>? = null,
	val title: FieldChange<String>? = null,
	val description: FieldChange<String?>? = null,
	val location: FieldChange<String?>? = null,
	val timing: TimingInput? = null,
	val rrule: FieldChange<String?>? = null,
	val attendees: FieldChange<List<Attendee>>? = null,
	val reminderMinutes: FieldChange<List<Int>>? = null,
	val status: FieldChange<EventStatus>? = null,
	val transparency: FieldChange<EventTransparency>? = null,
	val conferenceUrl: FieldChange<String?>? = null,
)

/** The time fields of a request, each `null` when not sent; [Timing.resolve] completes them from the current times. */
data class TimingInput(
	val allDay: Boolean? = null,
	val start: Instant? = null,
	val end: Instant? = null,
	val timeZone: String? = null,
	val startDate: LocalDate? = null,
	val endDate: LocalDate? = null,
) {
	val isSent: Boolean get() = listOf(allDay, start, end, timeZone, startDate, endDate).any { it != null }
}

/** When an event (or one occurrence) takes place: [Timed] or [AllDay]. */
sealed interface Timing {
	/** The start as wall-clock time: in the event's zone for a timed event, midnight of the first day for an all-day one. */
	val localStart: LocalDateTime

	/** From [start] to [end] (not before [start]), planned in [zone]. */
	data class Timed(
		val start: Instant,
		val end: Instant,
		val zone: ZoneId,
	) : Timing {
		val duration: Duration get() = Duration.between(start, end)
		override val localStart: LocalDateTime get() = start.atZone(zone).toLocalDateTime()
	}

	/** From [startDate] to the exclusive [endDate] (after [startDate]). */
	data class AllDay(
		val startDate: LocalDate,
		val endDate: LocalDate,
	) : Timing {
		val days: Long get() = ChronoUnit.DAYS.between(startDate, endDate)
		override val localStart: LocalDateTime get() = startDate.atStartOfDay()
	}

	/** The occurrence of a series with these (master) times that originally starts at [occurrence]. */
	fun occurrence(occurrence: OccurrenceId): Timing =
		when (this) {
			is Timed -> occurrence.at!!.let { Timed(it, it.plus(duration), zone) }
			is AllDay -> occurrence.date!!.let { AllDay(it, it.plusDays(days)) }
		}

	/** The id of the occurrence starting at these times (the first occurrence of a series master). */
	fun occurrenceId(): OccurrenceId =
		when (this) {
			is Timed -> OccurrenceId.of(start)
			is AllDay -> OccurrenceId.of(startDate)
		}

	/**
	 * These times moved like [from] moved to [to] (e.g. a series master when one of its occurrences moved): a timed
	 * result keeps the wall-clock offset to [to] in [to]'s zone and takes [to]'s duration, an all-day result moves by
	 * the days [to] moved and takes [to]'s length.
	 */
	fun shifted(
		from: Timing,
		to: Timing,
	): Timing =
		when (to) {
			is Timed -> {
				val start = localStart.plus(Duration.between(from.localStart, to.localStart)).atZone(to.zone).toInstant()
				Timed(start, start.plus(to.duration), to.zone)
			}
			is AllDay -> {
				val start = localStart.toLocalDate().plusDays(ChronoUnit.DAYS.between(from.localStart.toLocalDate(), to.startDate))
				AllDay(start, start.plusDays(to.days))
			}
		}

	/** [id] (an occurrence of a series at [from]) for the same series at [to]; null when the kind (timed/all-day) changes. */
	fun moveOccurrenceId(
		id: OccurrenceId,
		to: Timing,
	): OccurrenceId? {
		val from = this
		val at = id.at
		val date = id.date
		return when {
			from is Timed && to is Timed && at != null -> {
				val local = at.atZone(from.zone).toLocalDateTime().plus(Duration.between(from.localStart, to.localStart))
				OccurrenceId.of(local.atZone(to.zone).toInstant().truncatedTo(ChronoUnit.MICROS))
			}
			from is AllDay && to is AllDay && date != null -> OccurrenceId.of(date.plusDays(ChronoUnit.DAYS.between(from.startDate, to.startDate)))
			else -> null
		}
	}

	companion object {
		const val DEFAULT_DURATION_MINUTES = 60L

		fun of(event: CalendarEvent): Timing =
			if (event.allDay) {
				AllDay(event.startDate!!, event.endDate!!)
			} else {
				Timed(event.startAt!!, event.endAt!!, ZoneId.of(event.timeZone))
			}

		/**
		 * The times [input] asks for, with the fields not sent taken from [current] (null for a new event):
		 * the zone defaults to [defaultZone], the end keeps the current duration (one hour, or one day for all-day).
		 * Turning a timed event all-day without dates takes the day of its start.
		 *
		 * @throws InvalidRequestException (400) for missing, contradicting or invalid fields
		 */
		fun resolve(
			current: Timing?,
			input: TimingInput,
			defaultZone: ZoneId,
		): Timing {
			val allDay = input.allDay ?: (current is AllDay)
			val errors = mutableListOf<FieldError>()
			if (allDay) {
				if (input.start != null) errors += FieldError("start", "is not allowed for an all-day event")
				if (input.end != null) errors += FieldError("end", "is not allowed for an all-day event")
				if (input.timeZone != null) errors += FieldError("timeZone", "is not allowed for an all-day event")
				val start =
					input.startDate
						?: when (current) {
							is AllDay -> current.startDate
							is Timed -> current.start.atZone(current.zone).toLocalDate()
							null -> null
						}
				if (start == null) errors += FieldError("startDate", "is required")
				val end = input.endDate ?: start?.plusDays((current as? AllDay)?.days ?: 1)
				if (start != null && end != null && !end.isAfter(start)) {
					errors += FieldError("endDate", "must be after startDate (it is exclusive)")
				}
				if (errors.isNotEmpty()) throw InvalidRequestException(errors)
				return AllDay(start!!, end!!)
			}
			if (input.startDate != null) errors += FieldError("startDate", "is only allowed for an all-day event")
			if (input.endDate != null) errors += FieldError("endDate", "is only allowed for an all-day event")
			val zone =
				try {
					input.timeZone?.let(ZoneId::of) ?: (current as? Timed)?.zone ?: defaultZone
				} catch (e: DateTimeException) {
					errors += FieldError("timeZone", "is not a known time zone")
					defaultZone
				}
			val start = input.start?.truncatedTo(ChronoUnit.MICROS) ?: (current as? Timed)?.start
			if (start == null) errors += FieldError("start", "is required")
			val duration = (current as? Timed)?.duration ?: Duration.ofMinutes(DEFAULT_DURATION_MINUTES)
			val end = input.end?.truncatedTo(ChronoUnit.MICROS) ?: start?.plus(duration)
			if (start != null && end != null && end.isBefore(start)) errors += FieldError("end", "must not be before start")
			if (errors.isNotEmpty()) throw InvalidRequestException(errors)
			return Timed(start!!, end!!, zone)
		}
	}
}

/** Sets the times of the event. */
fun CalendarEvent.setTiming(timing: Timing) {
	when (timing) {
		is Timing.Timed -> setTimed(timing.start, timing.end, timing.zone)
		is Timing.AllDay -> setAllDay(timing.startDate, timing.endDate)
	}
}

/** Original starts in time order; both are of the same kind within one series. */
operator fun OccurrenceId.compareTo(other: OccurrenceId): Int {
	val mine = at
	val theirs = other.at
	return if (mine != null && theirs != null) mine.compareTo(theirs) else compareValues(date, other.date)
}

/** The content of an event apart from its times, calendar and recurrence; used to copy and to propagate changes. */
data class EventContent(
	val title: String,
	val description: String?,
	val location: String?,
	val attendees: List<Attendee>,
	val reminderMinutes: List<Int>,
	val status: EventStatus,
	val transparency: EventTransparency,
	val conferenceUrl: String?,
) {
	fun applyTo(event: CalendarEvent) {
		event.title = title
		event.description = description
		event.location = location
		event.attendees = attendees
		event.reminderMinutes = reminderMinutes
		event.status = status
		event.transparency = transparency
		event.conferenceUrl = conferenceUrl
	}

	/** Gives [event] (an override) each field that changed from [before] to this, where it still had the old value. */
	fun propagate(
		before: EventContent,
		event: CalendarEvent,
	) {
		if (event.title == before.title) event.title = title
		if (event.description == before.description) event.description = description
		if (event.location == before.location) event.location = location
		if (event.attendees == before.attendees) event.attendees = attendees
		if (event.reminderMinutes == before.reminderMinutes) event.reminderMinutes = reminderMinutes
		if (event.status == before.status) event.status = status
		if (event.transparency == before.transparency) event.transparency = transparency
		if (event.conferenceUrl == before.conferenceUrl) event.conferenceUrl = conferenceUrl
	}

	companion object {
		fun of(event: CalendarEvent) =
			EventContent(
				event.title,
				event.description,
				event.location,
				event.attendees,
				event.reminderMinutes,
				event.status,
				event.transparency,
				event.conferenceUrl,
			)
	}
}
