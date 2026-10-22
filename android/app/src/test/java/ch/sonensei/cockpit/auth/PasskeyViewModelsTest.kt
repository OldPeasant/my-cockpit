package ch.sonensei.cockpit.auth

import ch.sonensei.cockpit.core.network.generated.models.PasskeyResponse
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
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.OffsetDateTime
import java.util.UUID

/** The passkey parts of [LoginViewModel] and [PasskeySettingsViewModel]. */
@OptIn(ExperimentalCoroutinesApi::class)
class PasskeyViewModelsTest {
    private val dispatcher = StandardTestDispatcher()
    private val auth = mockk<AuthRepository>()
    private val passkeys = mockk<PasskeyRepository>()
    private val credentials = mockk<PasskeyCredentials>()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { auth.serverUrl } returns flowOf("https://cockpit.example.org/")
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `a passkey login needs only the server`() =
        runTest(dispatcher) {
            coEvery { passkeys.login(any(), any()) } returns LoginResult.Success
            val viewModel = LoginViewModel(auth, passkeys)
            advanceUntilIdle()

            assertFalse(viewModel.uiState.value.canSubmit)
            assertTrue(viewModel.uiState.value.canSubmitPasskey)
            viewModel.submitPasskey(credentials)
            assertTrue(viewModel.uiState.value.submitting)
            advanceUntilIdle()

            coVerify { passkeys.login("https://cockpit.example.org/", credentials) }
            assertFalse(viewModel.uiState.value.submitting)
            assertNull(viewModel.uiState.value.error)
        }

    @Test
    fun `no passkey login without a server`() =
        runTest(dispatcher) {
            val viewModel = LoginViewModel(auth, passkeys)
            advanceUntilIdle()
            viewModel.onServerUrlChange(" ")

            viewModel.submitPasskey(credentials)
            advanceUntilIdle()

            coVerify(exactly = 0) { passkeys.login(any(), any()) }
        }

    @Test
    fun `passkey login results show their errors, a cancel shows none`() =
        runTest(dispatcher) {
            val expected =
                mapOf(
                    LoginResult.PasskeyCancelled to null,
                    LoginResult.PasskeyRejected to LoginError.PASSKEY_REJECTED,
                    LoginResult.NoPasskey to LoginError.NO_PASSKEY,
                    LoginResult.PasskeyUnavailable to LoginError.PASSKEY_UNAVAILABLE,
                    LoginResult.PasskeyFailed to LoginError.PASSKEY_FAILED,
                    LoginResult.TotpResetRequired to LoginError.TOTP_RESET_REQUIRED,
                    LoginResult.Network to LoginError.NETWORK,
                )
            val viewModel = LoginViewModel(auth, passkeys)
            advanceUntilIdle()
            expected.forEach { (result, error) ->
                coEvery { passkeys.login(any(), any()) } returns result
                viewModel.submitPasskey(credentials)
                advanceUntilIdle()
                assertEquals(result.toString(), error, viewModel.uiState.value.error)
                assertFalse(viewModel.uiState.value.submitting)
            }
        }

    @Test
    fun `too many passkey attempts block both logins until the wait is over`() =
        runTest(dispatcher) {
            coEvery { passkeys.login(any(), any()) } returns LoginResult.TooManyAttempts(2)
            val viewModel = LoginViewModel(auth, passkeys)
            advanceUntilIdle()

            viewModel.submitPasskey(credentials)
            dispatcher.scheduler.runCurrent()
            assertEquals(LoginError.TOO_MANY_ATTEMPTS, viewModel.uiState.value.error)
            assertFalse(viewModel.uiState.value.canSubmitPasskey)
            advanceTimeBy(2_001)

            assertTrue(viewModel.uiState.value.canSubmitPasskey)
            assertNull(viewModel.uiState.value.error)
        }

    @Test
    fun `settings load the passkeys and keep them when a reload fails`() =
        runTest(dispatcher) {
            coEvery { passkeys.list() } returnsMany listOf(listOf(PASSKEY), null)
            val viewModel = PasskeySettingsViewModel(passkeys)
            advanceUntilIdle()
            assertEquals(listOf(PASSKEY), viewModel.uiState.value.passkeys)

            viewModel.load()
            advanceUntilIdle()

            assertEquals(listOf(PASSKEY), viewModel.uiState.value.passkeys)
            assertTrue(viewModel.uiState.value.loadFailed)
        }

    @Test
    fun `adding a passkey registers it with name and code and lists it`() =
        runTest(dispatcher) {
            coEvery { passkeys.list() } returns emptyList()
            coEvery { passkeys.register(any(), any(), any()) } returns PasskeyRegistrationResult.Success(PASSKEY)
            val viewModel = PasskeySettingsViewModel(passkeys)
            advanceUntilIdle()

            viewModel.openForm()
            assertTrue(
                viewModel.uiState.value.form!!
                    .name
                    .isNotBlank(),
            )
            viewModel.onNameChange("Pixel")
            assertFalse(
                viewModel.uiState.value.form!!
                    .canSubmit,
            )
            viewModel.onCodeChange("123456")
            viewModel.submit(credentials)
            assertTrue(
                viewModel.uiState.value.form!!
                    .submitting,
            )
            advanceUntilIdle()

            coVerify { passkeys.register("Pixel", "123456", credentials) }
            val state = viewModel.uiState.value
            assertNull(state.form)
            assertEquals("Pixel", state.added)
            assertEquals(listOf(PASSKEY), state.passkeys)
        }

    @Test
    fun `a failed registration keeps the form open with an error and asks for a new code`() =
        runTest(dispatcher) {
            coEvery { passkeys.list() } returns emptyList()
            val viewModel = PasskeySettingsViewModel(passkeys)
            advanceUntilIdle()
            val expected =
                mapOf(
                    PasskeyRegistrationResult.WrongCode to PasskeyError.WRONG_CODE,
                    PasskeyRegistrationResult.TooManyAttempts(5) to PasskeyError.TOO_MANY_ATTEMPTS,
                    PasskeyRegistrationResult.AlreadyRegistered to PasskeyError.ALREADY_REGISTERED,
                    PasskeyRegistrationResult.Unavailable to PasskeyError.UNAVAILABLE,
                    PasskeyRegistrationResult.Failed to PasskeyError.FAILED,
                    PasskeyRegistrationResult.Network to PasskeyError.NETWORK,
                    PasskeyRegistrationResult.ServerError to PasskeyError.SERVER,
                )
            viewModel.openForm()
            expected.forEach { (result, error) ->
                coEvery { passkeys.register(any(), any(), any()) } returns result
                viewModel.onCodeChange("123456")
                viewModel.submit(credentials)
                advanceUntilIdle()
                val form = viewModel.uiState.value.form!!
                assertEquals(error, form.error)
                assertEquals("", form.code)
                assertFalse(form.submitting)
            }
        }

    @Test
    fun `a cancelled registration keeps the code, and the form closes only when idle`() =
        runTest(dispatcher) {
            coEvery { passkeys.list() } returns emptyList()
            coEvery { passkeys.register(any(), any(), any()) } returns PasskeyRegistrationResult.Cancelled
            val viewModel = PasskeySettingsViewModel(passkeys)
            advanceUntilIdle()
            viewModel.openForm()
            viewModel.onCodeChange("123456")

            viewModel.submit(credentials)
            viewModel.closeForm()
            assertTrue(
                viewModel.uiState.value.form!!
                    .submitting,
            )
            advanceUntilIdle()

            assertEquals(
                "123456",
                viewModel.uiState.value.form!!
                    .code,
            )
            assertNull(
                viewModel.uiState.value.form!!
                    .error,
            )
            viewModel.closeForm()
            assertNull(viewModel.uiState.value.form)
        }

    private companion object {
        val PASSKEY =
            PasskeyResponse(
                createdAt = OffsetDateTime.parse("2026-10-22T10:00:00Z"),
                id = UUID.fromString("0192f1a0-0000-7000-8000-000000000009"),
                name = "Pixel",
            )
    }
}
