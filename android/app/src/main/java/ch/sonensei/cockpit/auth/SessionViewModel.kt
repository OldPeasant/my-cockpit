package ch.sonensei.cockpit.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Whether the app shows the login screen or the shell, and the logout action. */
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

        init {
            // Any logout while the app runs (the user's, or the server rejecting the refresh token) clears the caches.
            viewModelScope.launch {
                loggedIn.drop(1).filter { !it }.collect { repository.clearCaches() }
            }
        }

        fun logout() {
            viewModelScope.launch { repository.logout() }
        }
    }
