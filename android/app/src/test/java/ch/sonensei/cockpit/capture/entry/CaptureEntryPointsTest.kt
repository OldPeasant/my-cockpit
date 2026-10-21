package ch.sonensei.cockpit.capture.entry

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import ch.sonensei.cockpit.MainActivity
import ch.sonensei.cockpit.capture.queue.CaptureQueue
import ch.sonensei.cockpit.capture.queue.CaptureQueueBindings
import ch.sonensei.cockpit.capture.queue.CaptureQueueDao
import ch.sonensei.cockpit.capture.queue.CaptureSyncScheduler
import ch.sonensei.cockpit.capture.queue.RecordingScheduler
import ch.sonensei.cockpit.capture.share.ShareTags
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import dagger.hilt.android.testing.UninstallModules
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
import javax.inject.Inject

/** The capture sheet, tile and shortcut against the real Hilt graph (queue with its Room database). */
@HiltAndroidTest
@UninstallModules(CaptureQueueBindings::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
class CaptureEntryPointsTest {
    private val hilt = HiltAndroidRule(this)
    private val composeRule = createEmptyComposeRule()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(hilt).around(composeRule)

    @BindValue
    @JvmField
    val scheduler: CaptureSyncScheduler = RecordingScheduler()

    @Inject lateinit var dao: CaptureQueueDao

    @Inject lateinit var queue: CaptureQueue

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var scenario: ActivityScenario<CaptureActivity>? = null

    @Before
    fun setUp() = hilt.inject()

    @After
    fun tearDown() {
        scenario?.close()
    }

    private fun launch(): ActivityScenario<CaptureActivity> = ActivityScenario.launch(CaptureActivity::class.java).also { scenario = it }

    private fun ActivityScenario<CaptureActivity>.closing(): Boolean {
        if (state == Lifecycle.State.DESTROYED) return true
        var finishing = false
        onActivity { finishing = it.isFinishing }
        return finishing
    }

    @Test
    fun `the capture sheet opens empty with the title focused`() {
        launch()

        composeRule
            .onNodeWithTag(ShareTags.TEXT)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
            .assertIsFocused()
        composeRule.onNodeWithTag(ShareTags.SAVE).assertIsNotEnabled()
    }

    @Test
    fun `a capture is queued and counted as pending for the widget`() {
        val scenario = launch()

        composeRule.onNodeWithTag(ShareTags.TEXT).performTextReplacement("Pay rent")
        composeRule.onNodeWithTag(ShareTags.SAVE).performClick()
        composeRule.waitUntil(5_000) { scenario.closing() }

        assertEquals("Pay rent", runBlocking { dao.sendable() }.single().capture.text)
        val status = runBlocking { queue.status.first() }
        assertEquals(1, status.pendingCount)
        // The widget reads the same queue through its entry point.
        val widgetQueue = EntryPointAccessors.fromApplication(context, CaptureEntryPoint::class.java).captureQueue()
        assertEquals(1, runBlocking { widgetQueue.status.first() }.pendingCount)
        assertEquals(1, (scheduler as RecordingScheduler).scheduled)
    }

    @Test
    fun `cancel closes the sheet without queueing`() {
        val scenario = launch()

        composeRule.onNodeWithTag(ShareTags.CANCEL).performClick()
        composeRule.waitUntil(5_000) { scenario.closing() }

        assertTrue(runBlocking { dao.sendable() }.isEmpty())
    }

    // Android 13: the Intent variant. From 14 on the tile hands a PendingIntent to System UI, absent under Robolectric.
    @Test
    @Config(sdk = [33])
    fun `the tile opens the capture sheet in a new task`() {
        val tile = Robolectric.buildService(CaptureTileService::class.java).create().get()

        tile.onClick()

        val started = shadowOf(context as android.app.Application).nextStartedActivity
        assertNotNull(started)
        assertEquals(CaptureActivity::class.java.name, started.component?.className)
        assertTrue(started.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    @Config(sdk = [33])
    fun `the tile shows the pending captures when the shade opens`() {
        runBlocking { queue.capture("Call the plumber") }
        val service = Robolectric.buildService(CaptureTileService::class.java).create().get()
        val tile = service.qsTile
        assertNotNull("Robolectric provides the tile", tile)

        tile.state = Tile.STATE_UNAVAILABLE
        service.onStartListening()
        // The queue's Room flow emits on a background thread, then the tile is updated on the main looper.
        val deadline = System.currentTimeMillis() + 5_000
        while (tile.subtitle == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
            shadowOf(android.os.Looper.getMainLooper()).idle()
        }

        assertEquals(Tile.STATE_INACTIVE, tile.state)
        assertEquals("1 to sync", tile.subtitle)
    }

    @Test
    fun `the manifest registers the widget, the tile and the shortcut`() {
        val pm = context.packageManager
        val receivers = pm.queryBroadcastReceivers(Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE).setPackage(context.packageName), 0)
        assertTrue(receivers.any { it.activityInfo.name == CaptureWidgetReceiver::class.java.name })

        val tile = pm.getServiceInfo(ComponentName(context, CaptureTileService::class.java), PackageManager.GET_META_DATA)
        assertEquals(android.Manifest.permission.BIND_QUICK_SETTINGS_TILE, tile.permission)
        assertTrue(pm.queryIntentServices(Intent(TileService.ACTION_QS_TILE).setPackage(context.packageName), 0).isNotEmpty())

        val main = pm.getActivityInfo(ComponentName(context, MainActivity::class.java), PackageManager.GET_META_DATA)
        assertNotNull(main.metaData?.getInt("android.app.shortcuts")?.takeIf { it != 0 })
        val capture = pm.getActivityInfo(ComponentName(context, CaptureActivity::class.java), 0)
        assertFalse("only the app's own entry points start the capture sheet", capture.exported)
    }
}
