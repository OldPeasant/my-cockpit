package ch.sonensei.cockpit.calendar.agenda

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Which timed entries of a day column are scrolled out of view (pure, no Compose). */
class HiddenEntriesTest {
    private val date = LocalDate.parse("2026-10-21")

    // One px per minute: offsets and heights read as minutes of the day.
    private val scale = 1f

    private fun entry(
        n: Int,
        start: Int,
        end: Int,
        allDay: Boolean = false,
    ): DayEntry {
        val occurrence =
            Occurrence(
                UUID.fromString("00000000-0000-7000-8000-%012d".format(n)),
                UUID.fromString("00000000-0000-7000-8000-000000000090"),
                "Event $n",
                null,
                allDay,
                if (allDay) null else Instant.parse("2026-10-21T00:00:00Z"),
                if (allDay) null else Instant.parse("2026-10-21T01:00:00Z"),
                if (allDay) date else null,
                if (allDay) date.plusDays(1) else null,
            )
        return DayEntry(occurrence, date, start, end, false, false)
    }

    @Test
    fun `counts entries entirely above and below the viewport`() {
        val entries = listOf(entry(1, 60, 120), entry(2, 180, 240), entry(3, 600, 660), entry(4, 900, 960), entry(5, 1000, 1060))
        val hidden = hiddenEntries(entries, scrollOffset = 300, viewportHeight = 480, pxPerMinute = scale)
        assertEquals(2, hidden.above)
        assertEquals(2, hidden.below)
    }

    @Test
    fun `partly visible entries count as visible`() {
        val entries = listOf(entry(1, 250, 320), entry(2, 770, 840))
        assertEquals(HiddenEntries.NONE, hiddenEntries(entries, 300, 480, scale))
    }

    @Test
    fun `an entry ending exactly at the top edge is hidden, one starting exactly at the bottom edge too`() {
        val hidden = hiddenEntries(listOf(entry(1, 240, 300), entry(2, 780, 840)), 300, 480, scale)
        assertEquals(1, hidden.above)
        assertEquals(1, hidden.below)
    }

    @Test
    fun `all-day entries are ignored`() {
        assertEquals(HiddenEntries.NONE, hiddenEntries(listOf(entry(1, 0, 1440, allDay = true)), 600, 300, scale))
    }

    @Test
    fun `short entries use their drawn minimum height`() {
        // A zero-length entry at 4:45 is drawn MIN_ENTRY_MINUTES tall (until 5:05), so it is partly visible from 5:00.
        assertEquals(0, hiddenEntries(listOf(entry(1, 285, 285)), 300, 300, scale).above)
        assertEquals(1, hiddenEntries(listOf(entry(1, 285, 285)), 285 + MIN_ENTRY_MINUTES, 300, scale).above)
    }

    @Test
    fun `scroll targets bring the nearest hidden entry into view`() {
        val entries = listOf(entry(1, 60, 120), entry(2, 180, 240), entry(3, 900, 960), entry(4, 1000, 1060))
        val hidden = hiddenEntries(entries, 300, 480, scale)
        // Above: the nearest one (2:00 to 4:00 → 3:00) starts at the top edge.
        assertEquals(180, hidden.scrollToAbove)
        // Below: the nearest one (15:00 to 16:00) ends at the bottom edge.
        assertEquals(960 - 480, hidden.scrollToBelow)
    }

    @Test
    fun `scroll targets keep the revealed entry a margin away from the edge`() {
        val entries = listOf(entry(1, 60, 120), entry(2, 180, 240), entry(3, 900, 960), entry(4, 1000, 1060))
        val hidden = hiddenEntries(entries, 300, 480, scale, margin = 30)
        // Above: the nearest one starts 30 px below the top edge, clear of the chip there.
        assertEquals(180 - 30, hidden.scrollToAbove)
        // Below: the nearest one ends 30 px above the bottom edge.
        assertEquals(960 + 30 - 480, hidden.scrollToBelow)
        // The margin moves only the targets, not what is counted as hidden.
        assertEquals(2, hidden.above)
        assertEquals(2, hidden.below)
    }

    @Test
    fun `a margin never makes a scroll target negative`() {
        // Drawn 0:10 to 0:30: 10 px minus the 30 px margin is clamped to the start of the grid.
        val hidden = hiddenEntries(listOf(entry(1, 10, 10)), 300, 480, scale, margin = 30)
        assertEquals(0, hidden.scrollToAbove)
    }

    @Test
    fun `an entry taller than the viewport is scrolled to a margin above its start`() {
        val hidden = hiddenEntries(listOf(entry(1, 900, 1400)), 0, 300, scale, margin = 30)
        assertEquals(900 - 30, hidden.scrollToBelow)
    }

    @Test
    fun `an entry taller than the viewport is scrolled to its start`() {
        val hidden = hiddenEntries(listOf(entry(1, 900, 1400)), 0, 300, scale)
        assertEquals(900, hidden.scrollToBelow)
    }

    @Test
    fun `scale and scroll offset are in px`() {
        // 0.8 px per minute (48 px per hour): 9:00 to 10:00 is 432..480 px.
        val entries = listOf(entry(1, 540, 600))
        assertEquals(1, hiddenEntries(entries, 0, 400, 0.8f).below)
        assertEquals(0, hiddenEntries(entries, 0, 433, 0.8f).below)
        assertEquals(1, hiddenEntries(entries, 480, 400, 0.8f).above)
    }

    @Test
    fun `no viewport yet means nothing is reported`() {
        assertEquals(HiddenEntries.NONE, hiddenEntries(listOf(entry(1, 60, 120)), 0, 0, scale))
    }

    @Test
    fun `a negative viewport height means nothing is reported`() {
        assertEquals(HiddenEntries.NONE, hiddenEntries(listOf(entry(1, 60, 120)), 0, -1, scale))
    }

    @Test
    fun `the nearest hidden entries are found whatever the order of the input`() {
        val entries = listOf(entry(1, 1000, 1060), entry(2, 180, 240), entry(3, 900, 960), entry(4, 60, 120))
        val hidden = hiddenEntries(entries, 300, 480, scale)
        assertEquals(2, hidden.above)
        assertEquals(2, hidden.below)
        assertEquals(180, hidden.scrollToAbove)
        assertEquals(960 - 480, hidden.scrollToBelow)
    }

    @Test
    fun `the parts of overnight entries on this day are counted like other timed entries`() {
        // Continues from yesterday until 1:30, and starts at 23:00 continuing into tomorrow.
        val fromYesterday = entry(1, 0, 90).copy(continuesBefore = true)
        val intoTomorrow = entry(2, 1380, MINUTES_PER_DAY).copy(continuesAfter = true)
        val hidden = hiddenEntries(listOf(fromYesterday, intoTomorrow), 120, 480, scale)
        assertEquals(1, hidden.above)
        assertEquals(1, hidden.below)
        // Above: back to midnight. Below: the part until midnight ends at the bottom edge.
        assertEquals(0, hidden.scrollToAbove)
        assertEquals(MINUTES_PER_DAY - 480, hidden.scrollToBelow)
    }

    @Test
    fun `a middle day of a multi-day timed entry is never hidden`() {
        val wholeDay = entry(1, 0, MINUTES_PER_DAY).copy(continuesBefore = true, continuesAfter = true)
        for (offset in listOf(0, 300, MINUTES_PER_DAY - 480)) {
            assertEquals(HiddenEntries.NONE, hiddenEntries(listOf(wholeDay), offset, 480, scale))
        }
    }

    @Test
    fun `an entry just past the edge by one px is partly visible`() {
        // Ends one minute after the top edge, starts one minute before the bottom edge.
        assertEquals(HiddenEntries.NONE, hiddenEntries(listOf(entry(1, 240, 301), entry(2, 779, 840)), 300, 480, scale))
    }

    @Test
    fun `scroll targets are rounded to whole px`() {
        // 0.8 px per minute: 9:01 starts at 432.8 px, 10:01 ends at 480.8 px.
        val hidden = hiddenEntries(listOf(entry(1, 541, 601)), 0, 400, 0.8f)
        assertEquals(81, hidden.scrollToBelow)
        val above = hiddenEntries(listOf(entry(1, 541, 601)), 600, 400, 0.8f)
        assertEquals(433, above.scrollToAbove)
    }

    @Test
    fun `no targets when nothing is hidden`() {
        val hidden = hiddenEntries(emptyList(), 0, 300, scale)
        assertEquals(null, hidden.scrollToAbove)
        assertEquals(null, hidden.scrollToBelow)
    }
}
