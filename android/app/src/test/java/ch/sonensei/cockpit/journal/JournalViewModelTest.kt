package ch.sonensei.cockpit.journal

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import ch.sonensei.cockpit.notes.NoteImages
import ch.sonensei.cockpit.notes.NotePhoto
import ch.sonensei.cockpit.notes.SaveStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
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
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class JournalViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val day = LocalDate.of(2026, 10, 22)
    private val clock = Clock.fixed(Instant.parse("2026-10-22T10:00:00Z"), ZoneId.of("Europe/Zurich"))
    private val id = UUID.fromString("00000000-0000-7000-8000-000000000001")
    private val loaded = JournalEntry(id, day, "Morning run", 4, listOf("sport"), Instant.EPOCH, 3)
    private val repo = mockk<JournalRepository>()
    private val images = mockk<NoteImages> { every { loader } returns mockk() }
    private val photo = NotePhoto(byteArrayOf(1), "image/jpeg", "lake.jpg")

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        coEvery { repo.get(day) } returns JournalResult.Ok(loaded)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    /** Runs [block] with a view model created after [stub] set up the repository. */
    private fun test(
        stub: () -> Unit = {},
        block: suspend TestScope.(JournalViewModel) -> Unit,
    ) = runTest(dispatcher) {
        stub()
        val vm = JournalViewModel(repo, images, CoroutineScope(dispatcher), clock)
        block(vm)
    }

    private fun saved(
        body: String,
        version: Long,
    ) = JournalSaveResult.Saved(loaded.copy(bodyMd = body, version = version))

    @Test
    fun `opens today's entry in the rendered view`() =
        test { vm ->
            runCurrent()
            val state = vm.uiState.value
            assertEquals(day, state.date)
            assertEquals(loaded, state.entry)
            assertEquals("Morning run", state.body)
            assertFalse(state.editing)
            assertTrue(state.editable)
            assertEquals(SaveStatus.SAVED, state.status)
        }

    @Test
    fun `a day without an entry opens in the editor`() =
        test({ coEvery { repo.get(day) } returns JournalResult.Ok(null) }) { vm ->
            runCurrent()
            assertTrue(vm.uiState.value.editing)
            assertNull(vm.uiState.value.entry)
        }

    @Test
    fun `autosaves a second after the last change, over the saved version`() =
        test { vm ->
            coEvery { repo.save(day, loaded, "Morning run, 5 km") } returns saved("Morning run, 5 km", 4)
            runCurrent()
            vm.setEditing(true)
            vm.setBody("Morning run, 5")
            advanceTimeBy(500)
            vm.setBody("Morning run, 5 km")
            assertEquals(SaveStatus.UNSAVED, vm.uiState.value.status)
            advanceTimeBy(900)
            coVerify(exactly = 0) { repo.save(any(), any(), any()) }
            advanceUntilIdle()

            coVerify(exactly = 1) { repo.save(any(), any(), any()) }
            assertEquals(
                4L,
                vm.uiState.value.entry!!
                    .version,
            )
            assertEquals(SaveStatus.SAVED, vm.uiState.value.status)
        }

    @Test
    fun `the next save goes over the version the previous one returned`() =
        test { vm ->
            coEvery { repo.save(day, loaded, "A") } returns saved("A", 4)
            coEvery { repo.save(day, loaded.copy(bodyMd = "A", version = 4), "AB") } returns saved("AB", 5)
            runCurrent()
            vm.setBody("A")
            advanceUntilIdle()
            vm.setBody("AB")
            advanceUntilIdle()

            assertEquals(
                5L,
                vm.uiState.value.entry!!
                    .version,
            )
            assertEquals(SaveStatus.SAVED, vm.uiState.value.status)
        }

    @Test
    fun `a new entry is only created once it has text`() =
        test({ coEvery { repo.get(day) } returns JournalResult.Ok(null) }) { vm ->
            coEvery { repo.save(day, null, "Hello") } returns saved("Hello", 1)
            runCurrent()
            vm.setEditing(false)
            advanceUntilIdle()
            coVerify(exactly = 0) { repo.save(any(), any(), any()) }

            vm.setBody("Hello")
            advanceUntilIdle()
            coVerify(exactly = 1) { repo.save(day, null, "Hello") }
            assertEquals(SaveStatus.SAVED, vm.uiState.value.status)
        }

    @Test
    fun `leaving the editor saves at once`() =
        test { vm ->
            coEvery { repo.save(day, loaded, "Morning run!") } returns saved("Morning run!", 4)
            runCurrent()
            vm.setEditing(true)
            vm.setBody("Morning run!")
            vm.setEditing(false)
            runCurrent()
            coVerify(exactly = 1) { repo.save(day, loaded, "Morning run!") }
        }

    @Test
    fun `an offline save keeps the text and can be retried`() =
        test { vm ->
            coEvery { repo.save(day, loaded, "More") } returnsMany listOf(JournalSaveResult.Offline, saved("More", 4))
            runCurrent()
            vm.setBody("More")
            advanceUntilIdle()
            assertEquals(SaveStatus.OFFLINE, vm.uiState.value.status)
            assertEquals("More", vm.uiState.value.body)

            vm.saveNow()
            advanceUntilIdle()
            assertEquals(SaveStatus.SAVED, vm.uiState.value.status)
        }

    @Test
    fun `a refused save is reported as failed`() =
        test { vm ->
            coEvery { repo.save(any(), any(), any()) } returns JournalSaveResult.Failed(400)
            runCurrent()
            vm.setBody("More")
            advanceUntilIdle()
            assertEquals(SaveStatus.FAILED, vm.uiState.value.status)
        }

    @Test
    fun `text waiting for its autosave is saved when the screen goes away`() =
        runTest(dispatcher) {
            coEvery { repo.save(day, loaded, "Unsaved") } returns saved("Unsaved", 4)
            val store = ViewModelStore()
            val vm =
                ViewModelProvider(
                    store,
                    viewModelFactory { initializer { JournalViewModel(repo, images, CoroutineScope(dispatcher), clock) } },
                )[JournalViewModel::class.java]
            runCurrent()
            vm.setBody("Unsaved")
            store.clear()
            advanceUntilIdle()
            coVerify(exactly = 1) { repo.save(day, loaded, "Unsaved") }
        }

    @Test
    fun `a conflict stops the autosave until the user keeps their text`() =
        test { vm ->
            val theirs = loaded.copy(bodyMd = "From the web", version = 7)
            coEvery { repo.save(day, loaded, "Mine") } returns JournalSaveResult.Conflict(theirs)
            coEvery { repo.save(day, theirs, "Mine") } returns saved("Mine", 8)
            runCurrent()
            vm.setEditing(true)
            vm.setBody("Mine")
            advanceUntilIdle()
            val state = vm.uiState.value
            assertEquals(JournalConflict(theirs), state.conflict)
            assertFalse(state.editable)
            assertFalse(state.editing)
            vm.setBody("Ignored")
            assertEquals("Mine", vm.uiState.value.body)

            vm.keepMine()
            advanceUntilIdle()
            coVerify(exactly = 1) { repo.save(day, theirs, "Mine") }
            assertNull(vm.uiState.value.conflict)
            assertEquals(SaveStatus.SAVED, vm.uiState.value.status)
        }

    @Test
    fun `using the other version drops the text written here`() =
        test { vm ->
            val theirs = loaded.copy(bodyMd = "From the web", version = 7)
            coEvery { repo.save(day, loaded, "Mine") } returns JournalSaveResult.Conflict(theirs)
            runCurrent()
            vm.setBody("Mine")
            advanceUntilIdle()

            vm.useTheirs()
            advanceUntilIdle()
            val state = vm.uiState.value
            assertEquals("From the web", state.body)
            assertEquals(theirs, state.entry)
            assertEquals(SaveStatus.SAVED, state.status)
            coVerify(exactly = 1) { repo.save(any(), any(), any()) }
        }

    @Test
    fun `an entry deleted elsewhere can be saved again as a new one`() =
        test { vm ->
            coEvery { repo.save(day, loaded, "Mine") } returns JournalSaveResult.Conflict(null)
            coEvery { repo.save(day, null, "Mine") } returns saved("Mine", 1)
            runCurrent()
            vm.setBody("Mine")
            advanceUntilIdle()

            vm.keepMine()
            advanceUntilIdle()
            coVerify(exactly = 1) { repo.save(day, null, "Mine") }
        }

    @Test
    fun `a failed load shows a message, blocks writing and can be retried`() =
        test({ coEvery { repo.get(day) } returnsMany listOf(JournalResult.Offline, JournalResult.Ok(loaded)) }) { vm ->
            runCurrent()
            assertEquals(JournalMessage.LOAD_OFFLINE, vm.uiState.value.message)
            assertFalse(vm.uiState.value.editable)
            vm.setBody("Lost?")
            assertEquals("", vm.uiState.value.body)

            vm.retryLoad()
            runCurrent()
            assertNull(vm.uiState.value.message)
            assertEquals("Morning run", vm.uiState.value.body)
        }

    @Test
    fun `a photo is uploaded and inserted at the cursor, then saved`() =
        test { vm ->
            val image = "![lake.jpg](cockpit-file:$id)"
            coEvery { repo.uploadImage(photo) } returns JournalResult.Ok(image)
            coEvery { repo.save(day, loaded, "Morning\n$image\n run") } returns saved("Morning\n$image\n run", 4)
            runCurrent()
            vm.addPhoto(photo, at = 7)
            assertTrue(vm.uiState.value.uploading)
            advanceUntilIdle()

            assertFalse(vm.uiState.value.uploading)
            assertEquals("Morning\n$image\n run", vm.uiState.value.body)
            assertEquals(SaveStatus.SAVED, vm.uiState.value.status)
        }

    @Test
    fun `photo problems are shown and change nothing`() =
        test { vm ->
            runCurrent()
            vm.addPhoto(null)
            assertEquals(JournalMessage.PHOTO_UNREADABLE, vm.uiState.value.message)
            vm.dismissMessage()
            assertNull(vm.uiState.value.message)

            coEvery { repo.uploadImage(photo) } returns JournalResult.Offline
            vm.addPhoto(photo)
            advanceUntilIdle()
            assertEquals(JournalMessage.PHOTO_OFFLINE, vm.uiState.value.message)

            coEvery { repo.uploadImage(photo) } returns JournalResult.Failed(413)
            vm.addPhoto(photo)
            advanceUntilIdle()
            assertEquals(JournalMessage.PHOTO_FAILED, vm.uiState.value.message)
            assertEquals("Morning run", vm.uiState.value.body)
        }

    @Test
    fun `a photo uploaded while a conflict came up is added once the user decided`() =
        test { vm ->
            val image = "![lake.jpg](cockpit-file:$id)"
            val upload = CompletableDeferred<JournalResult<String>>()
            val theirs = loaded.copy(bodyMd = "Theirs", version = 7)
            coEvery { repo.uploadImage(photo) } coAnswers { upload.await() }
            coEvery { repo.save(day, loaded, "Mine") } returns JournalSaveResult.Conflict(theirs)
            coEvery { repo.save(day, theirs, any()) } answers { saved(thirdArg(), 8) }
            runCurrent()
            vm.setBody("Mine")
            vm.addPhoto(photo, at = 4)
            advanceUntilIdle()
            assertTrue(vm.uiState.value.conflict != null)
            upload.complete(JournalResult.Ok(image))
            advanceUntilIdle()
            assertEquals("Mine", vm.uiState.value.body)

            vm.useTheirs()
            advanceUntilIdle()
            assertEquals("Theirs\n$image\n", vm.uiState.value.body)
        }
}
