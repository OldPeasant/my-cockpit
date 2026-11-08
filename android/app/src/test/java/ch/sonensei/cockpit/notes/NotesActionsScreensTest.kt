package ch.sonensei.cockpit.notes

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.UUID

/** Search, the Archived view, pin/archive/delete menus and the snackbars of the list and the editor, driven by test states. */
@RunWith(AndroidJUnit4::class)
class NotesActionsScreensTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val pinned = Note(id(1), "Packing", "Packing", "Passport", true, listOf("trip"), Instant.EPOCH, 1)
    private val plain = Note(id(2), null, "Groceries", "Groceries\nmilk", false, emptyList(), Instant.EPOCH, 1)
    private val archived = Note(id(3), "Old trip", "Old trip", "Done", false, listOf("trip"), Instant.EPOCH, 2, archived = true)
    private val calls = mutableListOf<String>()

    private val listCallbacks =
        NotesListCallbacks(
            open = { calls += "open $it" },
            create = { calls += "create" },
            setQuery = { calls += "query $it" },
            showArchived = { calls += "archived $it" },
            togglePin = { calls += "pin ${it.id}" },
            toggleArchived = { calls += "archive ${it.id}" },
            delete = { calls += "delete ${it.id}" },
            undo = { calls += "undo ${it.note.id}" },
            dismiss = { calls += "dismiss ${it.id}" },
        )

    private fun showList(state: NotesListUiState) = composeRule.setContent { CockpitTheme { NotesListContent(state, listCallbacks) } }

    @Test
    fun `the search field reports what is typed, and Clear empties it`() {
        var state by mutableStateOf(NotesListUiState(notes = listOf(pinned, plain), loaded = true))
        composeRule.setContent {
            CockpitTheme {
                NotesListContent(
                    state,
                    listCallbacks.copy(setQuery = {
                        calls += "query $it"
                        state = state.copy(query = it)
                    }),
                )
            }
        }
        composeRule.onNodeWithTag(NotesTags.SEARCH_CLEAR).assertDoesNotExist()
        composeRule.onNodeWithTag(NotesTags.SEARCH).performTextInput("milk")
        composeRule.onNodeWithTag(NotesTags.SEARCH_CLEAR).performClick()
        composeRule.onNodeWithTag(NotesTags.SEARCH_CLEAR).assertDoesNotExist()
        assertEquals(listOf("query milk", "query "), calls)
    }

    @Test
    fun `a search shows only the notes found, and says when none match`() {
        var state by mutableStateOf(NotesListUiState(notes = listOf(pinned, plain), loaded = true, query = "milk", hits = setOf(plain.id)))
        composeRule.setContent { CockpitTheme { NotesListContent(state, listCallbacks) } }
        composeRule.onNodeWithTag(NotesTags.note(plain.id)).assertExists()
        composeRule.onNodeWithTag(NotesTags.note(pinned.id)).assertDoesNotExist()

        state = state.copy(query = " cheese ", hits = emptySet())
        composeRule.onNodeWithTag(NotesTags.EMPTY).assertTextContains("No notes match “cheese”.")

        // No answer yet: neither notes nor an empty text.
        state = state.copy(hits = null)
        composeRule.onNodeWithTag(NotesTags.EMPTY).assertDoesNotExist()
        composeRule.onNodeWithTag(NotesTags.note(plain.id)).assertDoesNotExist()
    }

    @Test
    fun `the view switch shows which view is active, and the Archived view has no New note`() {
        var state by mutableStateOf(NotesListUiState(notes = listOf(plain), loaded = true))
        composeRule.setContent { CockpitTheme { NotesListContent(state, listCallbacks) } }
        composeRule.onNodeWithTag(NotesTags.SHOW_ACTIVE).assertIsSelected()
        composeRule.onNodeWithTag(NotesTags.SHOW_ARCHIVED).assertIsNotSelected()
        composeRule.onNodeWithTag(NotesTags.NEW).assertExists()
        composeRule.onNodeWithTag(NotesTags.SHOW_ARCHIVED).performClick()

        state = NotesListUiState(notes = listOf(archived), archived = true, loaded = true)
        composeRule.onNodeWithTag(NotesTags.SHOW_ARCHIVED).assertIsSelected()
        composeRule.onNodeWithTag(NotesTags.NEW).assertDoesNotExist()
        composeRule.onNodeWithTag(NotesTags.SHOW_ACTIVE).performClick()
        assertEquals(listOf("archived true", "archived false"), calls)
    }

    @Test
    fun `the Archived view's empty texts follow the tag`() {
        var state by mutableStateOf(NotesListUiState(archived = true, loaded = true))
        composeRule.setContent { CockpitTheme { NotesListContent(state, listCallbacks) } }
        composeRule.onNodeWithTag(NotesTags.EMPTY).assertTextContains("No archived notes.")
        state = state.copy(tag = "trip")
        composeRule.onNodeWithTag(NotesTags.EMPTY).assertTextContains("No archived notes tagged trip.")
    }

    @Test
    fun `the row menu pins, archives and deletes the note`() {
        showList(NotesListUiState(notes = listOf(plain), loaded = true))
        composeRule.onNodeWithTag(NotesTags.menu(plain.id)).performClick()
        composeRule.onNodeWithTag(NotesTags.PIN).assertTextContains("Pin")
        composeRule.onNodeWithTag(NotesTags.ARCHIVE).assertTextContains("Archive")
        composeRule.onNodeWithTag(NotesTags.PIN).performClick()
        composeRule.onNodeWithTag(NotesTags.PIN).assertDoesNotExist()
        composeRule.onNodeWithTag(NotesTags.menu(plain.id)).performClick()
        composeRule.onNodeWithTag(NotesTags.ARCHIVE).performClick()
        composeRule.onNodeWithTag(NotesTags.menu(plain.id)).performClick()
        composeRule.onNodeWithTag(NotesTags.DELETE).assertTextContains("Move to Trash")
        composeRule.onNodeWithTag(NotesTags.DELETE).performClick()
        // The menu does not open the note.
        assertEquals(listOf("pin ${plain.id}", "archive ${plain.id}", "delete ${plain.id}"), calls)
    }

    @Test
    fun `the row menu offers Unpin for a pinned note and Unarchive for an archived one`() {
        showList(NotesListUiState(notes = listOf(pinned, archived), loaded = true))
        composeRule.onNodeWithTag(NotesTags.menu(pinned.id)).performClick()
        composeRule.onNodeWithTag(NotesTags.PIN).assertTextContains("Unpin")
        composeRule.onNodeWithTag(NotesTags.ARCHIVE).assertTextContains("Archive")
        composeRule.onNodeWithTag(NotesTags.PIN).performClick()
        composeRule.onNodeWithTag(NotesTags.menu(archived.id)).performClick()
        composeRule.onNodeWithTag(NotesTags.ARCHIVE).assertTextContains("Unarchive")
    }

    @Test
    fun `a busy note's menu is disabled`() {
        showList(NotesListUiState(notes = listOf(plain), loaded = true, busy = setOf(plain.id)))
        composeRule.onNodeWithTag(NotesTags.menu(plain.id)).assertIsNotEnabled()
        composeRule.onNodeWithTag(NotesTags.menu(plain.id)).performClick()
        composeRule.onNodeWithTag(NotesTags.PIN).assertDoesNotExist()
    }

    @Test
    fun `a trashed note's snackbar offers Undo`() {
        showList(NotesListUiState(loaded = true, snackbar = NotesSnackbar.Trashed(plain, id = 1)))
        composeRule.onNodeWithText("Moved “Groceries” to the Trash").assertExists()
        composeRule.onNodeWithText("Undo").performClick()
        composeRule.waitForIdle()
        assertEquals(listOf("undo ${plain.id}"), calls)
    }

    @Test
    fun `archive and failure snackbars tell what happened and report their end`() {
        var state by mutableStateOf(NotesListUiState(loaded = true, snackbar = NotesSnackbar.Archived(archived, id = 1)))
        composeRule.setContent { CockpitTheme { NotesListContent(state, listCallbacks) } }
        composeRule.onNodeWithText("Archived “Old trip”").assertExists()
        state = state.copy(snackbar = NotesSnackbar.Archived(plain, id = 2))
        composeRule.onNodeWithText("Moved “Groceries” back to Notes").assertExists()
        state = state.copy(snackbar = NotesSnackbar.Failed(NoteChangeFailure.CONFLICT, id = 3))
        composeRule.onNodeWithText("This note was changed elsewhere. The list was reloaded.").assertExists()
        state = state.copy(snackbar = NotesSnackbar.Failed(NoteChangeFailure.OFFLINE, id = 4))
        composeRule.onNodeWithText("Not changed: no connection.").assertExists()
        state = state.copy(snackbar = NotesSnackbar.Failed(NoteChangeFailure.FAILED, id = 5))
        composeRule.onNodeWithText("Not changed: the server refused the change.").assertExists()
        composeRule.mainClock.advanceTimeBy(15_000)
        composeRule.waitForIdle()
        assertEquals("dismiss 5", calls.last())
    }

    @Test
    @Config(qualifiers = "de")
    fun `the new list texts are translated to German`() {
        showList(NotesListUiState(notes = listOf(plain), loaded = true, snackbar = NotesSnackbar.Trashed(plain, id = 1)))
        composeRule.onNodeWithText("„Groceries“ in den Papierkorb verschoben").assertExists()
        composeRule.onNodeWithText("Rückgängig").assertExists()
        composeRule.onNodeWithTag(NotesTags.SHOW_ARCHIVED).assertTextContains("Archiv")
        composeRule.onNodeWithTag(NotesTags.menu(plain.id)).performClick()
        composeRule.onNodeWithTag(NotesTags.DELETE).assertTextContains("In den Papierkorb")
    }

    private var editor by mutableStateOf(NoteEditorUiState())

    private fun showEditor(state: NoteEditorUiState) {
        editor = state
        composeRule.setContent {
            CockpitTheme {
                NoteEditorContent(
                    editor,
                    NoteEditorCallbacks(
                        togglePin = { calls += "pin" },
                        toggleArchived = { calls += "archive" },
                        delete = { calls += "delete" },
                        dismissMessage = { calls += "dismiss" },
                    ),
                    imageLoader = null,
                )
            }
        }
    }

    @Test
    fun `the editor menu pins, archives and deletes a saved note`() {
        showEditor(NoteEditorUiState(note = plain, body = plain.bodyMd))
        composeRule.onNodeWithTag(NoteEditorTags.MENU).performClick()
        composeRule.onNodeWithTag(NoteEditorTags.PIN).assertTextContains("Pin")
        composeRule.onNodeWithTag(NoteEditorTags.ARCHIVE).assertTextContains("Archive")
        composeRule.onNodeWithTag(NoteEditorTags.PIN).performClick()
        composeRule.onNodeWithTag(NoteEditorTags.MENU).performClick()
        composeRule.onNodeWithTag(NoteEditorTags.ARCHIVE).performClick()
        composeRule.onNodeWithTag(NoteEditorTags.MENU).performClick()
        composeRule.onNodeWithTag(NoteEditorTags.DELETE).performClick()
        assertEquals(listOf("pin", "archive", "delete"), calls)
    }

    @Test
    fun `the editor menu offers Unpin and Unarchive`() {
        showEditor(NoteEditorUiState(note = archived.copy(pinned = true), body = "Done"))
        composeRule.onNodeWithTag(NoteEditorTags.MENU).performClick()
        composeRule.onNodeWithTag(NoteEditorTags.PIN).assertTextContains("Unpin")
        composeRule.onNodeWithTag(NoteEditorTags.ARCHIVE).assertTextContains("Unarchive")
    }

    @Test
    fun `no editor menu for a new note or during a conflict, and a disabled one while a change runs`() {
        showEditor(NoteEditorUiState(editing = true))
        composeRule.onNodeWithTag(NoteEditorTags.MENU).assertDoesNotExist()

        editor = NoteEditorUiState(note = plain, body = "Mine", conflict = NoteConflict(plain), status = SaveStatus.UNSAVED)
        composeRule.onNodeWithTag(NoteEditorTags.MENU).assertDoesNotExist()

        editor = NoteEditorUiState(note = plain, body = plain.bodyMd, changing = true)
        composeRule.onNodeWithTag(NoteEditorTags.MENU).assertIsNotEnabled()
    }

    @Test
    fun `a change that did not get through is told in the editor and can be dismissed`() {
        showEditor(NoteEditorUiState(note = plain, body = plain.bodyMd, message = NoteEditorMessage.CHANGE_OFFLINE))
        composeRule.onNodeWithText("Not changed: no connection.").assertExists()
        editor = editor.copy(message = NoteEditorMessage.CHANGE_FAILED)
        composeRule.onNodeWithText("Not changed: the server refused the change.").assertExists()
        composeRule.onNodeWithText("Dismiss").performClick()
        assertEquals(listOf("dismiss"), calls)
    }
}
