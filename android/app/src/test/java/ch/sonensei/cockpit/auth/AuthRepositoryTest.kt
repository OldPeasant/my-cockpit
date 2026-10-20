package ch.sonensei.cockpit.auth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.core.network.AccessTokenInterceptor
import ch.sonensei.cockpit.core.network.AuthSession
import ch.sonensei.cockpit.core.network.AuthTokens
import ch.sonensei.cockpit.core.network.CockpitHttp
import ch.sonensei.cockpit.core.network.LogoutReason
import ch.sonensei.cockpit.core.network.ServerUrlInterceptor
import ch.sonensei.cockpit.core.network.ServerUrlRepository
import ch.sonensei.cockpit.core.network.ServerUrlValidation
import ch.sonensei.cockpit.core.network.generated.apis.AuthApi
import ch.sonensei.cockpit.core.network.serverConfigDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** [AuthRepository] against a fake server: login answers, stored tokens, logout. */
@RunWith(AndroidJUnit4::class)
class AuthRepositoryTest {
    private val server = MockWebServer()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var tokenStore: PersistentTokenStore
    private lateinit var session: AuthSession
    private lateinit var serverUrls: ServerUrlRepository
    private lateinit var repository: AuthRepository
    private var cachesCleared = 0

    @Before
    fun setUp() {
        server.start()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("auth-repository-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        tokenStore = PersistentTokenStore(prefs, KeystoreTokenCipher())
        session = AuthSession(tokenStore)
        val file = File(context.filesDir, "server-${System.nanoTime()}.preferences_pb")
        serverUrls = ServerUrlRepository(serverConfigDataStore(scope) { file }, session)
        val client: OkHttpClient =
            CockpitHttp.okHttpClient(
                ServerUrlInterceptor(serverUrls),
                AccessTokenInterceptor(tokenStore),
                null,
            )
        val authApi = CockpitHttp.retrofit(client).create(AuthApi::class.java)
        repository = AuthRepository(serverUrls, authApi, tokenStore, session, setOf(SessionCache { cachesCleared++ }))
    }

    @After
    fun tearDown() {
        server.close()
        scope.cancel()
    }

    @Test
    fun `a successful login stores the tokens and the server`() =
        runBlocking {
            server.enqueue(json(200, TOKEN_BODY))

            val result = repository.login(serverAddress(), " alex ", "fictitious-password", SecondFactor.Totp("123456"))

            assertEquals(LoginResult.Success, result)
            assertEquals(DeviceSession(AuthTokens("access-1", "refresh-1"), DEVICE_ID), tokenStore.session.value)
            assertEquals(serverAddress(), repository.serverUrl.first())
            val recorded = server.takeRequest()
            assertEquals("/api/v1/auth/token", recorded.url.encodedPath)
            val body = Json.parseToJsonElement(recorded.body!!.utf8()).jsonObject
            assertEquals("alex", body["username"]!!.jsonPrimitive.content)
            assertEquals("fictitious-password", body["password"]!!.jsonPrimitive.content)
            assertEquals("123456", body["totp"]!!.jsonPrimitive.content)
            assertTrue(body["recoveryCode"].let { it == null || it is JsonNull })
            assertTrue(body["deviceName"]!!.jsonPrimitive.content.isNotBlank())
        }

    @Test
    fun `a recovery code is sent instead of a totp`() =
        runBlocking {
            server.enqueue(json(200, TOKEN_BODY))

            repository.login(serverAddress(), "alex", "pw", SecondFactor.RecoveryCode("abcde-fghjk"))

            val body = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
            assertEquals("abcde-fghjk", body["recoveryCode"]!!.jsonPrimitive.content)
            assertTrue(body["totp"].let { it == null || it is JsonNull })
        }

    @Test
    fun `401 is wrong credentials and stores nothing`() =
        runBlocking {
            server.enqueue(json(401, PROBLEM))

            assertEquals(LoginResult.WrongCredentials, login())
            assertNull(tokenStore.current())
        }

    @Test
    fun `400 is an invalid request`() =
        runBlocking {
            server.enqueue(json(400, PROBLEM))

            assertEquals(LoginResult.InvalidRequest, login())
        }

    @Test
    fun `429 passes on the Retry-After seconds`() =
        runBlocking {
            server.enqueue(json(429, PROBLEM).newBuilder().addHeader("Retry-After", "42").build())

            assertEquals(LoginResult.TooManyAttempts(42), login())
        }

    @Test
    fun `429 without a usable Retry-After waits the default`() =
        runBlocking {
            server.enqueue(json(429, PROBLEM))
            server.enqueue(json(429, PROBLEM).newBuilder().addHeader("Retry-After", "Wed, 21 Oct 2026 07:28:00 GMT").build())

            assertEquals(LoginResult.TooManyAttempts(AuthRepository.DEFAULT_RETRY_AFTER_SECONDS), login())
            assertEquals(LoginResult.TooManyAttempts(AuthRepository.DEFAULT_RETRY_AFTER_SECONDS), login())
        }

    @Test
    fun `server errors and unreadable bodies are server errors`() =
        runBlocking {
            server.enqueue(MockResponse.Builder().code(500).build())
            server.enqueue(json(200, """{"accessToken":"only"}"""))

            assertEquals(LoginResult.ServerError, login())
            assertEquals(LoginResult.ServerError, login())
            assertNull(tokenStore.current())
        }

    @Test
    fun `an unreachable server is a network error`() =
        runBlocking {
            val closed = MockWebServer()
            closed.start()
            val address = closed.url("/").toString()
            closed.close()

            assertEquals(LoginResult.Network, repository.login(address, "alex", "pw", SecondFactor.Totp("123456")))
        }

    @Test
    fun `an invalid server address is rejected before any request`() =
        runBlocking {
            assertEquals(
                LoginResult.InvalidServerUrl(ServerUrlValidation.Reason.HTTPS_REQUIRED),
                repository.login("http://cockpit.example.org", "alex", "pw", SecondFactor.Totp("123456")),
            )
            assertEquals(
                LoginResult.InvalidServerUrl(ServerUrlValidation.Reason.MALFORMED),
                repository.login("not a url", "alex", "pw", SecondFactor.Totp("123456")),
            )
            assertEquals(0, server.requestCount)
        }

    @Test
    fun `logout revokes this device, forgets the tokens and clears the caches`() =
        runBlocking {
            server.enqueue(json(200, TOKEN_BODY))
            login()
            server.takeRequest()
            server.enqueue(MockResponse.Builder().code(204).build())
            val reason = async(Dispatchers.Unconfined) { session.loggedOut.first() }

            repository.logout()

            val revoke = server.takeRequest()
            assertEquals("DELETE", revoke.method)
            assertEquals("/api/v1/auth/devices/$DEVICE_ID", revoke.url.encodedPath)
            assertEquals("Bearer access-1", revoke.headers["Authorization"])
            assertNull(tokenStore.current())
            assertEquals(1, cachesCleared)
            assertEquals(LogoutReason.USER, reason.await())
        }

    @Test
    fun `logout still happens locally when the server cannot be reached`() =
        runBlocking {
            server.enqueue(json(200, TOKEN_BODY))
            login()
            server.close()

            repository.logout()

            assertNull(tokenStore.current())
            assertEquals(1, cachesCleared)
        }

    private suspend fun login() = repository.login(serverAddress(), "alex", "pw", SecondFactor.Totp("123456"))

    private fun serverAddress() = server.url("/").toString()

    private fun json(
        code: Int,
        body: String,
    ) = MockResponse
        .Builder()
        .code(code)
        .addHeader("Content-Type", if (code < 400) "application/json" else "application/problem+json")
        .body(body)
        .build()

    private companion object {
        const val DEVICE_ID = "0192f1a0-0000-7000-8000-000000000001"
        const val PROBLEM = """{"type":"about:blank","title":"Error","status":401}"""
        val TOKEN_BODY =
            """
            {"accessToken":"access-1","accessTokenExpiresAt":"2026-10-20T12:15:00Z",
             "deviceId":"$DEVICE_ID","refreshToken":"refresh-1",
             "refreshTokenExpiresAt":"2027-01-18T12:00:00Z","tokenType":"Bearer"}
            """.trimIndent()
    }
}
