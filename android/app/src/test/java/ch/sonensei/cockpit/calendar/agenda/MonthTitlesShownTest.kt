package ch.sonensei.cockpit.calendar.agenda

import org.junit.Assert.assertEquals
import org.junit.Test

/** How many titles a month cell lists for the lines that fit, always keeping a line for "+n". */
class MonthTitlesShownTest {
    @Test
    fun `a high cell lists up to three titles and +n for the rest`() {
        assertEquals(0, monthTitlesShown(0, 10))
        assertEquals(3, monthTitlesShown(3, 10))
        assertEquals(3, monthTitlesShown(5, 10))
        assertEquals(3, monthTitlesShown(4, 4))
    }

    @Test
    fun `a low cell lists fewer titles and keeps a line for +n`() {
        // 4 entries, 3 lines: 2 titles and "+2".
        assertEquals(2, monthTitlesShown(4, 3))
        assertEquals(1, monthTitlesShown(4, 2))
        // A single line holds only "+n".
        assertEquals(0, monthTitlesShown(4, 1))
        // All entries fit: no "+n" is needed.
        assertEquals(2, monthTitlesShown(2, 2))
        assertEquals(1, monthTitlesShown(1, 1))
    }

    @Test
    fun `no room lists no title`() {
        assertEquals(0, monthTitlesShown(2, 0))
        assertEquals(0, monthTitlesShown(2, -1))
    }
}
