package ch.sonensei.cockpit.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.sonensei.cockpit.capture.queue.CaptureQueueStatus
import ch.sonensei.cockpit.core.network.ServerUrlRepository
import ch.sonensei.cockpit.core.network.ServerUrlValidation
import ch.sonensei.cockpit.core.network.generated.apis.AuthApi
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.HttpUrl
import javax.inject.Inject

/** The signed-in user as the server reports it. */
sealed interface UserName {
    data object Loading : UserName

    /** The server could not be asked (offline or an error). */
    data object Unknown : UserName

    data class Known(
        val name: String,
    ) : UserName
}

/** The open "change server" dialog. */
data class ServerForm(
    val address: String,
    val saving: Boolean = false,
    /** Why the last save was refused, with the address as the app understood it. */
    val error: ServerUrlValidation.Invalid? = null,
)

data class SettingsUiState(
    /** The configured server; null before it is read (or while it cannot be read). */
    val serverUrl: HttpUrl? = null,
    val user: UserName = UserName.Loading,
    val serverForm: ServerForm? = null,
    val permissions: PermissionState? = null,
    val distributors: DistributorState? = null,
    val distributorPickerOpen: Boolean = false,
    val capture: CaptureQueueStatus? = null,
    /** "Sync now" was tapped and the upload work is enqueued; cleared when the queue changes. */
    val syncRequested: Boolean = false,
    /** Enqueuing the upload work failed. */
    val syncFailed: Boolean = false,
    val appVersion: AppVersion? = null,
) {
    /** Where the server offers the current APK (`<server>/download/android`, below a path prefix if there is one). */
    val downloadUrl: HttpUrl? get() = serverUrl?.resolve(DOWNLOAD_PATH)

    companion object {
        const val DOWNLOAD_PATH = "download/android"
    }
}

/**
 * The phone's settings: server and signed-in user, the notification state, the capture queue and the app version. No
 * account settings here, those stay in the web app.
 */
@HiltViewModel
class SettingsViewModel
    @Inject
    constructor(
        private val serverUrls: ServerUrlRepository,
        private val authApi: AuthApi,
        private val environment: SettingsEnvironment,
    ) : ViewModel() {
        private val local =
            MutableStateFlow(
                SettingsUiState(
                    permissions = environment.permissions(),
                    distributors = environment.distributors(),
                    appVersion = environment.appVersion,
                ),
            )

        val uiState: StateFlow<SettingsUiState> = local.asStateFlow()

        init {
            loadUser()
            viewModelScope.launch { serverUrls.serverUrl.collect { url -> local.update { it.copy(serverUrl = url) } } }
            viewModelScope.launch {
                environment.captureStatus.collect { status ->
                    local.update {
                        // A change of the queue (a capture sent or failed) answers the "Sync now" tap.
                        val changed = it.capture != null && it.capture != status
                        it.copy(capture = status, syncRequested = it.syncRequested && !changed)
                    }
                }
            }
        }

        fun loadUser() {
            local.update { it.copy(user = UserName.Loading) }
            viewModelScope.launch {
                val user =
                    try {
                        authApi
                            .getCurrentUser()
                            .takeIf { it.isSuccessful }
                            ?.body()
                            ?.username
                            ?.let { UserName.Known(it) } ?: UserName.Unknown
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // Offline or an unreadable answer. Not logged: the exception may carry user data.
                        UserName.Unknown
                    }
                local.update { it.copy(user = user) }
            }
        }

        fun openServerForm() =
            local.update {
                it.copy(
                    serverForm =
                        ServerForm(
                            it.serverUrl
                                ?.toString()
                                .orEmpty(),
                        ),
                )
            }

        fun onServerAddressChange(address: String) =
            local.update { state -> state.copy(serverForm = state.serverForm?.copy(address = address, error = null)) }

        fun closeServerForm() = local.update { it.copy(serverForm = null) }

        /** Stores the address; another origin logs out (the app then shows the login screen), the same one keeps the session. */
        fun saveServer() {
            val form = local.value.serverForm ?: return
            if (form.saving) return
            local.update { it.copy(serverForm = form.copy(saving = true, error = null)) }
            viewModelScope.launch {
                when (val result = serverUrls.setServerUrl(form.address)) {
                    is ServerUrlValidation.Valid -> {
                        local.update { it.copy(serverForm = null) }
                        loadUser()
                    }
                    is ServerUrlValidation.Invalid ->
                        local.update { state -> state.copy(serverForm = state.serverForm?.copy(saving = false, error = result)) }
                }
            }
        }

        /** Back from the system settings or a distributor app: what is allowed may have changed. */
        fun refresh() =
            local.update {
                it.copy(permissions = environment.permissions(), distributors = environment.distributors())
            }

        fun openDistributorPicker() = local.update { it.copy(distributorPickerOpen = true, distributors = environment.distributors()) }

        fun closeDistributorPicker() = local.update { it.copy(distributorPickerOpen = false) }

        fun chooseDistributor(packageName: String) {
            local.update { it.copy(distributorPickerOpen = false) }
            viewModelScope.launch {
                try {
                    environment.useDistributor(packageName)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // The library failed; the state below shows which distributor is really in use.
                }
                refresh()
            }
        }

        fun syncNow() {
            local.update { it.copy(syncRequested = false, syncFailed = false) }
            viewModelScope.launch {
                val ok =
                    try {
                        environment.syncCapturesNow()
                        true
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        false
                    }
                local.update { it.copy(syncRequested = ok, syncFailed = !ok) }
            }
        }
    }
