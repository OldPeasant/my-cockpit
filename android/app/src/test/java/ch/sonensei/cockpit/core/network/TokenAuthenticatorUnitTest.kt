package ch.sonensei.cockpit.core.network

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Drives [TokenAuthenticator] directly with fake responses, so each branch is reached deterministically. */
class TokenAuthenticatorUnitTest {
    private val tokenStore = InMemoryTokenStore()
    private val session = AuthSession(tokenStore)
    private val refreshedWith = CopyOnWriteArrayList<String>()

    @Volatile
    private var refreshResult: RefreshResult = RefreshResult.Success(AuthTokens("new-access", "new-refresh"))

    private val refresher =
        TokenRefresher { refreshToken ->
            refreshedWith += refreshToken
            refreshResult
        }
    private val authenticator = TokenAuthenticator(tokenStore, refresher, session)

    @Test
    fun `refreshes with the stored refresh token and retries with the new access token`() {
        tokenStore.save(AuthTokens("old-access", "old-refresh"))

        val retry = authenticator.authenticate(null, unauthorized(bearer("old-access")))

        assertEquals("Bearer new-access", retry?.header(AUTHORIZATION))
        assertEquals("https://server.invalid/api/v1/auth/me", retry?.url.toString())
        assertEquals(listOf("old-refresh"), refreshedWith)
        assertEquals(AuthTokens("new-access", "new-refresh"), tokenStore.current())
    }

    @Test
    fun `a 401 for a request without a bearer token is final`() {
        tokenStore.save(AuthTokens("old-access", "old-refresh"))

        assertNull(authenticator.authenticate(null, unauthorized(request())))
        assertNull(
            authenticator.authenticate(null, unauthorized(request().newBuilder().header(AUTHORIZATION, "Basic YWxleDpwdw==").build())),
        )
        assertEquals(emptyList<String>(), refreshedWith)
        assertEquals(AuthTokens("old-access", "old-refresh"), tokenStore.current())
    }

    @Test
    fun `a 401 for an already retried request is final and does not refresh again`() {
        tokenStore.save(AuthTokens("new-access", "new-refresh"))
        val first = unauthorized(bearer("old-access"))
        val retried = unauthorized(bearer("new-access"), prior = first)

        assertNull(authenticator.authenticate(null, retried))
        assertEquals(emptyList<String>(), refreshedWith)
        assertTrue(session.isLoggedIn)
    }

    @Test
    fun `a request sent with an older token is retried with the stored one without refreshing`() {
        // Another request already refreshed while this one was in flight.
        tokenStore.save(AuthTokens("new-access", "new-refresh"))

        val retry = authenticator.authenticate(null, unauthorized(bearer("old-access")))

        assertEquals("Bearer new-access", retry?.header(AUTHORIZATION))
        assertEquals(emptyList<String>(), refreshedWith)
    }

    @Test
    fun `gives up without refreshing when logged out in the meantime`() {
        val retry = authenticator.authenticate(null, unauthorized(bearer("old-access")))

        assertNull(retry)
        assertEquals(emptyList<String>(), refreshedWith)
    }

    @Test
    fun `a rejected refresh logs out with REFRESH_REJECTED`() =
        runBlocking {
            tokenStore.save(AuthTokens("old-access", "old-refresh"))
            refreshResult = RefreshResult.Rejected
            val logout = async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) { session.loggedOut.first() }

            assertNull(authenticator.authenticate(null, unauthorized(bearer("old-access"))))

            assertNull(tokenStore.current())
            assertFalse(session.isLoggedIn)
            assertEquals(LogoutReason.REFRESH_REJECTED, withTimeout(1_000) { logout.await() })
        }

    @Test
    fun `a failed refresh keeps the tokens and does not log out`() =
        runBlocking {
            tokenStore.save(AuthTokens("old-access", "old-refresh"))
            refreshResult = RefreshResult.Failed
            val logouts = CopyOnWriteArrayList<LogoutReason>()
            val collector =
                async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) { session.loggedOut.collect { logouts += it } }

            assertNull(authenticator.authenticate(null, unauthorized(bearer("old-access"))))

            assertEquals(AuthTokens("old-access", "old-refresh"), tokenStore.current())
            assertEquals(emptyList<LogoutReason>(), logouts)
            collector.cancel()
        }

    @Test
    fun `a 401 arriving while another refresh runs waits for it and reuses its token`() {
        tokenStore.save(AuthTokens("old-access", "old-refresh"))
        val refreshStarted = CountDownLatch(1)
        val finishRefresh = CountDownLatch(1)
        val blockingAuthenticator =
            TokenAuthenticator(
                tokenStore,
                { refreshToken ->
                    refreshedWith += refreshToken
                    refreshStarted.countDown()
                    finishRefresh.await(5, TimeUnit.SECONDS)
                    RefreshResult.Success(AuthTokens("new-access", "new-refresh"))
                },
                session,
            )
        val firstRetry = AtomicReference<Request?>()
        val secondRetry = AtomicReference<Request?>()
        val first = Thread { firstRetry.set(blockingAuthenticator.authenticate(null, unauthorized(bearer("old-access")))) }
        val second = Thread { secondRetry.set(blockingAuthenticator.authenticate(null, unauthorized(bearer("old-access")))) }

        first.start()
        assertTrue(refreshStarted.await(5, TimeUnit.SECONDS))
        second.start()
        // The second 401 must queue behind the running refresh instead of starting its own.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (second.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.yield()
        assertEquals(Thread.State.BLOCKED, second.state)
        finishRefresh.countDown()
        first.join(5_000)
        second.join(5_000)

        assertEquals(listOf("old-refresh"), refreshedWith)
        assertEquals("Bearer new-access", firstRetry.get()?.header(AUTHORIZATION))
        assertEquals("Bearer new-access", secondRetry.get()?.header(AUTHORIZATION))
    }

    @Test
    fun `a 401 waiting for a refresh that fails gives up, and a later 401 refreshes again`() {
        tokenStore.save(AuthTokens("old-access", "old-refresh"))
        val failing = WaitingRefresher(RefreshResult.Failed)
        val authenticator = TokenAuthenticator(tokenStore, failing, session)
        val firstRetry = AtomicReference<Request?>(bearer("unset"))
        val secondRetry = AtomicReference<Request?>(bearer("unset"))
        val first = Thread { firstRetry.set(authenticator.authenticate(null, unauthorized(bearer("old-access")))) }
        val second = Thread { secondRetry.set(authenticator.authenticate(null, unauthorized(bearer("old-access")))) }

        first.start()
        assertTrue(failing.started.await(5, TimeUnit.SECONDS))
        second.start()
        // BLOCKED on the refresh lock: the second 401 is waiting for the running refresh.
        awaitBlocked(second)
        failing.finish.countDown()
        first.join(5_000)
        second.join(5_000)

        assertNull(firstRetry.get())
        assertNull(secondRetry.get())
        assertEquals(listOf("old-refresh"), failing.refreshedWith)
        assertEquals(AuthTokens("old-access", "old-refresh"), tokenStore.current())

        // A 401 that arrives after the failure is not a waiter of it: it may refresh again (and fails again).
        assertNull(authenticator.authenticate(null, unauthorized(bearer("old-access"))))
        assertEquals(listOf("old-refresh", "old-refresh"), failing.refreshedWith)
    }

    @Test
    fun `a 401 waiting for a failed refresh refreshes itself when the tokens changed meanwhile`() {
        tokenStore.save(AuthTokens("old-access", "old-refresh"))
        // The failing refresh started from old-refresh; meanwhile the refresh token was replaced (same access token).
        val failing =
            WaitingRefresher(RefreshResult.Failed) { tokenStore.save(AuthTokens("old-access", "rotated-refresh")) }
        val authenticator = TokenAuthenticator(tokenStore, failing, session)
        val first = Thread { authenticator.authenticate(null, unauthorized(bearer("old-access"))) }
        val second = Thread { authenticator.authenticate(null, unauthorized(bearer("old-access"))) }

        first.start()
        assertTrue(failing.started.await(5, TimeUnit.SECONDS))
        second.start()
        awaitBlocked(second)
        failing.finish.countDown()
        first.join(5_000)
        second.join(5_000)

        // The failure was for other tokens, so the waiter does not give up on the current ones.
        assertEquals(listOf("old-refresh", "rotated-refresh"), failing.refreshedWith)
    }

    @Test
    fun `a user logout clears the tokens and emits USER`() =
        runBlocking {
            tokenStore.save(AuthTokens("old-access", "old-refresh"))
            val logout = async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) { session.loggedOut.first() }

            session.logout(LogoutReason.USER)

            assertFalse(session.isLoggedIn)
            assertEquals(LogoutReason.USER, withTimeout(1_000) { logout.await() })
        }

    @Test
    fun `tokens are redacted in their string form`() {
        val text = AuthTokens("secret-access-fictitious", "secret-refresh-fictitious").toString()

        assertFalse(text, text.contains("secret"))
    }

    @Test
    fun `a user logout during a successful refresh stays logged out`() {
        tokenStore.save(AuthTokens("old-access", "old-refresh"))
        val racing =
            racingAuthenticator(RefreshResult.Success(AuthTokens("new-access", "new-refresh"))) { session.logout(LogoutReason.USER) }

        assertNull(racing.authenticate(null, unauthorized(bearer("old-access"))))
        assertNull(tokenStore.current())
        assertFalse(session.isLoggedIn)
    }

    @Test
    fun `a login during a successful refresh keeps the new login and retries with it`() {
        tokenStore.save(AuthTokens("old-access", "old-refresh"))
        val racing =
            racingAuthenticator(RefreshResult.Success(AuthTokens("new-access", "new-refresh"))) {
                tokenStore.save(AuthTokens("login-access", "login-refresh"))
            }

        val retry = racing.authenticate(null, unauthorized(bearer("old-access")))

        assertEquals("Bearer login-access", retry?.header(AUTHORIZATION))
        assertEquals(AuthTokens("login-access", "login-refresh"), tokenStore.current())
    }

    @Test
    fun `a rejected stale refresh does not log out a login that happened meanwhile`() =
        runBlocking {
            tokenStore.save(AuthTokens("old-access", "old-refresh"))
            val logouts = CopyOnWriteArrayList<LogoutReason>()
            val collector =
                async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) { session.loggedOut.collect { logouts += it } }
            val racing = racingAuthenticator(RefreshResult.Rejected) { tokenStore.save(AuthTokens("login-access", "login-refresh")) }

            val retry = racing.authenticate(null, unauthorized(bearer("old-access")))

            assertEquals("Bearer login-access", retry?.header(AUTHORIZATION))
            assertEquals(AuthTokens("login-access", "login-refresh"), tokenStore.current())
            assertEquals(emptyList<LogoutReason>(), logouts)
            collector.cancel()
        }

    @Test
    fun `a rejected refresh after a user logout meanwhile gives up and does not log out a second time`() =
        runBlocking {
            tokenStore.save(AuthTokens("old-access", "old-refresh"))
            val logouts = CopyOnWriteArrayList<LogoutReason>()
            val collector =
                async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) { session.loggedOut.collect { logouts += it } }
            val racing = racingAuthenticator(RefreshResult.Rejected) { session.logout(LogoutReason.USER) }

            assertNull(racing.authenticate(null, unauthorized(bearer("old-access"))))

            assertNull(tokenStore.current())
            assertEquals(listOf(LogoutReason.USER), logouts)
            collector.cancel()
        }

    @Test
    fun `a failed refresh during a login meanwhile leaves the new login alone`() {
        tokenStore.save(AuthTokens("old-access", "old-refresh"))
        val racing = racingAuthenticator(RefreshResult.Failed) { tokenStore.save(AuthTokens("login-access", "login-refresh")) }

        assertNull(racing.authenticate(null, unauthorized(bearer("old-access"))))
        assertEquals(AuthTokens("login-access", "login-refresh"), tokenStore.current())
    }

    @Test
    fun `logoutIfCurrent only logs out the expected tokens`() =
        runBlocking {
            val logouts = CopyOnWriteArrayList<LogoutReason>()
            val collector =
                async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) { session.loggedOut.collect { logouts += it } }
            tokenStore.save(AuthTokens("current-access", "current-refresh"))

            assertFalse(session.logoutIfCurrent(AuthTokens("stale-access", "stale-refresh"), LogoutReason.REFRESH_REJECTED))
            assertTrue(session.isLoggedIn)
            assertEquals(emptyList<LogoutReason>(), logouts)

            assertTrue(session.logoutIfCurrent(AuthTokens("current-access", "current-refresh"), LogoutReason.REFRESH_REJECTED))
            assertFalse(session.isLoggedIn)
            assertEquals(listOf(LogoutReason.REFRESH_REJECTED), logouts)
            collector.cancel()
        }

    @Test
    fun `replace only swaps the tokens it expects`() {
        val first = AuthTokens("a1", "r1")
        val second = AuthTokens("a2", "r2")
        tokenStore.save(first)

        assertFalse(tokenStore.replace(second, null))
        assertEquals(first, tokenStore.current())
        assertTrue(tokenStore.replace(first, second))
        assertEquals(second, tokenStore.current())
        assertTrue(tokenStore.replace(second, null))
        assertNull(tokenStore.current())
        assertFalse(tokenStore.replace(second, first))
        assertNull(tokenStore.current())
    }

    /** An authenticator whose refresh lets [meanwhile] (a logout or login on another thread) run before it answers. */
    private fun racingAuthenticator(
        result: RefreshResult,
        meanwhile: () -> Unit,
    ) = TokenAuthenticator(
        tokenStore,
        {
            Thread(meanwhile).apply { start() }.join(5_000)
            result
        },
        session,
    )

    /** A refresher that answers [result] only after [finish] opened, running [beforeAnswer] first; it records its calls. */
    private class WaitingRefresher(
        private val result: RefreshResult,
        private val beforeAnswer: () -> Unit = {},
    ) : TokenRefresher {
        val started = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val refreshedWith = CopyOnWriteArrayList<String>()

        override fun refresh(refreshToken: String): RefreshResult {
            refreshedWith += refreshToken
            started.countDown()
            finish.await(5, TimeUnit.SECONDS)
            beforeAnswer()
            return result
        }
    }

    private fun awaitBlocked(thread: Thread) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (thread.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.yield()
        assertEquals(Thread.State.BLOCKED, thread.state)
    }

    private fun request() = Request.Builder().url("https://server.invalid/api/v1/auth/me").build()

    private fun bearer(token: String) = request().withBearer(token)

    private fun unauthorized(
        request: Request,
        prior: Response? = null,
    ): Response =
        Response
            .Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(401)
            .message("Unauthorized")
            .priorResponse(prior)
            .build()
}
