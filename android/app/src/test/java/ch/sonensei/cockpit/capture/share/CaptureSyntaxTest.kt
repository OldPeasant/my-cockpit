package ch.sonensei.cockpit.capture.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The device's port of the capture syntax. The cases mirror the server's `CaptureParserTests` with the same inputs and
 * expectations, so a rule changed on one side and not the other shows up here.
 */
class CaptureSyntaxTest {
    private val today = LocalDate.of(2026, 3, 1)
    private val renovation = SyntaxProject("00000000-0000-7000-8000-000000000001", "Home Renovation")
    private val garden = SyntaxProject("00000000-0000-7000-8000-000000000002", "Garden")
    private val home = SyntaxContext("00000000-0000-7000-8000-000000000011", "@home")
    private val phone = SyntaxContext("00000000-0000-7000-8000-000000000012", "@Phone")
    private val errands = SyntaxContext("00000000-0000-7000-8000-000000000013", "errands")
    private val vocabulary = CaptureVocabulary(listOf(renovation, garden), listOf(home, phone, errands))

    private fun parse(text: String): CapturePreview = CaptureSyntax.parse(text, today, vocabulary)

    @Test
    fun `plain text is the title and has no tokens`() {
        val preview = parse("Call the plumber")

        assertEquals(CapturePreview("Call the plumber"), preview)
        assertFalse(preview.hasTokens)
    }

    @Test
    fun `a project is found by title, case-insensitive, with dashes or underscores for spaces`() {
        parse("Buy paint #garden").let {
            assertEquals(garden, it.project)
            assertEquals("Buy paint", it.title)
            assertTrue(it.hasTokens)
        }
        assertEquals(renovation, parse("Buy tiles #home-renovation").project)
        assertEquals(renovation, parse("Buy tiles #HOME_renovation").project)
    }

    @Test
    fun `an unknown project is kept as text`() {
        assertEquals(CapturePreview("Read #book soon"), parse("Read #book soon"))
    }

    @Test
    fun `the first known project wins, later ones stay as text`() {
        parse("#unknown #garden #home-renovation weeds").let {
            assertEquals(garden, it.project)
            assertEquals("#unknown #home-renovation weeds", it.title)
        }
    }

    @Test
    fun `the first project in the vocabulary's order wins when two have the same name`() {
        val older = SyntaxProject("00000000-0000-7000-8000-000000000003", "garden")
        val preview = CaptureSyntax.parse("Weeds #Garden", today, CaptureVocabulary(listOf(older, garden)))

        assertEquals(older, preview.project)
    }

    @Test
    fun `contexts are found with or without their at sign and accumulate`() {
        parse("@HOME call mom @phone @errands @home").let {
            assertEquals(listOf(home, phone, errands), it.contexts)
            assertEquals("call mom", it.title)
            assertTrue(it.hasTokens)
        }
    }

    @Test
    fun `an unknown context is kept as text`() {
        parse("Ask @office about it").let {
            assertEquals(emptyList<SyntaxContext>(), it.contexts)
            assertEquals("Ask @office about it", it.title)
        }
    }

    @Test
    fun `due today, tomorrow and an ISO date`() {
        assertEquals(today, parse("Pay bill !today").dueDate)
        assertEquals(today.plusDays(1), parse("Pay bill !Tomorrow").dueDate)
        parse("Pay bill !2026-11-03").let {
            assertEquals(LocalDate.of(2026, 11, 3), it.dueDate)
            assertEquals("Pay bill", it.title)
            assertTrue(it.hasTokens)
        }
    }

    @Test
    fun `tomorrow follows the given date across a month and a leap day`() {
        assertEquals(
            LocalDate.of(2028, 2, 29),
            CaptureSyntax.parse("Pay !tomorrow", LocalDate.of(2028, 2, 28), vocabulary).dueDate,
        )
        assertEquals(
            LocalDate.of(2027, 1, 1),
            CaptureSyntax.parse("Pay !tomorrow", LocalDate.of(2026, 12, 31), vocabulary).dueDate,
        )
    }

    @Test
    fun `invalid dates and unknown keywords stay as text`() {
        assertEquals(CapturePreview("Pay !2026-13-45 bill !soon !"), parse("Pay !2026-13-45 bill !soon !"))
        // Right shape, impossible day; and a date that is not zero-padded.
        assertEquals(CapturePreview("Pay !2026-02-30 !2026-4-1"), parse("Pay !2026-02-30 !2026-4-1"))
    }

    @Test
    fun `the first due date wins`() {
        parse("Pay !tomorrow !today").let {
            assertEquals(today.plusDays(1), it.dueDate)
            assertEquals("Pay !today", it.title)
        }
    }

    @Test
    fun `waiting puts the task on the waiting list, a name says for whom`() {
        parse("Quote for the roof ^waiting").let {
            assertTrue(it.waiting)
            assertNull(it.waitingFor)
            assertEquals("Quote for the roof", it.title)
            assertTrue(it.hasTokens)
        }
        parse("Quote for the roof ^WAITING").let {
            assertTrue(it.waiting)
            assertNull(it.waitingFor)
        }
        parse("Quote for the roof ^Anna ^Ben").let {
            assertTrue(it.waiting)
            assertEquals("Anna", it.waitingFor)
            assertEquals("Quote for the roof ^Ben", it.title)
        }
    }

    @Test
    fun `tokens need whitespace before them, so urls and emails are untouched`() {
        val text = "See https://example.org/page#garden and mail me@home.example a#garden!today"

        assertEquals(CapturePreview(text), parse(text))
    }

    @Test
    fun `a sigil alone or followed only by punctuation is text`() {
        assertEquals(CapturePreview("What # @ ^ !"), parse("What # @ ^ !"))
        assertEquals(CapturePreview("Hm ^. #, @;"), parse("Hm ^. #, @;"))
    }

    @Test
    fun `trailing punctuation is not part of a token and stays in the title`() {
        parse("Call mom @phone, tomorrow is due !tomorrow.").let {
            assertEquals(listOf(phone), it.contexts)
            assertEquals(today.plusDays(1), it.dueDate)
            assertEquals("Call mom, tomorrow is due.", it.title)
        }
        parse("Buy tiles (for #garden) today").let {
            assertEquals(garden, it.project)
            assertEquals("Buy tiles (for) today", it.title)
        }
    }

    @Test
    fun `all tokens together`() {
        assertEquals(
            CapturePreview(
                title = "Plant tulips",
                project = garden,
                contexts = listOf(home),
                dueDate = LocalDate.of(2026, 4, 1),
                waiting = true,
                waitingFor = "Anna",
            ),
            parse("#garden Plant tulips @home !2026-04-01 ^Anna"),
        )
    }

    @Test
    fun `the first non-blank line is the title line and runs of spaces collapse`() {
        parse("\n  Read   article @home\n\nhttps://example.org/a\nsecond line  \n").let {
            assertEquals("Read article", it.title)
            assertEquals(listOf(home), it.contexts)
        }
        assertEquals("Title with spaces", parse("\n  Title   with\tspaces @home \n\nbody").title)
    }

    @Test
    fun `a text of only tokens has an empty title`() {
        parse("#garden @home !today").let {
            assertEquals("", it.title)
            assertTrue(it.hasTokens)
        }
        parse("   \n ").let {
            assertEquals("", it.title)
            assertFalse(it.hasTokens)
        }
        assertEquals(CapturePreview(""), parse(""))
    }

    @Test
    fun `tokens on later lines are not parsed`() {
        val body = "see footnote ^1 and ask @home\n#garden !today !2026-04-01 ^waiting"

        assertEquals(CapturePreview("Read article"), parse("Read article\n$body"))
    }

    @Test
    fun `a title line of only tokens takes the next line as the title, unparsed`() {
        parse("#garden !today\n\nPlant tulips @home\n  bulbs").let {
            assertEquals(garden, it.project)
            assertEquals(today, it.dueDate)
            assertEquals(emptyList<SyntaxContext>(), it.contexts)
            assertEquals("Plant tulips @home", it.title)
        }
    }

    @Test
    fun `a title line of unknown tokens only stays the title`() {
        assertEquals(CapturePreview("#book @office"), parse("#book @office\nnext line"))
    }

    @Test
    fun `without a vocabulary only dates and waiting are recognized`() {
        val preview = CaptureSyntax.parse("Plant #garden @home !today ^Anna", today, CaptureVocabulary.EMPTY)

        assertNull(preview.project)
        assertEquals(emptyList<SyntaxContext>(), preview.contexts)
        assertEquals(today, preview.dueDate)
        assertEquals("Anna", preview.waitingFor)
        assertEquals("Plant #garden @home", preview.title)
    }

    @Test
    fun `a context name with surrounding spaces is still found`() {
        val padded = SyntaxContext("00000000-0000-7000-8000-000000000014", "  @Office ")

        assertEquals(listOf(padded), CaptureSyntax.parse("Print @office", today, CaptureVocabulary(contexts = listOf(padded))).contexts)
    }

    @Test
    fun `only ASCII whitespace separates tokens, as on the server (CR-1)`() {
        // No-break and narrow no-break spaces are part of the word on the server's java.util.regex; the device must agree
        // even where its own regex engine would treat them as \s.
        val noBreak = "Call @phone and #garden"
        assertEquals(CapturePreview(noBreak), parse(noBreak))
        parse("Call\t@phone\u000B#garden\u000C!today").let {
            assertEquals(listOf(phone), it.contexts)
            assertEquals(garden, it.project)
            assertEquals(today, it.dueDate)
        }
    }

    @Test
    fun `a no-break space does not end a token, so the longer name is unknown`() {
        parse("Call @phone now").let {
            assertEquals(emptyList<SyntaxContext>(), it.contexts)
            assertEquals("Call @phone now", it.title)
        }
    }

    @Test
    fun `a project title with runs of spaces or tabs is matched like single spaces`() {
        val spaced = SyntaxProject("00000000-0000-7000-8000-000000000004", "Tax  Return\t2026")

        assertEquals(spaced, CaptureSyntax.parse("File #tax-return_2026", today, CaptureVocabulary(listOf(spaced))).project)
    }
}
