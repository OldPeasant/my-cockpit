package ch.sonensei.cockpit.core.network

import javax.inject.Inject
import javax.inject.Singleton

/** The device's current token pair (SPEC.md §3.3: short-lived access token, rotating refresh token). */
data class AuthTokens(
    val accessToken: String,
    val refreshToken: String,
) {
    override fun toString(): String = "AuthTokens(<redacted>)"
}

/**
 * Holds the tokens of the logged-in device. Called from OkHttp threads, so implementations must be thread-safe and
 * fast (no network).
 */
interface TokenStore {
    fun current(): AuthTokens?

    fun save(tokens: AuthTokens)

    fun clear()

    /**
     * Replaces the tokens with [new] (null clears them) only if they still equal [expected], atomically. Returns
     * whether it did, so a slow refresh cannot undo a logout or a login that happened meanwhile.
     */
    fun replace(
        expected: AuthTokens,
        new: AuthTokens?,
    ): Boolean
}

/** Keeps the tokens in memory only, for tests; the app uses the Keystore-backed `auth.PersistentTokenStore`. */
@Singleton
class InMemoryTokenStore
    @Inject
    constructor() : TokenStore {
        @Volatile
        private var tokens: AuthTokens? = null

        override fun current(): AuthTokens? = tokens

        @Synchronized
        override fun save(tokens: AuthTokens) {
            this.tokens = tokens
        }

        @Synchronized
        override fun clear() {
            tokens = null
        }

        @Synchronized
        override fun replace(
            expected: AuthTokens,
            new: AuthTokens?,
        ): Boolean {
            if (tokens != expected) return false
            tokens = new
            return true
        }
    }
