package ch.sonensei.cockpit.calendar.agenda

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.util.UUID

/** Recognizing a birthday (an occurrence of the virtual calendar) and the contact it comes from. */
class BirthdayOccurrenceTest {
    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val day = LocalDate.parse("2026-12-10")

    private val work = CalendarInfo(id(90), "Work", null, visible = true, readOnly = false, version = 1)
    private val holidays = CalendarInfo(id(91), "Holidays", null, visible = true, readOnly = true, version = 1)
    private val birthdays = CalendarInfo(id(92), "Birthdays", null, visible = true, readOnly = true, version = 1, virtual = true)
    private val calendars = listOf(work, holidays, birthdays)

    private fun allDay(
        eventId: UUID,
        calendarId: UUID,
    ) = Occurrence(eventId, calendarId, "Ada Lovelace", null, true, null, null, day, day.plusDays(1), seriesId = eventId)

    @Test
    fun `a calendar is not virtual unless it says so`() {
        assertFalse(CalendarInfo(id(90), "Work", null, true, false, 1).virtual)
        assertFalse(CalendarInfo(id(91), "Holidays", null, true, true, 1).virtual)
    }

    @Test
    fun `an occurrence of the virtual calendar comes from the contact its event id names`() {
        assertEquals(id(50), allDay(id(50), birthdays.id).contactId(calendars))
    }

    @Test
    fun `occurrences of writable and other read-only calendars come from no contact`() {
        assertNull(allDay(id(1), work.id).contactId(calendars))
        assertNull(allDay(id(2), holidays.id).contactId(calendars))
    }

    @Test
    fun `an occurrence of a calendar that is not known comes from no contact`() {
        assertNull(allDay(id(50), id(99)).contactId(calendars))
        assertNull(allDay(id(50), birthdays.id).contactId(emptyList()))
    }
}
