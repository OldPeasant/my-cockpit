package ch.sonensei.cockpit.mail.list

import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.util.UUID

/** The folder dialogs while the server works, the keyboard's Done, and the picker rows that still open their folder. */
@RunWith(AndroidJUnit4::class)
class MailFolderManageScreenEdgeCasesTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val repo = mockk<MailRepository>()
    private val account = MailAccount(id(80), "Ann Example", "me@example.org")
    private val inbox = MailFolder(id(90), account.id, "INBOX", "INBOX", FolderRole.INBOX, 0, 1, "/")
    private val projects = MailFolder(id(92), account.id, "Projects", "Projects", null, 0, 3, "/")

    private lateinit var vm: MailListViewModel

    @Before
    fun setUp() {
        coEvery { repo.accounts() } returns MailResult.Ok(listOf(MailAccountFolders(account, listOf(inbox, projects))))
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
    fun `while the server works the name and the confirm button are disabled, then the dialog closes`() {
        val gate = CompletableDeferred<MailResult<MailFolder>>()
        val renamed = projects.copy(name = "Clients", path = "Clients", version = 4)
        coEvery { repo.renameFolder(projects, "Clients") } coAnswers { gate.await() }
        show()

        openFolderMenu(projects)
        composeRule.onNodeWithTag(MailListTags.FOLDER_RENAME).performClick()
        composeRule.onNodeWithTag(MailListTags.FOLDER_NAME).performTextClearance()
        composeRule.onNodeWithTag(MailListTags.FOLDER_NAME).performTextInput("Clients")
        composeRule.onNodeWithTag(MailListTags.FOLDER_CONFIRM).assertIsEnabled().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(MailListTags.FOLDER_CONFIRM).assertIsNotEnabled()
        composeRule.onNodeWithTag(MailListTags.FOLDER_NAME).assertIsNotEnabled()
        composeRule.onNodeWithTag(MailListTags.FOLDER_CANCEL).assertIsEnabled()

        coEvery { repo.accounts() } returns MailResult.Ok(listOf(MailAccountFolders(account, listOf(inbox, renamed))))
        gate.complete(MailResult.Ok(renamed))
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(MailListTags.FOLDER_DIALOG).assertDoesNotExist()
        coVerify(exactly = 1) { repo.renameFolder(projects, "Clients") }
        openPicker()
        composeRule.onNodeWithTag(MailListTags.folder(projects.id)).assertTextContains("Clients")
    }

    @Test
    fun `the delete confirm button is disabled while the folder is deleted`() {
        val gate = CompletableDeferred<MailResult<Unit>>()
        coEvery { repo.deleteFolder(projects) } coAnswers { gate.await() }
        show()

        openFolderMenu(projects)
        composeRule.onNodeWithTag(MailListTags.FOLDER_DELETE).performClick()
        composeRule.onNodeWithTag(MailListTags.FOLDER_CONFIRM).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(MailListTags.FOLDER_CONFIRM).assertIsNotEnabled()
        composeRule.onNodeWithTag(MailListTags.FOLDER_CONFIRM).performClick()

        coEvery { repo.accounts() } returns MailResult.Ok(listOf(MailAccountFolders(account, listOf(inbox))))
        gate.complete(MailResult.Ok(Unit))
        composeRule.waitForIdle()

        coVerify(exactly = 1) { repo.deleteFolder(projects) }
        composeRule.onNodeWithTag(MailListTags.FOLDER_DIALOG).assertDoesNotExist()
    }

    @Test
    fun `Done on the keyboard creates the folder`() {
        val receipts = MailFolder(id(94), account.id, "Receipts", "Receipts", null, 0, 1, "/")
        coEvery { repo.createFolder(account.id, null, "Receipts") } returns MailResult.Ok(receipts)
        show()

        openPicker()
        composeRule.onNodeWithTag(MailListTags.newFolder(account.id)).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("New folder").assertIsDisplayed()
        composeRule.onNodeWithTag(MailListTags.FOLDER_NAME).performTextInput("Receipts")
        coEvery { repo.accounts() } returns MailResult.Ok(listOf(MailAccountFolders(account, listOf(inbox, projects, receipts))))
        composeRule.onNodeWithTag(MailListTags.FOLDER_NAME).performImeAction()
        composeRule.waitForIdle()

        coVerify(exactly = 1) { repo.createFolder(account.id, null, "Receipts") }
        composeRule.onNodeWithTag(MailListTags.FOLDER_DIALOG).assertDoesNotExist()
    }

    @Test
    fun `Done on the keyboard with an empty name sends nothing`() {
        show()

        openPicker()
        composeRule.onNodeWithTag(MailListTags.newFolder(account.id)).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(MailListTags.FOLDER_NAME).performTextInput("   ")
        composeRule.onNodeWithTag(MailListTags.FOLDER_CONFIRM).assertIsNotEnabled()
        composeRule.onNodeWithTag(MailListTags.FOLDER_NAME).performImeAction()
        composeRule.waitForIdle()

        coVerify(exactly = 0) { repo.createFolder(any(), any(), any()) }
        composeRule.onNodeWithTag(MailListTags.FOLDER_DIALOG).assertIsDisplayed()
    }

    @Test
    fun `a tap on a folder row still opens the folder, the actions sit beside it`() {
        show()

        openPicker()
        composeRule.onNodeWithTag(MailListTags.folder(projects.id)).performClick()
        composeRule.waitForIdle()

        assertEquals(Mailbox.Folder(account.id, projects.id), vm.uiState.value.mailbox)
        assertEquals(null, vm.uiState.value.folderEdit)
        composeRule.onNodeWithTag(MailListTags.MAILBOX).assertTextContains("Projects")
    }

    @Test
    fun `a folder that cannot be deleted says so in the dialog, which stays open`() {
        coEvery { repo.deleteFolder(projects) } returns MailResult.Failed(409)
        show()

        openFolderMenu(projects)
        composeRule.onNodeWithTag(MailListTags.FOLDER_DELETE).performClick()
        composeRule.onNodeWithTag(MailListTags.FOLDER_CONFIRM).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(MailListTags.FOLDER_DIALOG).assertIsDisplayed()
        composeRule.onNodeWithTag(MailListTags.FOLDER_ERROR).assertTextContains("it holds a special folder", substring = true)
        composeRule.onNodeWithTag(MailListTags.FOLDER_CONFIRM).assertIsEnabled()
    }
}
