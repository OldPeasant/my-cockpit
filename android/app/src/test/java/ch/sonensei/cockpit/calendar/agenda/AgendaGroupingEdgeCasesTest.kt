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

/** Boundaries of grouping by day: time zones and clock changes, odd ends, ties, and the grid layout limits. */
class AgendaGroupingEdgeCasesTest {
    private val utc = ZoneId.of("UTC")
    private val zurich = ZoneId.of("Europe/Zurich")
    private val calendar = UUID.fromString("00000000-0000-7000-8000-000000000099")

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private fun timed(
        n: Int,
        start: String,
        end: String?,
        title: String = "Event $n",
    ) = Occurrence(id(n), calendar, title, null, false, Instant.parse(start), end?.let(Instant::parse), null, null)

    private fun allDay(
        n: Int,
        start: String,
        endExclusive: String?,
        title: String = "Event $n",
    ) = Occurrence(id(n), calendar, title, null, true, null, null, LocalDate.parse(start), endExclusive?.let(LocalDate::parse))

    private fun day(s: String) = LocalDate.parse(s)

    @Test
    fun `a night spanning the end of summer time is one full day in Zurich`() {
        // 00:00 on 25 October (UTC+2) to 00:00 on 26 October (UTC+1): a 25-hour day.
        val night = timed(1, "2026-10-24T22:00:00Z", "2026-10-25T23:00:00Z")

        val days = groupByDay(listOf(night), day("2026-10-20"), day("2026-10-30"), zurich)

        assertEquals(listOf(day("2026-10-25")), days.map { it.date })
        val entry = days.single().entries.single()
        assertEquals(0 to MINUTES_PER_DAY, entry.startMinute to entry.endMinute)
        assertFalse(entry.continuesBefore)
        assertFalse(entry.continuesAfter)
    }

    @Test
    fun `an event that started before the range appears from the first day of the range`() {
        val retreat = timed(1, "2026-10-19T10:00:00Z", "2026-10-22T10:00:00Z", "Retreat")

        val days = groupByDay(listOf(retreat), day("2026-10-21"), day("2026-10-28"), utc)

        assertEquals(listOf(day("2026-10-21"), day("2026-10-22")), days.map { it.date })
        val first = days[0].entries.single()
        assertTrue(first.continuesBefore)
        assertTrue(first.continuesAfter)
        assertEquals(0 to MINUTES_PER_DAY, first.startMinute to first.endMinute)
        val last = days[1].entries.single()
        assertTrue(last.continuesBefore)
        assertFalse(last.continuesAfter)
        assertEquals(0 to 10 * 60, last.startMinute to last.endMinute)
    }

    @Test
    fun `an event entirely outside the range is left out`() {
        val before = timed(1, "2026-10-19T10:00:00Z", "2026-10-19T11:00:00Z")
        val after = allDay(2, "2026-10-28", "2026-10-29")

        assertEquals(emptyList<AgendaDay>(), groupByDay(listOf(before, after), day("2026-10-21"), day("2026-10-28"), utc))
    }

    @Test
    fun `a zero-length event, one without an end and one ending before it starts each sit on their start day`() {
        val reminder = timed(1, "2026-10-21T09:00:00Z", "2026-10-21T09:00:00Z")
        val open = timed(2, "2026-10-21T10:00:00Z", null)
        val backwards = timed(3, "2026-10-21T12:00:00Z", "2026-10-21T11:00:00Z")

        for (occurrence in listOf(reminder, open, backwards)) {
            assertEquals(day("2026-10-21")..day("2026-10-21"), occurrence.days(utc))
            val entry = entryOn(occurrence, day("2026-10-21"), utc)!!
            assertEquals(entry.startMinute, entry.endMinute)
        }
        assertNull(entryOn(open, day("2026-10-22"), utc))
    }

    @Test
    fun `an all-day event without an end or with an end not after its start is one day`() {
        val noEnd = allDay(1, "2026-10-21", null)
        val sameDay = allDay(2, "2026-10-21", "2026-10-21")

        assertEquals(day("2026-10-21")..day("2026-10-21"), noEnd.days(zurich))
        assertEquals(day("2026-10-21")..day("2026-10-21"), sameDay.days(zurich))
    }

    @Test
    fun `a timed occurrence without a start is shown like an all-day one`() {
        val undated = Occurrence(id(1), calendar, "Undated", null, false, null, null, day("2026-10-21"), null)

        val entry = entryOn(undated, day("2026-10-21"), utc)!!

        assertEquals(0 to MINUTES_PER_DAY, entry.startMinute to entry.endMinute)
    }

    @Test
    fun `entries starting together are ordered by end, then by title ignoring case`() {
        val longer = timed(1, "2026-10-21T09:00:00Z", "2026-10-21T11:00:00Z", "Aardvark")
        val beta = timed(2, "2026-10-21T09:00:00Z", "2026-10-21T10:00:00Z", "beta")
        val alpha = timed(3, "2026-10-21T09:00:00Z", "2026-10-21T10:00:00Z", "Alpha")
        val midnight = timed(4, "2026-10-21T00:00:00Z", "2026-10-21T00:30:00Z", "Early")
        val holiday = allDay(5, "2026-10-21", "2026-10-22", "Zz holiday")

        val entries = entriesOn(listOf(longer, beta, alpha, midnight, holiday), day("2026-10-21"), utc)

        assertEquals(listOf("Zz holiday", "Early", "Alpha", "beta", "Aardvark"), entries.map { it.occurrence.title })
    }

    @Test
    fun `short events get a minimum height that can make them overlap, and never reach past midnight`() {
        fun entry(
            n: Int,
            start: String,
            end: String,
        ) = entryOn(timed(n, start, end), day("2026-10-21"), utc)!!
        val a = entry(1, "2026-10-21T09:00:00Z", "2026-10-21T09:00:00Z")
        val b = entry(2, "2026-10-21T09:10:00Z", "2026-10-21T09:15:00Z") // inside a's minimum height
        val late = entry(3, "2026-10-21T23:50:00Z", "2026-10-21T23:59:00Z")

        assertEquals(9 * 60 + MIN_ENTRY_MINUTES, visibleEnd(a))
        assertEquals(MINUTES_PER_DAY, visibleEnd(late))
        val placed = layoutDay(listOf(a, b, late)).associateBy { it.entry.occurrence.eventId }
        assertEquals(0 to 2, placed.getValue(id(1)).let { it.lane to it.lanes })
        assertEquals(1 to 2, placed.getValue(id(2)).let { it.lane to it.lanes })
        assertEquals(0 to 1, placed.getValue(id(3)).let { it.lane to it.lanes })
    }

    @Test
    fun `an event ending exactly when another starts does not share its width`() {
        fun entry(
            n: Int,
            start: String,
            end: String,
        ) = entryOn(timed(n, start, end), day("2026-10-21"), utc)!!
        val first = entry(1, "2026-10-21T09:00:00Z", "2026-10-21T10:00:00Z")
        val second = entry(2, "2026-10-21T10:00:00Z", "2026-10-21T11:00:00Z")

        assertEquals(listOf(1, 1), layoutDay(listOf(first, second)).map { it.lanes })
    }

    @Test
    fun `three events all overlapping each other get three lanes`() {
        fun entry(
            n: Int,
            start: String,
            end: String,
        ) = entryOn(timed(n, start, end), day("2026-10-21"), utc)!!
        val placed =
            layoutDay(
                listOf(
                    entry(1, "2026-10-21T09:00:00Z", "2026-10-21T12:00:00Z"),
                    entry(2, "2026-10-21T09:30:00Z", "2026-10-21T12:00:00Z"),
                    entry(3, "2026-10-21T10:00:00Z", "2026-10-21T12:00:00Z"),
                ),
            )

        assertEquals(listOf(0, 1, 2), placed.map { it.lane })
        assertEquals(listOf(3, 3, 3), placed.map { it.lanes })
    }

    @Test
    fun `the grid layout leaves all-day entries out and handles an empty day`() {
        val holiday = entryOn(allDay(1, "2026-10-21", "2026-10-22"), day("2026-10-21"), utc)!!

        assertEquals(emptyList<PlacedEntry>(), layoutDay(listOf(holiday)))
        assertEquals(emptyList<PlacedEntry>(), layoutDay(emptyList()))
    }

    @Test
    fun `a week starting on its first day starts on that date, and a Sunday-first week holds the Saturday`() {
        val monday = day("2026-10-19")
        assertEquals(monday, weekOf(monday, DayOfWeek.MONDAY).first())
        val saturday = day("2026-10-24")
        assertEquals(day("2026-10-18")..day("2026-10-24"), weekOf(saturday, DayOfWeek.SUNDAY).let { it.first()..it.last() })
        // A week across the year end.
        assertEquals(day("2026-12-28"), weekOf(day("2027-01-01"), DayOfWeek.MONDAY).first())
    }

    @Test
    fun `css colors ignore surrounding spaces and case, other lengths and non-hex digits are no color`() {
        assertEquals(0xFFABCDEFL, parseColor("  #ABCDEF "))
        assertEquals(0xFFAABBCCL, parseColor("abc"))
        assertNull(parseColor("#12345"))
        assertNull(parseColor("#11223344"))
        assertNull(parseColor("#zzzzzz"))
        assertNull(parseColor(""))
    }

    @Test
    fun `the version is sent as a quoted entity tag`() {
        assertEquals("\"42\"", etag(42))
    }
}
