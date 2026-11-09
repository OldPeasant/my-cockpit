package ch.sonensei.cockpit.notes

import android.content.Context
import android.os.Looper
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.search.SearchGroup
import ch.sonensei.cockpit.search.SearchHit
import ch.sonensei.cockpit.search.SearchResult
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * The integrated note editor (and-notes-editor): the route wired to one real view model (fake repository), with tags,
 * checklist toggles, `[[` links and earlier versions used together on the same note. Each feature has its own tests;
 * these check that the route passes every action to the view model and that the features' changes end up in the same
 * saves.
 */
@RunWith(AndroidJUnit4::class)
class NoteEditorIntegrationTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val body = "- [ ] passport\n- [x] tickets"
    private val toggled = "- [x] passport\n- [x] tickets"
    private val loaded = Note(id(1), "Trip", "Trip", body, false, emptyList(), Instant.EPOCH, 3)
    private val repo = mockk<NotesRepository>()
    private val images = NoteImages(ApplicationProvider.getApplicationContext<Context>(), OkHttpClient())
    private val searches = mutableListOf<String>()
    private val hit = SearchHit(id(7), "TASK", "Book flights", "Inbox", null)

    @Before
    fun setUp() {
        coEvery { repo.get(loaded.id) } returns NotesResult.Ok(loaded)
    }

    private fun show(): NoteEditorViewModel {
        val vm =
            NoteEditorViewModel(
                repo,
                images,
                linkSearch = { q ->
                    searches += q
                    SearchResult.Ok(listOf(SearchGroup("TASK", listOf(hit))))
                },
                tagSource = { NotesResult.Ok(listOf(NoteTag("family", 2), NoteTag("travel", 5))) },
            )
        composeRule.setContent {
            CockpitTheme {
                NoteEditorRoute(id = loaded.id, key = "integration", onClose = {}, viewModel = vm)
            }
        }
        composeRule.waitForIdle()
        return vm
    }

    /** Lets the view model's delays (autosave, link search) pass on the main looper. */
    private fun pass(ms: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
        composeRule.waitForIdle()
    }

    private fun waitUntilSaved(vm: NoteEditorViewModel) =
        composeRule.waitUntil(WAIT_MS) { vm.uiState.value.status == SaveStatus.SAVED && vm.settled }

    @Test
    fun `a suggested tag and a checklist tap in the view are saved together, in one save like an edit`() {
        val draft = NoteDraft("Trip", toggled, listOf("travel"))
        coEvery { repo.save(loaded, draft) } returns
            SaveResult.Saved(loaded.copy(bodyMd = toggled, tags = listOf("travel"), version = 4))
        val vm = show()

        // The tags in use are suggested most used first; one is picked as a chip.
        composeRule.onNodeWithTag(NoteTagsTags.INPUT).performClick()
        val suggested = composeRule.onAllNodesWithTag(NoteTagsTags.SUGGESTION).fetchSemanticsNodes()
        assertEquals(2, suggested.size)
        composeRule.onNodeWithText("#travel").performClick()
        composeRule.onNodeWithText("passport").performClick()

        assertEquals(listOf("travel"), vm.uiState.value.tags)
        assertEquals(toggled, vm.uiState.value.body)
        assertEquals(SaveStatus.UNSAVED, vm.uiState.value.status)
        pass(NoteEditorViewModel.AUTOSAVE_DELAY_MS)
        waitUntilSaved(vm)

        coVerify(exactly = 1) { repo.save(any(), any()) }
        coVerify(exactly = 1) { repo.save(loaded, draft) }
        assertEquals(1, composeRule.onAllNodesWithTag(NoteTagsTags.CHIP).fetchSemanticsNodes().size)
        val note = vm.uiState.value.note
        assertEquals(4L, note?.version)
    }

    @Test
    fun `typing a link searches through the view model, and the picked item is saved as type and id`() {
        val linked = "$body\nSee [[task:${id(7)}]]"
        coEvery { repo.save(loaded, NoteDraft("Trip", linked)) } returns SaveResult.Saved(loaded.copy(bodyMd = linked, version = 4))
        val vm = show()
        composeRule.onNodeWithTag(NoteEditorTags.TOGGLE).performClick()

        composeRule.onNodeWithTag(NoteEditorTags.BODY).performTextReplacement("$body\nSee [[boo")
        assertEquals(emptyList<String>(), searches)
        pass(NoteEditorViewModel.LINK_SEARCH_DELAY_MS)
        composeRule.waitUntil(WAIT_MS) { composeRule.onAllNodesWithTag(NoteEditorTags.LINK_HIT).fetchSemanticsNodes().size == 1 }
        assertEquals(listOf("boo"), searches)

        composeRule.onNodeWithTag(NoteEditorTags.LINK_HIT).performClick()
        assertEquals(linked, vm.uiState.value.body)
        assertNull(vm.uiState.value.links)
        composeRule.onNodeWithTag(NoteEditorTags.LINK_SUGGESTIONS).assertDoesNotExist()

        // Done shows the rendered note and saves at once.
        composeRule.onNodeWithTag(NoteEditorTags.TOGGLE).performClick()
        waitUntilSaved(vm)
        coVerify(exactly = 1) { repo.save(loaded, NoteDraft("Trip", linked)) }
    }

    @Test
    fun `restoring an earlier version from the menu saves the checklist toggle first, then shows the restored text with the tags`() {
        val withTag = loaded.copy(tags = listOf("travel"))
        coEvery { repo.get(loaded.id) } returns NotesResult.Ok(withTag)
        val saved = withTag.copy(bodyMd = toggled, version = 4)
        val restored = withTag.copy(title = "Old trip", displayTitle = "Old trip", bodyMd = "- [ ] visa", version = 5)
        coEvery { repo.save(withTag, NoteDraft("Trip", toggled, listOf("travel"))) } returns SaveResult.Saved(saved)
        coEvery { repo.revisions(loaded.id) } returns
            NotesResult.Ok(
                listOf(
                    NoteRevision(2, "Trip", body, Instant.parse("2026-01-02T10:00:00Z")),
                    NoteRevision(1, "Old trip", "- [ ] visa", Instant.parse("2026-01-01T10:00:00Z")),
                ),
            )
        coEvery { repo.restoreRevision(saved, 1) } returns SaveResult.Saved(restored)
        val vm = show()

        composeRule.onNodeWithText("passport").performClick()
        assertEquals(SaveStatus.UNSAVED, vm.uiState.value.status)
        composeRule.onNodeWithTag(NoteEditorTags.MENU).performClick()
        composeRule.onNodeWithTag(NoteEditorTags.REVISIONS).performClick()
        composeRule.waitUntil(WAIT_MS) { composeRule.onAllNodesWithTag(NoteRevisionsTags.ITEM).fetchSemanticsNodes().size == 2 }
        composeRule.onAllNodesWithTag(NoteRevisionsTags.ITEM)[1].performClick()
        val open = vm.uiState.value.revisions
        assertEquals(1, open?.selected)
        composeRule.onNodeWithTag(NoteRevisionsTags.RESTORE).performClick()

        composeRule.waitUntil(WAIT_MS) { vm.uiState.value.body == "- [ ] visa" && !vm.uiState.value.restoring }
        coVerifyOrder {
            repo.save(withTag, NoteDraft("Trip", toggled, listOf("travel")))
            repo.restoreRevision(saved, 1)
        }
        composeRule.onNodeWithTag(NoteRevisionsTags.DIALOG).assertDoesNotExist()
        composeRule.onNodeWithText("Old trip").assertExists()
        composeRule.onNodeWithText("visa").assertExists()
        assertEquals(listOf("travel"), vm.uiState.value.tags)
        assertEquals(1, composeRule.onAllNodesWithTag(NoteTagsTags.CHIP).fetchSemanticsNodes().size)
        assertEquals(SaveStatus.SAVED, vm.uiState.value.status)
    }

    private companion object {
        const val WAIT_MS = 5_000L
    }
}
