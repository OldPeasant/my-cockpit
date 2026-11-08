package ch.sonensei.cockpit.mail.list

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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

/** "Load older from server" at the end of a folder (SPEC.md §7.2): when it is offered, its progress, result and errors. */
@OptIn(ExperimentalCoroutinesApi::class)
class MailLoadOlderViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repo = mockk<MailRepository>()

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val account = MailAccount(id(80), "Work", "me@example.org")
    private val inboxFolder = MailFolder(id(90), account.id, "INBOX", "INBOX", FolderRole.INBOX, 0)
    private val projectsFolder = MailFolder(id(92), account.id, "Projects", "Projects", null, 0)
    private val inbox = Mailbox.Folder(account.id, inboxFolder.id)
    private val projects = Mailbox.Folder(account.id, projectsFolder.id)

    private fun mail(n: Int) = MailSummary(id(n), account.id, "Anna", "Subject $n", "", Instant.EPOCH, false, false, false)

    private fun ids(vm: MailListViewModel): List<UUID> {
        val messages = vm.uiState.value.messages
        return messages.map { it.id }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        coEvery { repo.accounts() } returns MailResult.Ok(listOf(MailAccountFolders(account, listOf(inboxFolder, projectsFolder))))
        coEvery { repo.messages(Mailbox.Unified, null) } returns MailResult.Ok(MailPage(listOf(mail(1)), null))
        coEvery { repo.messages(inbox, null) } returns MailResult.Ok(MailPage(listOf(mail(1), mail(2)), null))
        coEvery { repo.messages(projects, null) } returns MailResult.Ok(MailPage(listOf(mail(20)), null))
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.inInbox(): MailListViewModel {
        val vm = MailListViewModel(repo)
        vm.select(inbox)
        advanceUntilIdle()
        return vm
    }

    @Test
    fun `it is offered at the end of a fully loaded folder only`() =
        runTest(dispatcher) {
            val vm = MailListViewModel(repo)
            assertFalse(vm.uiState.value.canLoadOlder)

            vm.refresh()
            advanceUntilIdle()
            assertFalse("not in the unified inbox", vm.uiState.value.canLoadOlder)

            vm.select(inbox)
            advanceUntilIdle()
            assertTrue(vm.uiState.value.canLoadOlder)

            val folder = vm.uiState.value
            assertFalse("not while cached pages follow", folder.copy(nextCursor = "c2").canLoadOlder)
            assertFalse("not in a search", folder.copy(search = MailSearchState(inbox, "invoice")).canLoadOlder)
            assertFalse("not while loading", folder.copy(loading = true).canLoadOlder)
            assertFalse("not before the folder loaded", folder.copy(loaded = false).canLoadOlder)
            assertFalse("not offline", folder.copy(message = MailListMessage.OFFLINE).canLoadOlder)
            assertFalse("not after a failed load", folder.copy(message = MailListMessage.FAILED).canLoadOlder)
            assertTrue("an empty folder offers it", folder.copy(messages = emptyList()).canLoadOlder)
            assertTrue("after another message", folder.copy(message = MailListMessage.DELETE_FAILED).canLoadOlder)
        }

    @Test
    fun `it loads older mail for the folder, shows the progress, then the list up to its new end`() =
        runTest(dispatcher) {
            val added = CompletableDeferred<MailResult<Int>>()
            coEvery { repo.loadOlder(inbox) } coAnswers { added.await() }
            coEvery { repo.messagesUpTo(inbox, 5) } returns MailResult.Ok(MailPage((1..5).map(::mail), null))
            val vm = inInbox()
            vm.toggleSelection(id(2))

            vm.loadOlder()
            advanceUntilIdle()
            assertTrue(vm.uiState.value.loadingOlder)
            assertTrue("the button stays at the end", vm.uiState.value.canLoadOlder)

            added.complete(MailResult.Ok(3))
            advanceUntilIdle()

            assertEquals((1..5).map(::id), ids(vm))
            assertFalse(vm.uiState.value.loadingOlder)
            assertNull(vm.uiState.value.olderMessage)
            assertEquals(setOf(id(2)), vm.uiState.value.selected)
            assertTrue(vm.uiState.value.canLoadOlder)
        }

    @Test
    fun `a cursor after the newly cached mails hands over to infinite scroll`() =
        runTest(dispatcher) {
            coEvery { repo.loadOlder(inbox) } returns MailResult.Ok(1)
            coEvery { repo.messagesUpTo(inbox, 3) } returns MailResult.Ok(MailPage((1..3).map(::mail), "c4"))
            val vm = inInbox()

            vm.loadOlder()
            advanceUntilIdle()

            assertEquals("c4", vm.uiState.value.nextCursor)
            assertFalse(vm.uiState.value.canLoadOlder)
        }

    @Test
    fun `nothing older on the server is said at the end of the list, which stays`() =
        runTest(dispatcher) {
            coEvery { repo.loadOlder(inbox) } returns MailResult.Ok(0)
            val vm = inInbox()

            vm.loadOlder()
            advanceUntilIdle()

            assertEquals(OlderMessage.NOTHING_OLDER, vm.uiState.value.olderMessage)
            assertEquals(listOf(id(1), id(2)), ids(vm))
            assertFalse(vm.uiState.value.loadingOlder)
            assertNull("the list's own message is not used", vm.uiState.value.message)
            coVerify(exactly = 0) { repo.messagesUpTo(any(), any()) }
        }

    @Test
    fun `offline and refused load older are told apart and keep the list`() =
        runTest(dispatcher) {
            coEvery { repo.loadOlder(inbox) } returnsMany listOf(MailResult.Offline, MailResult.Failed(403))
            val vm = inInbox()

            vm.loadOlder()
            advanceUntilIdle()
            assertEquals(OlderMessage.OFFLINE, vm.uiState.value.olderMessage)
            assertEquals(listOf(id(1), id(2)), ids(vm))
            assertTrue("the button stays for a retry", vm.uiState.value.canLoadOlder)

            vm.loadOlder()
            advanceUntilIdle()
            assertEquals(OlderMessage.FAILED, vm.uiState.value.olderMessage)
            assertNull(vm.uiState.value.message)
        }

    @Test
    fun `a failure reading the list afterwards is shown and keeps the list`() =
        runTest(dispatcher) {
            coEvery { repo.loadOlder(inbox) } returns MailResult.Ok(4)
            coEvery { repo.messagesUpTo(inbox, 6) } returnsMany listOf(MailResult.Offline, MailResult.Failed(500))
            val vm = inInbox()

            vm.loadOlder()
            advanceUntilIdle()
            assertEquals(OlderMessage.OFFLINE, vm.uiState.value.olderMessage)
            assertEquals(listOf(id(1), id(2)), ids(vm))

            vm.loadOlder()
            advanceUntilIdle()
            assertEquals(OlderMessage.FAILED, vm.uiState.value.olderMessage)
        }

    @Test
    fun `a retry clears the last message while it runs`() =
        runTest(dispatcher) {
            val second = CompletableDeferred<MailResult<Int>>()
            coEvery { repo.loadOlder(inbox) } returns MailResult.Offline andThen MailResult.Ok(0)
            val vm = inInbox()
            vm.loadOlder()
            advanceUntilIdle()
            assertEquals(OlderMessage.OFFLINE, vm.uiState.value.olderMessage)

            coEvery { repo.loadOlder(inbox) } coAnswers { second.await() }
            vm.loadOlder()
            advanceUntilIdle()
            assertNull(vm.uiState.value.olderMessage)
            assertTrue(vm.uiState.value.loadingOlder)
            second.complete(MailResult.Ok(0))
            advanceUntilIdle()
            assertEquals(OlderMessage.NOTHING_OLDER, vm.uiState.value.olderMessage)
        }

    @Test
    fun `only one load older runs at a time`() =
        runTest(dispatcher) {
            val added = CompletableDeferred<MailResult<Int>>()
            coEvery { repo.loadOlder(inbox) } coAnswers { added.await() }
            val vm = inInbox()

            vm.loadOlder()
            advanceUntilIdle()
            vm.loadOlder()
            advanceUntilIdle()
            added.complete(MailResult.Ok(0))
            advanceUntilIdle()

            coVerify(exactly = 1) { repo.loadOlder(inbox) }
        }

    @Test
    fun `it does nothing in the unified inbox or while cached pages follow`() =
        runTest(dispatcher) {
            val vm = MailListViewModel(repo)
            vm.refresh()
            advanceUntilIdle()
            vm.loadOlder()
            advanceUntilIdle()

            coEvery { repo.messages(inbox, null) } returns MailResult.Ok(MailPage(listOf(mail(1)), "c2"))
            vm.select(inbox)
            advanceUntilIdle()
            vm.loadOlder()
            advanceUntilIdle()

            coVerify(exactly = 0) { repo.loadOlder(any()) }
            assertFalse(vm.uiState.value.loadingOlder)
        }

    @Test
    fun `leaving the folder while it loads older drops the answer and the progress`() =
        runTest(dispatcher) {
            val added = CompletableDeferred<MailResult<Int>>()
            coEvery { repo.loadOlder(inbox) } coAnswers { added.await() }
            coEvery { repo.messagesUpTo(inbox, any()) } returns MailResult.Ok(MailPage((1..9).map(::mail), null))
            val vm = inInbox()

            vm.loadOlder()
            advanceUntilIdle()
            vm.select(projects)
            advanceUntilIdle()
            added.complete(MailResult.Ok(7))
            advanceUntilIdle()

            assertEquals(projects, vm.uiState.value.mailbox)
            assertEquals(listOf(id(20)), ids(vm))
            assertFalse(vm.uiState.value.loadingOlder)
            assertNull(vm.uiState.value.olderMessage)
            assertTrue(vm.uiState.value.canLoadOlder)
        }

    @Test
    fun `refreshing or opening another folder clears the last message`() =
        runTest(dispatcher) {
            coEvery { repo.loadOlder(inbox) } returns MailResult.Ok(0)
            val vm = inInbox()
            vm.loadOlder()
            advanceUntilIdle()
            assertEquals(OlderMessage.NOTHING_OLDER, vm.uiState.value.olderMessage)

            vm.refresh()
            advanceUntilIdle()
            assertNull(vm.uiState.value.olderMessage)

            vm.loadOlder()
            advanceUntilIdle()
            vm.select(projects)
            assertNull(vm.uiState.value.olderMessage)
        }
}
