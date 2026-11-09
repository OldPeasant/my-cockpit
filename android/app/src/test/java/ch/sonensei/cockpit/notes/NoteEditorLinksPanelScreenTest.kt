package ch.sonensei.cockpit.notes

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.util.UUID

/** The linked-items panel slot of the note editor: shown below a saved note's text in the view, for that note. */
@RunWith(AndroidJUnit4::class)
class NoteEditorLinksPanelScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val note = Note(id(1), null, "Trip", "Pack the bags", false, emptyList(), Instant.EPOCH, 1)
    private val panels = mutableListOf<UUID>()
    private var editor by mutableStateOf(NoteEditorUiState())

    private fun show(state: NoteEditorUiState) {
        editor = state
        composeRule.setContent {
            CockpitTheme {
                NoteEditorContent(
                    editor,
                    NoteEditorCallbacks(),
                    imageLoader = null,
                    linksPanel = { noteId ->
                        panels += noteId
                        Text("links of $noteId", Modifier.testTag(PANEL))
                    },
                )
            }
        }
    }

    @Test
    fun `a saved note shows its links panel below the text in the view`() {
        show(NoteEditorUiState(note = note, body = note.bodyMd))

        composeRule.onNodeWithTag(NoteEditorTags.VIEW).assertExists()
        composeRule.onNodeWithTag(PANEL).assertExists()
        assertEquals(setOf(note.id), panels.toSet())
    }

    @Test
    fun `a saved note with an empty text still shows its links panel`() {
        show(NoteEditorUiState(note = note.copy(bodyMd = ""), body = ""))

        composeRule.onNodeWithTag(PANEL).assertExists()
    }

    @Test
    fun `a new note that is not saved yet has no links panel`() {
        show(NoteEditorUiState(body = "Draft"))

        composeRule.onNodeWithTag(NoteEditorTags.VIEW).assertExists()
        composeRule.onNodeWithTag(PANEL).assertDoesNotExist()
        assertEquals(emptyList<UUID>(), panels)
    }

    @Test
    fun `the panel is hidden while editing and back in the view`() {
        show(NoteEditorUiState(note = note, body = note.bodyMd, editing = true))
        composeRule.onNodeWithTag(NoteEditorTags.BODY).assertExists()
        composeRule.onNodeWithTag(PANEL).assertDoesNotExist()

        editor = editor.copy(editing = false)
        composeRule.onNodeWithTag(PANEL).assertExists()
    }

    @Test
    fun `a note still loading has no links panel`() {
        show(NoteEditorUiState(note = note, body = note.bodyMd, loading = true))

        composeRule.onNodeWithTag(PANEL).assertDoesNotExist()
    }

    private companion object {
        const val PANEL = "test-links-panel"
    }
}
