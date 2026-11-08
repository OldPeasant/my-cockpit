package ch.sonensei.cockpit.mail.compose

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.mail.list.MailAccount
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * Edge cases of the recipient chips, suggestions and the Files picker on the compose screen (test-writer): quoted
 * names, suggestions of another field or of a locked compose, Done, leaving the fields, the picker's root and problems.
 */
@RunWith(AndroidJUnit4::class)
class MailComposeRecipientsFilesScreenEdgeCasesTest {
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
            subject = { calls += "subject:$it" },
            leaveRecipients = { calls += "leave" },
            attach = { calls += "attach" },
            attachFromFiles = { calls += "files" },
        )

    private val pickerCallbacks =
        FilePickerCallbacks(
            retry = { calls += "retry" },
            close = { calls += "close" },
        )

    private fun show(
        state: MailComposeUiState,
        suggestions: RecipientSuggestions = RecipientSuggestions(),
        picker: FilePickerState? = null,
    ) = composeRule.setContent {
        CockpitTheme {
            MailComposeContent(state, callbacks, suggestions = suggestions, filePicker = picker, pickerCallbacks = pickerCallbacks)
        }
    }

    @Test
    fun `a quoted name shows unquoted on its chip, removing it keeps the others`() {
        show(editing.copy(to = "\"Doe, Carl\" <carl@example.org>, ben@example.org, "))

        composeRule.onNodeWithText("Doe, Carl").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Remove carl@example.org").assertIsDisplayed()
        composeRule.onNodeWithTag(MailComposeTags.recipient(RecipientField.TO, 0)).performClick()

        assertEquals(listOf("to:ben@example.org, "), calls)
    }

    @Test
    fun `done finishes the typed text as a chip`() {
        show(editing.copy(to = "ann@example.org, ben@example.org"))

        composeRule.onNodeWithTag(MailComposeTags.field(RecipientField.TO)).performImeAction()

        assertEquals(listOf("to:ann@example.org, ben@example.org, "), calls)
    }

    @Test
    fun `suggestions of another field do not show under To`() {
        show(editing.copy(to = "an"), RecipientSuggestions(RecipientField.CC, listOf(RecipientSuggestion("ann@example.org", null))))

        composeRule.onNodeWithTag(MailComposeTags.suggestions(RecipientField.TO)).assertDoesNotExist()
        composeRule.onNodeWithText("ann@example.org").assertDoesNotExist()
    }

    @Test
    fun `suggestions under Cc finish the Cc field`() {
        show(
            editing.copy(showCcBcc = true, cc = "ann@example.org, be"),
            RecipientSuggestions(RecipientField.CC, listOf(RecipientSuggestion("ben@example.org", "Ben Example"))),
        )

        composeRule.onNodeWithContentDescription("Recent Ben Example ben@example.org").assertIsDisplayed()
        composeRule.onNodeWithTag(MailComposeTags.suggestion("ben@example.org")).performClick()

        assertEquals(listOf("cc:ann@example.org, Ben Example <ben@example.org>, "), calls)
    }

    @Test
    fun `while sending no suggestions show and chips cannot be removed`() {
        show(
            editing.copy(phase = ComposePhase.SENDING, to = "ann@example.org, be"),
            RecipientSuggestions(RecipientField.TO, listOf(RecipientSuggestion("ben@example.org", null))),
        )

        composeRule.onNodeWithTag(MailComposeTags.suggestions(RecipientField.TO)).assertDoesNotExist()
        composeRule.onNodeWithTag(MailComposeTags.recipient(RecipientField.TO, 0)).assertIsNotEnabled()
        composeRule.onNodeWithTag(MailComposeTags.ATTACH).assertIsNotEnabled()
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `typing the subject hides the recipient suggestions first`() {
        show(editing)

        composeRule.onNodeWithTag(MailComposeTags.SUBJECT).performTextInput("Plan")

        assertEquals(listOf("leave", "subject:Plan"), calls)
    }

    @Test
    fun `attach from this device picks a device file`() {
        show(editing)

        composeRule.onNodeWithTag(MailComposeTags.ATTACH).performClick()
        composeRule.onNodeWithTag(MailComposeTags.ATTACH_DEVICE).performClick()

        assertEquals(listOf("attach"), calls)
    }

    @Test
    fun `at the root the picker has no parent button, an offline folder offers a retry`() {
        show(editing, picker = FilePickerState(loading = false, problem = FilePickerProblem.OFFLINE))

        composeRule.onNodeWithText("Files").assertIsDisplayed()
        composeRule.onNodeWithTag(MailComposeTags.FILES_UP).assertDoesNotExist()
        composeRule.onNodeWithTag(MailComposeTags.FILES_PROBLEM).assertIsDisplayed()
        composeRule.onNodeWithText("Can't reach the server. Your text is still here.").assertIsDisplayed()
        composeRule.onNodeWithText("Retry").performClick()

        assertEquals(listOf("retry"), calls)
    }

    @Test
    fun `a folder still loading says neither empty nor failed`() {
        show(editing, picker = FilePickerState(loading = true))

        composeRule.onNodeWithTag(MailComposeTags.FILES).assertIsDisplayed()
        composeRule.onNodeWithTag(MailComposeTags.FILES_EMPTY).assertDoesNotExist()
        composeRule.onNodeWithTag(MailComposeTags.FILES_PROBLEM).assertDoesNotExist()
    }
}
