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
    fun `no targets when nothing is hidden`() {
        val hidden = hiddenEntries(emptyList(), 0, 300, scale)
        assertEquals(null, hidden.scrollToAbove)
        assertEquals(null, hidden.scrollToBelow)
    }
}
