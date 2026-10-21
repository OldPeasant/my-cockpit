package ch.sonensei.cockpit.capture.entry

import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.glance.ExperimentalGlanceApi
import androidx.glance.appwidget.runComposition
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import ch.sonensei.cockpit.capture.queue.CaptureQueue
import ch.sonensei.cockpit.capture.queue.CaptureQueueBindings
import ch.sonensei.cockpit.capture.queue.CaptureQueueDao
import ch.sonensei.cockpit.capture.queue.CaptureSyncScheduler
import ch.sonensei.cockpit.capture.queue.RecordingScheduler
import ch.sonensei.cockpit.capture.share.ShareTags
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import dagger.hilt.android.testing.UninstallModules
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import javax.inject.Inject

/**
 * The entry points against queue states the other tests leave out: a failed upload, a capture that leaves the queue
 * after it was sent, and the save confirmation.
 */
@HiltAndroidTest
@UninstallModules(CaptureQueueBindings::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
class CaptureEntryQueueStatesTest {
    private val hilt = HiltAndroidRule(this)
    private val composeRule = createEmptyComposeRule()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(hilt).around(composeRule)

    @BindValue
    @JvmField
    val scheduler: CaptureSyncScheduler = RecordingScheduler()

    @Inject lateinit var queue: CaptureQueue

    @Inject lateinit var dao: CaptureQueueDao

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var scenario: ActivityScenario<CaptureActivity>? = null

    @Before
    fun setUp() = hilt.inject()

    @After
    fun tearDown() {
        scenario?.close()
    }

    /** Queues [text] and records a failed upload attempt for it, as the uploader does when the server is unreachable. */
    private fun queueFailedCapture(text: String): String =
        runBlocking {
            queue.capture(text)
            val id =
                dao
                    .sendable()
                    .single { it.capture.text == text }
                    .capture.id
            dao.markFailed(id, error = "HTTP 503", atEpochMs = 1_767_225_600_000L, rejected = false)
            assertEquals("HTTP 503", queue.status.first().lastError)
            id
        }

    private fun idleMainUntil(done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!done() && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }

    private fun View.texts(): List<String> =
        when (this) {
            is TextView -> listOf(text.toString())
            is ViewGroup -> (0 until childCount).flatMap { getChildAt(it).texts() }
            else -> emptyList()
        }

    @Test
    fun `the tile subtitle says not synced after a failed upload`() {
        queueFailedCapture("Send invoice to Example AG")
        val service = Robolectric.buildService(CaptureTileService::class.java).create().get()
        val tile = checkNotNull(service.qsTile) { "the tile is bound" }

        service.onStartListening()
        idleMainUntil { tile.subtitle != null }

        assertEquals("1 not synced", tile.subtitle)
    }

    @OptIn(ExperimentalGlanceApi::class)
    @Test
    fun `the placed widget says not synced after a failed upload`() {
        queueFailedCapture("Book train to Testhausen")

        val texts =
            runBlocking {
                val views = withTimeout(10_000) { CaptureWidget().runComposition(context).first() }
                views.apply(context, FrameLayout(context)).texts()
            }

        assertTrue("failure count in $texts", "1 not synced" in texts)
    }

    @OptIn(ExperimentalGlanceApi::class)
    @Test
    fun `a shown widget drops its count once the last capture was sent`() {
        val id = queueFailedCapture("Water the office plants")
        var sent = false
        val texts =
            runBlocking {
                withTimeout(10_000) {
                    CaptureWidget()
                        .runComposition(context)
                        .map { it.apply(context, FrameLayout(context)).texts() }
                        .onEach {
                            if (!sent && "1 not synced" in it) {
                                sent = true
                                // The uploader removes a capture from the queue once the server accepted it.
                                dao.delete(id)
                            }
                        }.first { sent && it.none { text -> text.contains("sync") } }
                }
            }

        assertTrue("pill hint stays in $texts", "What is it?" in texts)
    }

    @Test
    fun `saving a capture confirms it and closes the sheet`() {
        val scenario = ActivityScenario.launch(CaptureActivity::class.java).also { scenario = it }

        composeRule.onNodeWithTag(ShareTags.TEXT).performTextReplacement("Renew parking permit")
        composeRule.onNodeWithTag(ShareTags.SAVE).performClick()
        composeRule.waitUntil(5_000) {
            var closing = scenario.state == Lifecycle.State.DESTROYED
            if (!closing) scenario.onActivity { closing = it.isFinishing }
            closing
        }

        assertEquals("Saved to Inbox", ShadowToast.getTextOfLatestToast())
    }
}
