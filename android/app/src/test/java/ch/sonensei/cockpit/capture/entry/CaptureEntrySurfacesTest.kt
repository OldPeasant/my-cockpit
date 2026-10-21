package ch.sonensei.cockpit.capture.entry

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Looper
import android.service.quicksettings.Tile
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.glance.ExperimentalGlanceApi
import androidx.glance.appwidget.runComposition
import androidx.test.core.app.ApplicationProvider
import ch.sonensei.cockpit.R
import ch.sonensei.cockpit.capture.queue.CaptureQueue
import ch.sonensei.cockpit.capture.queue.CaptureQueueBindings
import ch.sonensei.cockpit.capture.queue.CaptureSyncScheduler
import ch.sonensei.cockpit.capture.queue.RecordingScheduler
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

/**
 * The entry points as the system sees them: the shortcut XML the launcher reads, the tile's subtitle from the real
 * queue, and the widget session composed from the real queue (provideGlance), against the Hilt graph.
 */
@HiltAndroidTest
@UninstallModules(CaptureQueueBindings::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
class CaptureEntrySurfacesTest {
    @get:Rule
    val hilt = HiltAndroidRule(this)

    @BindValue
    @JvmField
    val scheduler: CaptureSyncScheduler = RecordingScheduler()

    @javax.inject.Inject lateinit var queue: CaptureQueue

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() = hilt.inject()

    private fun queueCaptures(vararg texts: String) =
        runBlocking {
            texts.forEach { queue.capture(it) }
            assertEquals(texts.size, queue.status.first().pendingCount)
        }

    /** Runs the main looper until [done] holds: the tile resumes on Main after Room answered on its executor. */
    private fun idleMainUntil(done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!done() && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }

    // --- static app shortcut ---

    private data class Shortcut(
        val attributes: Map<String, String>,
        val intents: List<Map<String, String>>,
    )

    private fun shortcuts(): List<Shortcut> {
        val parser = context.resources.getXml(R.xml.shortcuts)
        val result = mutableListOf<Shortcut>()
        var current: Shortcut? = null

        fun attrs(): Map<String, String> =
            (0 until parser.attributeCount).associate {
                parser.getAttributeName(it) to parser.getAttributeValue(it)
            }
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "shortcut" -> current = Shortcut(attrs(), mutableListOf())
                    "intent" -> (current!!.intents as MutableList).add(attrs())
                }
            } else if (parser.eventType == XmlPullParser.END_TAG && parser.name == "shortcut") {
                result += current!!
                current = null
            }
        }
        return result
    }

    @Test
    fun `the static shortcut opens the capture sheet of this app`() {
        val shortcut = shortcuts().single()
        assertEquals("capture", shortcut.attributes["shortcutId"])
        assertEquals("true", shortcut.attributes["enabled"])

        val intent = shortcut.intents.single()
        assertEquals(Intent.ACTION_VIEW, intent["action"])
        assertEquals(context.packageName, intent["targetPackage"])
        assertEquals(CaptureActivity::class.java.name, intent["targetClass"])
        // The launcher starts exactly this component with this app's identity: it must exist, and need not be exported.
        val target = context.packageManager.getActivityInfo(ComponentName(intent["targetPackage"]!!, intent["targetClass"]!!), 0)
        assertFalse(target.exported)
    }

    @Test
    fun `the shortcut labels and icon are resources that exist`() {
        val attributes = shortcuts().single().attributes
        for (name in listOf("shortcutShortLabel", "shortcutLongLabel", "shortcutDisabledMessage", "icon")) {
            val ref = attributes[name]
            assertNotNull("$name is set", ref)
            assertTrue("$name is a resource reference: $ref", ref!!.startsWith("@"))
            assertTrue("$name resolves", context.resources.getResourceName(ref.drop(1).toInt()).isNotEmpty())
        }
        assertEquals("Capture", context.getString(R.string.capture_entry_label))
        // Launchers truncate short labels beyond 10 characters.
        assertTrue(context.getString(R.string.capture_entry_label).length <= 10)
    }

    // --- widget provider ---

    @Test
    fun `the widget receiver points at its provider info`() {
        val info =
            context.packageManager.getReceiverInfo(ComponentName(context, CaptureWidgetReceiver::class.java), PackageManager.GET_META_DATA)
        assertEquals(R.xml.capture_widget_info, info.metaData.getInt("android.appwidget.provider"))
        assertTrue(CaptureWidgetReceiver().glanceAppWidget is CaptureWidget)
    }

    // --- quick-settings tile ---

    @Test
    fun `the tile subtitle shows how many captures wait`() {
        queueCaptures("Call the plumber", "Return library book")
        val service = Robolectric.buildService(CaptureTileService::class.java).create().get()
        val tile = checkNotNull(service.qsTile) { "the tile is bound" }

        service.onStartListening()
        idleMainUntil { tile.subtitle != null }

        assertEquals("2 to sync", tile.subtitle)
        assertEquals(Tile.STATE_INACTIVE, tile.state)
        // No controller.destroy(): Robolectric 4.16's ShadowTileService cannot run Service.onDestroy (ClassCastException).
    }

    @Test
    fun `the tile has no subtitle when everything is synced`() {
        val service = Robolectric.buildService(CaptureTileService::class.java).create().get()
        val tile = checkNotNull(service.qsTile) { "the tile is bound" }
        tile.subtitle = "stale"

        service.onStartListening()
        idleMainUntil { tile.subtitle == null }

        assertNull(tile.subtitle)
    }

    @Test
    @Config(sdk = [33])
    fun `a tap on the locked device unlocks first, then opens the capture sheet`() {
        val service = Robolectric.buildService(CaptureTileService::class.java).create().get()
        shadowOf(service).setLocked(true)

        service.onClick()

        assertFalse("unlocked before opening", service.isLocked)
        val started = shadowOf(context as android.app.Application).nextStartedActivity
        assertEquals(CaptureActivity::class.java.name, started?.component?.className)
    }

    // --- widget session (provideGlance) ---

    private fun View.texts(): List<String> =
        when (this) {
            is TextView -> listOf(text.toString())
            is ViewGroup -> (0 until childCount).flatMap { getChildAt(it).texts() }
            else -> emptyList()
        }

    @OptIn(ExperimentalGlanceApi::class)
    private fun widgetTexts(): List<String> =
        runBlocking {
            val views = withTimeout(10_000) { CaptureWidget().runComposition(context).first() }
            views.apply(context, FrameLayout(context)).texts()
        }

    @Test
    fun `the placed widget reads the pending count from the queue`() {
        queueCaptures("Book dentist", "Renew passport", "Water plants")

        val texts = widgetTexts()

        assertTrue("pill hint in $texts", "What is it?" in texts)
        assertTrue("count in $texts", "3 to sync" in texts)
    }

    @OptIn(ExperimentalGlanceApi::class)
    @Test
    fun `a shown widget follows the queue while its session runs`() {
        var captured = false
        val texts =
            runBlocking {
                withTimeout(10_000) {
                    CaptureWidget()
                        .runComposition(context)
                        .map { it.apply(context, FrameLayout(context)).texts() }
                        .onEach {
                            // Capture once the empty widget is on screen; the same session must then redraw.
                            if (!captured) {
                                assertTrue("starts without a count: $it", it.none { text -> text.contains("sync") })
                                captured = true
                                queue.capture("Order printer ink")
                            }
                        }.first { "1 to sync" in it }
                }
            }

        assertTrue("count in $texts", "1 to sync" in texts)
    }

    // Android 11: no wallpaper colours, the widget falls back to the cockpit palette.
    @Test
    @Config(sdk = [30])
    fun `the widget renders its count before Android 12 too`() {
        queueCaptures("Fix bike light")

        val texts = widgetTexts()

        assertTrue("count in $texts", "1 to sync" in texts)
    }

    @Test
    fun `the placed widget shows no count while nothing waits`() {
        val texts = widgetTexts()

        assertTrue("pill hint in $texts", "What is it?" in texts)
        assertTrue("no count in $texts", texts.none { it.contains("sync") })
    }
}
