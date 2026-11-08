package ch.sonensei.cockpit.files

import android.content.Context
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onChildAt
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.UUID

/** The Files screen alone, driven by states the tests control. */
@RunWith(AndroidJUnit4::class)
class FilesScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val attachments = DriveEntry.Folder(id(1), "Attachments", "ATTACHMENTS")
    private val docs = DriveEntry.Folder(id(2), "Documents", null)
    private val pdf = DriveEntry.File(id(3), "2025.pdf", "application/pdf", 1536, Instant.parse("2026-10-01T09:00:00Z"))
    private val calls = mutableListOf<String>()

    private fun show(state: FilesUiState) =
        composeRule.setContent {
            CockpitTheme {
                FilesContent(
                    state,
                    FilesCallbacks(
                        openFolder = { calls += "folder ${it.name}" },
                        openFile = { calls += "open ${it.name}" },
                        saveFile = { calls += "save ${it.name}" },
                        goTo = { calls += "goTo $it" },
                        upload = { calls += "upload" },
                        dismissUpload = { calls += "dismiss $it" },
                        dismissDownloadMessage = { calls += "dismiss download" },
                        retry = { calls += "retry" },
                    ),
                )
            }
        }

    @Test
    fun `a folder lists folders then files, and opens either`() {
        show(FilesUiState(entries = listOf(attachments, docs, pdf), loaded = true))

        val list = composeRule.onNodeWithTag(FilesTags.LIST)
        list.onChildAt(0).assertTextContains("Attachments", substring = true)
        list.onChildAt(0).assertTextContains("Managed by Cockpit", substring = true)
        composeRule.onNodeWithText("1.5 KB", substring = true).assertExists()

        composeRule.onNodeWithTag(FilesTags.entry(docs.id)).performClick()
        composeRule.onNodeWithTag(FilesTags.entry(pdf.id)).performClick()
        assertEquals(listOf("folder Documents", "open 2025.pdf"), calls)
    }

    @Test
    fun `a file's menu opens it or saves it to the device`() {
        show(FilesUiState(entries = listOf(pdf), loaded = true))
        composeRule.onNodeWithTag(FilesTags.more(pdf.id)).performClick()
        composeRule.onNodeWithTag(FilesTags.SAVE).performClick()
        composeRule.onNodeWithTag(FilesTags.more(pdf.id)).performClick()
        composeRule.onNodeWithTag(FilesTags.OPEN).performClick()
        assertEquals(listOf("save 2025.pdf", "open 2025.pdf"), calls)
    }

    @Test
    fun `the path leads back to any folder above, the current one is not a link`() {
        show(
            FilesUiState(
                path = listOf(FolderCrumb(docs.id, "Documents"), FolderCrumb(id(4), "tax")),
                loaded = true,
            ),
        )
        composeRule.onNodeWithTag(FilesTags.crumb(2)).assertIsNotEnabled()
        composeRule.onNodeWithTag(FilesTags.crumb(1)).performClick()
        composeRule.onNodeWithTag(FilesTags.crumb(0)).assertTextContains("Files").performClick()
        assertEquals(listOf("goTo 1", "goTo 0"), calls)
        composeRule.onNodeWithTag(FilesTags.EMPTY).assertExists()
    }

    @Test
    fun `an empty folder invites an upload, and the button asks for files`() {
        show(FilesUiState(loaded = true))
        composeRule.onNodeWithTag(FilesTags.EMPTY).assertTextContains("Upload a file", substring = true)
        composeRule.onNodeWithTag(FilesTags.UPLOAD).performClick()
        assertEquals(listOf("upload"), calls)
    }

    @Test
    fun `nothing is called empty before the folder arrived`() {
        show(FilesUiState(loading = true))
        composeRule.onNodeWithTag(FilesTags.EMPTY).assertDoesNotExist()
    }

    @Test
    fun `a failed load says so with retry`() {
        show(FilesUiState(entries = listOf(docs), loaded = true, message = FilesMessage.OFFLINE))
        composeRule.onNodeWithTag(FilesTags.MESSAGE).assertExists()
        composeRule.onNodeWithText("Offline. Showing what was loaded before.").assertExists()
        composeRule.onNodeWithTag(FilesTags.RETRY).performClick()
        assertEquals(listOf("retry"), calls)
    }

    @Test
    fun `uploads show their state, finished ones can be dismissed`() {
        show(
            FilesUiState(
                loaded = true,
                uploads =
                    listOf(
                        UploadItem(1, "a.txt", UploadState.UPLOADING),
                        UploadItem(2, "big.iso", UploadState.TOO_LARGE),
                        UploadItem(3, "c.txt", UploadState.DONE),
                    ),
            ),
        )
        composeRule.onNodeWithTag(FilesTags.upload(1)).assertExists()
        composeRule.onNodeWithText("Uploading…").assertExists()
        composeRule.onNodeWithTag(FilesTags.dismissUpload(1)).assertDoesNotExist()
        composeRule.onNodeWithText("Too large for the drive").assertExists()
        composeRule.onNodeWithTag(FilesTags.dismissUpload(3)).performClick()
        assertEquals(listOf("dismiss 3"), calls)
    }

    @Test
    fun `a file downloading shows progress, a failed download can be dismissed`() {
        show(
            FilesUiState(
                entries = listOf(pdf),
                loaded = true,
                downloading = pdf.id,
                downloadMessage = FilesMessage.FAILED,
            ),
        )
        composeRule.onNodeWithTag(FilesTags.DOWNLOADING).assertExists()
        composeRule.onNodeWithTag(FilesTags.more(pdf.id)).assertDoesNotExist()
        composeRule.onNodeWithTag(FilesTags.DOWNLOAD_MESSAGE).assertExists()
        composeRule.onNodeWithText("The file could not be downloaded.").assertExists()
        composeRule.onNodeWithContentDescription("Dismiss").performClick()
        assertEquals(listOf("dismiss download"), calls)
    }

    @Test
    fun `while a file downloads, no other file can be opened or saved`() {
        val other = DriveEntry.File(id(4), "b.txt", "text/plain", 1, Instant.parse("2026-10-01T09:00:00Z"))
        show(FilesUiState(entries = listOf(docs, pdf, other), loaded = true, downloading = pdf.id))

        // Folders still open: browsing goes on while the file downloads.
        composeRule.onNodeWithTag(FilesTags.entry(docs.id)).performClick()
        composeRule.onNodeWithTag(FilesTags.entry(other.id)).assertIsNotEnabled().performClick()
        composeRule.onNodeWithTag(FilesTags.more(other.id)).performClick()
        composeRule.onNodeWithTag(FilesTags.OPEN).assertIsNotEnabled().performClick()
        composeRule.onNodeWithTag(FilesTags.SAVE).assertIsNotEnabled().performClick()
        assertEquals(listOf("folder Documents"), calls)
    }

    private val all =
        FilesCallbacks(
            openFolder = { calls += "folder ${it.name}" },
            openFile = { calls += "open ${it.name}" },
            toggleSelected = { calls += "toggle ${it.name}" },
            clearSelection = { calls += "clear" },
            newFolder = { calls += "new folder" },
            rename = { calls += "rename ${it.name}" },
            move = { calls += "move ${it.name}" },
            delete = { calls += "delete ${it.name}" },
            moveSelected = { calls += "move selected" },
            deleteSelected = { calls += "delete selected" },
            dismissActionMessage = { calls += "dismiss action" },
            saveName = { calls += "save name $it" },
            cancelName = { calls += "cancel name" },
            pickerOpen = { calls += "picker open ${it.name}" },
            pickerGoTo = { calls += "picker goTo $it" },
            confirmMove = { calls += "confirm move" },
            cancelMove = { calls += "cancel move" },
        )

    private fun showAll(state: FilesUiState) = composeRule.setContent { CockpitTheme { FilesContent(state, all) } }

    /** Shows [initial], and lets the test change the state like the view model would. */
    private fun showLive(initial: FilesUiState): MutableState<FilesUiState> {
        val state = mutableStateOf(initial)
        composeRule.setContent { CockpitTheme { FilesContent(state.value, all) } }
        return state
    }

    @Test
    fun `a long press starts selecting a file or a folder, but not an auto-managed folder`() {
        showAll(FilesUiState(entries = listOf(attachments, docs, pdf), loaded = true))
        composeRule.onNodeWithTag(FilesTags.entry(pdf.id)).performTouchInput { longClick() }
        composeRule.onNodeWithTag(FilesTags.entry(docs.id)).performTouchInput { longClick() }
        composeRule.onNodeWithTag(FilesTags.entry(attachments.id)).performTouchInput { longClick() }
        // A long press on an auto-managed folder selects nothing (Compose then takes it for a tap, which opens it).
        assertEquals(listOf("toggle 2025.pdf", "toggle Documents"), calls.filter { it.startsWith("toggle") })
        composeRule.onNodeWithTag(FilesTags.SELECTION_BAR).assertDoesNotExist()
    }

    @Test
    fun `while selecting, a tap picks or unpicks instead of opening, and the bar acts on the selection`() {
        showAll(FilesUiState(entries = listOf(attachments, docs, pdf), loaded = true, selected = setOf(docs.id, pdf.id)))

        composeRule.onNodeWithTag(FilesTags.SELECTION_COUNT).assertTextEquals("2 selected")
        composeRule.onNodeWithTag(FilesTags.entry(pdf.id)).assertIsSelected()
        composeRule.onNodeWithTag(FilesTags.crumb(0)).assertDoesNotExist()
        composeRule.onNodeWithTag(FilesTags.UPLOAD).assertDoesNotExist()
        composeRule.onNodeWithTag(FilesTags.NEW_FOLDER).assertDoesNotExist()
        composeRule.onNodeWithTag(FilesTags.more(pdf.id)).assertDoesNotExist()
        composeRule.onNodeWithTag(FilesTags.more(docs.id)).assertDoesNotExist()

        composeRule.onNodeWithTag(FilesTags.entry(pdf.id)).performClick()
        composeRule.onNodeWithTag(FilesTags.entry(docs.id)).performClick()
        composeRule.onNodeWithTag(FilesTags.entry(attachments.id)).assertIsNotEnabled().performClick()
        composeRule.onNodeWithTag(FilesTags.SELECTION_MOVE).performClick()
        composeRule.onNodeWithTag(FilesTags.SELECTION_DELETE).performClick()
        composeRule.onNodeWithTag(FilesTags.SELECTION_CLEAR).performClick()
        assertEquals(listOf("toggle 2025.pdf", "toggle Documents", "move selected", "delete selected", "clear"), calls)
    }

    @Test
    fun `one selected entry is counted, an unpicked row is not selected`() {
        showAll(FilesUiState(entries = listOf(docs, pdf), loaded = true, selected = setOf(pdf.id)))
        composeRule.onNodeWithTag(FilesTags.SELECTION_COUNT).assertTextEquals("1 selected")
        composeRule.onNodeWithTag(FilesTags.entry(docs.id)).assertIsNotSelected()
    }

    @Test
    fun `while a move or delete runs, the selection bar cannot start another`() {
        showAll(FilesUiState(entries = listOf(pdf), loaded = true, selected = setOf(pdf.id), working = true))
        composeRule.onNodeWithTag(FilesTags.SELECTION_MOVE).assertIsNotEnabled().performClick()
        composeRule.onNodeWithTag(FilesTags.SELECTION_DELETE).assertIsNotEnabled().performClick()
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun `a file's and a folder's menu rename, move and delete them, an auto-managed folder has no menu`() {
        showAll(FilesUiState(entries = listOf(attachments, docs, pdf), loaded = true))
        composeRule.onNodeWithTag(FilesTags.more(attachments.id)).assertDoesNotExist()

        for (entry in listOf(pdf, docs)) {
            for (action in listOf(FilesTags.RENAME, FilesTags.MOVE, FilesTags.DELETE)) {
                composeRule.onNodeWithTag(FilesTags.more(entry.id)).performClick()
                composeRule.onNodeWithTag(action).performClick()
                composeRule.onNodeWithTag(action).assertDoesNotExist()
            }
        }
        assertEquals(
            listOf("rename 2025.pdf", "move 2025.pdf", "delete 2025.pdf", "rename Documents", "move Documents", "delete Documents"),
            calls,
        )
    }

    @Test
    fun `the menu says Move to Trash, and its changes are disabled while one runs`() {
        showAll(FilesUiState(entries = listOf(pdf), loaded = true, working = true))
        composeRule.onNodeWithTag(FilesTags.more(pdf.id)).performClick()
        composeRule
            .onNodeWithTag(FilesTags.DELETE)
            .assertTextContains("Move to Trash")
            .assertIsNotEnabled()
            .performClick()
        composeRule.onNodeWithTag(FilesTags.RENAME).assertIsNotEnabled()
        composeRule.onNodeWithTag(FilesTags.MOVE).assertIsNotEnabled()
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun `the new folder button asks for a name, and a failed change can be dismissed`() {
        showAll(FilesUiState(loaded = true, actionMessage = ActionMessage.MOVE_CONFLICT))
        composeRule.onNodeWithTag(FilesTags.ACTION_MESSAGE).assertExists()
        composeRule.onNodeWithText("Not moved: the folder already has an item with that name", substring = true).assertExists()
        composeRule.onNodeWithTag(FilesTags.NEW_FOLDER).performClick()
        composeRule.onNodeWithContentDescription("Dismiss").performClick()
        assertEquals(listOf("new folder", "dismiss action"), calls)
    }

    @Test
    fun `a change made offline or refused says so`() {
        showAll(
            FilesUiState(
                entries = listOf(pdf),
                loaded = true,
                actionMessage = ActionMessage.OFFLINE,
                movePicker = MovePicker(listOf(pdf), origin = emptyList(), path = emptyList(), moveMessage = ActionMessage.FAILED),
            ),
        )
        composeRule.onNodeWithText("Offline. Nothing was changed.").assertExists()
        composeRule.onNodeWithText("That did not work.").assertExists()
    }

    @Test
    fun `the new folder dialog creates only a valid name and says what is wrong once typed`() {
        showAll(FilesUiState(loaded = true, nameDialog = NameDialog()))
        composeRule.onNodeWithText("New folder").assertExists()
        // Nothing typed yet: not an error, but nothing to create either.
        composeRule.onNodeWithTag(FilesTags.NAME_ERROR, useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithTag(FilesTags.NAME_SAVE).assertIsNotEnabled()

        composeRule.onNodeWithTag(FilesTags.NAME_FIELD).performTextInput("a/b")
        composeRule
            .onNodeWithTag(
                FilesTags.NAME_ERROR,
                useUnmergedTree = true,
            ).assertTextEquals("A name cannot be “.” or “..” or contain / or \\ or control characters such as tabs or line breaks")
        composeRule.onNodeWithTag(FilesTags.NAME_SAVE).assertIsNotEnabled()
        composeRule.onNodeWithTag(FilesTags.NAME_FIELD).performTextReplacement("   ")
        composeRule.onNodeWithTag(FilesTags.NAME_ERROR, useUnmergedTree = true).assertTextEquals("Enter a name")
        composeRule.onNodeWithTag(FilesTags.NAME_FIELD).performTextReplacement("a".repeat(MAX_NAME_LENGTH + 1))
        composeRule.onNodeWithTag(FilesTags.NAME_ERROR, useUnmergedTree = true).assertTextEquals("At most 255 characters")

        composeRule.onNodeWithTag(FilesTags.NAME_FIELD).performTextReplacement("Taxes")
        composeRule.onNodeWithTag(FilesTags.NAME_ERROR, useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithTag(FilesTags.NAME_SAVE).assertTextEquals("Create").performClick()
        composeRule.onNodeWithTag(FilesTags.NAME_FIELD).performImeAction()
        composeRule.onNodeWithTag(FilesTags.NAME_CANCEL).performClick()
        assertEquals(listOf("save name Taxes", "save name Taxes", "cancel name"), calls)
    }

    @Test
    fun `the rename dialog starts with the name, which cannot be saved unchanged, and shows a taken name`() {
        showAll(FilesUiState(entries = listOf(pdf), loaded = true, nameDialog = NameDialog(pdf, message = ActionMessage.NAME_TAKEN)))
        composeRule.onNodeWithTag(FilesTags.NAME_FIELD).assertTextContains("2025.pdf")
        composeRule.onNodeWithTag(FilesTags.NAME_ERROR, useUnmergedTree = true).assertTextEquals("That name is already in this folder")
        composeRule.onNodeWithTag(FilesTags.NAME_SAVE).assertTextEquals("Rename").assertIsNotEnabled()

        composeRule.onNodeWithTag(FilesTags.NAME_FIELD).performTextReplacement(" 2025.pdf ")
        composeRule.onNodeWithTag(FilesTags.NAME_SAVE).assertIsNotEnabled()
        composeRule.onNodeWithTag(FilesTags.NAME_FIELD).performTextReplacement("2026.pdf")
        composeRule.onNodeWithTag(FilesTags.NAME_SAVE).assertIsEnabled().performClick()
        assertEquals(listOf("save name 2026.pdf"), calls)
    }

    @Test
    fun `while a name is saved, the dialog cannot be changed, saved again or cancelled`() {
        showAll(FilesUiState(entries = listOf(pdf), loaded = true, nameDialog = NameDialog(pdf, saving = true)))
        composeRule.onNodeWithTag(FilesTags.NAME_FIELD).assertIsNotEnabled()
        composeRule.onNodeWithTag(FilesTags.NAME_SAVE).assertIsNotEnabled()
        composeRule.onNodeWithTag(FilesTags.NAME_CANCEL).assertIsNotEnabled()
    }

    @Test
    fun `the move picker browses folders, cannot open the one being moved, and cannot move where it already is`() {
        val tax = DriveEntry.Folder(id(4), "tax", null)
        val state =
            showLive(
                FilesUiState(
                    entries = listOf(attachments, docs, tax, pdf),
                    loaded = true,
                    movePicker =
                        MovePicker(
                            listOf(docs),
                            origin = emptyList(),
                            path = emptyList(),
                            folders = listOf(attachments, docs, tax),
                        ),
                ),
            )
        composeRule.onNodeWithText("Move Documents").assertExists()
        composeRule.onNodeWithTag(FilesTags.PICKER_MOVE).assertTextEquals("Move here").assertIsNotEnabled()
        composeRule.onNodeWithTag(FilesTags.pickerFolder(docs.id)).assertIsNotEnabled().performClick()
        composeRule.onNodeWithTag(FilesTags.pickerFolder(attachments.id)).performClick()
        composeRule.onNodeWithTag(FilesTags.pickerFolder(tax.id)).performClick()
        composeRule.onNodeWithTag(FilesTags.pickerCrumb(0)).assertIsNotEnabled()

        state.value =
            state.value.copy(
                movePicker = state.value.movePicker!!.copy(path = listOf(FolderCrumb(tax.id, "tax")), folders = emptyList()),
            )
        composeRule.onNodeWithTag(FilesTags.PICKER_EMPTY).assertTextEquals("No folders here")
        composeRule.onNodeWithTag(FilesTags.pickerCrumb(1)).assertIsNotEnabled()
        composeRule.onNodeWithTag(FilesTags.pickerCrumb(0)).performClick()
        composeRule.onNodeWithTag(FilesTags.PICKER_MOVE).assertIsEnabled().performClick()
        composeRule.onNodeWithTag(FilesTags.PICKER_CANCEL).performClick()
        assertEquals(listOf("picker open Attachments", "picker open tax", "picker goTo 0", "confirm move", "cancel move"), calls)
    }

    @Test
    fun `the move picker counts several entries and shows why a folder or the move failed`() {
        showAll(
            FilesUiState(
                entries = listOf(docs, pdf),
                loaded = true,
                movePicker =
                    MovePicker(
                        listOf(docs, pdf),
                        origin = emptyList(),
                        path = listOf(FolderCrumb(id(4), "tax")),
                        message = FilesMessage.OFFLINE,
                        moveMessage = ActionMessage.CHANGED,
                    ),
            ),
        )
        composeRule.onNodeWithText("Move 2 items").assertExists()
        composeRule.onNodeWithTag(FilesTags.PICKER_MESSAGE).assertTextEquals("Offline. Showing what was loaded before.")
        composeRule.onNodeWithTag(FilesTags.PICKER_EMPTY).assertDoesNotExist()
        composeRule
            .onNodeWithTag(FilesTags.ACTION_MESSAGE)
            .assertTextEquals("It was changed elsewhere meanwhile. The folder was reloaded; try again.")
    }

    @Test
    fun `while moving, the picker can neither browse, move again nor be cancelled`() {
        val tax = DriveEntry.Folder(id(4), "tax", null)
        showAll(
            FilesUiState(
                entries = listOf(pdf),
                loaded = true,
                movePicker =
                    MovePicker(
                        listOf(pdf),
                        origin = emptyList(),
                        path = listOf(FolderCrumb(docs.id, "Documents")),
                        folders = listOf(tax),
                        moving = true,
                    ),
            ),
        )
        composeRule.onNodeWithTag(FilesTags.PICKER_MOVE).assertIsNotEnabled()
        composeRule.onNodeWithTag(FilesTags.PICKER_CANCEL).assertIsNotEnabled()
        composeRule.onNodeWithTag(FilesTags.pickerCrumb(0)).assertIsNotEnabled()
        composeRule.onNodeWithTag(FilesTags.pickerFolder(tax.id)).assertIsNotEnabled()
    }

    @Test
    fun `confirmations name one entry and count several`() {
        val res = ApplicationProvider.getApplicationContext<Context>().resources
        assertEquals("Created Taxes", changedText(res, FilesEvent.Changed(FilesChange.CREATED, 1, "Taxes")))
        assertEquals("Renamed to 2026.pdf", changedText(res, FilesEvent.Changed(FilesChange.RENAMED, 1, "2026.pdf")))
        assertEquals("Moved 2025.pdf", changedText(res, FilesEvent.Changed(FilesChange.MOVED, 1, "2025.pdf")))
        assertEquals("Moved 3 items", changedText(res, FilesEvent.Changed(FilesChange.MOVED, 3, "2025.pdf")))
        assertEquals("Moved 2025.pdf to the Trash", changedText(res, FilesEvent.Changed(FilesChange.TRASHED, 1, "2025.pdf")))
        assertEquals("Moved 2 items to the Trash", changedText(res, FilesEvent.Changed(FilesChange.TRASHED, 2, "2025.pdf")))
    }

    @Test
    @Config(qualifiers = "de")
    fun `in German, the selection, the menu and the confirmations are German`() {
        showAll(FilesUiState(entries = listOf(docs, pdf), loaded = true, selected = setOf(docs.id, pdf.id)))
        composeRule.onNodeWithTag(FilesTags.SELECTION_COUNT).assertTextEquals("2 ausgewählt")
        composeRule.onNodeWithContentDescription("Auswahl aufheben").assertExists()
        composeRule.onNodeWithContentDescription("In den Papierkorb").assertExists()
        composeRule.onNodeWithContentDescription("Verschieben").assertExists()

        val res = ApplicationProvider.getApplicationContext<Context>().resources
        assertEquals("2 Elemente verschoben", changedText(res, FilesEvent.Changed(FilesChange.MOVED, 2, "2025.pdf")))
        assertEquals("2025.pdf in den Papierkorb verschoben", changedText(res, FilesEvent.Changed(FilesChange.TRASHED, 1, "2025.pdf")))
    }

    @Test
    @Config(qualifiers = "de")
    fun `in German, the menu and the name dialog are German`() {
        showAll(FilesUiState(entries = listOf(pdf), loaded = true, nameDialog = NameDialog(message = ActionMessage.NAME_TAKEN)))
        composeRule.onNodeWithText("Neuer Ordner").assertExists()
        composeRule.onNodeWithTag(FilesTags.NAME_SAVE).assertTextEquals("Erstellen")
        composeRule.onNodeWithTag(FilesTags.NAME_CANCEL).assertTextEquals("Abbrechen")
        composeRule
            .onNodeWithTag(
                FilesTags.NAME_ERROR,
                useUnmergedTree = true,
            ).assertTextEquals("Dieser Name ist in diesem Ordner schon vergeben")
    }
}
