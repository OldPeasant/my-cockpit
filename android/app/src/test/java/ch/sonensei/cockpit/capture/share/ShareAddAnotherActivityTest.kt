package ch.sonensei.cockpit.capture.share

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import ch.sonensei.cockpit.capture.queue.CaptureQueueBindings
import ch.sonensei.cockpit.capture.queue.CaptureQueueDao
import ch.sonensei.cockpit.capture.queue.CaptureSyncScheduler
import ch.sonensei.cockpit.capture.queue.PendingCapture
import ch.sonensei.cockpit.capture.queue.RecordingScheduler
import ch.sonensei.cockpit.gtd.data.CachedProjectEntity
import ch.sonensei.cockpit.gtd.data.GtdCacheDao
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import dagger.hilt.android.testing.UninstallModules
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import java.io.File
import javax.inject.Inject

/** "Save and add another" and the syntax preview in the real share activity, Hilt graph, queue and GTD cache. */
@HiltAndroidTest
@UninstallModules(CaptureQueueBindings::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
class ShareAddAnotherActivityTest {
    private val hilt = HiltAndroidRule(this)
    private val composeRule = createEmptyComposeRule()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(hilt).around(composeRule)

    @BindValue
    @JvmField
    val scheduler: CaptureSyncScheduler = RecordingScheduler()

    @Inject lateinit var dao: CaptureQueueDao

    @Inject lateinit var gtdCache: GtdCacheDao

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

    private fun stateOf(scenario: ActivityScenario<ShareActivity>): ShareUiState {
        lateinit var state: ShareUiState
        scenario.onActivity { state = ViewModelProvider(it)[ShareViewModel::class.java].uiState.value }
        return state
    }

    @Test
    fun `save and add another queues the share, keeps the sheet open and cleared, then a plain save closes it`() {
        val photo = TestFilesProvider.share("beach.jpg", "beach")
        val scenario =
            launch(
                Intent(Intent.ACTION_SEND)
                    .setType("image/jpeg")
                    .putExtra(Intent.EXTRA_TEXT, "Holiday photo")
                    .putExtra(Intent.EXTRA_STREAM, photo)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            )
        composeRule.waitUntil(5_000) { !stateOf(scenario).loadingFiles }
        awaitCount(ShareTags.FILE, 1)

        composeRule.onNodeWithTag(ShareTags.SAVE_ANOTHER).performScrollTo().performClick()
        composeRule.waitUntil(5_000) { stateOf(scenario).savedAndCleared == 1 }

        assertFalse(scenario.closing())
        composeRule.onNodeWithTag(ShareTags.TEXT).assertTextEquals("")
        awaitCount(ShareTags.FILE, 0)
        composeRule.waitUntil(5_000) { ShadowToast.getTextOfLatestToast() == "Saved to Inbox" }
        val first = queued().single()
        assertEquals("Holiday photo", first.capture.text)
        assertEquals(listOf("beach.jpg"), first.attachments.map { it.name })

        composeRule.onNodeWithTag(ShareTags.TEXT).performTextReplacement("Second thought")
        composeRule.onNodeWithTag(ShareTags.SAVE).performScrollTo().performClick()
        composeRule.waitUntil(5_000) { scenario.closing() }

        assertEquals(listOf("Holiday photo", "Second thought"), queued().map { it.capture.text }.sorted())
    }

    @Test
    fun `a project from the offline GTD cache is previewed as a chip while typing`() {
        runBlocking {
            gtdCache.replaceProjects(
                listOf(
                    CachedProjectEntity(
                        id = "00000000-0000-7000-8000-000000000002",
                        ordinal = 0,
                        title = "Home Renovation",
                        status = "ACTIVE",
                        stalled = false,
                        version = 1,
                        areaId = null,
                        nextTaskId = null,
                        dueDate = null,
                        reviewDate = null,
                        descriptionMd = null,
                    ),
                ),
                1L,
            )
        }
        launch(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "Buy tiles"))

        composeRule.onNodeWithTag(ShareTags.TEXT).performTextReplacement("Buy tiles #home-renovation")

        composeRule.waitUntil(5_000) {
            composeRule
                .onAllNodes(hasTestTag(ShareTags.PREVIEW_PROJECT) and hasAnyDescendant(hasText("Home Renovation")))
                .fetchSemanticsNodes()
                .size == 1
        }
        assertEquals(0, queued().size)
    }

    private fun awaitCount(
        tag: String,
        n: Int,
    ) = composeRule.waitUntil(5_000) { composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().size == n }
}
