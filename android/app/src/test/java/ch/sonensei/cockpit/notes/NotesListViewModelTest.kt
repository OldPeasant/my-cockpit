package ch.sonensei.cockpit.notes

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
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
}
