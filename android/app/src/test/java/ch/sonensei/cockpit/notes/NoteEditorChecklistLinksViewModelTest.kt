package ch.sonensei.cockpit.notes

import ch.sonensei.cockpit.search.SearchGroup
import ch.sonensei.cockpit.search.SearchHit
import ch.sonensei.cockpit.search.SearchResult
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.UUID

/** Checklist toggles from the rendered note and the `[[` link search of the note editor. */
@OptIn(ExperimentalCoroutinesApi::class)
class NoteEditorChecklistLinksViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val id = UUID.fromString("00000000-0000-7000-8000-000000000001")
    private val body = "Trip\n- [ ] passport\n- [x] tickets\nplain line"
    private val loaded = Note(id, null, "Trip", body, pinned = false, tags = emptyList(), updatedAt = Instant.EPOCH, version = 3)
    private val repo = mockk<NotesRepository>()
    private val images = mockk<NoteImages> { every { loader } returns mockk() }
    private val searches = mutableListOf<String>()
    private var answer: suspend (String) -> SearchResult = { SearchResult.Ok(emptyList()) }

    private fun hit(
        n: Int,
        title: String,
        type: String = "TASK",
    ) = SearchHit(UUID.fromString("00000000-0000-7000-8000-%012d".format(n)), type, title, null, null)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        coEvery { repo.get(id) } returns NotesResult.Ok(loaded)
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
                    linkSearch = { q ->
                        searches += q
                        answer(q)
                    },
                )
            block(vm)
        }

    @Test
    fun `toggling a checklist item changes the body, marks it unsaved and autosaves like an edit`() =
        test { vm ->
            val toggled = "Trip\n- [x] passport\n- [x] tickets\nplain line"
            coEvery { repo.save(loaded, NoteDraft("", toggled)) } returns
                SaveResult.Saved(loaded.copy(bodyMd = toggled, version = 4))
            vm.open(id)
            runCurrent()

            vm.toggleChecklist(1)
            assertEquals(toggled, vm.uiState.value.body)
            assertEquals(SaveStatus.UNSAVED, vm.uiState.value.status)
            advanceTimeBy(NoteEditorViewModel.AUTOSAVE_DELAY_MS - 100)
            coVerify(exactly = 0) { repo.save(any(), any()) }
            advanceUntilIdle()

            coVerify(exactly = 1) { repo.save(loaded, NoteDraft("", toggled)) }
            assertEquals(SaveStatus.SAVED, vm.uiState.value.status)
            val note = vm.uiState.value.note
            assertEquals(4L, note?.version)
        }

    @Test
    fun `a checked item is unchecked, and toggling back needs no save`() =
        test { vm ->
            vm.open(id)
            runCurrent()

            vm.toggleChecklist(2)
            assertEquals("Trip\n- [ ] passport\n- [ ] tickets\nplain line", vm.uiState.value.body)
            vm.toggleChecklist(2)
            assertEquals(body, vm.uiState.value.body)
            assertEquals(SaveStatus.SAVED, vm.uiState.value.status)
            advanceUntilIdle()
            coVerify(exactly = 0) { repo.save(any(), any()) }
        }

    @Test
    fun `a line that is no checklist item, or out of range, changes nothing`() =
        test { vm ->
            vm.open(id)
            runCurrent()

            listOf(0, 3, -1, 99).forEach { vm.toggleChecklist(it) }

            assertEquals(body, vm.uiState.value.body)
            assertEquals(SaveStatus.SAVED, vm.uiState.value.status)
            advanceUntilIdle()
            coVerify(exactly = 0) { repo.save(any(), any()) }
        }

    @Test
    fun `nothing is toggled while the note loads or a conflict waits`() =
        test { vm ->
            val pending = CompletableDeferred<NotesResult<Note>>()
            coEvery { repo.get(id) } coAnswers { pending.await() }
            vm.open(id)
            runCurrent()
            vm.toggleChecklist(1)
            assertEquals(false, vm.uiState.value.editable)
            pending.complete(NotesResult.Ok(loaded))
            runCurrent()
            assertEquals(body, vm.uiState.value.body)

            val theirs = loaded.copy(bodyMd = "Theirs", version = 7)
            coEvery { repo.save(loaded, any()) } returns SaveResult.Conflict(theirs)
            vm.setBody("$body\n- [ ] more")
            advanceUntilIdle()
            assertEquals(NoteConflict(theirs), vm.uiState.value.conflict)
            vm.toggleChecklist(1)
            assertEquals("$body\n- [ ] more", vm.uiState.value.body)
        }

    @Test
    fun `link search runs once typing pauses for 250 ms, with the trimmed query, and lists all hits`() =
        test { vm ->
            val task = hit(10, "Book flights")
            val note = hit(11, "Flight notes", "NOTE")
            answer = { SearchResult.Ok(listOf(SearchGroup("TASK", listOf(task)), SearchGroup("NOTE", listOf(note)))) }

            vm.searchLinks("fl")
            advanceTimeBy(100)
            vm.searchLinks("fli ")
            assertEquals(LinkSuggestions("fli ", searching = true), vm.uiState.value.links)
            advanceTimeBy(NoteEditorViewModel.LINK_SEARCH_DELAY_MS - 1)
            assertTrue(searches.isEmpty())
            advanceTimeBy(2)

            assertEquals(listOf("fli"), searches)
            assertEquals(LinkSuggestions("fli ", hits = listOf(task, note)), vm.uiState.value.links)
        }

    @Test
    fun `the same query again does not search again`() =
        test { vm ->
            vm.searchLinks("abc")
            advanceUntilIdle()
            vm.searchLinks("abc")
            advanceUntilIdle()
            assertEquals(listOf("abc"), searches)
        }

    @Test
    fun `an empty or blank query shows the hint without searching, null clears the suggestions`() =
        test { vm ->
            vm.searchLinks("")
            assertEquals(LinkSuggestions(""), vm.uiState.value.links)
            vm.searchLinks("  ")
            advanceUntilIdle()
            assertEquals(LinkSuggestions("  "), vm.uiState.value.links)
            assertTrue(searches.isEmpty())

            vm.searchLinks("abc")
            vm.searchLinks(null)
            advanceUntilIdle()
            assertNull(vm.uiState.value.links)
            assertTrue("a cleared query cancels the pending search", searches.isEmpty())
        }

    @Test
    fun `offline and failed searches say so`() =
        test { vm ->
            answer = { SearchResult.Offline }
            vm.searchLinks("abc")
            advanceUntilIdle()
            assertEquals(LinkSuggestions("abc", error = LinkSearchError.OFFLINE), vm.uiState.value.links)

            answer = { SearchResult.Failed(500) }
            vm.searchLinks("abcd")
            advanceUntilIdle()
            assertEquals(LinkSuggestions("abcd", error = LinkSearchError.FAILED), vm.uiState.value.links)
        }

    @Test
    fun `older hits stay while the next query waits, and a slow older search never overwrites newer results`() =
        test { vm ->
            val old = hit(20, "Old")
            val new = hit(21, "New")
            val slow = CompletableDeferred<SearchResult>()
            answer = { q -> if (q == "a") slow.await() else SearchResult.Ok(listOf(SearchGroup("TASK", listOf(new)))) }

            vm.searchLinks("a")
            advanceTimeBy(NoteEditorViewModel.LINK_SEARCH_DELAY_MS + 1)
            assertEquals(listOf("a"), searches)
            vm.searchLinks("ab")
            advanceUntilIdle()
            assertEquals(LinkSuggestions("ab", hits = listOf(new)), vm.uiState.value.links)

            slow.complete(SearchResult.Ok(listOf(SearchGroup("TASK", listOf(old)))))
            advanceUntilIdle()
            assertEquals(LinkSuggestions("ab", hits = listOf(new)), vm.uiState.value.links)

            vm.searchLinks("abc")
            assertEquals(LinkSuggestions("abc", hits = listOf(new), searching = true), vm.uiState.value.links)
        }
}
