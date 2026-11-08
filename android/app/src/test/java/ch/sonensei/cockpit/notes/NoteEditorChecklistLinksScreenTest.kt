package ch.sonensei.cockpit.notes

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.search.SearchHit
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.util.UUID

/** Tapping checklist items in the rendered note and picking a `[[` link suggestion in the editor. */
@RunWith(AndroidJUnit4::class)
class NoteEditorChecklistLinksScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val note = Note(id(1), null, "Trip", "", false, emptyList(), Instant.EPOCH, 1)
    private val calls = mutableListOf<String>()
    private var editor by mutableStateOf(NoteEditorUiState())

    private fun show(state: NoteEditorUiState) {
        editor = state
        composeRule.setContent {
            CockpitTheme {
                NoteEditorContent(
                    editor,
                    NoteEditorCallbacks(
                        setBody = { editor = editor.copy(body = it) },
                        toggleChecklist = { calls += "toggle $it" },
                        searchLinks = { calls += "search $it" },
                    ),
                    imageLoader = null,
                )
            }
        }
    }

    @Test
    fun `tapping a checklist item in the view toggles it by its line`() {
        show(NoteEditorUiState(note = note, body = "Trip\n- [ ] passport\n- [x] tickets\n- loose"))

        composeRule.onAllNodesWithTag(MarkdownTags.CHECK_ITEM).fetchSemanticsNodes().let { assertEquals(2, it.size) }
        composeRule.onNodeWithText("tickets").performClick()
        composeRule.onNodeWithText("passport").performClick()
        composeRule.onNodeWithText("loose").performClick()

        assertEquals(listOf("toggle 2", "toggle 1"), calls)
    }

    @Test
    fun `checklist items cannot be tapped during a conflict`() {
        show(NoteEditorUiState(note = note, body = "- [ ] passport", conflict = NoteConflict(note), status = SaveStatus.UNSAVED))

        composeRule.onNodeWithTag(MarkdownTags.CHECK_ITEM).assertDoesNotExist()
    }

    @Test
    fun `typing a link reports its query, and picking a hit inserts the link`() {
        val hit = SearchHit(id(7), "TASK", "Book flights", "Inbox", null)
        show(NoteEditorUiState(note = note, body = "", editing = true))

        composeRule.onNodeWithTag(NoteEditorTags.BODY).performTextInput("See [[bo")
        assertEquals("search bo", calls.last())
        composeRule.onNodeWithTag(NoteEditorTags.LINK_SUGGESTIONS).assertDoesNotExist()

        editor = editor.copy(links = LinkSuggestions("bo", hits = listOf(hit)))
        composeRule.onNodeWithTag(NoteEditorTags.LINK_SUGGESTIONS).assertExists()
        composeRule.onNodeWithText("Book flights").assertExists()
        composeRule.onNodeWithTag(NoteEditorTags.LINK_HIT).performClick()

        assertEquals("See [[task:${id(7)}]]", editor.body)
        assertEquals("search null", calls.last())
    }

    @Test
    fun `the suggestion list shows at most six hits, and says when the search is offline or empty`() {
        val hits = (1..8).map { SearchHit(id(10 + it), "NOTE", "Hit $it", null, null) }
        show(NoteEditorUiState(note = note, body = "", editing = true))
        composeRule.onNodeWithTag(NoteEditorTags.BODY).performTextInput("[[hit")

        editor = editor.copy(links = LinkSuggestions("hit", hits = hits))
        composeRule.onAllNodesWithTag(NoteEditorTags.LINK_HIT).fetchSemanticsNodes().let { assertEquals(6, it.size) }

        editor = editor.copy(links = LinkSuggestions("hit", error = LinkSearchError.OFFLINE))
        composeRule.onAllNodesWithTag(NoteEditorTags.LINK_HIT).fetchSemanticsNodes().let { assertEquals(0, it.size) }
        composeRule.onNodeWithTag(NoteEditorTags.LINK_SUGGESTIONS).assertExists()
    }
}
