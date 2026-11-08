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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.UUID

/** Multi-select, bulk actions with partial failures, and undo of marks and moves in the list's view model. */
@OptIn(ExperimentalCoroutinesApi::class)
class MailActionsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repo = mockk<MailRepository>()

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val work = MailAccount(id(80), "Ann Example", "me@example.org")
    private val home = MailAccount(id(81), "Ann Example", "me@example.net")
    private val workInbox = MailFolder(id(90), work.id, "INBOX", "INBOX", FolderRole.INBOX, 0)
    private val workArchive = MailFolder(id(91), work.id, "Archives", "Archives", FolderRole.ARCHIVE, 0)
    private val projects = MailFolder(id(92), work.id, "Projects", "Projects", null, 0)
    private val workTrash = MailFolder(id(93), work.id, "Trash", "Trash", FolderRole.TRASH, 0)
    private val homeInbox = MailFolder(id(94), home.id, "INBOX", "INBOX", FolderRole.INBOX, 0)
    private val accounts =
        listOf(
            MailAccountFolders(work, listOf(workInbox, workArchive, workTrash, projects)),
            MailAccountFolders(home, listOf(homeInbox)),
        )

    private fun mail(
        n: Int,
        account: MailAccount = work,
        folder: UUID? = workInbox.id,
        seen: Boolean = false,
        flagged: Boolean = false,
    ) = MailSummary(id(n), account.id, "Anna Example", "Subject $n", "", Instant.EPOCH, seen, flagged, false, folder)

    private val unified = listOf(mail(1), mail(2), mail(3), mail(5, account = home, folder = homeInbox.id))

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        coEvery { repo.accounts() } returns MailResult.Ok(accounts)
        coEvery { repo.messages(Mailbox.Unified, null) } returns MailResult.Ok(MailPage(unified, null))
        // By default the server does every action for every mail.
        coEvery { repo.actAll(any(), any(), any()) } coAnswers { MailResult.Ok(firstArg<List<UUID>>().toSet()) }
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.loaded(): MailListViewModel {
        val vm = MailListViewModel(repo)
        vm.refresh()
        advanceUntilIdle()
        return vm
    }

    private val MailListViewModel.state get() = uiState.value

    private fun MailListViewModel.ids() = state.messages.map { it.id }

    private fun MailListViewModel.mail(n: Int) = state.messages.first { it.id == id(n) }

    // Selection

    @Test
    fun `a long press starts the selection, another adds a mail, pressing a selected one removes it`() =
        runTest(dispatcher) {
            val vm = loaded()
            assertFalse(vm.state.selecting)

            vm.toggleSelection(id(2))
            assertTrue(vm.state.selecting)
            vm.toggleSelection(id(1))
            assertEquals(setOf(id(1), id(2)), vm.state.selected)
            // The selection is in list order, not in the order picked.
            assertEquals(listOf(id(1), id(2)), vm.state.selection.map { it.id })

            vm.toggleSelection(id(2))
            vm.toggleSelection(id(1))
            assertFalse(vm.state.selecting)
        }

    @Test
    fun `a mail not in the list cannot be selected`() =
        runTest(dispatcher) {
            val vm = loaded()
            vm.toggleSelection(id(42))
            assertEquals(emptySet<UUID>(), vm.state.selected)
        }

    @Test
    fun `the selection ends when cleared, another mailbox is shown or the search opens`() =
        runTest(dispatcher) {
            val box = Mailbox.Folder(work.id, projects.id)
            coEvery { repo.messages(box, null) } returns MailResult.Ok(MailPage(listOf(mail(7, folder = projects.id)), null))
            val vm = loaded()

            vm.toggleSelection(id(1))
            vm.clearSelection()
            assertFalse(vm.state.selecting)

            vm.toggleSelection(id(1))
            vm.openSearch()
            assertFalse(vm.state.selecting)
            vm.closeSearch()

            vm.toggleSelection(id(1))
            vm.select(box)
            assertFalse(vm.state.selecting)
        }

    @Test
    fun `a reload keeps the selected mails still listed and drops the others`() =
        runTest(dispatcher) {
            val vm = loaded()
            vm.toggleSelection(id(1))
            vm.toggleSelection(id(2))
            coEvery { repo.messages(Mailbox.Unified, null) } returns MailResult.Ok(MailPage(listOf(mail(1), mail(3)), null))

            vm.refresh()
            advanceUntilIdle()

            assertEquals(setOf(id(1)), vm.state.selected)
        }

    // Move targets

    @Test
    fun `mails of one folder can be moved to the other folders of their account`() =
        runTest(dispatcher) {
            val vm = loaded()
            assertEquals(emptyList<MailFolder>(), vm.state.moveTargets)

            vm.toggleSelection(id(1))
            vm.toggleSelection(id(2))

            assertEquals(listOf(workArchive, workTrash, projects), vm.state.moveTargets)
        }

    @Test
    fun `mails of different folders of one account are offered every folder of it`() =
        runTest(dispatcher) {
            coEvery { repo.messages(Mailbox.Unified, null) } returns
                MailResult.Ok(MailPage(listOf(mail(1), mail(2, folder = projects.id)), null))
            val vm = loaded()
            vm.toggleSelection(id(1))
            vm.toggleSelection(id(2))

            assertEquals(listOf(workInbox, workArchive, workTrash, projects), vm.state.moveTargets)
        }

    @Test
    fun `mails of two accounts cannot be moved, and moveSelection does nothing then`() =
        runTest(dispatcher) {
            val vm = loaded()
            vm.toggleSelection(id(1))
            vm.toggleSelection(id(5))

            assertEquals(emptyList<MailFolder>(), vm.state.moveTargets)
            vm.moveSelection(projects)
            advanceUntilIdle()

            coVerify(exactly = 0) { repo.actAll(any(), any(), any()) }
            assertEquals(unified.map { it.id }, vm.ids())
            assertEquals(setOf(id(1), id(5)), vm.state.selected)
        }

    @Test
    fun `a mail of an account without folder list or without account has no move targets`() =
        runTest(dispatcher) {
            val stranger = MailAccount(id(82), "Other", "other@example.com")
            coEvery { repo.messages(Mailbox.Unified, null) } returns
                MailResult.Ok(MailPage(listOf(mail(1, account = stranger, folder = null), mail(2).copy(accountId = null)), null))
            val vm = loaded()

            vm.toggleSelection(id(1))
            assertEquals(emptyList<MailFolder>(), vm.state.moveTargets)
            vm.clearSelection()
            vm.toggleSelection(id(2))
            assertEquals(emptyList<MailFolder>(), vm.state.moveTargets)
        }

    // Bulk actions

    @Test
    fun `a bulk mark changes the mails at once, ends the selection and offers to undo it`() =
        runTest(dispatcher) {
            val answer = CompletableDeferred<MailResult<Set<UUID>>>()
            coEvery { repo.actAll(listOf(id(1), id(3)), MailListAction.READ, null) } coAnswers { answer.await() }
            val vm = loaded()
            vm.toggleSelection(id(3))
            vm.toggleSelection(id(1))

            vm.actOnSelection(MailListAction.READ)

            assertTrue(vm.mail(1).seen)
            assertTrue(vm.mail(3).seen)
            assertFalse(vm.mail(2).seen)
            assertFalse(vm.state.selecting)
            assertNull(vm.state.undo)

            answer.complete(MailResult.Ok(setOf(id(1), id(3))))
            advanceUntilIdle()

            val undo = checkNotNull(vm.state.undo)
            assertEquals(MailListAction.READ, undo.action)
            assertEquals(listOf(id(1), id(3)), undo.mails.map { it.id })
            // The undo keeps the mails as they were before.
            assertTrue(undo.mails.none { it.seen })
            assertNull(vm.state.message)
        }

    @Test
    fun `bulk archive, delete and spam take the selected mails out of the list`() =
        runTest(dispatcher) {
            listOf(MailListAction.ARCHIVE, MailListAction.DELETE, MailListAction.SPAM).forEach { action ->
                val vm = loaded()
                vm.toggleSelection(id(1))
                vm.toggleSelection(id(2))

                vm.actOnSelection(action)
                advanceUntilIdle()

                assertEquals("$action", listOf(id(3), id(5)), vm.ids())
                assertEquals(action, vm.state.undo?.action)
                coVerify { repo.actAll(listOf(id(1), id(2)), action, null) }
            }
        }

    @Test
    fun `bulk flag and unflag change only the selected mails`() =
        runTest(dispatcher) {
            val vm = loaded()
            vm.toggleSelection(id(2))
            vm.actOnSelection(MailListAction.FLAG)
            advanceUntilIdle()
            assertTrue(vm.mail(2).flagged)
            assertFalse(vm.mail(1).flagged)

            vm.toggleSelection(id(2))
            vm.actOnSelection(MailListAction.UNFLAG)
            advanceUntilIdle()
            assertFalse(vm.mail(2).flagged)
            vm.toggleSelection(id(2))
            vm.actOnSelection(MailListAction.UNREAD)
            advanceUntilIdle()
            assertFalse(vm.mail(2).seen)
            assertEquals(MailListAction.UNREAD, vm.state.undo?.action)
        }

    @Test
    fun `MOVE is not a plain bulk action, and without a selection nothing is sent`() =
        runTest(dispatcher) {
            val vm = loaded()
            vm.actOnSelection(MailListAction.ARCHIVE)
            vm.toggleSelection(id(1))
            vm.actOnSelection(MailListAction.MOVE)
            advanceUntilIdle()

            coVerify(exactly = 0) { repo.actAll(any(), any(), any()) }
            assertEquals(setOf(id(1)), vm.state.selected)
        }

    @Test
    fun `bulk archive is not sent in the Archive folder`() =
        runTest(dispatcher) {
            val box = Mailbox.Folder(work.id, workArchive.id)
            coEvery { repo.messages(box, null) } returns MailResult.Ok(MailPage(listOf(mail(1, folder = workArchive.id)), null))
            val vm = loaded()
            vm.select(box)
            advanceUntilIdle()
            vm.toggleSelection(id(1))

            vm.actOnSelection(MailListAction.ARCHIVE)
            advanceUntilIdle()

            coVerify(exactly = 0) { repo.actAll(any(), MailListAction.ARCHIVE, any()) }
            assertEquals(listOf(id(1)), vm.ids())
        }

    @Test
    fun `moving the selection sends MOVE with the folder and takes the mails out`() =
        runTest(dispatcher) {
            val vm = loaded()
            vm.toggleSelection(id(1))
            vm.toggleSelection(id(2))

            vm.moveSelection(projects)
            advanceUntilIdle()

            coVerify(exactly = 1) { repo.actAll(listOf(id(1), id(2)), MailListAction.MOVE, projects.id) }
            assertEquals(listOf(id(3), id(5)), vm.ids())
            assertFalse(vm.state.selecting)
            assertEquals(MailListAction.MOVE, vm.state.undo?.action)
        }

    @Test
    fun `moving the selection to the folder it is in, or to a folder of another account, does nothing`() =
        runTest(dispatcher) {
            val vm = loaded()
            vm.toggleSelection(id(1))

            vm.moveSelection(workInbox)
            vm.moveSelection(homeInbox)
            advanceUntilIdle()

            coVerify(exactly = 0) { repo.actAll(any(), any(), any()) }
            assertEquals(setOf(id(1)), vm.state.selected)
        }

    @Test
    fun `mails the server could not move come back in their places, the others stay gone, and no undo is offered`() =
        runTest(dispatcher) {
            coEvery { repo.actAll(listOf(id(1), id(2), id(3)), MailListAction.ARCHIVE, null) } returns MailResult.Ok(setOf(id(1), id(3)))
            val vm = loaded()
            listOf(1, 2, 3).forEach { vm.toggleSelection(id(it)) }

            vm.actOnSelection(MailListAction.ARCHIVE)
            assertEquals(listOf(id(5)), vm.ids())
            advanceUntilIdle()

            assertEquals(listOf(id(2), id(5)), vm.ids())
            assertEquals(MailListMessage.ARCHIVE_FAILED, vm.state.message)
            assertNull(vm.state.undo)
        }

    @Test
    fun `marks the server refused are taken back, the others stay`() =
        runTest(dispatcher) {
            coEvery { repo.actAll(listOf(id(1), id(2)), MailListAction.FLAG, null) } returns MailResult.Ok(setOf(id(1)))
            val vm = loaded()
            vm.toggleSelection(id(1))
            vm.toggleSelection(id(2))

            vm.actOnSelection(MailListAction.FLAG)
            advanceUntilIdle()

            assertTrue(vm.mail(1).flagged)
            assertFalse(vm.mail(2).flagged)
            assertEquals(MailListMessage.MARK_FAILED, vm.state.message)
            assertNull(vm.state.undo)
        }

    @Test
    fun `an offline or refused bulk action brings every mail back with the action's message`() =
        runTest(dispatcher) {
            val cases =
                listOf(
                    MailListAction.MOVE to MailListMessage.MOVE_FAILED,
                    MailListAction.SPAM to MailListMessage.SPAM_FAILED,
                    MailListAction.DELETE to MailListMessage.DELETE_FAILED,
                    MailListAction.UNREAD to MailListMessage.MARK_FAILED,
                )
            cases.forEach { (action, message) ->
                coEvery { repo.actAll(any(), action, any()) } returns
                    if (action == MailListAction.SPAM) MailResult.Failed(400) else MailResult.Offline
                val vm = loaded()
                vm.toggleSelection(id(1))
                vm.toggleSelection(id(2))

                if (action == MailListAction.MOVE) vm.moveSelection(projects) else vm.actOnSelection(action)
                advanceUntilIdle()

                assertEquals("$action", unified.map { it.id }, vm.ids())
                assertEquals("$action", unified, vm.state.messages)
                assertEquals("$action", message, vm.state.message)
                assertNull(vm.state.undo)
            }
        }

    // Undo

    @Test
    fun `undoing a mark sends the inverse and changes the mails back`() =
        runTest(dispatcher) {
            val vm = loaded()
            vm.toggleSelection(id(1))
            vm.toggleSelection(id(2))
            vm.actOnSelection(MailListAction.FLAG)
            advanceUntilIdle()
            val undo = checkNotNull(vm.state.undo)

            vm.undo(undo)
            assertNull(vm.state.undo)
            assertFalse(vm.mail(1).flagged)
            advanceUntilIdle()

            coVerify(exactly = 1) { repo.actAll(listOf(id(1), id(2)), MailListAction.UNFLAG, null) }
            assertFalse(vm.mail(1).flagged)
            assertFalse(vm.mail(2).flagged)
            // Undoing is not offered to undo again.
            assertNull(vm.state.undo)
            assertNull(vm.state.message)
        }

    @Test
    fun `a failed undo of a mark keeps the marks and says the undo failed`() =
        runTest(dispatcher) {
            coEvery { repo.actAll(listOf(id(1)), MailListAction.UNREAD, null) } returns MailResult.Offline
            val vm = loaded()
            vm.toggleSelection(id(1))
            vm.actOnSelection(MailListAction.READ)
            advanceUntilIdle()

            vm.undo(checkNotNull(vm.state.undo))
            advanceUntilIdle()

            assertTrue(vm.mail(1).seen)
            assertEquals(MailListMessage.UNDO_FAILED, vm.state.message)
        }

    @Test
    fun `undoing a move puts the mails back in their places, moves them to their folder and reloads`() =
        runTest(dispatcher) {
            val back = CompletableDeferred<MailResult<Set<UUID>>>()
            coEvery { repo.actAll(listOf(id(1), id(3)), MailListAction.MOVE, workInbox.id) } coAnswers { back.await() }
            val vm = loaded()
            vm.toggleSelection(id(1))
            vm.toggleSelection(id(3))
            vm.moveSelection(projects)
            advanceUntilIdle()
            assertEquals(listOf(id(2), id(5)), vm.ids())

            vm.undo(checkNotNull(vm.state.undo))
            assertEquals(unified.map { it.id }, vm.ids())

            back.complete(MailResult.Ok(setOf(id(1), id(3))))
            advanceUntilIdle()

            coVerify(exactly = 1) { repo.actAll(listOf(id(1), id(3)), MailListAction.MOVE, workInbox.id) }
            // The first load and the reload after the undo.
            coVerify(exactly = 2) { repo.messages(Mailbox.Unified, null) }
            assertNull(vm.state.message)
        }

    @Test
    fun `undoing an archive of mails from two accounts moves each back to its own folder`() =
        runTest(dispatcher) {
            val vm = loaded()
            vm.toggleSelection(id(2))
            vm.toggleSelection(id(5))
            vm.actOnSelection(MailListAction.ARCHIVE)
            advanceUntilIdle()

            vm.undo(checkNotNull(vm.state.undo))
            advanceUntilIdle()

            coVerify(exactly = 1) { repo.actAll(listOf(id(2)), MailListAction.MOVE, workInbox.id) }
            coVerify(exactly = 1) { repo.actAll(listOf(id(5)), MailListAction.MOVE, homeInbox.id) }
        }

    @Test
    fun `a swipe offers its undo too`() =
        runTest(dispatcher) {
            val vm = loaded()
            vm.act(id(2), MailListAction.DELETE)
            advanceUntilIdle()

            val undo = checkNotNull(vm.state.undo)
            assertEquals(MailListAction.DELETE, undo.action)
            assertEquals(listOf(id(2)), undo.mails.map { it.id })
            assertEquals(mapOf(id(2) to 1), undo.positions)
        }

    @Test
    fun `a failed undo of a move takes the mails out again and says so, without reloading`() =
        runTest(dispatcher) {
            coEvery { repo.actAll(any(), MailListAction.MOVE, workInbox.id) } returns MailResult.Ok(emptySet())
            val vm = loaded()
            vm.toggleSelection(id(1))
            vm.toggleSelection(id(2))
            vm.actOnSelection(MailListAction.ARCHIVE)
            advanceUntilIdle()

            vm.undo(checkNotNull(vm.state.undo))
            advanceUntilIdle()

            assertEquals(listOf(id(3), id(5)), vm.ids())
            assertEquals(MailListMessage.UNDO_FAILED, vm.state.message)
            coVerify(exactly = 1) { repo.messages(Mailbox.Unified, null) }
        }

    @Test
    fun `a partly failed undo of a move keeps only the mails moved back, and reloads`() =
        runTest(dispatcher) {
            coEvery { repo.actAll(listOf(id(1), id(2)), MailListAction.MOVE, workInbox.id) } returns MailResult.Ok(setOf(id(1)))
            val vm = loaded()
            vm.toggleSelection(id(1))
            vm.toggleSelection(id(2))
            vm.actOnSelection(MailListAction.SPAM)
            advanceUntilIdle()
            // The reload would bring the server's state; here it answers what was there before the action.
            coEvery { repo.messages(Mailbox.Unified, null) } returns MailResult.Ok(MailPage(listOf(mail(1), mail(3)), null))

            vm.undo(checkNotNull(vm.state.undo))
            advanceUntilIdle()

            coVerify(exactly = 2) { repo.messages(Mailbox.Unified, null) }
            assertEquals(listOf(id(1), id(3)), vm.ids())
            // The mail that could not be moved back is gone from the list: the user must be told, also after the reload.
            assertEquals(MailListMessage.UNDO_FAILED, vm.state.message)
        }

    @Test
    fun `a delete in the Trash is permanent and offers no undo`() =
        runTest(dispatcher) {
            val trash = Mailbox.Folder(work.id, workTrash.id)
            coEvery { repo.messages(trash, null) } returns
                MailResult.Ok(MailPage(listOf(mail(1, folder = workTrash.id), mail(2, folder = workTrash.id)), null))
            val vm = loaded()
            vm.select(trash)
            advanceUntilIdle()
            vm.toggleSelection(id(1))

            vm.actOnSelection(MailListAction.DELETE)
            advanceUntilIdle()

            assertEquals(listOf(id(2)), vm.ids())
            assertNull(vm.state.undo)

            // Moving out of the Trash can be undone.
            vm.toggleSelection(id(2))
            vm.moveSelection(projects)
            advanceUntilIdle()
            assertEquals(MailListAction.MOVE, vm.state.undo?.action)
        }

    @Test
    fun `a move of a mail in no known folder offers no undo, a mark does`() =
        runTest(dispatcher) {
            val unknown = UUID.fromString("00000000-0000-7000-8000-000000000999")
            coEvery { repo.messages(Mailbox.Unified, null) } returns
                MailResult.Ok(MailPage(listOf(mail(1, folder = null), mail(2, folder = unknown), mail(3)), null))
            val vm = loaded()

            vm.act(id(1), MailListAction.ARCHIVE)
            advanceUntilIdle()
            assertNull(vm.state.undo)

            vm.act(id(2), MailListAction.DELETE)
            advanceUntilIdle()
            assertNull(vm.state.undo)

            vm.toggleSelection(id(3))
            vm.actOnSelection(MailListAction.FLAG)
            advanceUntilIdle()
            assertNotNull(vm.state.undo)
        }

    @Test
    fun `an undo no longer offered is ignored, and dismissing forgets only the one offered`() =
        runTest(dispatcher) {
            val vm = loaded()
            vm.act(id(1), MailListAction.ARCHIVE)
            advanceUntilIdle()
            val first = checkNotNull(vm.state.undo)
            vm.act(id(2), MailListAction.ARCHIVE)
            advanceUntilIdle()
            val second = checkNotNull(vm.state.undo)
            assertNotEquals(first, second)

            vm.undo(first)
            vm.dismissUndo(first)
            advanceUntilIdle()
            assertEquals(second, vm.state.undo)
            coVerify(exactly = 0) { repo.actAll(any(), MailListAction.MOVE, any()) }

            vm.dismissUndo(second)
            assertNull(vm.state.undo)
            vm.undo(second)
            advanceUntilIdle()
            coVerify(exactly = 0) { repo.actAll(any(), MailListAction.MOVE, any()) }
        }

    @Test
    fun `the same action twice is offered twice`() =
        runTest(dispatcher) {
            val vm = loaded()
            vm.toggleSelection(id(1))
            vm.actOnSelection(MailListAction.FLAG)
            advanceUntilIdle()
            val first = checkNotNull(vm.state.undo)
            vm.undo(first)
            advanceUntilIdle()
            vm.toggleSelection(id(1))
            vm.actOnSelection(MailListAction.FLAG)
            advanceUntilIdle()

            assertNotEquals(first.id, checkNotNull(vm.state.undo).id)
        }

    @Test
    fun `a new action withdraws the undo offered before`() =
        runTest(dispatcher) {
            val pending = CompletableDeferred<MailResult<Set<UUID>>>()
            val vm = loaded()
            vm.act(id(1), MailListAction.ARCHIVE)
            advanceUntilIdle()
            assertNotNull(vm.state.undo)
            coEvery { repo.actAll(listOf(id(2)), MailListAction.DELETE, null) } coAnswers { pending.await() }

            vm.act(id(2), MailListAction.DELETE)

            assertNull(vm.state.undo)
            pending.complete(MailResult.Ok(setOf(id(2))))
            advanceUntilIdle()
            assertEquals(MailListAction.DELETE, vm.state.undo?.action)
        }

    @Test
    fun `when an older action answers after a newer one, the newer one stays offered to undo`() =
        runTest(dispatcher) {
            val older = CompletableDeferred<MailResult<Set<UUID>>>()
            coEvery { repo.actAll(listOf(id(1)), MailListAction.ARCHIVE, null) } coAnswers { older.await() }
            val vm = loaded()

            vm.act(id(1), MailListAction.ARCHIVE)
            vm.act(id(2), MailListAction.DELETE)
            advanceUntilIdle()
            assertEquals(MailListAction.DELETE, vm.state.undo?.action)

            older.complete(MailResult.Ok(setOf(id(1))))
            advanceUntilIdle()

            // "The last action that can be undone, offered until the next one": the delete started last.
            assertEquals(MailListAction.DELETE, vm.state.undo?.action)
        }

    @Test
    fun `undoing a move after another mailbox was opened does not put the mails into that list`() =
        runTest(dispatcher) {
            val box = Mailbox.Folder(work.id, projects.id)
            coEvery { repo.messages(box, null) } returns MailResult.Ok(MailPage(listOf(mail(7, folder = projects.id)), null))
            val vm = loaded()
            vm.act(id(1), MailListAction.ARCHIVE)
            advanceUntilIdle()
            val undo = checkNotNull(vm.state.undo)
            vm.select(box)
            advanceUntilIdle()

            vm.undo(undo)
            assertEquals(listOf(id(7)), vm.ids())
            advanceUntilIdle()

            coVerify(exactly = 1) { repo.actAll(listOf(id(1)), MailListAction.MOVE, workInbox.id) }
        }

    // The read view's marks

    @Test
    fun `a mark from the read view shows at once in the list and in the search results`() =
        runTest(dispatcher) {
            coEvery { repo.search(any(), any(), any()) } returns MailResult.Ok(MailSearchResult(listOf(mail(2))))
            val vm = loaded()
            vm.openSearch()
            vm.search("Subject")
            advanceUntilIdle()

            vm.marked(id(2), MailListAction.FLAG)
            assertTrue(vm.mail(2).flagged)
            assertTrue(
                vm.state.search!!
                    .results
                    .single()
                    .flagged,
            )

            vm.marked(id(2), MailListAction.UNREAD)
            assertFalse(vm.mail(2).seen)
            vm.marked(id(2), MailListAction.READ)
            assertTrue(vm.mail(2).seen)
            vm.marked(id(2), MailListAction.UNFLAG)
            assertFalse(vm.mail(2).flagged)
            assertTrue(vm.mail(1).let { !it.flagged && !it.seen })
        }

    @Test
    fun `a removing action reported as a mark changes nothing`() =
        runTest(dispatcher) {
            val vm = loaded()
            MailListAction.entries.filter { it.removes }.forEach { vm.marked(id(1), it) }
            assertEquals(unified, vm.state.messages)
        }
}
