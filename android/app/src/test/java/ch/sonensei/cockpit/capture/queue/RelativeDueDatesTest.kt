package ch.sonensei.cockpit.capture.queue

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/** Mirrors the token rules of the server's `CaptureParser` (SPEC.md §4.5). */
class RelativeDueDatesTest {
    private val today = LocalDate.parse("2026-10-21")

    private fun pin(text: String) = RelativeDueDates.pin(text, today)

    @Test
    fun `today and tomorrow become absolute dates, case-insensitive`() {
        assertEquals("Pay rent !2026-10-21", pin("Pay rent !today"))
        assertEquals("!2026-10-22 call mum", pin("!TOMORROW call mum"))
        assertEquals("Call\t!2026-10-21 @phone", pin("Call\t!Today @phone"))
    }

    @Test
    fun `trailing punctuation stays after the date`() {
        assertEquals("Pay rent !2026-10-21, then relax", pin("Pay rent !today, then relax"))
        assertEquals("Due !2026-10-22!?)", pin("Due !tomorrow!?)"))
    }

    @Test
    fun `only the first recognized due token counts`() {
        assertEquals("Due !2026-10-01 not !today", pin("Due !2026-10-01 not !today"))
        assertEquals("A !2026-10-21 B !tomorrow", pin("A !today B !tomorrow"))
        // Unrecognized tokens (unknown words, invalid dates, a bare "!") do not stop the search.
        assertEquals("Wow !soon !2026-13-45 ! !! !2026-10-22", pin("Wow !soon !2026-13-45 ! !! !tomorrow"))
    }

    @Test
    fun `text inside words and other sigils is not a token`() {
        assertEquals("hey!today a#!today", pin("hey!today a#!today"))
        assertEquals("#today @today ^today", pin("#today @today ^today"))
        assertEquals("!todays plan", pin("!todays plan"))
    }

    @Test
    fun `only the title line, the first non-blank line, is rewritten and line breaks are kept`() {
        assertEquals("\r\n  \r\nTitle !2026-10-21\r\nnote !today\n", pin("\r\n  \r\nTitle !today\r\nnote !today\n"))
        assertEquals("Title\n!today", pin("Title\n!today"))
        assertEquals("   ", pin("   "))
    }
}
