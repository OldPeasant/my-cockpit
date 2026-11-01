package ch.sonensei.cockpit.mail.list

import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.util.UUID

/** The folder actions of the mailbox picker and their dialogs, wired to the real view model with a fake repository. */
@RunWith(AndroidJUnit4::class)
class MailFolderManageScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val repo = mockk<MailRepository>()
    private val account = MailAccount(id(80), "Ann Example", "me@example.org")
    private val inbox = MailFolder(id(90), account.id, "INBOX", "INBOX", FolderRole.INBOX, 0, 1, "/")
    private val sent = MailFolder(id(91), account.id, "Sent", "Sent", FolderRole.SENT, 0, 1, "/")
    private val projects = MailFolder(id(92), account.id, "Projects", "Projects", null, 0, 3, "/")

    private lateinit var vm: MailListViewModel

    @Before
    fun setUp() {
        coEvery { repo.accounts() } returns MailResult.Ok(listOf(MailAccountFolders(account, listOf(inbox, sent, projects))))
        coEvery { repo.messages(any(), null) } returns
            MailResult.Ok(MailPage(listOf(MailSummary(id(1), account.id, "Anna", "Hello", "", Instant.EPOCH, true, false, false)), null))
    }

    private fun show(mailbox: Mailbox = Mailbox.Unified) {
        vm = MailListViewModel(repo)
        vm.select(mailbox)
        composeRule.setContent {
            val state by vm.uiState.collectAsStateWithLifecycle()
            CockpitTheme {
                MailListContent(
                    state,
                    MailListCallbacks(
                        select = vm::select,
                        folders =
                            FolderCallbacks(
                                create = vm::startCreateFolder,
                                rename = vm::startRenameFolder,
                                delete = vm::startDeleteFolder,
                                submit = vm::submitFolderEdit,
                                cancel = vm::cancelFolderEdit,
                            ),
                    ),
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun openPicker() {
        composeRule.onNodeWithTag(MailListTags.MAILBOX).performClick()
        composeRule.waitForIdle()
    }

    private fun openFolderMenu(folder: MailFolder) {
        openPicker()
        composeRule.onNodeWithTag(MailListTags.folderActions(folder.id)).performClick()
        composeRule.waitForIdle()
    }

    @Test
    fun `New folder per account creates a folder that shows in the picker at once`() {
        val receipts = MailFolder(id(94), account.id, "Receipts", "Receipts", null, 0, 1, "/")
        coEvery { repo.createFolder(account.id, null, "Receipts") } returns MailResult.Ok(receipts)
        show()

        openPicker()
        composeRule.onNodeWithContentDescription("New folder in me@example.org").performClick()
        composeRule.onNodeWithTag(MailListTags.FOLDER_CONFIRM).assertIsNotEnabled()
        composeRule.onNodeWithTag(MailListTags.FOLDER_NAME).performTextInput("Receipts")
        // The reload after it fails: what the picker shows comes from the answer alone.
        coEvery { repo.accounts() } returns MailResult.Offline
        composeRule.onNodeWithTag(MailListTags.FOLDER_CONFIRM).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(MailListTags.FOLDER_DIALOG).assertDoesNotExist()
        openPicker()
        composeRule.onNodeWithTag(MailListTags.folder(receipts.id)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `New subfolder creates the folder inside the chosen one`() {
        val alpha = MailFolder(id(93), account.id, "Alpha", "Projects/Alpha", null, 1, 1, "/")
        coEvery { repo.createFolder(account.id, projects, "Alpha") } returns MailResult.Ok(alpha)
        show()

        openFolderMenu(projects)
        composeRule.onNodeWithTag(MailListTags.FOLDER_NEW_SUB).performClick()
        composeRule.onNodeWithText("New folder in Projects").assertIsDisplayed()
        composeRule.onNodeWithTag(MailListTags.FOLDER_NAME).performTextInput("Alpha")
        coEvery { repo.accounts() } returns MailResult.Offline
        composeRule.onNodeWithTag(MailListTags.FOLDER_CONFIRM).performClick()
        composeRule.waitForIdle()

        coVerify { repo.createFolder(account.id, projects, "Alpha") }
        openPicker()
        composeRule.onNodeWithTag(MailListTags.folder(alpha.id)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `INBOX offers only a new subfolder, a folder with a role no delete, others everything`() {
        show()

        openFolderMenu(inbox)
        composeRule.onNodeWithTag(MailListTags.FOLDER_NEW_SUB).assertIsDisplayed()
        composeRule.onNodeWithTag(MailListTags.FOLDER_RENAME).assertDoesNotExist()
        composeRule.onNodeWithTag(MailListTags.FOLDER_DELETE).assertDoesNotExist()
        composeRule.onNodeWithTag(MailListTags.FOLDER_NEW_SUB).performClick()
        vm.cancelFolderEdit()
        composeRule.waitForIdle()

        openFolderMenu(sent)
        composeRule.onNodeWithTag(MailListTags.FOLDER_RENAME).assertIsDisplayed()
        composeRule.onNodeWithTag(MailListTags.FOLDER_DELETE).assertDoesNotExist()
        composeRule.onNodeWithTag(MailListTags.FOLDER_NEW_SUB).performClick()
        vm.cancelFolderEdit()
        composeRule.waitForIdle()

        openFolderMenu(projects)
        composeRule.onNodeWithTag(MailListTags.FOLDER_RENAME).assertIsDisplayed()
        composeRule.onNodeWithTag(MailListTags.FOLDER_DELETE).assertIsDisplayed()
    }

    @Test
    fun `the folder actions have TalkBack labels`() {
        show()

        openPicker()
        composeRule.onNodeWithContentDescription("New folder in me@example.org").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Folder actions: Projects").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Folder actions: Inbox").assertIsDisplayed()
    }

    @Test
    fun `rename starts with the current name, a taken name is shown in the dialog which stays open`() {
        coEvery { repo.renameFolder(projects, "Sent") } returns MailResult.Failed(409)
        show()

        openFolderMenu(projects)
        composeRule.onNodeWithTag(MailListTags.FOLDER_RENAME).performClick()
        composeRule.onNodeWithTag(MailListTags.FOLDER_NAME).assertTextContains("Projects")
        composeRule.onNodeWithTag(MailListTags.FOLDER_NAME).performTextClearance()
        composeRule.onNodeWithTag(MailListTags.FOLDER_NAME).performTextInput("Sent")
        composeRule.onNodeWithTag(MailListTags.FOLDER_CONFIRM).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(MailListTags.FOLDER_DIALOG).assertIsDisplayed()
        composeRule.onNodeWithTag(MailListTags.FOLDER_ERROR).assertTextContains("A folder with this name already exists here.")
        composeRule.onNodeWithTag(MailListTags.FOLDER_NAME).assertTextContains("Sent")
    }

    @Test
    fun `invalid names and an unreachable server are shown in the dialog`() {
        coEvery { repo.createFolder(account.id, null, "a/b") } returns MailResult.Failed(400)
        coEvery { repo.createFolder(account.id, null, "Receipts") } returns MailResult.Offline
        show()

        openPicker()
        composeRule.onNodeWithTag(MailListTags.newFolder(account.id)).performClick()
        composeRule.onNodeWithTag(MailListTags.FOLDER_NAME).performTextInput("a/b")
        composeRule.onNodeWithTag(MailListTags.FOLDER_CONFIRM).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(MailListTags.FOLDER_ERROR).assertTextContains("This name is not allowed", substring = true)

        composeRule.onNodeWithTag(MailListTags.FOLDER_NAME).performTextClearance()
        composeRule.onNodeWithTag(MailListTags.FOLDER_NAME).performTextInput("Receipts")
        composeRule.onNodeWithTag(MailListTags.FOLDER_CONFIRM).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(MailListTags.FOLDER_ERROR).assertTextContains("Can't reach the server", substring = true)
        composeRule.onNodeWithTag(MailListTags.FOLDER_DIALOG).assertIsDisplayed()
    }

    @Test
    fun `delete asks for confirmation, says messages and subfolders go too, and switches to the unified inbox`() {
        coEvery { repo.deleteFolder(projects) } returns MailResult.Ok(Unit)
        show(Mailbox.Folder(account.id, projects.id))
        composeRule.onNodeWithTag(MailListTags.MAILBOX).assertTextContains("Projects")

        openFolderMenu(projects)
        composeRule.onNodeWithTag(MailListTags.FOLDER_DELETE).performClick()
        composeRule.onNodeWithText("Delete Projects?").assertIsDisplayed()
        composeRule
            .onNodeWithText("The folder, its subfolders and all their messages are deleted on the mail server too", substring = true)
            .assertIsDisplayed()
        coVerify(exactly = 0) { repo.deleteFolder(any()) }
        composeRule.onNodeWithTag(MailListTags.FOLDER_CONFIRM).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(MailListTags.FOLDER_DIALOG).assertDoesNotExist()
        assertEquals(Mailbox.Unified, vm.uiState.value.mailbox)
        composeRule.onNodeWithTag(MailListTags.MAILBOX).assertTextContains("All inboxes")
    }

    @Test
    fun `cancelling the delete keeps the folder`() {
        show()

        openFolderMenu(projects)
        composeRule.onNodeWithTag(MailListTags.FOLDER_DELETE).performClick()
        composeRule.onNodeWithTag(MailListTags.FOLDER_CANCEL).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(MailListTags.FOLDER_DIALOG).assertDoesNotExist()
        coVerify(exactly = 0) { repo.deleteFolder(any()) }
    }

    @Test
    fun `a stale folder closes the dialog and shows a short message`() {
        coEvery { repo.renameFolder(projects, "Clients") } returns MailResult.Failed(412)
        show()

        openFolderMenu(projects)
        composeRule.onNodeWithTag(MailListTags.FOLDER_RENAME).performClick()
        composeRule.onNodeWithTag(MailListTags.FOLDER_NAME).performTextClearance()
        composeRule.onNodeWithTag(MailListTags.FOLDER_NAME).performTextInput("Clients")
        composeRule.onNodeWithTag(MailListTags.FOLDER_CONFIRM).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(MailListTags.FOLDER_DIALOG).assertDoesNotExist()
        composeRule.onNodeWithTag(MailListTags.MESSAGE).assertTextContains("The folder was changed elsewhere", substring = true)
    }
}
