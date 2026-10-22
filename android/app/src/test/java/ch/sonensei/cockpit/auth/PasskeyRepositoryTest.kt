package ch.sonensei.cockpit.auth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.core.network.AccessTokenInterceptor
import ch.sonensei.cockpit.core.network.AuthSession
import ch.sonensei.cockpit.core.network.AuthTokens
import ch.sonensei.cockpit.core.network.CockpitHttp
import ch.sonensei.cockpit.core.network.ServerUrlInterceptor
import ch.sonensei.cockpit.core.network.ServerUrlRepository
import ch.sonensei.cockpit.core.network.ServerUrlValidation
import ch.sonensei.cockpit.core.network.generated.apis.PasskeysApi
import ch.sonensei.cockpit.core.network.serverConfigDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** [PasskeyRepository] against a fake server and a fake authenticator. */
@RunWith(AndroidJUnit4::class)
class PasskeyRepositoryTest {
    private val server = MockWebServer()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var tokenStore: PersistentTokenStore
    private lateinit var repository: PasskeyRepository
    private lateinit var serverUrls: ServerUrlRepository
    private val authenticator = FakePasskeys()

    @Before
    fun setUp() {
        server.start()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("passkey-repository-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        tokenStore = PersistentTokenStore(prefs, KeystoreTokenCipher())
        val session = AuthSession(tokenStore)
        val file = File(context.filesDir, "server-${System.nanoTime()}.preferences_pb")
        serverUrls = ServerUrlRepository(serverConfigDataStore(scope) { file }, session)
        val retrofit =
            CockpitHttp.retrofit(
                CockpitHttp.okHttpClient(ServerUrlInterceptor(serverUrls), AccessTokenInterceptor(tokenStore), null),
            )
        repository =
            PasskeyRepository(
                serverUrls,
                retrofit.create(PasskeysApi::class.java),
                retrofit.create(PasskeyCeremonyApi::class.java),
                tokenStore,
                Dispatchers.IO,
            )
    }

    @After
    fun tearDown() {
        server.close()
        scope.cancel()
    }

    @Test
    fun `a passkey login passes the options unchanged and stores the tokens`() =
        runBlocking {
            server.enqueue(json(200, AUTH_OPTIONS))
            server.enqueue(json(200, TOKEN_BODY))
            authenticator.next = CredentialOutcome.Success(ASSERTION)

            val result = repository.login(serverAddress(), authenticator)

            assertEquals(LoginResult.Success, result)
            assertEquals(AuthTokens("access-1", "refresh-1"), tokenStore.current())
            assertEquals(DEVICE_ID, tokenStore.session.value?.deviceId)
            // Fields the generated model does not know (extensions) still reach the authenticator.
            val options = Json.parseToJsonElement(authenticator.requests.single()).jsonObject
            assertEquals("Y2hhbGxlbmdl", options["challenge"]!!.jsonPrimitive.content)
            assertTrue("extensions" in options)
            assertEquals("/api/v1/auth/passkeys/authentication/options", server.takeRequest().url.encodedPath)
            val token = server.takeRequest()
            assertEquals("/api/v1/auth/passkeys/token", token.url.encodedPath)
            val body = Json.parseToJsonElement(token.body!!.utf8()).jsonObject
            assertEquals("ceremony-1", body["ceremonyId"]!!.jsonPrimitive.content)
            assertEquals("cred-1", body["credential"]!!.jsonObject["id"]!!.jsonPrimitive.content)
            assertTrue(body["deviceName"]!!.jsonPrimitive.content.isNotBlank())
        }

    @Test
    fun `an invalid server address stops before any request`() =
        runBlocking {
            val result = repository.login("ftp://cockpit.example.org", authenticator)

            assertEquals(LoginResult.InvalidServerUrl(ServerUrlValidation.Reason.MALFORMED), result)
            assertEquals(0, server.requestCount)
        }

    @Test
    fun `authenticator outcomes other than success end the login without a token request`() =
        runBlocking {
            val expected =
                mapOf(
                    CredentialOutcome.Cancelled to LoginResult.PasskeyCancelled,
                    CredentialOutcome.NoCredential to LoginResult.NoPasskey,
                    CredentialOutcome.Unavailable to LoginResult.PasskeyUnavailable,
                    CredentialOutcome.Failed to LoginResult.PasskeyFailed,
                    CredentialOutcome.Success("not json") to LoginResult.PasskeyFailed,
                    CredentialOutcome.Success("[1]") to LoginResult.PasskeyFailed,
                )
            expected.forEach { (outcome, result) ->
                server.enqueue(json(200, AUTH_OPTIONS))
                authenticator.next = outcome
                assertEquals(outcome.toString(), result, repository.login(serverAddress(), authenticator))
            }
            assertEquals(expected.size, server.requestCount)
            assertNull(tokenStore.current())
        }

    @Test
    fun `token answers map to login results`() =
        runBlocking {
            val expected =
                listOf(
                    json(400, PROBLEM) to LoginResult.PasskeyFailed,
                    json(401, PROBLEM) to LoginResult.PasskeyRejected,
                    json(403, PROBLEM) to LoginResult.TotpResetRequired,
                    json(429, PROBLEM).newBuilder().addHeader("Retry-After", "17").build() to LoginResult.TooManyAttempts(17),
                    json(500, PROBLEM) to LoginResult.ServerError,
                    json(200, """{"accessToken":"only"}""") to LoginResult.ServerError,
                )
            expected.forEach { (answer, result) ->
                server.enqueue(json(200, AUTH_OPTIONS))
                server.enqueue(answer)
                authenticator.next = CredentialOutcome.Success(ASSERTION)
                assertEquals(result, repository.login(serverAddress(), authenticator))
            }
            assertNull(tokenStore.current())
        }

    @Test
    fun `failing option requests never open the passkey sheet`() =
        runBlocking {
            server.enqueue(json(500, PROBLEM))
            server.enqueue(json(429, PROBLEM).newBuilder().addHeader("Retry-After", "5").build())

            assertEquals(LoginResult.ServerError, repository.login(serverAddress(), authenticator))
            assertEquals(LoginResult.TooManyAttempts(5), repository.login(serverAddress(), authenticator))
            val address = serverAddress()
            server.close()
            assertEquals(LoginResult.Network, repository.login(address, authenticator))
            assertTrue(authenticator.requests.isEmpty())
        }

    @Test
    fun `registration sends the code, then the authenticator's response with the name`() =
        runBlocking {
            loggedIn()
            server.enqueue(json(200, CREATION_OPTIONS))
            server.enqueue(json(201, PASSKEY))
            authenticator.next = CredentialOutcome.Success(ATTESTATION)

            val result = repository.register(" Pixel ", " 123456 ", authenticator)

            assertEquals("Pixel", (result as PasskeyRegistrationResult.Success).passkey.name)
            val options = server.takeRequest()
            assertEquals("/api/v1/auth/passkeys/registration/options", options.url.encodedPath)
            assertEquals("Bearer access-1", options.headers["Authorization"])
            assertEquals(
                "123456",
                Json
                    .parseToJsonElement(options.body!!.utf8())
                    .jsonObject["totp"]!!
                    .jsonPrimitive.content,
            )
            assertEquals(
                "alex",
                Json
                    .parseToJsonElement(
                        authenticator.registrations.single(),
                    ).jsonObject["user"]!!
                    .jsonObject["name"]!!
                    .jsonPrimitive.content,
            )
            val finish = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
            assertEquals("ceremony-2", finish["ceremonyId"]!!.jsonPrimitive.content)
            assertEquals("Pixel", finish["name"]!!.jsonPrimitive.content)
            assertEquals("cred-2", finish["credential"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        }

    @Test
    fun `registration errors map to results`() =
        runBlocking {
            loggedIn()
            server.enqueue(json(403, PROBLEM))
            assertEquals(PasskeyRegistrationResult.WrongCode, repository.register("Pixel", "000000", authenticator))
            server.enqueue(json(429, PROBLEM).newBuilder().addHeader("Retry-After", "9").build())
            assertEquals(PasskeyRegistrationResult.TooManyAttempts(9), repository.register("Pixel", "000000", authenticator))
            assertTrue(authenticator.registrations.isEmpty())

            val outcomes =
                mapOf(
                    CredentialOutcome.Cancelled to PasskeyRegistrationResult.Cancelled,
                    CredentialOutcome.AlreadyRegistered to PasskeyRegistrationResult.AlreadyRegistered,
                    CredentialOutcome.Unavailable to PasskeyRegistrationResult.Unavailable,
                    CredentialOutcome.Failed to PasskeyRegistrationResult.Failed,
                )
            outcomes.forEach { (outcome, result) ->
                server.enqueue(json(200, CREATION_OPTIONS))
                authenticator.next = outcome
                assertEquals(result, repository.register("Pixel", "123456", authenticator))
            }

            listOf(
                json(409, PROBLEM) to PasskeyRegistrationResult.AlreadyRegistered,
                json(400, PROBLEM) to PasskeyRegistrationResult.Failed,
                json(500, PROBLEM) to PasskeyRegistrationResult.ServerError,
            ).forEach { (answer, result) ->
                server.enqueue(json(200, CREATION_OPTIONS))
                server.enqueue(answer)
                authenticator.next = CredentialOutcome.Success(ATTESTATION)
                assertEquals(result, repository.register("Pixel", "123456", authenticator))
            }
        }

    @Test
    fun `the list is null when it cannot be loaded`() =
        runBlocking {
            loggedIn()
            server.enqueue(json(200, "[$PASSKEY]"))
            server.enqueue(json(500, PROBLEM))

            assertEquals(listOf("Pixel"), repository.list()!!.map { it.name })
            assertNull(repository.list())
        }

    @Test
    fun `a 429 without a usable Retry-After waits the default time`() =
        runBlocking {
            server.enqueue(json(429, PROBLEM))
            server.enqueue(json(200, AUTH_OPTIONS))
            server.enqueue(json(429, PROBLEM).newBuilder().addHeader("Retry-After", "soon").build())
            authenticator.next = CredentialOutcome.Success(ASSERTION)

            val default = LoginResult.TooManyAttempts(AuthRepository.DEFAULT_RETRY_AFTER_SECONDS)
            assertEquals(default, repository.login(serverAddress(), authenticator))
            assertEquals(default, repository.login(serverAddress(), authenticator))
        }

    @Test
    fun `options without a body are a server error and never open the passkey sheet`() =
        runBlocking {
            server.enqueue(MockResponse.Builder().code(204).build())

            assertEquals(LoginResult.ServerError, repository.login(serverAddress(), authenticator))
            assertTrue(authenticator.requests.isEmpty())
        }

    @Test
    fun `an authenticator that answers with a registration outcome fails the login`() =
        runBlocking {
            server.enqueue(json(200, AUTH_OPTIONS))
            authenticator.next = CredentialOutcome.AlreadyRegistered

            assertEquals(LoginResult.PasskeyFailed, repository.login(serverAddress(), authenticator))
            assertEquals(1, server.requestCount)
            assertNull(tokenStore.current())
        }

    @Test
    fun `losing the server after the passkey sheet is a network error and stores nothing`() =
        runBlocking {
            server.enqueue(json(200, AUTH_OPTIONS))
            authenticator.next = CredentialOutcome.Success(ASSERTION)
            authenticator.onCall = { server.close() }

            assertEquals(LoginResult.Network, repository.login(serverAddress(), authenticator))
            assertNull(tokenStore.current())
        }

    @Test
    fun `failing registration option requests never open the passkey sheet`() =
        runBlocking {
            loggedIn()
            server.enqueue(json(400, PROBLEM))
            server.enqueue(json(500, PROBLEM))
            server.enqueue(MockResponse.Builder().code(204).build())

            assertEquals(PasskeyRegistrationResult.WrongCode, repository.register("Pixel", "", authenticator))
            assertEquals(PasskeyRegistrationResult.ServerError, repository.register("Pixel", "123456", authenticator))
            assertEquals(PasskeyRegistrationResult.ServerError, repository.register("Pixel", "123456", authenticator))
            server.close()
            assertEquals(PasskeyRegistrationResult.Network, repository.register("Pixel", "123456", authenticator))
            assertTrue(authenticator.registrations.isEmpty())
        }

    @Test
    fun `an unusable authenticator answer fails the registration without a finish request`() =
        runBlocking {
            loggedIn()
            val outcomes =
                listOf(
                    CredentialOutcome.NoCredential,
                    CredentialOutcome.Success("not json"),
                    CredentialOutcome.Success("\"just a string\""),
                )
            outcomes.forEach { outcome ->
                server.enqueue(json(200, CREATION_OPTIONS))
                authenticator.next = outcome
                assertEquals(outcome.toString(), PasskeyRegistrationResult.Failed, repository.register("Pixel", "123456", authenticator))
            }
            assertEquals(outcomes.size, server.requestCount)
        }

    @Test
    fun `finishing a registration without a readable answer or connection reports it`() =
        runBlocking {
            loggedIn()
            server.enqueue(json(200, CREATION_OPTIONS))
            server.enqueue(MockResponse.Builder().code(204).build())
            authenticator.next = CredentialOutcome.Success(ATTESTATION)
            assertEquals(PasskeyRegistrationResult.ServerError, repository.register("Pixel", "123456", authenticator))

            server.enqueue(json(200, CREATION_OPTIONS))
            authenticator.onCall = { server.close() }
            assertEquals(PasskeyRegistrationResult.Network, repository.register("Pixel", "123456", authenticator))
        }

    @Test
    fun `malformed server answers are a failure, never a crash`() =
        runBlocking {
            loggedIn()
            server.enqueue(json(200, """[{"id":"0192f1a0-0000-7000-8000-000000000009","name":"Pixel","createdAt":"yesterday"}]"""))
            server.enqueue(json(200, """[{"id":"not-a-uuid","name":"Pixel","createdAt":"2026-10-22T10:00:00Z"}]"""))
            server.enqueue(json(200, """{"ceremonyId":"ceremony-2"}"""))
            server.enqueue(json(200, "not json"))

            assertNull(repository.list())
            assertNull(repository.list())
            assertEquals(PasskeyRegistrationResult.ServerError, repository.register("Pixel", "123456", authenticator))
            assertEquals(LoginResult.ServerError, repository.login(serverAddress(), authenticator))
            assertTrue(authenticator.registrations.isEmpty() && authenticator.requests.isEmpty())
        }

    @Test
    fun `the list is null without a connection`() =
        runBlocking {
            loggedIn()
            server.close()

            assertNull(repository.list())
        }

    private suspend fun loggedIn() {
        serverUrls.setServerUrl(serverAddress())
        tokenStore.login(AuthTokens("access-1", "refresh-1"), DEVICE_ID)
    }

    private fun serverAddress() = server.url("/").toString()

    private fun json(
        code: Int,
        body: String,
    ) = MockResponse
        .Builder()
        .code(code)
        .addHeader("Content-Type", if (code < 300) "application/json" else "application/problem+json")
        .body(body)
        .build()

    private class FakePasskeys : PasskeyCredentials {
        var next: CredentialOutcome = CredentialOutcome.Failed
        val requests = mutableListOf<String>()
        val registrations = mutableListOf<String>()

        /** Runs while the system sheet would be open, e.g. to drop the connection. */
        var onCall: () -> Unit = {}

        override suspend fun authenticate(requestJson: String): CredentialOutcome {
            requests += requestJson
            onCall()
            return next
        }

        override suspend fun register(requestJson: String): CredentialOutcome {
            registrations += requestJson
            onCall()
            return next
        }
    }

    private companion object {
        const val DEVICE_ID = "0192f1a0-0000-7000-8000-000000000001"
        const val PROBLEM = """{"type":"about:blank","title":"Error","status":401}"""
        val TOKEN_BODY =
            """
            {"accessToken":"access-1","accessTokenExpiresAt":"2026-10-20T12:15:00Z",
             "deviceId":"$DEVICE_ID","refreshToken":"refresh-1",
             "refreshTokenExpiresAt":"2027-01-18T12:00:00Z","tokenType":"Bearer"}
            """.trimIndent()
        const val AUTH_OPTIONS =
            """{"ceremonyId":"ceremony-1","publicKey":{"challenge":"Y2hhbGxlbmdl","rpId":"cockpit.example.org",
               "allowCredentials":[],"timeout":300000,"userVerification":"required","extensions":{"credProps":true}}}"""
        const val CREATION_OPTIONS =
            """{"ceremonyId":"ceremony-2","publicKey":{"challenge":"Y2hhbGxlbmdl","rp":{"id":"cockpit.example.org","name":"Cockpit"},
               "user":{"id":"dXNlcg","name":"alex","displayName":"alex"},"pubKeyCredParams":[{"type":"public-key","alg":-7}],
               "excludeCredentials":[],"authenticatorSelection":{"residentKey":"required","userVerification":"required"},
               "attestation":"none","timeout":300000}}"""
        const val ASSERTION = """{"id":"cred-1","rawId":"cred-1","type":"public-key","response":{"signature":"c2ln"}}"""
        const val ATTESTATION = """{"id":"cred-2","rawId":"cred-2","type":"public-key","response":{"attestationObject":"YQ"}}"""
        const val PASSKEY = """{"id":"0192f1a0-0000-7000-8000-000000000009","name":"Pixel","createdAt":"2026-10-22T10:00:00Z"}"""
    }
}
