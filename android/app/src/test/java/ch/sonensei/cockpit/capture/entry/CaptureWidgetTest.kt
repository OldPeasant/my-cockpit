package ch.sonensei.cockpit.capture.entry

import android.content.Context
import android.content.Intent
import androidx.glance.appwidget.testing.unit.hasStartActivityClickAction
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.hasContentDescriptionEqualTo
import androidx.glance.testing.unit.hasTestTag
import androidx.glance.testing.unit.hasText
import androidx.test.core.app.ApplicationProvider
import ch.sonensei.cockpit.capture.queue.CaptureQueueStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The widget's content for each queue state: the capture pill always, the pending count only while captures wait. */
@RunWith(RobolectricTestRunner::class)
class CaptureWidgetTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `nothing pending shows only the capture pill`() =
        runGlanceAppWidgetUnitTest {
            setContext(context)
            provideComposable { CaptureWidgetContent(CaptureQueueStatus(pendingCount = 0, lastError = null)) }

            onNode(hasTestTag(CaptureWidgetTags.PILL)).assertExists()
            onNode(hasText("What is it?")).assertExists()
            onNode(hasTestTag(CaptureWidgetTags.PENDING)).assertDoesNotExist()
        }

    @Test
    fun `pending captures show their count`() =
        runGlanceAppWidgetUnitTest {
            setContext(context)
            provideComposable { CaptureWidgetContent(CaptureQueueStatus(pendingCount = 3, lastError = null)) }

            onNode(hasTestTag(CaptureWidgetTags.PENDING)).assertExists()
            onNode(hasText("3 to sync")).assertExists()
        }

    @Test
    fun `a failed sync is shown as not synced`() =
        runGlanceAppWidgetUnitTest {
            setContext(context)
            provideComposable { CaptureWidgetContent(CaptureQueueStatus(pendingCount = 1, lastError = "HTTP 500")) }

            onNode(hasText("1 not synced")).assertExists()
        }

    @Test
    fun `a tap on the pill opens the capture sheet`() =
        runGlanceAppWidgetUnitTest {
            setContext(context)
            provideComposable { CaptureWidgetContent(CaptureQueueStatus(pendingCount = 2, lastError = null)) }

            onNode(hasTestTag(CaptureWidgetTags.PILL)).assert(hasStartActivityClickAction(Intent(context, CaptureActivity::class.java)))
        }

    @Test
    fun `screen readers hear the action and the pending count on the pill`() =
        runGlanceAppWidgetUnitTest {
            setContext(context)
            provideComposable { CaptureWidgetContent(CaptureQueueStatus(pendingCount = 4, lastError = "offline")) }

            onNode(hasTestTag(CaptureWidgetTags.PILL)).assert(hasContentDescriptionEqualTo("Capture to Inbox, 4 not synced"))
        }

    @Test
    fun `screen readers hear only the action while nothing waits`() =
        runGlanceAppWidgetUnitTest {
            setContext(context)
            provideComposable { CaptureWidgetContent(CaptureQueueStatus(pendingCount = 0, lastError = null)) }

            onNode(hasTestTag(CaptureWidgetTags.PILL)).assert(hasContentDescriptionEqualTo("Capture to Inbox"))
        }

    @Test
    fun `pending label follows count and failure`() {
        val resources = context.resources
        assertNull(pendingLabel(resources, CaptureQueueStatus(0, null)))
        assertNull(pendingLabel(resources, CaptureQueueStatus(0, "stale error")))
        assertEquals("1 to sync", pendingLabel(resources, CaptureQueueStatus(1, null)))
        assertEquals("12 to sync", pendingLabel(resources, CaptureQueueStatus(12, null)))
        assertEquals("2 not synced", pendingLabel(resources, CaptureQueueStatus(2, "offline")))
    }
}
