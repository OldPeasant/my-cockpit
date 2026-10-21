package ch.sonensei.cockpit.calendar.agenda

import ch.sonensei.cockpit.core.network.generated.models.CalendarResponse
import ch.sonensei.cockpit.core.network.generated.models.OccurrenceResponse
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** A calendar of any source (SPEC.md §6.1), as the views and the visibility toggles need it. */
data class CalendarInfo(
    val id: UUID,
    val name: String,
    /** ARGB, from the calendar's CSS hex color; `null` when it has none (or an unreadable one). */
    val color: Long?,
    /** Its events are shown in the views. */
    val visible: Boolean,
    val readOnly: Boolean,
    /** Sent back as `If-Match` when the calendar is changed. */
    val version: Long,
)

/** One occurrence of an event: a single event, or one instance of an expanded series. */
data class Occurrence(
    val eventId: UUID,
    val calendarId: UUID,
    val title: String,
    val location: String?,
    val allDay: Boolean,
    /** Timed occurrences: the start and the (exclusive) end. */
    val start: Instant?,
    val end: Instant?,
    /** All-day occurrences: the first day and the day after the last one. */
    val startDate: LocalDate?,
    val endDate: LocalDate?,
    val cancelled: Boolean = false,
    val seriesId: UUID? = null,
    /** The original start of a series occurrence, which identifies it for edits. */
    val occurrence: String? = null,
    val version: Long = 1,
) {
    /** Unique within a range: an event appears once per occurrence. */
    val key: String get() = "$eventId/${occurrence ?: start ?: startDate}"

    /** The first and the last day (inclusive) it touches in [zone]. */
    fun days(zone: ZoneId): ClosedRange<LocalDate> {
        if (allDay || start == null) {
            val first = startDate ?: start?.atZone(zone)?.toLocalDate() ?: NO_DATE
            val last = endDate?.minusDays(1)?.takeIf { it >= first } ?: first
            return first..last
        }
        val first = start.atZone(zone).toLocalDate()
        // The end is exclusive: an event ending at midnight does not touch the next day.
        val last = end?.takeIf { it > start }?.let { it.minusNanos(1).atZone(zone).toLocalDate() } ?: first
        return first..last
    }
}

/** One day of the agenda with what happens on it: all-day occurrences first, then by start. */
data class AgendaDay(
    val date: LocalDate,
    val entries: List<DayEntry>,
)

/** An occurrence as it appears on one day; a timed one spanning days is cut to the part on that day. */
data class DayEntry(
    val occurrence: Occurrence,
    val date: LocalDate,
    /** Minutes after midnight on [date]; 0 and [MINUTES_PER_DAY] for all-day entries. */
    val startMinute: Int,
    val endMinute: Int,
    /** It started on an earlier day / ends on a later one. */
    val continuesBefore: Boolean,
    val continuesAfter: Boolean,
) {
    val allDay: Boolean get() = occurrence.allDay
}

const val MINUTES_PER_DAY = 24 * 60

/** Stands for a date not known yet (`LocalDate.EPOCH` needs API 34). */
val NO_DATE: LocalDate = LocalDate.of(1970, 1, 1)

/** The entries of [occurrence] on [date] in [zone], or `null` when it does not touch that day. */
fun entryOn(
    occurrence: Occurrence,
    date: LocalDate,
    zone: ZoneId,
): DayEntry? {
    val days = occurrence.days(zone)
    if (date !in days) return null
    val before = date > days.start
    val after = date < days.endInclusive
    if (occurrence.allDay || occurrence.start == null) return DayEntry(occurrence, date, 0, MINUTES_PER_DAY, before, after)
    val startMinute = if (before) 0 else minuteOfDay(occurrence.start, zone)
    val endMinute =
        if (after) {
            MINUTES_PER_DAY
        } else {
            val end = occurrence.end?.takeIf { it > occurrence.start } ?: occurrence.start
            // Ending exactly at midnight of the next day was handled by days(); here the end is on this day.
            if (end.atZone(zone).toLocalDate() > date) MINUTES_PER_DAY else minuteOfDay(end, zone)
        }
    return DayEntry(occurrence, date, startMinute, maxOf(endMinute, startMinute), before, after)
}

private fun minuteOfDay(
    instant: Instant,
    zone: ZoneId,
): Int {
    val time = instant.atZone(zone).toLocalTime()
    return time.hour * 60 + time.minute
}

/** The entries of every occurrence on [date]: all-day first, then by start, then by title. */
fun entriesOn(
    occurrences: List<Occurrence>,
    date: LocalDate,
    zone: ZoneId,
): List<DayEntry> = occurrences.mapNotNull { entryOn(it, date, zone) }.sortedWith(entryOrder)

private val entryOrder =
    compareBy<DayEntry>({ !it.allDay }, { it.startMinute }, { it.endMinute }, { it.occurrence.title.lowercase() }, { it.occurrence.key })

/**
 * Groups [occurrences] by the days in [from, to) they touch in [zone] (an occurrence spanning several days appears
 * on each); days without occurrences are left out. Days are in order, entries as in [entriesOn].
 */
fun groupByDay(
    occurrences: List<Occurrence>,
    from: LocalDate,
    to: LocalDate,
    zone: ZoneId,
): List<AgendaDay> {
    val byDay = sortedMapOf<LocalDate, MutableList<DayEntry>>()
    for (occurrence in occurrences.distinctBy { it.key }) {
        val days = occurrence.days(zone)
        var day = maxOf(days.start, from)
        val last = minOf(days.endInclusive, to.minusDays(1))
        while (day <= last) {
            entryOn(occurrence, day, zone)?.let { byDay.getOrPut(day) { mutableListOf() } += it }
            day = day.plusDays(1)
        }
    }
    return byDay.map { (date, entries) -> AgendaDay(date, entries.sortedWith(entryOrder)) }
}

/** A timed entry placed in the day grid: in [lane] of [lanes] side-by-side columns of its overlap group. */
data class PlacedEntry(
    val entry: DayEntry,
    val lane: Int,
    val lanes: Int,
)

/** Shortest height in minutes a timed entry gets in the grid, so a zero-length event can still be seen and tapped. */
const val MIN_ENTRY_MINUTES = 20

/**
 * Lays out the timed entries of one day: overlapping entries share the width in lanes, each in the first free lane;
 * a group of entries that overlap each other (directly or through others) all get the same number of lanes.
 */
fun layoutDay(entries: List<DayEntry>): List<PlacedEntry> {
    val timed = entries.filter { !it.allDay }.sortedWith(entryOrder)
    val placed = mutableListOf<PlacedEntry>()
    var group = mutableListOf<Pair<DayEntry, Int>>()
    val laneEnds = mutableListOf<Int>()
    var groupEnd = Int.MIN_VALUE

    fun closeGroup() {
        val lanes = laneEnds.size.coerceAtLeast(1)
        group.mapTo(placed) { (entry, lane) -> PlacedEntry(entry, lane, lanes) }
        group = mutableListOf()
        laneEnds.clear()
    }

    for (entry in timed) {
        val end = visibleEnd(entry)
        if (entry.startMinute >= groupEnd) {
            closeGroup()
            groupEnd = Int.MIN_VALUE
        }
        val lane = laneEnds.indexOfFirst { it <= entry.startMinute }.takeIf { it >= 0 } ?: laneEnds.size
        if (lane == laneEnds.size) laneEnds += end else laneEnds[lane] = end
        group += entry to lane
        groupEnd = maxOf(groupEnd, end)
    }
    closeGroup()
    return placed
}

/** The end as drawn: at least [MIN_ENTRY_MINUTES] after the start, never past midnight. */
fun visibleEnd(entry: DayEntry): Int = minOf(maxOf(entry.endMinute, entry.startMinute + MIN_ENTRY_MINUTES), MINUTES_PER_DAY)

/** The days of the week containing [date], starting on [firstDay]. */
fun weekOf(
    date: LocalDate,
    firstDay: DayOfWeek,
): List<LocalDate> {
    val offset = (date.dayOfWeek.value - firstDay.value + 7) % 7
    val start = date.minusDays(offset.toLong())
    return (0L until 7L).map { start.plusDays(it) }
}

internal fun CalendarResponse.toCalendar() =
    CalendarInfo(
        id = id,
        name = name,
        color = color?.let(::parseColor),
        visible = visible,
        readOnly = readOnly,
        version = version,
    )

internal fun OccurrenceResponse.toOccurrence() =
    Occurrence(
        eventId = eventId,
        calendarId = calendarId,
        title = title,
        location = location?.takeIf { it.isNotBlank() },
        allDay = allDay,
        start = start?.toInstant(),
        end = end?.toInstant(),
        startDate = startDate,
        endDate = endDate,
        cancelled = status == OccurrenceResponse.Status.CANCELLED,
        seriesId = seriesId,
        occurrence = occurrence,
        version = version,
    )

/** `#rgb` or `#rrggbb` (CSS hex) as opaque ARGB; anything else is `null`. */
internal fun parseColor(css: String): Long? {
    val hex = css.trim().removePrefix("#")
    val full =
        when {
            hex.length == 3 -> hex.map { "$it$it" }.joinToString("")
            hex.length == 6 -> hex
            else -> return null
        }
    return full.toLongOrNull(16)?.let { 0xFF000000L or it }
}

internal fun etag(version: Long) = "\"$version\""
