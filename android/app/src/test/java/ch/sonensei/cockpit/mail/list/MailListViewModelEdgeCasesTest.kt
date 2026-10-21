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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.UUID

/** The list view model when answers come late, fail, or the user moves on before they arrive. */
@OptIn(ExperimentalCoroutinesApi::class)
class MailListViewModelEdgeCasesTest {
    private val dispatcher = StandardTestDispatcher()
    private val repo = mockk<MailRepository>()

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val account = MailAccount(id(80), "Work", "me@example.org")
    private val inbox = MailFolder(id(90), account.id, "INBOX", "INBOX", FolderRole.INBOX, 0)
    private val archive = MailFolder(id(91), account.id, "Archive", "Archive", FolderRole.ARCHIVE, 0)
    private val accounts = listOf(MailAccountFolders(account, listOf(inbox, archive)))
    private val archiveBox = Mailbox.Folder(account.id, archive.id)

    private fun mail(n: Int) = MailSummary(id(n), account.id, "Anna", "Subject $n", "", Instant.EPOCH, false, false, false)

    private fun ids(vm: MailListViewModel) =
        vm.uiState.value.messages
            .map { it.id }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        coEvery { repo.accounts() } returns MailResult.Ok(accounts)
        coEvery { repo.messages(Mailbox.Unified, null) } returns MailResult.Ok(MailPage(listOf(mail(1), mail(2), mail(3)), "c1"))
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `the mailbox is loaded with the accounts and loading ends`() =
        runTest(dispatcher) {
            val vm = MailListViewModel(repo)
            vm.refresh()
            assertTrue(vm.uiState.value.loading)
            advanceUntilIdle()

            val state = vm.uiState.value
            assertFalse(state.loading)
            assertTrue(state.loaded)
            assertEquals(accounts, state.accounts)
            assertEquals("c1", state.nextCursor)
            assertNull(state.message)
        }

    @Test
    fun `accounts that fail to load keep the last ones, the mails still show`() =
        runTest(dispatcher) {
            val vm = MailListViewModel(repo)
            vm.refresh()
            advanceUntilIdle()
            coEvery { repo.accounts() } returns MailResult.Offline

            vm.refresh()
            advanceUntilIdle()

            assertEquals(accounts, vm.uiState.value.accounts)
            assertEquals(3, vm.uiState.value.messages.size)
            assertNull(vm.uiState.value.message)
        }

    @Test
    fun `a first load that is refused ends loading and reports it, without claiming the mailbox was loaded`() =
        runTest(dispatcher) {
            coEvery { repo.messages(Mailbox.Unified, null) } returns MailResult.Failed(403)
            val vm = MailListViewModel(repo)
            vm.refresh()
            advanceUntilIdle()

            assertFalse(vm.uiState.value.loading)
            assertFalse(vm.uiState.value.loaded)
            assertEquals(MailListMessage.FAILED, vm.uiState.value.message)

            // Retrying after the failure loads it.
            coEvery { repo.messages(Mailbox.Unified, null) } returns MailResult.Ok(MailPage(listOf(mail(1)), null))
            vm.refresh()
            advanceUntilIdle()
            assertTrue(vm.uiState.value.loaded)
            assertNull(vm.uiState.value.message)
        }

    @Test
    fun `a failed next page keeps the loaded mails and the cursor, so scrolling retries it`() =
        runTest(dispatcher) {
            coEvery { repo.messages(Mailbox.Unified, "c1") } returns MailResult.Offline andThen MailResult.Failed(500)
            val vm = MailListViewModel(repo)
            vm.refresh()
            advanceUntilIdle()

            vm.loadMore()
            assertTrue(vm.uiState.value.loadingMore)
            advanceUntilIdle()
            assertEquals(MailListMessage.OFFLINE, vm.uiState.value.message)
            assertFalse(vm.uiState.value.loadingMore)
            assertEquals("c1", vm.uiState.value.nextCursor)

            vm.loadMore()
            advanceUntilIdle()
            assertEquals(MailListMessage.FAILED, vm.uiState.value.message)
            assertEquals(listOf(1, 2, 3).map(::id), ids(vm))
        }

    @Test
    fun `no next page is asked for while the mailbox loads or another page loads`() =
        runTest(dispatcher) {
            val page = CompletableDeferred<MailResult<MailPage>>()
            coEvery { repo.messages(Mailbox.Unified, "c1") } coAnswers { page.await() }
            val vm = MailListViewModel(repo)
            vm.refresh()
            advanceUntilIdle()

            vm.loadMore()
            vm.loadMore()
            advanceUntilIdle()
            page.complete(MailResult.Ok(MailPage(listOf(mail(4)), null)))
            advanceUntilIdle()
            coVerify(exactly = 1) { repo.messages(Mailbox.Unified, "c1") }

            val load = CompletableDeferred<MailResult<MailPage>>()
            coEvery { repo.messages(Mailbox.Unified, null) } coAnswers { load.await() }
            coEvery { repo.messages(Mailbox.Unified, "c2") } returns MailResult.Ok(MailPage(emptyList(), null))
            vm.refresh()
            vm.loadMore()
            advanceUntilIdle()
            coVerify(exactly = 0) { repo.messages(Mailbox.Unified, "c2") }
            load.complete(MailResult.Ok(MailPage(listOf(mail(1)), null)))
            advanceUntilIdle()
        }

    @Test
    fun `switching mailboxes drops the answer of the one left`() =
        runTest(dispatcher) {
            val slow = CompletableDeferred<MailResult<MailPage>>()
            coEvery { repo.messages(Mailbox.Unified, null) } coAnswers { slow.await() }
            coEvery { repo.messages(archiveBox, null) } returns MailResult.Ok(MailPage(listOf(mail(9)), null))
            val vm = MailListViewModel(repo)
            vm.refresh()
            advanceUntilIdle()

            vm.select(archiveBox)
            advanceUntilIdle()
            slow.complete(MailResult.Ok(MailPage(listOf(mail(1)), "c1")))
            advanceUntilIdle()

            assertEquals(archiveBox, vm.uiState.value.mailbox)
            assertEquals(listOf(id(9)), ids(vm))
            assertNull(vm.uiState.value.nextCursor)
            assertFalse(vm.uiState.value.loading)
        }

    @Test
    fun `selecting the mailbox already shown does not load it again`() =
        runTest(dispatcher) {
            val vm = MailListViewModel(repo)
            vm.refresh()
            advanceUntilIdle()

            vm.select(Mailbox.Unified)
            advanceUntilIdle()

            coVerify(exactly = 1) { repo.messages(Mailbox.Unified, null) }
        }

    @Test
    fun `in the Archive a mail is not archived again, and an unknown mail is ignored`() =
        runTest(dispatcher) {
            coEvery { repo.messages(archiveBox, null) } returns MailResult.Ok(MailPage(listOf(mail(9)), null))
            val vm = MailListViewModel(repo)
            vm.select(archiveBox)
            advanceUntilIdle()

            vm.act(id(9), MailListAction.ARCHIVE)
            vm.act(id(42), MailListAction.DELETE)
            advanceUntilIdle()

            assertEquals(listOf(id(9)), ids(vm))
            coVerify(exactly = 0) { repo.act(any(), any()) }
        }

    @Test
    fun `the last mail refused by the server comes back at the end`() =
        runTest(dispatcher) {
            coEvery { repo.act(id(3), MailListAction.DELETE) } returns MailResult.Failed(409)
            val vm = MailListViewModel(repo)
            vm.refresh()
            advanceUntilIdle()

            vm.act(id(3), MailListAction.DELETE)
            advanceUntilIdle()

            assertEquals(listOf(1, 2, 3).map(::id), ids(vm))
            assertEquals(MailListMessage.DELETE_FAILED, vm.uiState.value.message)
        }

    @Test
    fun `two mails swiped quickly, both refused, both come back`() =
        runTest(dispatcher) {
            coEvery { repo.act(any(), MailListAction.DELETE) } returns MailResult.Ok(false)
            val vm = MailListViewModel(repo)
            vm.refresh()
            advanceUntilIdle()

            vm.act(id(1), MailListAction.DELETE)
            vm.act(id(2), MailListAction.DELETE)
            assertEquals(listOf(id(3)), ids(vm))
            advanceUntilIdle()

            assertEquals(listOf(1, 2, 3).map(::id).toSet(), ids(vm).toSet())
            assertEquals(3, ids(vm).size)
        }

    @Test
    fun `a refused swipe does not bring the mail into a mailbox selected meanwhile`() =
        runTest(dispatcher) {
            val answer = CompletableDeferred<MailResult<Boolean>>()
            coEvery { repo.act(id(2), MailListAction.DELETE) } coAnswers { answer.await() }
            coEvery { repo.messages(archiveBox, null) } returns MailResult.Ok(MailPage(listOf(mail(9)), null))
            val vm = MailListViewModel(repo)
            vm.refresh()
            advanceUntilIdle()

            // An Inbox mail is swiped away, then the user opens the Archive before the server answers.
            vm.act(id(2), MailListAction.DELETE)
            vm.select(archiveBox)
            advanceUntilIdle()
            answer.complete(MailResult.Ok(false))
            advanceUntilIdle()

            assertEquals(MailListMessage.DELETE_FAILED, vm.uiState.value.message)
            assertEquals(listOf(id(9)), ids(vm))
        }

    @Test
    fun `an archived mail stays gone even when a reload that started before brought it back`() =
        runTest(dispatcher) {
            val answer = CompletableDeferred<MailResult<Boolean>>()
            coEvery { repo.act(id(2), MailListAction.ARCHIVE) } coAnswers { answer.await() }
            val vm = MailListViewModel(repo)
            vm.refresh()
            advanceUntilIdle()

            vm.act(id(2), MailListAction.ARCHIVE)
            advanceUntilIdle()
            // A refresh answered by the server before it moved the mail.
            vm.refresh()
            advanceUntilIdle()
            assertEquals(listOf(1, 2, 3).map(::id), ids(vm))

            answer.complete(MailResult.Ok(true))
            advanceUntilIdle()
            assertEquals(listOf(1, 3).map(::id), ids(vm))
        }

    @Test
    fun `a refresh keeps the pages loaded behind the first one and their cursor`() =
        runTest(dispatcher) {
            coEvery { repo.messages(Mailbox.Unified, "c1") } returns MailResult.Ok(MailPage(listOf(mail(4), mail(5)), "c2"))
            val vm = MailListViewModel(repo)
            vm.refresh()
            advanceUntilIdle()
            vm.loadMore()
            advanceUntilIdle()

            // New mail arrived, mail 2 left the server.
            coEvery { repo.messages(Mailbox.Unified, null) } returns MailResult.Ok(MailPage(listOf(mail(6), mail(1), mail(3)), "c1b"))
            vm.refresh()
            advanceUntilIdle()

            assertEquals(listOf(6, 1, 3, 4, 5).map(::id), ids(vm))
            assertEquals("c2", vm.uiState.value.nextCursor)
        }

    @Test
    fun `a refresh whose first page does not reach the loaded mails replaces them`() =
        runTest(dispatcher) {
            coEvery { repo.messages(Mailbox.Unified, "c1") } returns MailResult.Ok(MailPage(listOf(mail(4)), "c2"))
            val vm = MailListViewModel(repo)
            vm.refresh()
            advanceUntilIdle()
            vm.loadMore()
            advanceUntilIdle()

            // So much new mail that the first page ends before the loaded ones: no gap is left in the list.
            coEvery { repo.messages(Mailbox.Unified, null) } returns MailResult.Ok(MailPage(listOf(mail(7), mail(8)), "c9"))
            vm.refresh()
            advanceUntilIdle()

            assertEquals(listOf(7, 8).map(::id), ids(vm))
            assertEquals("c9", vm.uiState.value.nextCursor)
        }

    @Test
    fun `a refresh that reaches the end replaces the list`() =
        runTest(dispatcher) {
            coEvery { repo.messages(Mailbox.Unified, "c1") } returns MailResult.Ok(MailPage(listOf(mail(4)), null))
            val vm = MailListViewModel(repo)
            vm.refresh()
            advanceUntilIdle()
            vm.loadMore()
            advanceUntilIdle()

            coEvery { repo.messages(Mailbox.Unified, null) } returns MailResult.Ok(MailPage(listOf(mail(1), mail(3)), null))
            vm.refresh()
            advanceUntilIdle()

            assertEquals(listOf(1, 3).map(::id), ids(vm))
            assertNull(vm.uiState.value.nextCursor)
        }
}
