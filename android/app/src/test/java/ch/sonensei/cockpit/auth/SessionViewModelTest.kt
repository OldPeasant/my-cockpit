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

/** [SessionViewModel]: routing state from the stored session, and cache clearing on every kind of logout. */
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
    fun `without a session the app is logged out and no caches are cleared at start`() {
        val viewModel = SessionViewModel(tokenStore, repository)

        assertFalse(viewModel.loggedIn.value)
        coVerify(exactly = 0) { repository.clearCaches() }
    }

    @Test
    fun `with a stored session the app starts logged in`() {
        tokenStore.login(TOKENS, DEVICE_ID)

        val viewModel = SessionViewModel(tokenStore, repository)

        assertTrue(viewModel.loggedIn.value)
        coVerify(exactly = 0) { repository.clearCaches() }
    }

    @Test
    fun `a login switches to logged in without clearing caches`() {
        val viewModel = SessionViewModel(tokenStore, repository)

        tokenStore.login(TOKENS, DEVICE_ID)

        assertTrue(viewModel.loggedIn.value)
        coVerify(exactly = 0) { repository.clearCaches() }
    }

    @Test
    fun `a logout by the network layer clears the caches`() {
        tokenStore.login(TOKENS, DEVICE_ID)
        val viewModel = SessionViewModel(tokenStore, repository)

        AuthSession(tokenStore).logout(LogoutReason.REFRESH_REJECTED)

        assertFalse(viewModel.loggedIn.value)
        coVerify(exactly = 1) { repository.clearCaches() }
    }

    @Test
    fun `a rotated token keeps the session and the caches`() {
        tokenStore.login(TOKENS, DEVICE_ID)
        val viewModel = SessionViewModel(tokenStore, repository)

        assertTrue(tokenStore.replace(TOKENS, AuthTokens("access-2", "refresh-2")))

        assertTrue(viewModel.loggedIn.value)
        coVerify(exactly = 0) { repository.clearCaches() }
    }

    @Test
    fun `every logout of a running app clears the caches again`() {
        val viewModel = SessionViewModel(tokenStore, repository)

        repeat(2) {
            tokenStore.login(TOKENS, DEVICE_ID)
            tokenStore.clear()
        }

        assertFalse(viewModel.loggedIn.value)
        coVerify(exactly = 2) { repository.clearCaches() }
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

    private object NoKeyCipher : TokenCipher {
        override fun encrypt(plain: ByteArray): ByteArray? = null

        override fun decrypt(encrypted: ByteArray): ByteArray? = null
    }

    private companion object {
        val TOKENS = AuthTokens("access-fictitious-1", "refresh-fictitious-1")
        const val DEVICE_ID = "0192f1a0-0000-7000-8000-000000000001"
    }
}
