package ch.sonensei.cockpit.mail.list

import io.mockk.coEvery
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
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.UUID

/**
 * A folder change must not be undone by a mailbox load that took its folder snapshot before the change
 * (criterion 2: after success the folder list shows the change). Reported as TW-1 (same defect as review CR-1).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MailFolderManageReloadRaceTest {
    private val dispatcher = StandardTestDispatcher()
    private val repo = mockk<MailRepository>()

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val account = MailAccount(id(80), "Work", "me@example.org")
    private val inbox = MailFolder(id(90), account.id, "INBOX", "INBOX", FolderRole.INBOX, 0, 1, "/")
    private val projects = MailFolder(id(92), account.id, "Projects", "Projects", null, 0, 3, "/")
    private val receipts = MailFolder(id(94), account.id, "Receipts", "Receipts", null, 0, 1, "/")
    private val page = MailPage(listOf(MailSummary(id(1), account.id, "Anna", "Hello", "", Instant.EPOCH, false, false, false)), null)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        coEvery { repo.accounts() } returns MailResult.Ok(listOf(MailAccountFolders(account, listOf(inbox, projects))))
        coEvery { repo.messages(any(), null) } returns MailResult.Ok(page)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun MailListViewModel.folders() =
        uiState.value.accounts
            .single()
            .folders

    @Test
    fun `a new folder stays in the list when a refresh that started before it finishes later`() =
        runTest(dispatcher) {
            coEvery { repo.createFolder(account.id, null, "Receipts") } returns MailResult.Ok(receipts)
            val vm = MailListViewModel(repo)
            vm.select(Mailbox.Unified)
            advanceUntilIdle()

            // A refresh reads the old folder list, then waits for its first page.
            val slowPage = CompletableDeferred<MailResult<MailPage>>()
            coEvery { repo.messages(Mailbox.Unified, null) } coAnswers { slowPage.await() }
            vm.refresh()
            advanceUntilIdle()

            // Meanwhile the folder is created; the server's list has it from now on.
            coEvery { repo.accounts() } returns MailResult.Ok(listOf(MailAccountFolders(account, listOf(inbox, projects, receipts))))
            vm.startCreateFolder(account.id)
            vm.submitFolderEdit("Receipts")
            advanceUntilIdle()
            assertEquals(listOf(inbox, projects, receipts), vm.folders())

            slowPage.complete(MailResult.Ok(page))
            advanceUntilIdle()

            assertEquals(listOf(inbox, projects, receipts), vm.folders())
        }

    @Test
    fun `a deleted folder does not come back when a refresh that started before it finishes later`() =
        runTest(dispatcher) {
            coEvery { repo.deleteFolder(projects) } returns MailResult.Ok(Unit)
            val vm = MailListViewModel(repo)
            vm.select(Mailbox.Unified)
            advanceUntilIdle()

            val slowPage = CompletableDeferred<MailResult<MailPage>>()
            coEvery { repo.messages(Mailbox.Unified, null) } coAnswers { slowPage.await() }
            vm.refresh()
            advanceUntilIdle()

            coEvery { repo.accounts() } returns MailResult.Ok(listOf(MailAccountFolders(account, listOf(inbox))))
            vm.startDeleteFolder(projects)
            vm.submitFolderEdit()
            advanceUntilIdle()
            assertEquals(listOf(inbox), vm.folders())

            slowPage.complete(MailResult.Ok(page))
            advanceUntilIdle()

            assertEquals(listOf(inbox), vm.folders())
        }
}
