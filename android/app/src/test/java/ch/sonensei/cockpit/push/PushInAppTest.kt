package ch.sonensei.cockpit.push

import androidx.test.core.app.ApplicationProvider
import ch.sonensei.cockpit.auth.AuthRepository
import ch.sonensei.cockpit.auth.LoginResult
import ch.sonensei.cockpit.auth.PersistentTokenStore
import ch.sonensei.cockpit.auth.SecondFactor
import ch.sonensei.cockpit.core.network.AuthSession
import ch.sonensei.cockpit.core.network.AuthTokens
import ch.sonensei.cockpit.core.network.LogoutReason
import ch.sonensei.cockpit.core.network.ServerUrlRepository
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import dagger.hilt.android.testing.UninstallModules
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.data.PublicKeySet
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * UnifiedPush in the real Hilt graph and HTTP client against a fake server; only the distributor library, the instance
 * store and the Android adapters are fakes. Covers the wiring: the push service reaching the handler and registrar, the
 * logout hook deleting the endpoint before the device is revoked, and the session cache unregistering on every logout.
 */
@HiltAndroidTest
@UninstallModules(PushBindings::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
class PushInAppTest {
    @get:Rule
    val hilt = HiltAndroidRule(this)

    @BindValue
    @JvmField
    val connector: UnifiedPushConnector = RecordingConnector()

    @BindValue
    @JvmField
    val store: PushInstanceStore = FakeInstanceStore()

    @BindValue
    @JvmField
    val sync: PushSyncTrigger = RecordingSyncTrigger()

    @BindValue
    @JvmField
    val notifier: NewMailNotifier = RecordingMailNotifier()

    @Inject lateinit var authRepository: AuthRepository

    @Inject lateinit var tokenStore: PersistentTokenStore

    @Inject lateinit var session: AuthSession

    @Inject lateinit var serverUrls: ServerUrlRepository

    @Inject lateinit var registrar: PushRegistrar

    private val server = MockWebServer()
    private val recording get() = connector as RecordingConnector
    private val fakeStore get() = store as FakeInstanceStore
    private val recordingSync get() = sync as RecordingSyncTrigger
    private val recordingNotifier get() = notifier as RecordingMailNotifier

    @Before
    fun setUp() {
        hilt.inject()
        server.start()
        runBlocking { serverUrls.setServerUrl(server.url("/").toString()) }
        tokenStore.login(AuthTokens("access-fictitious", "refresh-fictitious"), DEVICE_ID)
        fakeStore.instance = INSTANCE
    }

    @After
    fun tearDown() = server.close()

    private fun service(): CockpitPushService = Robolectric.buildService(CockpitPushService::class.java).create().get()

    private fun answer(code: Int = 204) = server.enqueue(MockResponse.Builder().code(code).build())

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + WAIT_MS
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertTrue("condition not reached within $WAIT_MS ms", condition())
    }

    @Test
    fun `a user logout deletes the endpoint on the server before the device is revoked, then unregisters`() {
        answer()
        answer()

        runBlocking { authRepository.logout() }

        val delete = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("DELETE", delete.method)
        assertEquals("/api/v1/push/unifiedpush/$INSTANCE", delete.url.encodedPath)
        assertEquals("the delete is still authenticated", "Bearer access-fictitious", delete.headers["Authorization"])
        val revoke = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("/api/v1/auth/devices/$DEVICE_ID", revoke.url.encodedPath)
        assertEquals(listOf(INSTANCE), recording.unregistered)
        assertNull(store.current())
        assertEquals(1, recordingNotifier.dismissed)
        assertNull(tokenStore.current())
    }

    @Test
    fun `a user logout goes on when the server rejects the endpoint delete`() {
        answer(500)
        answer()

        runBlocking { authRepository.logout() }

        assertEquals("/api/v1/push/unifiedpush/$INSTANCE", server.takeRequest(5, TimeUnit.SECONDS)!!.url.encodedPath)
        assertEquals("/api/v1/auth/devices/$DEVICE_ID", server.takeRequest(5, TimeUnit.SECONDS)!!.url.encodedPath)
        assertEquals(listOf(INSTANCE), recording.unregistered)
        assertNull(tokenStore.current())
    }

    @Test
    fun `a logout by the network layer unregisters locally without a server request`() {
        session.logout(LogoutReason.REFRESH_REJECTED)

        waitFor { recordingNotifier.dismissed == 1 }
        assertEquals(listOf(INSTANCE), recording.unregistered)
        assertNull(store.current())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `the push service passes a decrypted reminder push to the reminder delivery and the reminder sync`() {
        service().onMessage(PushMessage(payload("reminder"), true), INSTANCE)

        assertEquals(listOf(java.util.UUID.fromString(ITEM_ID)), recordingSync.shownReminders)
        assertEquals(1, recordingSync.reminderSyncs)
        assertEquals(0, recordingNotifier.shown)
    }

    @Test
    fun `the push reminder worker's delivery loads the pushed reminder through the app's authenticated client`() {
        server.enqueue(
            MockResponse
                .Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body(
                    """{"id":"$ITEM_ID","channel":"NOTIFICATION","createdAt":"2026-10-21T07:00:00Z",""" +
                        """"remindAt":"2026-10-21T08:00:00Z","firedAt":"2026-10-21T08:00:01Z",""" +
                        """"item":{"available":true,"id":"$ITEM_ID","type":"TASK","title":"Water the ficus"}}""",
                ).build(),
        )
        val delivery = pushGraph(ApplicationProvider.getApplicationContext()).pushReminderDelivery()

        val result = runBlocking { delivery.deliver(java.util.UUID.fromString(ITEM_ID)) }

        assertEquals(PushReminderResult.SHOWN, result)
        val get = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("GET", get.method)
        assertEquals("/api/v1/reminders/$ITEM_ID", get.url.encodedPath)
        assertEquals("Bearer access-fictitious", get.headers["Authorization"])
    }

    @Test
    fun `the push service passes a decrypted new-mail push to the notification`() {
        service().onMessage(PushMessage(payload("mail"), true), INSTANCE)

        assertEquals(1, recordingNotifier.shown)
        assertEquals(0, recordingSync.reminderSyncs)
    }

    @Test
    fun `the push service ignores a push that was not decrypted`() {
        service().onMessage(PushMessage(payload("mail"), false), INSTANCE)

        assertEquals(0, recordingNotifier.shown)
        assertEquals(0, recordingSync.reminderSyncs)
    }

    @Test
    fun `the push service sends a new endpoint with its keys to the server`() {
        answer()

        service().onNewEndpoint(PushEndpoint(ENDPOINT, PublicKeySet("fictitious-p256dh", "fictitious-auth"), false), INSTANCE)

        val put = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("PUT", put.method)
        assertEquals("/api/v1/push/unifiedpush/$INSTANCE", put.url.encodedPath)
        val body = Json.parseToJsonElement(put.body!!.utf8()).jsonObject
        assertEquals(ENDPOINT, body["endpoint"]!!.jsonPrimitive.content)
        assertEquals("fictitious-p256dh", body["p256dh"]!!.jsonPrimitive.content)
        assertEquals("fictitious-auth", body["auth"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the push service deletes the endpoint when the distributor unregisters`() {
        answer()

        service().onUnregistered(INSTANCE)

        val delete = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("DELETE", delete.method)
        assertEquals("/api/v1/push/unifiedpush/$INSTANCE", delete.url.encodedPath)
    }

    @Test
    fun `a failed registration changes nothing`() {
        service().onRegistrationFailed(FailedReason.entries.first(), INSTANCE)

        assertEquals(INSTANCE, store.current())
        assertTrue(recording.unregistered.isEmpty())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `the registrar of the app graph registers at start while logged in`() {
        fakeStore.instance = null

        registrar.start()

        waitFor { recording.registered.isNotEmpty() }
        assertEquals(listOf("instance-1"), recording.registered.toList())
    }

    @Test
    fun `a login through the login screen's repository registers with the distributor`() {
        // The app started logged out: the registrar waits for the login of the real token store.
        tokenStore.clear()
        fakeStore.instance = null
        registrar.start()
        server.enqueue(
            MockResponse
                .Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body(TOKEN_BODY)
                .build(),
        )

        val result =
            runBlocking {
                authRepository.login(server.url("/").toString(), "alex", "fictitious-password", SecondFactor.Totp("123456"))
            }

        assertEquals(LoginResult.Success, result)
        waitFor { recording.registered.isNotEmpty() }
        assertEquals(listOf("instance-1"), recording.registered.toList())
        assertEquals("instance-1", store.current())
    }

    @Test
    fun `the endpoint is sent to the server with the login's access token`() {
        answer()

        service().onNewEndpoint(PushEndpoint(ENDPOINT, null, false), INSTANCE)

        val put = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("PUT", put.method)
        assertEquals("Bearer access-fictitious", put.headers["Authorization"])
    }

    @Test
    fun `the push service ignores a garbled push`() {
        service().onMessage(PushMessage("""{"kind":"reminder","id":"not-a-uuid"}""".toByteArray(), true), INSTANCE)
        service().onMessage(PushMessage("""{"kind":"calendar","id":"$ITEM_ID"}""".toByteArray(), true), INSTANCE)

        assertEquals(0, recordingSync.reminderSyncs)
        assertEquals(0, recordingNotifier.shown)
    }

    @Test
    fun `a push that arrives after a user logout is ignored`() {
        answer()
        answer()
        runBlocking { authRepository.logout() }

        service().onMessage(PushMessage(payload("mail"), true), INSTANCE)
        service().onMessage(PushMessage(payload("reminder"), true), INSTANCE)

        assertEquals(0, recordingNotifier.shown)
        assertEquals(0, recordingSync.reminderSyncs)
    }

    @Test
    fun `a user logout without a push registration only revokes the device`() {
        // No distributor installed: nothing was registered, so there is no endpoint to delete or unregister.
        fakeStore.instance = null
        answer()

        runBlocking { authRepository.logout() }

        assertEquals("/api/v1/auth/devices/$DEVICE_ID", server.takeRequest(5, TimeUnit.SECONDS)!!.url.encodedPath)
        assertEquals(1, server.requestCount)
        assertTrue(recording.unregistered.isEmpty())
        assertNull(tokenStore.current())
    }

    private fun payload(kind: String) = """{"kind":"$kind","id":"$ITEM_ID"}""".toByteArray()

    private class RecordingConnector : UnifiedPushConnector {
        val registered = CopyOnWriteArrayList<String>()
        val unregistered = CopyOnWriteArrayList<String>()

        override fun selectDistributor() = true

        override fun register(instance: String) {
            registered += instance
        }

        override fun unregister(instance: String) {
            unregistered += instance
        }
    }

    private companion object {
        const val WAIT_MS = 5_000L
        const val DEVICE_ID = "0192f1a0-0000-7000-8000-000000000001"
        const val INSTANCE = "0b4f7d58-34a2-4b1e-a9b3-6f3a1d2c9e01"
        const val ITEM_ID = "0192f000-0000-7000-8000-000000000002"
        const val ENDPOINT = "https://ntfy.example.test/upFictitious?up=1"
        val TOKEN_BODY =
            """
            {"accessToken":"access-1","accessTokenExpiresAt":"2026-10-20T12:15:00Z",
             "deviceId":"$DEVICE_ID","refreshToken":"refresh-1",
             "refreshTokenExpiresAt":"2027-01-18T12:00:00Z","tokenType":"Bearer"}
            """.trimIndent()
    }
}
