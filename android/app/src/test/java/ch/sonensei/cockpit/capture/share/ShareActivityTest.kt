package ch.sonensei.cockpit.capture.share

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import ch.sonensei.cockpit.capture.queue.CaptureQueueBindings
import ch.sonensei.cockpit.capture.queue.CaptureQueueDao
import ch.sonensei.cockpit.capture.queue.CaptureSyncScheduler
import ch.sonensei.cockpit.capture.queue.PendingCapture
import ch.sonensei.cockpit.capture.queue.RecordingScheduler
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import dagger.hilt.android.testing.UninstallModules
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import javax.inject.Inject

/** The real share activity and Hilt graph (queue with its Room database), with the upload scheduler recorded. */
@HiltAndroidTest
@UninstallModules(CaptureQueueBindings::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
class ShareActivityTest {
    private val hilt = HiltAndroidRule(this)
    private val composeRule = createEmptyComposeRule()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(hilt).around(composeRule)

    @BindValue
    @JvmField
    val scheduler: CaptureSyncScheduler = RecordingScheduler()

    @Inject lateinit var dao: CaptureQueueDao

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var scenario: ActivityScenario<ShareActivity>? = null

    @Before
    fun setUp() {
        hilt.inject()
        TestFilesProvider.dir = File(context.cacheDir, "shared-${System.nanoTime()}").apply { mkdirs() }
        Robolectric.setupContentProvider(TestFilesProvider::class.java, TestFilesProvider.AUTHORITY)
    }

    @After
    fun tearDown() {
        scenario?.close()
        TestFilesProvider.dir.deleteRecursively()
    }

    private fun launch(intent: Intent): ActivityScenario<ShareActivity> =
        ActivityScenario.launch<ShareActivity>(intent.setClass(context, ShareActivity::class.java)).also { scenario = it }

    private fun queued(): List<PendingCapture> = runBlocking { dao.sendable() }

    private fun ActivityScenario<ShareActivity>.closing(): Boolean {
        if (state == Lifecycle.State.DESTROYED) return true
        var finishing = false
        onActivity { finishing = it.isFinishing }
        return finishing
    }

    private fun awaitClosed(scenario: ActivityScenario<ShareActivity>) {
        composeRule.waitUntil(5_000) { scenario.closing() }
    }

    private fun stateOf(scenario: ActivityScenario<ShareActivity>): ShareUiState {
        lateinit var state: ShareUiState
        scenario.onActivity { state = ViewModelProvider(it)[ShareViewModel::class.java].uiState.value }
        return state
    }

    @Test
    fun `shared text is edited in the sheet and queued`() {
        val scenario = launch(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "Call plumber\nkitchen sink"))

        composeRule.onNodeWithTag(ShareTags.TEXT).assertTextEquals("Call plumber")
        composeRule.onNodeWithTag(ShareTags.NOTES).assertTextEquals("kitchen sink")
        composeRule.onNodeWithTag(ShareTags.TEXT).performTextReplacement("Call plumber @phone")
        composeRule.onNodeWithTag(ShareTags.SAVE).performClick()
        awaitClosed(scenario)

        val capture = queued().single()
        assertEquals("Call plumber @phone", capture.capture.text)
        assertEquals("kitchen sink", capture.capture.notes)
        assertTrue(capture.attachments.isEmpty())
        assertEquals(1, (scheduler as RecordingScheduler).scheduled)
    }

    @Test
    fun `a shared URL is queued with the page title`() {
        val scenario =
            launch(
                Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, "Holiday ideas")
                    .putExtra(Intent.EXTRA_TEXT, "https://example.org/holiday"),
            )

        composeRule.onNodeWithTag(ShareTags.SAVE).performClick()
        awaitClosed(scenario)

        val capture = queued().single().capture
        assertEquals("Holiday ideas", capture.text)
        assertEquals("https://example.org/holiday", capture.notes)
    }

    @Test
    fun `multiple shared images are copied into the queue before the sheet closes`() {
        val images = listOf(TestFilesProvider.share("beach.jpg", "beach"), TestFilesProvider.share("dunes.png", "dunes"))
        val scenario =
            launch(
                Intent(Intent.ACTION_SEND_MULTIPLE)
                    .setType("image/*")
                    .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList<Uri>(images))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            )

        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithTag(ShareTags.FILE).fetchSemanticsNodes().size == 2 }
        composeRule.onNodeWithText("beach.jpg").assertExists()
        composeRule.onNodeWithTag(ShareTags.TEXT).assertTextEquals("beach.jpg, dunes.png")
        composeRule.onNodeWithTag(ShareTags.SAVE).performClick()
        awaitClosed(scenario)

        // The sending app may delete its files (or our grant ends) once the sheet is gone: the queue has copies.
        TestFilesProvider.dir.deleteRecursively()
        val attachments = queued().single().attachments
        assertEquals(listOf("beach.jpg", "dunes.png"), attachments.map { it.name })
        assertEquals(listOf("image/jpeg", "image/png"), attachments.map { it.mime })
        assertEquals(listOf("beach", "dunes"), attachments.map { File(it.localPath).readText() })
    }

    @Test
    fun `cancel closes the sheet without queueing`() {
        val scenario = launch(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "Maybe later"))

        composeRule.onNodeWithTag(ShareTags.CANCEL).performClick()
        awaitClosed(scenario)

        assertTrue(queued().isEmpty())
    }

    @Test
    fun `a blank title cannot be saved`() {
        launch(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "Something"))

        composeRule.onNodeWithTag(ShareTags.TEXT).performTextClearance()

        composeRule.onNodeWithTag(ShareTags.SAVE).assertIsNotEnabled()
        assertTrue(queued().isEmpty())
    }

    @Test
    fun `an unreadable file keeps the sheet open with an error and queues nothing`() {
        val gone = Uri.parse("content://${TestFilesProvider.AUTHORITY}/deleted.pdf")
        val scenario =
            launch(
                Intent(
                    Intent.ACTION_SEND,
                ).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, gone).putExtra(Intent.EXTRA_TEXT, "Invoice"),
            )

        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithTag(ShareTags.FILE).fetchSemanticsNodes().size == 1 }
        composeRule.onNodeWithTag(ShareTags.SAVE).performClick()
        composeRule.waitUntil(5_000) { !stateOf(scenario).saving }

        assertEquals(ShareError.UNREADABLE_FILE, stateOf(scenario).error)
        composeRule.onNodeWithTag(ShareTags.ERROR).assertExists()
        assertFalse(scenario.closing())
        assertTrue(queued().isEmpty())
    }

    @Test
    fun `a single file of any type is copied with its caption`() {
        val pdf = TestFilesProvider.share("contract.pdf", "terms")
        val scenario =
            launch(
                Intent(Intent.ACTION_SEND)
                    .setType("application/pdf")
                    .putExtra(Intent.EXTRA_STREAM, pdf)
                    .putExtra(Intent.EXTRA_TEXT, "Sign contract")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            )

        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithTag(ShareTags.FILE).fetchSemanticsNodes().size == 1 }
        composeRule.onNodeWithTag(ShareTags.SAVE).performClick()
        awaitClosed(scenario)

        TestFilesProvider.dir.deleteRecursively()
        val pending = queued().single()
        assertEquals("Sign contract", pending.capture.text)
        val attachment = pending.attachments.single()
        assertEquals("contract.pdf", attachment.name)
        assertEquals("terms", File(attachment.localPath).readText())
    }

    @Test
    fun `too many files keep the sheet open with an error and queue nothing`() {
        val files = (1..51).map { TestFilesProvider.share("photo-$it.jpg", "p$it") }
        val scenario =
            launch(
                Intent(Intent.ACTION_SEND_MULTIPLE)
                    .setType("image/*")
                    .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList<Uri>(files)),
            )

        composeRule.waitUntil(5_000) { !stateOf(scenario).loadingFiles }
        composeRule.onNodeWithTag(ShareTags.SAVE).performScrollTo().performClick()

        composeRule.onNodeWithTag(ShareTags.ERROR).performScrollTo().assertTextEquals("Too many files. Share at most 50 at once.")
        assertFalse(scenario.closing())
        assertTrue(queued().isEmpty())
    }

    @Test
    fun `a tap outside the sheet closes it without queueing`() {
        val scenario = launch(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "Not now"))

        composeRule.onNodeWithTag(ShareTags.TEXT).assertExists()
        composeRule.onRoot().performTouchInput { click(Offset(10f, 10f)) }
        awaitClosed(scenario)

        assertTrue(queued().isEmpty())
    }

    @Test
    fun `edits survive a configuration change`() {
        val scenario = launch(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "Draft"))
        composeRule.onNodeWithTag(ShareTags.TEXT).performTextReplacement("Draft, edited")

        scenario.recreate()

        composeRule.onNodeWithTag(ShareTags.TEXT).assertTextEquals("Draft, edited")
        assertTrue(queued().isEmpty())
    }

    @Test
    fun `a share without content closes at once`() {
        val scenario = launch(Intent(Intent.ACTION_SEND).setType("text/plain"))

        awaitClosed(scenario)
        assertTrue(queued().isEmpty())
    }
}
