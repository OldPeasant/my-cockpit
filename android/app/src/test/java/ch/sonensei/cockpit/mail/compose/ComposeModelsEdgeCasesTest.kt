package ch.sonensei.cockpit.mail.compose

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

/** Edge cases of the recipient fields, the formatting buttons, the markup and the saved compose mode. */
class ComposeModelsEdgeCasesTest {
    @Test
    fun `an empty or separator-only field has no recipients and nothing invalid`() {
        listOf("", "   ", " ; , ;; ").forEach { field ->
            assertEquals(field, ParsedRecipients(emptyList(), emptyList()), parseRecipients(field))
        }
    }

    @Test
    fun `angle brackets without a name, a quoted name and semicolons without spaces are read`() {
        val parsed = parseRecipients("<ann@example.org>;\"Ben Example\" <ben@example.org>;  carl@example.org  ")

        assertEquals(
            listOf(Recipient("ann@example.org"), Recipient("ben@example.org", "Ben Example"), Recipient("carl@example.org")),
            parsed.recipients,
        )
        assertEquals(emptyList<String>(), parsed.invalid)
    }

    @Test
    fun `addresses without exactly one at sign with text on both sides are invalid, as typed`() {
        val typed = listOf("ann.example.org", "a@b@example.org", "ann@", "@example.org", "ann @example.org", "Ann <not-an-address>")

        val parsed = parseRecipients(typed.joinToString(", "))

        assertEquals(emptyList<Recipient>(), parsed.recipients)
        assertEquals(typed, parsed.invalid)
    }

    @Test
    fun `text after the closing bracket makes the entry invalid`() {
        val parsed = parseRecipients("Ann <ann@example.org> extra, ben@example.org")

        assertEquals(listOf(Recipient("ben@example.org")), parsed.recipients)
        assertEquals(listOf("Ann <ann@example.org> extra"), parsed.invalid)
    }

    @Test
    fun `blank names are left out, names with separators are quoted without inner quotes`() {
        val field =
            formatRecipients(
                listOf(
                    Recipient("a@example.org", "  "),
                    Recipient("x@example.org", "Say \"hi\", there"),
                    Recipient("y@example.org", "Team <ops>"),
                ),
            )

        assertEquals("a@example.org, \"Say hi, there\" <x@example.org>, \"Team <ops>\" <y@example.org>", field)
        assertEquals(
            listOf(Recipient("a@example.org"), Recipient("x@example.org", "Say hi, there"), Recipient("y@example.org", "Team <ops>")),
            parseRecipients(field).recipients,
        )
        assertEquals("", formatRecipients(emptyList()))
    }

    @Test
    fun `a name with quotes but no separator reads back unchanged`() {
        val recipients = listOf(Recipient("q@example.org", "The \"Boss\""))

        assertEquals(recipients, parseRecipients(formatRecipients(recipients)).recipients)
    }

    @Test
    fun `a reversed selection is wrapped like a forward one`() {
        val bold = applyFormat(TextFieldValue("say hello now", TextRange(9, 4)), TextFormat.BOLD)
        assertEquals("say **hello** now", bold.text)
        assertEquals(TextRange(6, 11), bold.selection)

        val italic = applyFormat(TextFieldValue("ab", TextRange(0, 2)), TextFormat.ITALIC)
        assertEquals("_ab_", italic.text)
        assertEquals(TextRange(1, 3), italic.selection)
    }

    @Test
    fun `a bullet without selection marks only the line of the cursor and puts the cursor at its end`() {
        // Inside the second line.
        val middle = applyFormat(TextFieldValue("intro\none\ntwo", TextRange(8)), TextFormat.BULLET)
        assertEquals("intro\n- one\ntwo", middle.text)
        assertEquals(TextRange(11), middle.selection)

        // At the end of the first line (on its line break).
        assertEquals("- intro\none", applyFormat(TextFieldValue("intro\none", TextRange(5)), TextFormat.BULLET).text)

        // At the start of the second line.
        assertEquals("intro\n- one", applyFormat(TextFieldValue("intro\none", TextRange(6)), TextFormat.BULLET).text)
    }

    @Test
    fun `a bullet in an empty body starts a list`() {
        val listed = applyFormat(TextFieldValue(""), TextFormat.BULLET)

        assertEquals("- ", listed.text)
        assertEquals(TextRange(2), listed.selection)
    }

    @Test
    fun `windows line breaks and runs of blank lines are read like single ones`() {
        assertEquals("<p>a<br>b</p>", markupToHtml("a\r\nb"))
        assertEquals("<p>one</p><p>two</p>", markupToHtml("one\n\n \n\ntwo"))
    }

    @Test
    fun `unclosed markers stay text, nested ones are both applied`() {
        assertEquals("<p>**open and _half</p>", markupToHtml("**open and _half"))
        assertEquals("<p><strong><em>both</em></strong></p>", markupToHtml("**_both_**"))
        assertEquals("<p>x_y_ and <em>z</em></p>", markupToHtml("x_y_ and _z_"))
    }

    @Test
    fun `typed html, quotes and entities are escaped, also inside emphasis`() {
        assertEquals("<p>&lt;script&gt;alert(1)&lt;/script&gt;</p>", markupToHtml("<script>alert(1)</script>"))
        assertEquals("<p>say &quot;hi&quot; &amp;amp;</p>", markupToHtml("say \"hi\" &amp;"))
        assertEquals("<p><strong>a &lt; b</strong></p>", markupToHtml("**a < b**"))
    }

    @Test
    fun `a dash without a space is text, a list ends at the next text line`() {
        assertEquals(
            "<p>-not a bullet</p><ul><li>item</li></ul><p>after</p>",
            markupToHtml("-not a bullet\n- item\nafter"),
        )
    }

    @Test
    fun `incomplete or unknown saved modes are read safely`() {
        assertEquals(ComposeMode.New(), ComposeMode.parse("new:"))
        assertEquals(ComposeMode.New(), ComposeMode.parse("new"))
        assertNull(ComposeMode.parse(""))
        assertNull(ComposeMode.parse("reply:"))
        assertNull(ComposeMode.parse("forward"))
        assertNull(ComposeMode.parse("reply-all:${UUID.fromString("00000000-0000-7000-8000-000000000001")}x"))
    }
}
