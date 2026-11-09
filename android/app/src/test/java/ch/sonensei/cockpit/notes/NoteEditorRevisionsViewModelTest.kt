package ch.sonensei.cockpit.notes

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.UUID

/** The earlier versions in the editor: listed newest first, one shown, restored after the text written here is saved. */
@OptIn(ExperimentalCoroutinesApi::class)
class NoteEditorRevisionsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val id = UUID.fromString("00000000-0000-7000-8000-000000000001")
    private val loaded =
        Note(id, "Trip", "Trip", "Passport", pinned = true, tags = listOf("travel"), updatedAt = Instant.EPOCH, version = 3)
    private val v3 = NoteRevision(3, "Trip v3", "Passport, tickets", Instant.parse("2026-10-03T09:00:00Z"))
    private val v2 = NoteRevision(2, null, "Passport only", Instant.parse("2026-10-02T09:00:00Z"))
    private val v1 = NoteRevision(1, "Trip v1", "", Instant.parse("2026-10-01T09:00:00Z"))
    private val repo = mockk<NotesRepository>()
    private val images = mockk<NoteImages> { every { loader } returns mockk() }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        coEvery { repo.get(id) } returns NotesResult.Ok(loaded)
        coEvery { repo.revisions(id) } returns NotesResult.Ok(listOf(v3, v2, v1))
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun test(block: suspend TestScope.(NoteEditorViewModel) -> Unit) =
        runTest(dispatcher) {
            block(NoteEditorViewModel(repo, images, CoroutineScope(SupervisorJob() + dispatcher)))
        }

    private fun TestScope.opened(vm: NoteEditorViewModel) {
        vm.open(id)
        runCurrent()
    }

    private val NoteEditorViewModel.revisions get() = uiState.value.revisions

    @Test
    fun `opening the earlier versions shows them loading, then newest first with the newest selected`() =
        test { vm ->
            val answer = CompletableDeferred<NotesResult<List<NoteRevision>>>()
            coEvery { repo.revisions(id) } coAnswers { answer.await() }
            opened(vm)
            assertNull(vm.revisions)

            vm.openRevisions()
            assertEquals("loading: no list and no error yet", NoteRevisionsState(), vm.revisions)
            runCurrent()
            assertEquals(NoteRevisionsState(), vm.revisions)

            answer.complete(NotesResult.Ok(listOf(v3, v2, v1)))
            runCurrent()

            assertEquals(NoteRevisionsState(listOf(v3, v2, v1), selected = 3), vm.revisions)
            assertEquals(v3, vm.revisions?.selectedRevision)
            coVerify(exactly = 1) { repo.revisions(id) }
        }

    @Test
    fun `a note without earlier versions shows an empty list with nothing selected, and Restore does nothing`() =
        test { vm ->
            coEvery { repo.revisions(id) } returns NotesResult.Ok(emptyList())
            opened(vm)

            vm.openRevisions()
            runCurrent()
            assertEquals(NoteRevisionsState(emptyList(), selected = null), vm.revisions)
            assertNull(vm.revisions?.selectedRevision)

            vm.restoreRevision()
            advanceUntilIdle()
            coVerify(exactly = 0) { repo.restoreRevision(any(), any()) }
            assertEquals("the empty list stays open", NoteRevisionsState(emptyList()), vm.revisions)
        }

    @Test
    fun `versions that cannot load show offline or failed, and Retry loads them again`() =
        test { vm ->
            coEvery { repo.revisions(id) } returns NotesResult.Offline
            opened(vm)
            vm.openRevisions()
            runCurrent()
            assertEquals(NoteRevisionsState(error = RevisionsError.OFFLINE), vm.revisions)

            coEvery { repo.revisions(id) } returns NotesResult.Failed(404)
            vm.retryRevisions()
            assertEquals("retrying shows loading again", NoteRevisionsState(), vm.revisions)
            runCurrent()
            assertEquals(NoteRevisionsState(error = RevisionsError.FAILED), vm.revisions)

            coEvery { repo.revisions(id) } returns NotesResult.Ok(listOf(v3, v2))
            vm.retryRevisions()
            runCurrent()
            assertEquals(NoteRevisionsState(listOf(v3, v2), selected = 3), vm.revisions)
            coVerify(exactly = 3) { repo.revisions(id) }
        }

    @Test
    fun `Retry does nothing when the list loaded, or when it is closed`() =
        test { vm ->
            opened(vm)
            vm.retryRevisions()
            runCurrent()
            assertNull(vm.revisions)

            vm.openRevisions()
            runCurrent()
            vm.retryRevisions()
            runCurrent()
            assertEquals(NoteRevisionsState(listOf(v3, v2, v1), selected = 3), vm.revisions)
            coVerify(exactly = 1) { repo.revisions(id) }
        }

    @Test
    fun `selecting a version shows it, a number not in the list changes nothing`() =
        test { vm ->
            opened(vm)
            vm.selectRevision(2)
            assertNull("no list open: nothing to select", vm.revisions)

            vm.openRevisions()
            runCurrent()
            vm.selectRevision(2)
            assertEquals(2, vm.revisions?.selected)
            assertEquals(v2, vm.revisions?.selectedRevision)

            vm.selectRevision(42)
            assertEquals(2, vm.revisions?.selected)
        }

    @Test
    fun `selecting while the list loads changes nothing`() =
        test { vm ->
            val answer = CompletableDeferred<NotesResult<List<NoteRevision>>>()
            coEvery { repo.revisions(id) } coAnswers { answer.await() }
            opened(vm)
            vm.openRevisions()
            runCurrent()

            vm.selectRevision(2)
            assertEquals(NoteRevisionsState(), vm.revisions)

            answer.complete(NotesResult.Ok(listOf(v3, v2)))
            runCurrent()
            assertEquals("the newest is selected once loaded", 3, vm.revisions?.selected)
        }

    @Test
    fun `closing hides the list, and a load still running does not open it again`() =
        test { vm ->
            val answer = CompletableDeferred<NotesResult<List<NoteRevision>>>()
            coEvery { repo.revisions(id) } coAnswers { answer.await() }
            opened(vm)
            vm.openRevisions()
            runCurrent()

            vm.closeRevisions()
            assertNull(vm.revisions)
            answer.complete(NotesResult.Ok(listOf(v3)))
            advanceUntilIdle()

            assertNull(vm.revisions)
        }

    @Test
    fun `an older load that ends last does not replace the list opened again`() =
        test { vm ->
            val older = CompletableDeferred<NotesResult<List<NoteRevision>>>()
            coEvery { repo.revisions(id) } coAnswers { older.await() }
            opened(vm)
            vm.openRevisions()
            runCurrent()
            vm.closeRevisions()

            coEvery { repo.revisions(id) } returns NotesResult.Ok(listOf(v3, v2, v1))
            vm.openRevisions()
            runCurrent()
            assertEquals(3, vm.revisions?.revisions?.size)

            older.complete(NotesResult.Ok(listOf(v1)))
            advanceUntilIdle()
            assertEquals(NoteRevisionsState(listOf(v3, v2, v1), selected = 3), vm.revisions)
        }

    @Test
    fun `opening the list again while a load runs replaces it, so its older answer never shows`() =
        test { vm ->
            val older = CompletableDeferred<NotesResult<List<NoteRevision>>>()
            coEvery { repo.revisions(id) } coAnswers { older.await() }
            opened(vm)
            vm.openRevisions()
            runCurrent()

            coEvery { repo.revisions(id) } returns NotesResult.Offline
            vm.openRevisions()
            runCurrent()
            older.complete(NotesResult.Ok(listOf(v1)))
            advanceUntilIdle()

            assertEquals(NoteRevisionsState(error = RevisionsError.OFFLINE), vm.revisions)
        }

    @Test
    fun `restore saves the text written here first, then takes the restored title and body and closes the list`() =
        test { vm ->
            val saved = loaded.copy(bodyMd = "Passport, visa", version = 4)
            val restored = loaded.copy(title = "Trip v3", displayTitle = "Trip v3", bodyMd = "Passport, tickets", version = 5)
            coEvery { repo.save(loaded, NoteDraft("Trip", "Passport, visa", listOf("travel"))) } returns SaveResult.Saved(saved)
            coEvery { repo.restoreRevision(saved, 3) } returns SaveResult.Saved(restored)
            opened(vm)
            vm.setBody("Passport, visa")
            vm.openRevisions()
            runCurrent()

            vm.restoreRevision()
            assertNull("the list closes at once", vm.revisions)
            advanceUntilIdle()

            coVerifyOrder {
                repo.save(loaded, NoteDraft("Trip", "Passport, visa", listOf("travel")))
                repo.restoreRevision(saved, 3)
            }
            val s = vm.uiState.value
            assertEquals(restored, s.note)
            assertEquals("Trip v3", s.title)
            assertEquals("Passport, tickets", s.body)
            assertEquals(listOf("travel"), s.tags)
            assertEquals(SaveStatus.SAVED, s.status)
            assertFalse(s.changing)
            assertNull(s.message)
            assertNull(s.revisions)
            coVerify(exactly = 1) { repo.save(any(), any()) }
        }

    @Test
    fun `restoring a version without a title leaves the title empty, and nothing saved first when nothing changed`() =
        test { vm ->
            val restored = loaded.copy(title = null, displayTitle = "Passport only", bodyMd = "Passport only", version = 4)
            coEvery { repo.restoreRevision(loaded, 2) } returns SaveResult.Saved(restored)
            opened(vm)
            vm.openRevisions()
            runCurrent()
            vm.selectRevision(2)

            vm.restoreRevision()
            advanceUntilIdle()

            coVerify(exactly = 0) { repo.save(any(), any()) }
            coVerify(exactly = 1) { repo.restoreRevision(loaded, 2) }
            assertEquals("", vm.uiState.value.title)
            assertEquals("Passport only", vm.uiState.value.body)
            assertEquals(SaveStatus.SAVED, vm.uiState.value.status)
        }

    @Test
    fun `a restore refused because the note changed elsewhere shows the latest version when nothing is unsaved`() =
        test { vm ->
            val theirs = loaded.copy(bodyMd = "Theirs", version = 8)
            coEvery { repo.restoreRevision(loaded, 3) } returns SaveResult.Conflict(theirs)
            opened(vm)
            vm.openRevisions()
            runCurrent()

            vm.restoreRevision()
            advanceUntilIdle()

            val s = vm.uiState.value
            assertEquals(theirs, s.note)
            assertEquals("Theirs", s.body)
            assertEquals(NoteEditorMessage.CHANGE_CONFLICT, s.message)
            assertNull(s.conflict)
            assertFalse(s.changing)
            assertNull(s.revisions)
        }

    @Test
    fun `a restore of a note deleted elsewhere is a conflict to decide`() =
        test { vm ->
            coEvery { repo.restoreRevision(loaded, 3) } returns SaveResult.Conflict(null)
            opened(vm)
            vm.openRevisions()
            runCurrent()

            vm.restoreRevision()
            advanceUntilIdle()

            assertEquals(NoteConflict(null), vm.uiState.value.conflict)
            assertFalse(vm.uiState.value.editable)
            assertEquals("the text stays", "Passport", vm.uiState.value.body)
        }

    @Test
    fun `a restore without a connection or refused tells so and keeps the text`() =
        test { vm ->
            coEvery { repo.restoreRevision(loaded, 3) } returns SaveResult.Offline
            opened(vm)
            vm.openRevisions()
            runCurrent()
            vm.restoreRevision()
            advanceUntilIdle()

            assertEquals(NoteEditorMessage.CHANGE_OFFLINE, vm.uiState.value.message)
            assertEquals("Passport", vm.uiState.value.body)
            assertEquals(loaded, vm.uiState.value.note)
            assertFalse(vm.uiState.value.changing)

            coEvery { repo.restoreRevision(loaded, 3) } returns SaveResult.Failed(400)
            vm.openRevisions()
            runCurrent()
            vm.restoreRevision()
            advanceUntilIdle()
            assertEquals(NoteEditorMessage.CHANGE_FAILED, vm.uiState.value.message)
            assertEquals("Passport", vm.uiState.value.body)
        }

    @Test
    fun `when the text written here cannot be saved first, nothing is restored`() =
        test { vm ->
            coEvery { repo.save(loaded, any()) } returns SaveResult.Offline
            opened(vm)
            vm.setBody("Passport, visa")
            vm.openRevisions()
            runCurrent()

            vm.restoreRevision()
            advanceUntilIdle()

            coVerify(exactly = 0) { repo.restoreRevision(any(), any()) }
            assertEquals("Passport, visa", vm.uiState.value.body)
            assertEquals(SaveStatus.OFFLINE, vm.uiState.value.status)
            assertFalse(vm.uiState.value.changing)
        }

    @Test
    fun `the earlier versions cannot be opened for a new note not saved yet`() =
        test { vm ->
            vm.open(null)
            runCurrent()
            vm.openRevisions()
            assertNull(vm.revisions)
            coVerify(exactly = 0) { repo.revisions(any()) }
        }

    @Test
    fun `the earlier versions cannot be opened while the note loads`() =
        test { vm ->
            val pending = CompletableDeferred<NotesResult<Note>>()
            coEvery { repo.get(id) } coAnswers { pending.await() }
            vm.open(id)
            runCurrent()
            vm.openRevisions()
            assertNull(vm.revisions)
            coVerify(exactly = 0) { repo.revisions(any()) }
        }

    @Test
    fun `during a conflict the versions cannot be opened, and an open list cannot restore`() =
        test { vm ->
            coEvery { repo.save(loaded, any()) } returns SaveResult.Conflict(loaded.copy(version = 9))
            opened(vm)
            vm.openRevisions()
            runCurrent()
            vm.setBody("Mine")
            advanceUntilIdle()
            assertEquals(NoteConflict(loaded.copy(version = 9)), vm.uiState.value.conflict)

            vm.restoreRevision()
            advanceUntilIdle()
            coVerify(exactly = 0) { repo.restoreRevision(any(), any()) }

            vm.closeRevisions()
            vm.openRevisions()
            assertNull(vm.revisions)
            coVerify(exactly = 1) { repo.revisions(id) }
        }

    @Test
    fun `restore does nothing without an open list, or while the list loads`() =
        test { vm ->
            val answer = CompletableDeferred<NotesResult<List<NoteRevision>>>()
            coEvery { repo.revisions(id) } coAnswers { answer.await() }
            opened(vm)

            vm.restoreRevision()
            vm.openRevisions()
            runCurrent()
            vm.restoreRevision()
            advanceUntilIdle()

            coVerify(exactly = 0) { repo.restoreRevision(any(), any()) }
            assertEquals("the loading list stays open", NoteRevisionsState(), vm.revisions)
            assertFalse(vm.uiState.value.changing)
        }

    @Test
    fun `restore is ignored while a pin runs`() =
        test { vm ->
            val pin = CompletableDeferred<SaveResult>()
            coEvery { repo.setFlags(loaded, pinned = false) } coAnswers { pin.await() }
            opened(vm)
            vm.openRevisions()
            runCurrent()
            vm.togglePin()
            runCurrent()

            vm.restoreRevision()
            runCurrent()
            coVerify(exactly = 0) { repo.restoreRevision(any(), any()) }
            assertEquals("the list stays open", 3, vm.revisions?.selected)

            pin.complete(SaveResult.Saved(loaded.copy(pinned = false, version = 4)))
            advanceUntilIdle()
            coVerify(exactly = 0) { repo.restoreRevision(any(), any()) }
        }
}
