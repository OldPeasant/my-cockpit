package ch.sonensei.cockpit.auth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.core.network.AuthSession
import ch.sonensei.cockpit.core.network.AuthTokens
import ch.sonensei.cockpit.core.network.LogoutReason
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** [SessionViewModel]: routing state from the stored session, and the logout action. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class SessionViewModelTest {
    private val repository = mockk<AuthRepository>(relaxed = true)
    private lateinit var tokenStore: PersistentTokenStore

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val prefs =
            ApplicationProvider
                .getApplicationContext<Context>()
                .getSharedPreferences("session-view-model-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        // Memory-only cipher: routing does not depend on what reaches the disk.
        tokenStore = PersistentTokenStore(prefs, NoKeyCipher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `without a session the app is logged out`() {
        val viewModel = SessionViewModel(tokenStore, repository)

        assertFalse(viewModel.loggedIn.value)
    }

    @Test
    fun `with a stored session the app starts logged in`() {
        tokenStore.login(TOKENS, DEVICE_ID)

        val viewModel = SessionViewModel(tokenStore, repository)

        assertTrue(viewModel.loggedIn.value)
    }

    @Test
    fun `a login switches to logged in`() {
        val viewModel = SessionViewModel(tokenStore, repository)

        tokenStore.login(TOKENS, DEVICE_ID)

        assertTrue(viewModel.loggedIn.value)
    }

    @Test
    fun `a logout by the network layer switches to logged out`() {
        tokenStore.login(TOKENS, DEVICE_ID)
        val viewModel = SessionViewModel(tokenStore, repository)

        AuthSession(tokenStore).logout(LogoutReason.REFRESH_REJECTED)

        assertFalse(viewModel.loggedIn.value)
    }

    @Test
    fun `a rotated token keeps the session`() {
        tokenStore.login(TOKENS, DEVICE_ID)
        val viewModel = SessionViewModel(tokenStore, repository)

        assertTrue(tokenStore.replace(TOKENS, AuthTokens("access-2", "refresh-2")))

        assertTrue(viewModel.loggedIn.value)
    }

    @Test
    fun `logout delegates to the repository`() {
        coEvery { repository.logout() } coAnswers { tokenStore.clear() }
        tokenStore.login(TOKENS, DEVICE_ID)
        val viewModel = SessionViewModel(tokenStore, repository)

        viewModel.logout()

        coVerify(exactly = 1) { repository.logout() }
        assertFalse(viewModel.loggedIn.value)
    }

    @Test
    fun `taps while a logout runs start no second logout`() {
        val revocation = CompletableDeferred<Unit>()
        coEvery { repository.logout() } coAnswers {
            revocation.await()
            tokenStore.clear()
        }
        tokenStore.login(TOKENS, DEVICE_ID)
        val viewModel = SessionViewModel(tokenStore, repository)

        repeat(3) { viewModel.logout() }
        revocation.complete(Unit)

        coVerify(exactly = 1) { repository.logout() }
        assertFalse(viewModel.loggedIn.value)
    }

    @Test
    fun `a new logout can start once the previous one finished`() {
        coEvery { repository.logout() } coAnswers { tokenStore.clear() }
        val viewModel = SessionViewModel(tokenStore, repository)

        repeat(2) {
            tokenStore.login(TOKENS, DEVICE_ID)
            viewModel.logout()
        }

        coVerify(exactly = 2) { repository.logout() }
        assertFalse(viewModel.loggedIn.value)
    }

    private object NoKeyCipher : TokenCipher {
        override fun encrypt(plain: ByteArray): ByteArray? = null

        override fun decrypt(encrypted: ByteArray): ByteArray? = null
    }

    private companion object {
        val TOKENS = AuthTokens("access-fictitious-1", "refresh-fictitious-1")
        const val DEVICE_ID = "0192f1a0-0000-7000-8000-000000000001"
    }
}
