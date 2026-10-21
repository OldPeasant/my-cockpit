package ch.sonensei.cockpit.calendar.agenda

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** Grouping occurrences by day, cutting events that span days, and laying out overlaps. */
class AgendaGroupingTest {
    private val zurich = ZoneId.of("Europe/Zurich")
    private val calendar = UUID.fromString("00000000-0000-7000-8000-000000000099")

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private fun timed(
        n: Int,
        start: String,
        end: String,
        title: String = "Event $n",
    ) = Occurrence(id(n), calendar, title, null, false, Instant.parse(start), Instant.parse(end), null, null)

    private fun allDay(
        n: Int,
        start: String,
        endExclusive: String,
        title: String = "Event $n",
    ) = Occurrence(id(n), calendar, title, null, true, null, null, LocalDate.parse(start), LocalDate.parse(endExclusive))

    private fun day(s: String) = LocalDate.parse(s)

    @Test
    fun `occurrences are grouped by local day, all-day first, then by start`() {
        val late = timed(1, "2026-10-21T16:00:00Z", "2026-10-21T17:00:00Z", "Dentist")
        val early = timed(2, "2026-10-21T06:30:00Z", "2026-10-21T07:00:00Z", "Standup")
        val holiday = allDay(3, "2026-10-21", "2026-10-22", "Holiday")
        // 23:30 UTC is already the next day in Zurich (UTC+2 in October).
        val night = timed(4, "2026-10-21T23:30:00Z", "2026-10-22T00:30:00Z", "Night train")

        val days = groupByDay(listOf(late, early, holiday, night), day("2026-10-21"), day("2026-10-28"), zurich)

        assertEquals(listOf(day("2026-10-21"), day("2026-10-22")), days.map { it.date })
        assertEquals(listOf("Holiday", "Standup", "Dentist"), days[0].entries.map { it.occurrence.title })
        assertEquals(listOf("Night train"), days[1].entries.map { it.occurrence.title })
        assertEquals(8 * 60 + 30, days[0].entries[1].startMinute)
        assertEquals(1 * 60 + 30, days[1].entries[0].startMinute)
    }

    @Test
    fun `events spanning days appear on each day, cut to the range`() {
        val trip = allDay(1, "2026-10-20", "2026-10-24", "Trip")
        val conference = timed(2, "2026-10-21T14:00:00Z", "2026-10-23T09:00:00Z", "Conference")

        val days = groupByDay(listOf(trip, conference), day("2026-10-21"), day("2026-10-23"), zurich)

        assertEquals(listOf(day("2026-10-21"), day("2026-10-22")), days.map { it.date })
        val first = days[0].entries.first { it.occurrence.title == "Conference" }
        assertEquals(16 * 60, first.startMinute)
        assertEquals(MINUTES_PER_DAY, first.endMinute)
        assertFalse(first.continuesBefore)
        assertTrue(first.continuesAfter)
        val middle = days[1].entries.first { it.occurrence.title == "Conference" }
        assertEquals(0, middle.startMinute)
        assertTrue(middle.continuesBefore)
        assertTrue(days[0].entries.first { it.occurrence.title == "Trip" }.continuesBefore)
    }

    @Test
    fun `an exclusive end at midnight does not touch the next day`() {
        val evening = timed(1, "2026-10-21T20:00:00Z", "2026-10-21T22:00:00Z") // 22:00-24:00 in Zurich
        val oneDay = allDay(2, "2026-10-21", "2026-10-22")

        assertEquals(day("2026-10-21")..day("2026-10-21"), evening.days(zurich))
        assertEquals(day("2026-10-21")..day("2026-10-21"), oneDay.days(zurich))
        assertNull(entryOn(evening, day("2026-10-22"), zurich))
        assertEquals(MINUTES_PER_DAY, entryOn(evening, day("2026-10-21"), zurich)!!.endMinute)
    }

    @Test
    fun `a repeated occurrence is listed once and days without events are left out`() {
        val a = timed(1, "2026-10-21T08:00:00Z", "2026-10-21T09:00:00Z")
        val days = groupByDay(listOf(a, a), day("2026-10-20"), day("2026-10-30"), zurich)
        assertEquals(1, days.size)
        assertEquals(1, days[0].entries.size)
        assertEquals(emptyList<AgendaDay>(), groupByDay(emptyList(), day("2026-10-20"), day("2026-10-30"), zurich))
    }

    @Test
    fun `overlapping events share the width, separate groups use the full width`() {
        fun entry(
            n: Int,
            start: String,
            end: String,
        ) = entryOn(timed(n, start, end), day("2026-10-21"), ZoneId.of("UTC"))!!
        val a = entry(1, "2026-10-21T09:00:00Z", "2026-10-21T11:00:00Z")
        val b = entry(2, "2026-10-21T10:00:00Z", "2026-10-21T12:00:00Z")
        val c = entry(3, "2026-10-21T11:00:00Z", "2026-10-21T11:30:00Z") // free lane of a again
        val d = entry(4, "2026-10-21T14:00:00Z", "2026-10-21T15:00:00Z")

        val placed = layoutDay(listOf(d, c, b, a)).associateBy { it.entry.occurrence.eventId }

        assertEquals(0 to 2, placed.getValue(id(1)).let { it.lane to it.lanes })
        assertEquals(1 to 2, placed.getValue(id(2)).let { it.lane to it.lanes })
        assertEquals(0 to 2, placed.getValue(id(3)).let { it.lane to it.lanes })
        assertEquals(0 to 1, placed.getValue(id(4)).let { it.lane to it.lanes })
    }

    @Test
    fun `the week starts on the given first day`() {
        val wednesday = day("2026-10-21")
        assertEquals(day("2026-10-19"), weekOf(wednesday, DayOfWeek.MONDAY).first())
        assertEquals(day("2026-10-18"), weekOf(wednesday, DayOfWeek.SUNDAY).first())
        assertEquals(7, weekOf(wednesday, DayOfWeek.MONDAY).size)
        assertEquals(day("2026-10-25"), weekOf(wednesday, DayOfWeek.MONDAY).last())
    }

    @Test
    fun `css colors are read as opaque argb`() {
        assertEquals(0xFF3366FFL, parseColor("#3366ff"))
        assertEquals(0xFFFF0000L, parseColor("#f00"))
        assertNull(parseColor("blue"))
    }
}
