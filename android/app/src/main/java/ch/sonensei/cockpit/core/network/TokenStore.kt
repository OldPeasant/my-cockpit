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
}

/** Keeps the tokens in memory only; the login task replaces it with Keystore-backed storage. */
@Singleton
class InMemoryTokenStore
    @Inject
    constructor() : TokenStore {
        @Volatile
        private var tokens: AuthTokens? = null

        override fun current(): AuthTokens? = tokens

        override fun save(tokens: AuthTokens) {
            this.tokens = tokens
        }

        override fun clear() {
            tokens = null
        }
    }
