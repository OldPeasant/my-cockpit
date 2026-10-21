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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class NoteEditorViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val id = UUID.fromString("00000000-0000-7000-8000-000000000001")
    private val loaded = Note(id, null, "Shopping", "Shopping", pinned = false, tags = emptyList(), updatedAt = Instant.EPOCH, version = 3)
    private val repo = mockk<NotesRepository>()
    private val images = mockk<NoteImages> { every { loader } returns mockk() }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        coEvery { repo.get(id) } returns NotesResult.Ok(loaded)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun test(block: suspend TestScope.(NoteEditorViewModel) -> Unit) =
        runTest(dispatcher) {
            val vm = NoteEditorViewModel(repo, images)
            block(vm)
        }

    private fun saved(
        body: String,
        version: Long,
    ) = SaveResult.Saved(loaded.copy(bodyMd = body, displayTitle = body, version = version))

    @Test
    fun `opens the note in the rendered view`() =
        test { vm ->
            vm.open(id)
            runCurrent()
            val state = vm.uiState.value
            assertEquals(loaded, state.note)
            assertEquals("Shopping", state.body)
            assertEquals(false, state.editing)
            assertEquals(SaveStatus.SAVED, state.status)
        }

    @Test
    fun `saves automatically a second after the last change, over the saved version`() =
        test { vm ->
            coEvery { repo.save(loaded, NoteDraft("", "Shopping list")) } returns saved("Shopping list", 4)
            vm.open(id)
            runCurrent()
            vm.setEditing(true)
            vm.setBody("Shopping l")
            advanceTimeBy(500)
            vm.setBody("Shopping list")
            assertEquals(SaveStatus.UNSAVED, vm.uiState.value.status)
            advanceTimeBy(900)
            coVerify(exactly = 0) { repo.save(any(), any()) }
            advanceUntilIdle()

            coVerify(exactly = 1) { repo.save(any(), any()) }
            assertEquals(
                4L,
                vm.uiState.value.note!!
                    .version,
            )
            assertEquals(SaveStatus.SAVED, vm.uiState.value.status)
        }

    @Test
    fun `text typed during a save is saved next, over the version the save returned`() =
        test { vm ->
            val answer = CompletableDeferred<SaveResult>()
            coEvery { repo.save(loaded, NoteDraft("", "A")) } coAnswers { answer.await() }
            val afterFirst = saved("A", 4).note
            coEvery { repo.save(afterFirst, NoteDraft("", "AB")) } returns saved("AB", 5)
            vm.open(id)
            runCurrent()
            vm.setBody("A")
            advanceTimeBy(1_100)
            assertEquals(SaveStatus.SAVING, vm.uiState.value.status)
            vm.setBody("AB")
            answer.complete(saved("A", 4))
            advanceUntilIdle()

            coVerify(exactly = 1) { repo.save(afterFirst, NoteDraft("", "AB")) }
            assertEquals(
                5L,
                vm.uiState.value.note!!
                    .version,
            )
            assertEquals(SaveStatus.SAVED, vm.uiState.value.status)
        }

    @Test
    fun `a conflict stops the autosave until the user decides, keep mine saves over the server version`() =
        test { vm ->
            val theirs = loaded.copy(bodyMd = "Theirs", version = 7)
            coEvery { repo.save(loaded, any()) } returns SaveResult.Conflict(theirs)
            coEvery { repo.save(theirs, NoteDraft("", "Mine")) } returns saved("Mine", 8)
            vm.open(id)
            runCurrent()
            vm.setBody("Mine")
            advanceUntilIdle()
            assertEquals(NoteConflict(theirs), vm.uiState.value.conflict)
            assertEquals(false, vm.uiState.value.editable)
            vm.setBody("ignored")
            advanceUntilIdle()
            assertEquals("Mine", vm.uiState.value.body)

            vm.keepMine()
            advanceUntilIdle()

            assertNull(vm.uiState.value.conflict)
            assertEquals(
                8L,
                vm.uiState.value.note!!
                    .version,
            )
            assertEquals(SaveStatus.SAVED, vm.uiState.value.status)
        }

    @Test
    fun `use theirs drops the local text, and closes when the note was deleted`() =
        test { vm ->
            val theirs = loaded.copy(title = "T", bodyMd = "Theirs", version = 7)
            coEvery { repo.save(loaded, any()) } returns SaveResult.Conflict(theirs)
            vm.open(id)
            runCurrent()
            vm.setBody("Mine")
            advanceUntilIdle()
            vm.useTheirs()
            assertEquals("Theirs", vm.uiState.value.body)
            assertEquals("T", vm.uiState.value.title)
            assertEquals(SaveStatus.SAVED, vm.uiState.value.status)

            coEvery { repo.save(theirs, any()) } returns SaveResult.Conflict(null)
            vm.setBody("Again")
            advanceUntilIdle()
            vm.useTheirs()
            assertTrue(vm.uiState.value.closed)
        }

    @Test
    fun `offline keeps the text unsaved until saved again`() =
        test { vm ->
            coEvery { repo.save(loaded, any()) } returns SaveResult.Offline andThen saved("Mine", 4)
            vm.open(id)
            runCurrent()
            vm.setBody("Mine")
            advanceUntilIdle()
            assertEquals(SaveStatus.OFFLINE, vm.uiState.value.status)
            vm.saveNow()
            advanceUntilIdle()
            assertEquals(SaveStatus.SAVED, vm.uiState.value.status)
        }

    @Test
    fun `a new note is created on its first change only`() =
        test { vm ->
            val created = loaded.copy(bodyMd = "Hi", version = 1)
            coEvery { repo.save(null, NoteDraft("", "Hi")) } returns SaveResult.Saved(created)
            vm.open(null)
            assertTrue(vm.uiState.value.editing)
            vm.setEditing(false)
            advanceUntilIdle()
            coVerify(exactly = 0) { repo.save(any(), any()) }

            vm.setBody("Hi")
            advanceUntilIdle()
            assertEquals(created, vm.uiState.value.note)
        }

    @Test
    fun `a photo is uploaded and its image inserted at the cursor, then saved`() =
        test { vm ->
            val photo = NotePhoto(byteArrayOf(1), "image/jpeg", "p.jpg")
            coEvery { repo.uploadImage(id, photo) } returns NotesResult.Ok("![](cockpit-file:x)")
            coEvery { repo.save(loaded, NoteDraft("", "Shop\n![](cockpit-file:x)\nping")) } returns
                saved("Shop\n![](cockpit-file:x)\nping", 4)
            vm.open(id)
            runCurrent()
            vm.addPhoto(photo, at = 4)
            advanceUntilIdle()
            assertEquals("Shop\n![](cockpit-file:x)\nping", vm.uiState.value.body)
            assertEquals(SaveStatus.SAVED, vm.uiState.value.status)
            assertEquals(false, vm.uiState.value.uploading)
        }

    @Test
    fun `a photo for a new note creates the note first`() =
        test { vm ->
            val photo = NotePhoto(byteArrayOf(1), "image/jpeg", "p.jpg")
            val created = loaded.copy(bodyMd = "", version = 1)
            coEvery { repo.save(null, NoteDraft("", "")) } returns SaveResult.Saved(created)
            coEvery { repo.uploadImage(id, photo) } returns NotesResult.Ok("![](x)")
            coEvery { repo.save(created, any()) } returns saved("![](x)\n", 2)
            vm.open(null)
            vm.addPhoto(photo)
            advanceUntilIdle()
            assertEquals("![](x)\n", vm.uiState.value.body)
            assertEquals(
                2L,
                vm.uiState.value.note!!
                    .version,
            )
        }

    @Test
    fun `a photo that fails to upload or read shows a message and leaves the text`() =
        test { vm ->
            val photo = NotePhoto(byteArrayOf(1), "image/jpeg", "p.jpg")
            coEvery { repo.uploadImage(id, photo) } returns NotesResult.Failed(413)
            vm.open(id)
            runCurrent()
            vm.addPhoto(photo)
            advanceUntilIdle()
            assertEquals(NoteEditorMessage.PHOTO_FAILED, vm.uiState.value.message)
            assertEquals("Shopping", vm.uiState.value.body)
            vm.addPhoto(null)
            assertEquals(NoteEditorMessage.PHOTO_UNREADABLE, vm.uiState.value.message)
        }

    @Test
    fun `a failed load can be retried`() =
        test { vm ->
            coEvery { repo.get(id) } returns NotesResult.Offline andThen NotesResult.Ok(loaded)
            vm.open(id)
            runCurrent()
            assertEquals(NoteEditorMessage.LOAD_OFFLINE, vm.uiState.value.message)
            assertEquals(false, vm.uiState.value.loading)
            vm.retryLoad(id)
            runCurrent()
            assertEquals(loaded, vm.uiState.value.note)
            assertNull(vm.uiState.value.message)
        }
}
