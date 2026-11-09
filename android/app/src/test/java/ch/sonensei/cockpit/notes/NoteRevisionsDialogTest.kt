package ch.sonensei.cockpit.notes

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.UUID

/** The editor's Earlier versions: the menu item and the dialog listing, showing and restoring a version, driven by test states. */
@RunWith(AndroidJUnit4::class)
class NoteRevisionsDialogTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val note =
        Note(UUID.fromString("00000000-0000-7000-8000-000000000001"), "Trip", "Trip", "Passport", false, emptyList(), Instant.EPOCH, 4)
    private val v3 = NoteRevision(3, "Trip v3", "Passport, tickets", Instant.parse("2026-10-03T09:00:00Z"))
    private val v2 = NoteRevision(2, null, "Passport only\nand a map", Instant.parse("2026-10-02T09:00:00Z"))
    private val v1 = NoteRevision(1, "  ", "", Instant.parse("2026-10-01T09:00:00Z"))
    private val calls = mutableListOf<String>()

    private var editor by mutableStateOf(NoteEditorUiState())

    private fun showEditor(state: NoteEditorUiState) {
        editor = state
        composeRule.setContent {
            CockpitTheme {
                NoteEditorContent(
                    editor,
                    NoteEditorCallbacks(
                        openRevisions = { calls += "open" },
                        revisions =
                            NoteRevisionsCallbacks(
                                select = { calls += "select $it" },
                                restore = { calls += "restore" },
                                retry = { calls += "retry" },
                                close = { calls += "close" },
                            ),
                    ),
                    imageLoader = null,
                )
            }
        }
    }

    private fun showList(revisions: NoteRevisionsState) =
        showEditor(NoteEditorUiState(note = note, body = note.bodyMd, revisions = revisions))

    private fun inPreview(
        text: String,
        substring: Boolean = false,
    ) = composeRule.onNode(hasText(text, substring = substring) and hasAnyAncestor(hasTestTag(NoteRevisionsTags.PREVIEW)))

    @Test
    fun `the editor menu offers Earlier versions, which opens them`() {
        showEditor(NoteEditorUiState(note = note, body = note.bodyMd))
        composeRule.onNodeWithTag(NoteRevisionsTags.DIALOG).assertDoesNotExist()

        composeRule.onNodeWithTag(NoteEditorTags.MENU).performClick()
        composeRule.onNodeWithTag(NoteEditorTags.REVISIONS).assertTextContains("Earlier versions")
        composeRule.onNodeWithTag(NoteEditorTags.REVISIONS).performClick()

        composeRule.onNodeWithTag(NoteEditorTags.REVISIONS).assertDoesNotExist()
        assertEquals(listOf("open"), calls)
    }

    @Test
    fun `the list shows each version newest first with its title, else its first line, else Empty note`() {
        showList(NoteRevisionsState(listOf(v3, v2, v1), selected = 3))

        composeRule.onNodeWithTag(NoteRevisionsTags.DIALOG).assertExists()
        val items = composeRule.onAllNodesWithTag(NoteRevisionsTags.ITEM)
        assertEquals(3, items.fetchSemanticsNodes().size)
        items[0].assertTextContains("Trip v3")
        items[1].assertTextContains("Passport only")
        items[2].assertTextContains("Empty note")
        items[0].assertIsSelected()
        items[1].assertIsNotSelected()
    }

    @Test
    fun `the selected version is previewed, and tapping another selects it`() {
        showList(NoteRevisionsState(listOf(v3, v2), selected = 3))
        inPreview("Trip v3").assertExists()
        inPreview("Passport, tickets").assertExists()

        composeRule.onAllNodesWithTag(NoteRevisionsTags.ITEM)[1].performClick()
        assertEquals(listOf("select 2"), calls)

        editor = editor.copy(revisions = NoteRevisionsState(listOf(v3, v2), selected = 2))
        composeRule.onAllNodesWithTag(NoteRevisionsTags.ITEM)[1].assertIsSelected()
        inPreview("Passport, tickets").assertDoesNotExist()
        inPreview("Passport only", substring = true).assertExists()
    }

    @Test
    fun `Restore and Close report to the editor`() {
        showList(NoteRevisionsState(listOf(v3, v2), selected = 2))

        composeRule.onNodeWithTag(NoteRevisionsTags.RESTORE).assertTextContains("Restore this version").assertIsEnabled()
        composeRule.onNodeWithTag(NoteRevisionsTags.RESTORE).performClick()
        composeRule.onNodeWithTag(NoteRevisionsTags.CLOSE).assertTextContains("Close").performClick()

        assertEquals(listOf("restore", "close"), calls)
    }

    @Test
    fun `Restore is disabled when no version is selected`() {
        showList(NoteRevisionsState(listOf(v3), selected = null))
        composeRule.onNodeWithTag(NoteRevisionsTags.RESTORE).assertIsNotEnabled()
        composeRule.onNodeWithTag(NoteRevisionsTags.PREVIEW).assertDoesNotExist()
    }

    @Test
    fun `Restore is disabled while a pin, archive or delete runs, or a photo uploads`() {
        val list = NoteRevisionsState(listOf(v3), selected = 3)
        showEditor(NoteEditorUiState(note = note, body = note.bodyMd, changing = true, revisions = list))
        composeRule.onNodeWithTag(NoteRevisionsTags.RESTORE).assertIsNotEnabled()

        editor = editor.copy(changing = false, uploading = true)
        composeRule.onNodeWithTag(NoteRevisionsTags.RESTORE).assertIsNotEnabled()

        editor = editor.copy(uploading = false)
        composeRule.onNodeWithTag(NoteRevisionsTags.RESTORE).assertIsEnabled()
    }

    @Test
    fun `while loading there is a loading text and neither Restore nor Retry`() {
        showList(NoteRevisionsState())
        composeRule.onNodeWithText("Loading versions…").assertExists()
        composeRule.onNodeWithTag(NoteRevisionsTags.RESTORE).assertDoesNotExist()
        composeRule.onNodeWithTag(NoteRevisionsTags.RETRY).assertDoesNotExist()
        composeRule.onNodeWithTag(NoteRevisionsTags.CLOSE).assertExists()
    }

    @Test
    fun `a note without earlier versions says so, without Restore`() {
        showList(NoteRevisionsState(emptyList()))
        composeRule.onNodeWithText("No earlier versions yet. Each save keeps the version before it.").assertExists()
        composeRule.onNodeWithTag(NoteRevisionsTags.ITEM).assertDoesNotExist()
        composeRule.onNodeWithTag(NoteRevisionsTags.RESTORE).assertDoesNotExist()
    }

    @Test
    fun `versions that could not load tell why and offer Retry`() {
        showList(NoteRevisionsState(error = RevisionsError.OFFLINE))
        composeRule.onNodeWithText("The earlier versions could not be loaded: no connection.").assertExists()
        composeRule.onNodeWithTag(NoteRevisionsTags.RESTORE).assertDoesNotExist()
        composeRule.onNodeWithTag(NoteRevisionsTags.RETRY).performClick()

        editor = editor.copy(revisions = NoteRevisionsState(error = RevisionsError.FAILED))
        composeRule.onNodeWithText("The earlier versions could not be loaded: the server refused it.").assertExists()
        composeRule.onNodeWithTag(NoteRevisionsTags.RETRY).performClick()

        assertEquals(listOf("retry", "retry"), calls)
    }

    @Test
    fun `the list is not shown while a conflict waits`() {
        showEditor(
            NoteEditorUiState(
                note = note,
                body = "Mine",
                status = SaveStatus.UNSAVED,
                conflict = NoteConflict(note),
                revisions = NoteRevisionsState(listOf(v3), selected = 3),
            ),
        )
        composeRule.onNodeWithTag(NoteRevisionsTags.DIALOG).assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "de")
    fun `the earlier versions are translated to German`() {
        showEditor(NoteEditorUiState(note = note, body = note.bodyMd))
        composeRule.onNodeWithTag(NoteEditorTags.MENU).performClick()
        composeRule.onNodeWithTag(NoteEditorTags.REVISIONS).assertTextContains("Frühere Versionen")
        composeRule.onNodeWithTag(NoteEditorTags.REVISIONS).performClick()

        editor = editor.copy(revisions = NoteRevisionsState(listOf(v1), selected = 1))
        composeRule.onAllNodesWithTag(NoteRevisionsTags.ITEM)[0].assertTextContains("Leere Notiz")
        composeRule.onNodeWithTag(NoteRevisionsTags.RESTORE).assertTextContains("Diese Version wiederherstellen")
        composeRule.onNodeWithTag(NoteRevisionsTags.CLOSE).assertTextContains("Schließen")

        editor = editor.copy(revisions = NoteRevisionsState(emptyList()))
        composeRule.onNodeWithText("Noch keine früheren Versionen. Jedes Speichern behält die Version davor.").assertExists()
    }

    @Test
    fun `revisionSummary is the trimmed title, else the first non-empty body line, else null`() {
        assertEquals("Trip v3", revisionSummary(v3.copy(title = "  Trip v3 ")))
        assertEquals("Passport only", revisionSummary(v2))
        assertEquals("First", revisionSummary(v2.copy(title = "   ", bodyMd = "\n\n  First\nSecond")))
        assertNull(revisionSummary(v1))
        assertNull(revisionSummary(v1.copy(title = null, bodyMd = " \n \n")))
    }
}
