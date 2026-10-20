package ch.sonensei.cockpit.core.network

import ch.sonensei.cockpit.core.network.generated.apis.AuthApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class TokenAuthenticatorTest {
    private val server = MockWebServer()
    private val tokenStore = InMemoryTokenStore()
    private val session = AuthSession(tokenStore)
    private val refreshCalls = AtomicInteger()
    private val authorizations = CopyOnWriteArrayList<String?>()

    /** What the refresh endpoint answers. */
    @Volatile
    private var refreshResponse: () -> MockResponse = { tokenResponse(access = "new-access", refresh = "new-refresh") }

    private lateinit var api: AuthApi

    @Before
    fun setUp() {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    if (request.url.encodedPath == "/api/v1/auth/token/refresh") {
                        refreshCalls.incrementAndGet()
                        refreshResponse()
                    } else {
                        val authorization = request.headers[AUTHORIZATION]
                        authorizations += authorization
                        if (authorization == "Bearer new-access") meResponse() else MockResponse.Builder().code(401).build()
                    }
            }
        server.start()
        val serverUrl = ServerUrlInterceptor { server.url("/") }
        val refresher = AuthApiTokenRefresher(retrofitApi(CockpitHttp.okHttpClient(serverUrl, null, null)))
        val client =
            CockpitHttp.okHttpClient(
                serverUrl,
                AccessTokenInterceptor(tokenStore),
                TokenAuthenticator(tokenStore, refresher, session),
            )
        api = retrofitApi(client)
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `adds the access token to requests`() =
        runBlocking {
            tokenStore.save(AuthTokens("new-access", "new-refresh"))

            val response = api.getCurrentUser()

            assertEquals(200, response.code())
            assertEquals(listOf("Bearer new-access"), authorizations)
            assertEquals(0, refreshCalls.get())
        }

    @Test
    fun `sends no Authorization header when logged out`() =
        runBlocking {
            val response = api.getCurrentUser()

            assertEquals(401, response.code())
            assertEquals(listOf<String?>(null), authorizations)
            assertEquals(0, refreshCalls.get())
        }

    @Test
    fun `refreshes once on 401 and retries with the new token`() =
        runBlocking {
            tokenStore.save(AuthTokens("old-access", "old-refresh"))

            val response = api.getCurrentUser()

            assertEquals(200, response.code())
            assertEquals(listOf("Bearer old-access", "Bearer new-access"), authorizations)
            assertEquals(1, refreshCalls.get())
            assertEquals(AuthTokens("new-access", "new-refresh"), tokenStore.current())
        }

    @Test
    fun `concurrent 401s share one refresh`() =
        runBlocking {
            tokenStore.save(AuthTokens("old-access", "old-refresh"))
            // A slow refresh, so all requests get their 401 while it is still running.
            refreshResponse = {
                tokenResponse(access = "new-access", refresh = "new-refresh")
                    .newBuilder()
                    .headersDelay(300, TimeUnit.MILLISECONDS)
                    .build()
            }

            val codes =
                withTimeout(10_000) {
                    (1..8).map { async(kotlinx.coroutines.Dispatchers.IO) { api.getCurrentUser().code() } }.awaitAll()
                }

            assertEquals(List(8) { 200 }, codes)
            assertEquals(1, refreshCalls.get())
            assertEquals(AuthTokens("new-access", "new-refresh"), tokenStore.current())
        }

    @Test
    fun `a rejected refresh logs out`() =
        runBlocking {
            tokenStore.save(AuthTokens("old-access", "old-refresh"))
            refreshResponse = { MockResponse.Builder().code(401).build() }
            val logout = async(kotlinx.coroutines.Dispatchers.Unconfined) { session.loggedOut.first() }

            val response = api.getCurrentUser()

            assertEquals(401, response.code())
            assertEquals(1, refreshCalls.get())
            assertNull(tokenStore.current())
            assertFalse(session.isLoggedIn)
            assertEquals(LogoutReason.REFRESH_REJECTED, withTimeout(1_000) { logout.await() })
        }

    @Test
    fun `a server error during refresh keeps the tokens`() =
        runBlocking {
            tokenStore.save(AuthTokens("old-access", "old-refresh"))
            refreshResponse = { MockResponse.Builder().code(503).build() }

            val response = api.getCurrentUser()

            assertEquals(401, response.code())
            assertTrue(session.isLoggedIn)
            assertEquals(AuthTokens("old-access", "old-refresh"), tokenStore.current())
        }

    @Test
    fun `a refreshed token that is rejected too is not refreshed again`() =
        runBlocking {
            tokenStore.save(AuthTokens("old-access", "old-refresh"))
            refreshResponse = { tokenResponse(access = "still-rejected", refresh = "rotated-refresh") }

            val response = withTimeout(10_000) { api.getCurrentUser() }

            assertEquals(401, response.code())
            assertEquals(listOf("Bearer old-access", "Bearer still-rejected"), authorizations)
            assertEquals(1, refreshCalls.get())
            // The rotated tokens are kept: the server accepted the refresh, only the request was refused.
            assertEquals(AuthTokens("still-rejected", "rotated-refresh"), tokenStore.current())
        }

    @Test
    fun `an Authorization header set by the caller is not overwritten`() {
        tokenStore.save(AuthTokens("stored-access", "stored-refresh"))
        val client =
            CockpitHttp.okHttpClient(ServerUrlInterceptor { server.url("/") }, AccessTokenInterceptor(tokenStore), null)
        val request =
            okhttp3.Request
                .Builder()
                .url("${PLACEHOLDER_BASE_URL}api/v1/auth/me")
                .header(AUTHORIZATION, "Bearer new-access")
                .build()

        client.newCall(request).execute().use { assertEquals(200, it.code) }

        assertEquals(listOf("Bearer new-access"), authorizations)
    }

    @Test
    fun `a network error during refresh fails the request and keeps the tokens`() =
        runBlocking {
            tokenStore.save(AuthTokens("old-access", "old-refresh"))
            val closed = MockWebServer()
            closed.start()
            val unreachable = closed.url("/")
            closed.close()
            val refresher =
                AuthApiTokenRefresher(retrofitApi(CockpitHttp.okHttpClient(ServerUrlInterceptor { unreachable }, null, null)))
            val client =
                CockpitHttp.okHttpClient(
                    ServerUrlInterceptor { server.url("/") },
                    AccessTokenInterceptor(tokenStore),
                    TokenAuthenticator(tokenStore, refresher, session),
                )

            val response = retrofitApi(client).getCurrentUser()

            assertEquals(401, response.code())
            assertEquals(0, refreshCalls.get())
            assertTrue(session.isLoggedIn)
            assertEquals(AuthTokens("old-access", "old-refresh"), tokenStore.current())
        }

    @Test
    fun `a malformed refresh answer fails like a server error instead of crashing the call`() =
        runBlocking {
            tokenStore.save(AuthTokens("old-access", "old-refresh"))
            refreshResponse = {
                MockResponse
                    .Builder()
                    .code(200)
                    .addHeader("Content-Type", "text/html")
                    .body("<html>captive portal</html>")
                    .build()
            }

            val response = withTimeout(10_000) { api.getCurrentUser() }

            assertEquals(401, response.code())
            assertEquals(1, refreshCalls.get())
            assertEquals(AuthTokens("old-access", "old-refresh"), tokenStore.current())
        }

    private fun retrofitApi(client: OkHttpClient): AuthApi = CockpitHttp.retrofit(client).create(AuthApi::class.java)

    private fun meResponse() =
        MockResponse
            .Builder()
            .code(200)
            .addHeader("Content-Type", "application/json")
            .body("""{"username":"alex"}""")
            .build()

    private fun tokenResponse(
        access: String,
        refresh: String,
    ) = MockResponse
        .Builder()
        .code(200)
        .addHeader("Content-Type", "application/json")
        .body(
            """
            {"accessToken":"$access","accessTokenExpiresAt":"2026-10-20T12:15:00Z",
             "deviceId":"0192f1a0-0000-7000-8000-000000000001","refreshToken":"$refresh",
             "refreshTokenExpiresAt":"2027-01-18T12:00:00Z","tokenType":"Bearer"}
            """.trimIndent(),
        ).build()
}
