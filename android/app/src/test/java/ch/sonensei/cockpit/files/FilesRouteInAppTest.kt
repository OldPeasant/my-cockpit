package ch.sonensei.cockpit.files

import android.app.Activity
import android.app.Application
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import ch.sonensei.cockpit.MainActivity
import ch.sonensei.cockpit.auth.PersistentTokenStore
import ch.sonensei.cockpit.core.network.AuthTokens
import ch.sonensei.cockpit.ui.navigation.CockpitDestination
import ch.sonensei.cockpit.ui.screenTag
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import java.io.OutputStream
import java.time.Instant
import java.util.UUID
import javax.inject.Inject

/**
 * The Files route in the Hilt activity with a fake repository: browsing into a folder and back with the system back,
 * opening a file in another app through this app's FileProvider, saving one with the system "save as" picker and
 * uploading files picked with the system picker.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
class FilesRouteInAppTest {
    private val hilt = HiltAndroidRule(this)
    private val composeRule = createEmptyComposeRule()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(hilt).around(composeRule)

    @BindValue
    @JvmField
    val repo: FilesRepository = mockk()

    @Inject lateinit var tokenStore: PersistentTokenStore

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val docs = DriveEntry.Folder(id(1), "Documents", null)
    private val pdf = DriveEntry.File(id(2), "Steuern 2025.pdf", "application/pdf", 1536, Instant.parse("2026-10-01T09:00:00Z"))
    private val inner = DriveEntry.File(id(3), "Mietvertrag.txt", "text/plain; charset=utf-8", 12, Instant.parse("2026-09-01T09:00:00Z"))

    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun setUp() {
        hilt.inject()
        resetFileProviderCache()
        WorkManagerTestInitHelper.initializeTestWorkManager(ApplicationProvider.getApplicationContext())
        tokenStore.login(AuthTokens("access-fictitious", "refresh-fictitious"), null)
        coEvery { repo.list(null) } returns FilesResult.Ok(listOf(docs, pdf))
        coEvery { repo.list(docs.id) } returns FilesResult.Ok(listOf(inner))
    }

    @After
    fun tearDown() {
        scenario?.close()
    }

    private fun activity(): Activity {
        var activity: Activity? = null
        scenario!!.onActivity { activity = it }
        return activity!!
    }

    private fun waitForTag(tag: String) =
        composeRule.waitUntil(WAIT_MS) { composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    private fun waitForText(text: String) =
        composeRule.waitUntil(WAIT_MS) { composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    private fun openFiles() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.onNode(hasText("More") and isSelectable()).performClick()
        composeRule.onNodeWithTag(screenTag(CockpitDestination.More)).performScrollToNode(hasText("Files"))
        composeRule.onNodeWithText("Files").performClick()
        waitForTag(FilesTags.entry(pdf.id))
    }

    /** The download writes [content] into whatever target the screen gave it, like the real repository does. */
    private fun downloadWrites(content: String) {
        coEvery { repo.download(pdf.id, any()) } coAnswers {
            val target = secondArg<() -> OutputStream?>()
            val bytes = content.toByteArray()
            target()!!.use { it.write(bytes) }
            FilesResult.Ok(bytes.size.toLong())
        }
    }

    @Test
    fun `the Files screen is the real drive, not the placeholder`() {
        openFiles()
        composeRule.onNodeWithTag(screenTag(CockpitDestination.Files)).assertExists()
        composeRule.onNodeWithText("This part of your cockpit is not built yet.").assertDoesNotExist()
        composeRule.onNodeWithTag(FilesTags.entry(docs.id)).assertExists()
        coVerify { repo.list(null) }
    }

    @Test
    fun `a folder opens, and the system back goes up to the drive root, still on the Files screen`() {
        openFiles()
        composeRule.onNodeWithTag(FilesTags.entry(docs.id)).performClick()
        waitForTag(FilesTags.entry(inner.id))
        composeRule.onNodeWithTag(FilesTags.crumb(1)).assertExists()
        composeRule.onNodeWithTag(FilesTags.entry(pdf.id)).assertDoesNotExist()
        coVerify(exactly = 1) { repo.list(docs.id) }

        composeRule.runOnUiThread { (activity() as ComponentActivity).onBackPressedDispatcher.onBackPressed() }

        waitForTag(FilesTags.entry(pdf.id))
        composeRule.onNodeWithTag(FilesTags.crumb(1)).assertDoesNotExist()
        composeRule.onNodeWithTag(FilesTags.entry(inner.id)).assertDoesNotExist()
        composeRule.onNodeWithTag(screenTag(CockpitDestination.Files)).assertExists()
        coVerify(atLeast = 2) { repo.list(null) }
    }

    @Test
    fun `tapping a file downloads it and opens it in another app through this app's FileProvider with a read grant`() {
        downloadWrites("fictitious tax return")
        openFiles()

        composeRule.onNodeWithTag(FilesTags.entry(pdf.id)).performClick()
        composeRule.waitUntil(WAIT_MS) { shadowOf(activity()).peekNextStartedActivity() != null }

        val started = shadowOf(activity()).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals("application/pdf", started.type)
        assertEquals("content", started.data!!.scheme)
        assertEquals("${activity().packageName}.files", started.data!!.authority)
        assertTrue(started.data!!.path!!.endsWith("/Steuern 2025.pdf"))
        assertTrue("no read grant", started.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        // The other app reads what was downloaded.
        val read = activity().contentResolver.openInputStream(started.data!!)!!.use { String(it.readBytes()) }
        assertEquals("fictitious tax return", read)
        coVerify(exactly = 1) { repo.download(pdf.id, any()) }
    }

    @Test
    fun `tapping another file while one downloads leaves the first intact, and it still opens`() {
        val other = DriveEntry.File(id(4), "Quittung.pdf", "application/pdf", 10, Instant.parse("2026-10-02T09:00:00Z"))
        coEvery { repo.list(null) } returns FilesResult.Ok(listOf(docs, pdf, other))
        val gate = CompletableDeferred<Unit>()
        coEvery { repo.download(pdf.id, any()) } coAnswers {
            secondArg<() -> OutputStream?>()()!!.use { it.write("fictitious tax return".toByteArray()) }
            gate.await()
            FilesResult.Ok(21L)
        }
        openFiles()

        composeRule.onNodeWithTag(FilesTags.entry(pdf.id)).performClick()
        waitForTag(FilesTags.DOWNLOADING)
        composeRule.onNodeWithTag(FilesTags.entry(other.id)).performClick()
        composeRule.waitForIdle()
        gate.complete(Unit)
        composeRule.waitUntil(WAIT_MS) { shadowOf(activity()).peekNextStartedActivity() != null }

        val started = shadowOf(activity()).nextStartedActivity
        assertTrue(started.data!!.path!!.endsWith("/Steuern 2025.pdf"))
        val read = activity().contentResolver.openInputStream(started.data!!)!!.use { String(it.readBytes()) }
        assertEquals("fictitious tax return", read)
        coVerify(exactly = 0) { repo.download(other.id, any()) }
    }

    @Test
    fun `a file no app on the phone opens says so and starts nothing`() {
        downloadWrites("fictitious")
        openFiles()
        shadowOf(ApplicationProvider.getApplicationContext<Application>()).checkActivities(true)

        composeRule.onNodeWithTag(FilesTags.entry(pdf.id)).performClick()
        composeRule.waitUntil(WAIT_MS) { ShadowToast.getTextOfLatestToast() != null }

        assertEquals("No app on this phone opens this file", ShadowToast.getTextOfLatestToast())
        assertNull(shadowOf(activity()).nextStartedActivity)
        composeRule.onNodeWithTag(FilesTags.entry(pdf.id)).assertExists()
    }

    @Test
    fun `a failed download says so and opens nothing`() {
        coEvery { repo.download(pdf.id, any()) } returns FilesResult.Offline
        openFiles()

        composeRule.onNodeWithTag(FilesTags.entry(pdf.id)).performClick()
        waitForTag(FilesTags.DOWNLOAD_MESSAGE)

        composeRule.onNodeWithText("Offline. The file could not be downloaded.").assertExists()
        assertNull(shadowOf(activity()).nextStartedActivity)
    }

    @Test
    fun `save to device asks where, then writes the file there and says so`() {
        PickedFilesProvider.register()
        downloadWrites("fictitious tax return")
        openFiles()

        composeRule.onNodeWithTag(FilesTags.more(pdf.id)).performClick()
        composeRule.onNodeWithTag(FilesTags.SAVE).performClick()
        composeRule.waitForIdle()

        val request = shadowOf(activity()).nextStartedActivityForResult
        assertNotNull("the save-as picker was not started", request)
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, request.intent.action)
        assertEquals("Steuern 2025.pdf", request.intent.getStringExtra(Intent.EXTRA_TITLE))

        val chosen = PickedFilesProvider.uri("saved-tax.pdf")
        shadowOf(activity()).receiveResult(request.intent, Activity.RESULT_OK, Intent().setData(chosen))
        composeRule.waitUntil(WAIT_MS) { ShadowToast.getTextOfLatestToast() != null }

        assertEquals("Steuern 2025.pdf saved", ShadowToast.getTextOfLatestToast())
        assertEquals("fictitious tax return", PickedFilesProvider.file(activity(), "saved-tax.pdf").readText())
    }

    @Test
    fun `a save still arrives when the activity was recreated while the save-as picker was open`() {
        PickedFilesProvider.register()
        downloadWrites("fictitious tax return")
        openFiles()
        composeRule.onNodeWithTag(FilesTags.more(pdf.id)).performClick()
        composeRule.onNodeWithTag(FilesTags.SAVE).performClick()
        composeRule.waitForIdle()
        val request = shadowOf(activity()).nextStartedActivityForResult

        scenario!!.recreate()
        waitForTag(FilesTags.entry(pdf.id))
        val chosen = PickedFilesProvider.uri("saved-after-rotation.pdf")
        composeRule.runOnUiThread {
            (activity() as ComponentActivity).activityResultRegistry.dispatchResult(
                request.requestCode,
                Activity.RESULT_OK,
                Intent().setData(chosen),
            )
        }
        composeRule.waitUntil(WAIT_MS) { ShadowToast.getTextOfLatestToast() != null }

        assertEquals("Steuern 2025.pdf saved", ShadowToast.getTextOfLatestToast())
        assertEquals("fictitious tax return", PickedFilesProvider.file(activity(), "saved-after-rotation.pdf").readText())
    }

    @Test
    fun `a failed save deletes the document the save-as picker created`() {
        PickedFilesProvider.register()
        coEvery { repo.download(pdf.id, any()) } coAnswers {
            secondArg<() -> OutputStream?>()()!!.use { it.write("cut".toByteArray()) }
            FilesResult.Offline
        }
        val created = PickedFilesProvider.file(ApplicationProvider.getApplicationContext(), "half.pdf").apply { writeText("") }
        openFiles()
        composeRule.onNodeWithTag(FilesTags.more(pdf.id)).performClick()
        composeRule.onNodeWithTag(FilesTags.SAVE).performClick()
        composeRule.waitForIdle()
        val request = shadowOf(activity()).nextStartedActivityForResult

        shadowOf(activity()).receiveResult(request.intent, Activity.RESULT_OK, Intent().setData(PickedFilesProvider.uri("half.pdf")))
        waitForTag(FilesTags.DOWNLOAD_MESSAGE)
        composeRule.waitUntil(WAIT_MS) { !created.exists() }

        composeRule.onNodeWithText("Offline. The file could not be downloaded.").assertExists()
    }

    @Test
    fun `a cancelled save-as picker downloads nothing`() {
        openFiles()
        composeRule.onNodeWithTag(FilesTags.more(pdf.id)).performClick()
        composeRule.onNodeWithTag(FilesTags.SAVE).performClick()
        composeRule.waitForIdle()
        val request = shadowOf(activity()).nextStartedActivityForResult

        shadowOf(activity()).receiveResult(request.intent, Activity.RESULT_CANCELED, null)
        composeRule.waitForIdle()

        coVerify(exactly = 0) { repo.download(any(), any()) }
    }

    @Test
    fun `files picked on the device are uploaded into the folder shown, which then reloads`() {
        PickedFilesProvider.register()
        PickedFilesProvider.file(ApplicationProvider.getApplicationContext(), PickedFilesProvider.REISE).writeText("fictitious plan")
        val uploaded = DriveEntry.File(id(9), "Reise.pdf", "application/pdf", 15, Instant.parse("2026-10-22T10:00:00Z"))
        var sentBytes: String? = null
        coEvery { repo.upload(docs.id, any()) } coAnswers {
            sentBytes = secondArg<UploadSource>().open()!!.use { String(it.readBytes()) }
            FilesResult.Ok(uploaded)
        }
        openFiles()
        composeRule.onNodeWithTag(FilesTags.entry(docs.id)).performClick()
        waitForTag(FilesTags.entry(inner.id))

        composeRule.onNodeWithTag(FilesTags.UPLOAD).performClick()
        composeRule.waitForIdle()
        val request = shadowOf(activity()).nextStartedActivityForResult
        assertNotNull("the file picker was not started", request)
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, request.intent.action)
        assertTrue("several files can be picked", request.intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false))

        shadowOf(activity()).receiveResult(
            request.intent,
            Activity.RESULT_OK,
            Intent().setData(PickedFilesProvider.uri(PickedFilesProvider.REISE)),
        )
        waitForText("Uploaded")

        coVerify(exactly = 1) {
            repo.upload(docs.id, match { it.name == "Reise.pdf" && it.mime == "application/pdf" && it.sizeBytes == 2048L })
        }
        assertEquals("fictitious plan", sentBytes)
        coVerify(atLeast = 2) { repo.list(docs.id) }
    }

    @Test
    fun `a failed upload shows why`() {
        PickedFilesProvider.register()
        coEvery { repo.upload(null, any()) } returns FilesResult.Failed(413)
        openFiles()

        composeRule.onNodeWithTag(FilesTags.UPLOAD).performClick()
        composeRule.waitForIdle()
        val request = shadowOf(activity()).nextStartedActivityForResult
        shadowOf(activity()).receiveResult(
            request.intent,
            Activity.RESULT_OK,
            Intent().setData(PickedFilesProvider.uri(PickedFilesProvider.NO_SIZE)),
        )

        waitForText("Too large for the drive")
        coVerify(exactly = 1) { repo.upload(null, match { it.name == "notes.txt" }) }
    }

    @Test
    fun `a cancelled file picker uploads nothing`() {
        openFiles()
        composeRule.onNodeWithTag(FilesTags.UPLOAD).performClick()
        composeRule.waitForIdle()
        val request = shadowOf(activity()).nextStartedActivityForResult

        shadowOf(activity()).receiveResult(request.intent, Activity.RESULT_CANCELED, null)
        composeRule.waitForIdle()

        coVerify(exactly = 0) { repo.upload(any(), any()) }
    }

    private companion object {
        const val WAIT_MS = 5_000L
    }
}
