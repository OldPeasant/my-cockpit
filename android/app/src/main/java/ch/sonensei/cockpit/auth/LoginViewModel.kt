package ch.sonensei.cockpit.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.sonensei.cockpit.core.network.ServerUrlValidation
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class LoginError {
    SERVER_URL_MALFORMED,
    SERVER_URL_HTTPS_REQUIRED,
    INVALID_REQUEST,
    WRONG_CREDENTIALS,
    TOO_MANY_ATTEMPTS,
    NETWORK,
    SERVER,
}

data class LoginUiState(
    val serverUrl: String = "",
    val username: String = "",
    val password: String = "",
    val code: String = "",
    val useRecoveryCode: Boolean = false,
    val submitting: Boolean = false,
    val error: LoginError? = null,
    /** Seconds until the server accepts the next attempt after a 429; 0 when there is no wait. */
    val retryAfterSeconds: Long = 0,
) {
    val canSubmit: Boolean
        get() =
            !submitting &&
                retryAfterSeconds == 0L &&
                listOf(serverUrl, username, password, code).none { it.isBlank() }

    override fun toString(): String =
        "LoginUiState(serverUrl=$serverUrl, username=$username, useRecoveryCode=$useRecoveryCode, " +
            "submitting=$submitting, error=$error, retryAfterSeconds=$retryAfterSeconds)"
}

/** State of the login form; a successful login stores the tokens, which switches the app to the shell. */
@HiltViewModel
class LoginViewModel
    @Inject
    constructor(
        private val repository: AuthRepository,
    ) : ViewModel() {
        private val state = MutableStateFlow(LoginUiState())
        val uiState: StateFlow<LoginUiState> = state.asStateFlow()

        private var countdown: Job? = null

        init {
            viewModelScope.launch {
                val configured = repository.serverUrl.first() ?: return@launch
                state.update { if (it.serverUrl.isEmpty()) it.copy(serverUrl = configured) else it }
            }
        }

        fun onServerUrlChange(value: String) = state.update { it.copy(serverUrl = value, error = it.errorAfterEdit()) }

        fun onUsernameChange(value: String) = state.update { it.copy(username = value, error = it.errorAfterEdit()) }

        fun onPasswordChange(value: String) = state.update { it.copy(password = value, error = it.errorAfterEdit()) }

        fun onCodeChange(value: String) = state.update { it.copy(code = value, error = it.errorAfterEdit()) }

        fun onUseRecoveryCodeChange(value: Boolean) =
            state.update { it.copy(useRecoveryCode = value, code = "", error = it.errorAfterEdit()) }

        fun submit() {
            val form = state.value
            if (!form.canSubmit) return
            state.update { it.copy(submitting = true, error = null) }
            viewModelScope.launch {
                val factor = if (form.useRecoveryCode) SecondFactor.RecoveryCode(form.code) else SecondFactor.Totp(form.code)
                val result = repository.login(form.serverUrl, form.username, form.password, factor)
                state.update { it.after(result) }
                if (result is LoginResult.TooManyAttempts) startCountdown(result.retryAfterSeconds)
            }
        }

        private fun LoginUiState.after(result: LoginResult): LoginUiState =
            when (result) {
                LoginResult.Success -> copy(submitting = false, password = "", code = "")
                is LoginResult.InvalidServerUrl ->
                    copy(
                        submitting = false,
                        error =
                            when (result.reason) {
                                ServerUrlValidation.Reason.MALFORMED -> LoginError.SERVER_URL_MALFORMED
                                ServerUrlValidation.Reason.HTTPS_REQUIRED -> LoginError.SERVER_URL_HTTPS_REQUIRED
                            },
                    )
                LoginResult.InvalidRequest -> copy(submitting = false, error = LoginError.INVALID_REQUEST)
                // A code is only valid briefly (and a recovery code once), so the user enters a fresh one.
                LoginResult.WrongCredentials -> copy(submitting = false, code = "", error = LoginError.WRONG_CREDENTIALS)
                is LoginResult.TooManyAttempts ->
                    copy(submitting = false, code = "", error = LoginError.TOO_MANY_ATTEMPTS, retryAfterSeconds = result.retryAfterSeconds)
                LoginResult.Network -> copy(submitting = false, error = LoginError.NETWORK)
                LoginResult.ServerError -> copy(submitting = false, error = LoginError.SERVER)
            }

        private fun startCountdown(seconds: Long) {
            countdown?.cancel()
            countdown =
                viewModelScope.launch {
                    var remaining = seconds
                    while (remaining > 0) {
                        delay(1_000)
                        remaining--
                        state.update {
                            it.copy(
                                retryAfterSeconds = remaining,
                                error = if (remaining == 0L && it.error == LoginError.TOO_MANY_ATTEMPTS) null else it.error,
                            )
                        }
                    }
                }
        }

        /** Editing clears an error about the form, but the wait after too many attempts stays visible. */
        private fun LoginUiState.errorAfterEdit(): LoginError? = error?.takeIf { it == LoginError.TOO_MANY_ATTEMPTS }
    }
