package ch.sonensei.cockpit.files

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onChildAt
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
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
}
