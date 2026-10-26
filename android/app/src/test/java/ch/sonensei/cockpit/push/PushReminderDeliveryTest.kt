package ch.sonensei.cockpit.push

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import ch.sonensei.cockpit.R
import ch.sonensei.cockpit.core.network.AuthSession
import ch.sonensei.cockpit.core.network.generated.apis.RemindersApi
import ch.sonensei.cockpit.core.network.generated.infrastructure.Serializer
import ch.sonensei.cockpit.reminders.AlarmManagerReminderAlarms
import ch.sonensei.cockpit.reminders.NOW
import ch.sonensei.cockpit.reminders.ReminderNotifier
import ch.sonensei.cockpit.reminders.ReminderOpenRequests
import ch.sonensei.cockpit.reminders.ReminderScheduler
import ch.sonensei.cockpit.reminders.ReminderStore
import ch.sonensei.cockpit.reminders.itemId
import ch.sonensei.cockpit.reminders.reminder
import ch.sonensei.cockpit.reminders.reminderId
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.MediaType.Companion.toMediaType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlarmManager
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit

/**
 * A reminder push shows the reminder it announces (`GET /api/v1/reminders/{id}`), also one this device never synced
 * (the reminder sync only lists pending ones), and only once per device.
 */
@RunWith(AndroidJUnit4::class)
class PushReminderDeliveryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val server = MockWebServer()
    private val store = ReminderStore(context)
    private val scheduler =
        ReminderScheduler(store, AlarmManagerReminderAlarms(context), ReminderNotifier(context), Clock.fixed(NOW, ZoneOffset.UTC))
    private var loggedIn = true
    private val session = mockk<AuthSession> { every { isLoggedIn } answers { loggedIn } }
    private lateinit var api: RemindersApi
    private lateinit var delivery: PushReminderDelivery

    @Before
    fun setUp() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        server.start()
        api =
            Retrofit
                .Builder()
                .baseUrl(server.url("/"))
                .addConverterFactory(Serializer.kotlinxSerializationJson.asConverterFactory("application/json".toMediaType()))
                .build()
                .create(RemindersApi::class.java)
        delivery = PushReminderDelivery(session, api, scheduler)
    }

    @After
    fun tearDown() {
        server.close()
        store.clear()
        notifications.cancelAll()
        ShadowAlarmManager.reset()
    }

    private fun answer(
        body: String,
        code: Int = 200,
    ) = server.enqueue(
        MockResponse
            .Builder()
            .code(code)
            .addHeader("Content-Type", "application/json")
            .body(body)
            .build(),
    )

    private fun firedJson(
        n: Int,
        firedAt: Instant? = NOW,
        available: Boolean = true,
        title: String = "Call plumber $n",
    ): String {
        val item =
            if (available) {
                """{"available":true,"id":"${itemId(n)}","type":"TASK","title":"$title","deepLink":"/tasks/${itemId(n)}"}"""
            } else {
                """{"available":false,"id":"${itemId(n)}","type":"TASK"}"""
            }
        val fired = firedAt?.let { ""","firedAt":"$it"""" } ?: ""
        return """{"id":"${reminderId(n)}","channel":"NOTIFICATION","createdAt":"2026-10-21T07:00:00Z",""" +
            """"remindAt":"${NOW.minusSeconds(60)}"$fired,"item":$item}"""
    }

    private fun shown(): List<Notification> = shadowOf(notifications).allNotifications

    private fun deliver(n: Int) = runBlocking { delivery.deliver(reminderId(n)) }

    @Test
    fun `a fired reminder this device never synced is loaded by id and shown`() {
        answer(firedJson(1, title = "Water the ficus"))

        assertEquals(PushReminderResult.SHOWN, deliver(1))

        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("GET", request.method)
        assertEquals("/api/v1/reminders/${reminderId(1)}", request.url.encodedPath)
        val notification = shown().single()
        assertEquals(ReminderNotifier.CHANNEL_ID, notification.channelId)
        assertEquals("Water the ficus", shadowOf(notification).contentTitle.toString())
        assertTrue(reminderId(1).toString() in store.notifiedIds())
    }

    @Test
    fun `a tap on the pushed reminder opens its item, and the notification shows the reminder's time`() {
        val remindAt = Instant.parse("2026-10-21T07:45:00Z")
        answer(
            """{"id":"${reminderId(1)}","channel":"NOTIFICATION","createdAt":"2026-10-21T07:00:00Z",""" +
                """"remindAt":"$remindAt","firedAt":"$NOW",""" +
                """"item":{"available":true,"id":"${itemId(7)}","type":"NOTE","title":"Fictitious meeting notes"}}""",
        )

        assertEquals(PushReminderResult.SHOWN, deliver(1))

        val notification = shown().single()
        assertEquals(remindAt.toEpochMilli(), notification.`when`)
        val open: Intent = shadowOf(notification.contentIntent).savedIntent
        assertEquals(ReminderOpenRequests.ACTION_OPEN_ITEM, open.action)
        assertEquals("NOTE", open.getStringExtra(ReminderOpenRequests.EXTRA_ITEM_TYPE))
        assertEquals(itemId(7).toString(), open.getStringExtra(ReminderOpenRequests.EXTRA_ITEM_ID))
    }

    @Test
    fun `a pushed reminder whose item has no title is shown as untitled`() {
        answer(
            """{"id":"${reminderId(1)}","channel":"NOTIFICATION","createdAt":"2026-10-21T07:00:00Z",""" +
                """"remindAt":"${NOW.minusSeconds(60)}","firedAt":"$NOW",""" +
                """"item":{"available":true,"id":"${itemId(1)}","type":"TASK"}}""",
        )

        assertEquals(PushReminderResult.SHOWN, deliver(1))

        assertEquals(context.getString(R.string.reminder_untitled), shadowOf(shown().single()).contentTitle.toString())
    }

    @Test
    fun `a second push of the same reminder does not show it again`() {
        answer(firedJson(1))
        answer(firedJson(1))

        assertEquals(PushReminderResult.SHOWN, deliver(1))
        notifications.cancelAll()
        assertEquals(PushReminderResult.IGNORED, deliver(1))

        assertTrue(shown().isEmpty())
    }

    @Test
    fun `a reminder the local alarm already showed is not shown again`() {
        scheduler.apply(listOf(reminder(1, minutes = -1)))
        // The local alarm went off first.
        assertEquals(reminderId(1).toString(), scheduler.fire(reminderId(1).toString())?.id)
        answer(firedJson(1))

        assertEquals(PushReminderResult.IGNORED, deliver(1))

        assertTrue(shown().isEmpty())
    }

    @Test
    fun `after the push showed it, the local alarm of the reminder does not show it again`() {
        scheduler.apply(listOf(reminder(1, minutes = 1)))
        answer(firedJson(1))

        assertEquals(PushReminderResult.SHOWN, deliver(1))

        assertNull(scheduler.fire(reminderId(1).toString()))
        assertEquals(1, shown().size)
    }

    @Test
    fun `logged out, nothing is requested or shown`() {
        loggedIn = false

        assertEquals(PushReminderResult.IGNORED, deliver(1))

        assertEquals(0, server.requestCount)
        assertTrue(shown().isEmpty())
    }

    @Test
    fun `a logout while the request runs does not show the reminder`() {
        val loggingOut = mockk<AuthSession> { every { isLoggedIn } returnsMany listOf(true, false) }
        answer(firedJson(1))

        val result = runBlocking { PushReminderDelivery(loggingOut, api, scheduler).deliver(reminderId(1)) }

        assertEquals(PushReminderResult.IGNORED, result)
        assertEquals(1, server.requestCount)
        assertTrue(shown().isEmpty())
        assertTrue(store.notifiedIds().isEmpty())
    }

    @Test
    fun `a deleted reminder (404) is ignored`() {
        answer("""{"type":"about:blank","title":"Not Found","status":404}""", code = 404)

        assertEquals(PushReminderResult.IGNORED, deliver(1))

        assertTrue(shown().isEmpty())
        assertTrue(store.notifiedIds().isEmpty())
    }

    @Test
    fun `an error answer or an unreadable body is ignored`() {
        answer("""{"type":"about:blank","title":"Internal Server Error","status":500}""", code = 500)
        answer("""{"id":""")

        assertEquals(PushReminderResult.IGNORED, deliver(1))
        assertEquals(PushReminderResult.IGNORED, deliver(1))

        assertTrue(shown().isEmpty())
    }

    @Test
    fun `a success answer without a body is ignored`() {
        server.enqueue(MockResponse.Builder().code(204).build())

        assertEquals(PushReminderResult.IGNORED, deliver(1))

        assertTrue(shown().isEmpty())
        assertTrue(store.notifiedIds().isEmpty())
    }

    @Test
    fun `the worker does not retry a push it ignores`() =
        runBlocking {
            answer("""{"type":"about:blank","title":"Not Found","status":404}""", code = 404)

            assertEquals(ListenableWorker.Result.success(), worker(reminderId(1).toString()).doWork())

            assertEquals(1, server.requestCount)
            assertTrue(shown().isEmpty())
        }

    @Test
    fun `a reminder whose item was trashed is not shown`() {
        answer(firedJson(1, available = false))

        assertEquals(PushReminderResult.IGNORED, deliver(1))

        assertTrue(shown().isEmpty())
    }

    @Test
    fun `a reminder the server has not fired is left to its local alarm`() {
        answer(firedJson(1, firedAt = null))

        assertEquals(PushReminderResult.IGNORED, deliver(1))

        assertTrue(shown().isEmpty())
        assertTrue(store.notifiedIds().isEmpty())
    }

    @Test
    fun `an answer for another reminder is not shown`() {
        answer(firedJson(2))

        assertEquals(PushReminderResult.IGNORED, deliver(1))

        assertTrue(shown().isEmpty())
    }

    @Test
    fun `an unreachable server is offline and the worker retries`() =
        runBlocking {
            server.close()

            assertEquals(PushReminderResult.OFFLINE, delivery.deliver(reminderId(1)))
            assertEquals(ListenableWorker.Result.retry(), worker(reminderId(1).toString()).doWork())
            assertTrue(shown().isEmpty())
        }

    @Test
    fun `a cancelled delivery is cancelled, not reported as ignored`() {
        val cancelled = mockk<RemindersApi>()
        coEvery { cancelled.getReminder(any()) } throws CancellationException("worker stopped")

        assertThrows(CancellationException::class.java) {
            runBlocking { PushReminderDelivery(session, cancelled, scheduler).deliver(reminderId(1)) }
        }
    }

    @Test
    fun `the worker shows the reminder of its input and succeeds`() =
        runBlocking {
            answer(firedJson(1))

            assertEquals(ListenableWorker.Result.success(), worker(reminderId(1).toString()).doWork())

            assertEquals(1, shown().size)
        }

    @Test
    fun `a worker without a reminder id fails without a request`() =
        runBlocking {
            assertEquals(ListenableWorker.Result.failure(), worker(null).doWork())
            assertEquals(ListenableWorker.Result.failure(), worker("not-a-uuid").doWork())
            assertEquals(0, server.requestCount)
        }

    @Test
    fun `the trigger enqueues one delivery per reminder that waits for the network`() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val workManager = WorkManager.getInstance(context)
        val trigger = WorkManagerPushSyncTrigger(context)

        trigger.showReminder(reminderId(1))
        trigger.showReminder(reminderId(1))
        trigger.showReminder(reminderId(2))

        val first = workManager.getWorkInfosForUniqueWork(WorkManagerPushSyncTrigger.reminderWorkName(reminderId(1))).get()
        val second = workManager.getWorkInfosForUniqueWork(WorkManagerPushSyncTrigger.reminderWorkName(reminderId(2))).get()
        val work = first.single()
        assertEquals(WorkInfo.State.ENQUEUED, work.state)
        assertEquals(NetworkType.CONNECTED, work.constraints.requiredNetworkType)
        assertNull("one-time work", work.periodicityInfo)
        assertTrue(work.tags.contains(PushReminderWorker::class.java.name))
        assertEquals(WorkInfo.State.ENQUEUED, second.single().state)
        // The reminder sync of the push is separate work and is not replaced.
        assertTrue(workManager.getWorkInfosForUniqueWork(WorkManagerPushSyncTrigger.WORK_NAME).get().isEmpty())
    }

    private fun worker(id: String?): PushReminderWorker =
        TestListenableWorkerBuilder<PushReminderWorker>(context)
            .setInputData(id?.let { workDataOf(PushReminderWorker.KEY_REMINDER_ID to it) } ?: workDataOf())
            .setWorkerFactory(
                object : WorkerFactory() {
                    override fun createWorker(
                        appContext: Context,
                        workerClassName: String,
                        workerParameters: WorkerParameters,
                    ): ListenableWorker = PushReminderWorker(appContext, workerParameters) { delivery }
                },
            ).build()
}
