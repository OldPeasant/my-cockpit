package ch.sonensei.cockpit.core.network

import ch.sonensei.cockpit.auth.PersistentTokenStore
import ch.sonensei.cockpit.core.network.generated.apis.AuthApi
import ch.sonensei.cockpit.core.network.generated.apis.EventsApi
import ch.sonensei.cockpit.core.network.generated.apis.SettingsApi
import ch.sonensei.cockpit.core.network.generated.apis.SetupApi
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.inject.Inject

/** The production Hilt graph of core.network, wired end to end against a fake server. */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
class NetworkModuleGraphTest {
    @get:Rule
    val hilt = HiltAndroidRule(this)

    @Inject lateinit var client: OkHttpClient

    @Inject lateinit var authApi: AuthApi

    @Inject lateinit var eventsApi: EventsApi

    @Inject lateinit var settingsApi: SettingsApi

    @Inject lateinit var setupApi: SetupApi

    @Inject lateinit var tokenStore: TokenStore

    @Inject lateinit var session: AuthSession

    @Inject lateinit var serverUrls: ServerUrlRepository

    private val server = MockWebServer()
    private val refreshAuthorizations = mutableListOf<String?>()

    @Before
    fun setUp() {
        hilt.inject()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    when {
                        request.url.encodedPath == "/cockpit/api/v1/auth/token/refresh" -> {
                            synchronized(refreshAuthorizations) { refreshAuthorizations += request.headers[AUTHORIZATION] }
                            json(
                                """
                                {"accessToken":"graph-new-access","accessTokenExpiresAt":"2026-10-20T12:15:00Z",
                                 "deviceId":"0192f1a0-0000-7000-8000-000000000002","refreshToken":"graph-new-refresh",
                                 "refreshTokenExpiresAt":"2027-01-18T12:00:00Z","tokenType":"Bearer"}
                                """.trimIndent(),
                            )
                        }
                        request.headers[AUTHORIZATION] == "Bearer graph-new-access" -> json("""{"username":"alex"}""")
                        else -> MockResponse.Builder().code(401).build()
                    }
            }
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `the graph provides the APIs and shares one token store`() {
        assertNotNull(eventsApi)
        assertNotNull(settingsApi)
        assertNotNull(setupApi)
        assertSame(tokenStore, (tokenStore as? PersistentTokenStore))
        assertNotNull(client.authenticator as? TokenAuthenticator)
        tokenStore.save(AuthTokens("graph-access", "graph-refresh"))
        assertEquals(true, session.isLoggedIn)
        session.logout(LogoutReason.USER)
        assertNull(tokenStore.current())
    }

    @Test
    fun `an injected API uses the stored server URL, token and refresh client`() =
        runBlocking {
            serverUrls.setServerUrl(server.url("/cockpit").toString())
            tokenStore.save(AuthTokens("graph-old-access", "graph-old-refresh"))

            val response = authApi.getCurrentUser()

            assertEquals(200, response.code())
            assertEquals("alex", response.body()?.username)
            assertEquals(AuthTokens("graph-new-access", "graph-new-refresh"), tokenStore.current())
            // The refresh call goes through the @RefreshClient: same server, but no access token attached.
            assertEquals(listOf<String?>(null), refreshAuthorizations)
        }

    private fun json(body: String) =
        MockResponse
            .Builder()
            .code(200)
            .addHeader("Content-Type", "application/json")
            .body(body)
            .build()
}
