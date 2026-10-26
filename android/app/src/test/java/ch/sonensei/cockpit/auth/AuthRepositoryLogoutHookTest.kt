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
import ch.sonensei.cockpit.core.network.generated.apis.AuthApi
import ch.sonensei.cockpit.core.network.serverConfigDataStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * [LogoutHook]s run at the start of a user logout, while the device is still logged in and before it is revoked. They are
 * best effort: a hook that fails or hangs never keeps the device logged in. Logouts the device did not start skip them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class AuthRepositoryLogoutHookTest {
    private val server = MockWebServer()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var tokenStore: PersistentTokenStore
    private lateinit var session: AuthSession
    private lateinit var serverUrls: ServerUrlRepository
    private lateinit var authApi: AuthApi
    private val events = CopyOnWriteArrayList<String>()

    @Before
    fun setUp() {
        server.start()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("auth-logout-hook-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        tokenStore = PersistentTokenStore(prefs, KeystoreTokenCipher())
        session = AuthSession(tokenStore)
        val file = File(context.filesDir, "server-${System.nanoTime()}.preferences_pb")
        serverUrls = ServerUrlRepository(serverConfigDataStore(scope) { file }, session)
        runBlocking { serverUrls.setServerUrl(server.url("/").toString()) }
        authApi =
            CockpitHttp
                .retrofit(CockpitHttp.okHttpClient(ServerUrlInterceptor(serverUrls), AccessTokenInterceptor(tokenStore), null))
                .create(AuthApi::class.java)
    }

    @After
    fun tearDown() {
        server.close()
        scope.cancel()
    }

    private fun repository(
        vararg hooks: LogoutHook,
        appScope: CoroutineScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob()),
    ) = AuthRepository(
        serverUrls,
        authApi,
        tokenStore,
        session,
        setOf(SessionCache { events += "cache" }),
        appScope,
        Dispatchers.IO,
        linkedSetOf(*hooks),
    )

    private fun loggedIn(deviceId: String? = DEVICE_ID) = tokenStore.login(AuthTokens("access-1", "refresh-1"), deviceId)

    private fun revocationAnswered() = server.enqueue(MockResponse.Builder().code(204).build())

    private fun assertLoggedOutLocally() {
        assertNull(tokenStore.current())
        assertTrue("caches cleared: $events", events.contains("cache"))
    }

    @Test
    fun `hooks run before the device is revoked, while the tokens are still there`() =
        runBlocking {
            loggedIn()
            revocationAnswered()
            var tokensDuringHook: AuthTokens? = null
            var requestsBeforeHook = -1
            val hook =
                LogoutHook {
                    tokensDuringHook = tokenStore.current()
                    requestsBeforeHook = server.requestCount
                    events += "hook"
                }

            repository(hook).logout()

            assertEquals(AuthTokens("access-1", "refresh-1"), tokensDuringHook)
            assertEquals("no revocation before the hook", 0, requestsBeforeHook)
            val revoke = server.takeRequest(1, TimeUnit.SECONDS)
            assertNotNull(revoke)
            assertEquals("/api/v1/auth/devices/$DEVICE_ID", revoke!!.url.encodedPath)
            assertEquals(listOf("hook", "cache"), events)
            assertLoggedOutLocally()
        }

    @Test
    fun `every hook runs once per user logout`() =
        runBlocking {
            loggedIn(deviceId = null)

            repository(LogoutHook { events += "first" }, LogoutHook { events += "second" }).logout()

            assertEquals(listOf("first", "second", "cache"), events)
        }

    @Test
    fun `a throwing hook neither stops the other hooks nor the revocation nor the local logout`() =
        runBlocking {
            loggedIn()
            revocationAnswered()

            repository(LogoutHook { throw IllegalStateException("fictitious failure") }, LogoutHook { events += "second" }).logout()

            assertEquals("DELETE", server.takeRequest(1, TimeUnit.SECONDS)?.method)
            assertTrue(events.contains("second"))
            assertLoggedOutLocally()
        }

    @Test
    fun `a hook that hangs is given up after its timeout, and the logout goes on`() =
        runTest {
            loggedIn(deviceId = null)

            repository(LogoutHook { awaitCancellation() }, LogoutHook { events += "second" }).logout()

            assertEquals(AuthRepository.LOGOUT_HOOK_TIMEOUT_MS, testScheduler.currentTime)
            assertEquals(listOf("second", "cache"), events)
            assertLoggedOutLocally()
        }

    @Test
    fun `each hanging hook has its own timeout`() =
        runTest {
            loggedIn(deviceId = null)

            repository(LogoutHook { awaitCancellation() }, LogoutHook { awaitCancellation() }).logout()

            assertEquals(2 * AuthRepository.LOGOUT_HOOK_TIMEOUT_MS, testScheduler.currentTime)
            assertLoggedOutLocally()
        }

    @Test
    fun `a hook whose own timeout fires does not skip the revocation or abort the logout`() =
        runBlocking {
            // A TimeoutCancellationException thrown inside the hook (e.g. by its own withTimeout) is a failure of that
            // hook like any other, not a cancellation of the logout: the device must still be revoked.
            loggedIn()
            revocationAnswered()
            val hook = LogoutHook { withTimeout(1) { awaitCancellation() } }

            val outcome = runCatching { repository(hook).logout() }

            assertTrue("logout threw ${outcome.exceptionOrNull()}", outcome.isSuccess)
            assertEquals("DELETE", server.takeRequest(1, TimeUnit.SECONDS)?.method)
            assertLoggedOutLocally()
        }

    @Test
    fun `a hook that throws a CancellationException does not skip the revocation`() =
        runBlocking {
            loggedIn()
            revocationAnswered()
            val hook = LogoutHook { throw CancellationException("fictitious cancellation inside the hook") }

            val outcome = runCatching { repository(hook).logout() }

            assertTrue("logout threw ${outcome.exceptionOrNull()}", outcome.isSuccess)
            assertEquals("DELETE", server.takeRequest(1, TimeUnit.SECONDS)?.method)
            assertLoggedOutLocally()
        }

    @Test
    fun `a logout by the network layer does not run the hooks`() =
        runBlocking {
            val owner = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
            repository(LogoutHook { events += "hook" }, appScope = owner)
            loggedIn()

            assertTrue(session.logoutIfCurrent(AuthTokens("access-1", "refresh-1"), LogoutReason.REFRESH_REJECTED))

            assertNull(tokenStore.current())
            assertEquals(listOf("cache"), events)
            assertEquals(0, server.requestCount)
            owner.cancel()
        }

    @Test
    fun `a logout for a server change does not run the hooks`() =
        runBlocking {
            val owner = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
            repository(LogoutHook { events += "hook" }, appScope = owner)
            loggedIn()

            session.logout(LogoutReason.SERVER_CHANGED)

            assertEquals(listOf("cache"), events)
            owner.cancel()
        }

    private companion object {
        const val DEVICE_ID = "0192f1a0-0000-7000-8000-000000000001"
    }
}
