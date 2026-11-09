package ch.sonensei.cockpit.notes

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.UUID

/** Editing a note's tags in the editor: loaded with the note, added and removed as edits, saved with the text. */
@OptIn(ExperimentalCoroutinesApi::class)
class NoteEditorTagsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val id = UUID.fromString("00000000-0000-7000-8000-000000000001")
    private val loaded =
        Note(id, null, "Trip", "Trip", pinned = false, tags = listOf("travel"), updatedAt = Instant.EPOCH, version = 3)
    private val repo = mockk<NotesRepository>()
    private val images = mockk<NoteImages> { every { loader } returns mockk() }
    private var tagCalls = 0
    private var known: suspend () -> NotesResult<List<NoteTag>> =
        { NotesResult.Ok(listOf(NoteTag("home", 2), NoteTag("Work", 7), NoteTag("travel", 4))) }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        coEvery { repo.get(id) } returns NotesResult.Ok(loaded)
        // Any save a test does not expect explicitly goes through with the tags sent.
        coEvery { repo.save(any(), any()) } answers {
            SaveResult.Saved(loaded.copy(tags = secondArg<NoteDraft>().tags, version = 4))
        }
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun test(block: suspend TestScope.(NoteEditorViewModel) -> Unit) =
        runTest(dispatcher) {
            val vm =
                NoteEditorViewModel(
                    repo,
                    images,
                    CoroutineScope(SupervisorJob() + dispatcher),
                    tagSource = {
                        tagCalls++
                        known()
                    },
                )
            block(vm)
        }

    private suspend fun TestScope.opened(vm: NoteEditorViewModel) {
        vm.open(id)
        runCurrent()
    }

    @Test
    fun `the note's tags load with it, and the tags in use load most used first`() =
        test { vm ->
            opened(vm)

            assertEquals(listOf("travel"), vm.uiState.value.tags)
            assertEquals(listOf("Work", "travel", "home"), vm.uiState.value.knownTags)
            assertEquals(SaveStatus.SAVED, vm.uiState.value.status)
            assertEquals(1, tagCalls)
        }

    @Test
    fun `suggestions are the tags in use not on the note yet, in any case`() =
        test { vm ->
            opened(vm)
            assertEquals(listOf("Work", "home"), vm.uiState.value.tagSuggestions)

            assertTrue(vm.addTag("work"))
            assertEquals(listOf("home"), vm.uiState.value.tagSuggestions)
        }

    @Test
    fun `an added tag is trimmed, loses a leading hash, counts as unsaved and is autosaved with the text`() =
        test { vm ->
            val draft = NoteDraft("", "Trip", listOf("travel", "packing list"))
            coEvery { repo.save(loaded, draft) } returns SaveResult.Saved(loaded.copy(tags = draft.tags, version = 4))
            opened(vm)

            assertTrue(vm.addTag("  #packing list  "))

            assertEquals(listOf("travel", "packing list"), vm.uiState.value.tags)
            assertEquals(SaveStatus.UNSAVED, vm.uiState.value.status)
            advanceTimeBy(NoteEditorViewModel.AUTOSAVE_DELAY_MS - 100)
            coVerify(exactly = 0) { repo.save(any(), any()) }
            advanceUntilIdle()
            coVerify(exactly = 1) { repo.save(loaded, draft) }
            assertEquals(SaveStatus.SAVED, vm.uiState.value.status)
            val note = vm.uiState.value.note
            assertEquals(4L, note?.version)
        }

    @Test
    fun `a tag already on the note in any case is not added again`() =
        test { vm ->
            opened(vm)

            assertFalse(vm.addTag("travel"))
            assertFalse(vm.addTag("TRAVEL"))
            assertFalse(vm.addTag("#Travel "))

            assertEquals(listOf("travel"), vm.uiState.value.tags)
            assertEquals(SaveStatus.SAVED, vm.uiState.value.status)
            advanceUntilIdle()
            coVerify(exactly = 0) { repo.save(any(), any()) }
        }

    @Test
    fun `a tag that differs from one in use only in case takes that spelling, an unknown tag keeps its own`() =
        test { vm ->
            opened(vm)

            assertTrue(vm.addTag("WORK"))
            assertTrue(vm.addTag("Errands"))

            assertEquals(listOf("travel", "Work", "Errands"), vm.uiState.value.tags)
        }

    @Test
    fun `an empty, blank or hash-only tag is not added`() =
        test { vm ->
            opened(vm)

            listOf("", "   ", "#", " # ").forEach { assertFalse("'$it' must not be added", vm.addTag(it)) }

            assertEquals(listOf("travel"), vm.uiState.value.tags)
            assertEquals(SaveStatus.SAVED, vm.uiState.value.status)
        }

    @Test
    fun `a tag of 50 characters is added, one of 51 is not`() =
        test { vm ->
            opened(vm)
            val max = "a".repeat(NoteEditorViewModel.MAX_TAG_LENGTH)

            assertFalse(vm.addTag("b".repeat(NoteEditorViewModel.MAX_TAG_LENGTH + 1)))
            assertTrue("the hash does not count", vm.addTag("#$max"))

            assertEquals(listOf("travel", max), vm.uiState.value.tags)
        }

    @Test
    fun `a note takes 50 tags at most`() =
        test { vm ->
            opened(vm)

            (2..NoteEditorViewModel.MAX_TAGS).forEach { assertTrue(vm.addTag("tag$it")) }
            assertEquals(NoteEditorViewModel.MAX_TAGS, vm.uiState.value.tags.size)

            assertFalse(vm.addTag("one too many"))
            assertEquals(NoteEditorViewModel.MAX_TAGS, vm.uiState.value.tags.size)

            vm.removeTag("tag2")
            assertTrue("a removed tag makes room again", vm.addTag("one too many"))
        }

    @Test
    fun `a removed tag is autosaved, and a tag not on the note changes nothing`() =
        test { vm ->
            val draft = NoteDraft("", "Trip", emptyList())
            coEvery { repo.save(loaded, draft) } returns SaveResult.Saved(loaded.copy(tags = emptyList(), version = 4))
            opened(vm)

            vm.removeTag("home")
            vm.removeTag("Travel")
            assertEquals(listOf("travel"), vm.uiState.value.tags)
            assertEquals(SaveStatus.SAVED, vm.uiState.value.status)

            vm.removeTag("travel")
            assertEquals(emptyList<String>(), vm.uiState.value.tags)
            assertEquals(SaveStatus.UNSAVED, vm.uiState.value.status)
            assertEquals(listOf("Work", "travel", "home"), vm.uiState.value.tagSuggestions)
            advanceUntilIdle()

            coVerify(exactly = 1) { repo.save(loaded, draft) }
            assertEquals(SaveStatus.SAVED, vm.uiState.value.status)
        }

    @Test
    fun `adding and removing a tag back needs no save`() =
        test { vm ->
            opened(vm)

            vm.addTag("home")
            vm.removeTag("home")

            assertEquals(SaveStatus.SAVED, vm.uiState.value.status)
            advanceUntilIdle()
            coVerify(exactly = 0) { repo.save(any(), any()) }
        }

    @Test
    fun `tags are saved with the text in one save`() =
        test { vm ->
            val draft = NoteDraft("", "Trip\nPassport", listOf("travel", "home"))
            coEvery { repo.save(loaded, draft) } returns
                SaveResult.Saved(loaded.copy(bodyMd = draft.bodyMd, tags = draft.tags, version = 4))
            opened(vm)

            vm.setBody("Trip\nPassport")
            vm.addTag("home")
            advanceUntilIdle()

            coVerify(exactly = 1) { repo.save(any(), any()) }
            coVerify { repo.save(loaded, draft) }
            assertEquals(SaveStatus.SAVED, vm.uiState.value.status)
        }

    @Test
    fun `the server's spelling of the saved tags is taken, so they do not count as unsaved`() =
        test { vm ->
            known = { NotesResult.Ok(emptyList()) }
            coEvery { repo.save(loaded, NoteDraft("", "Trip", listOf("travel", "Work"))) } returns
                SaveResult.Saved(loaded.copy(tags = listOf("travel", "work"), version = 4))
            opened(vm)

            vm.addTag("Work")
            advanceUntilIdle()

            assertEquals(listOf("travel", "work"), vm.uiState.value.tags)
            assertEquals(SaveStatus.SAVED, vm.uiState.value.status)
            coVerify(exactly = 1) { repo.save(any(), any()) }
        }

    @Test
    fun `a tag added while a save runs is kept and saved next, over the version that save returned`() =
        test { vm ->
            val first = CompletableDeferred<SaveResult>()
            val v4 = loaded.copy(tags = listOf("travel", "home"), version = 4)
            coEvery { repo.save(loaded, NoteDraft("", "Trip", listOf("travel", "home"))) } coAnswers { first.await() }
            coEvery { repo.save(v4, NoteDraft("", "Trip", listOf("travel", "home", "Work"))) } returns
                SaveResult.Saved(v4.copy(tags = listOf("travel", "home", "Work"), version = 5))
            opened(vm)

            vm.addTag("home")
            advanceTimeBy(NoteEditorViewModel.AUTOSAVE_DELAY_MS + 1)
            assertEquals(SaveStatus.SAVING, vm.uiState.value.status)
            vm.addTag("work")
            first.complete(SaveResult.Saved(v4))
            runCurrent()

            assertEquals("the tag added meanwhile is not replaced", listOf("travel", "home", "Work"), vm.uiState.value.tags)
            assertEquals(SaveStatus.UNSAVED, vm.uiState.value.status)
            advanceUntilIdle()
            coVerify(exactly = 1) { repo.save(v4, NoteDraft("", "Trip", listOf("travel", "home", "Work"))) }
            assertEquals(SaveStatus.SAVED, vm.uiState.value.status)
            val note = vm.uiState.value.note
            assertEquals(5L, note?.version)
        }

    @Test
    fun `a new note with only a tag is created with it`() =
        test { vm ->
            val created = Note(id, null, "Untitled", "", pinned = false, tags = listOf("ideas"), updatedAt = Instant.EPOCH, version = 1)
            coEvery { repo.save(null, NoteDraft("", "", listOf("ideas"))) } returns SaveResult.Saved(created)
            vm.open(null)
            runCurrent()

            assertTrue(vm.addTag("#ideas"))
            assertEquals(SaveStatus.UNSAVED, vm.uiState.value.status)
            advanceUntilIdle()

            coVerify(exactly = 1) { repo.save(null, NoteDraft("", "", listOf("ideas"))) }
            assertEquals(created, vm.uiState.value.note)
            assertEquals(SaveStatus.SAVED, vm.uiState.value.status)
        }

    @Test
    fun `a photo creating a new note sends its tags and takes the server's spelling`() =
        test { vm ->
            val created = Note(id, null, "Untitled", "", pinned = false, tags = listOf("work"), updatedAt = Instant.EPOCH, version = 1)
            val photo = NotePhoto(byteArrayOf(1), "image/png", "p.png")
            known = { NotesResult.Ok(emptyList()) }
            coEvery { repo.save(null, NoteDraft("", "", listOf("Work"))) } returns SaveResult.Saved(created)
            coEvery { repo.uploadImage(id, photo) } returns NotesResult.Ok("![](img)")
            coEvery { repo.save(any(), match { it.bodyMd.contains("![](img)") }) } answers {
                SaveResult.Saved(created.copy(bodyMd = secondArg<NoteDraft>().bodyMd, version = 2))
            }
            vm.open(null)
            runCurrent()

            vm.addTag("Work")
            vm.addPhoto(photo)
            advanceUntilIdle()

            coVerify(exactly = 1) { repo.save(null, NoteDraft("", "", listOf("Work"))) }
            assertEquals(listOf("work"), vm.uiState.value.tags)
            assertEquals(SaveStatus.SAVED, vm.uiState.value.status)
        }

    @Test
    fun `tags cannot change while the note loads or a conflict waits, and use theirs takes the other tags`() =
        test { vm ->
            val pending = CompletableDeferred<NotesResult<Note>>()
            coEvery { repo.get(id) } coAnswers { pending.await() }
            vm.open(id)
            runCurrent()
            assertFalse(vm.addTag("home"))
            pending.complete(NotesResult.Ok(loaded))
            runCurrent()
            assertEquals(listOf("travel"), vm.uiState.value.tags)

            val theirs = loaded.copy(tags = listOf("Work"), version = 7)
            coEvery { repo.save(loaded, any()) } returns SaveResult.Conflict(theirs)
            vm.addTag("home")
            advanceUntilIdle()
            assertEquals(NoteConflict(theirs), vm.uiState.value.conflict)
            assertFalse(vm.addTag("errands"))
            vm.removeTag("home")
            assertEquals(listOf("travel", "home"), vm.uiState.value.tags)

            vm.useTheirs()
            assertEquals(listOf("Work"), vm.uiState.value.tags)
            assertEquals(SaveStatus.SAVED, vm.uiState.value.status)
        }

    @Test
    fun `when the tags in use cannot load there are no suggestions, and tags can still be added`() =
        test { vm ->
            known = { NotesResult.Offline }
            opened(vm)

            assertEquals(emptyList<String>(), vm.uiState.value.knownTags)
            assertEquals(emptyList<String>(), vm.uiState.value.tagSuggestions)
            assertTrue(vm.addTag("work"))
            assertEquals(listOf("travel", "work"), vm.uiState.value.tags)
        }

    @Test
    fun `the tags in use load again when the note is opened again, not on the same opening`() =
        test { vm ->
            val first = Any()
            vm.open(id, first)
            runCurrent()
            vm.open(id, first)
            runCurrent()
            assertEquals(1, tagCalls)

            known = { NotesResult.Ok(listOf(NoteTag("fresh", 1))) }
            vm.open(id, Any())
            runCurrent()
            assertEquals(2, tagCalls)
            assertEquals(listOf("fresh"), vm.uiState.value.knownTags)
        }

    @Test
    fun `an older load of the tags in use that ends last does not replace the newer one`() =
        test { vm ->
            val older = CompletableDeferred<NotesResult<List<NoteTag>>>()
            known = { older.await() }
            vm.open(id, Any())
            runCurrent()

            known = { NotesResult.Ok(listOf(NoteTag("fresh", 1))) }
            vm.open(id, Any())
            runCurrent()
            assertEquals(listOf("fresh"), vm.uiState.value.knownTags)

            older.complete(NotesResult.Ok(listOf(NoteTag("stale", 9))))
            advanceUntilIdle()

            assertEquals(2, tagCalls)
            assertEquals(listOf("fresh"), vm.uiState.value.knownTags)
        }

    @Test
    fun `normalizeTag trims and drops one leading hash, as the server does`() {
        assertEquals("home", NoteEditorViewModel.normalizeTag("  #home "))
        assertEquals("home", NoteEditorViewModel.normalizeTag("# home"))
        assertEquals("#home", NoteEditorViewModel.normalizeTag("##home"))
        assertEquals("c#", NoteEditorViewModel.normalizeTag("c#"))
        assertEquals("", NoteEditorViewModel.normalizeTag(" # "))
    }
}
