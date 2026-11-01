package ch.sonensei.cockpit.mail.list

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.UUID

/** The folder dialogs: what they send, how the list changes at once, which errors keep them open, the switch after delete. */
@OptIn(ExperimentalCoroutinesApi::class)
class MailFolderManageViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repo = mockk<MailRepository>()

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val account = MailAccount(id(80), "Work", "me@example.org")
    private val inbox = MailFolder(id(90), account.id, "INBOX", "INBOX", FolderRole.INBOX, 0, 1, "/")
    private val projects = MailFolder(id(92), account.id, "Projects", "Projects", null, 0, 3, "/")
    private val alpha = MailFolder(id(93), account.id, "Alpha", "Projects/Alpha", null, 1, 1, "/")
    private val folders = listOf(inbox, projects, alpha)

    private fun mail(n: Int) = MailSummary(id(n), account.id, "Anna", "Subject $n", "", Instant.EPOCH, false, false, false)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        coEvery { repo.accounts() } returns MailResult.Ok(listOf(MailAccountFolders(account, folders)))
        coEvery { repo.messages(any(), null) } returns MailResult.Ok(MailPage(listOf(mail(1)), null))
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun loaded(mailbox: Mailbox = Mailbox.Unified): MailListViewModel {
        val vm = MailListViewModel(repo)
        vm.select(mailbox)
        return vm
    }

    private fun MailListViewModel.folders() =
        uiState.value.accounts
            .single()
            .folders

    @Test
    fun `a new folder appears in the list at once and the dialog closes`() =
        runTest(dispatcher) {
            val created = MailFolder(id(94), account.id, "Receipts", "Receipts", null, 0, 1, "/")
            coEvery { repo.createFolder(account.id, null, "Receipts") } returns MailResult.Ok(created)
            val vm = loaded()
            advanceUntilIdle()
            coEvery { repo.accounts() } returns MailResult.Failed(null) // the reload after it does not hide the change

            vm.startCreateFolder(account.id)
            assertEquals(FolderEdit(FolderEditKind.CREATE, account.id, null), vm.uiState.value.folderEdit)
            vm.submitFolderEdit("  Receipts ")
            assertTrue(
                vm.uiState.value.folderEdit!!
                    .busy,
            )
            advanceUntilIdle()

            assertNull(vm.uiState.value.folderEdit)
            assertEquals(listOf(inbox, projects, alpha, created), vm.folders())
        }

    @Test
    fun `a new subfolder is created in its parent`() =
        runTest(dispatcher) {
            val beta = MailFolder(id(95), account.id, "Beta", "Projects/Beta", null, 1, 1, "/")
            coEvery { repo.createFolder(account.id, projects, "Beta") } returns MailResult.Ok(beta)
            val vm = loaded()
            advanceUntilIdle()
            coEvery { repo.accounts() } returns MailResult.Offline

            vm.startCreateFolder(account.id, projects)
            vm.submitFolderEdit("Beta")
            advanceUntilIdle()

            assertEquals(listOf(inbox, projects, alpha, beta), vm.folders())
        }

    @Test
    fun `a renamed folder shows its new name at once, then the list is reloaded for its subfolders`() =
        runTest(dispatcher) {
            val renamed = projects.copy(name = "Clients", path = "Clients", version = 4)
            val movedAlpha = alpha.copy(path = "Clients/Alpha", version = 2)
            coEvery { repo.renameFolder(projects, "Clients") } returns MailResult.Ok(renamed)
            val vm = loaded()
            advanceUntilIdle()
            coEvery { repo.accounts() } returns MailResult.Ok(listOf(MailAccountFolders(account, listOf(inbox, renamed, movedAlpha))))

            vm.startRenameFolder(projects)
            vm.submitFolderEdit("Clients")
            advanceUntilIdle()

            assertNull(vm.uiState.value.folderEdit)
            assertEquals(listOf(inbox, renamed, movedAlpha), vm.folders())
            coVerify(exactly = 1) { repo.renameFolder(projects, "Clients") }
        }

    @Test
    fun `server errors stay in the open dialog`() =
        runTest(dispatcher) {
            val vm = loaded()
            advanceUntilIdle()
            val cases =
                listOf(
                    MailResult.Failed(409) to FolderEditError.NAME_TAKEN,
                    MailResult.Failed(400) to FolderEditError.INVALID_NAME,
                    MailResult.Offline to FolderEditError.UNREACHABLE,
                    MailResult.Failed(404) to FolderEditError.FAILED,
                    MailResult.Failed(null) to FolderEditError.FAILED,
                )
            vm.startRenameFolder(projects)
            for ((result, error) in cases) {
                coEvery { repo.renameFolder(projects, "Inbox") } returns result
                vm.submitFolderEdit("Inbox")
                advanceUntilIdle()

                assertEquals(
                    FolderEdit(FolderEditKind.RENAME, account.id, projects, busy = false, error = error),
                    vm.uiState.value.folderEdit,
                )
                assertEquals(folders, vm.folders())
            }
        }

    @Test
    fun `an empty name is not sent`() =
        runTest(dispatcher) {
            val vm = loaded()
            advanceUntilIdle()

            vm.startCreateFolder(account.id)
            vm.submitFolderEdit("   ")
            advanceUntilIdle()

            assertEquals(
                FolderEditError.INVALID_NAME,
                vm.uiState.value.folderEdit
                    ?.error,
            )
            coVerify(exactly = 0) { repo.createFolder(any(), any(), any()) }
        }

    @Test
    fun `a stale ETag closes the dialog, reloads the folders and says so`() =
        runTest(dispatcher) {
            coEvery { repo.deleteFolder(projects) } returns MailResult.Failed(412)
            val vm = loaded()
            advanceUntilIdle()
            val fresh = projects.copy(version = 9)
            coEvery { repo.accounts() } returns MailResult.Ok(listOf(MailAccountFolders(account, listOf(inbox, fresh, alpha))))

            vm.startDeleteFolder(projects)
            vm.submitFolderEdit()
            advanceUntilIdle()

            assertNull(vm.uiState.value.folderEdit)
            assertEquals(MailListMessage.FOLDER_CHANGED, vm.uiState.value.message)
            assertEquals(listOf(inbox, fresh, alpha), vm.folders())
        }

    @Test
    fun `a stale ETag when renaming does the same`() =
        runTest(dispatcher) {
            coEvery { repo.renameFolder(projects, "Clients") } returns MailResult.Failed(412)
            val vm = loaded()
            advanceUntilIdle()

            vm.startRenameFolder(projects)
            vm.submitFolderEdit("Clients")
            advanceUntilIdle()

            assertNull(vm.uiState.value.folderEdit)
            assertEquals(MailListMessage.FOLDER_CHANGED, vm.uiState.value.message)
            coVerify(atLeast = 2) { repo.accounts() }
        }

    @Test
    fun `deleting removes the folder with its subfolders`() =
        runTest(dispatcher) {
            coEvery { repo.deleteFolder(projects) } returns MailResult.Ok(Unit)
            val vm = loaded()
            advanceUntilIdle()
            coEvery { repo.accounts() } returns MailResult.Offline

            vm.startDeleteFolder(projects)
            vm.submitFolderEdit()
            advanceUntilIdle()

            assertNull(vm.uiState.value.folderEdit)
            assertEquals(listOf(inbox), vm.folders())
            assertEquals(Mailbox.Unified, vm.uiState.value.mailbox)
        }

    @Test
    fun `deleting the folder shown switches to the unified inbox`() =
        runTest(dispatcher) {
            coEvery { repo.deleteFolder(projects) } returns MailResult.Ok(Unit)
            val vm = loaded(Mailbox.Folder(account.id, projects.id))
            advanceUntilIdle()

            vm.startDeleteFolder(projects)
            vm.submitFolderEdit()
            advanceUntilIdle()

            assertEquals(Mailbox.Unified, vm.uiState.value.mailbox)
            assertTrue(vm.uiState.value.loaded)
            coVerify { repo.messages(Mailbox.Unified, null) }
        }

    @Test
    fun `deleting the parent of the folder shown also switches to the unified inbox`() =
        runTest(dispatcher) {
            coEvery { repo.deleteFolder(projects) } returns MailResult.Ok(Unit)
            val vm = loaded(Mailbox.Folder(account.id, alpha.id))
            advanceUntilIdle()

            vm.startDeleteFolder(projects)
            vm.submitFolderEdit()
            advanceUntilIdle()

            assertEquals(Mailbox.Unified, vm.uiState.value.mailbox)
        }

    @Test
    fun `deleting another folder keeps the folder shown`() =
        runTest(dispatcher) {
            coEvery { repo.deleteFolder(alpha) } returns MailResult.Ok(Unit)
            val shown = Mailbox.Folder(account.id, projects.id)
            val vm = loaded(shown)
            advanceUntilIdle()

            vm.startDeleteFolder(alpha)
            vm.submitFolderEdit()
            advanceUntilIdle()

            assertEquals(shown, vm.uiState.value.mailbox)
        }

    @Test
    fun `a folder holding a special folder cannot be deleted, the dialog says so`() =
        runTest(dispatcher) {
            coEvery { repo.deleteFolder(projects) } returns MailResult.Failed(409)
            val vm = loaded()
            advanceUntilIdle()

            vm.startDeleteFolder(projects)
            vm.submitFolderEdit()
            advanceUntilIdle()

            assertEquals(
                FolderEditError.CANNOT_DELETE,
                vm.uiState.value.folderEdit
                    ?.error,
            )
            assertEquals(folders, vm.folders())
        }

    @Test
    fun `INBOX cannot be renamed or deleted, folders with a role cannot be deleted`() =
        runTest(dispatcher) {
            val sent = MailFolder(id(96), account.id, "Sent", "Sent", FolderRole.SENT, 0, 1, "/")
            val vm = loaded()
            advanceUntilIdle()

            vm.startRenameFolder(inbox)
            assertNull(vm.uiState.value.folderEdit)
            vm.startDeleteFolder(inbox)
            assertNull(vm.uiState.value.folderEdit)
            vm.startDeleteFolder(sent)
            assertNull(vm.uiState.value.folderEdit)
            vm.startRenameFolder(sent)
            assertEquals(
                FolderEditKind.RENAME,
                vm.uiState.value.folderEdit
                    ?.kind,
            )
        }

    @Test
    fun `a success after the dialog was cancelled still shows in the list`() =
        runTest(dispatcher) {
            coEvery { repo.deleteFolder(projects) } returns MailResult.Ok(Unit)
            val vm = loaded()
            advanceUntilIdle()
            coEvery { repo.accounts() } returns MailResult.Offline

            vm.startDeleteFolder(projects)
            vm.submitFolderEdit()
            vm.cancelFolderEdit()
            advanceUntilIdle()

            assertNull(vm.uiState.value.folderEdit)
            assertEquals(listOf(inbox), vm.folders())
        }

    @Test
    fun `a failure after the dialog was cancelled does not open it again`() =
        runTest(dispatcher) {
            coEvery { repo.renameFolder(projects, "Clients") } returns MailResult.Failed(409)
            val vm = loaded()
            advanceUntilIdle()

            vm.startRenameFolder(projects)
            vm.submitFolderEdit("Clients")
            vm.cancelFolderEdit()
            advanceUntilIdle()

            assertNull(vm.uiState.value.folderEdit)
        }

    @Test
    fun `a second submit while busy is ignored`() =
        runTest(dispatcher) {
            coEvery { repo.renameFolder(projects, "Clients") } returns MailResult.Failed(409)
            val vm = loaded()
            advanceUntilIdle()

            vm.startRenameFolder(projects)
            vm.submitFolderEdit("Clients")
            vm.submitFolderEdit("Clients")
            advanceUntilIdle()

            coVerify(exactly = 1) { repo.renameFolder(projects, "Clients") }
        }
}
