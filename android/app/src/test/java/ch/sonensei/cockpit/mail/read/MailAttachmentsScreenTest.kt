package ch.sonensei.cockpit.mail.read

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.VideoFile
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.files.DriveEntry
import ch.sonensei.cockpit.files.FolderCrumb
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** The attachment list of the read view: rows, the menu, the notices, the preview and the "Save to Files" picker. */
@RunWith(AndroidJUnit4::class)
class MailAttachmentsScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val pdf = MailAttachment("2", "report.pdf", 1024, "application/pdf")
    private val photo = MailAttachment("3", "photo.jpg", 3 * 1024 * 1024, "image/jpeg")
    private val zip = MailAttachment("4", "archive.zip", null, "application/zip")

    private val message =
        MailMessage(
            id = id(1),
            subject = "Quarterly report",
            from = "Anna Example <anna@example.org>",
            to = listOf("me@example.org"),
            cc = emptyList(),
            date = Instant.parse("2026-10-21T06:00:00Z"),
            seen = true,
            html = "<p>Hello</p>",
            text = "Hello",
            remoteImagesBlocked = 0,
            attachments = listOf(pdf, photo, zip),
        )

    private val calls = mutableListOf<String>()

    private val callbacks =
        MailAttachmentCallbacks(
            open = { calls += "open ${it.partId}" },
            preview = { calls += "preview ${it.partId}" },
            saveToDevice = { calls += "device ${it.partId}" },
            saveToFiles = { calls += "files ${it.partId}" },
            dismissNotice = { calls += "dismiss" },
            closePreview = { calls += "close preview" },
            openFolder = { calls += "folder ${it.name}" },
            goToFolder = { calls += "go $it" },
            saveHere = { calls += "save here" },
            closePicker = { calls += "close picker" },
        )

    private fun show(
        attachments: MailAttachmentsUiState = MailAttachmentsUiState(),
        busy: Boolean = false,
    ) = composeRule.setContent {
        CockpitTheme {
            MailReadContent(
                MailReadUiState(message = message, busy = busy),
                MailReadCallbacks(),
                attachments = attachments,
                attachmentCallbacks = callbacks,
                zone = ZoneId.of("Europe/Zurich"),
                body = { _, modifier -> Box(modifier.testTag(MailReadTags.BODY)) },
            )
        }
    }

    // The list

    @Test
    fun `each attachment is a row with its name and size`() {
        show()

        composeRule.onNodeWithText("Attachments").assertExists()
        composeRule.onNodeWithTag(MailAttachmentTags.row("2")).assertTextContains("report.pdf").assertTextContains("1.0 KB")
        composeRule.onNodeWithTag(MailAttachmentTags.row("3")).assertTextContains("photo.jpg").assertTextContains("3.0 MB")
        composeRule.onNodeWithTag(MailAttachmentTags.row("4")).assertTextEquals("archive.zip")
    }

    @Test
    fun `a mail without attachments has no attachment list`() {
        composeRule.setContent {
            CockpitTheme {
                MailReadContent(
                    MailReadUiState(message = message.copy(attachments = emptyList())),
                    MailReadCallbacks(),
                    attachmentCallbacks = callbacks,
                    body = { _, modifier -> Box(modifier.testTag(MailReadTags.BODY)) },
                )
            }
        }

        composeRule.onNodeWithTag(MailReadTags.ATTACHMENTS).assertDoesNotExist()
    }

    @Test
    fun `a tap on a row opens the attachment`() {
        show()

        composeRule.onNodeWithTag(MailAttachmentTags.row("2")).performClick()

        assertEquals(listOf("open 2"), calls)
    }

    @Test
    fun `only images and PDFs offer a preview`() {
        show()

        composeRule.onNodeWithTag(MailAttachmentTags.preview("2")).assertExists().performClick()
        composeRule.onNodeWithTag(MailAttachmentTags.preview("3")).assertExists().performClick()
        composeRule.onNodeWithTag(MailAttachmentTags.preview("4")).assertDoesNotExist()

        assertEquals(listOf("preview 2", "preview 3"), calls)
    }

    @Test
    fun `the menu saves to the device or to Files`() {
        show()

        composeRule.onNodeWithContentDescription("More for report.pdf").performClick()
        composeRule.onNodeWithTag(MailAttachmentTags.saveDevice("2")).assertTextEquals("Save to device").performClick()
        composeRule.onNodeWithTag(MailAttachmentTags.saveDevice("2")).assertDoesNotExist()
        composeRule.onNodeWithTag(MailAttachmentTags.more("2")).performClick()
        composeRule.onNodeWithTag(MailAttachmentTags.saveFiles("2")).assertTextEquals("Save to Files").performClick()
        composeRule.onNodeWithTag(MailAttachmentTags.saveFiles("2")).assertDoesNotExist()

        assertEquals(listOf("device 2", "files 2"), calls)
    }

    @Test
    fun `while an attachment is worked on its row shows progress and every button waits`() {
        show(MailAttachmentsUiState(working = "3"))

        composeRule.onNodeWithTag(MailAttachmentTags.working("3")).assertExists()
        composeRule.onNodeWithTag(MailAttachmentTags.working("2")).assertDoesNotExist()
        composeRule.onNodeWithTag(MailAttachmentTags.row("2")).assertIsNotEnabled()
        composeRule.onNodeWithTag(MailAttachmentTags.preview("2")).assertIsNotEnabled()
        composeRule.onNodeWithTag(MailAttachmentTags.more("2")).assertIsNotEnabled()

        composeRule.onNodeWithTag(MailAttachmentTags.row("2")).performClick()
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun `while the mail itself is busy the attachment buttons wait`() {
        show(busy = true)

        composeRule.onNodeWithTag(MailAttachmentTags.row("2")).assertIsNotEnabled()
        composeRule.onNodeWithTag(MailAttachmentTags.more("2")).assertIsNotEnabled()
    }

    // Notices

    private fun notice(
        notice: AttachmentNotice,
        text: String,
    ) {
        show(MailAttachmentsUiState(notice = notice))
        composeRule.onNodeWithTag(MailAttachmentTags.NOTICE).assertTextEquals(text)
    }

    @Test
    fun `a save to the device is confirmed`() = notice(AttachmentNotice.SavedToDevice("report.pdf"), "report.pdf was saved.")

    @Test
    fun `a save to Files names the default folder`() =
        notice(AttachmentNotice.SavedToFiles("report.pdf"), "report.pdf was saved to Files in /Attachments/Mail.")

    @Test
    fun `a save to Files names the picked folder`() =
        notice(AttachmentNotice.SavedToFiles("report.pdf", "/Projects/2026"), "report.pdf was saved to Files in /Projects/2026.")

    @Test
    fun `a failed move says where the file is and where it was meant to go`() =
        notice(
            AttachmentNotice.SavedToFiles("report.pdf", notMoved = "/Projects"),
            "report.pdf was saved to Files in /Attachments/Mail; it could not be moved to /Projects.",
        )

    @Test
    fun `a failed open is reported`() = notice(AttachmentNotice.Failed(AttachmentAction.OPEN), "The attachment could not be opened.")

    @Test
    fun `an offline preview is reported with its reason`() =
        notice(
            AttachmentNotice.Failed(AttachmentAction.PREVIEW, offline = true),
            "The preview could not be loaded. Can't reach the server.",
        )

    @Test
    fun `a failed save to the device is reported`() =
        notice(AttachmentNotice.Failed(AttachmentAction.SAVE), "The attachment could not be saved.")

    @Test
    fun `a too large attachment for Files is reported with its reason`() =
        notice(
            AttachmentNotice.Failed(AttachmentAction.FILES, tooLarge = true),
            "The attachment could not be saved to Files. It is larger than Files allows.",
        )

    @Test
    fun `no app for an attachment is reported`() = notice(AttachmentNotice.NoApp, "No app on this device opens this attachment.")

    @Test
    fun `a notice can be dismissed`() {
        show(MailAttachmentsUiState(notice = AttachmentNotice.NoApp))

        composeRule.onNodeWithContentDescription("Dismiss").performClick()

        assertEquals(listOf("dismiss"), calls)
    }

    // Preview

    @Test
    fun `a preview of a file that cannot be rendered says so, and can be closed or opened elsewhere`() {
        val broken = tmp.newFile("report.pdf").apply { writeText("not a pdf") }
        show(MailAttachmentsUiState(preview = AttachmentPreview(pdf, broken, AttachmentPreviewKind.PDF)))

        composeRule.onNodeWithTag(MailAttachmentTags.PREVIEW_DIALOG).assertExists()
        composeRule
            .onNode(hasText("report.pdf") and hasAnyAncestor(hasTestTag(MailAttachmentTags.PREVIEW_DIALOG)))
            .assertExists()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodes(hasTestTag(MailAttachmentTags.PREVIEW_ERROR)).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(MailAttachmentTags.PREVIEW_ERROR).assertTextEquals("This file cannot be shown here.")

        composeRule.onNodeWithTag(MailAttachmentTags.PREVIEW_OPEN).performClick()
        composeRule.onNodeWithTag(MailAttachmentTags.PREVIEW_CLOSE).performClick()

        assertEquals(listOf("close preview", "open 2", "close preview"), calls)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `an image preview shows the image as its only page`() {
        val image = tmp.newFile("photo.png")
        val bitmap = Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888)
        image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        show(MailAttachmentsUiState(preview = AttachmentPreview(photo, image, AttachmentPreviewKind.IMAGE)))

        composeRule.waitUntil(5_000) {
            composeRule.onAllNodes(hasContentDescription("Page 1 of 1")).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(MailAttachmentTags.PREVIEW_ERROR).assertDoesNotExist()
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `an empty image file cannot be shown`() {
        val empty = tmp.newFile("photo.jpg")
        show(MailAttachmentsUiState(preview = AttachmentPreview(photo, empty, AttachmentPreviewKind.IMAGE)))

        composeRule.waitUntil(5_000) {
            composeRule.onAllNodes(hasTestTag(MailAttachmentTags.PREVIEW_ERROR)).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(MailAttachmentTags.PREVIEW_ERROR).assertExists()
    }

    // Folder picker

    @Test
    fun `the picker at the drive root lists its folders and saves to the default folder`() {
        show(
            MailAttachmentsUiState(
                picker = AttachmentFolderPicker(pdf, folders = listOf(DriveEntry.Folder(id(20), "Projects", null))),
            ),
        )

        composeRule.onNodeWithText("Save report.pdf to Files").assertExists()
        composeRule.onNodeWithTag(MailAttachmentTags.crumb(0)).assertTextEquals("Files").assertIsNotEnabled()
        composeRule.onNodeWithTag(MailAttachmentTags.PICKER_SAVE).assertTextEquals("Save to /Attachments/Mail").assertIsEnabled()
        composeRule.onNodeWithTag(MailAttachmentTags.folder("Projects")).performClick()
        composeRule.onNodeWithTag(MailAttachmentTags.PICKER_SAVE).performClick()
        composeRule.onNodeWithTag(MailAttachmentTags.PICKER_CANCEL).assertTextEquals("Cancel").performClick()

        assertEquals(listOf("folder Projects", "save here", "close picker"), calls)
    }

    @Test
    fun `inside a folder the picker saves here and its path leads back`() {
        show(
            MailAttachmentsUiState(
                picker =
                    AttachmentFolderPicker(
                        pdf,
                        path = listOf(FolderCrumb(id(20), "Projects"), FolderCrumb(id(22), "2026")),
                    ),
            ),
        )

        composeRule.onNodeWithTag(MailAttachmentTags.PICKER_SAVE).assertTextEquals("Save here")
        composeRule.onNodeWithTag(MailAttachmentTags.PICKER_EMPTY).assertTextEquals("No folders here.")
        composeRule.onNodeWithTag(MailAttachmentTags.crumb(2)).assertTextEquals("2026").assertIsNotEnabled()
        composeRule.onNodeWithTag(MailAttachmentTags.crumb(1)).assertTextEquals("Projects").performClick()
        composeRule.onNodeWithTag(MailAttachmentTags.crumb(0)).performClick()

        assertEquals(listOf("go 1", "go 0"), calls)
    }

    @Test
    fun `a folder that cannot be listed is reported in the picker`() {
        show(MailAttachmentsUiState(picker = AttachmentFolderPicker(pdf, failed = true)))

        composeRule.onNodeWithTag(MailAttachmentTags.PICKER_FAILED).assertTextEquals("The folders could not be loaded.")
        composeRule.onNodeWithTag(MailAttachmentTags.PICKER_EMPTY).assertDoesNotExist()
    }

    @Test
    fun `a loading picker shows neither folders nor the empty text`() {
        show(MailAttachmentsUiState(picker = AttachmentFolderPicker(pdf, loading = true)))

        composeRule.onNodeWithTag(MailAttachmentTags.PICKER).assertExists()
        composeRule.onNodeWithTag(MailAttachmentTags.PICKER_EMPTY).assertDoesNotExist()
        composeRule.onNodeWithTag(MailAttachmentTags.PICKER_FAILED).assertDoesNotExist()
    }

    @Test
    fun `save here waits while an attachment action runs`() {
        show(MailAttachmentsUiState(working = "3", picker = AttachmentFolderPicker(pdf)))

        composeRule.onNodeWithTag(MailAttachmentTags.PICKER_SAVE).assertIsNotEnabled()
    }

    @Test
    fun `the picker follows the state it is given`() {
        var state by mutableStateOf(MailAttachmentsUiState(picker = AttachmentFolderPicker(pdf, loading = true)))
        composeRule.setContent {
            CockpitTheme {
                MailReadContent(
                    MailReadUiState(message = message),
                    MailReadCallbacks(),
                    attachments = state,
                    attachmentCallbacks = callbacks,
                    body = { _, modifier -> Box(modifier.testTag(MailReadTags.BODY)) },
                )
            }
        }

        state = state.copy(picker = AttachmentFolderPicker(pdf, folders = listOf(DriveEntry.Folder(id(20), "Taxes", null))))
        composeRule.onNodeWithTag(MailAttachmentTags.folder("Taxes")).assert(hasClickAction())

        state = state.copy(picker = null)
        composeRule.onNodeWithTag(MailAttachmentTags.PICKER).assertDoesNotExist()
    }

    // Icons

    @Test
    fun `the type icon follows the content type, then the file extension`() {
        fun icon(
            name: String,
            type: String? = null,
        ) = attachmentIcon(MailAttachment("1", name, null, type))

        assertEquals(Icons.Outlined.Photo, icon("scan", "image/heic"))
        assertEquals(Icons.Outlined.Photo, icon("holiday.JPG"))
        assertEquals(Icons.Outlined.PictureAsPdf, icon("x", "Application/PDF; name=x"))
        assertEquals(Icons.Outlined.PictureAsPdf, icon("invoice.pdf", "application/octet-stream"))
        assertEquals(Icons.Outlined.AudioFile, icon("memo.m4a"))
        assertEquals(Icons.Outlined.AudioFile, icon("x", "audio/mpeg"))
        assertEquals(Icons.Outlined.VideoFile, icon("clip.mov"))
        assertEquals(Icons.Outlined.FolderZip, icon("x", "application/x-zip-compressed"))
        assertEquals(Icons.Outlined.FolderZip, icon("backup.tar"))
        assertEquals(Icons.Outlined.Description, icon("letter.docx"))
        assertEquals(Icons.Outlined.Description, icon("x", "text/csv"))
        assertEquals(Icons.AutoMirrored.Outlined.InsertDriveFile, icon("data.bin", "application/octet-stream"))
        assertEquals(Icons.AutoMirrored.Outlined.InsertDriveFile, icon("README"))
    }
}
