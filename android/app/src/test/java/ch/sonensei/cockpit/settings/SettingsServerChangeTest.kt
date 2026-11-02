package ch.sonensei.cockpit.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.viewModelScope
import ch.sonensei.cockpit.capture.queue.CaptureQueueStatus
import ch.sonensei.cockpit.core.network.AuthSession
import ch.sonensei.cockpit.core.network.AuthTokens
import ch.sonensei.cockpit.core.network.InMemoryTokenStore
import ch.sonensei.cockpit.core.network.LogoutReason
import ch.sonensei.cockpit.core.network.ServerUrlRepository
import ch.sonensei.cockpit.core.network.generated.apis.AuthApi
import ch.sonensei.cockpit.core.network.generated.models.MeResponse
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import retrofit2.Response
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The settings "Change server" dialog with the real [ServerUrlRepository]: another origin logs out (as the login screen
 * does), the same origin keeps the session, an invalid address changes nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsServerChangeTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val tokenStore = InMemoryTokenStore()
    private val session = AuthSession(tokenStore)
    private val storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val authApi = mockk<AuthApi>()
    private lateinit var repository: ServerUrlRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        coEvery { authApi.getCurrentUser() } returns Response.success(MeResponse(username = "alex"))
        val dataStore = PreferenceDataStoreFactory.create(scope = storeScope) { File(tmp.root, "server.preferences_pb") }
        repository = ServerUrlRepository(dataStore, session)
    }

    @After
    fun tearDown() {
        // Stops the ViewModels' work before their store and Main go away. Joining matters: a save resumes on the store's
        // IO thread and then launches the user reload on Main, which must not happen after resetMain.
        runBlocking { viewModels.forEach { it.viewModelScope.coroutineContext.job.cancelAndJoin() } }
        storeScope.cancel()
        Dispatchers.resetMain()
    }

    /** Opens the dialog, types [address] and saves; waits (real time, the store runs on IO) until the save is answered. */
    private suspend fun SettingsViewModel.changeServerTo(address: String) {
        openServerForm()
        onServerAddressChange(address)
        saveServer()
        withTimeout(5_000) { uiState.first { it.serverForm == null || it.serverForm?.saving == false } }
    }

    private val viewModels = mutableListOf<SettingsViewModel>()

    private fun viewModel() = SettingsViewModel(repository, authApi, NoDeviceEnvironment).also { viewModels += it }

    @Test
    fun `changing to another server in settings logs out with SERVER_CHANGED and shows the new address`() =
        runBlocking {
            repository.setServerUrl("https://cockpit.example.org")
            tokenStore.save(AuthTokens("access", "refresh"))
            val logouts = CopyOnWriteArrayList<LogoutReason>()
            val collector =
                async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) { session.loggedOut.collect { logouts += it } }
            val viewModel = viewModel()

            viewModel.changeServerTo("other.example.org")

            assertNull(viewModel.uiState.value.serverForm)
            assertNull("tokens never reach the new server", tokenStore.current())
            assertEquals(listOf(LogoutReason.SERVER_CHANGED), logouts)
            assertEquals("https://other.example.org/".toHttpUrl(), repository.serverUrl.first())
            withTimeout(5_000) { viewModel.uiState.first { it.serverUrl == "https://other.example.org/".toHttpUrl() } }
            collector.cancel()
        }

    @Test
    fun `saving an address on the same origin keeps the session`() =
        runBlocking {
            repository.setServerUrl("https://cockpit.example.org")
            tokenStore.save(AuthTokens("access", "refresh"))
            val viewModel = viewModel()

            viewModel.changeServerTo("https://COCKPIT.example.org/cockpit")

            assertNull(viewModel.uiState.value.serverForm)
            assertEquals(AuthTokens("access", "refresh"), tokenStore.current())
            assertEquals("https://cockpit.example.org/cockpit/".toHttpUrl(), repository.serverUrl.first())
        }

    @Test
    fun `an address the app refuses keeps the server and the session`() =
        runBlocking {
            repository.setServerUrl("https://cockpit.example.org")
            tokenStore.save(AuthTokens("access", "refresh"))
            val viewModel = viewModel()

            viewModel.changeServerTo("http://other.example.org")

            assertEquals(
                "http://other.example.org",
                viewModel.uiState.value.serverForm
                    ?.error
                    ?.address,
            )
            assertEquals(AuthTokens("access", "refresh"), tokenStore.current())
            assertEquals("https://cockpit.example.org/".toHttpUrl(), repository.serverUrl.first())
        }

    /** The device side is not part of these tests. */
    private object NoDeviceEnvironment : SettingsEnvironment {
        override val captureStatus: Flow<CaptureQueueStatus> = flowOf(CaptureQueueStatus(0, null))

        override suspend fun syncCapturesNow() = Unit

        override fun permissions() = PermissionState(notifications = true, exactAlarms = true, exactAlarmsSettingExists = true)

        override fun distributors() = DistributorState(emptyList(), null)

        override suspend fun useDistributor(packageName: String) = Unit

        override val appVersion = AppVersion("0.1.0", 1)
    }
}
