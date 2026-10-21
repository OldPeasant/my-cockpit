package ch.sonensei.cockpit.notes

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onChildAt
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.util.UUID

/** The list and the editor alone, driven by states the tests control. */
@RunWith(AndroidJUnit4::class)
class NotesScreensTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val pinned = Note(id(1), "Packing", "Packing", "Passport and **tickets**", true, listOf("trip"), Instant.EPOCH, 1)
    private val plain = Note(id(2), null, "Groceries", "Groceries\nmilk, bread", false, emptyList(), Instant.EPOCH, 1)
    private val calls = mutableListOf<String>()

    private fun showList(state: NotesListUiState) =
        composeRule.setContent {
            CockpitTheme {
                NotesListContent(
                    state,
                    NotesListCallbacks(
                        open = { calls += "open $it" },
                        create = { calls += "create" },
                        selectTag = { calls += "tag $it" },
                        retry = { calls += "retry" },
                    ),
                )
            }
        }

    @Test
    fun `the list shows notes in the given order with pin, excerpt and tags`() {
        showList(NotesListUiState(notes = listOf(pinned, plain), tags = listOf(NoteTag("trip", 1)), loaded = true))

        val list = composeRule.onNodeWithTag(NotesTags.LIST)
        list.onChildAt(0).assertTextContains("Packing", substring = true)
        list.onChildAt(1).assertTextContains("Groceries", substring = true)
        composeRule.onNodeWithContentDescription("Pinned").assertExists()
        composeRule.onNodeWithText("Passport and tickets").assertExists()
        composeRule.onNodeWithText("milk, bread").assertExists()
        composeRule.onNodeWithTag(NotesTags.note(pinned.id)).assertTextContains("#trip", substring = true)

        composeRule.onNodeWithTag(NotesTags.note(plain.id)).performClick()
        composeRule.onNodeWithTag(NotesTags.tag("trip")).performClick()
        composeRule.onNodeWithTag(NotesTags.NEW).performClick()
        assertEquals(listOf("open ${plain.id}", "tag trip", "create"), calls)
    }

    @Test
    fun `the active tag is selected, and an empty filter says so`() {
        showList(NotesListUiState(tags = listOf(NoteTag("trip", 0)), tag = "trip", loaded = true))
        composeRule.onNodeWithTag(NotesTags.tag("trip")).assertIsSelected()
        composeRule.onNodeWithTag(NotesTags.EMPTY).assertTextContains("No notes tagged trip.")
        composeRule.onNodeWithTag(NotesTags.ALL).performClick()
        assertEquals(listOf("tag null"), calls)
    }

    @Test
    fun `offline shows a retry`() {
        showList(NotesListUiState(notes = listOf(plain), loaded = true, message = NotesListMessage.OFFLINE))
        composeRule.onNodeWithTag(NotesTags.RETRY).performClick()
        assertEquals(listOf("retry"), calls)
    }

    private var editor by mutableStateOf(NoteEditorUiState())

    private fun showEditor(state: NoteEditorUiState) {
        editor = state
        composeRule.setContent {
            CockpitTheme {
                NoteEditorContent(
                    editor,
                    NoteEditorCallbacks(
                        close = { calls += "close" },
                        setEditing = {
                            calls += "editing $it"
                            editor = editor.copy(editing = it)
                        },
                        setTitle = { editor = editor.copy(title = it) },
                        setBody = { editor = editor.copy(body = it, status = SaveStatus.UNSAVED) },
                        addPhoto = { source, at -> calls += "photo $source $at" },
                        saveNow = { calls += "save" },
                        keepMine = { calls += "keep" },
                        useTheirs = { calls += "theirs" },
                    ),
                    imageLoader = null,
                )
            }
        }
    }

    @Test
    fun `the view renders Markdown with its images, Edit opens the plain editor`() {
        showEditor(NoteEditorUiState(note = plain, body = "# Heading\nSome *text*\n\n![Receipt](cockpit-file:${id(9)})"))

        composeRule.onNodeWithText("Heading").assertExists()
        composeRule.onNodeWithText("Some text").assertExists()
        composeRule.onNodeWithContentDescription("Receipt").assertExists()
        composeRule.onAllNodesWithTag(MarkdownTags.IMAGE).fetchSemanticsNodes().let { assertEquals(1, it.size) }
        composeRule.onNodeWithTag(NoteEditorTags.STATUS).assertTextContains("Saved")

        composeRule.onNodeWithTag(NoteEditorTags.TOGGLE).performClick()
        composeRule.onNodeWithTag(NoteEditorTags.BODY).performTextReplacement("Plain")
        assertEquals("Plain", editor.body)
        composeRule.onNodeWithTag(NoteEditorTags.STATUS).assertTextContains("Unsaved changes")
        assertEquals(listOf("editing true"), calls)
    }

    @Test
    fun `photo buttons pass the cursor position`() {
        showEditor(NoteEditorUiState(note = plain, body = "", editing = true))
        composeRule.onNodeWithTag(NoteEditorTags.BODY).performTextInput("abc")
        composeRule.onNodeWithTag(NoteEditorTags.GALLERY).performClick()
        composeRule.onNodeWithTag(NoteEditorTags.CAMERA).performClick()
        assertEquals(listOf("photo GALLERY 3", "photo CAMERA 3"), calls)
    }

    @Test
    fun `a conflict offers both versions and hides editing`() {
        showEditor(NoteEditorUiState(note = plain, body = "Mine", conflict = NoteConflict(plain), status = SaveStatus.UNSAVED))
        composeRule.onNodeWithText("This note was changed on another device while you edited it.").assertExists()
        composeRule.onNodeWithTag(NoteEditorTags.TOGGLE).assertDoesNotExist()
        composeRule.onNodeWithTag(NoteEditorTags.KEEP_MINE).performClick()
        composeRule.onNodeWithTag(NoteEditorTags.USE_THEIRS).performClick()
        assertEquals(listOf("keep", "theirs"), calls)
    }

    @Test
    fun `a failed save offers to save again`() {
        showEditor(NoteEditorUiState(note = plain, body = "Mine", status = SaveStatus.OFFLINE))
        composeRule.onNodeWithTag(NoteEditorTags.STATUS).assertTextContains("Not saved: no connection")
        composeRule.onNodeWithTag(NoteEditorTags.SAVE_RETRY).performClick()
        composeRule.onNodeWithContentDescription("Back to notes").performClick()
        assertEquals(listOf("save", "close"), calls)
    }
}
