package ch.sonensei.cockpit.capture.entry

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import ch.sonensei.cockpit.MainActivity
import ch.sonensei.cockpit.auth.PersistentTokenStore
import ch.sonensei.cockpit.capture.queue.CaptureQueue
import ch.sonensei.cockpit.capture.queue.CaptureQueueBindings
import ch.sonensei.cockpit.capture.queue.CaptureQueueDao
import ch.sonensei.cockpit.capture.queue.CaptureSyncScheduler
import ch.sonensei.cockpit.capture.queue.RecordingScheduler
import ch.sonensei.cockpit.capture.share.ShareTags
import ch.sonensei.cockpit.core.network.AuthTokens
import ch.sonensei.cockpit.ui.navigation.CockpitDestination
import ch.sonensei.cockpit.ui.screenTag
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
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import javax.inject.Inject

/**
 * The in-app capture button against the real activity and Hilt graph (queue with its Room database, no network):
 * the badge follows the offline queue, and the sheet it opens saves to the queue and closes back to the screen.
 */
@HiltAndroidTest
@UninstallModules(CaptureQueueBindings::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
class CaptureButtonFlowTest {
    private val hilt = HiltAndroidRule(this)
    private val composeRule = createEmptyComposeRule()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(hilt).around(composeRule)

    // No sync worker runs: the capture stays in the queue as it would in airplane mode.
    @BindValue
    @JvmField
    val scheduler: CaptureSyncScheduler = RecordingScheduler()

    @Inject lateinit var tokenStore: PersistentTokenStore

    @Inject lateinit var queue: CaptureQueue

    @Inject lateinit var dao: CaptureQueueDao

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var main: ActivityScenario<MainActivity>? = null
    private var sheet: ActivityScenario<CaptureActivity>? = null

    @Before
    fun setUp() {
        hilt.inject()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        tokenStore.login(AuthTokens("access-fictitious", "refresh-fictitious"), null)
    }

    @After
    fun tearDown() {
        sheet?.close()
        main?.close()
    }

    private fun launchShell(): ActivityScenario<MainActivity> =
        ActivityScenario.launch(MainActivity::class.java).also {
            main = it
            composeRule.onNodeWithTag(screenTag(CockpitDestination.Dashboard)).assertIsDisplayed()
        }

    /** The intent the shell started for the sheet, launched as Android would (in the same task: no new-task flag). */
    private fun ActivityScenario<MainActivity>.openStartedSheet(): ActivityScenario<CaptureActivity> {
        var started: Intent? = null
        onActivity { started = shadowOf(it).nextStartedActivity }
        val intent = checkNotNull(started) { "the button starts an activity" }
        assertEquals(CaptureActivity::class.java.name, intent.component?.className)
        assertEquals(0, intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
        return ActivityScenario.launch<CaptureActivity>(intent).also { sheet = it }
    }

    private fun ActivityScenario<*>.closing(): Boolean {
        if (state == Lifecycle.State.DESTROYED) return true
        var finishing = false
        onActivity { finishing = it.isFinishing }
        return finishing
    }

    private fun ActivityScenario<MainActivity>.isOpen(): Boolean = !closing()

    @Test
    fun `the badge on the shell's button follows the offline queue`() {
        launchShell()
        composeRule.onNodeWithTag(CaptureButtonTags.FAB).assertContentDescriptionEquals("Capture to Inbox")
        composeRule.onNodeWithTag(CaptureButtonTags.BADGE, useUnmergedTree = true).assertDoesNotExist()

        runBlocking { queue.capture("Water the fictitious plants") }

        composeRule.waitUntil(5_000) {
            composeRule.onAllNodes(hasContentDescription("Capture to Inbox, 1 to sync")).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(CaptureButtonTags.BADGE, useUnmergedTree = true).assertExists()
    }

    @Test
    fun `saving in the sheet opened from the FAB queues offline, closes the sheet and counts it as pending`() {
        val shell = launchShell()
        composeRule.onNodeWithTag(CaptureButtonTags.FAB).performClick()
        val scenario = shell.openStartedSheet()

        composeRule.onNodeWithTag(ShareTags.TEXT).performTextReplacement("Buy fictitious stamps")
        composeRule.onNodeWithTag(ShareTags.NOTES).performTextReplacement("At the corner shop")
        composeRule.onNodeWithTag(ShareTags.SAVE).performClick()
        composeRule.waitUntil(5_000) { scenario.closing() }

        val queued = runBlocking { dao.sendable() }.single().capture
        assertEquals("Buy fictitious stamps", queued.text)
        assertEquals("At the corner shop", queued.notes)
        assertTrue("the shell stays open behind the sheet", shell.isOpen())
        composeRule.waitUntil(5_000) {
            composeRule
                .onAllNodes(hasTestTag(CaptureButtonTags.FAB) and hasContentDescription("Capture to Inbox, 1 to sync"))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithTag(screenTag(CockpitDestination.Dashboard)).assertIsDisplayed()
    }

    @Test
    fun `cancelling the sheet opened from a screen's app-bar action returns to that screen without queueing`() {
        val shell = launchShell()
        composeRule.onNode(hasText("More") and isSelectable()).performClick()
        composeRule.onNodeWithTag(screenTag(CockpitDestination.More)).performScrollToNode(hasText("Notes"))
        composeRule.onNodeWithText("Notes").performClick()
        composeRule.onNodeWithTag(screenTag(CockpitDestination.Notes)).assertIsDisplayed()

        composeRule.onNodeWithTag(CaptureButtonTags.ACTION).performClick()
        val scenario = shell.openStartedSheet()
        composeRule.onNodeWithTag(ShareTags.CANCEL).performClick()
        composeRule.waitUntil(5_000) { scenario.closing() }

        assertTrue(runBlocking { dao.sendable() }.isEmpty())
        assertFalse("the shell was not closed", shell.closing())
        composeRule.onNodeWithTag(screenTag(CockpitDestination.Notes)).assertIsDisplayed()
        composeRule.onNodeWithTag(CaptureButtonTags.ACTION).assertContentDescriptionEquals("Capture to Inbox")
    }
}
