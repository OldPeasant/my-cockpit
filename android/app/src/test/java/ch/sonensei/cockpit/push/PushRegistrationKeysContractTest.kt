package ch.sonensei.cockpit.push

import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.core.network.AuthSession
import ch.sonensei.cockpit.core.network.generated.apis.PushApi
import ch.sonensei.cockpit.core.network.generated.infrastructure.Serializer
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.MediaType.Companion.toMediaType
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.unifiedpush.android.connector.data.PublicKeySet
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * The registration direction of the push contract: the keys the UnifiedPush connector hands to
 * [CockpitPushService.onNewEndpoint] reach the server's `PUT /api/v1/push/unifiedpush/{instanceId}` unchanged and in
 * the format the server's `WebPushKeys` accepts: base64url (padding optional) of a 65-byte uncompressed P-256 point
 * (`p256dh`) and of a 16-byte secret (`auth`). The server side of the same format is pinned by `UnifiedPushWebTests`
 * (real keys, unpadded base64url, 204).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class PushRegistrationKeysContractTest {
    private val server = MockWebServer()
    private val session = mockk<AuthSession> { every { isLoggedIn } returns true }
    private val store = FakeInstanceStore(INSTANCE)
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
        registrar =
            PushRegistrar(
                session,
                api,
                mockk<UnifiedPushConnector>(relaxed = true),
                store,
                MutableStateFlow(true),
                CoroutineScope(UnconfinedTestDispatcher()),
            )
    }

    @After
    fun tearDown() = server.close()

    /** The connector's own encoders (`internal/keys/Utils.kt`), which fill the [PublicKeySet] it hands to the app. */
    private val connectorKeys = Class.forName("org.unifiedpush.android.connector.internal.keys.UtilsKt")

    private fun connectorSerialize(key: ECPublicKey) =
        connectorKeys.getMethod("serialize", ECPublicKey::class.java).invoke(null, key) as String

    private fun connectorB64(bytes: ByteArray) = connectorKeys.getMethod("b64encode", ByteArray::class.java).invoke(null, bytes) as String

    @Test
    fun `real connector keys are sent unchanged in the format the server accepts`() {
        val publicKey =
            KeyPairGenerator
                .getInstance("EC")
                .apply { initialize(ECGenParameterSpec("secp256r1")) }
                .generateKeyPair()
                .public as ECPublicKey
        // Fictitious secret with bytes that differ between base64 and base64url (0xFB -> "-_" vs "+/").
        val secret = ByteArray(16) { if (it % 2 == 0) 0xFB.toByte() else 0xEF.toByte() }
        val keys = PublicKeySet(connectorSerialize(publicKey), connectorB64(secret))
        server.enqueue(MockResponse.Builder().code(204).build())

        // What CockpitPushService.onNewEndpoint passes on.
        registrar.onNewEndpoint(INSTANCE, ENDPOINT, keys.pubKey, keys.auth)

        val put = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("PUT", put.method)
        assertEquals("/api/v1/push/unifiedpush/$INSTANCE", put.url.encodedPath)
        val body = Json.parseToJsonElement(put.body!!.utf8()).jsonObject
        assertEquals(setOf("endpoint", "p256dh", "auth"), body.keys)
        assertEquals(ENDPOINT, body["endpoint"]!!.jsonPrimitive.content)
        val p256dh = body["p256dh"]!!.jsonPrimitive.content
        val auth = body["auth"]!!.jsonPrimitive.content
        assertEquals(keys.pubKey, p256dh)
        assertEquals(keys.auth, auth)

        // The server's WebPushKeys rules: base64url, 65-byte uncompressed point, 16-byte secret.
        listOf(p256dh, auth).forEach { assertTrue(it, Regex("^[A-Za-z0-9_-]+$").matches(it)) }
        val point = Base64.getUrlDecoder().decode(p256dh)
        assertEquals(65, point.size)
        assertEquals(0x04.toByte(), point[0])
        assertEquals(publicKey.w.affineX, BigInteger(1, point.copyOfRange(1, 33)))
        assertEquals(publicKey.w.affineY, BigInteger(1, point.copyOfRange(33, 65)))
        assertArrayEquals(secret, Base64.getUrlDecoder().decode(auth))
    }

    private companion object {
        const val INSTANCE = "0b4f7d58-34a2-4b1e-a9b3-6f3a1d2c9e01"
        const val ENDPOINT = "https://ntfy.example.test/upFictitious?up=1"
    }
}
