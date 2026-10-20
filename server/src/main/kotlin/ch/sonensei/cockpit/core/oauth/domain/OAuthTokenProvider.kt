package ch.sonensei.cockpit.core.oauth.domain

import java.time.Instant
import java.util.UUID

/**
 * How modules (calendar, mail, contacts) get a valid access token for an OAuth connection. Tokens are refreshed
 * before they expire; a refresh token the provider no longer accepts marks the connection
 * [OAuthConnectionStatus.RECONNECT_REQUIRED].
 *
 * ```
 * val token = tokens.accessToken(account.oauthConnectionId, OAuthResource.MAIL)
 * imap.connect(host, connection.email, token.value)   // XOAUTH2
 * ```
 *
 * @throws OAuthConnectionNotFoundException when the connection does not exist (any more)
 * @throws OAuthReconnectRequiredException when the owner must connect the account again
 * @throws OAuthProviderUnavailableException when the provider could not be reached (retry later)
 * @throws OAuthGrantRejectedException when the provider rejected the refresh for another reason (e.g. app config)
 */
interface OAuthTokenProvider {
	/** An access token for the provider's APIs (Google APIs, Microsoft Graph). */
	fun accessToken(connectionId: UUID): OAuthAccessToken = accessToken(connectionId, OAuthResource.API)

	fun accessToken(
		connectionId: UUID,
		resource: OAuthResource,
	): OAuthAccessToken

	/** The connection's account (email for XOAUTH2, status), or `null` when it does not exist. */
	fun connection(connectionId: UUID): OAuthConnectionInfo?
}

/** A bearer token valid until at least shortly before [expiresAt]. `toString` never shows it. */
class OAuthAccessToken(
	val value: String,
	val expiresAt: Instant,
) {
	override fun toString(): String = "OAuthAccessToken(expiresAt=$expiresAt, ******)"
}

/** What the API and other modules may know about a connection: everything except its tokens. */
data class OAuthConnectionInfo(
	val id: UUID,
	val provider: OAuthProvider,
	val email: String?,
	val displayName: String?,
	val scopes: List<String>,
	val status: OAuthConnectionStatus,
	val lastError: String?,
	val lastRefreshedAt: Instant?,
	val createdAt: Instant,
	val version: Long,
) {
	companion object {
		fun of(connection: OAuthConnection): OAuthConnectionInfo =
			OAuthConnectionInfo(
				id = connection.id,
				provider = connection.provider,
				email = connection.email,
				displayName = connection.displayName,
				scopes = connection.scopeList,
				status = connection.status,
				lastError = connection.lastError,
				lastRefreshedAt = connection.lastRefreshedAt,
				createdAt = checkNotNull(connection.createdAt),
				version = connection.version,
			)
	}
}

/**
 * Published (synchronously, inside the deleting transaction) before a connection is removed, so modules can detach
 * their accounts and sources from it.
 */
data class OAuthConnectionDisconnected(
	val connectionId: UUID,
	val provider: OAuthProvider,
)
