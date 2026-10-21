package ch.sonensei.cockpit.reminders

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import ch.sonensei.cockpit.core.network.AuthSession
import ch.sonensei.cockpit.core.network.generated.apis.RemindersApi
import ch.sonensei.cockpit.core.network.generated.infrastructure.Serializer
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
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.shadows.ShadowAlarmManager
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.time.Clock
import java.time.Duration
import java.time.ZoneOffset

@RunWith(AndroidJUnit4::class)
class ReminderSyncTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val server = MockWebServer()
    private val store = ReminderStore(context)
    private val scheduler =
        ReminderScheduler(store, AlarmManagerReminderAlarms(context), ReminderNotifier(context), Clock.fixed(NOW, ZoneOffset.UTC))
    private var loggedIn = true
    private val session = mockk<AuthSession> { every { isLoggedIn } answers { loggedIn } }
    private lateinit var sync: ReminderSync

    @Before
    fun setUp() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        server.start()
        val api =
            Retrofit
                .Builder()
                .baseUrl(server.url("/"))
                .addConverterFactory(Serializer.kotlinxSerializationJson.asConverterFactory("application/json".toMediaType()))
                .build()
                .create(RemindersApi::class.java)
        sync = ReminderSync(session, api, scheduler)
    }

    @After
    fun tearDown() {
        server.close()
        store.clear()
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

    @Test
    fun `sync asks for the reminders of the next week and sets their alarms`() =
        runBlocking {
            answer(upcomingJson(reminderJson(1, NOW.plusSeconds(600)), reminderJson(2, NOW.plusSeconds(1200))))

            assertEquals(ReminderSyncResult.SYNCED, sync.sync())

            val request = server.takeRequest()
            assertEquals("/api/v1/reminders/upcoming", request.url.encodedPath)
            assertEquals(
                NOW.plus(Duration.ofDays(7)),
                java.time.OffsetDateTime
                    .parse(request.url.queryParameter("until"))
                    .toInstant(),
            )
            assertEquals(setOf(reminderId(1), reminderId(2)).map { it.toString() }.toSet(), alarmReminderIds(context).toSet())
            assertEquals("Call plumber 1", store.find(reminderId(1).toString())?.title)
        }

    @Test
    fun `a reminder deleted on the server loses its alarm on the next sync`() =
        runBlocking {
            answer(upcomingJson(reminderJson(1, NOW.plusSeconds(600)), reminderJson(2, NOW.plusSeconds(1200))))
            sync.sync()
            answer(upcomingJson(reminderJson(2, NOW.plusSeconds(1200))))

            sync.sync()

            assertEquals(listOf(reminderId(2).toString()), alarmReminderIds(context))
        }

    @Test
    fun `a server error keeps the alarms`() =
        runBlocking {
            answer(upcomingJson(reminderJson(1, NOW.plusSeconds(600))))
            sync.sync()
            answer("""{"title":"boom"}""", code = 500)

            assertEquals(ReminderSyncResult.FAILED, sync.sync())

            assertEquals(listOf(reminderId(1).toString()), alarmReminderIds(context))
        }

    @Test
    fun `an unreadable answer keeps the alarms and the worker does not retry`() =
        runBlocking {
            answer(upcomingJson(reminderJson(1, NOW.plusSeconds(600))))
            sync.sync()
            answer("""{"items":[{"id":"not-a-uuid"}]}""")

            assertEquals(ReminderSyncResult.FAILED, sync.sync())
            assertEquals(listOf(reminderId(1).toString()), alarmReminderIds(context))

            answer("{broken")
            assertEquals(ListenableWorker.Result.success(), worker().doWork())
            assertEquals(listOf(reminderId(1).toString()), alarmReminderIds(context))
        }

    @Test
    fun `a rejected request fails without touching the alarms`() =
        runBlocking {
            answer(upcomingJson(reminderJson(1, NOW.plusSeconds(600))))
            sync.sync()
            answer("""{"title":"Unauthorized","status":401}""", code = 401)

            assertEquals(ReminderSyncResult.FAILED, sync.sync())
            assertEquals(listOf(reminderId(1).toString()), alarmReminderIds(context))
        }

    @Test
    fun `an answer without a body fails without touching the alarms`() =
        runBlocking {
            answer(upcomingJson(reminderJson(1, NOW.plusSeconds(600))))
            sync.sync()
            server.enqueue(MockResponse.Builder().code(204).build())

            assertEquals(ReminderSyncResult.FAILED, sync.sync())
            assertEquals(listOf(reminderId(1).toString()), alarmReminderIds(context))
        }

    @Test
    fun `a cancelled sync is cancelled, not reported as failed`() {
        val cancelled = mockk<RemindersApi>()
        coEvery { cancelled.listUpcomingReminders(any()) } throws CancellationException("worker stopped")

        assertThrows(CancellationException::class.java) { runBlocking { ReminderSync(session, cancelled, scheduler).sync() } }
    }

    @Test
    fun `an unreachable server is offline and the worker retries`() =
        runBlocking {
            server.close()

            assertEquals(ReminderSyncResult.OFFLINE, sync.sync())
            assertEquals(ListenableWorker.Result.retry(), worker().doWork())
        }

    @Test
    fun `the worker succeeds after a sync`() =
        runBlocking {
            answer(upcomingJson())

            assertEquals(ListenableWorker.Result.success(), worker().doWork())
        }

    @Test
    fun `the worker WorkManager creates takes its sync from the app graph`() =
        runBlocking {
            // WorkManager's default factory uses the (Context, WorkerParameters) constructor. No one is logged in in the
            // test app, so the sync from the app graph ends without a request.
            val worker = TestListenableWorkerBuilder<ReminderSyncWorker>(context).build()

            assertEquals(ListenableWorker.Result.success(), worker.doWork())
            assertEquals(0, server.requestCount)
        }

    @Test
    fun `without a session nothing is requested`() =
        runBlocking {
            loggedIn = false

            assertEquals(ReminderSyncResult.SYNCED, sync.sync())
            assertEquals(0, server.requestCount)
        }

    @Test
    fun `a logout during the request sets no alarm`() =
        runBlocking {
            answer(upcomingJson(reminderJson(1, NOW.plusSeconds(600))))
            // The user logs out while the answer is on its way.
            val logoutFirst =
                object : okhttp3.Interceptor {
                    override fun intercept(chain: okhttp3.Interceptor.Chain): okhttp3.Response =
                        chain.proceed(chain.request()).also {
                            loggedIn =
                                false
                        }
                }
            val api =
                Retrofit
                    .Builder()
                    .baseUrl(server.url("/"))
                    .client(
                        okhttp3.OkHttpClient
                            .Builder()
                            .addInterceptor(logoutFirst)
                            .build(),
                    ).addConverterFactory(Serializer.kotlinxSerializationJson.asConverterFactory("application/json".toMediaType()))
                    .build()
                    .create(RemindersApi::class.java)

            ReminderSync(session, api, scheduler).sync()

            assertTrue(alarmReminderIds(context).isEmpty())
        }

    private fun worker(): ReminderSyncWorker =
        TestListenableWorkerBuilder<ReminderSyncWorker>(context)
            .setWorkerFactory(
                object : WorkerFactory() {
                    override fun createWorker(
                        appContext: Context,
                        workerClassName: String,
                        workerParameters: WorkerParameters,
                    ): ListenableWorker = ReminderSyncWorker(appContext, workerParameters) { sync }
                },
            ).build()
}
