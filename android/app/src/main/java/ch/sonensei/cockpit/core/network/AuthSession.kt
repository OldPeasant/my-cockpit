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

    /** The user pointed the app at another server; tokens are only ever sent to the server that issued them. */
    SERVER_CHANGED,
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

        /**
         * Held by [TokenAuthenticator] while a refresh runs. A server change logs out under it, so a refresh never
         * sends the refresh token to a server that was configured after the refresh started.
         */
        internal val refreshLock = Any()

        val isLoggedIn: Boolean get() = tokenStore.current() != null

        fun logout(reason: LogoutReason) {
            tokenStore.clear()
            logouts.tryEmit(reason)
        }

        /** Logs out only if the stored tokens are still [tokens]; returns whether it did. */
        fun logoutIfCurrent(
            tokens: AuthTokens,
            reason: LogoutReason,
        ): Boolean {
            if (!tokenStore.replace(tokens, null)) return false
            logouts.tryEmit(reason)
            return true
        }
    }
