package ch.sonensei.cockpit.core.oauth.domain

import java.time.Duration

/**
 * A token response of the provider. `toString` never shows the tokens.
 * [expiresIn] is the access token lifetime; [refreshToken] is absent when the provider keeps the old one.
 */
class TokenGrant(
	val accessToken: String,
	val expiresIn: Duration,
	val refreshToken: String?,
	val scope: String?,
	val idToken: String?,
) {
	override fun toString(): String = "TokenGrant(expiresIn=$expiresIn, scope=$scope, ******)"
}

/** The identity of the connected account, from the ID token. */
data class OAuthIdentity(
	val subject: String,
	val email: String?,
	val displayName: String?,
)

/** Base class of OAuth failures. Messages contain provider error codes at most, never tokens or response bodies. */
sealed class OAuthException(
	message: String,
	cause: Throwable? = null,
) : RuntimeException(message, cause)

/** The provider answered the token request with an OAuth error ([error] is the RFC 6749 code, e.g. `invalid_grant`). */
class OAuthGrantRejectedException(
	val error: String,
) : OAuthException("The provider rejected the token request: $error")

/** The provider could not be reached or answered with something that is not a valid token response. */
class OAuthProviderUnavailableException(
	message: String,
	cause: Throwable? = null,
) : OAuthException(message, cause)

/** The connection's refresh token is gone or was revoked: the owner has to connect the account again. */
class OAuthReconnectRequiredException(
	message: String = "The OAuth connection has to be reconnected",
) : OAuthException(message)

/** No connection with this id. */
class OAuthConnectionNotFoundException : OAuthException("OAuth connection not found")
