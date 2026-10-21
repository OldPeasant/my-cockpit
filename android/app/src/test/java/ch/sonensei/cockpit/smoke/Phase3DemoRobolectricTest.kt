package ch.sonensei.cockpit.smoke

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import ch.sonensei.cockpit.capture.queue.WorkManagerCaptureSyncScheduler
import ch.sonensei.cockpit.capture.share.TestFilesProvider
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.shadows.ShadowSystemClock
import java.io.File
import java.time.Duration
import java.time.Instant

/**
 * The Phase 3 demo on Robolectric, against a running e2e stack: for machines without an emulator. Excluded from
 * `testDebugUnitTest` unless `-Pe2eBaseUrl=http://127.0.0.1:<port>/` is given (docs/ARCHITECTURE.md "Android demo
 * script").
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
class Phase3DemoRobolectricTest : Phase3Demo() {
    override val device: SmokeDevice = RobolectricDevice()
}

/**
 * Airplane mode is WorkManager's network constraint left unmet (the test WorkManager runs constrained work only when
 * told), the gallery is a test content provider, and the alarm is fired by hand after checking it is set exactly for
 * the reminder time (Robolectric's clock does not run).
 */
private class RobolectricDevice : SmokeDevice {
    private val context: Context = ApplicationProvider.getApplicationContext()

    override val baseUrl: String = System.getProperty(BASE_URL_PROPERTY).orEmpty()
    override val reminderLeadMs = 60_000L
    override val alarmSlackMs = 5_000L
    override val stackReachableOffline = true
    private var online = true

    override fun setUp() {
        assumeTrue("set -Pe2eBaseUrl to run the demo against an e2e stack", baseUrl.isNotEmpty())
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        TestFilesProvider.dir = File(context.cacheDir, "gallery-${System.nanoTime()}").apply { mkdirs() }
        Robolectric.setupContentProvider(TestFilesProvider::class.java, TestFilesProvider.AUTHORITY)
    }

    override fun tearDown() {
        if (baseUrl.isNotEmpty()) TestFilesProvider.dir.deleteRecursively()
    }

    override fun setOnline(online: Boolean) {
        this.online = online
        if (online) runQueuedWork()
    }

    /** Both clocks: the app reads `SystemClock` (faked by Robolectric) and `System.nanoTime` (real) for timing. */
    override fun pause(ms: Long) {
        ShadowSystemClock.advanceBy(Duration.ofMillis(ms))
        Thread.sleep(ms)
    }

    override fun runQueuedWork() {
        if (!online) return
        val driver = WorkManagerTestInitHelper.getTestDriver(context) ?: return
        WorkManager
            .getInstance(context)
            .getWorkInfosForUniqueWork(WorkManagerCaptureSyncScheduler.WORK_NAME)
            .get()
            .filter { it.state == WorkInfo.State.ENQUEUED }
            .forEach { driver.setAllConstraintsMet(it.id) }
    }

    override fun galleryPhoto(
        name: String,
        bytes: ByteArray,
    ): Uri {
        File(TestFilesProvider.dir, name).writeBytes(bytes)
        return Uri.parse("content://${TestFilesProvider.AUTHORITY}/$name")
    }

    override fun letAlarmGoOff(at: Instant) {
        val alarm = shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.single()
        assertEquals("alarm time", at.toEpochMilli(), alarm.triggerAtMs)
        assertEquals("exact alarm", 0L, alarm.windowLengthMs)
        alarm.operation!!.send()
        shadowOf(Looper.getMainLooper()).idle()
    }

    companion object {
        const val BASE_URL_PROPERTY = "cockpit.e2e.baseUrl"
    }
}
