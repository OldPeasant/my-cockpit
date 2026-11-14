package ch.sonensei.cockpit.mail.list

import ch.sonensei.cockpit.gtd.data.ChangeSignal
import ch.sonensei.cockpit.gtd.data.MailRefreshTriggers
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
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

/**
 * The mail list reloads the first page of the current mailbox while shown when the server announces new or changed mail
 * or the change stream reconnects; bursts give one reload, the search results stay, and a failed event reload keeps
 * the list without a new message.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MailListLiveRefreshTest {
    private val dispatcher = StandardTestDispatcher()
    private val repo = mockk<MailRepository>()
    private val signals = MutableSharedFlow<ChangeSignal>(extraBufferCapacity = 64)

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val account = MailAccount(id(80), "Work", "me@example.org")
    private val archive = MailFolder(id(91), account.id, "Archive", "Archive", FolderRole.ARCHIVE, 0)
    private val box = Mailbox.Folder(account.id, archive.id)

    private fun mail(n: Int) = MailSummary(id(n), account.id, "Anna", "Subject $n", "", Instant.EPOCH, false, false, false)

    private fun page(
        vararg ns: Int,
        cursor: String? = null,
    ) = MailResult.Ok(MailPage(ns.map(::mail), cursor))

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        coEvery { repo.accounts() } returns MailResult.Ok(listOf(MailAccountFolders(account, listOf(archive))))
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun firstPages(vararg results: MailResult<MailPage>) =
        coEvery { repo.messages(Mailbox.Unified, null) } returnsMany results.toList()

    /** The list shown and loaded, following the mail changes as `MailRoute` does while started. */
    private fun TestScope.shown(): MailListViewModel {
        val vm = MailListViewModel(repo, MailRefreshTriggers(signals, QUIET_MS))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.followMailChanges() }
        vm.refresh()
        advanceUntilIdle()
        return vm
    }

    private fun signal(vararg signal: ChangeSignal) = signal.forEach { signals.tryEmit(it) }

    /** Past the quiet time after the last signal, with the reload it started done. */
    private fun TestScope.quiet() {
        advanceTimeBy(QUIET_MS + 1)
        advanceUntilIdle()
    }

    private fun MailListViewModel.ids() = uiState.value.messages.map { it.id }

    @Test
    fun `an email event shows the new mail at the top of the unified inbox`() =
        runTest(dispatcher) {
            firstPages(page(1, 2, 3, cursor = "c1"), page(9, 1, 2, cursor = "c1-new"))
            val vm = shown()

            signal(ChangeSignal.Changed("EMAIL"))
            quiet()

            // The page loaded behind the new first page stays, and so does its cursor.
            assertEquals(listOf(9, 1, 2, 3).map(::id), vm.ids())
            assertEquals("c1", vm.uiState.value.nextCursor)
            assertFalse(vm.uiState.value.loading)
            assertNull(vm.uiState.value.message)
            coVerify(exactly = 2) { repo.messages(Mailbox.Unified, null) }
        }

    @Test
    fun `a folder change reloads the folder shown, not the unified inbox`() =
        runTest(dispatcher) {
            firstPages(page(1, 2))
            coEvery { repo.messages(box, null) } returnsMany listOf(page(5), page(6, 5))
            val vm = shown()
            vm.select(box)
            advanceUntilIdle()

            signal(ChangeSignal.Changed("MAIL_FOLDER"))
            quiet()

            assertEquals(box, vm.uiState.value.mailbox)
            assertEquals(listOf(6, 5).map(::id), vm.ids())
            coVerify(exactly = 2) { repo.messages(box, null) }
            coVerify(exactly = 1) { repo.messages(Mailbox.Unified, null) }
        }

    @Test
    fun `the list reloads only after the quiet time`() =
        runTest(dispatcher) {
            firstPages(page(1), page(9, 1))
            val vm = shown()

            signal(ChangeSignal.Changed("EMAIL"))
            advanceTimeBy(QUIET_MS - 1)
            coVerify(exactly = 1) { repo.messages(Mailbox.Unified, null) }

            advanceTimeBy(2)
            advanceUntilIdle()
            assertEquals(listOf(9, 1).map(::id), vm.ids())
        }

    @Test
    fun `a burst of mail events gives one reload`() =
        runTest(dispatcher) {
            firstPages(page(1), page(9, 1))
            val vm = shown()

            repeat(15) {
                signal(ChangeSignal.Changed("EMAIL"))
                advanceTimeBy(60)
            }
            signal(ChangeSignal.Changed("MAIL_FOLDER"))
            quiet()

            coVerify(exactly = 2) { repo.messages(Mailbox.Unified, null) }
            assertEquals(listOf(9, 1).map(::id), vm.ids())
        }

    @Test
    fun `a task event does not reload the list`() =
        runTest(dispatcher) {
            firstPages(page(1), page(9, 1))
            val vm = shown()

            signal(ChangeSignal.Changed("TASK"), ChangeSignal.Changed("CONTEXT"), ChangeSignal.Connected)
            advanceTimeBy(10 * QUIET_MS)
            advanceUntilIdle()

            coVerify(exactly = 1) { repo.messages(Mailbox.Unified, null) }
            assertEquals(listOf(id(1)), vm.ids())
        }

    @Test
    fun `a reconnect reloads the list`() =
        runTest(dispatcher) {
            firstPages(page(1), page(9, 1))
            val vm = shown()

            signal(ChangeSignal.Reconnected)
            quiet()

            assertEquals(listOf(9, 1).map(::id), vm.ids())
        }

    @Test
    fun `an offline or failed event reload keeps the list and shows no message`() =
        runTest(dispatcher) {
            firstPages(page(1, 2), MailResult.Offline, MailResult.Failed(500))
            val vm = shown()

            for (n in 1..2) {
                signal(ChangeSignal.Changed("EMAIL"))
                quiet()

                val state = vm.uiState.value
                assertEquals(listOf(1, 2).map(::id), vm.ids())
                assertNull("reload $n", state.message)
                assertFalse("reload $n", state.loading)
                assertTrue("reload $n", state.loaded)
            }
            coVerify(exactly = 3) { repo.messages(Mailbox.Unified, null) }
        }

    @Test
    fun `an event reload shows no loading indicator while it runs`() =
        runTest(dispatcher) {
            val answer = CompletableDeferred<MailResult<MailPage>>()
            coEvery { repo.messages(Mailbox.Unified, null) } returns page(1) coAndThen { answer.await() }
            val vm = shown()

            signal(ChangeSignal.Changed("EMAIL"))
            quiet()
            coVerify(exactly = 2) { repo.messages(Mailbox.Unified, null) }
            assertFalse(vm.uiState.value.loading)

            answer.complete(page(9, 1))
            advanceUntilIdle()
            assertEquals(listOf(9, 1).map(::id), vm.ids())
        }

    @Test
    fun `an event during a running load does not start a second one but reloads once it ends`() =
        runTest(dispatcher) {
            val manual = CompletableDeferred<MailResult<MailPage>>()
            coEvery { repo.messages(Mailbox.Unified, null) } returns page(1) coAndThen { manual.await() } andThen page(9, 1)
            val vm = shown()
            vm.refresh()
            advanceUntilIdle()
            assertTrue(vm.uiState.value.loading)

            signal(ChangeSignal.Changed("EMAIL"))
            quiet()
            signal(ChangeSignal.Changed("MAIL_FOLDER"))
            quiet()
            coVerify(exactly = 2) { repo.messages(Mailbox.Unified, null) }

            // The running load may have read the server before the new mail was stored: one more reload follows it.
            manual.complete(page(1))
            advanceUntilIdle()
            assertFalse(vm.uiState.value.loading)
            coVerify(exactly = 3) { repo.messages(Mailbox.Unified, null) }
            assertEquals(listOf(9, 1).map(::id), vm.ids())

            advanceTimeBy(10 * QUIET_MS)
            advanceUntilIdle()
            coVerify(exactly = 3) { repo.messages(Mailbox.Unified, null) }
        }

    @Test
    fun `an event during load more still reloads the first page`() =
        runTest(dispatcher) {
            val nextPage = CompletableDeferred<MailResult<MailPage>>()
            firstPages(page(1, 2, cursor = "c1"), page(9, 1, cursor = "c1-new"))
            coEvery { repo.messages(Mailbox.Unified, "c1") } coAnswers { nextPage.await() }
            val vm = shown()
            vm.loadMore()
            advanceUntilIdle()
            assertTrue(vm.uiState.value.loadingMore)

            signal(ChangeSignal.Changed("EMAIL"))
            quiet()

            coVerify(exactly = 2) { repo.messages(Mailbox.Unified, null) }
            assertEquals(listOf(9, 1, 2).map(::id), vm.ids())
            assertTrue(vm.uiState.value.loadingMore)

            nextPage.complete(page(3))
            advanceUntilIdle()
            assertFalse(vm.uiState.value.loadingMore)
            assertEquals(listOf(9, 1, 2, 3).map(::id), vm.ids())
        }

    @Test
    fun `an event reload outrun by load more and a folder switch does not overwrite the new folder`() =
        runTest(dispatcher) {
            val stale = CompletableDeferred<MailResult<MailPage>>()
            val folder = CompletableDeferred<MailResult<MailPage>>()
            coEvery { repo.messages(Mailbox.Unified, null) } returns page(1, 2, cursor = "c1") coAndThen { stale.await() }
            coEvery { repo.messages(Mailbox.Unified, "c1") } returns page(3)
            coEvery { repo.messages(box, null) } coAnswers { folder.await() }
            val vm = shown()

            // An event reload runs, a page is loaded meanwhile, then another folder is opened.
            signal(ChangeSignal.Changed("EMAIL"))
            quiet()
            vm.loadMore()
            advanceUntilIdle()
            vm.select(box)
            advanceUntilIdle()
            assertTrue(vm.uiState.value.loading)

            stale.complete(page(9, 1, cursor = "c-unified"))
            advanceUntilIdle()
            assertEquals(box, vm.uiState.value.mailbox)
            assertEquals(emptyList<UUID>(), vm.ids())
            assertTrue(vm.uiState.value.loading)

            folder.complete(page(5, 6))
            advanceUntilIdle()
            assertEquals(listOf(5, 6).map(::id), vm.ids())
            assertNull(vm.uiState.value.nextCursor)
            assertFalse(vm.uiState.value.loading)
        }

    @Test
    fun `the open search is not reloaded while the mailbox behind it is`() =
        runTest(dispatcher) {
            firstPages(page(1), page(9, 1))
            coEvery { repo.search("invoice", Mailbox.Unified, false) } returns
                MailResult.Ok(MailSearchResult(listOf(mail(5)), hasMore = false))
            val vm = shown()
            vm.openSearch()
            vm.search("invoice")
            advanceUntilIdle()
            val search = vm.uiState.value.search

            signal(ChangeSignal.Changed("EMAIL"))
            quiet()

            assertEquals(search, vm.uiState.value.search)
            coVerify(exactly = 1) { repo.search(any(), any(), any()) }
            assertEquals(listOf(9, 1).map(::id), vm.ids())
        }

    @Test
    fun `a successful event reload ends an outdated message but keeps an action's message`() =
        runTest(dispatcher) {
            firstPages(page(1, 2), MailResult.Offline, page(9, 1, 2), page(8, 9, 1, 2))
            coEvery { repo.actAll(listOf(id(2)), MailListAction.DELETE, null) } returns MailResult.Ok(emptySet())
            val vm = shown()
            vm.refresh()
            advanceUntilIdle()
            assertEquals(MailListMessage.OFFLINE, vm.uiState.value.message)

            signal(ChangeSignal.Changed("EMAIL"))
            quiet()
            assertNull(vm.uiState.value.message)

            vm.act(id(2), MailListAction.DELETE)
            advanceUntilIdle()
            assertEquals(MailListMessage.DELETE_FAILED, vm.uiState.value.message)

            signal(ChangeSignal.Changed("EMAIL"))
            quiet()
            assertEquals(MailListMessage.DELETE_FAILED, vm.uiState.value.message)
            assertEquals(listOf(8, 9, 1, 2).map(::id), vm.ids())
        }

    @Test
    fun `an event reload keeps the selection of mails still listed`() =
        runTest(dispatcher) {
            firstPages(page(1, 2, 3), page(9, 1, 3))
            val vm = shown()
            vm.toggleSelection(id(1))
            vm.toggleSelection(id(2))

            signal(ChangeSignal.Changed("EMAIL"))
            quiet()

            assertEquals(setOf(id(1)), vm.uiState.value.selected)
        }

    @Test
    fun `no event reloads once the screen stopped following`() =
        runTest(dispatcher) {
            firstPages(page(1), page(9, 1))
            val vm = MailListViewModel(repo, MailRefreshTriggers(signals, QUIET_MS))
            val following = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.followMailChanges() }
            vm.refresh()
            advanceUntilIdle()

            following.cancel()
            signal(ChangeSignal.Changed("EMAIL"), ChangeSignal.Reconnected)
            advanceTimeBy(10 * QUIET_MS)
            advanceUntilIdle()

            coVerify(exactly = 1) { repo.messages(Mailbox.Unified, null) }
        }

    private companion object {
        const val QUIET_MS = 1_000L
    }
}
