package ch.sonensei.cockpit.mail.read

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.files.FilesRepository
import ch.sonensei.cockpit.files.FilesResult
import ch.sonensei.cockpit.files.OpenedFiles
import ch.sonensei.cockpit.files.PickedFilesProvider
import ch.sonensei.cockpit.files.resetFileProviderCache
import ch.sonensei.cockpit.mail.list.MailRepository
import ch.sonensei.cockpit.mail.list.MailResult
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.io.OutputStream
import java.time.Instant
import java.util.UUID

/**
 * The read route with a real attachments view model and a fake server: open hands the downloaded file to another app
 * through the FileProvider, "Save to device" goes through the system "save as" picker, preview and "Save to Files" run
 * end to end in the screen.
 */
@RunWith(AndroidJUnit4::class)
class MailAttachmentsRouteTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val pdf = MailAttachment("2", "report.pdf", 1024, "application/pdf")
    private val generic = MailAttachment("3", "scan.pdf", 2048, "application/octet-stream")
    private val untyped = MailAttachment("5", "notes.qqx1", null, null)

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
            attachments = listOf(pdf, generic, untyped),
        )

    private val reader = mockk<MailReadRepository>()
    private val mail = mockk<MailRepository>()
    private val attachments = mockk<MailAttachmentRepository>()
    private val files = mockk<FilesRepository>()

    private val activity get() = composeRule.activity

    @Before
    fun setUp() {
        resetFileProviderCache()
        coEvery { reader.message(id(1)) } returns MailResult.Ok(message)
        coEvery { mail.markRead(any()) } returns MailResult.Ok(true)
    }

    private fun show() {
        val vm = readViewModel(reader, mail)
        val attachmentsVm = MailAttachmentsViewModel(attachments, files)
        composeRule.setContent {
            CockpitTheme {
                MailReadRoute(id = id(1), onClose = {}, viewModel = vm, attachmentsViewModel = attachmentsVm)
            }
        }
        waitForTag(MailAttachmentTags.row(pdf.partId))
    }

    private fun waitForTag(tag: String) =
        composeRule.waitUntil(WAIT_MS) { composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    /** The download writes [content] into whatever target the screen gave it, like the real repository does. */
    private fun downloadWrites(
        content: String,
        result: MailResult<Long>? = null,
    ) = coEvery { attachments.download(id(1), any(), any()) } coAnswers {
        val bytes = content.toByteArray()
        thirdArg<() -> OutputStream?>()()!!.use { it.write(bytes) }
        result ?: MailResult.Ok(bytes.size.toLong())
    }

    @Test
    fun `tapping an attachment downloads it and opens it in another app with a read grant`() {
        downloadWrites("fictitious quarterly figures")
        show()

        composeRule.onNodeWithTag(MailAttachmentTags.row(pdf.partId)).performClick()
        composeRule.waitUntil(WAIT_MS) { shadowOf(activity).peekNextStartedActivity() != null }

        val started = shadowOf(activity).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals("application/pdf", started.type)
        assertEquals(OpenedFiles.authority(activity), started.data!!.authority)
        assertTrue(started.data!!.path!!.endsWith("/report.pdf"))
        assertTrue("no read grant", started.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        val read = activity.contentResolver.openInputStream(started.data!!)!!.use { String(it.readBytes()) }
        assertEquals("fictitious quarterly figures", read)
    }

    @Test
    fun `a generic content type is opened with the type its extension stands for`() {
        shadowOf(MimeTypeMap.getSingleton()).addExtensionMimeTypeMapping("pdf", "application/pdf")
        downloadWrites("fictitious scan")
        show()

        composeRule.onNodeWithTag(MailAttachmentTags.row(generic.partId)).performClick()
        composeRule.waitUntil(WAIT_MS) { shadowOf(activity).peekNextStartedActivity() != null }

        assertEquals("application/pdf", shadowOf(activity).nextStartedActivity.type)
    }

    @Test
    fun `an attachment without a type and with an unknown extension is opened as generic data`() {
        downloadWrites("fictitious notes")
        show()

        composeRule.onNodeWithTag(MailAttachmentTags.row(untyped.partId)).performScrollTo().performClick()
        composeRule.waitUntil(WAIT_MS) { shadowOf(activity).peekNextStartedActivity() != null }

        val started = shadowOf(activity).nextStartedActivity
        assertEquals("application/octet-stream", started.type)
        assertTrue(started.data!!.path!!.endsWith("/notes.qqx1"))
    }

    @Test
    fun `a pending save of an attachment without size or type survives a state restore`() {
        PickedFilesProvider.register()
        downloadWrites("fictitious notes")
        val restoration = StateRestorationTester(composeRule)
        val vm = readViewModel(reader, mail)
        val attachmentsVm = MailAttachmentsViewModel(attachments, files)
        restoration.setContent {
            CockpitTheme {
                MailReadRoute(id = id(1), onClose = {}, viewModel = vm, attachmentsViewModel = attachmentsVm)
            }
        }
        waitForTag(MailAttachmentTags.row(untyped.partId))
        composeRule.onNodeWithTag(MailAttachmentTags.more(untyped.partId)).performScrollTo().performClick()
        composeRule.onNodeWithTag(MailAttachmentTags.saveDevice(untyped.partId)).performClick()
        composeRule.waitForIdle()
        val request = shadowOf(activity).nextStartedActivityForResult
        assertEquals("notes.qqx1", request.intent.getStringExtra(Intent.EXTRA_TITLE))

        restoration.emulateSavedInstanceStateRestore()
        waitForTag(MailAttachmentTags.row(untyped.partId))
        val picked = Intent().setData(PickedFilesProvider.uri("restored-notes.qqx1"))
        shadowOf(activity).receiveResult(request.intent, Activity.RESULT_OK, picked)
        waitForTag(MailAttachmentTags.NOTICE)

        composeRule.onNodeWithTag(MailAttachmentTags.NOTICE).assertTextEquals("notes.qqx1 was saved.")
        assertEquals("fictitious notes", PickedFilesProvider.file(activity, "restored-notes.qqx1").readText())
        coVerify { attachments.download(id(1), untyped.partId, any()) }
    }

    @Test
    fun `an attachment no app opens says so`() {
        shadowOf(ApplicationProvider.getApplicationContext<Application>()).checkActivities(true)
        downloadWrites("fictitious quarterly figures")
        show()

        composeRule.onNodeWithTag(MailAttachmentTags.row(pdf.partId)).performClick()
        waitForTag(MailAttachmentTags.NOTICE)

        composeRule.onNodeWithTag(MailAttachmentTags.NOTICE).assertTextEquals("No app on this device opens this attachment.")
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test
    fun `save to device asks where, writes the attachment there and says so`() {
        PickedFilesProvider.register()
        downloadWrites("fictitious quarterly figures")
        show()

        composeRule.onNodeWithTag(MailAttachmentTags.more(pdf.partId)).performClick()
        composeRule.onNodeWithTag(MailAttachmentTags.saveDevice(pdf.partId)).performClick()
        composeRule.waitForIdle()

        val request = shadowOf(activity).nextStartedActivityForResult
        assertNotNull("the save-as picker was not started", request)
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, request.intent.action)
        assertEquals("report.pdf", request.intent.getStringExtra(Intent.EXTRA_TITLE))

        shadowOf(activity).receiveResult(request.intent, Activity.RESULT_OK, Intent().setData(PickedFilesProvider.uri("saved-report.pdf")))
        waitForTag(MailAttachmentTags.NOTICE)

        composeRule.onNodeWithTag(MailAttachmentTags.NOTICE).assertTextEquals("report.pdf was saved.")
        assertEquals("fictitious quarterly figures", PickedFilesProvider.file(activity, "saved-report.pdf").readText())
    }

    @Test
    fun `a save still arrives when the screen state was restored while the save-as picker was open`() {
        PickedFilesProvider.register()
        downloadWrites("fictitious quarterly figures")
        val restoration = StateRestorationTester(composeRule)
        val vm = readViewModel(reader, mail)
        val attachmentsVm = MailAttachmentsViewModel(attachments, files)
        restoration.setContent {
            CockpitTheme {
                MailReadRoute(id = id(1), onClose = {}, viewModel = vm, attachmentsViewModel = attachmentsVm)
            }
        }
        waitForTag(MailAttachmentTags.row(generic.partId))
        composeRule.onNodeWithTag(MailAttachmentTags.more(generic.partId)).performClick()
        composeRule.onNodeWithTag(MailAttachmentTags.saveDevice(generic.partId)).performClick()
        composeRule.waitForIdle()
        val request = shadowOf(activity).nextStartedActivityForResult

        restoration.emulateSavedInstanceStateRestore()
        waitForTag(MailAttachmentTags.row(generic.partId))
        shadowOf(activity).receiveResult(request.intent, Activity.RESULT_OK, Intent().setData(PickedFilesProvider.uri("restored-scan.pdf")))
        waitForTag(MailAttachmentTags.NOTICE)

        composeRule.onNodeWithTag(MailAttachmentTags.NOTICE).assertTextEquals("scan.pdf was saved.")
        assertEquals("fictitious quarterly figures", PickedFilesProvider.file(activity, "restored-scan.pdf").readText())
        coVerify { attachments.download(id(1), generic.partId, any()) }
    }

    @Test
    fun `a failed save to device deletes the document the save-as picker created`() {
        PickedFilesProvider.register()
        downloadWrites("cut", MailResult.Offline)
        val created = PickedFilesProvider.file(activity, "half-report.pdf").apply { writeText("") }
        show()

        composeRule.onNodeWithTag(MailAttachmentTags.more(pdf.partId)).performClick()
        composeRule.onNodeWithTag(MailAttachmentTags.saveDevice(pdf.partId)).performClick()
        composeRule.waitForIdle()
        val request = shadowOf(activity).nextStartedActivityForResult
        shadowOf(activity).receiveResult(request.intent, Activity.RESULT_OK, Intent().setData(PickedFilesProvider.uri("half-report.pdf")))
        waitForTag(MailAttachmentTags.NOTICE)
        composeRule.waitUntil(WAIT_MS) { !created.exists() }

        composeRule
            .onNodeWithTag(MailAttachmentTags.NOTICE)
            .assertTextEquals("The attachment could not be saved. Can't reach the server.")
    }

    @Test
    fun `a cancelled save-as picker downloads nothing`() {
        show()

        composeRule.onNodeWithTag(MailAttachmentTags.more(pdf.partId)).performClick()
        composeRule.onNodeWithTag(MailAttachmentTags.saveDevice(pdf.partId)).performClick()
        composeRule.waitForIdle()
        val request = shadowOf(activity).nextStartedActivityForResult
        shadowOf(activity).receiveResult(request.intent, Activity.RESULT_CANCELED, null)
        composeRule.waitForIdle()

        coVerify(exactly = 0) { attachments.download(any(), any(), any()) }
        composeRule.onNodeWithTag(MailAttachmentTags.NOTICE).assertDoesNotExist()
    }

    @Test
    fun `the preview button downloads the attachment and opens the in-app preview`() {
        downloadWrites("not a real pdf")
        show()

        composeRule.onNodeWithTag(MailAttachmentTags.preview(pdf.partId)).performClick()
        waitForTag(MailAttachmentTags.PREVIEW_DIALOG)

        composeRule.onNodeWithTag(MailAttachmentTags.PREVIEW_CLOSE).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(MailAttachmentTags.PREVIEW_DIALOG).assertDoesNotExist()
    }

    @Test
    fun `open from the preview hands the previewed file to another app without downloading it again`() {
        downloadWrites("fictitious preview figures")
        show()

        composeRule.onNodeWithTag(MailAttachmentTags.preview(pdf.partId)).performClick()
        waitForTag(MailAttachmentTags.PREVIEW_DIALOG)
        composeRule.onNodeWithTag(MailAttachmentTags.PREVIEW_OPEN).performClick()
        composeRule.waitUntil(WAIT_MS) { shadowOf(activity).peekNextStartedActivity() != null }

        val started = shadowOf(activity).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals("application/pdf", started.type)
        assertTrue(started.data!!.path!!.endsWith("/report.pdf"))
        val read = activity.contentResolver.openInputStream(started.data!!)!!.use { String(it.readBytes()) }
        assertEquals("fictitious preview figures", read)
        composeRule.onNodeWithTag(MailAttachmentTags.PREVIEW_DIALOG).assertDoesNotExist()
        coVerify(exactly = 1) { attachments.download(id(1), pdf.partId, any()) }
    }

    @Test
    fun `save to Files picks a folder and stores the attachment there`() {
        coEvery { files.list(null) } returns FilesResult.Ok(emptyList())
        coEvery { attachments.saveToFiles(id(1), pdf.partId, null) } returns
            MailResult.Ok(SavedAttachment(id(10), "report.pdf", id(50)))
        show()

        composeRule.onNodeWithTag(MailAttachmentTags.more(pdf.partId)).performClick()
        composeRule.onNodeWithTag(MailAttachmentTags.saveFiles(pdf.partId)).performClick()
        waitForTag(MailAttachmentTags.PICKER_EMPTY)
        composeRule.onNodeWithTag(MailAttachmentTags.PICKER_SAVE).performClick()
        waitForTag(MailAttachmentTags.NOTICE)

        composeRule.onNodeWithTag(MailAttachmentTags.NOTICE).assertTextEquals("report.pdf was saved to Files in /Attachments/Mail.")
        composeRule.onNodeWithTag(MailAttachmentTags.PICKER).assertDoesNotExist()
    }

    private companion object {
        const val WAIT_MS = 5_000L
    }
}
