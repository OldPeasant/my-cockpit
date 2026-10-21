package ch.sonensei.cockpit.notes

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.UUID

/**
 * Editors outlive nothing they should not: one closed with everything saved is dropped, one with unsaved text is kept,
 * saved again and reachable, also for a new note; clearing an editor (the Notes screen left by any way) saves what it
 * still holds and does not cancel a running save.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NoteEditorsTest {
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

    private fun test(block: suspend TestScope.() -> Unit) = runTest(dispatcher) { block() }

    /** An editor in its own store, as [NoteEditors] keeps it, so the test can clear it. */
    private fun editorInStore(): Pair<ViewModelStore, NoteEditorViewModel> {
        val store = ViewModelStore()
        val factory = viewModelFactory { initializer { NoteEditorViewModel(repo, images) } }
        return store to ViewModelProvider(store, factory)[NoteEditorViewModel::class.java]
    }

    private class Holder(
        val editors: NoteEditors,
        val store: ViewModelStore,
    )

    /** The Notes screen's editors, in a store standing for the Notes back stack entry. */
    private fun editors(): Holder {
        val store = ViewModelStore()
        val factory = viewModelFactory { initializer { NoteEditors(repo, images, NoteSaveScope()) } }
        return Holder(ViewModelProvider(store, factory)[NoteEditors::class.java], store)
    }

    @Test
    fun `an editor cleared before its autosave saves the text at once`() =
        test {
            coEvery { repo.save(loaded, NoteDraft("", "Typed just before leaving")) } returns
                SaveResult.Saved(loaded.copy(bodyMd = "Typed just before leaving", version = 4))
            val (store, vm) = editorInStore()
            vm.open(id)
            runCurrent()
            vm.setBody("Typed just before leaving")
            runCurrent()

            store.clear()
            advanceUntilIdle()
            coVerify(exactly = 1) { repo.save(loaded, NoteDraft("", "Typed just before leaving")) }
            assertEquals(SaveStatus.SAVED, vm.uiState.value.status)
        }

    @Test
    fun `an editor cleared while its save runs lets that save finish`() =
        test {
            val answer = CompletableDeferred<SaveResult>()
            coEvery { repo.save(loaded, NoteDraft("", "Mine")) } coAnswers { answer.await() }
            val (store, vm) = editorInStore()
            vm.open(id)
            runCurrent()
            vm.setBody("Mine")
            val saving = vm.saveNow()
            runCurrent()

            store.clear()
            runCurrent()
            assertFalse(saving.isCancelled)
            answer.complete(SaveResult.Saved(loaded.copy(bodyMd = "Mine", version = 4)))
            advanceUntilIdle()
            assertTrue(saving.isCompleted && !saving.isCancelled)
            assertEquals(SaveStatus.SAVED, vm.uiState.value.status)
            coVerify(exactly = 1) { repo.save(any(), any()) }
        }

    @Test
    fun `an editor cleared while a photo creates its new note lets that create finish, so the note is created once`() =
        test {
            val created = loaded.copy(bodyMd = "Idea", version = 1)
            val answer = CompletableDeferred<SaveResult>()
            coEvery { repo.save(null, NoteDraft("", "Idea")) } coAnswers { answer.await() }
            val (store, vm) = editorInStore()
            vm.open(null)
            vm.setBody("Idea")
            vm.addPhoto(NotePhoto(byteArrayOf(1), "image/jpeg", "p.jpg"))
            runCurrent()
            coVerify(exactly = 1) { repo.save(null, NoteDraft("", "Idea")) }

            // Left (e.g. by the shell's back arrow) while the create runs: the create is not cancelled.
            store.clear()
            runCurrent()
            answer.complete(SaveResult.Saved(created))
            advanceUntilIdle()
            assertEquals(created, vm.uiState.value.note)
            assertEquals(SaveStatus.SAVED, vm.uiState.value.status)
            coVerify(exactly = 1) { repo.save(any(), any()) }
            coVerify(exactly = 0) { repo.uploadImage(any(), any()) }
        }

    @Test
    fun `an editor cleared during a conflict or before its note loaded saves nothing`() =
        test {
            coEvery { repo.get(id) } returns NotesResult.Offline
            val (store, vm) = editorInStore()
            vm.open(id)
            runCurrent()
            store.clear()
            advanceUntilIdle()
            coVerify(exactly = 0) { repo.save(any(), any()) }
        }

    @Test
    fun `an editor closed with everything saved is dropped, so editors do not pile up`() =
        test {
            val holder = editors()
            val first = holder.editors.editor(id.toString())
            first.open(id, "a")
            runCurrent()
            assertSame(first, holder.editors.editor(id.toString()))

            holder.editors.settle()
            val again = holder.editors.editor(id.toString())
            assertNotSame(first, again)

            // A new note left empty is dropped as well.
            holder.editors.editor("new:x").open(null, "new:x")
            holder.editors.settle()
            assertNull(holder.editors.unsentNewNote())
            coVerify(exactly = 0) { repo.save(any(), any()) }
        }

    @Test
    fun `an editor left with unsaved text is kept, saved again when the list shows, then dropped`() =
        test {
            coEvery { repo.save(loaded, NoteDraft("", "Mine")) } returns
                SaveResult.Offline andThen SaveResult.Saved(loaded.copy(bodyMd = "Mine", version = 4))
            val holder = editors()
            val vm = holder.editors.editor(id.toString())
            vm.open(id, "a")
            runCurrent()
            vm.setBody("Mine")
            vm.saveNow()
            advanceUntilIdle()
            assertEquals(SaveStatus.OFFLINE, vm.uiState.value.status)

            // The list is shown: the save is tried again, and once it went through, the editor is dropped.
            holder.editors.settle()
            coVerify(exactly = 2) { repo.save(loaded, NoteDraft("", "Mine")) }
            assertEquals(SaveStatus.SAVED, vm.uiState.value.status)
            assertNotSame(vm, holder.editors.editor(id.toString()))
        }

    @Test
    fun `a new note's unsaved text is reached again by opening the note it was saved as`() =
        test {
            val created = loaded.copy(bodyMd = "First", version = 1)
            coEvery { repo.save(null, NoteDraft("", "First")) } returns SaveResult.Saved(created)
            coEvery { repo.save(created, NoteDraft("", "First and more")) } returns SaveResult.Offline
            val holder = editors()
            val vm = holder.editors.editor("new:x")
            vm.open(null, "new:x")
            vm.setBody("First")
            vm.saveNow()
            advanceUntilIdle()
            vm.setBody("First and more")
            vm.saveNow()
            advanceUntilIdle()
            assertEquals(SaveStatus.OFFLINE, vm.uiState.value.status)

            // Left offline: kept, and opening the note from the list reaches the same text.
            holder.editors.settle()
            val reopened = holder.editors.editor(id.toString())
            assertSame(vm, reopened)
            reopened.open(id, "$id#2")
            advanceUntilIdle()
            assertEquals("First and more", reopened.uiState.value.body)
            coVerify(exactly = 0) { repo.get(any()) }
        }

    @Test
    fun `a new note that could not be created is opened again by New note`() =
        test {
            coEvery { repo.save(null, NoteDraft("", "Offline idea")) } returns SaveResult.Offline
            val holder = editors()
            val vm = holder.editors.editor("new:x")
            vm.open(null, "new:x")
            vm.setBody("Offline idea")
            vm.saveNow()
            advanceUntilIdle()

            holder.editors.settle()
            assertEquals("new:x", holder.editors.unsentNewNote())
            assertSame(vm, holder.editors.editor("new:x"))
            assertEquals("Offline idea", vm.uiState.value.body)
        }

    @Test
    fun `leaving the Notes screen saves what every editor still holds`() =
        test {
            coEvery { repo.save(loaded, NoteDraft("", "Unsaved")) } returns
                SaveResult.Saved(loaded.copy(bodyMd = "Unsaved", version = 4))
            coEvery { repo.save(null, NoteDraft("", "New")) } returns
                SaveResult.Saved(loaded.copy(id = UUID.randomUUID(), bodyMd = "New", version = 1))
            val holder = editors()
            val existing = holder.editors.editor(id.toString())
            existing.open(id, "a")
            runCurrent()
            existing.setBody("Unsaved")
            val new = holder.editors.editor("new:x")
            new.open(null, "new:x")
            new.setBody("New")
            runCurrent()

            // The shell's back arrow pops the Notes entry: its store is cleared before any autosave ran.
            holder.store.clear()
            advanceUntilIdle()
            coVerify(exactly = 1) { repo.save(loaded, NoteDraft("", "Unsaved")) }
            coVerify(exactly = 1) { repo.save(null, NoteDraft("", "New")) }
        }

    @Test
    fun `an editor cleared while a conflict waits saves nothing over the other version`() =
        test {
            val theirs = loaded.copy(bodyMd = "Changed on the laptop", version = 4)
            coEvery { repo.save(loaded, NoteDraft("", "Mine")) } returns SaveResult.Conflict(theirs)
            val (store, vm) = editorInStore()
            vm.open(id)
            runCurrent()
            vm.setBody("Mine")
            vm.saveNow()
            advanceUntilIdle()
            assertEquals(NoteConflict(theirs), vm.uiState.value.conflict)

            store.clear()
            advanceUntilIdle()
            coVerify(exactly = 1) { repo.save(any(), any()) }
        }

    @Test
    fun `the list showing keeps an editor whose conflict waits, saves nothing, and reopening reaches the conflict`() =
        test {
            val theirs = loaded.copy(bodyMd = "Changed on the laptop", version = 4)
            coEvery { repo.save(loaded, NoteDraft("", "Mine")) } returns SaveResult.Conflict(theirs)
            val holder = editors()
            val vm = holder.editors.editor(id.toString())
            vm.open(id, "a")
            runCurrent()
            vm.setBody("Mine")
            vm.saveNow()
            advanceUntilIdle()

            holder.editors.settle()
            advanceUntilIdle()
            coVerify(exactly = 1) { repo.save(any(), any()) }
            assertFalse(vm.settled)
            val reopened = holder.editors.editor(id.toString())
            assertSame(vm, reopened)
            reopened.open(id, "b")
            advanceUntilIdle()
            assertEquals(NoteConflict(theirs), reopened.uiState.value.conflict)
            assertEquals("Mine", reopened.uiState.value.body)
            coVerify(exactly = 1) { repo.get(id) }
        }

    @Test
    fun `a photo still uploading when the list shows keeps its editor, and its image is saved once it arrives`() =
        test {
            val photo = NotePhoto(byteArrayOf(1), "image/jpeg", "p.jpg")
            val upload = CompletableDeferred<NotesResult<String>>()
            coEvery { repo.uploadImage(id, photo) } coAnswers { upload.await() }
            coEvery { repo.save(loaded, NoteDraft("", "Shopping\n![](p)\n")) } returns
                SaveResult.Saved(loaded.copy(bodyMd = "Shopping\n![](p)\n", version = 4))
            val holder = editors()
            val vm = holder.editors.editor(id.toString())
            vm.open(id, "a")
            runCurrent()
            vm.addPhoto(photo)
            runCurrent()
            assertTrue(vm.uiState.value.uploading)

            // The editor is left while the photo uploads: the list must not drop it (that would cancel the upload).
            holder.editors.settle()
            assertSame(vm, holder.editors.editor(id.toString()))
            upload.complete(NotesResult.Ok("![](p)"))
            advanceUntilIdle()
            coVerify(exactly = 1) { repo.save(loaded, NoteDraft("", "Shopping\n![](p)\n")) }
            assertEquals(SaveStatus.SAVED, vm.uiState.value.status)

            // Everything is on the server now: the next time the list shows, the editor is dropped.
            holder.editors.settle()
            assertNotSame(vm, holder.editors.editor(id.toString()))
        }

    @Test
    fun `a new note that was created but not saved since is reached from the list, not offered again by New note`() =
        test {
            val created = loaded.copy(bodyMd = "First", version = 1)
            coEvery { repo.save(null, NoteDraft("", "First")) } returns SaveResult.Saved(created)
            coEvery { repo.save(created, NoteDraft("", "First draft")) } returns SaveResult.Failed(500)
            val holder = editors()
            val vm = holder.editors.editor("new:x")
            vm.open(null, "new:x")
            vm.setBody("First")
            vm.saveNow()
            advanceUntilIdle()
            vm.setBody("First draft")
            vm.saveNow()
            advanceUntilIdle()
            assertEquals(SaveStatus.FAILED, vm.uiState.value.status)

            holder.editors.settle()
            assertNull(holder.editors.unsentNewNote())
            assertSame(vm, holder.editors.editor(id.toString()))
        }
}
