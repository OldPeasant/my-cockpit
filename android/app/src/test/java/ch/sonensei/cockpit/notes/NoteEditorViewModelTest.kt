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
    fun `keep mine on a note deleted elsewhere saves the text as a new note`() =
        test { vm ->
            val created = loaded.copy(id = UUID.fromString("00000000-0000-7000-8000-000000000099"), bodyMd = "Mine", version = 1)
            coEvery { repo.save(loaded, any()) } returns SaveResult.Conflict(null)
            coEvery { repo.save(null, NoteDraft("", "Mine")) } returns SaveResult.Saved(created)
            vm.open(id)
            runCurrent()
            vm.setBody("Mine")
            advanceUntilIdle()
            assertEquals(NoteConflict(null), vm.uiState.value.conflict)

            vm.keepMine()
            advanceUntilIdle()

            coVerify(exactly = 1) { repo.save(null, NoteDraft("", "Mine")) }
            assertEquals(created, vm.uiState.value.note)
            assertNull(vm.uiState.value.conflict)
            assertEquals(false, vm.uiState.value.closed)
            assertEquals(SaveStatus.SAVED, vm.uiState.value.status)
        }

    @Test
    fun `a conflict answering a save keeps the text typed meanwhile, which keep mine then saves`() =
        test { vm ->
            val theirs = loaded.copy(bodyMd = "Theirs", version = 7)
            val answer = CompletableDeferred<SaveResult>()
            coEvery { repo.save(loaded, NoteDraft("", "A")) } coAnswers { answer.await() }
            coEvery { repo.save(theirs, NoteDraft("", "AB")) } returns saved("AB", 8)
            vm.open(id)
            runCurrent()
            vm.setEditing(true)
            vm.setBody("A")
            advanceTimeBy(1_100)
            assertEquals(SaveStatus.SAVING, vm.uiState.value.status)
            vm.setBody("AB")
            answer.complete(SaveResult.Conflict(theirs))
            advanceUntilIdle()

            val state = vm.uiState.value
            assertEquals(NoteConflict(theirs), state.conflict)
            assertEquals("AB", state.body)
            assertEquals(false, state.editing)
            assertEquals(SaveStatus.UNSAVED, state.status)
            // The autosave of "AB" did not go over the refused version.
            coVerify(exactly = 1) { repo.save(any(), any()) }

            vm.keepMine()
            advanceUntilIdle()
            coVerify(exactly = 1) { repo.save(theirs, NoteDraft("", "AB")) }
            assertEquals(SaveStatus.SAVED, vm.uiState.value.status)
        }

    @Test
    fun `use theirs and keep mine do nothing without a conflict`() =
        test { vm ->
            vm.open(id)
            runCurrent()
            vm.useTheirs()
            vm.keepMine()
            advanceUntilIdle()
            assertEquals(loaded, vm.uiState.value.note)
            assertEquals(false, vm.uiState.value.closed)
            coVerify(exactly = 0) { repo.save(any(), any()) }
        }

    @Test
    fun `leaving the editor saves at once, without waiting for the autosave`() =
        test { vm ->
            coEvery { repo.save(loaded, NoteDraft("", "Now")) } returns saved("Now", 4)
            vm.open(id)
            runCurrent()
            vm.setEditing(true)
            vm.setBody("Now")
            vm.setEditing(false)
            runCurrent()

            coVerify(exactly = 1) { repo.save(loaded, NoteDraft("", "Now")) }
            assertEquals(SaveStatus.SAVED, vm.uiState.value.status)
            advanceUntilIdle()
            coVerify(exactly = 1) { repo.save(any(), any()) }
        }

    @Test
    fun `a refused save shows failed and can be saved again`() =
        test { vm ->
            coEvery { repo.save(loaded, any()) } returns SaveResult.Failed(400) andThen saved("Mine", 4)
            vm.open(id)
            runCurrent()
            vm.setBody("Mine")
            advanceUntilIdle()
            assertEquals(SaveStatus.FAILED, vm.uiState.value.status)
            assertEquals("Mine", vm.uiState.value.body)
            assertNull(vm.uiState.value.conflict)

            vm.saveNow()
            advanceUntilIdle()
            assertEquals(SaveStatus.SAVED, vm.uiState.value.status)
        }

    @Test
    fun `typing back to the saved text needs no save`() =
        test { vm ->
            vm.open(id)
            runCurrent()
            vm.setBody("Shopping!")
            assertEquals(SaveStatus.UNSAVED, vm.uiState.value.status)
            vm.setBody("Shopping")
            assertEquals(SaveStatus.SAVED, vm.uiState.value.status)
            advanceUntilIdle()
            coVerify(exactly = 0) { repo.save(any(), any()) }
        }

    @Test
    fun `a title with spaces around it counts as saved once the trimmed title is stored`() =
        test { vm ->
            coEvery { repo.save(loaded, NoteDraft("Trip ", "Shopping")) } returns SaveResult.Saved(loaded.copy(title = "Trip", version = 4))
            vm.open(id)
            runCurrent()
            vm.setTitle("Trip ")
            advanceUntilIdle()
            assertEquals(SaveStatus.SAVED, vm.uiState.value.status)
            assertEquals("Trip ", vm.uiState.value.title)
            coVerify(exactly = 1) { repo.save(any(), any()) }
        }

    @Test
    fun `nothing can be edited while the note loads, and only the first open counts`() =
        test { vm ->
            val answer = CompletableDeferred<NotesResult<Note>>()
            coEvery { repo.get(id) } coAnswers { answer.await() }
            vm.open(id)
            runCurrent()
            assertTrue(vm.uiState.value.loading)
            vm.setEditing(true)
            vm.setBody("too early")
            vm.open(null)
            vm.retryLoad(id)
            assertEquals(false, vm.uiState.value.editing)
            assertEquals("", vm.uiState.value.body)

            answer.complete(NotesResult.Ok(loaded))
            advanceUntilIdle()
            assertEquals("Shopping", vm.uiState.value.body)
            coVerify(exactly = 1) { repo.get(id) }
            coVerify(exactly = 0) { repo.save(any(), any()) }
        }

    @Test
    fun `a loaded note is not loaded again by retry, a refused load says so`() =
        test { vm ->
            vm.open(id)
            runCurrent()
            vm.retryLoad(id)
            runCurrent()
            coVerify(exactly = 1) { repo.get(id) }

            val other = NoteEditorViewModel(repo, images)
            val missing = UUID.fromString("00000000-0000-7000-8000-000000000404")
            coEvery { repo.get(missing) } returns NotesResult.Failed(404)
            other.open(missing)
            runCurrent()
            assertEquals(NoteEditorMessage.LOAD_FAILED, other.uiState.value.message)
            assertNull(other.uiState.value.note)
            assertEquals(false, other.uiState.value.loading)
        }

    @Test
    fun `a photo without a connection shows a message and leaves the text`() =
        test { vm ->
            val photo = NotePhoto(byteArrayOf(1), "image/jpeg", "p.jpg")
            coEvery { repo.uploadImage(id, photo) } returns NotesResult.Offline
            vm.open(id)
            runCurrent()
            vm.addPhoto(photo)
            advanceUntilIdle()
            assertEquals(NoteEditorMessage.PHOTO_OFFLINE, vm.uiState.value.message)
            assertEquals(false, vm.uiState.value.uploading)
            assertEquals("Shopping", vm.uiState.value.body)
            vm.dismissMessage()
            assertNull(vm.uiState.value.message)
        }

    @Test
    fun `a new note that cannot be created gets no photo upload`() =
        test { vm ->
            val photo = NotePhoto(byteArrayOf(1), "image/jpeg", "p.jpg")
            coEvery { repo.save(null, any()) } returns SaveResult.Offline
            vm.open(null)
            vm.addPhoto(photo)
            advanceUntilIdle()
            coVerify(exactly = 0) { repo.uploadImage(any(), any()) }
            assertTrue(vm.uiState.value.message != null)
            assertEquals(false, vm.uiState.value.uploading)
            assertNull(vm.uiState.value.note)
        }

    @Test
    fun `a second photo while one uploads is ignored`() =
        test { vm ->
            val first = NotePhoto(byteArrayOf(1), "image/jpeg", "a.jpg")
            val second = NotePhoto(byteArrayOf(2), "image/jpeg", "b.jpg")
            val answer = CompletableDeferred<NotesResult<String>>()
            coEvery { repo.uploadImage(id, first) } coAnswers { answer.await() }
            coEvery { repo.save(loaded, any()) } returns saved("Shopping\n![](a)\n", 4)
            vm.open(id)
            runCurrent()
            vm.addPhoto(first)
            runCurrent()
            assertTrue(vm.uiState.value.uploading)
            vm.addPhoto(second)
            answer.complete(NotesResult.Ok("![](a)"))
            advanceUntilIdle()

            coVerify(exactly = 0) { repo.uploadImage(any(), second) }
            assertEquals("Shopping\n![](a)\n", vm.uiState.value.body)
        }

    @Test
    fun `no photo can be added while a conflict waits for a decision`() =
        test { vm ->
            val photo = NotePhoto(byteArrayOf(1), "image/jpeg", "p.jpg")
            coEvery { repo.save(loaded, any()) } returns SaveResult.Conflict(loaded.copy(version = 9))
            vm.open(id)
            runCurrent()
            vm.setBody("Mine")
            advanceUntilIdle()
            vm.addPhoto(photo)
            advanceUntilIdle()
            coVerify(exactly = 0) { repo.uploadImage(any(), any()) }
            assertEquals(false, vm.uiState.value.uploading)
        }

    @Test
    fun `typing and a photo on a new note create it only once`() =
        test { vm ->
            val photo = NotePhoto(byteArrayOf(1), "image/jpeg", "p.jpg")
            val created = loaded.copy(bodyMd = "Hi", version = 1)
            coEvery { repo.save(null, any()) } returns SaveResult.Saved(created)
            coEvery { repo.uploadImage(id, photo) } returns NotesResult.Ok("![](x)")
            coEvery { repo.save(created, NoteDraft("", "Hi\n![](x)\n")) } returns saved("Hi\n![](x)\n", 2)
            vm.open(null)
            vm.setBody("Hi")
            vm.addPhoto(photo)
            advanceUntilIdle()

            coVerify(exactly = 1) { repo.save(null, any()) }
            assertEquals("Hi\n![](x)\n", vm.uiState.value.body)
            assertEquals(
                2L,
                vm.uiState.value.note!!
                    .version,
            )
            assertEquals(SaveStatus.SAVED, vm.uiState.value.status)
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

    @Test
    fun `after a failed load nothing can be edited, saved or uploaded until the retry loads the note`() =
        test { vm ->
            val photo = NotePhoto(byteArrayOf(1), "image/jpeg", "p.jpg")
            coEvery { repo.get(id) } returns NotesResult.Offline andThen NotesResult.Ok(loaded)
            vm.open(id)
            runCurrent()
            assertEquals(false, vm.uiState.value.editable)

            vm.setEditing(true)
            vm.setTitle("Stray")
            vm.setBody("Stray")
            vm.addPhoto(photo)
            vm.saveNow()
            advanceUntilIdle()
            coVerify(exactly = 0) { repo.save(any(), any()) }
            coVerify(exactly = 0) { repo.uploadImage(any(), any()) }
            assertEquals(false, vm.uiState.value.editing)
            assertEquals("", vm.uiState.value.body)
            assertNull(vm.uiState.value.note)
            assertEquals(NoteEditorMessage.LOAD_OFFLINE, vm.uiState.value.message)

            vm.retryLoad(id)
            advanceUntilIdle()
            assertEquals(loaded, vm.uiState.value.note)
            assertTrue(vm.uiState.value.editable)
            coVerify(exactly = 2) { repo.get(id) }
        }

    @Test
    fun `a note opened again is loaded again, but not on the same opening`() =
        test { vm ->
            val changed = loaded.copy(bodyMd = "Changed on the web", version = 8)
            coEvery { repo.get(id) } returns NotesResult.Ok(loaded) andThen NotesResult.Ok(changed)
            vm.open(id, "first")
            runCurrent()
            vm.setEditing(true)
            // The same opening again (a configuration change): nothing happens.
            vm.open(id, "first")
            runCurrent()
            coVerify(exactly = 1) { repo.get(id) }
            assertTrue(vm.uiState.value.editing)

            vm.open(id, "second")
            runCurrent()
            coVerify(exactly = 2) { repo.get(id) }
            assertEquals(changed, vm.uiState.value.note)
            assertEquals("Changed on the web", vm.uiState.value.body)
            assertEquals(false, vm.uiState.value.editing)
        }

    @Test
    fun `a note opened again while its text is not saved keeps that text`() =
        test { vm ->
            coEvery { repo.save(loaded, any()) } returns SaveResult.Offline
            vm.open(id, "first")
            runCurrent()
            vm.setBody("Mine")
            advanceUntilIdle()
            assertEquals(SaveStatus.OFFLINE, vm.uiState.value.status)

            vm.open(id, "second")
            advanceUntilIdle()
            coVerify(exactly = 1) { repo.get(id) }
            assertEquals("Mine", vm.uiState.value.body)
        }

    @Test
    fun `a note deleted elsewhere and discarded is loaded again when opened again`() =
        test { vm ->
            coEvery { repo.save(loaded, any()) } returns SaveResult.Conflict(null)
            vm.open(id, "first")
            runCurrent()
            vm.setBody("Mine")
            advanceUntilIdle()
            vm.useTheirs()
            assertTrue(vm.uiState.value.closed)

            coEvery { repo.get(id) } returns NotesResult.Failed(404)
            vm.open(id, "second")
            runCurrent()
            assertEquals(false, vm.uiState.value.closed)
            assertNull(vm.uiState.value.note)
            assertEquals(NoteEditorMessage.LOAD_FAILED, vm.uiState.value.message)
            assertEquals(false, vm.uiState.value.editable)
        }

    @Test
    fun `save now returns a job that ends once the text is saved`() =
        test { vm ->
            val answer = CompletableDeferred<SaveResult>()
            coEvery { repo.save(loaded, NoteDraft("", "Now")) } coAnswers { answer.await() }
            vm.open(id)
            runCurrent()
            vm.setBody("Now")
            val job = vm.saveNow()
            runCurrent()
            assertEquals(false, job.isCompleted)
            answer.complete(saved("Now", 4))
            runCurrent()
            assertTrue(job.isCompleted)
            assertEquals(SaveStatus.SAVED, vm.uiState.value.status)
            advanceUntilIdle()
            coVerify(exactly = 1) { repo.save(any(), any()) }
        }

    @Test
    fun `a photo uploaded while a conflict came up is inserted once the user keeps their text`() =
        test { vm ->
            val photo = NotePhoto(byteArrayOf(1), "image/jpeg", "p.jpg")
            val theirs = loaded.copy(bodyMd = "Theirs", version = 7)
            val upload = CompletableDeferred<NotesResult<String>>()
            coEvery { repo.uploadImage(id, photo) } coAnswers { upload.await() }
            coEvery { repo.save(loaded, any()) } returns SaveResult.Conflict(theirs)
            coEvery { repo.save(theirs, NoteDraft("", "![](p)\nMine")) } returns saved("![](p)\nMine", 8)
            vm.open(id)
            runCurrent()
            vm.addPhoto(photo, at = 0)
            vm.setBody("Mine")
            advanceTimeBy(1_100)
            runCurrent()
            assertEquals(NoteConflict(theirs), vm.uiState.value.conflict)

            upload.complete(NotesResult.Ok("![](p)"))
            runCurrent()
            assertEquals(false, vm.uiState.value.uploading)
            assertEquals("Mine", vm.uiState.value.body)

            vm.keepMine()
            advanceUntilIdle()
            assertEquals("![](p)\nMine", vm.uiState.value.body)
            coVerify(exactly = 1) { repo.save(theirs, NoteDraft("", "![](p)\nMine")) }
            assertEquals(SaveStatus.SAVED, vm.uiState.value.status)
        }

    @Test
    fun `a photo uploaded while a conflict came up is added to the other version when the user takes it`() =
        test { vm ->
            val photo = NotePhoto(byteArrayOf(1), "image/jpeg", "p.jpg")
            val theirs = loaded.copy(bodyMd = "Theirs", version = 7)
            val upload = CompletableDeferred<NotesResult<String>>()
            coEvery { repo.uploadImage(id, photo) } coAnswers { upload.await() }
            coEvery { repo.save(loaded, any()) } returns SaveResult.Conflict(theirs)
            coEvery { repo.save(theirs, NoteDraft("", "Theirs\n![](p)\n")) } returns saved("Theirs\n![](p)\n", 8)
            vm.open(id)
            runCurrent()
            vm.addPhoto(photo, at = 0)
            vm.setBody("Mine")
            advanceTimeBy(1_100)
            runCurrent()
            upload.complete(NotesResult.Ok("![](p)"))
            runCurrent()

            vm.useTheirs()
            advanceUntilIdle()
            assertEquals("Theirs\n![](p)\n", vm.uiState.value.body)
            coVerify(exactly = 1) { repo.save(theirs, NoteDraft("", "Theirs\n![](p)\n")) }
        }

    @Test
    fun `a photo uploaded while the note was deleted elsewhere is dropped when the user discards, and not brought back on reopen`() =
        test { vm ->
            val photo = NotePhoto(byteArrayOf(1), "image/jpeg", "p.jpg")
            val upload = CompletableDeferred<NotesResult<String>>()
            coEvery { repo.uploadImage(id, photo) } coAnswers { upload.await() }
            coEvery { repo.save(loaded, NoteDraft("", "Mine")) } returns SaveResult.Conflict(null)
            coEvery { repo.save(loaded, NoteDraft("", "Shopping!")) } returns saved("Shopping!", 4)
            vm.open(id, "first")
            runCurrent()
            vm.addPhoto(photo, at = 0)
            vm.setBody("Mine")
            advanceTimeBy(1_100)
            runCurrent()
            assertEquals(NoteConflict(null), vm.uiState.value.conflict)
            upload.complete(NotesResult.Ok("![](p)"))
            runCurrent()

            vm.useTheirs()
            advanceUntilIdle()
            assertTrue(vm.uiState.value.closed)

            // The note exists again (restored from the Trash): opening it shows the server text without the photo.
            vm.open(id, "second")
            advanceUntilIdle()
            assertEquals("Shopping", vm.uiState.value.body)
            assertEquals(SaveStatus.SAVED, vm.uiState.value.status)
            vm.setEditing(true)
            vm.setBody("Shopping!")
            advanceUntilIdle()
            coVerify(exactly = 0) { repo.save(any(), match { "![](p)" in it.bodyMd }) }
        }

    @Test
    fun `a photo uploaded while the note was deleted elsewhere goes into the new note when the user keeps their text`() =
        test { vm ->
            val photo = NotePhoto(byteArrayOf(1), "image/jpeg", "p.jpg")
            val upload = CompletableDeferred<NotesResult<String>>()
            val created = loaded.copy(id = UUID.fromString("00000000-0000-7000-8000-000000000077"), bodyMd = "Mine\n![](p)\n", version = 1)
            coEvery { repo.uploadImage(id, photo) } coAnswers { upload.await() }
            coEvery { repo.save(loaded, any()) } returns SaveResult.Conflict(null)
            coEvery { repo.save(null, NoteDraft("", "Mine\n![](p)\n")) } returns SaveResult.Saved(created)
            vm.open(id)
            runCurrent()
            vm.addPhoto(photo)
            vm.setBody("Mine")
            advanceTimeBy(1_100)
            runCurrent()
            upload.complete(NotesResult.Ok("![](p)"))
            runCurrent()

            vm.keepMine()
            advanceUntilIdle()
            coVerify(exactly = 1) { repo.save(null, NoteDraft("", "Mine\n![](p)\n")) }
            assertEquals(created, vm.uiState.value.note)
            assertEquals(SaveStatus.SAVED, vm.uiState.value.status)
        }

    @Test
    fun `a note opened again while a conflict waits keeps the conflict and the local text`() =
        test { vm ->
            val theirs = loaded.copy(bodyMd = "Theirs", version = 7)
            coEvery { repo.save(loaded, any()) } returns SaveResult.Conflict(theirs)
            vm.open(id, "first")
            runCurrent()
            vm.setBody("Mine")
            advanceUntilIdle()

            vm.open(id, "second")
            advanceUntilIdle()
            coVerify(exactly = 1) { repo.get(id) }
            assertEquals(NoteConflict(theirs), vm.uiState.value.conflict)
            assertEquals("Mine", vm.uiState.value.body)
        }

    @Test
    fun `a note opened again while a photo uploads keeps it, and the photo is still inserted`() =
        test { vm ->
            val photo = NotePhoto(byteArrayOf(1), "image/jpeg", "p.jpg")
            val upload = CompletableDeferred<NotesResult<String>>()
            coEvery { repo.uploadImage(id, photo) } coAnswers { upload.await() }
            coEvery { repo.save(loaded, NoteDraft("", "Shopping\n![](p)\n")) } returns saved("Shopping\n![](p)\n", 4)
            vm.open(id, "first")
            runCurrent()
            vm.addPhoto(photo)
            runCurrent()
            assertTrue(vm.uiState.value.uploading)

            vm.open(id, "second")
            runCurrent()
            coVerify(exactly = 1) { repo.get(id) }
            upload.complete(NotesResult.Ok("![](p)"))
            advanceUntilIdle()
            assertEquals("Shopping\n![](p)\n", vm.uiState.value.body)
            assertEquals(SaveStatus.SAVED, vm.uiState.value.status)
        }

    @Test
    fun `a note opened again while its save runs is not reloaded over the save`() =
        test { vm ->
            val answer = CompletableDeferred<SaveResult>()
            coEvery { repo.save(loaded, NoteDraft("", "Mine")) } coAnswers { answer.await() }
            vm.open(id, "first")
            runCurrent()
            vm.setBody("Mine")
            vm.saveNow()
            runCurrent()
            assertEquals(SaveStatus.SAVING, vm.uiState.value.status)

            vm.open(id, "second")
            runCurrent()
            answer.complete(saved("Mine", 4))
            advanceUntilIdle()
            coVerify(exactly = 1) { repo.get(id) }
            assertEquals("Mine", vm.uiState.value.body)
            assertEquals(
                4L,
                vm.uiState.value.note!!
                    .version,
            )
        }

    @Test
    fun `a note opened again while its first load runs is loaded once`() =
        test { vm ->
            val answer = CompletableDeferred<NotesResult<Note>>()
            coEvery { repo.get(id) } coAnswers { answer.await() }
            vm.open(id, "first")
            runCurrent()
            vm.open(id, "second")
            runCurrent()
            answer.complete(NotesResult.Ok(loaded))
            advanceUntilIdle()
            coVerify(exactly = 1) { repo.get(id) }
            assertEquals("Shopping", vm.uiState.value.body)
            assertTrue(vm.uiState.value.editable)
        }

    @Test
    fun `a note opened again after a failed load is loaded again`() =
        test { vm ->
            coEvery { repo.get(id) } returns NotesResult.Offline andThen NotesResult.Ok(loaded)
            vm.open(id, "first")
            runCurrent()
            assertEquals(false, vm.uiState.value.editable)

            vm.open(id, "second")
            runCurrent()
            coVerify(exactly = 2) { repo.get(id) }
            assertEquals(loaded, vm.uiState.value.note)
            assertNull(vm.uiState.value.message)
            assertTrue(vm.uiState.value.editable)
        }
}
