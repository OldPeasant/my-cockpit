package ch.sonensei.cockpit.mail.compose

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.mail.list.MailAccount
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** The compose screen alone, driven by states the tests control. */
@RunWith(AndroidJUnit4::class)
class MailComposeScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val work = MailAccount(id(80), "Work", "me@example.org")
    private val home = MailAccount(id(81), "Home", "me@example.net")

    private val editing =
        MailComposeUiState(
            mode = ComposeMode.New(),
            phase = ComposePhase.EDITING,
            accounts = listOf(home, work),
            accountId = work.id,
        )

    private val calls = mutableListOf<String>()

    private val callbacks =
        MailComposeCallbacks(
            back = { calls += "back" },
            abandon = { calls += "abandon" },
            retry = { calls += "retry" },
            account = { calls += "account:$it" },
            to = { calls += "to:$it" },
            showCcBcc = { calls += "cc-bcc" },
            subject = { calls += "subject:$it" },
            body = { calls += "body:${it.text}" },
            format = { calls += "format:$it" },
            attach = { calls += "attach" },
            removeAttachment = { calls += "remove:$it" },
            save = { calls += "save" },
            send = { calls += "send" },
            discard = { calls += "discard" },
        )

    private fun show(state: MailComposeUiState) =
        composeRule.setContent {
            CockpitTheme { MailComposeContent(state, callbacks) }
        }

    @Test
    fun `a new mail shows the form - typing, formatting, attach and send reach the callbacks`() {
        show(editing)

        composeRule.onNodeWithTag(MailComposeTags.TITLE).assertTextEquals("New message")
        composeRule.onNodeWithText("Work <me@example.org>").assertIsDisplayed()
        composeRule.onNodeWithTag(MailComposeTags.field(RecipientField.TO)).performTextInput("ann@example.org")
        composeRule.onNodeWithTag(MailComposeTags.SUBJECT).performTextInput("Plan")
        composeRule.onNodeWithTag(MailComposeTags.BODY).performTextInput("Hi")
        composeRule.onNodeWithTag(MailComposeTags.format(TextFormat.BOLD)).performClick()
        composeRule.onNodeWithTag(MailComposeTags.format(TextFormat.BULLET)).performClick()
        composeRule.onNodeWithTag(MailComposeTags.CC_BCC).performClick()
        composeRule.onNodeWithTag(MailComposeTags.ATTACH).performClick()
        composeRule.onNodeWithTag(MailComposeTags.SEND).performClick()
        composeRule.onNodeWithTag(MailComposeTags.BACK).performClick()

        assertEquals(
            listOf(
                "to:ann@example.org",
                "subject:Plan",
                "body:Hi",
                "format:BOLD",
                "format:BULLET",
                "cc-bcc",
                "attach",
                "send",
                "back",
            ),
            // A controlled field reports its (unchanged) value again when it loses focus.
            calls.filterNot { it == "to:" || it == "subject:" },
        )
    }

    @Test
    fun `the account is picked from the menu before the first save`() {
        show(editing)

        composeRule.onNodeWithTag(MailComposeTags.ACCOUNT).performClick()
        composeRule.onNodeWithTag(MailComposeTags.account(home.id)).performClick()

        assertEquals(listOf("account:${home.id}"), calls)
    }

    @Test
    fun `a saved draft cannot change its account`() {
        show(editing.copy(accountLocked = true, draft = DraftState.SAVED))

        composeRule.onNodeWithTag(MailComposeTags.ACCOUNT).assertIsNotEnabled()
        composeRule.onNodeWithTag(MailComposeTags.STATUS).assertTextEquals("Draft saved")
    }

    @Test
    fun `the draft states are shown`() {
        val states =
            mapOf(
                DraftState.UNSAVED to "Not saved yet",
                DraftState.SAVING to "Saving draft…",
                DraftState.SAVED to "Draft saved",
            )
        var current by mutableStateOf(editing)
        composeRule.setContent { CockpitTheme { MailComposeContent(current, callbacks) } }
        states.forEach { (draft, text) ->
            current = editing.copy(draft = draft)
            composeRule.onNodeWithTag(MailComposeTags.STATUS).assertTextEquals(text)
        }
    }

    @Test
    fun `while sending nothing can be edited nor sent again`() {
        show(editing.copy(phase = ComposePhase.SENDING, to = "ann@example.org"))

        composeRule.onNodeWithTag(MailComposeTags.STATUS).assertTextEquals("Sending…")
        composeRule.onNodeWithTag(MailComposeTags.SEND).assertIsNotEnabled()
        composeRule.onNodeWithTag(MailComposeTags.ATTACH).assertIsNotEnabled()
        composeRule.onNodeWithTag(MailComposeTags.format(TextFormat.BOLD)).assertIsNotEnabled()
    }

    @Test
    fun `send waits for uploads`() {
        show(editing.copy(uploading = listOf("plan.pdf")))

        composeRule.onNodeWithTag(MailComposeTags.SEND).assertIsNotEnabled()
        composeRule.onNodeWithTag(MailComposeTags.UPLOADING).assertTextEquals("Uploading plan.pdf…")
    }

    @Test
    fun `send problems are shown`() {
        val problems =
            mapOf(
                ComposeProblem.REJECTED to "The mail server refused the message. Check the recipients.",
                ComposeProblem.NO_RECIPIENT to "Add at least one recipient.",
                ComposeProblem.OFFLINE to "Can't reach the server. Your text is still here.",
            )
        var current by mutableStateOf(editing)
        composeRule.setContent { CockpitTheme { MailComposeContent(current, callbacks) } }
        problems.forEach { (problem, text) ->
            current = editing.copy(problem = problem)
            composeRule.onNodeWithTag(MailComposeTags.PROBLEM).assertTextEquals(text)
        }
    }

    @Test
    fun `attachments are listed without inline parts and can be removed`() {
        show(
            editing.copy(
                attachments = listOf(DraftAttachment("2", "plan.pdf", 10), DraftAttachment("3", "logo.png", 5, inline = true)),
            ),
        )

        composeRule.onNodeWithText("plan.pdf").assertIsDisplayed()
        composeRule.onNodeWithText("logo.png").assertDoesNotExist()
        composeRule.onNodeWithTag(MailComposeTags.attachment("2")).performClick()

        assertEquals(listOf("remove:2"), calls)
    }

    @Test
    fun `a reply shows its quote on request`() {
        show(
            editing.copy(
                mode = ComposeMode.Reply(id(5)),
                accountLocked = true,
                to = "ann@example.org",
                subject = "Re: Plan",
                quotedText = "> old text",
            ),
        )

        composeRule.onNodeWithTag(MailComposeTags.TITLE).assertTextEquals("Reply")
        composeRule.onNodeWithText("> old text").assertDoesNotExist()
        composeRule.onNodeWithTag(MailComposeTags.QUOTED).performClick()
        composeRule.onNodeWithText("> old text").assertExists()
    }

    @Test
    fun `a reply being prepared shows no form, a failed start offers retry`() {
        show(MailComposeUiState(mode = ComposeMode.Forward(id(5)), phase = ComposePhase.STARTING, problem = ComposeProblem.START_FAILED))

        composeRule.onNodeWithTag(MailComposeTags.TITLE).assertTextEquals("Forward")
        composeRule.onNodeWithTag(MailComposeTags.SUBJECT).assertDoesNotExist()
        composeRule.onNodeWithTag(MailComposeTags.RETRY).performClick()

        assertEquals(listOf("retry"), calls)
    }

    @Test
    fun `save and discard are in the menu, leaving unsaved is offered after a failed leave`() {
        show(editing.copy(leaveBlocked = true, problem = ComposeProblem.OFFLINE))

        composeRule.onNodeWithTag(MailComposeTags.MENU).performClick()
        composeRule.onNodeWithTag(MailComposeTags.SAVE).assertIsEnabled().performClick()
        composeRule.onNodeWithTag(MailComposeTags.MENU).performClick()
        composeRule.onNodeWithTag(MailComposeTags.DISCARD).performClick()
        composeRule.onNodeWithTag(MailComposeTags.ABANDON).performClick()

        assertEquals(listOf("save", "discard", "abandon"), calls)
    }
}
