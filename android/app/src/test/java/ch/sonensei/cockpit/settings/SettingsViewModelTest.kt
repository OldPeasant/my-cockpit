package ch.sonensei.cockpit.settings

import ch.sonensei.cockpit.capture.queue.CaptureQueueStatus
import ch.sonensei.cockpit.core.network.ServerUrlRepository
import ch.sonensei.cockpit.core.network.ServerUrlValidation
import ch.sonensei.cockpit.core.network.ServerUrlValidator
import ch.sonensei.cockpit.core.network.generated.apis.AuthApi
import ch.sonensei.cockpit.core.network.generated.models.MeResponse
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val serverUrl = MutableStateFlow<HttpUrl?>("https://cockpit.example.org/".toHttpUrl())
    private val serverUrls = mockk<ServerUrlRepository>()
    private val authApi = mockk<AuthApi>()
    private val environment = FakeEnvironment()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { serverUrls.serverUrl } returns serverUrl
        coEvery { serverUrls.setServerUrl(any()) } answers { ServerUrlValidator.validate(firstArg()) }
        coEvery { authApi.getCurrentUser() } returns Response.success(MeResponse(username = "alex"))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.viewModel(): SettingsViewModel {
        val viewModel = SettingsViewModel(serverUrls, authApi, environment)
        backgroundScope.launch(dispatcher) { viewModel.uiState.collect {} }
        advanceUntilIdle()
        return viewModel
    }

    @Test
    fun `shows the server, the signed-in user, permissions, distributors, queue and version`() =
        runTest(dispatcher) {
            val state = viewModel().uiState.value

            assertEquals("https://cockpit.example.org/".toHttpUrl(), state.serverUrl)
            assertEquals(UserName.Known("alex"), state.user)
            assertEquals(environment.permissions, state.permissions)
            assertEquals(environment.distributorState, state.distributors)
            assertEquals(CaptureQueueStatus(2, "Server error (HTTP 503)"), state.capture)
            assertEquals(AppVersion("0.1.0", 7), state.appVersion)
        }

    @Test
    fun `the download link is below the server, also below a path prefix`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            assertEquals(
                "https://cockpit.example.org/download/android",
                viewModel.uiState.value.downloadUrl
                    .toString(),
            )

            serverUrl.value = "https://example.org/cockpit/".toHttpUrl()
            advanceUntilIdle()
            assertEquals(
                "https://example.org/cockpit/download/android",
                viewModel.uiState.value.downloadUrl
                    .toString(),
            )

            serverUrl.value = null
            advanceUntilIdle()
            assertNull(viewModel.uiState.value.downloadUrl)
        }

    @Test
    fun `an unreachable server or an error answer leaves the user unknown, retry asks again`() =
        runTest(dispatcher) {
            coEvery { authApi.getCurrentUser() } throws IOException("offline")
            val viewModel = viewModel()
            assertEquals(UserName.Unknown, viewModel.uiState.value.user)

            coEvery { authApi.getCurrentUser() } returns Response.error(500, "".toResponseBody())
            viewModel.loadUser()
            assertEquals(UserName.Loading, viewModel.uiState.value.user)
            advanceUntilIdle()
            assertEquals(UserName.Unknown, viewModel.uiState.value.user)

            coEvery { authApi.getCurrentUser() } returns Response.success(MeResponse(username = "alex"))
            viewModel.loadUser()
            advanceUntilIdle()
            assertEquals(UserName.Known("alex"), viewModel.uiState.value.user)
        }

    @Test
    fun `changing the server goes through setServerUrl, closes the form and reloads the user`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            viewModel.openServerForm()
            assertEquals(
                "https://cockpit.example.org/",
                viewModel.uiState.value.serverForm
                    ?.address,
            )

            viewModel.onServerAddressChange("other.example.org")
            viewModel.saveServer()
            assertTrue(
                viewModel.uiState.value.serverForm!!
                    .saving,
            )
            advanceUntilIdle()

            coVerify { serverUrls.setServerUrl("other.example.org") }
            assertNull(viewModel.uiState.value.serverForm)
            coVerify(exactly = 2) { authApi.getCurrentUser() }
        }

    @Test
    fun `an invalid address keeps the form open with the reason, editing clears it`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            viewModel.openServerForm()
            viewModel.onServerAddressChange("http://cockpit.example.org")
            viewModel.saveServer()
            advanceUntilIdle()

            val form = viewModel.uiState.value.serverForm!!
            assertFalse(form.saving)
            assertEquals(ServerUrlValidation.Reason.HTTPS_REQUIRED, form.error?.reason)

            viewModel.onServerAddressChange("https://cockpit.example.org")
            assertNull(
                viewModel.uiState.value.serverForm!!
                    .error,
            )
            viewModel.closeServerForm()
            assertNull(viewModel.uiState.value.serverForm)
        }

    @Test
    fun `refresh reads permissions and distributors again`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            environment.permissions = PermissionState(notifications = true, exactAlarms = true, exactAlarmsSettingExists = true)
            environment.distributorState = DistributorState(emptyList(), null)

            viewModel.refresh()
            advanceUntilIdle()

            assertEquals(environment.permissions, viewModel.uiState.value.permissions)
            assertEquals(DistributorState(emptyList(), null), viewModel.uiState.value.distributors)
        }

    @Test
    fun `choosing a distributor switches to it and closes the picker`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            viewModel.openDistributorPicker()
            assertTrue(viewModel.uiState.value.distributorPickerOpen)

            viewModel.chooseDistributor("org.example.second")
            advanceUntilIdle()

            assertEquals(listOf("org.example.second"), environment.used)
            assertFalse(viewModel.uiState.value.distributorPickerOpen)
            assertEquals(
                "org.example.second",
                viewModel.uiState.value.distributors
                    ?.selected
                    ?.packageName,
            )
        }

    @Test
    fun `a failing distributor switch does not crash`() =
        runTest(dispatcher) {
            environment.useFails = true
            val viewModel = viewModel()
            viewModel.chooseDistributor("org.example.second")
            advanceUntilIdle()

            assertEquals(
                "org.example.first",
                viewModel.uiState.value.distributors
                    ?.selected
                    ?.packageName,
            )
        }

    @Test
    fun `sync now enqueues the upload work and confirms until the queue changes`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            viewModel.syncNow()
            advanceUntilIdle()

            assertEquals(1, environment.syncs)
            assertTrue(viewModel.uiState.value.syncRequested)

            environment.status.value = CaptureQueueStatus(0, null)
            advanceUntilIdle()
            assertFalse(viewModel.uiState.value.syncRequested)
            assertEquals(CaptureQueueStatus(0, null), viewModel.uiState.value.capture)
        }

    @Test
    fun `a failure to enqueue is shown`() =
        runTest(dispatcher) {
            environment.syncFails = true
            val viewModel = viewModel()
            viewModel.syncNow()
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.syncFailed)
            assertFalse(viewModel.uiState.value.syncRequested)
        }

    private class FakeEnvironment : SettingsEnvironment {
        val status = MutableStateFlow(CaptureQueueStatus(2, "Server error (HTTP 503)"))
        var permissions = PermissionState(notifications = false, exactAlarms = false, exactAlarmsSettingExists = true)
        private val first = Distributor("org.example.first", "First")
        private val second = Distributor("org.example.second", "Second")
        var distributorState = DistributorState(listOf(first, second), first)
        val used = mutableListOf<String>()
        var useFails = false
        var syncs = 0
        var syncFails = false

        override val captureStatus: Flow<CaptureQueueStatus> = status

        override suspend fun syncCapturesNow() {
            if (syncFails) throw IllegalStateException("WorkManager")
            syncs++
        }

        override fun permissions() = permissions

        override fun distributors() = distributorState

        override suspend fun useDistributor(packageName: String) {
            if (useFails) throw IllegalStateException("library")
            used += packageName
            distributorState = distributorState.copy(selected = distributorState.installed.first { it.packageName == packageName })
        }

        override val appVersion = AppVersion("0.1.0", 7)
    }
}
