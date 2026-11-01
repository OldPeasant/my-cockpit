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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.UUID

/** Edge cases of searching from the list: reopening, scope, failed first searches, late answers, read-view updates. */
@OptIn(ExperimentalCoroutinesApi::class)
class MailSearchViewModelEdgeCasesTest {
    private val dispatcher = StandardTestDispatcher()
    private val repo = mockk<MailRepository>()

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val account = MailAccount(id(80), "Work", "me@example.org")
    private val inbox = MailFolder(id(90), account.id, "INBOX", "INBOX", FolderRole.INBOX, 0)
    private val folder = Mailbox.Folder(account.id, inbox.id)

    private fun mail(n: Int) = MailSummary(id(n), account.id, "Anna", "Subject $n", "", Instant.EPOCH, false, false, false)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        coEvery { repo.accounts() } returns MailResult.Ok(listOf(MailAccountFolders(account, listOf(inbox))))
        coEvery { repo.messages(any(), null) } returns MailResult.Ok(MailPage(listOf(mail(1), mail(2)), null))
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun loaded(): MailListViewModel {
        val vm = MailListViewModel(repo)
        vm.refresh()
        return vm
    }

    /** The open search; fails the test when none is open. */
    private val MailListViewModel.shown: MailSearchState get() = uiState.value.search!!

    @Test
    fun `opening the search again keeps the open search as it is`() =
        runTest(dispatcher) {
            coEvery { repo.search("x", Mailbox.Unified, false) } returns MailResult.Ok(MailSearchResult(listOf(mail(5))))
            val vm = loaded()
            advanceUntilIdle()
            vm.openSearch()
            vm.search("x")
            advanceUntilIdle()

            vm.openSearch()

            assertEquals("x", vm.shown.query)
            assertEquals(listOf(id(5)), vm.shown.results.map { it.id })
        }

    @Test
    fun `a search opened again after closing starts empty in the mailbox shown now`() =
        runTest(dispatcher) {
            coEvery { repo.search("x", Mailbox.Unified, false) } returns MailResult.Ok(MailSearchResult(listOf(mail(5))))
            coEvery { repo.search("y", folder, false) } returns MailResult.Ok(MailSearchResult(emptyList()))
            val vm = loaded()
            advanceUntilIdle()
            vm.openSearch()
            vm.search("x")
            advanceUntilIdle()

            vm.closeSearch()
            vm.select(folder)
            advanceUntilIdle()
            vm.openSearch()

            assertEquals(MailSearchState(folder), vm.shown)
            vm.search("y")
            advanceUntilIdle()
            coVerify(exactly = 1) { repo.search("y", folder, false) }
        }

    @Test
    fun `a new query after a search on the server searches the cache again and offers the server again`() =
        runTest(dispatcher) {
            coEvery { repo.search(any(), Mailbox.Unified, false) } returns MailResult.Ok(MailSearchResult(listOf(mail(5))))
            coEvery { repo.search("x", Mailbox.Unified, true) } returns
                MailResult.Ok(MailSearchResult(listOf(mail(6)), hasMore = true, failedAccounts = listOf(id(81))))
            val vm = loaded()
            advanceUntilIdle()
            vm.openSearch()
            vm.search("x")
            advanceUntilIdle()
            vm.searchOnServer()
            advanceUntilIdle()
            assertTrue(vm.shown.server)

            vm.search("y")
            assertEquals(listOf<MailSummary>(), vm.shown.results)
            assertFalse(vm.shown.hasMore)
            assertEquals(emptyList<UUID>(), vm.shown.failedAccounts)
            advanceUntilIdle()

            assertFalse(vm.shown.server)
            assertTrue(vm.shown.canSearchServer)
            coVerify(exactly = 1) { repo.search("y", Mailbox.Unified, false) }
        }

    @Test
    fun `a failed first search offers no server search, and retry repeats the cached search`() =
        runTest(dispatcher) {
            coEvery { repo.search("x", Mailbox.Unified, false) } returnsMany
                listOf(MailResult.Offline, MailResult.Ok(MailSearchResult(listOf(mail(5)))))
            val vm = loaded()
            advanceUntilIdle()
            vm.openSearch()
            vm.search("x")
            advanceUntilIdle()

            assertEquals(MailListMessage.OFFLINE, vm.shown.message)
            assertFalse(vm.shown.loaded)
            assertFalse(vm.shown.loading)
            assertFalse(vm.shown.canSearchServer)
            vm.searchOnServer()
            advanceUntilIdle()
            coVerify(exactly = 0) { repo.search(any(), any(), true) }

            vm.retrySearch()
            advanceUntilIdle()

            assertNull(vm.shown.message)
            assertTrue(vm.shown.loaded)
            assertTrue(vm.shown.canSearchServer)
            assertEquals(listOf(id(5)), vm.shown.results.map { it.id })
            coVerify(exactly = 2) { repo.search("x", Mailbox.Unified, false) }
        }

    @Test
    fun `retry and search on server do nothing before a query, retry does nothing while a search runs`() =
        runTest(dispatcher) {
            val answer = CompletableDeferred<MailResult<MailSearchResult>>()
            coEvery { repo.search("x", Mailbox.Unified, false) } coAnswers { answer.await() }
            val vm = loaded()
            advanceUntilIdle()
            vm.retrySearch()
            vm.searchOnServer()
            vm.openSearch()
            vm.retrySearch()
            vm.searchOnServer()
            advanceUntilIdle()
            coVerify(exactly = 0) { repo.search(any(), any(), any()) }
            assertEquals(MailSearchState(Mailbox.Unified), vm.shown)

            vm.search("x")
            advanceUntilIdle()
            vm.retrySearch()
            vm.searchOnServer()
            answer.complete(MailResult.Ok(MailSearchResult(listOf(mail(5)))))
            advanceUntilIdle()

            coVerify(exactly = 1) { repo.search("x", Mailbox.Unified, false) }
            coVerify(exactly = 0) { repo.search(any(), any(), true) }
        }

    @Test
    fun `an older search failing after a newer one answered leaves no error`() =
        runTest(dispatcher) {
            val old = CompletableDeferred<MailResult<MailSearchResult>>()
            coEvery { repo.search("old", Mailbox.Unified, false) } coAnswers { old.await() }
            coEvery { repo.search("new", Mailbox.Unified, false) } returns MailResult.Ok(MailSearchResult(listOf(mail(9))))
            val vm = loaded()
            advanceUntilIdle()
            vm.openSearch()
            vm.search("old")
            advanceUntilIdle()
            vm.search("new")
            advanceUntilIdle()

            old.complete(MailResult.Offline)
            advanceUntilIdle()

            assertNull(vm.shown.message)
            assertEquals("new", vm.shown.query)
            assertEquals(listOf(id(9)), vm.shown.results.map { it.id })
        }

    @Test
    fun `a search on the server answering after a new query is not shown`() =
        runTest(dispatcher) {
            val serverAnswer = CompletableDeferred<MailResult<MailSearchResult>>()
            coEvery { repo.search("x", Mailbox.Unified, false) } returns MailResult.Ok(MailSearchResult(listOf(mail(5))))
            coEvery { repo.search("x", Mailbox.Unified, true) } coAnswers { serverAnswer.await() }
            coEvery { repo.search("y", Mailbox.Unified, false) } returns MailResult.Ok(MailSearchResult(listOf(mail(7))))
            val vm = loaded()
            advanceUntilIdle()
            vm.openSearch()
            vm.search("x")
            advanceUntilIdle()
            vm.searchOnServer()
            advanceUntilIdle()

            vm.search("y")
            advanceUntilIdle()
            serverAnswer.complete(MailResult.Ok(MailSearchResult(listOf(mail(6)), hasMore = true, failedAccounts = listOf(id(81)))))
            advanceUntilIdle()

            val search = vm.shown
            assertEquals("y", search.query)
            assertFalse(search.server)
            assertFalse(search.hasMore)
            assertEquals(emptyList<UUID>(), search.failedAccounts)
            assertEquals(listOf(id(7)), search.results.map { it.id })
        }

    @Test
    fun `search on server with text edited since the search searches that text, from empty results`() =
        runTest(dispatcher) {
            coEvery { repo.search("x", Mailbox.Unified, false) } returns MailResult.Ok(MailSearchResult(listOf(mail(5))))
            coEvery { repo.search(any(), Mailbox.Unified, true) } returns MailResult.Ok(MailSearchResult(listOf(mail(7))))
            val vm = loaded()
            advanceUntilIdle()
            vm.openSearch()
            vm.search("x")
            advanceUntilIdle()

            vm.searchOnServer("   ")
            assertFalse(vm.shown.server)
            vm.searchOnServer("  y ")
            assertEquals("y", vm.shown.query)
            assertTrue(vm.shown.server && vm.shown.loading)
            assertEquals(listOf<MailSummary>(), vm.shown.results)
            advanceUntilIdle()

            assertEquals(listOf(id(7)), vm.shown.results.map { it.id })
            coVerify(exactly = 1) { repo.search("y", Mailbox.Unified, true) }
            coVerify(exactly = 0) { repo.search("x", Mailbox.Unified, true) }
        }

    @Test
    fun `search on server with the text searched keeps the cached results until it answers`() =
        runTest(dispatcher) {
            val serverAnswer = CompletableDeferred<MailResult<MailSearchResult>>()
            coEvery { repo.search("x", Mailbox.Unified, false) } returns MailResult.Ok(MailSearchResult(listOf(mail(5))))
            coEvery { repo.search("x", Mailbox.Unified, true) } coAnswers { serverAnswer.await() }
            val vm = loaded()
            advanceUntilIdle()
            vm.openSearch()
            vm.search("x")
            advanceUntilIdle()

            vm.searchOnServer(" x ")
            advanceUntilIdle()

            assertTrue(vm.shown.server && vm.shown.loading)
            assertEquals(listOf(id(5)), vm.shown.results.map { it.id })
            serverAnswer.complete(MailResult.Ok(MailSearchResult(listOf(mail(6), mail(5)))))
            advanceUntilIdle()
            assertEquals(listOf(id(6), id(5)), vm.shown.results.map { it.id })
        }

    @Test
    fun `the version changes with each answer, not when a search starts, fails, or a result is read or removed`() =
        runTest(dispatcher) {
            coEvery { repo.search("x", Mailbox.Unified, false) } returns MailResult.Ok(MailSearchResult(listOf(mail(5))))
            coEvery { repo.search("x", Mailbox.Unified, true) } returnsMany
                listOf(MailResult.Offline, MailResult.Failed(500), MailResult.Ok(MailSearchResult(listOf(mail(6), mail(5)))))
            val vm = loaded()
            advanceUntilIdle()
            vm.openSearch()
            assertEquals(0L, vm.shown.version)

            vm.search("x")
            assertEquals(0L, vm.shown.version)
            advanceUntilIdle()
            val first = vm.shown.version
            assertNotEquals(0L, first)

            // The same query again is a new answer: the list starts at the top again.
            vm.search("x")
            assertTrue(vm.shown.loading)
            assertEquals(first, vm.shown.version)
            advanceUntilIdle()
            val again = vm.shown.version
            assertNotEquals(first, again)

            vm.searchOnServer()
            assertEquals(again, vm.shown.version)
            advanceUntilIdle()
            assertEquals(MailListMessage.OFFLINE, vm.shown.message)
            assertEquals(again, vm.shown.version)

            vm.retrySearch()
            advanceUntilIdle()
            assertEquals(MailListMessage.FAILED, vm.shown.message)
            assertEquals(again, vm.shown.version)
            assertEquals(listOf(id(5)), vm.shown.results.map { it.id })

            vm.retrySearch()
            advanceUntilIdle()
            val server = vm.shown.version
            assertNotEquals(again, server)
            assertNotEquals(first, server)
            assertEquals(listOf(id(6), id(5)), vm.shown.results.map { it.id })

            vm.markSeen(id(6))
            vm.removed(id(5))
            assertEquals(listOf(id(6)), vm.shown.results.map { it.id })
            assertEquals(server, vm.shown.version)
        }

    @Test
    fun `a late answer of an older search leaves the version, and a search opened again gets a new one`() =
        runTest(dispatcher) {
            val old = CompletableDeferred<MailResult<MailSearchResult>>()
            coEvery { repo.search("old", Mailbox.Unified, false) } coAnswers { old.await() }
            coEvery { repo.search("new", Mailbox.Unified, false) } returns MailResult.Ok(MailSearchResult(listOf(mail(9))))
            val vm = loaded()
            advanceUntilIdle()
            vm.openSearch()
            vm.search("old")
            advanceUntilIdle()
            vm.search("new")
            advanceUntilIdle()
            val shown = vm.shown.version

            old.complete(MailResult.Ok(MailSearchResult(listOf(mail(5)))))
            advanceUntilIdle()
            assertEquals(shown, vm.shown.version)
            assertEquals(listOf(id(9)), vm.shown.results.map { it.id })

            vm.closeSearch()
            vm.openSearch()
            assertEquals(0L, vm.shown.version)
            vm.search("new")
            advanceUntilIdle()
            // Not the version of the search before closing either, so the list of the new search starts at the top.
            assertNotEquals(shown, vm.shown.version)
            assertNotEquals(0L, vm.shown.version)
        }

    @Test
    fun `a mail removed in the read view leaves both the results and the mailbox`() =
        runTest(dispatcher) {
            coEvery { repo.search("x", Mailbox.Unified, false) } returns MailResult.Ok(MailSearchResult(listOf(mail(1), mail(5))))
            val vm = loaded()
            advanceUntilIdle()
            vm.openSearch()
            vm.search("x")
            advanceUntilIdle()

            vm.markSeen(id(1))
            vm.removed(id(1))
            assertEquals(listOf(id(5)), vm.shown.results.map { it.id })
            vm.closeSearch()

            val messages = vm.uiState.value.messages
            assertEquals(listOf(id(2)), messages.map { it.id })
        }

    @Test
    fun `read and removed mails without an open search change only the mailbox`() =
        runTest(dispatcher) {
            val vm = loaded()
            advanceUntilIdle()

            vm.markSeen(id(1))
            vm.removed(id(2))

            assertNull(vm.uiState.value.search)
            val messages = vm.uiState.value.messages
            assertEquals(listOf(id(1)), messages.map { it.id })
            assertTrue(messages.single().seen)
        }
}
