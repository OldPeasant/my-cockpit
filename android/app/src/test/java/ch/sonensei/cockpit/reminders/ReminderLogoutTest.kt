package ch.sonensei.cockpit.reminders

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import ch.sonensei.cockpit.auth.AuthRepository
import ch.sonensei.cockpit.auth.PersistentTokenStore
import ch.sonensei.cockpit.core.network.AuthSession
import ch.sonensei.cockpit.core.network.AuthTokens
import ch.sonensei.cockpit.core.network.LogoutReason
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import javax.inject.Inject

/**
 * The reminders belong to the user: in the real Hilt graph, every logout (from the app or by the network layer) stops
 * the sync and removes the alarms, the shown notifications and the stored reminders.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
class ReminderLogoutTest {
    @get:Rule
    val hilt = HiltAndroidRule(this)

    @Inject lateinit var authRepository: AuthRepository

    @Inject lateinit var tokenStore: PersistentTokenStore

    @Inject lateinit var session: AuthSession

    @Inject lateinit var scheduler: ReminderScheduler

    @Inject lateinit var store: ReminderStore

    @Inject lateinit var notifier: ReminderNotifier

    @Inject lateinit var syncScheduler: ReminderSyncScheduler

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private lateinit var workManager: WorkManager

    @Before
    fun setUp() {
        hilt.inject()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        workManager = WorkManager.getInstance(context)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        tokenStore.login(AuthTokens("access-fictitious", "refresh-fictitious"), null)
        // A logged-in user with a running sync, two alarms and one shown reminder.
        syncScheduler.start()
        scheduler.apply(listOf(reminder(1, minutes = 0, title = "Water the plants"), reminder(2, minutes = 60)))
        scheduler.fire(reminderId(1).toString())?.let(notifier::show)
        store.dismissPrompt()
        assertEquals(1, shadowOf(notifications).allNotifications.size)
        // ShadowAlarmManager keeps an alarm listed after its (simulated) firing, so both are still there.
        assertEquals(2, scheduledAlarms(context).size)
    }

    @After
    fun tearDown() {
        ShadowAlarmManager.reset()
    }

    private fun assertNothingOfTheUserStays() {
        assertTrue("alarms", scheduledAlarms(context).isEmpty())
        assertTrue("notifications", shadowOf(notifications).allNotifications.isEmpty())
        assertTrue("stored reminders", ReminderStore(context).scheduled.value.isEmpty())
        assertTrue("notified ids", ReminderStore(context).notifiedIds().isEmpty())
        val syncWork =
            listOf(WorkManagerReminderSyncScheduler.PERIODIC_WORK_NAME, WorkManagerReminderSyncScheduler.NOW_WORK_NAME)
                .flatMap { workManager.getWorkInfosForUniqueWork(it).get() }
        assertTrue("sync work", syncWork.isNotEmpty() && syncWork.all { it.state == WorkInfo.State.CANCELLED })
    }

    @Test
    fun `logging out removes every alarm and notification and stops the sync`() {
        runBlocking { authRepository.logout() }

        assertNothingOfTheUserStays()
    }

    @Test
    fun `a logout by the network layer also removes them`() {
        session.logout(LogoutReason.REFRESH_REJECTED)

        val deadline = System.currentTimeMillis() + WAIT_MS
        // The clearing runs on a background dispatcher; emptying the store is the reminders cache's last step.
        while (store.scheduled.value.isNotEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertNothingOfTheUserStays()
    }

    private companion object {
        const val WAIT_MS = 5_000L
    }
}
