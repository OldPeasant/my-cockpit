package ch.sonensei.cockpit.calendar.recurrence

import ch.sonensei.cockpit.calendar.model.CalendarEvent
import ch.sonensei.cockpit.calendar.model.EventStatus
import ch.sonensei.cockpit.calendar.model.OccurrenceId
import net.fortuna.ical4j.model.Recur
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * The time range [[from], [to]) to expand, seen from [zone] (the user's zone): timed occurrences overlap it by
 * instants, all-day occurrences by the days [fromDate, toDate) that the range touches in [zone].
 */
data class OccurrenceRange(
	val from: Instant,
	val to: Instant,
	val zone: ZoneId,
) {
	init {
		require(from.isBefore(to)) { "The range must end after it starts" }
	}

	/** The day of [from] in [zone]. */
	val fromDate: LocalDate get() = from.atZone(zone).toLocalDate()

	/** The day after the last day the range touches in [zone] (exclusive). */
	val toDate: LocalDate
		get() {
			val end = to.atZone(zone)
			val day = end.toLocalDate()
			return if (end == day.atStartOfDay(zone)) day else day.plusDays(1)
		}
}

/**
 * One occurrence of an event in a range. [event] provides its data (title, location, ...): the series master for a
 * generated occurrence, the override for a replaced one, the event itself for a single event.
 *
 * [seriesId] and [occurrenceId] (the original start) identify an occurrence of a series (both null for a single
 * event, and for an override whose master is not part of the expansion). Timed occurrences have [start]/[end],
 * all-day occurrences [startDate] and the exclusive [endDate].
 */
data class Occurrence(
	val event: CalendarEvent,
	val seriesId: UUID?,
	val occurrenceId: OccurrenceId?,
	val start: Instant?,
	val end: Instant?,
	val startDate: LocalDate?,
	val endDate: LocalDate?,
) {
	val allDay: Boolean get() = startDate != null
}

/**
 * Expands events into their occurrences in a range (SPEC.md §6.1), using ical4j for the RRULE.
 *
 * A timed series recurs at the wall-clock time of its start in the event's time zone (so a weekly 09:00 meeting in
 * Europe/Zurich stays at 09:00 across DST changes), each occurrence lasting as long as the master. An all-day series
 * recurs by date. The start of the master is always the first occurrence (RFC 5545). EXDATEs remove occurrences; an
 * override replaces the occurrence of its recurrence id with its own times, a [EventStatus.CANCELLED] override removes
 * it. An override is shown wherever its own times are, even if its original occurrence is outside the range.
 */
@Component
class OccurrenceExpander {
	/**
	 * Expands [events] (single events, series masters and overrides, e.g. the range candidates of
	 * `CalendarEventRepository.findCandidatesInRange`) into the occurrences overlapping [range], ordered by start.
	 * Deleted events are ignored. Overrides are matched to masters in [events]; an override whose master is missing is
	 * shown on its own.
	 */
	fun expand(
		events: Collection<CalendarEvent>,
		range: OccurrenceRange,
	): List<Occurrence> {
		val live = events.filter { !it.isDeleted }
		val masters = live.filter { !it.isOverride }
		val masterIds = masters.mapTo(HashSet()) { it.id }
		val overridesByMaster = live.filter { it.isOverride }.groupBy { it.parentEventId }
		val occurrences = masters.flatMap { expandSeries(it, overridesByMaster[it.id].orEmpty(), range) }
		val orphans =
			overridesByMaster
				.filterKeys { it !in masterIds }
				.values
				.flatten()
				.mapNotNull { override -> asOccurrence(override, seriesId = null, occurrenceId = null)?.takeIf { overlaps(it, range) } }
		return (occurrences + orphans).sortedWith(compareBy({ sortKey(it, range.zone) }, { it.event.id }))
	}

	/**
	 * The occurrences of [event] (a single event or a series master) in [range], with [overrides] (of this series)
	 * applied. A single event yields at most one occurrence.
	 */
	fun expandSeries(
		event: CalendarEvent,
		overrides: Collection<CalendarEvent>,
		range: OccurrenceRange,
	): List<Occurrence> {
		require(!event.isOverride) { "Expand the series master, not an override" }
		if (event.isDeleted) return emptyList()
		val rule = event.rrule
		if (rule == null) {
			val single = asOccurrence(event, seriesId = null, occurrenceId = null) ?: return emptyList()
			return if (overlaps(single, range)) listOf(single) else emptyList()
		}
		val liveOverrides = overrides.filter { !it.isDeleted && it.parentEventId == event.id }
		val replaced = liveOverrides.mapNotNullTo(HashSet()) { it.recurrenceId }
		val generated =
			originalStarts(event, rule, range)
				.filter { it !in event.exdates && it !in replaced }
				.mapNotNull { seriesOccurrence(event, it) }
				.filter { overlaps(it, range) }
		val overridden =
			liveOverrides
				.filter { it.status != EventStatus.CANCELLED }
				.mapNotNull { asOccurrence(it, seriesId = event.id, occurrenceId = it.recurrenceId) }
				.filter { overlaps(it, range) }
		return (generated + overridden).sortedWith(compareBy({ sortKey(it, range.zone) }, { it.event.id }))
	}

	/** The original starts of the series' occurrences that may overlap [range] (a superset, filtered by the caller). */
	private fun originalStarts(
		master: CalendarEvent,
		rule: String,
		range: OccurrenceRange,
	): List<OccurrenceId> {
		if (master.allDay) {
			val seed = master.startDate ?: return emptyList()
			val days = ChronoUnit.DAYS.between(seed, master.endDate ?: seed.plusDays(1))
			val periodStart = range.fromDate.minusDays(days)
			val periodEnd = range.toDate
			if (!seed.isBefore(periodEnd)) return emptyList()
			val dates = Recur<LocalDate>(rule).getDates(seed, maxOf(seed, periodStart), periodEnd)
			return (listOf(seed) + dates).distinct().map { OccurrenceId.of(it) }
		}
		val start = master.startAt ?: return emptyList()
		val duration = Duration.between(start, master.endAt ?: start)
		val zone = zoneOf(master)
		val seed = start.atZone(zone)
		val periodStart = range.from.minus(duration).atZone(zone)
		val periodEnd = range.to.atZone(zone)
		if (!seed.isBefore(periodEnd)) return emptyList()
		val dates = Recur<ZonedDateTime>(rule).getDates(seed, maxOf(seed, periodStart), periodEnd)
		return (listOf(seed) + dates).map { it.toInstant() }.distinct().map { OccurrenceId.of(it) }
	}

	/** The generated occurrence of [master] originally starting at [id], with the master's duration. */
	private fun seriesOccurrence(
		master: CalendarEvent,
		id: OccurrenceId,
	): Occurrence? {
		val at = id.at
		val date = id.date
		return if (master.allDay && date != null) {
			val days = ChronoUnit.DAYS.between(master.startDate, master.endDate)
			Occurrence(master, master.id, id, null, null, date, date.plusDays(days))
		} else if (!master.allDay && at != null) {
			val duration = Duration.between(master.startAt, master.endAt)
			Occurrence(master, master.id, id, at, at.plus(duration), null, null)
		} else {
			null
		}
	}

	/** [event] at its own times; null if it has none (an inconsistent row). */
	private fun asOccurrence(
		event: CalendarEvent,
		seriesId: UUID?,
		occurrenceId: OccurrenceId?,
	): Occurrence? {
		if (event.allDay) {
			val start = event.startDate ?: return null
			return Occurrence(event, seriesId, occurrenceId, null, null, start, event.endDate ?: start.plusDays(1))
		}
		val start = event.startAt ?: return null
		return Occurrence(event, seriesId, occurrenceId, start, event.endAt ?: start, null, null)
	}

	private fun overlaps(
		occurrence: Occurrence,
		range: OccurrenceRange,
	): Boolean {
		if (occurrence.allDay) {
			return occurrence.startDate!! < range.toDate && occurrence.endDate!! > range.fromDate
		}
		val start = occurrence.start!!
		val end = occurrence.end!!
		// An instant (zero-length) event belongs to the range it starts in.
		return start < range.to && (end > range.from || (end == start && start >= range.from))
	}

	private fun sortKey(
		occurrence: Occurrence,
		zone: ZoneId,
	): Instant = occurrence.start ?: occurrence.startDate!!.atStartOfDay(zone).toInstant()

	private fun zoneOf(event: CalendarEvent): ZoneId = event.timeZone?.let(ZoneId::of) ?: ZoneOffset.UTC
}
