package ch.sonensei.cockpit.mail.compose

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.mail.list.MailAccount
import ch.sonensei.cockpit.mail.list.MailResult
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * Draft save and send states on screen: the screen alone for the phases the other screen tests skip, and the route
 * with a real view model for a refused send, a manual save, discard and leaving a draft that cannot be saved.
 */
@RunWith(AndroidJUnit4::class)
class MailComposeStatesScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val work = MailAccount(id(80), "Work", "me@example.org")
    private val repository = mockk<MailComposeRepository>()
    private var closed = 0

    private fun draft(etag: String = "\"1\"") = Draft(id(1), work.id, etag, emptyList(), emptyList(), emptyList(), "", "", "", emptyList())

    private val editing =
        MailComposeUiState(
            mode = ComposeMode.New(),
            phase = ComposePhase.EDITING,
            accounts = listOf(work),
            accountId = work.id,
        )

    @Before
    fun setUp() {
        coEvery { repository.accounts() } returns MailResult.Ok(listOf(work))
        coEvery { repository.create(any(), any()) } returns MailResult.Ok(draft())
        coEvery { repository.update(any(), any(), any()) } returns MailResult.Ok(draft("\"2\""))
        coEvery { repository.discard(any(), any()) } returns MailResult.Ok(Unit)
    }

    private fun showContent(state: MailComposeUiState) =
        composeRule.setContent { CockpitTheme { MailComposeContent(state, MailComposeCallbacks()) } }

    private fun showRoute(vm: MailComposeViewModel) =
        composeRule.setContent {
            CockpitTheme { MailComposeRoute(mode = ComposeMode.New(work.id), onClose = { closed++ }, viewModel = vm) }
        }

    private fun typeMail() {
        composeRule.onNodeWithTag(MailComposeTags.field(RecipientField.TO)).performTextInput("ann@example.org")
        composeRule.onNodeWithTag(MailComposeTags.BODY).performTextInput("Hello Ann")
    }

    @Test
    fun `while a reply all is prepared the status says so, without form, retry or save`() {
        showContent(MailComposeUiState(mode = ComposeMode.ReplyAll(id(5)), phase = ComposePhase.STARTING))

        composeRule.onNodeWithTag(MailComposeTags.TITLE).assertTextEquals("Reply all")
        composeRule.onNodeWithTag(MailComposeTags.STATUS).assertTextEquals("Preparing the draft…")
        composeRule.onNodeWithTag(MailComposeTags.SUBJECT).assertDoesNotExist()
        composeRule.onNodeWithTag(MailComposeTags.RETRY).assertDoesNotExist()
        composeRule.onNodeWithTag(MailComposeTags.SEND).assertIsNotEnabled()
        composeRule.onNodeWithTag(MailComposeTags.MENU).performClick()
        composeRule.onNodeWithTag(MailComposeTags.SAVE).assertIsNotEnabled()
        composeRule.onNodeWithTag(MailComposeTags.DISCARD).assertIsEnabled()
    }

    @Test
    fun `while sending, neither save nor discard can be chosen`() {
        showContent(editing.copy(phase = ComposePhase.SENDING, to = "ann@example.org", draft = DraftState.SAVED))

        composeRule.onNodeWithTag(MailComposeTags.MENU).performClick()
        composeRule.onNodeWithTag(MailComposeTags.SAVE).assertIsNotEnabled()
        composeRule.onNodeWithTag(MailComposeTags.DISCARD).assertIsNotEnabled()
    }

    @Test
    fun `while back saves the draft, the form, send, save and discard are locked`() {
        showContent(editing.copy(leaving = true, to = "ann@example.org", draft = DraftState.SAVING))

        composeRule.onNodeWithTag(MailComposeTags.SEND).assertIsNotEnabled()
        composeRule.onNodeWithTag(MailComposeTags.SUBJECT).assertIsNotEnabled()
        composeRule.onNodeWithTag(MailComposeTags.BODY).assertIsNotEnabled()
        composeRule.onNodeWithTag(MailComposeTags.ATTACH).assertIsNotEnabled()
        composeRule.onNodeWithTag(MailComposeTags.MENU).performClick()
        composeRule.onNodeWithTag(MailComposeTags.SAVE).assertIsNotEnabled()
        composeRule.onNodeWithTag(MailComposeTags.DISCARD).assertIsNotEnabled()
    }

    @Test
    fun `a sent mail says so and can no longer be changed`() {
        showContent(editing.copy(phase = ComposePhase.SENT, to = "ann@example.org", draft = DraftState.SAVED))

        composeRule.onNodeWithTag(MailComposeTags.STATUS).assertTextEquals("Sent")
        composeRule.onNodeWithTag(MailComposeTags.SEND).assertIsNotEnabled()
        composeRule.onNodeWithTag(MailComposeTags.SUBJECT).assertIsNotEnabled()
        composeRule.onNodeWithTag(MailComposeTags.MENU).performClick()
        composeRule.onNodeWithTag(MailComposeTags.DISCARD).assertIsNotEnabled()
    }

    @Test
    fun `nothing typed yet shows no draft status`() {
        showContent(editing)

        composeRule.onNodeWithTag(MailComposeTags.STATUS).assertDoesNotExist()
        composeRule.onNodeWithTag(MailComposeTags.SEND).assertIsEnabled()
    }

    @Test
    fun `a refused send keeps the route open with the reason and the text, a second send closes it`() {
        coEvery { repository.send(id(1), "\"1\"") } returns SendOutcome.Rejected andThen SendOutcome.Sent
        val vm = MailComposeViewModel(repository)
        showRoute(vm)
        typeMail()

        composeRule.onNodeWithTag(MailComposeTags.SEND).performClick()
        composeRule.waitUntil(WAIT_MS) { vm.uiState.value.problem == ComposeProblem.REJECTED }
        composeRule.waitForIdle()

        assertEquals(0, closed)
        composeRule
            .onNodeWithTag(MailComposeTags.PROBLEM)
            .assertTextEquals("The mail server refused the message. Check the recipients.")
        composeRule.onNodeWithTag(MailComposeTags.STATUS).assertTextEquals("Draft saved")
        composeRule.onNodeWithTag(MailComposeTags.field(RecipientField.TO)).assertTextEquals("ann@example.org")
        composeRule.onNodeWithTag(MailComposeTags.SEND).assertIsEnabled().performClick()
        composeRule.waitUntil(WAIT_MS) { closed == 1 }

        coVerify(exactly = 1) { repository.create(any(), any()) }
        coVerify(exactly = 2) { repository.send(id(1), "\"1\"") }
    }

    @Test
    fun `save in the menu stores the draft and shows it saved, discard then deletes it and closes`() {
        val vm = MailComposeViewModel(repository)
        showRoute(vm)
        typeMail()
        composeRule.onNodeWithTag(MailComposeTags.STATUS).assertTextEquals("Not saved yet")

        composeRule.onNodeWithTag(MailComposeTags.MENU).performClick()
        composeRule.onNodeWithTag(MailComposeTags.SAVE).performClick()
        composeRule.waitUntil(WAIT_MS) { vm.uiState.value.draft == DraftState.SAVED }
        composeRule.onNodeWithTag(MailComposeTags.STATUS).assertTextEquals("Draft saved")
        composeRule.onNodeWithTag(MailComposeTags.ACCOUNT).assertIsNotEnabled()

        composeRule.onNodeWithTag(MailComposeTags.MENU).performClick()
        composeRule.onNodeWithTag(MailComposeTags.DISCARD).performClick()
        composeRule.waitUntil(WAIT_MS) { closed == 1 }

        coVerify(exactly = 1) { repository.create(work.id, match { it.html == "<p>Hello Ann</p>" }) }
        coVerify(exactly = 1) { repository.discard(id(1), "\"1\"") }
    }

    @Test
    fun `back with a draft that cannot be saved stays, says why and offers to leave without saving`() {
        coEvery { repository.create(any(), any()) } returns MailResult.Offline
        val vm = MailComposeViewModel(repository)
        showRoute(vm)
        typeMail()

        composeRule.onNodeWithTag(MailComposeTags.BACK).performClick()
        composeRule.waitUntil(WAIT_MS) { vm.uiState.value.leaveBlocked }
        composeRule.waitForIdle()

        assertEquals(0, closed)
        composeRule.onNodeWithTag(MailComposeTags.PROBLEM).assertTextEquals("Can't reach the server. Your text is still here.")
        composeRule.onNodeWithTag(MailComposeTags.STATUS).assertTextEquals("Not saved yet")
        composeRule.onNodeWithTag(MailComposeTags.ABANDON).performClick()
        composeRule.waitUntil(WAIT_MS) { closed == 1 }

        coVerify(exactly = 0) { repository.discard(any(), any()) }
    }

    private companion object {
        const val WAIT_MS = 5_000L
    }
}
