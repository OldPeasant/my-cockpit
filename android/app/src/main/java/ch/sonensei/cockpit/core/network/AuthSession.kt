package ch.sonensei.cockpit.core.network

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Why the device was logged out. */
enum class LogoutReason {
    /** The server rejected the refresh token (expired, rotated or the device was revoked). */
    REFRESH_REJECTED,

    /** The user logged out. */
    USER,
}

/** Logs the device out: forgets its tokens and tells the UI, which then shows the login screen. */
@Singleton
class AuthSession
    @Inject
    constructor(
        private val tokenStore: TokenStore,
    ) {
        private val logouts =
            MutableSharedFlow<LogoutReason>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

        /** Emits once per logout; collectors that are not subscribed at that moment miss it. */
        val loggedOut: SharedFlow<LogoutReason> = logouts.asSharedFlow()

        val isLoggedIn: Boolean get() = tokenStore.current() != null

        fun logout(reason: LogoutReason) {
            tokenStore.clear()
            logouts.tryEmit(reason)
        }
    }
