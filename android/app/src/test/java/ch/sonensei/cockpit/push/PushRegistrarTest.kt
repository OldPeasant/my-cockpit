package ch.sonensei.cockpit.push

import ch.sonensei.cockpit.core.network.AuthSession
import ch.sonensei.cockpit.core.network.generated.apis.PushApi
import ch.sonensei.cockpit.core.network.generated.infrastructure.Serializer
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.MediaType.Companion.toMediaType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class PushRegistrarTest {
    private val server = MockWebServer()
    private val loggedInFlow = MutableStateFlow(true)
    private val session = mockk<AuthSession> { every { isLoggedIn } answers { loggedInFlow.value } }
    private val connector = FakeConnector()
    private val store = FakeInstanceStore()
    private val scope = CoroutineScope(UnconfinedTestDispatcher())
    private lateinit var registrar: PushRegistrar

    @Before
    fun setUp() {
        server.start()
        val api =
            Retrofit
                .Builder()
                .baseUrl(server.url("/"))
                .addConverterFactory(Serializer.kotlinxSerializationJson.asConverterFactory("application/json".toMediaType()))
                .build()
                .create(PushApi::class.java)
        registrar = PushRegistrar(session, api, connector, store, loggedInFlow, scope)
    }

    @After
    fun tearDown() = server.close()

    private fun answer(code: Int = 204) = server.enqueue(MockResponse.Builder().code(code).build())

    @Test
    fun `registers with the distributor at app start when logged in`() {
        registrar.start()

        assertEquals(listOf("instance-1"), connector.registered)
        assertEquals("instance-1", store.current())
    }

    @Test
    fun `registers again after a new login`() {
        loggedInFlow.value = false
        registrar.start()
        assertEquals(emptyList<String>(), connector.registered)

        loggedInFlow.value = true

        assertEquals(listOf("instance-1"), connector.registered)
    }

    @Test
    fun `no distributor installed means no registration, only the periodic sync`() {
        connector.distributorInstalled = false

        registrar.start()

        assertEquals(emptyList<String>(), connector.registered)
        assertNull(store.current())
    }

    @Test
    fun `a failing library does not break the app start`() {
        connector.failure = IllegalStateException("database locked")

        registrar.start()

        assertEquals(emptyList<String>(), connector.registered)
    }

    @Test
    fun `a new endpoint is sent to the server with its WebPush keys`() {
        registrar.start()
        answer()

        registrar.onNewEndpoint("instance-1", ENDPOINT, "BPubKey", "authSecret")

        val request = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("PUT", request.method)
        assertEquals("/api/v1/push/unifiedpush/instance-1", request.url.encodedPath)
        val body = Json.decodeFromString<JsonObject>(request.body!!.utf8())
        assertEquals(ENDPOINT, body["endpoint"]!!.jsonPrimitive.content)
        assertEquals("BPubKey", body["p256dh"]!!.jsonPrimitive.content)
        assertEquals("authSecret", body["auth"]!!.jsonPrimitive.content)
    }

    @Test
    fun `an endpoint without keys is sent without keys`() {
        registrar.start()
        answer()

        registrar.onNewEndpoint("instance-1", ENDPOINT, null, null)

        val body = Json.decodeFromString<JsonObject>(server.takeRequest(1, TimeUnit.SECONDS)!!.body!!.utf8())
        assertEquals(ENDPOINT, body["endpoint"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, body["p256dh"] ?: JsonNull)
        assertEquals(JsonNull, body["auth"] ?: JsonNull)
    }

    @Test
    fun `an endpoint of another registration or after the logout is not sent`() {
        registrar.start()

        registrar.onNewEndpoint("an-older-login", ENDPOINT, null, null)
        loggedInFlow.value = false
        registrar.onNewEndpoint("instance-1", ENDPOINT, null, null)

        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a server error on the endpoint is swallowed`() {
        registrar.start()
        answer(500)

        registrar.onNewEndpoint("instance-1", ENDPOINT, null, null)

        assertEquals("PUT", server.takeRequest(1, TimeUnit.SECONDS)!!.method)
    }

    @Test
    fun `logout deletes the endpoint on the server, then unregisters at the distributor`() {
        registrar.start()
        answer()

        runBlocking { registrar.deleteOnServer() }
        val request = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("DELETE", request.method)
        assertEquals("/api/v1/push/unifiedpush/instance-1", request.url.encodedPath)

        registrar.clear()
        assertEquals(listOf("instance-1"), connector.unregistered)
        assertNull(store.current())
    }

    @Test
    fun `logout without a registration does nothing`() {
        runBlocking { registrar.deleteOnServer() }
        registrar.clear()

        assertEquals(0, server.requestCount)
        assertEquals(emptyList<String>(), connector.unregistered)
    }

    @Test
    fun `an unreachable server does not stop the logout`() {
        registrar.start()
        server.close()

        runBlocking { registrar.deleteOnServer() }
        registrar.clear()

        assertNull(store.current())
    }

    @Test
    fun `the distributor dropping the registration deletes it on the server`() {
        registrar.start()
        answer()

        registrar.onUnregistered("instance-1")

        assertEquals("DELETE", server.takeRequest(1, TimeUnit.SECONDS)!!.method)
    }

    @Test
    fun `the next login registers a new instance`() {
        registrar.start()
        registrar.clear()
        loggedInFlow.value = false

        loggedInFlow.value = true

        assertEquals(listOf("instance-1", "instance-2"), connector.registered)
        assertFalse(store.current() == "instance-1")
    }

    private class FakeConnector : UnifiedPushConnector {
        var distributorInstalled = true
        var failure: RuntimeException? = null
        val registered = mutableListOf<String>()
        val unregistered = mutableListOf<String>()

        override fun selectDistributor(): Boolean {
            failure?.let { throw it }
            return distributorInstalled
        }

        override fun register(instance: String) {
            registered += instance
        }

        override fun unregister(instance: String) {
            unregistered += instance
        }
    }

    private companion object {
        const val ENDPOINT = "https://ntfy.example.test/upAbc123?up=1"
    }
}
