package ch.sonensei.cockpit.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.sonensei.cockpit.core.network.generated.models.PasskeyResponse
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class PasskeyError {
    WRONG_CODE,
    TOO_MANY_ATTEMPTS,
    ALREADY_REGISTERED,
    UNAVAILABLE,
    FAILED,
    NETWORK,
    SERVER,
}

/** The form for adding a passkey: its name and the current authenticator code the server asks for. */
data class PasskeyForm(
    val name: String = "",
    val code: String = "",
    val submitting: Boolean = false,
    val error: PasskeyError? = null,
) {
    val canSubmit: Boolean
        get() = !submitting && name.isNotBlank() && name.trim().length <= NAME_MAX && code.isNotBlank()

    override fun toString(): String = "PasskeyForm(name=$name, submitting=$submitting, error=$error)"

    companion object {
        /** The server rejects longer names (`PasskeyRegistrationRequest.name` maxLength), but only after the passkey exists. */
        const val NAME_MAX = 100
    }
}

data class PasskeySettingsUiState(
    /** The user's passkeys, oldest first; null while loading or when they could not be loaded ([loadFailed]). */
    val passkeys: List<PasskeyResponse>? = null,
    val loadFailed: Boolean = false,
    /** The open add form; null when it is closed. */
    val form: PasskeyForm? = null,
    /** The name of the passkey added last, for a confirmation. */
    val added: String? = null,
)

/** The passkey section of the settings: lists the user's passkeys and adds one through Credential Manager. */
@HiltViewModel
class PasskeySettingsViewModel
    @Inject
    constructor(
        private val repository: PasskeyRepository,
    ) : ViewModel() {
        private val state = MutableStateFlow(PasskeySettingsUiState())
        val uiState: StateFlow<PasskeySettingsUiState> = state.asStateFlow()

        /** The running list request: a newer load or a registration replaces its (then stale) answer. */
        private var loadJob: Job? = null

        init {
            load()
        }

        fun load() {
            loadJob?.cancel()
            state.update { it.copy(loadFailed = false) }
            loadJob =
                viewModelScope.launch {
                    val passkeys = repository.list()
                    state.update { it.copy(passkeys = passkeys ?: it.passkeys, loadFailed = passkeys == null) }
                }
        }

        fun openForm() = state.update { it.copy(form = PasskeyForm(name = deviceName()), added = null) }

        fun closeForm() = state.update { if (it.form?.submitting == true) it else it.copy(form = null) }

        fun onNameChange(value: String) = updateForm { it.copy(name = value.take(PasskeyForm.NAME_MAX), error = null) }

        fun onCodeChange(value: String) = updateForm { it.copy(code = value, error = null) }

        /** Asks the server for options, lets [credentials] create the passkey, then registers it. */
        fun submit(credentials: PasskeyCredentials) {
            val form = state.value.form ?: return
            if (!form.canSubmit) return
            updateForm { it.copy(submitting = true, error = null) }
            viewModelScope.launch {
                when (val result = repository.register(form.name, form.code, credentials)) {
                    is PasskeyRegistrationResult.Success -> {
                        // A list still loading may have been read before this passkey existed: ask again instead.
                        val stale = loadJob?.isActive == true
                        loadJob?.cancel()
                        state.update {
                            it.copy(form = null, added = result.passkey.name, passkeys = (it.passkeys ?: emptyList()) + result.passkey)
                        }
                        if (stale) load()
                    }
                    // The code may still be valid: the user can simply try again.
                    PasskeyRegistrationResult.Cancelled -> updateForm { it.copy(submitting = false) }
                    else -> updateForm { it.copy(submitting = false, code = "", error = result.error()) }
                }
            }
        }

        private fun updateForm(change: (PasskeyForm) -> PasskeyForm) = state.update { s -> s.form?.let { s.copy(form = change(it)) } ?: s }

        private fun PasskeyRegistrationResult.error(): PasskeyError =
            when (this) {
                PasskeyRegistrationResult.WrongCode -> PasskeyError.WRONG_CODE
                is PasskeyRegistrationResult.TooManyAttempts -> PasskeyError.TOO_MANY_ATTEMPTS
                PasskeyRegistrationResult.AlreadyRegistered -> PasskeyError.ALREADY_REGISTERED
                PasskeyRegistrationResult.Unavailable -> PasskeyError.UNAVAILABLE
                PasskeyRegistrationResult.Network -> PasskeyError.NETWORK
                PasskeyRegistrationResult.ServerError -> PasskeyError.SERVER
                PasskeyRegistrationResult.Failed, PasskeyRegistrationResult.Cancelled, is PasskeyRegistrationResult.Success ->
                    PasskeyError.FAILED
            }
    }
