package ch.sonensei.cockpit.auth

import ch.sonensei.cockpit.core.network.generated.models.PasskeyResponse
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
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

    @Test
    fun `a second tap while a passkey login runs starts no second login`() =
        runTest(dispatcher) {
            val pending = CompletableDeferred<LoginResult>()
            coEvery { passkeys.login(any(), any()) } coAnswers { pending.await() }
            val viewModel = LoginViewModel(auth, passkeys)
            advanceUntilIdle()

            viewModel.submitPasskey(credentials)
            advanceUntilIdle()
            assertFalse(viewModel.uiState.value.canSubmitPasskey)
            viewModel.submitPasskey(credentials)
            pending.complete(LoginResult.PasskeyRejected)
            advanceUntilIdle()

            coVerify(exactly = 1) { passkeys.login(any(), any()) }
            assertEquals(LoginError.PASSKEY_REJECTED, viewModel.uiState.value.error)
        }

    @Test
    fun `a new passkey attempt clears the previous error while it runs`() =
        runTest(dispatcher) {
            coEvery { passkeys.login(any(), any()) } returns LoginResult.NoPasskey
            val viewModel = LoginViewModel(auth, passkeys)
            advanceUntilIdle()
            viewModel.submitPasskey(credentials)
            advanceUntilIdle()
            assertEquals(LoginError.NO_PASSKEY, viewModel.uiState.value.error)

            coEvery { passkeys.login(any(), any()) } returns LoginResult.PasskeyCancelled
            viewModel.submitPasskey(credentials)
            assertNull(viewModel.uiState.value.error)
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.error)
            assertFalse(viewModel.uiState.value.submitting)
        }

    @Test
    fun `settings that cannot load show the failure, and a retry recovers`() =
        runTest(dispatcher) {
            coEvery { passkeys.list() } returnsMany listOf(null, listOf(PASSKEY))
            val viewModel = PasskeySettingsViewModel(passkeys)
            advanceUntilIdle()
            assertNull(viewModel.uiState.value.passkeys)
            assertTrue(viewModel.uiState.value.loadFailed)

            viewModel.load()
            assertFalse(viewModel.uiState.value.loadFailed)
            advanceUntilIdle()

            assertEquals(listOf(PASSKEY), viewModel.uiState.value.passkeys)
            assertFalse(viewModel.uiState.value.loadFailed)
        }

    @Test
    fun `a second submit while a registration runs registers once`() =
        runTest(dispatcher) {
            val pending = CompletableDeferred<PasskeyRegistrationResult>()
            coEvery { passkeys.list() } returns emptyList()
            coEvery { passkeys.register(any(), any(), any()) } coAnswers { pending.await() }
            val viewModel = PasskeySettingsViewModel(passkeys)
            advanceUntilIdle()
            viewModel.openForm()
            viewModel.onNameChange("Pixel")
            viewModel.onCodeChange("123456")

            viewModel.submit(credentials)
            advanceUntilIdle()
            viewModel.submit(credentials)
            pending.complete(PasskeyRegistrationResult.Success(PASSKEY))
            advanceUntilIdle()

            coVerify(exactly = 1) { passkeys.register(any(), any(), any()) }
            assertEquals(listOf(PASSKEY), viewModel.uiState.value.passkeys)
        }

    @Test
    fun `no registration without an open form, a name or a code`() =
        runTest(dispatcher) {
            coEvery { passkeys.list() } returns emptyList()
            val viewModel = PasskeySettingsViewModel(passkeys)
            advanceUntilIdle()

            viewModel.submit(credentials)
            viewModel.onCodeChange("123456")
            assertNull(viewModel.uiState.value.form)
            viewModel.openForm()
            viewModel.onNameChange("   ")
            viewModel.onCodeChange("123456")
            viewModel.submit(credentials)
            viewModel.onNameChange("Pixel")
            viewModel.onCodeChange(" ")
            viewModel.submit(credentials)
            advanceUntilIdle()

            coVerify(exactly = 0) { passkeys.register(any(), any(), any()) }
            assertFalse(
                viewModel.uiState.value.form!!
                    .submitting,
            )
        }

    @Test
    fun `editing the form clears its error, and reopening it clears the last confirmation`() =
        runTest(dispatcher) {
            coEvery { passkeys.list() } returns emptyList()
            coEvery { passkeys.register(any(), any(), any()) } returnsMany
                listOf(PasskeyRegistrationResult.Success(PASSKEY), PasskeyRegistrationResult.WrongCode)
            val viewModel = PasskeySettingsViewModel(passkeys)
            advanceUntilIdle()
            viewModel.openForm()
            viewModel.onCodeChange("123456")
            viewModel.submit(credentials)
            advanceUntilIdle()
            assertEquals("Pixel", viewModel.uiState.value.added)

            viewModel.openForm()
            assertNull(viewModel.uiState.value.added)
            viewModel.onCodeChange("000000")
            viewModel.submit(credentials)
            advanceUntilIdle()
            assertEquals(
                PasskeyError.WRONG_CODE,
                viewModel.uiState.value.form!!
                    .error,
            )
            viewModel.onNameChange("Pixel 9")
            assertNull(
                viewModel.uiState.value.form!!
                    .error,
            )
        }

    @Test
    fun `the add form never prints the authenticator code`() {
        val form = PasskeyForm(name = "Pixel", code = "482913")

        assertFalse(form.toString().contains("482913"))
        assertTrue(form.toString().contains("Pixel"))
    }

    @Test
    fun `a list that answers after a registration does not hide the new passkey`() =
        runTest(dispatcher) {
            // The first load is slow; meanwhile the user adds a passkey. The list was read before the passkey existed.
            val slowList = CompletableDeferred<List<PasskeyResponse>?>()
            val older = PASSKEY.copy(id = UUID.fromString("0192f1a0-0000-7000-8000-000000000008"), name = "Laptop")
            coEvery { passkeys.list() } coAnswers { slowList.await() } coAndThen { listOf(older, PASSKEY) }
            coEvery { passkeys.register(any(), any(), any()) } returns PasskeyRegistrationResult.Success(PASSKEY)
            val viewModel = PasskeySettingsViewModel(passkeys)
            advanceUntilIdle()
            viewModel.openForm()
            viewModel.onCodeChange("123456")
            viewModel.submit(credentials)
            advanceUntilIdle()
            // The stale load was replaced by a fresh one, which knows the new passkey and the older ones.
            coVerify(exactly = 2) { passkeys.list() }
            assertEquals(listOf(older, PASSKEY), viewModel.uiState.value.passkeys)

            slowList.complete(emptyList())
            advanceUntilIdle()

            assertEquals(listOf(older, PASSKEY), viewModel.uiState.value.passkeys)
            assertFalse(viewModel.uiState.value.loadFailed)
        }

    @Test
    fun `a new load replaces a running one, so an older answer never wins`() =
        runTest(dispatcher) {
            val first = CompletableDeferred<List<PasskeyResponse>?>()
            coEvery { passkeys.list() } coAnswers { first.await() } coAndThen { listOf(PASSKEY) }
            val viewModel = PasskeySettingsViewModel(passkeys)
            advanceUntilIdle()

            viewModel.load()
            advanceUntilIdle()
            first.complete(null)
            advanceUntilIdle()

            assertEquals(listOf(PASSKEY), viewModel.uiState.value.passkeys)
            assertFalse(viewModel.uiState.value.loadFailed)
        }

    @Test
    fun `a passkey name is capped at the server's 100 characters`() =
        runTest(dispatcher) {
            coEvery { passkeys.list() } returns emptyList()
            coEvery { passkeys.register(any(), any(), any()) } returns PasskeyRegistrationResult.Success(PASSKEY)
            val viewModel = PasskeySettingsViewModel(passkeys)
            advanceUntilIdle()
            viewModel.openForm()
            viewModel.onCodeChange("123456")

            viewModel.onNameChange("a".repeat(PasskeyForm.NAME_MAX))
            assertEquals(
                PasskeyForm.NAME_MAX,
                viewModel.uiState.value.form!!
                    .name.length,
            )
            assertTrue(
                viewModel.uiState.value.form!!
                    .canSubmit,
            )
            viewModel.onNameChange("b".repeat(PasskeyForm.NAME_MAX + 1))
            assertEquals(
                "b".repeat(PasskeyForm.NAME_MAX),
                viewModel.uiState.value.form!!
                    .name,
            )
            assertFalse(PasskeyForm(name = "c".repeat(PasskeyForm.NAME_MAX + 1), code = "123456").canSubmit)
            assertTrue(PasskeyForm(name = " " + "c".repeat(PasskeyForm.NAME_MAX) + " ", code = "123456").canSubmit)

            viewModel.submit(credentials)
            advanceUntilIdle()
            coVerify { passkeys.register("b".repeat(PasskeyForm.NAME_MAX), "123456", credentials) }
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
