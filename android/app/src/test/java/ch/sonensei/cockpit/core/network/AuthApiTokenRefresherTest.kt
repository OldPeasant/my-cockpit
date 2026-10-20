package ch.sonensei.cockpit.core.network

import ch.sonensei.cockpit.core.network.generated.apis.AuthApi
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** [AuthApiTokenRefresher] against a fake server: how each answer of the refresh endpoint is classified. */
class AuthApiTokenRefresherTest {
    private val server = MockWebServer()

    @Before
    fun setUp() {
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `a 200 returns the rotated tokens and posts the refresh token without an access token`() {
        server.enqueue(json(200, tokenBody(access = "fresh-access", refresh = "fresh-refresh")))

        val result = refresherFor { server.url("/") }.refresh("old-refresh")

        assertEquals(RefreshResult.Success(AuthTokens("fresh-access", "fresh-refresh")), result)
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/v1/auth/token/refresh", recorded.url.encodedPath)
        assertEquals("""{"refreshToken":"old-refresh"}""", recorded.body?.utf8())
        assertNull(recorded.headers[AUTHORIZATION])
    }

    @Test
    fun `400 and 401 mean the refresh token was rejected`() {
        listOf(400, 401).forEach { code ->
            server.enqueue(json(code, """{"type":"about:blank","title":"Unauthorized","status":$code}"""))

            assertEquals("HTTP $code", RefreshResult.Rejected, refresherFor { server.url("/") }.refresh("old-refresh"))
        }
    }

    @Test
    fun `other errors keep the session as a failed refresh`() {
        listOf(403, 429, 500, 503).forEach { code ->
            server.enqueue(MockResponse.Builder().code(code).build())

            assertEquals("HTTP $code", RefreshResult.Failed, refresherFor { server.url("/") }.refresh("old-refresh"))
        }
    }

    @Test
    fun `a success without a body is a failed refresh`() {
        server.enqueue(MockResponse.Builder().code(204).build())

        assertEquals(RefreshResult.Failed, refresherFor { server.url("/") }.refresh("old-refresh"))
    }

    @Test
    fun `an unreachable server is a failed refresh`() {
        val closed = MockWebServer()
        closed.start()
        val unreachable = closed.url("/")
        closed.close()

        assertEquals(RefreshResult.Failed, refresherFor { unreachable }.refresh("old-refresh"))
    }

    @Test
    fun `no configured server is a failed refresh`() {
        assertEquals(RefreshResult.Failed, refresherFor { null }.refresh("old-refresh"))
    }

    @Test
    fun `a malformed success body is a failed refresh, not a crash`() {
        // A proxy or captive portal answering 200 with HTML must not throw out of the OkHttp authenticator.
        server.enqueue(
            MockResponse
                .Builder()
                .code(200)
                .addHeader("Content-Type", "text/html")
                .body("<html>login</html>")
                .build(),
        )

        assertEquals(RefreshResult.Failed, refresherFor { server.url("/") }.refresh("old-refresh"))
    }

    @Test
    fun `a success body missing required fields is a failed refresh, not a crash`() {
        server.enqueue(json(200, """{"accessToken":"fresh-access"}"""))

        assertEquals(RefreshResult.Failed, refresherFor { server.url("/") }.refresh("old-refresh"))
    }

    @Test
    fun `a success body with an invalid device id is a failed refresh, not a crash`() {
        server.enqueue(json(200, tokenBody(access = "fresh-access", refresh = "fresh-refresh").replace(DEVICE_ID, "not-a-uuid")))

        assertEquals(RefreshResult.Failed, refresherFor { server.url("/") }.refresh("old-refresh"))
    }

    @Test
    fun `a success body with an invalid expiry date is a failed refresh, not a crash`() {
        // The generated OffsetDateTime adapter throws DateTimeParseException, which is neither IO nor serialization.
        server.enqueue(
            json(200, tokenBody(access = "fresh-access", refresh = "fresh-refresh").replace("2026-10-20T12:15:00Z", "tomorrow")),
        )

        assertEquals(RefreshResult.Failed, refresherFor { server.url("/") }.refresh("old-refresh"))
    }

    @Test
    fun `a success body with an unknown token type is a failed refresh, not a crash`() {
        server.enqueue(json(200, tokenBody(access = "fresh-access", refresh = "fresh-refresh").replace("\"Bearer\"", "\"MAC\"")))

        assertEquals(RefreshResult.Failed, refresherFor { server.url("/") }.refresh("old-refresh"))
    }

    private fun refresherFor(url: () -> HttpUrl?): AuthApiTokenRefresher {
        val client = CockpitHttp.okHttpClient(ServerUrlInterceptor(url), null, null)
        return AuthApiTokenRefresher(CockpitHttp.retrofit(client).create(AuthApi::class.java))
    }

    private fun json(
        code: Int,
        body: String,
    ) = MockResponse
        .Builder()
        .code(code)
        .addHeader("Content-Type", "application/json")
        .body(body)
        .build()

    private fun tokenBody(
        access: String,
        refresh: String,
    ) = """
        {"accessToken":"$access","accessTokenExpiresAt":"2026-10-20T12:15:00Z",
         "deviceId":"$DEVICE_ID","refreshToken":"$refresh",
         "refreshTokenExpiresAt":"2027-01-18T12:00:00Z","tokenType":"Bearer"}
        """.trimIndent()

    private companion object {
        const val DEVICE_ID = "0192f1a0-0000-7000-8000-000000000001"
    }
}
