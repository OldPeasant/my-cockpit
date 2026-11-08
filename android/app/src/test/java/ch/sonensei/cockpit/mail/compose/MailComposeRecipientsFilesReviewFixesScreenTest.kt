package ch.sonensei.cockpit.mail.compose

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.percentOffset
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.requestFocus
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.mail.list.MailAccount
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Review fixes of the recipient chips on the screen (CR-1, CR-4): leaving a field hides suggestions, chip taps are safe. */
@RunWith(AndroidJUnit4::class)
class MailComposeRecipientsFilesReviewFixesScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val work = MailAccount(id(80), "Work", "me@example.org")

    private val editing =
        MailComposeUiState(
            mode = ComposeMode.New(),
            phase = ComposePhase.EDITING,
            accounts = listOf(work),
            accountId = work.id,
        )

    private val calls = mutableListOf<String>()

    private val callbacks =
        MailComposeCallbacks(
            to = { calls += "to:$it" },
            cc = { calls += "cc:$it" },
            leaveRecipients = { calls += "leave" },
        )

    private fun show(state: MailComposeUiState) =
        composeRule.setContent {
            CockpitTheme { MailComposeContent(state, callbacks, suggestions = RecipientSuggestions()) }
        }

    @Test
    fun `focus leaving a recipient field hides the suggestions`() {
        show(editing.copy(to = "an"))
        composeRule.onNodeWithTag(MailComposeTags.field(RecipientField.TO)).requestFocus()
        composeRule.waitForIdle()
        assertTrue(calls.isEmpty())

        composeRule.onNodeWithTag(MailComposeTags.SUBJECT).requestFocus()
        composeRule.waitForIdle()

        assertEquals(listOf("leave"), calls)
    }

    @Test
    fun `moving from To to Cc leaves To`() {
        show(editing.copy(showCcBcc = true, to = "an"))
        composeRule.onNodeWithTag(MailComposeTags.field(RecipientField.TO)).requestFocus()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(MailComposeTags.field(RecipientField.CC)).requestFocus()
        composeRule.waitForIdle()

        assertEquals(listOf("leave"), calls)
    }

    @Test
    fun `fields that never had focus report no leave`() {
        show(editing.copy(showCcBcc = true))
        composeRule.waitForIdle()

        assertTrue(calls.isEmpty())
    }

    @Test
    fun `tapping the body of a chip keeps the recipient`() {
        show(editing.copy(to = "ann@example.org, ben@example.org, "))

        // A tap on the label side of the chip (the test text renders narrow, so its center is next to the close icon).
        composeRule.onNodeWithTag(MailComposeTags.recipient(RecipientField.TO, 0)).performTouchInput { click(percentOffset(0.1f, 0.5f)) }
        // The chip body has no click action at all (and-mail-chip-a11y), so TalkBack announces none.
        composeRule
            .onNodeWithTag(
                MailComposeTags.recipient(RecipientField.TO, 0),
            ).assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))

        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun `the close icon of the second chip removes only that one`() {
        show(editing.copy(to = "ann@example.org, ben@example.org, "))

        composeRule.onNodeWithContentDescription("Remove ben@example.org").performClick()

        assertEquals(listOf("to:ann@example.org, "), calls)
    }

    @Test
    fun `while sending the close icon of a chip removes nothing`() {
        show(editing.copy(phase = ComposePhase.SENDING, to = "ann@example.org, "))

        composeRule.onNodeWithContentDescription("Remove ann@example.org").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("Remove ann@example.org").performClick()

        assertTrue(calls.isEmpty())
    }
}
