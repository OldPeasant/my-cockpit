package ch.sonensei.cockpit.mail.compose

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

/** Recipient fields, the formatting buttons and the HTML the typed text becomes. */
class ComposeModelsTest {
    @Test
    fun `recipients are read with and without names, quoted names may hold commas`() {
        val parsed = parseRecipients("Ann Example <ann@example.org>, ben@example.org; \"Doe, Carl\" <carl@example.org>,, ")

        assertEquals(
            listOf(
                Recipient("ann@example.org", "Ann Example"),
                Recipient("ben@example.org"),
                Recipient("carl@example.org", "Doe, Carl"),
            ),
            parsed.recipients,
        )
        assertEquals(emptyList<String>(), parsed.invalid)
    }

    @Test
    fun `text that is not an address is reported`() {
        val parsed = parseRecipients("ann@example.org, not an address, <broken>")

        assertEquals(listOf(Recipient("ann@example.org")), parsed.recipients)
        assertEquals(listOf("not an address", "<broken>"), parsed.invalid)
    }

    @Test
    fun `formatted recipients read back the same`() {
        val recipients =
            listOf(Recipient("ann@example.org", "Ann Example"), Recipient("carl@example.org", "Doe, Carl"), Recipient("b@example.org"))

        val field = formatRecipients(recipients)

        assertEquals("Ann Example <ann@example.org>, \"Doe, Carl\" <carl@example.org>, b@example.org", field)
        assertEquals(recipients, parseRecipients(field).recipients)
    }

    @Test
    fun `bold wraps the selection, an empty selection gets an empty pair with the cursor inside`() {
        val bold = applyFormat(TextFieldValue("say hello now", TextRange(4, 9)), TextFormat.BOLD)
        assertEquals("say **hello** now", bold.text)
        assertEquals(TextRange(6, 11), bold.selection)

        val italic = applyFormat(TextFieldValue("ab", TextRange(1)), TextFormat.ITALIC)
        assertEquals("a__b", italic.text)
        assertEquals(TextRange(2), italic.selection)
    }

    @Test
    fun `bullet marks the selected lines and removes the marks again`() {
        val text = "intro\none\ntwo\nend"
        val listed = applyFormat(TextFieldValue(text, TextRange(7, 12)), TextFormat.BULLET)
        assertEquals("intro\n- one\n- two\nend", listed.text)

        val unlisted = applyFormat(TextFieldValue(listed.text, TextRange(8, 14)), TextFormat.BULLET)
        assertEquals(text, unlisted.text)
    }

    @Test
    fun `typed text becomes paragraphs, line breaks, lists and emphasis, everything else escaped`() {
        val html = markupToHtml("Hi **Ann**,\nsee _this_ & <that>\n\n- one\n- two\nbye snake_case_name")

        assertEquals(
            "<p>Hi <strong>Ann</strong>,<br>see <em>this</em> &amp; &lt;that&gt;</p><ul><li>one</li><li>two</li></ul>" +
                "<p>bye snake_case_name</p>",
            html,
        )
        assertEquals("", markupToHtml("  \n "))
    }

    @Test
    fun `modes survive as strings`() {
        val id = UUID.fromString("00000000-0000-7000-8000-000000000001")
        listOf(ComposeMode.New(), ComposeMode.New(id), ComposeMode.Reply(id), ComposeMode.ReplyAll(id), ComposeMode.Forward(id)).forEach {
            assertEquals(it, ComposeMode.parse(it.encode()))
        }
        assertNull(ComposeMode.parse("reply:nonsense"))
        assertNull(ComposeMode.parse("other:$id"))
    }
}
