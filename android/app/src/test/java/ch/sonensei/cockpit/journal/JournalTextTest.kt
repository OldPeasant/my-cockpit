package ch.sonensei.cockpit.journal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

class JournalTextTest {
    @Test
    fun `an excerpt drops Markdown, images and entity links`() {
        val body = "# Lake\n- [x] swim in the ![](a.jpg) [cold](http://x) water [[NOTE:00000000-0000-7000-8000-000000000001]] **now**"
        assertEquals("Lake swim in the cold water now", JournalText.excerpt(body))
    }

    @Test
    fun `a long excerpt is cut with an ellipsis`() {
        assertEquals("abcd…", JournalText.excerpt("abcdefgh", length = 5))
        assertEquals("abc", JournalText.excerpt("abc", length = 5))
    }

    @Test
    fun `month weeks start on the week start and are padded`() {
        // November 2026 starts on a Sunday.
        val weeks = JournalText.monthWeeks(YearMonth.of(2026, 11), DayOfWeek.MONDAY)
        assertTrue(weeks.all { it.size == 7 })
        assertEquals(List(6) { null } + LocalDate.of(2026, 11, 1), weeks.first())
        assertEquals(30, weeks.flatten().filterNotNull().size)
        assertEquals(LocalDate.of(2026, 11, 1), JournalText.monthWeeks(YearMonth.of(2026, 11), DayOfWeek.SUNDAY).first().first())
    }

    @Test
    fun `tags are parsed from text without duplicates or hashes`() {
        assertEquals(listOf("Travel", "home"), JournalDraft.parseTags(" #Travel, travel,, home ,#"))
    }

    @Test
    fun `drafts compare tags regardless of order and case`() {
        assertTrue(JournalDraft("a", 1, listOf("B", "a")).sameAs(JournalDraft("a", 1, listOf("A", "b"))))
        assertFalse(JournalDraft("a", 1).sameAs(JournalDraft("a", 2)))
        assertTrue(JournalDraft().isEmpty)
        assertFalse(JournalDraft(tags = listOf("x")).isEmpty)
    }
}
