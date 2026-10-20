package ch.sonensei.cockpit.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Whether the app shows the login screen or the shell, and the logout action. Caches are the repository's job. */
@HiltViewModel
class SessionViewModel
    @Inject
    constructor(
        tokenStore: PersistentTokenStore,
        private val repository: AuthRepository,
    ) : ViewModel() {
        val loggedIn: StateFlow<Boolean> =
            tokenStore.session
                .map { it != null }
                .stateIn(viewModelScope, SharingStarted.Eagerly, tokenStore.session.value != null)

        private var logoutJob: Job? = null

        /** Starts the logout; taps while one runs (the revocation may take a few seconds) are ignored. */
        fun logout() {
            if (logoutJob?.isActive == true) return
            logoutJob = viewModelScope.launch { repository.logout() }
        }
    }
