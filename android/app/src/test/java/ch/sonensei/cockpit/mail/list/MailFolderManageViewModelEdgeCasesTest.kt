package ch.sonensei.cockpit.mail.list

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
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
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.UUID

/** Folder dialogs beyond the main paths: several accounts, retries, errors per action, renaming the folder shown. */
@OptIn(ExperimentalCoroutinesApi::class)
class MailFolderManageViewModelEdgeCasesTest {
    private val dispatcher = StandardTestDispatcher()
    private val repo = mockk<MailRepository>()

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val work = MailAccount(id(80), "Work", "me@example.org")
    private val home = MailAccount(id(81), "Home", "me@home.example")
    private val workProjects = MailFolder(id(92), work.id, "Projects", "Projects", null, 0, 3, "/")
    private val workAlpha = MailFolder(id(93), work.id, "Alpha", "Projects/Alpha", null, 1, 1, "/")
    private val homeProjects = MailFolder(id(94), home.id, "Projects", "Projects", null, 0, 2, "/")
    private val homeAlpha = MailFolder(id(95), home.id, "Alpha", "Projects/Alpha", null, 1, 1, "/")
    private val lists =
        listOf(
            MailAccountFolders(work, listOf(workProjects, workAlpha)),
            MailAccountFolders(home, listOf(homeProjects, homeAlpha)),
        )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        coEvery { repo.accounts() } returns MailResult.Ok(lists)
        coEvery { repo.messages(any(), null) } returns
            MailResult.Ok(MailPage(listOf(MailSummary(id(1), work.id, "Anna", "Hello", "", Instant.EPOCH, false, false, false)), null))
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun loaded(mailbox: Mailbox = Mailbox.Unified): MailListViewModel {
        val vm = MailListViewModel(repo)
        vm.select(mailbox)
        return vm
    }

    private fun MailListViewModel.foldersOf(account: MailAccount) =
        uiState.value.accounts
            .single { it.account == account }
            .folders

    private fun MailListViewModel.editError(): FolderEditError? {
        val edit = uiState.value.folderEdit
        return edit?.error
    }

    @Test
    fun `deleting a folder leaves the folder with the same path in another account, and the shown one there`() =
        runTest(dispatcher) {
            coEvery { repo.deleteFolder(workProjects) } returns MailResult.Ok(Unit)
            val shown = Mailbox.Folder(home.id, homeAlpha.id)
            val vm = loaded(shown)
            advanceUntilIdle()
            coEvery { repo.accounts() } returns MailResult.Offline

            vm.startDeleteFolder(workProjects)
            vm.submitFolderEdit()
            advanceUntilIdle()

            assertEquals(emptyList<MailFolder>(), vm.foldersOf(work))
            assertEquals(listOf(homeProjects, homeAlpha), vm.foldersOf(home))
            assertEquals(shown, vm.uiState.value.mailbox)
        }

    @Test
    fun `a renamed name is trimmed, an empty one is not sent`() =
        runTest(dispatcher) {
            val clients = workProjects.copy(name = "Clients", path = "Clients")
            coEvery { repo.renameFolder(workProjects, "Clients") } returns MailResult.Ok(clients)
            val vm = loaded()
            advanceUntilIdle()

            vm.startRenameFolder(workProjects)
            vm.submitFolderEdit(" \t ")
            advanceUntilIdle()
            assertEquals(FolderEditError.INVALID_NAME, vm.editError())
            coVerify(exactly = 0) { repo.renameFolder(any(), any()) }

            vm.submitFolderEdit("  Clients  ")
            advanceUntilIdle()
            coVerify(exactly = 1) { repo.renameFolder(workProjects, "Clients") }
            assertNull(vm.uiState.value.folderEdit)
        }

    @Test
    fun `submit without an open dialog does nothing`() =
        runTest(dispatcher) {
            val vm = loaded()
            advanceUntilIdle()

            vm.submitFolderEdit("Receipts")
            advanceUntilIdle()

            assertNull(vm.uiState.value.folderEdit)
            coVerify(exactly = 0) { repo.createFolder(any(), any(), any()) }
            coVerify(exactly = 0) { repo.renameFolder(any(), any()) }
            coVerify(exactly = 0) { repo.deleteFolder(any()) }
        }

    @Test
    fun `create and delete map server errors to their dialog messages`() =
        runTest(dispatcher) {
            val vm = loaded()
            advanceUntilIdle()
            val createCases =
                listOf(
                    MailResult.Failed(409) to FolderEditError.NAME_TAKEN,
                    MailResult.Failed(400) to FolderEditError.INVALID_NAME,
                    MailResult.Offline to FolderEditError.UNREACHABLE,
                    MailResult.Failed(403) to FolderEditError.FAILED,
                )
            vm.startCreateFolder(work.id, workProjects)
            for ((result, error) in createCases) {
                coEvery { repo.createFolder(work.id, workProjects, "Beta") } returns result
                vm.submitFolderEdit("Beta")
                advanceUntilIdle()
                assertEquals(FolderEdit(FolderEditKind.CREATE, work.id, workProjects, error = error), vm.uiState.value.folderEdit)
            }
            vm.cancelFolderEdit()

            val deleteCases =
                listOf(
                    MailResult.Offline to FolderEditError.UNREACHABLE,
                    MailResult.Failed(404) to FolderEditError.FAILED,
                    MailResult.Failed(null) to FolderEditError.FAILED,
                )
            vm.startDeleteFolder(workAlpha)
            for ((result, error) in deleteCases) {
                coEvery { repo.deleteFolder(workAlpha) } returns result
                vm.submitFolderEdit()
                advanceUntilIdle()
                assertEquals(FolderEdit(FolderEditKind.DELETE, work.id, workAlpha, error = error), vm.uiState.value.folderEdit)
            }
            assertEquals(lists, vm.uiState.value.accounts)
        }

    @Test
    fun `a retry after an error clears the error while the server works on it`() =
        runTest(dispatcher) {
            val gate = CompletableDeferred<MailResult<MailFolder>>()
            var calls = 0
            coEvery { repo.createFolder(work.id, null, "Receipts") } coAnswers { if (calls++ == 0) MailResult.Offline else gate.await() }
            val vm = loaded()
            advanceUntilIdle()

            vm.startCreateFolder(work.id)
            vm.submitFolderEdit("Receipts")
            advanceUntilIdle()
            assertEquals(FolderEditError.UNREACHABLE, vm.editError())

            vm.submitFolderEdit("Receipts")
            advanceUntilIdle()
            assertEquals(FolderEdit(FolderEditKind.CREATE, work.id, null, busy = true, error = null), vm.uiState.value.folderEdit)

            gate.complete(MailResult.Ok(MailFolder(id(96), work.id, "Receipts", "Receipts", null, 0, 1, "/")))
            advanceUntilIdle()
            assertNull(vm.uiState.value.folderEdit)
        }

    @Test
    fun `a failed delete of the folder shown keeps it shown`() =
        runTest(dispatcher) {
            coEvery { repo.deleteFolder(workProjects) } returns MailResult.Failed(409)
            val shown = Mailbox.Folder(work.id, workProjects.id)
            val vm = loaded(shown)
            advanceUntilIdle()

            vm.startDeleteFolder(workProjects)
            vm.submitFolderEdit()
            advanceUntilIdle()

            assertEquals(shown, vm.uiState.value.mailbox)
            assertEquals(workProjects, vm.uiState.value.folder)
        }

    @Test
    fun `renaming the folder shown keeps it shown under its new name`() =
        runTest(dispatcher) {
            val renamed = workProjects.copy(name = "Clients", path = "Clients", version = 4)
            coEvery { repo.renameFolder(workProjects, "Clients") } returns MailResult.Ok(renamed)
            val shown = Mailbox.Folder(work.id, workProjects.id)
            val vm = loaded(shown)
            advanceUntilIdle()
            coEvery { repo.accounts() } returns MailResult.Offline

            vm.startRenameFolder(workProjects)
            vm.submitFolderEdit("Clients")
            advanceUntilIdle()

            assertEquals(shown, vm.uiState.value.mailbox)
            assertEquals(renamed, vm.uiState.value.folder)
        }

    @Test
    fun `a dialog opened meanwhile is not closed by the answer to an earlier one`() =
        runTest(dispatcher) {
            val gate = CompletableDeferred<MailResult<Unit>>()
            coEvery { repo.deleteFolder(workAlpha) } coAnswers { gate.await() }
            val vm = loaded()
            advanceUntilIdle()
            coEvery { repo.accounts() } returns MailResult.Offline

            vm.startDeleteFolder(workAlpha)
            vm.submitFolderEdit()
            advanceUntilIdle()
            vm.cancelFolderEdit()
            vm.startRenameFolder(homeProjects)
            gate.complete(MailResult.Ok(Unit))
            advanceUntilIdle()

            assertEquals(FolderEdit(FolderEditKind.RENAME, home.id, homeProjects), vm.uiState.value.folderEdit)
            assertEquals(listOf(workProjects), vm.foldersOf(work))
        }

    @Test
    fun `a stale ETag on delete keeps the folder shown and leaves the mails alone`() =
        runTest(dispatcher) {
            coEvery { repo.deleteFolder(workProjects) } returns MailResult.Failed(412)
            val shown = Mailbox.Folder(work.id, workProjects.id)
            val vm = loaded(shown)
            advanceUntilIdle()

            vm.startDeleteFolder(workProjects)
            vm.submitFolderEdit()
            advanceUntilIdle()

            assertEquals(shown, vm.uiState.value.mailbox)
            assertEquals(MailListMessage.FOLDER_CHANGED, vm.uiState.value.message)
            // Only the folders are reloaded: the first page was asked for once.
            coVerify(exactly = 1) { repo.messages(shown, null) }
        }
}
