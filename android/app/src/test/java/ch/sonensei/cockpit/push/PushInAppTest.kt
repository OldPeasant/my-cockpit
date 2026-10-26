package ch.sonensei.cockpit.push

import ch.sonensei.cockpit.auth.AuthRepository
import ch.sonensei.cockpit.auth.PersistentTokenStore
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
    fun `the push service passes a decrypted reminder push to the reminder sync`() {
        service().onMessage(PushMessage(payload("reminder"), true), INSTANCE)

        assertEquals(1, recordingSync.reminderSyncs)
        assertEquals(0, recordingNotifier.shown)
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
    }
}
