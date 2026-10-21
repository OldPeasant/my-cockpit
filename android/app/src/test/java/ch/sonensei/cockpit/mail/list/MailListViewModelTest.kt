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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.UUID

/** Loading mailboxes page by page, and the swipe actions: the mail leaves at once and returns if the server refuses. */
@OptIn(ExperimentalCoroutinesApi::class)
class MailListViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repo = mockk<MailRepository>()

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val account = MailAccount(id(80), "Work", "me@example.org")
    private val archive = MailFolder(id(91), account.id, "Archive", "Archive", FolderRole.ARCHIVE, 0)

    private fun mail(n: Int) = MailSummary(id(n), account.id, "Anna", "Subject $n", "", Instant.EPOCH, false, false, false)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        coEvery { repo.accounts() } returns MailResult.Ok(listOf(MailAccountFolders(account, listOf(archive))))
        coEvery { repo.messages(Mailbox.Unified, null) } returns MailResult.Ok(MailPage(listOf(mail(1), mail(2), mail(3)), "c1"))
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun loaded(): MailListViewModel {
        val vm = MailListViewModel(repo)
        vm.refresh()
        return vm
    }

    @Test
    fun `the unified inbox loads first, further pages are appended`() =
        runTest(dispatcher) {
            coEvery { repo.messages(Mailbox.Unified, "c1") } returns MailResult.Ok(MailPage(listOf(mail(3), mail(4)), null))
            val vm = loaded()
            advanceUntilIdle()
            assertEquals(
                listOf(1, 2, 3).map(::id),
                vm.uiState.value.messages
                    .map { it.id },
            )

            vm.loadMore()
            advanceUntilIdle()
            assertEquals(
                listOf(1, 2, 3, 4).map(::id),
                vm.uiState.value.messages
                    .map { it.id },
            )
            assertNull(vm.uiState.value.nextCursor)

            vm.loadMore()
            advanceUntilIdle()
            coVerify(exactly = 1) { repo.messages(Mailbox.Unified, "c1") }
        }

    @Test
    fun `selecting a folder loads it from its newest mail`() =
        runTest(dispatcher) {
            val box = Mailbox.Folder(account.id, archive.id)
            coEvery { repo.messages(box, null) } returns MailResult.Ok(MailPage(listOf(mail(9)), null))
            val vm = loaded()
            advanceUntilIdle()

            vm.select(box)
            advanceUntilIdle()

            assertEquals(box, vm.uiState.value.mailbox)
            assertEquals(
                listOf(id(9)),
                vm.uiState.value.messages
                    .map { it.id },
            )
            assertEquals(false, vm.uiState.value.canArchive)
        }

    @Test
    fun `a swiped mail leaves the list at once and stays gone when the server moved it`() =
        runTest(dispatcher) {
            val answer = CompletableDeferred<MailResult<Boolean>>()
            coEvery { repo.act(id(2), MailListAction.ARCHIVE) } coAnswers { answer.await() }
            val vm = loaded()
            advanceUntilIdle()

            vm.act(id(2), MailListAction.ARCHIVE)
            advanceUntilIdle()
            assertEquals(
                listOf(1, 3).map(::id),
                vm.uiState.value.messages
                    .map { it.id },
            )

            answer.complete(MailResult.Ok(true))
            advanceUntilIdle()
            assertEquals(
                listOf(1, 3).map(::id),
                vm.uiState.value.messages
                    .map { it.id },
            )
            assertNull(vm.uiState.value.message)
        }

    @Test
    fun `a refused or offline swipe puts the mail back in its place and says so`() =
        runTest(dispatcher) {
            coEvery { repo.act(id(2), MailListAction.DELETE) } returns MailResult.Ok(false)
            coEvery { repo.act(id(3), MailListAction.ARCHIVE) } returns MailResult.Offline
            val vm = loaded()
            advanceUntilIdle()

            vm.act(id(2), MailListAction.DELETE)
            advanceUntilIdle()
            assertEquals(
                listOf(1, 2, 3).map(::id),
                vm.uiState.value.messages
                    .map { it.id },
            )
            assertEquals(MailListMessage.DELETE_FAILED, vm.uiState.value.message)

            vm.act(id(3), MailListAction.ARCHIVE)
            advanceUntilIdle()
            assertEquals(
                listOf(1, 2, 3).map(::id),
                vm.uiState.value.messages
                    .map { it.id },
            )
            assertEquals(MailListMessage.ARCHIVE_FAILED, vm.uiState.value.message)
        }

    @Test
    fun `an offline load keeps what was shown and reports it`() =
        runTest(dispatcher) {
            val vm = loaded()
            advanceUntilIdle()
            coEvery { repo.messages(Mailbox.Unified, null) } returns MailResult.Offline

            vm.refresh()
            advanceUntilIdle()

            assertEquals(3, vm.uiState.value.messages.size)
            assertEquals(MailListMessage.OFFLINE, vm.uiState.value.message)
            assertTrue(vm.uiState.value.loaded)
        }

    @Test
    fun `the read view marks a mail seen, or removes it when it was archived there`() =
        runTest(dispatcher) {
            val vm = loaded()
            advanceUntilIdle()

            vm.markSeen(id(1))
            vm.removed(id(2))

            assertEquals(
                true,
                vm.uiState.value.messages
                    .first { it.id == id(1) }
                    .seen,
            )
            assertEquals(
                listOf(1, 3).map(::id),
                vm.uiState.value.messages
                    .map { it.id },
            )
        }
}
