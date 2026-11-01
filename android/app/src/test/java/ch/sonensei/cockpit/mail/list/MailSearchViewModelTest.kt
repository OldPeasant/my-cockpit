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

/** Searching from the list: scope, search on the server, failures, and closing back to the mailbox. */
@OptIn(ExperimentalCoroutinesApi::class)
class MailSearchViewModelTest {
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

    private fun loaded(mailbox: Mailbox = Mailbox.Unified): MailListViewModel {
        val vm = MailListViewModel(repo)
        vm.refresh()
        if (mailbox != Mailbox.Unified) vm.select(mailbox)
        return vm
    }

    @Test
    fun `a search in a folder searches that folder and shows its results beside the untouched mailbox`() =
        runTest(dispatcher) {
            coEvery { repo.search("invoice", folder, false) } returns
                MailResult.Ok(MailSearchResult(listOf(mail(5)), hasMore = false))
            val vm = loaded(folder)
            advanceUntilIdle()

            vm.openSearch()
            assertEquals(
                folder,
                vm.uiState.value.search
                    ?.mailbox,
            )
            vm.search("  invoice ")
            assertTrue(
                vm.uiState.value.search!!
                    .loading,
            )
            advanceUntilIdle()

            val search = vm.uiState.value.search!!
            assertEquals("invoice", search.query)
            assertEquals(listOf(id(5)), search.results.map { it.id })
            assertTrue(search.loaded)
            assertFalse(search.loading)
            assertTrue(search.canSearchServer)
            assertEquals(
                listOf(id(1), id(2)),
                vm.uiState.value.messages
                    .map { it.id },
            )
        }

    @Test
    fun `from the unified inbox the search covers all folders`() =
        runTest(dispatcher) {
            coEvery { repo.search("x", Mailbox.Unified, false) } returns MailResult.Ok(MailSearchResult(emptyList()))
            val vm = loaded()
            advanceUntilIdle()

            vm.openSearch()
            vm.search("x")
            advanceUntilIdle()

            coVerify(exactly = 1) { repo.search("x", Mailbox.Unified, false) }
            assertTrue(
                vm.uiState.value.search!!
                    .loaded,
            )
            assertTrue(
                vm.uiState.value.search!!
                    .results
                    .isEmpty(),
            )
        }

    @Test
    fun `a blank query does nothing, a long one is cut to what the server takes`() =
        runTest(dispatcher) {
            coEvery { repo.search(any(), any(), any()) } returns MailResult.Ok(MailSearchResult(emptyList()))
            val vm = loaded()
            advanceUntilIdle()
            vm.search("ignored while closed")
            vm.openSearch()
            vm.search("   ")
            advanceUntilIdle()
            coVerify(exactly = 0) { repo.search(any(), any(), any()) }

            vm.search("a".repeat(MAX_QUERY + 20))
            advanceUntilIdle()
            coVerify(exactly = 1) { repo.search("a".repeat(MAX_QUERY), Mailbox.Unified, false) }
        }

    @Test
    fun `search on server keeps the cached results until it answers, then names failed accounts and hasMore`() =
        runTest(dispatcher) {
            val serverAnswer = CompletableDeferred<MailResult<MailSearchResult>>()
            coEvery { repo.search("x", Mailbox.Unified, false) } returns MailResult.Ok(MailSearchResult(listOf(mail(5))))
            coEvery { repo.search("x", Mailbox.Unified, true) } coAnswers { serverAnswer.await() }
            val vm = loaded()
            advanceUntilIdle()
            vm.openSearch()
            vm.search("x")
            advanceUntilIdle()

            vm.searchOnServer()
            advanceUntilIdle()
            val running = vm.uiState.value.search!!
            assertTrue(running.loading && running.server)
            assertFalse(running.canSearchServer)
            assertEquals(listOf(id(5)), running.results.map { it.id })

            serverAnswer.complete(
                MailResult.Ok(MailSearchResult(listOf(mail(6), mail(5)), hasMore = true, failedAccounts = listOf(id(81)))),
            )
            advanceUntilIdle()
            val done = vm.uiState.value.search!!
            assertEquals(listOf(id(6), id(5)), done.results.map { it.id })
            assertTrue(done.hasMore)
            assertEquals(listOf(id(81)), done.failedAccounts)
            assertFalse(done.canSearchServer)

            vm.searchOnServer()
            advanceUntilIdle()
            coVerify(exactly = 1) { repo.search("x", Mailbox.Unified, true) }
        }

    @Test
    fun `a failed search shows the list's message and keeps what was shown, retry asks again`() =
        runTest(dispatcher) {
            coEvery { repo.search("x", Mailbox.Unified, false) } returns MailResult.Ok(MailSearchResult(listOf(mail(5))))
            coEvery { repo.search("x", Mailbox.Unified, true) } returnsMany
                listOf(MailResult.Offline, MailResult.Failed(500), MailResult.Ok(MailSearchResult(listOf(mail(7)))))
            val vm = loaded()
            advanceUntilIdle()
            vm.openSearch()
            vm.search("x")
            advanceUntilIdle()

            vm.searchOnServer()
            advanceUntilIdle()
            assertEquals(
                MailListMessage.OFFLINE,
                vm.uiState.value.search!!
                    .message,
            )
            assertEquals(
                listOf(id(5)),
                vm.uiState.value.search!!
                    .results
                    .map { it.id },
            )

            vm.retrySearch()
            advanceUntilIdle()
            assertEquals(
                MailListMessage.FAILED,
                vm.uiState.value.search!!
                    .message,
            )

            vm.retrySearch()
            advanceUntilIdle()
            assertNull(
                vm.uiState.value.search!!
                    .message,
            )
            assertEquals(
                listOf(id(7)),
                vm.uiState.value.search!!
                    .results
                    .map { it.id },
            )
            coVerify(exactly = 3) { repo.search("x", Mailbox.Unified, true) }
        }

    @Test
    fun `closing the search drops a running answer and returns to the mailbox`() =
        runTest(dispatcher) {
            val answer = CompletableDeferred<MailResult<MailSearchResult>>()
            coEvery { repo.search("x", Mailbox.Unified, false) } coAnswers { answer.await() }
            val vm = loaded()
            advanceUntilIdle()
            vm.openSearch()
            vm.search("x")
            advanceUntilIdle()

            vm.closeSearch()
            answer.complete(MailResult.Ok(MailSearchResult(listOf(mail(5)))))
            advanceUntilIdle()

            assertNull(vm.uiState.value.search)
            assertEquals(
                listOf(id(1), id(2)),
                vm.uiState.value.messages
                    .map { it.id },
            )
        }

    @Test
    fun `a newer search wins over an older one still running`() =
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
            old.complete(MailResult.Ok(MailSearchResult(listOf(mail(8)))))
            advanceUntilIdle()

            assertEquals(
                "new",
                vm.uiState.value.search!!
                    .query,
            )
            assertEquals(
                listOf(id(9)),
                vm.uiState.value.search!!
                    .results
                    .map { it.id },
            )
        }

    @Test
    fun `a result read or removed in the read view changes in the results too`() =
        runTest(dispatcher) {
            coEvery { repo.search("x", Mailbox.Unified, false) } returns MailResult.Ok(MailSearchResult(listOf(mail(5), mail(6))))
            val vm = loaded()
            advanceUntilIdle()
            vm.openSearch()
            vm.search("x")
            advanceUntilIdle()

            vm.markSeen(id(5))
            vm.removed(id(6))

            val results =
                vm.uiState.value.search!!
                    .results
            assertEquals(listOf(id(5)), results.map { it.id })
            assertTrue(results.single().seen)
        }
}
