package ch.sonensei.cockpit.calendar.agenda

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalTime

/** Where a long press in the hour grid starts the new event: rounded down to 15 minutes, inside the day. */
class CalendarLongPressTest {
    // 2 px per minute: an hour is 120 px.
    private val pxPerMinute = 2f

    @Test
    fun `a press is rounded down to the quarter hour it falls into`() {
        assertEquals(LocalTime.of(10, 30), slotStart(637.5f * pxPerMinute, pxPerMinute))
        assertEquals(LocalTime.of(7, 45), slotStart(477.9f * pxPerMinute, pxPerMinute))
        assertEquals(LocalTime.of(13, 0), slotStart(780f * pxPerMinute, pxPerMinute))
    }

    @Test
    fun `exactly on a quarter hour starts there, just before it starts a quarter earlier`() {
        assertEquals(LocalTime.of(0, 15), slotStart(15f * pxPerMinute, pxPerMinute))
        assertEquals(LocalTime.of(0, 0), slotStart(15f * pxPerMinute - 0.5f, pxPerMinute))
        assertEquals(LocalTime.of(9, 0), slotStart(540f * pxPerMinute, pxPerMinute))
        assertEquals(LocalTime.of(8, 45), slotStart(540f * pxPerMinute - 0.5f, pxPerMinute))
    }

    @Test
    fun `the top of the column is midnight and a press above it is clamped to midnight`() {
        assertEquals(LocalTime.MIDNIGHT, slotStart(0f, pxPerMinute))
        assertEquals(LocalTime.MIDNIGHT, slotStart(-40f, pxPerMinute))
    }

    @Test
    fun `the last quarter of the day is the latest start, also for a press below the column`() {
        assertEquals(LocalTime.of(23, 45), slotStart((MINUTES_PER_DAY - 1) * pxPerMinute, pxPerMinute))
        assertEquals(LocalTime.of(23, 45), slotStart(MINUTES_PER_DAY * pxPerMinute, pxPerMinute))
        assertEquals(LocalTime.of(23, 45), slotStart(10_000f * pxPerMinute, pxPerMinute))
    }

    @Test
    fun `the rounding follows the density of the grid`() {
        // 48 dp per hour at density 1 and at density 2.75.
        assertEquals(LocalTime.of(7, 45), slotStart(7.9f * 48f, 48f / 60f))
        assertEquals(LocalTime.of(7, 45), slotStart(7.9f * 48f * 2.75f, 48f * 2.75f / 60f))
    }
}
