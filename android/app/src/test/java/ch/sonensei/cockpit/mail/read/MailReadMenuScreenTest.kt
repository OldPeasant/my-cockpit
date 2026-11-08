package ch.sonensei.cockpit.mail.read

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.mail.list.FolderRole
import ch.sonensei.cockpit.mail.list.MailAccount
import ch.sonensei.cockpit.mail.list.MailAccountFolders
import ch.sonensei.cockpit.mail.list.MailFolder
import ch.sonensei.cockpit.mail.list.MailListAction
import ch.sonensei.cockpit.mail.list.MailMoveTags
import ch.sonensei.cockpit.mail.list.MailRepository
import ch.sonensei.cockpit.mail.list.MailResult
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** The read view's overflow menu: mark unread, flag or unflag, move to a folder of the same account, and spam. */
@RunWith(AndroidJUnit4::class)
class MailReadMenuScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val work = MailAccount(id(80), "Ann Example", "me@example.org")
    private val home = MailAccount(id(81), "Ann Example", "me@example.net")
    private val workInbox = MailFolder(id(90), work.id, "INBOX", "INBOX", FolderRole.INBOX, 0)
    private val workTrash = MailFolder(id(93), work.id, "Trash", "Trash", FolderRole.TRASH, 0)
    private val projects = MailFolder(id(92), work.id, "Projects", "Projects", null, 0)
    private val homeInbox = MailFolder(id(94), home.id, "INBOX", "INBOX", FolderRole.INBOX, 0)
    private val accounts =
        listOf(MailAccountFolders(work, listOf(workInbox, workTrash, projects)), MailAccountFolders(home, listOf(homeInbox)))

    private val message =
        MailMessage(
            id = id(1),
            subject = "Quarterly report",
            from = "Anna Example <anna@example.org>",
            to = listOf("me@example.org"),
            cc = emptyList(),
            date = Instant.parse("2026-10-21T06:00:00Z"),
            seen = true,
            html = "<p>Hello</p>",
            text = "Hello",
            remoteImagesBlocked = 0,
            attachments = emptyList(),
            accountId = work.id,
            folderId = workInbox.id,
        )

    private val calls = mutableListOf<String>()

    private fun show(
        state: MailReadUiState,
        moveTargets: List<MailFolder> = moveTargets(accounts, state.message),
    ) = composeRule.setContent {
        CockpitTheme {
            MailReadContent(
                state,
                MailReadCallbacks(act = { calls += "act $it" }, move = { calls += "move ${it.id}" }),
                zone = ZoneId.of("Europe/Zurich"),
                moveTargets = moveTargets,
                body = { _, modifier -> Box(modifier.testTag(MailReadTags.BODY)) },
            )
        }
    }

    private fun item(action: MailListAction) = composeRule.onNodeWithTag(MailReadTags.action(action))

    private fun openMenu() = composeRule.onNodeWithTag(MailReadTags.MORE).performClick()

    @Test
    fun `the menu offers mark unread, flag, move and spam`() {
        show(MailReadUiState(message = message))
        openMenu()

        item(MailListAction.UNREAD).assertTextContains("Mark unread").assertIsEnabled()
        item(MailListAction.FLAG).assertTextContains("Flag").assertIsEnabled()
        item(MailListAction.MOVE).assertTextContains("Move to folder").assertIsEnabled()
        item(MailListAction.SPAM).assertTextContains("Move to spam").assertIsEnabled()
        item(MailListAction.UNFLAG).assertDoesNotExist()
        item(MailListAction.READ).assertDoesNotExist()
    }

    @Test
    fun `mark unread, flag and spam ask for their action and close the menu`() {
        show(MailReadUiState(message = message))

        openMenu()
        item(MailListAction.UNREAD).performClick()
        item(MailListAction.UNREAD).assertDoesNotExist()
        openMenu()
        item(MailListAction.FLAG).performClick()
        openMenu()
        item(MailListAction.SPAM).performClick()

        assertEquals(listOf("act UNREAD", "act FLAG", "act SPAM"), calls)
    }

    @Test
    fun `a flagged mail offers remove flag instead of flag`() {
        show(MailReadUiState(message = message.copy(flagged = true)))
        openMenu()

        item(MailListAction.FLAG).assertDoesNotExist()
        item(MailListAction.UNFLAG).assertTextContains("Remove flag").performClick()

        assertEquals(listOf("act UNFLAG"), calls)
    }

    @Test
    fun `move opens a picker of the other folders of the mail's account`() {
        show(MailReadUiState(message = message))
        openMenu()
        item(MailListAction.MOVE).performClick()

        composeRule.onNodeWithTag(MailMoveTags.DIALOG).assertIsDisplayed()
        composeRule.onNodeWithTag(MailMoveTags.folder(workInbox.id)).assertDoesNotExist()
        composeRule.onNodeWithTag(MailMoveTags.folder(homeInbox.id)).assertDoesNotExist()
        composeRule.onNodeWithTag(MailMoveTags.folder(workTrash.id)).assertTextEquals("Trash")
        composeRule.onNodeWithTag(MailMoveTags.folder(projects.id)).performClick()

        assertEquals(listOf("move ${projects.id}"), calls)
        composeRule.onNodeWithTag(MailMoveTags.DIALOG).assertDoesNotExist()
    }

    @Test
    fun `the move picker can be cancelled`() {
        show(MailReadUiState(message = message))
        openMenu()
        item(MailListAction.MOVE).performClick()
        composeRule.onNodeWithTag(MailMoveTags.CANCEL).performClick()

        composeRule.onNodeWithTag(MailMoveTags.DIALOG).assertDoesNotExist()
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun `without folders to move to, move is disabled`() {
        show(MailReadUiState(message = message), moveTargets = emptyList())
        openMenu()

        item(MailListAction.MOVE).assertIsNotEnabled().performClick()
        composeRule.onNodeWithTag(MailMoveTags.DIALOG).assertDoesNotExist()
    }

    @Test
    fun `the move targets are the other folders of the mail's account`() {
        assertEquals(listOf(workTrash, projects), moveTargets(accounts, message))
        // The home account has only its inbox, which the mail is in: nowhere to move it.
        assertEquals(emptyList<MailFolder>(), moveTargets(accounts, message.copy(accountId = home.id, folderId = homeInbox.id)))
        // Without a known folder every folder of the account is offered.
        assertEquals(listOf(workInbox, workTrash, projects), moveTargets(accounts, message.copy(folderId = null)))
        assertEquals(emptyList<MailFolder>(), moveTargets(accounts, message.copy(accountId = null)))
        assertEquals(emptyList<MailFolder>(), moveTargets(accounts, message.copy(accountId = id(82))))
        assertEquals(emptyList<MailFolder>(), moveTargets(accounts, null))
        assertEquals(emptyList<MailFolder>(), moveTargets(emptyList(), message))
    }

    @Test
    fun `failed marks and moves are reported`() {
        show(MailReadUiState(message = message, notice = MailReadNotice.ActionFailed(MailListAction.FLAG)))
        composeRule.onNodeWithTag(MailReadTags.NOTICE).assertTextEquals("The mail could not be changed.")
    }

    @Test
    fun `a failed move shows the server's code`() {
        show(MailReadUiState(message = message, notice = MailReadNotice.ActionFailed(MailListAction.MOVE, "OTHER_ACCOUNT")))
        composeRule.onNodeWithTag(MailReadTags.NOTICE).assertTextEquals("The mail could not be moved. Server error: OTHER_ACCOUNT")
    }

    @Test
    fun `a move refused for a missing role folder shows the code, as it names no role folder`() {
        show(MailReadUiState(message = message, notice = MailReadNotice.ActionFailed(MailListAction.MOVE, "NO_ROLE_FOLDER")))
        composeRule.onNodeWithTag(MailReadTags.NOTICE).assertTextEquals("The mail could not be moved. Server error: NO_ROLE_FOLDER")
    }

    @Test
    fun `while an action runs the menu cannot be opened`() {
        show(MailReadUiState(message = message, busy = true))

        composeRule.onNodeWithTag(MailReadTags.MORE).assertIsNotEnabled()
        openMenu()
        item(MailListAction.UNREAD).assertDoesNotExist()
        assertEquals(emptyList<String>(), calls)
    }

    // The route with a real view model

    private fun route(
        reader: MailReadRepository,
        mail: MailRepository = mockk(),
    ) {
        val vm = readViewModel(reader, mail)
        composeRule.setContent {
            CockpitTheme {
                MailReadRoute(
                    id = id(1),
                    onClose = { calls += "close" },
                    onRemoved = { calls += "removed $it" },
                    onMarked = { mailId, action -> calls += "marked $mailId $action" },
                    accounts = accounts,
                    viewModel = vm,
                    attachmentsViewModel = attachmentsViewModel(),
                )
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun `the route's mark unread reports UNREAD to the list and closes`() {
        val reader = mockk<MailReadRepository>()
        coEvery { reader.message(id(1)) } returns MailResult.Ok(message)
        coEvery { reader.act(id(1), MailListAction.UNREAD, null) } returns MailResult.Ok(MailActionOutcome(true))
        route(reader)

        openMenu()
        item(MailListAction.UNREAD).performClick()
        composeRule.waitForIdle()

        assertEquals(listOf("marked ${id(1)} UNREAD", "close"), calls)
    }

    @Test
    fun `the route's flag toggles the menu entry and reports each change to the list`() {
        val reader = mockk<MailReadRepository>()
        coEvery { reader.message(id(1)) } returns MailResult.Ok(message)
        coEvery { reader.act(id(1), any(), null) } returns MailResult.Ok(MailActionOutcome(true))
        route(reader)

        openMenu()
        item(MailListAction.FLAG).performClick()
        composeRule.waitForIdle()
        openMenu()
        item(MailListAction.FLAG).assertDoesNotExist()
        item(MailListAction.UNFLAG).performClick()
        composeRule.waitForIdle()

        assertEquals(listOf("marked ${id(1)} FLAG", "marked ${id(1)} UNFLAG"), calls)
        composeRule.onNodeWithTag(MailReadTags.SUBJECT).assertTextEquals("Quarterly report")
    }

    @Test
    fun `the route moves the mail to the folder picked, reports it removed and closes`() {
        val reader = mockk<MailReadRepository>()
        coEvery { reader.message(id(1)) } returns MailResult.Ok(message)
        coEvery { reader.act(id(1), MailListAction.MOVE, projects.id) } returns MailResult.Ok(MailActionOutcome(true))
        route(reader)

        openMenu()
        item(MailListAction.MOVE).performClick()
        composeRule.onNodeWithTag(MailMoveTags.folder(workInbox.id)).assertDoesNotExist()
        composeRule.onNodeWithTag(MailMoveTags.folder(projects.id)).performClick()
        composeRule.waitForIdle()

        coVerify(exactly = 1) { reader.act(id(1), MailListAction.MOVE, projects.id) }
        assertEquals(listOf("removed ${id(1)}", "close"), calls)
    }

    @Test
    fun `the route's refused unread stays open with a notice`() {
        val reader = mockk<MailReadRepository>()
        coEvery { reader.message(id(1)) } returns MailResult.Ok(message)
        coEvery { reader.act(id(1), MailListAction.UNREAD, null) } returns MailResult.Ok(MailActionOutcome(false))
        route(reader)

        openMenu()
        item(MailListAction.UNREAD).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(MailReadTags.NOTICE).assertTextEquals("The mail could not be changed.")
        assertEquals(emptyList<String>(), calls)
    }
}
