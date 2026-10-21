package ch.sonensei.cockpit.reminders

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/** The reminder sync runs every 15 minutes and once when the app opens, while online; a logout cancels it. */
@RunWith(AndroidJUnit4::class)
class ReminderSyncSchedulerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var workManager: WorkManager

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        workManager = WorkManager.getInstance(context)
    }

    private fun work(name: String): List<WorkInfo> = workManager.getWorkInfosForUniqueWork(name).get()

    private fun periodic() = work(WorkManagerReminderSyncScheduler.PERIODIC_WORK_NAME)

    private fun now() = work(WorkManagerReminderSyncScheduler.NOW_WORK_NAME)

    @Test
    fun `start enqueues a periodic sync every 15 minutes and a sync now, both waiting for the network`() {
        WorkManagerReminderSyncScheduler(context).start()

        val periodic = periodic().single()
        assertEquals(WorkInfo.State.ENQUEUED, periodic.state)
        assertNotNull(periodic.periodicityInfo)
        assertEquals(TimeUnit.MINUTES.toMillis(15), periodic.periodicityInfo!!.repeatIntervalMillis)
        assertEquals(NetworkType.CONNECTED, periodic.constraints.requiredNetworkType)
        val now = now().single()
        assertEquals(WorkInfo.State.ENQUEUED, now.state)
        assertEquals(NetworkType.CONNECTED, now.constraints.requiredNetworkType)
    }

    @Test
    fun `starting again, also from a new scheduler instance, keeps the one periodic sync`() {
        val scheduler = WorkManagerReminderSyncScheduler(context)
        scheduler.start()
        val first = periodic().single().id

        scheduler.start()
        WorkManagerReminderSyncScheduler(context).start()

        assertEquals(first, periodic().single().id)
        assertEquals(1, now().size)
    }

    @Test
    fun `cancel stops the periodic and the pending sync`() {
        val scheduler = WorkManagerReminderSyncScheduler(context)
        scheduler.start()

        scheduler.cancel()

        assertTrue(periodic().all { it.state == WorkInfo.State.CANCELLED })
        assertTrue(now().all { it.state == WorkInfo.State.CANCELLED })
    }

    @Test
    fun `after a cancel (logout) the next start (login) schedules the periodic sync again`() {
        val scheduler = WorkManagerReminderSyncScheduler(context)
        scheduler.start()
        scheduler.cancel()

        scheduler.start()

        assertTrue(periodic().any { it.state == WorkInfo.State.ENQUEUED })
        assertTrue(now().any { it.state == WorkInfo.State.ENQUEUED })
    }
}
