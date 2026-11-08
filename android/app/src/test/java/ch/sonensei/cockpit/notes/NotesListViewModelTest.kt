package ch.sonensei.cockpit.notes

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
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

@OptIn(ExperimentalCoroutinesApi::class)
class NotesListViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repo = mockk<NotesRepository>()
    private val images = mockk<NoteImages> { every { loader } returns mockk() }

    private fun note(n: Int) =
        Note(UUID.fromString("00000000-0000-7000-8000-%012d".format(n)), null, "Note $n", "", false, listOf("home"), Instant.EPOCH, 1)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        coEvery { repo.tags() } returns NotesResult.Ok(listOf(NoteTag("home", 2), NoteTag("work", 1)))
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `loads the notes and tags, and filters by a tag`() =
        runTest(dispatcher) {
            coEvery { repo.list(null) } returns NotesResult.Ok(listOf(note(1), note(2)))
            coEvery { repo.list("work") } returns NotesResult.Ok(listOf(note(3)))
            val vm = NotesListViewModel(repo, images)
            vm.refresh()
            advanceUntilIdle()
            assertEquals(listOf(note(1), note(2)), vm.uiState.value.notes)
            assertEquals(2, vm.uiState.value.tags.size)
            assertTrue(vm.uiState.value.loaded)

            vm.selectTag("work")
            assertEquals(emptyList<Note>(), vm.uiState.value.notes)
            advanceUntilIdle()
            assertEquals("work", vm.uiState.value.tag)
            assertEquals(listOf(note(3)), vm.uiState.value.notes)

            // Choosing the active tag again clears the filter.
            vm.selectTag("WORK")
            advanceUntilIdle()
            assertNull(vm.uiState.value.tag)
            coVerify(exactly = 2) { repo.list(null) }
        }

    @Test
    fun `a list for an earlier filter that answers last is dropped`() =
        runTest(dispatcher) {
            val all = CompletableDeferred<NotesResult<List<Note>>>()
            coEvery { repo.list(null) } coAnswers { all.await() }
            coEvery { repo.list("work") } returns NotesResult.Ok(listOf(note(3)))
            val vm = NotesListViewModel(repo, images)
            vm.refresh()
            runCurrent()
            vm.selectTag("work")
            advanceUntilIdle()
            assertEquals(listOf(note(3)), vm.uiState.value.notes)

            // The unfiltered answer arrives late, with success or failure: neither may replace the filtered list.
            all.complete(NotesResult.Ok(listOf(note(1), note(2))))
            advanceUntilIdle()
            assertEquals("work", vm.uiState.value.tag)
            assertEquals(listOf(note(3)), vm.uiState.value.notes)
            assertNull(vm.uiState.value.message)
            assertEquals(false, vm.uiState.value.loading)
        }

    @Test
    fun `a failed first load is not shown as an empty list`() =
        runTest(dispatcher) {
            coEvery { repo.list(null) } returns NotesResult.Offline andThen NotesResult.Ok(listOf(note(1)))
            val vm = NotesListViewModel(repo, images)
            vm.refresh()
            assertTrue(vm.uiState.value.loading)
            advanceUntilIdle()
            assertEquals(false, vm.uiState.value.loaded)
            assertEquals(NotesListMessage.OFFLINE, vm.uiState.value.message)

            vm.refresh()
            advanceUntilIdle()
            assertTrue(vm.uiState.value.loaded)
            assertNull(vm.uiState.value.message)
            assertEquals(listOf(note(1)), vm.uiState.value.notes)
        }

    @Test
    fun `failed tags keep the tags shown before`() =
        runTest(dispatcher) {
            coEvery { repo.tags() } returns NotesResult.Ok(listOf(NoteTag("home", 1))) andThen NotesResult.Offline
            coEvery { repo.list(null) } returns NotesResult.Ok(listOf(note(1)))
            val vm = NotesListViewModel(repo, images)
            vm.refresh()
            advanceUntilIdle()
            vm.refresh()
            advanceUntilIdle()
            assertEquals(listOf(NoteTag("home", 1)), vm.uiState.value.tags)
            assertNull(vm.uiState.value.message)
        }

    @Test
    fun `choosing All without a filter does not reload`() =
        runTest(dispatcher) {
            coEvery { repo.list(null) } returns NotesResult.Ok(listOf(note(1)))
            val vm = NotesListViewModel(repo, images)
            vm.refresh()
            advanceUntilIdle()
            vm.selectTag(null)
            advanceUntilIdle()
            coVerify(exactly = 1) { repo.list(null) }
            assertEquals(listOf(note(1)), vm.uiState.value.notes)
        }

    @Test
    fun `the list keeps the order the repository returns`() =
        runTest(dispatcher) {
            val ordered = listOf(note(1).copy(pinned = true), note(3), note(2))
            coEvery { repo.list(null) } returns NotesResult.Ok(ordered)
            val vm = NotesListViewModel(repo, images)
            vm.refresh()
            advanceUntilIdle()
            assertEquals(ordered, vm.uiState.value.notes)
        }

    @Test
    fun `a failed reload keeps the shown notes with a message`() =
        runTest(dispatcher) {
            coEvery { repo.list(null) } returns NotesResult.Ok(listOf(note(1))) andThen NotesResult.Offline andThen NotesResult.Failed(500)
            val vm = NotesListViewModel(repo, images)
            vm.refresh()
            advanceUntilIdle()
            vm.refresh()
            advanceUntilIdle()
            assertEquals(listOf(note(1)), vm.uiState.value.notes)
            assertEquals(NotesListMessage.OFFLINE, vm.uiState.value.message)
            vm.refresh()
            advanceUntilIdle()
            assertEquals(NotesListMessage.FAILED, vm.uiState.value.message)
            assertEquals(false, vm.uiState.value.loading)
        }

    private fun TestScope.loadedWith(vararg shown: Note): NotesListViewModel {
        coEvery { repo.list(null, false) } returns NotesResult.Ok(shown.toList())
        val vm = NotesListViewModel(repo, images)
        vm.refresh()
        advanceUntilIdle()
        return vm
    }

    @Test
    fun `the Archived view loads the archived notes, and back to the notes`() =
        runTest(dispatcher) {
            val archived = note(5).copy(archived = true)
            coEvery { repo.list(null, true) } returns NotesResult.Ok(listOf(archived))
            val vm = loadedWith(note(1))

            vm.showArchived(true)
            assertEquals(emptyList<Note>(), vm.uiState.value.notes)
            assertEquals(false, vm.uiState.value.loaded)
            advanceUntilIdle()
            assertTrue(vm.uiState.value.archived)
            assertEquals(listOf(archived), vm.uiState.value.notes)

            // Choosing the active view again does not reload.
            vm.showArchived(true)
            advanceUntilIdle()
            coVerify(exactly = 1) { repo.list(null, true) }

            vm.showArchived(false)
            advanceUntilIdle()
            assertEquals(listOf(note(1)), vm.uiState.value.notes)
            coVerify(exactly = 2) { repo.list(null, false) }
        }

    @Test
    fun `the Archived view keeps the tag filter`() =
        runTest(dispatcher) {
            coEvery { repo.list("work", false) } returns NotesResult.Ok(listOf(note(3)))
            coEvery { repo.list("work", true) } returns NotesResult.Ok(emptyList())
            val vm = loadedWith(note(1))
            vm.selectTag("work")
            advanceUntilIdle()
            vm.showArchived(true)
            advanceUntilIdle()
            coVerify(exactly = 1) { repo.list("work", true) }
            assertEquals("work", vm.uiState.value.tag)
        }

    @Test
    fun `the search runs once the typing paused 300 ms and shows only the notes it found, in list order`() =
        runTest(dispatcher) {
            coEvery { repo.search("milk") } returns NotesResult.Ok(listOf(id(3), id(1)))
            val vm = loadedWith(note(1), note(2), note(3))

            vm.setQuery("m")
            advanceTimeBy(200)
            vm.setQuery("milk")
            assertTrue(vm.uiState.value.searching)
            // Nothing is shown before the search answered.
            assertEquals(emptyList<Note>(), vm.uiState.value.shown)
            advanceTimeBy(299)
            runCurrent()
            coVerify(exactly = 0) { repo.search(any()) }
            advanceTimeBy(2)
            runCurrent()
            advanceUntilIdle()

            coVerify(exactly = 1) { repo.search("milk") }
            assertEquals(setOf(id(1), id(3)), vm.uiState.value.hits)
            assertEquals(listOf(note(1), note(3)), vm.uiState.value.shown)
            assertEquals(false, vm.uiState.value.loading)
        }

    @Test
    fun `clearing the search shows every note again without asking the server`() =
        runTest(dispatcher) {
            coEvery { repo.search("milk") } returns NotesResult.Ok(listOf(id(1)))
            val vm = loadedWith(note(1), note(2))
            vm.setQuery("milk")
            advanceUntilIdle()
            assertEquals(listOf(note(1)), vm.uiState.value.shown)

            vm.setQuery("")
            advanceUntilIdle()
            assertNull(vm.uiState.value.hits)
            assertEquals(listOf(note(1), note(2)), vm.uiState.value.shown)
            coVerify(exactly = 1) { repo.search(any()) }

            // Only spaces added: no new search.
            vm.setQuery("milk")
            advanceUntilIdle()
            vm.setQuery("milk ")
            advanceUntilIdle()
            coVerify(exactly = 2) { repo.search("milk") }
        }

    @Test
    fun `clearing the search while it waits for the server ends the loading`() =
        runTest(dispatcher) {
            val answer = CompletableDeferred<NotesResult<List<UUID>>>()
            coEvery { repo.search("milk") } coAnswers { answer.await() }
            val vm = loadedWith(note(1), note(2))
            vm.setQuery("milk")
            advanceTimeBy(NotesListViewModel.SEARCH_DELAY_MS + 1)
            runCurrent()
            assertTrue(vm.uiState.value.loading)

            vm.setQuery("")
            advanceUntilIdle()

            assertEquals(listOf(note(1), note(2)), vm.uiState.value.shown)
            assertEquals(false, vm.uiState.value.loading)
            assertNull(vm.uiState.value.hits)

            // The cancelled search answers late: nothing changes.
            answer.complete(NotesResult.Ok(listOf(id(1))))
            advanceUntilIdle()
            assertNull(vm.uiState.value.hits)
            assertEquals(false, vm.uiState.value.loading)
        }

    @Test
    fun `a reload with a search text keeps loading until both the list and the search answered`() =
        runTest(dispatcher) {
            coEvery { repo.search("milk") } returns NotesResult.Ok(listOf(id(1)))
            val vm = loadedWith(note(1), note(2))
            vm.setQuery("milk")
            advanceUntilIdle()

            val answer = CompletableDeferred<NotesResult<List<UUID>>>()
            coEvery { repo.search("milk") } coAnswers { answer.await() }
            vm.refresh()
            advanceUntilIdle()
            // The list answered, the search did not yet.
            assertTrue(vm.uiState.value.loading)

            answer.complete(NotesResult.Ok(listOf(id(2))))
            advanceUntilIdle()
            assertEquals(false, vm.uiState.value.loading)
            assertEquals(listOf(note(2)), vm.uiState.value.shown)
        }

    @Test
    fun `a new search text drops the hits of the previous one at once, also when its search fails`() =
        runTest(dispatcher) {
            coEvery { repo.search("milk") } returns NotesResult.Ok(listOf(id(1)))
            coEvery { repo.search("bread") } returns NotesResult.Offline
            val vm = loadedWith(note(1), note(2))
            vm.setQuery("milk")
            advanceUntilIdle()
            assertEquals(listOf(note(1)), vm.uiState.value.shown)

            vm.setQuery("bread")
            assertNull(vm.uiState.value.hits)
            assertEquals(emptyList<Note>(), vm.uiState.value.shown)
            advanceUntilIdle()
            assertNull(vm.uiState.value.hits)
            assertEquals(emptyList<Note>(), vm.uiState.value.shown)
            assertEquals(NotesListMessage.OFFLINE, vm.uiState.value.message)
        }

    @Test
    fun `a successful search takes away the message of a failed one, but not a failed load's`() =
        runTest(dispatcher) {
            coEvery { repo.search("milk") } returns NotesResult.Offline andThen NotesResult.Ok(listOf(id(1)))
            val vm = loadedWith(note(1), note(2))
            vm.setQuery("milk")
            advanceUntilIdle()
            assertEquals(NotesListMessage.OFFLINE, vm.uiState.value.message)

            vm.setQuery("milk again")
            coEvery { repo.search("milk again") } returns NotesResult.Ok(listOf(id(1)))
            advanceUntilIdle()
            assertNull(vm.uiState.value.message)
            assertEquals(listOf(note(1)), vm.uiState.value.shown)

            // A failed load's message stays through a successful search.
            coEvery { repo.list(null, false) } returns NotesResult.Failed(500)
            vm.refresh()
            advanceUntilIdle()
            assertEquals(NotesListMessage.FAILED, vm.uiState.value.message)
            vm.setQuery("bread")
            coEvery { repo.search("bread") } returns NotesResult.Ok(emptyList())
            advanceUntilIdle()
            assertEquals(NotesListMessage.FAILED, vm.uiState.value.message)
        }

    @Test
    fun `clearing or redoing a search that failed like the list load keeps the load's message and its Retry`() =
        runTest(dispatcher) {
            coEvery { repo.list(null, false) } returns NotesResult.Offline
            coEvery { repo.search(any()) } returns NotesResult.Offline
            val vm = NotesListViewModel(repo, images)
            vm.refresh()
            advanceUntilIdle()
            assertEquals(NotesListMessage.OFFLINE, vm.uiState.value.message)
            assertFalse(vm.uiState.value.loaded)

            vm.setQuery("milk")
            advanceUntilIdle()
            assertEquals(NotesListMessage.OFFLINE, vm.uiState.value.message)
            vm.setQuery("")
            assertEquals(NotesListMessage.OFFLINE, vm.uiState.value.message)

            // A successful search does not take the load's message away either.
            coEvery { repo.search("bread") } returns NotesResult.Ok(emptyList())
            vm.setQuery("milk")
            advanceUntilIdle()
            vm.setQuery("bread")
            advanceUntilIdle()
            assertEquals(NotesListMessage.OFFLINE, vm.uiState.value.message)

            // Retry loads the list again, and its success takes the message away.
            vm.setQuery("")
            coEvery { repo.list(null, false) } returns NotesResult.Ok(listOf(note(1)))
            vm.refresh()
            advanceUntilIdle()
            assertNull(vm.uiState.value.message)
            assertTrue(vm.uiState.value.loaded)
            assertEquals(listOf(note(1)), vm.uiState.value.shown)
        }

    @Test
    fun `clearing a failed search takes its message away`() =
        runTest(dispatcher) {
            coEvery { repo.search("milk") } returns NotesResult.Offline
            val vm = loadedWith(note(1), note(2))
            vm.setQuery("milk")
            advanceUntilIdle()
            assertEquals(NotesListMessage.OFFLINE, vm.uiState.value.message)

            vm.setQuery("")
            assertNull(vm.uiState.value.message)
        }

    @Test
    fun `a load that started before an archive does not bring the archived note back`() =
        runTest(dispatcher) {
            val vm = loadedWith(note(1), note(2))
            val stale = CompletableDeferred<NotesResult<List<Note>>>()
            coEvery { repo.list(null, false) } coAnswers { stale.await() } andThen NotesResult.Ok(listOf(note(2)))
            coEvery { repo.setFlags(note(1), pinned = null, archived = true) } returns
                SaveResult.Saved(note(1).copy(archived = true, version = 2))
            vm.refresh()
            runCurrent()

            vm.toggleArchived(note(1))
            advanceUntilIdle()
            // The load from before the archive answers last, with the note still in it.
            stale.complete(NotesResult.Ok(listOf(note(1), note(2))))
            advanceUntilIdle()

            assertEquals(listOf(note(2)), vm.uiState.value.notes)
            assertEquals(false, vm.uiState.value.loading)
        }

    @Test
    fun `an older search that answers after a newer one is dropped`() =
        runTest(dispatcher) {
            val old = CompletableDeferred<NotesResult<List<UUID>>>()
            coEvery { repo.search("milk") } coAnswers { old.await() }
            coEvery { repo.search("bread") } returns NotesResult.Ok(listOf(id(2)))
            val vm = loadedWith(note(1), note(2))
            vm.setQuery("milk")
            advanceTimeBy(NotesListViewModel.SEARCH_DELAY_MS + 1)
            runCurrent()
            vm.setQuery("bread")
            advanceUntilIdle()
            old.complete(NotesResult.Ok(listOf(id(1))))
            advanceUntilIdle()

            assertEquals(listOf(note(2)), vm.uiState.value.shown)
        }

    @Test
    fun `a failed search says why, and a reload searches again at once`() =
        runTest(dispatcher) {
            coEvery { repo.search("milk") } returns NotesResult.Offline andThen NotesResult.Ok(listOf(id(1)))
            val vm = loadedWith(note(1), note(2))
            vm.setQuery("milk")
            advanceUntilIdle()
            assertEquals(NotesListMessage.OFFLINE, vm.uiState.value.message)
            assertNull(vm.uiState.value.hits)
            assertEquals(emptyList<Note>(), vm.uiState.value.shown)
            assertEquals(false, vm.uiState.value.loading)

            vm.refresh()
            advanceUntilIdle()
            coVerify(exactly = 2) { repo.search("milk") }
            assertEquals(listOf(note(1)), vm.uiState.value.shown)
            assertNull(vm.uiState.value.message)
            assertEquals(false, vm.uiState.value.loading)
        }

    @Test
    fun `pinning replaces the note and keeps pinned notes first`() =
        runTest(dispatcher) {
            val vm = loadedWith(note(1), note(2))
            val pinned = note(2).copy(pinned = true, version = 2)
            coEvery { repo.setFlags(note(2), pinned = true, archived = null) } returns SaveResult.Saved(pinned)

            vm.togglePin(note(2))
            assertEquals(setOf(id(2)), vm.uiState.value.busy)
            advanceUntilIdle()

            assertEquals(listOf(pinned, note(1)), vm.uiState.value.notes)
            assertEquals(emptySet<UUID>(), vm.uiState.value.busy)
            assertNull(vm.uiState.value.snackbar)
        }

    @Test
    fun `a change uses the latest shown version, and a second tap while it runs is ignored`() =
        runTest(dispatcher) {
            val newer = note(1).copy(version = 5)
            val vm = loadedWith(newer)
            val answer = CompletableDeferred<SaveResult>()
            coEvery { repo.setFlags(newer, pinned = true, archived = null) } coAnswers { answer.await() }

            // The row passed an older copy of the note.
            vm.togglePin(note(1))
            runCurrent()
            vm.togglePin(note(1))
            vm.delete(note(1))
            answer.complete(SaveResult.Saved(newer.copy(pinned = true, version = 6)))
            advanceUntilIdle()

            coVerify(exactly = 1) { repo.setFlags(any(), any(), any()) }
            coVerify(exactly = 0) { repo.delete(any()) }
        }

    @Test
    fun `archiving removes the note from the view with a snackbar`() =
        runTest(dispatcher) {
            val vm = loadedWith(note(1), note(2))
            val archived = note(1).copy(archived = true, version = 2)
            coEvery { repo.setFlags(note(1), pinned = null, archived = true) } returns SaveResult.Saved(archived)

            vm.toggleArchived(note(1))
            advanceUntilIdle()

            assertEquals(listOf(note(2)), vm.uiState.value.notes)
            val snackbar = vm.uiState.value.snackbar as NotesSnackbar.Archived
            assertEquals(archived, snackbar.note)
        }

    @Test
    fun `unarchiving in the Archived view removes the note from it`() =
        runTest(dispatcher) {
            val archived = note(5).copy(archived = true)
            coEvery { repo.list(null, true) } returns NotesResult.Ok(listOf(archived))
            val vm = loadedWith(note(1))
            vm.showArchived(true)
            advanceUntilIdle()
            coEvery { repo.setFlags(archived, pinned = null, archived = false) } returns SaveResult.Saved(note(5))

            vm.toggleArchived(archived)
            advanceUntilIdle()

            assertEquals(emptyList<Note>(), vm.uiState.value.notes)
            assertEquals(false, (vm.uiState.value.snackbar as NotesSnackbar.Archived).note.archived)
        }

    @Test
    fun `a conflicting pin says so and reloads the list, other failures only say so`() =
        runTest(dispatcher) {
            val vm = loadedWith(note(1))
            coEvery { repo.setFlags(note(1), pinned = true, archived = null) } returns
                SaveResult.Conflict(note(1).copy(version = 9)) andThen SaveResult.Offline andThen SaveResult.Failed(400)

            vm.togglePin(note(1))
            advanceUntilIdle()
            assertEquals(NoteChangeFailure.CONFLICT, (vm.uiState.value.snackbar as NotesSnackbar.Failed).failure)
            coVerify(exactly = 2) { repo.list(null, false) }

            vm.togglePin(note(1))
            advanceUntilIdle()
            assertEquals(NoteChangeFailure.OFFLINE, (vm.uiState.value.snackbar as NotesSnackbar.Failed).failure)
            coEvery { repo.setFlags(note(1), pinned = null, archived = true) } returns SaveResult.Failed(400)
            vm.toggleArchived(note(1))
            advanceUntilIdle()
            assertEquals(NoteChangeFailure.FAILED, (vm.uiState.value.snackbar as NotesSnackbar.Failed).failure)
            coVerify(exactly = 2) { repo.list(null, false) }
            assertEquals(listOf(note(1)), vm.uiState.value.notes)
        }

    @Test
    fun `deleting removes the note, offers Undo and reloads the tags`() =
        runTest(dispatcher) {
            val vm = loadedWith(note(1), note(2))
            coEvery { repo.delete(note(1)) } returns NotesResult.Ok(Unit)

            vm.delete(note(1))
            advanceUntilIdle()

            assertEquals(listOf(note(2)), vm.uiState.value.notes)
            assertEquals(note(1), (vm.uiState.value.snackbar as NotesSnackbar.Trashed).note)
            coVerify(exactly = 2) { repo.tags() }
        }

    @Test
    fun `Undo restores the note and loads the list again`() =
        runTest(dispatcher) {
            val vm = loadedWith(note(1), note(2))
            coEvery { repo.delete(note(1)) } returns NotesResult.Ok(Unit)
            coEvery { repo.restore(id(1)) } returns NotesResult.Ok(Unit)
            vm.delete(note(1))
            advanceUntilIdle()
            val trashed = vm.uiState.value.snackbar as NotesSnackbar.Trashed

            vm.undo(trashed)
            assertNull(vm.uiState.value.snackbar)
            advanceUntilIdle()

            coVerify(exactly = 1) { repo.restore(id(1)) }
            coVerify(exactly = 2) { repo.list(null, false) }
            assertEquals(listOf(note(1), note(2)), vm.uiState.value.notes)
            assertNull(vm.uiState.value.snackbar)
        }

    @Test
    fun `a failed Undo says so`() =
        runTest(dispatcher) {
            val vm = loadedWith(note(1))
            coEvery { repo.restore(id(1)) } returns NotesResult.Offline andThen NotesResult.Failed(404)
            vm.trashed(note(1))
            vm.undo(vm.uiState.value.snackbar as NotesSnackbar.Trashed)
            advanceUntilIdle()
            assertEquals(NoteChangeFailure.OFFLINE, (vm.uiState.value.snackbar as NotesSnackbar.Failed).failure)

            vm.undo(NotesSnackbar.Trashed(note(1)))
            advanceUntilIdle()
            assertEquals(NoteChangeFailure.FAILED, (vm.uiState.value.snackbar as NotesSnackbar.Failed).failure)
            coVerify(exactly = 1) { repo.list(null, false) }
        }

    @Test
    fun `a delete refused because the note changed or is gone reloads, offline and other refusals only say so`() =
        runTest(dispatcher) {
            val vm = loadedWith(note(1))
            coEvery { repo.delete(note(1)) } returns NotesResult.Failed(412) andThen NotesResult.Failed(404) andThen
                NotesResult.Offline andThen NotesResult.Failed(400)

            vm.delete(note(1))
            advanceUntilIdle()
            assertEquals(NoteChangeFailure.CONFLICT, (vm.uiState.value.snackbar as NotesSnackbar.Failed).failure)
            vm.delete(note(1))
            advanceUntilIdle()
            assertEquals(NoteChangeFailure.CONFLICT, (vm.uiState.value.snackbar as NotesSnackbar.Failed).failure)
            coVerify(exactly = 3) { repo.list(null, false) }

            vm.delete(note(1))
            advanceUntilIdle()
            assertEquals(NoteChangeFailure.OFFLINE, (vm.uiState.value.snackbar as NotesSnackbar.Failed).failure)
            vm.delete(note(1))
            advanceUntilIdle()
            assertEquals(NoteChangeFailure.FAILED, (vm.uiState.value.snackbar as NotesSnackbar.Failed).failure)
            coVerify(exactly = 3) { repo.list(null, false) }
            assertEquals(listOf(note(1)), vm.uiState.value.notes)
            assertEquals(emptySet<UUID>(), vm.uiState.value.busy)
        }

    @Test
    fun `each snackbar is a new one, and dismissing an older one keeps the newer`() =
        runTest(dispatcher) {
            val vm = loadedWith(note(1))
            vm.trashed(note(1))
            val first = vm.uiState.value.snackbar!!
            vm.trashed(note(1))
            val second = vm.uiState.value.snackbar!!
            assertTrue(first != second)

            vm.dismiss(first)
            assertEquals(second, vm.uiState.value.snackbar)
            vm.dismiss(second)
            assertNull(vm.uiState.value.snackbar)
        }

    @Test
    fun `unpinning sends pinned false and moves the note below the pinned ones`() =
        runTest(dispatcher) {
            val pinnedA = note(1).copy(pinned = true)
            val pinnedB = note(2).copy(pinned = true)
            val vm = loadedWith(pinnedA, pinnedB, note(3))
            val unpinned = note(1).copy(version = 2, updatedAt = Instant.EPOCH.plusSeconds(60))
            coEvery { repo.setFlags(pinnedA, pinned = false, archived = null) } returns SaveResult.Saved(unpinned)

            vm.togglePin(pinnedA)
            advanceUntilIdle()

            coVerify(exactly = 1) { repo.setFlags(pinnedA, pinned = false, archived = null) }
            // Still pinned first; the unpinned note is the most recently changed of the others.
            assertEquals(listOf(pinnedB, unpinned, note(3)), vm.uiState.value.notes)
        }

    @Test
    fun `deleting during a search removes the note from the results, and Undo searches again and shows it`() =
        runTest(dispatcher) {
            val vm = loadedWith(note(1), note(2), note(3))
            coEvery { repo.search("milk") } returns NotesResult.Ok(listOf(id(1), id(3)))
            vm.setQuery("milk")
            advanceUntilIdle()
            assertEquals(listOf(note(1), note(3)), vm.uiState.value.shown)

            coEvery { repo.delete(note(1)) } returns NotesResult.Ok(Unit)
            vm.delete(note(1))
            advanceUntilIdle()
            assertEquals(listOf(note(3)), vm.uiState.value.shown)
            assertEquals("milk", vm.uiState.value.query)

            coEvery { repo.restore(id(1)) } returns NotesResult.Ok(Unit)
            vm.undo(vm.uiState.value.snackbar as NotesSnackbar.Trashed)
            advanceUntilIdle()

            // The reload after Undo keeps the search text and asks the server's search again.
            coVerify(exactly = 2) { repo.search("milk") }
            assertEquals(listOf(note(1), note(3)), vm.uiState.value.shown)
            assertFalse(vm.uiState.value.loading)
        }

    @Test
    fun `pinning a found note during a search keeps it in the results, pinned first`() =
        runTest(dispatcher) {
            val vm = loadedWith(note(1), note(2), note(3))
            coEvery { repo.search("milk") } returns NotesResult.Ok(listOf(id(2), id(3)))
            vm.setQuery("milk")
            advanceUntilIdle()
            val pinned = note(3).copy(pinned = true, version = 2)
            coEvery { repo.setFlags(note(3), pinned = true, archived = null) } returns SaveResult.Saved(pinned)

            vm.togglePin(note(3))
            advanceUntilIdle()

            assertEquals(listOf(pinned, note(2)), vm.uiState.value.shown)
        }

    @Test
    fun `a delete whose tags cannot be reloaded keeps the tags shown before`() =
        runTest(dispatcher) {
            val vm = loadedWith(note(1), note(2))
            coEvery { repo.tags() } returns NotesResult.Offline
            coEvery { repo.delete(note(1)) } returns NotesResult.Ok(Unit)

            vm.delete(note(1))
            advanceUntilIdle()

            assertEquals(listOf(NoteTag("home", 2), NoteTag("work", 1)), vm.uiState.value.tags)
            assertEquals(listOf(note(2)), vm.uiState.value.notes)
        }

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))
}
