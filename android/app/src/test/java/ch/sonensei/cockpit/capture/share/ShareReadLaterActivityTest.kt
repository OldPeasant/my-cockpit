package ch.sonensei.cockpit.capture.share

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import ch.sonensei.cockpit.capture.queue.CaptureQueueBindings
import ch.sonensei.cockpit.capture.queue.CaptureQueueDao
import ch.sonensei.cockpit.capture.queue.CaptureSyncScheduler
import ch.sonensei.cockpit.capture.queue.RecordingScheduler
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import dagger.hilt.android.testing.UninstallModules
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import javax.inject.Inject

/** Read later end to end: the real share activity, view model, queue and Room database (SPEC.md §8.4). */
@HiltAndroidTest
@UninstallModules(CaptureQueueBindings::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
class ShareReadLaterActivityTest {
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
    }

    @After
    fun tearDown() {
        scenario?.close()
    }

    private fun launchUrl(
        text: String,
        subject: String? = null,
    ): ActivityScenario<ShareActivity> {
        val intent =
            Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, text)
                .setClass(context, ShareActivity::class.java)
        subject?.let { intent.putExtra(Intent.EXTRA_SUBJECT, it) }
        return ActivityScenario.launch<ShareActivity>(intent).also { scenario = it }
    }

    private fun awaitClosed(scenario: ActivityScenario<ShareActivity>) {
        composeRule.waitUntil(5_000) {
            if (scenario.state == Lifecycle.State.DESTROYED) {
                true
            } else {
                var finishing = false
                scenario.onActivity { finishing = it.isFinishing }
                finishing
            }
        }
    }

    @Test
    fun `read later queues a bookmark, no capture, and confirms it`() {
        val scenario = launchUrl("https://example.org/holiday", subject = "Holiday ideas")

        composeRule.onNodeWithTag(ShareTags.DESTINATION_READ_LATER).performClick()
        composeRule.onNodeWithTag(ShareTags.DESTINATION_READ_LATER).assertIsSelected()
        composeRule.onNodeWithTag(ShareTags.URL).assertTextEquals("https://example.org/holiday")
        composeRule.onNodeWithTag(ShareTags.SAVE).performClick()
        awaitClosed(scenario)

        val bookmark = runBlocking { dao.sendableBookmarks() }.single()
        assertEquals("https://example.org/holiday", bookmark.url)
        assertEquals("Holiday ideas", bookmark.title)
        assertTrue(runBlocking { dao.sendable() }.isEmpty())
        assertEquals(1, (scheduler as RecordingScheduler).scheduled)
        assertEquals("Saved to read later", ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun `read later of a bare link with an edited title keeps the edit`() {
        val scenario = launchUrl("https://example.org/a")

        composeRule.onNodeWithTag(ShareTags.DESTINATION_READ_LATER).performClick()
        composeRule.onNodeWithTag(ShareTags.TEXT).performTextReplacement("Article about bread")
        composeRule.onNodeWithTag(ShareTags.SAVE).performClick()
        awaitClosed(scenario)

        assertEquals("Article about bread", runBlocking { dao.sendableBookmarks() }.single().title)
    }

    @Test
    fun `read later of a bare link sends no title`() {
        val scenario = launchUrl("https://example.org/a")

        composeRule.onNodeWithTag(ShareTags.DESTINATION_READ_LATER).performClick()
        composeRule.onNodeWithTag(ShareTags.SAVE).performClick()
        awaitClosed(scenario)

        assertNull(runBlocking { dao.sendableBookmarks() }.single().title)
    }

    @Test
    fun `switching back to the Inbox saves a capture as before`() {
        val scenario = launchUrl("https://example.org/holiday", subject = "Holiday ideas")

        composeRule.onNodeWithTag(ShareTags.DESTINATION_READ_LATER).performClick()
        composeRule.onNodeWithTag(ShareTags.DESTINATION_INBOX).performClick()
        composeRule.onNodeWithTag(ShareTags.NOTES).assertTextEquals("https://example.org/holiday")
        composeRule.onNodeWithTag(ShareTags.SAVE).performClick()
        awaitClosed(scenario)

        assertEquals("Holiday ideas", runBlocking { dao.sendable() }.single().capture.text)
        assertTrue(runBlocking { dao.sendableBookmarks() }.isEmpty())
        assertEquals("Saved to Inbox", ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun `the chosen destination survives a configuration change`() {
        val scenario = launchUrl("https://example.org/a")
        composeRule.onNodeWithTag(ShareTags.DESTINATION_READ_LATER).performClick()

        scenario.recreate()

        composeRule.onNodeWithTag(ShareTags.DESTINATION_READ_LATER).assertIsSelected()
        assertTrue(runBlocking { dao.sendableBookmarks() }.isEmpty())
    }
}
