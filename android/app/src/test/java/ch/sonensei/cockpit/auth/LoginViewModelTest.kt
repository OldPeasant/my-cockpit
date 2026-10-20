package ch.sonensei.cockpit.auth

import ch.sonensei.cockpit.core.network.ServerUrlValidation
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repository = mockk<AuthRepository>()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { repository.serverUrl } returns flowOf("https://cockpit.example.org/")
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `the configured server prefills the form`() =
        runTest(dispatcher) {
            val viewModel = LoginViewModel(repository)
            advanceUntilIdle()

            assertEquals("https://cockpit.example.org/", viewModel.uiState.value.serverUrl)
        }

    @Test
    fun `submitting needs every field`() =
        runTest(dispatcher) {
            val viewModel = LoginViewModel(repository)
            advanceUntilIdle()
            viewModel.onUsernameChange("alex")
            viewModel.onPasswordChange("pw")

            assertFalse(viewModel.uiState.value.canSubmit)
            viewModel.submit()
            advanceUntilIdle()
            coVerify(exactly = 0) { repository.login(any(), any(), any(), any()) }

            viewModel.onCodeChange("123456")
            assertTrue(viewModel.uiState.value.canSubmit)
        }

    @Test
    fun `a successful login sends the totp and clears the secrets`() =
        runTest(dispatcher) {
            coEvery { repository.login(any(), any(), any(), any()) } returns LoginResult.Success
            val viewModel = filledIn()

            viewModel.submit()
            assertTrue(viewModel.uiState.value.submitting)
            assertFalse(viewModel.uiState.value.canSubmit)
            advanceUntilIdle()

            coVerify { repository.login("https://cockpit.example.org/", "alex", "pw", SecondFactor.Totp("123456")) }
            val state = viewModel.uiState.value
            assertFalse(state.submitting)
            assertNull(state.error)
            assertEquals("", state.password)
            assertEquals("", state.code)
        }

    @Test
    fun `the recovery code toggle sends a recovery code and resets the code field`() =
        runTest(dispatcher) {
            coEvery { repository.login(any(), any(), any(), any()) } returns LoginResult.Success
            val viewModel = filledIn()

            viewModel.onUseRecoveryCodeChange(true)
            assertEquals("", viewModel.uiState.value.code)
            viewModel.onCodeChange("abcde-fghjk")
            viewModel.submit()
            advanceUntilIdle()

            coVerify { repository.login(any(), any(), any(), SecondFactor.RecoveryCode("abcde-fghjk")) }
        }

    @Test
    fun `wrong credentials show the error, keep the password and ask for a new code`() =
        runTest(dispatcher) {
            coEvery { repository.login(any(), any(), any(), any()) } returns LoginResult.WrongCredentials
            val viewModel = filledIn()

            viewModel.submit()
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals(LoginError.WRONG_CREDENTIALS, state.error)
            assertEquals("pw", state.password)
            assertEquals("", state.code)

            viewModel.onCodeChange("654321")
            assertNull(viewModel.uiState.value.error)
        }

    @Test
    fun `each failure maps to its error`() =
        runTest(dispatcher) {
            val cases =
                mapOf(
                    LoginResult.InvalidServerUrl(ServerUrlValidation.Reason.MALFORMED) to LoginError.SERVER_URL_MALFORMED,
                    LoginResult.InvalidServerUrl(ServerUrlValidation.Reason.HTTPS_REQUIRED) to LoginError.SERVER_URL_HTTPS_REQUIRED,
                    LoginResult.InvalidRequest to LoginError.INVALID_REQUEST,
                    LoginResult.Network to LoginError.NETWORK,
                    LoginResult.ServerError to LoginError.SERVER,
                )
            cases.forEach { (result, error) ->
                coEvery { repository.login(any(), any(), any(), any()) } returns result
                val viewModel = filledIn()

                viewModel.submit()
                advanceUntilIdle()

                assertEquals(result.toString(), error, viewModel.uiState.value.error)
                assertFalse(viewModel.uiState.value.submitting)
            }
        }

    @Test
    fun `too many attempts blocks submitting and counts down the wait`() =
        runTest(dispatcher) {
            coEvery { repository.login(any(), any(), any(), any()) } returns LoginResult.TooManyAttempts(3)
            val viewModel = filledIn()

            viewModel.submit()
            runCurrent()

            assertEquals(LoginError.TOO_MANY_ATTEMPTS, viewModel.uiState.value.error)
            assertEquals(3, viewModel.uiState.value.retryAfterSeconds)
            viewModel.onCodeChange("111111")
            assertFalse(viewModel.uiState.value.canSubmit)
            assertEquals("editing keeps the wait visible", LoginError.TOO_MANY_ATTEMPTS, viewModel.uiState.value.error)
            viewModel.submit()

            advanceTimeBy(1_001)
            assertEquals(2, viewModel.uiState.value.retryAfterSeconds)
            advanceTimeBy(2_000)
            assertEquals(0, viewModel.uiState.value.retryAfterSeconds)
            assertNull(viewModel.uiState.value.error)
            assertTrue(viewModel.uiState.value.canSubmit)
            coVerify(exactly = 1) { repository.login(any(), any(), any(), any()) }
        }

    @Test
    fun `the state's string form hides the password and code`() {
        val text = LoginUiState(password = "secret-pw", code = "123456").toString()

        assertFalse(text.contains("secret-pw"))
        assertFalse(text.contains("123456"))
    }

    private fun kotlinx.coroutines.test.TestScope.filledIn(): LoginViewModel {
        val viewModel = LoginViewModel(repository)
        advanceUntilIdle()
        viewModel.onUsernameChange("alex")
        viewModel.onPasswordChange("pw")
        viewModel.onCodeChange("123456")
        return viewModel
    }
}
