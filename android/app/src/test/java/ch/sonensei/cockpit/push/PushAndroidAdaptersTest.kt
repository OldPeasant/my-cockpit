package ch.sonensei.cockpit.push

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import ch.sonensei.cockpit.MainActivity
import ch.sonensei.cockpit.R
import ch.sonensei.cockpit.reminders.ReminderSyncWorker
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The Android adapters of a push: the content-free new-mail notification on channel "mail" and the one-time reminder
 * sync that a reminder push enqueues; plus the stored UnifiedPush instance of the login.
 */
@RunWith(AndroidJUnit4::class)
class PushAndroidAdaptersTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private lateinit var workManager: WorkManager

    @Before
    fun setUp() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        workManager = WorkManager.getInstance(context)
        context
            .getSharedPreferences("push", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @After
    fun tearDown() {
        notifications.cancelAll()
    }

    private fun shown(): List<Notification> = shadowOf(notifications).allNotifications

    private fun pushSync(): List<WorkInfo> = workManager.getWorkInfosForUniqueWork(WorkManagerPushSyncTrigger.WORK_NAME).get()

    @Test
    fun `a new-mail notification is posted on channel mail, without mail contents, and opens the app`() {
        SystemNewMailNotifier(context).show()

        val notification = shown().single()
        assertEquals(SystemNewMailNotifier.CHANNEL_ID, notification.channelId)
        assertEquals("mail", notification.channelId)
        assertNotNull(notifications.getNotificationChannel("mail"))
        assertEquals(context.getString(R.string.push_mail_title), shadowOf(notification).contentTitle.toString())
        assertEquals(context.getString(R.string.push_mail_text), shadowOf(notification).contentText.toString())
        assertEquals(NotificationCompat.CATEGORY_EMAIL, notification.category)
        assertTrue("auto cancel", notification.flags and Notification.FLAG_AUTO_CANCEL != 0)
        val open: Intent = shadowOf(notification.contentIntent).savedIntent
        assertEquals(MainActivity::class.java.name, open.component?.className)
    }

    @Test
    fun `every new-mail push updates the one notification instead of adding another`() {
        val notifier = SystemNewMailNotifier(context)

        notifier.show()
        notifier.show()
        notifier.show()

        assertEquals(1, shown().size)
    }

    @Test
    fun `without the notification permission no notification is shown`() {
        shadowOf(context as Application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        SystemNewMailNotifier(context).show()

        assertTrue(shown().isEmpty())
    }

    @Test
    @Config(sdk = [30])
    fun `before Android 13 the notification needs no runtime permission`() {
        shadowOf(context as Application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        SystemNewMailNotifier(context).show()

        assertEquals(1, shown().size)
    }

    @Test
    fun `dismiss removes the new-mail notification and leaves other notifications`() {
        val other =
            NotificationCompat
                .Builder(context, "other")
                .setSmallIcon(R.drawable.ic_notification_mail)
                .setContentTitle("Water the plants")
                .build()
        notifications.notify(SystemNewMailNotifier.NOTIFICATION_ID, other)
        val notifier = SystemNewMailNotifier(context)
        notifier.show()
        assertEquals(2, shown().size)

        notifier.dismiss()

        assertEquals(listOf("Water the plants"), shown().map { shadowOf(it).contentTitle.toString() })
    }

    @Test
    fun `dismiss without a shown notification is harmless`() {
        SystemNewMailNotifier(context).dismiss()

        assertTrue(shown().isEmpty())
    }

    @Test
    fun `a reminder push enqueues one reminder sync that waits for the network`() {
        WorkManagerPushSyncTrigger(context).syncReminders()

        val work = pushSync().single()
        assertEquals(WorkInfo.State.ENQUEUED, work.state)
        assertEquals(NetworkType.CONNECTED, work.constraints.requiredNetworkType)
        assertNull("one-time work", work.periodicityInfo)
        assertTrue(work.tags.contains(ReminderSyncWorker::class.java.name))
    }

    @Test
    fun `a second reminder push replaces the pending sync`() {
        val trigger = WorkManagerPushSyncTrigger(context)
        trigger.syncReminders()
        val first = pushSync().single().id

        trigger.syncReminders()

        val all = pushSync()
        assertEquals(1, all.count { it.state == WorkInfo.State.ENQUEUED })
        assertNotEquals(first, all.single { it.state == WorkInfo.State.ENQUEUED }.id)
        assertTrue(all.filter { it.id == first }.all { it.state == WorkInfo.State.CANCELLED })
    }

    @Test
    fun `the instance store has no instance before the first registration`() {
        assertNull(PreferencesPushInstanceStore(context).current())
    }

    @Test
    fun `the instance is a random UUID that stays the same, also across app restarts`() {
        val created = PreferencesPushInstanceStore(context).getOrCreate()

        assertEquals(
            created,
            java.util.UUID
                .fromString(created)
                .toString(),
        )
        assertEquals(created, PreferencesPushInstanceStore(context).current())
        assertEquals(created, PreferencesPushInstanceStore(context).getOrCreate())
    }

    @Test
    fun `the instance fits the server's instance id pattern of the push registration path`() {
        // UnifiedPushController in the server's core.push.api rejects other instance ids with 400.
        val serverPattern = Regex("^[A-Za-z0-9._-]{1,128}$")

        val created = PreferencesPushInstanceStore(context).getOrCreate()

        assertTrue(created, serverPattern.matches(created))
    }

    @Test
    fun `clear forgets the instance and the next login gets a new one`() {
        val store = PreferencesPushInstanceStore(context)
        val first = store.getOrCreate()

        store.clear()

        assertNull(store.current())
        assertNull(PreferencesPushInstanceStore(context).current())
        val second = store.getOrCreate()
        assertFalse(first == second)
    }
}
