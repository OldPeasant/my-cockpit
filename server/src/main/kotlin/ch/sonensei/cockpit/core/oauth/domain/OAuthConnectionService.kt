package ch.sonensei.cockpit.core.oauth.domain

import ch.sonensei.cockpit.core.crypto.EncryptedSecret
import ch.sonensei.cockpit.core.oauth.infra.OAuthAuthorizationRepository
import ch.sonensei.cockpit.core.oauth.infra.OAuthConnectionRepository
import ch.sonensei.cockpit.core.oauth.infra.OAuthHttpClient
import ch.sonensei.cockpit.core.oauth.infra.OAuthProperties
import ch.sonensei.cockpit.core.web.ConflictException
import ch.sonensei.cockpit.core.web.ETags
import ch.sonensei.cockpit.core.web.NotFoundException
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.util.UriComponentsBuilder
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.HexFormat
import java.util.UUID

/**
 * Connects provider accounts with the authorization-code flow and PKCE (RFC 6749 §4.1, RFC 7636 S256), lists and
 * disconnects them (SPEC.md §7.1).
 *
 * [start] stores a pending [OAuthAuthorization] bound to the browser session and returns the provider's consent URL;
 * [complete] accepts the callback only with the state of a pending flow of the same session, exchanges the code and
 * creates or updates the one [OAuthConnection] of that provider account.
 */
@Service
class OAuthConnectionService(
	private val properties: OAuthProperties,
	private val connections: OAuthConnectionRepository,
	private val authorizations: OAuthAuthorizationRepository,
	private val http: OAuthHttpClient,
	private val tokens: OAuthTokenService,
	private val events: ApplicationEventPublisher,
	private val transactions: TransactionTemplate,
	private val clock: Clock,
) {
	private val log = LoggerFactory.getLogger(javaClass)
	private val random = SecureRandom()

	fun isConfigured(provider: OAuthProvider): Boolean = properties.registration(provider).configured

	/**
	 * Begins a connect flow for the browser session [sessionToken] and returns the URL to send the browser to.
	 *
	 * @throws ConflictException (409) when the provider's OAuth app is not configured on this server
	 */
	fun start(
		provider: OAuthProvider,
		purpose: OAuthPurpose,
		sessionToken: String,
	): URI {
		val registration = properties.registration(provider)
		if (!registration.configured) throw ConflictException("${provider.label} is not configured on this server")
		val state = randomToken(STATE_BYTES)
		val verifier = randomToken(VERIFIER_BYTES)
		val now = clock.instant()
		transactions.executeWithoutResult {
			authorizations.deleteExpired(now)
			authorizations.save(
				OAuthAuthorization(provider, purpose, sha256(state), sessionBinding(sessionToken), EncryptedSecret(verifier), now.plus(FLOW_LIFETIME)),
			)
		}
		val builder =
			UriComponentsBuilder
				.fromUriString(registration.authorizationUri)
				.queryParam("response_type", "code")
				.queryParam("client_id", registration.clientId)
				.queryParam("redirect_uri", properties.redirectUri(provider))
				.queryParam("scope", OAuthScopes.authorization(provider))
				.queryParam("state", state)
				.queryParam("code_challenge", codeChallenge(verifier))
				.queryParam("code_challenge_method", "S256")
		OAuthScopes.extraAuthorizationParameters(provider).forEach { (key, value) -> builder.queryParam(key, value) }
		return builder.encode().build().toUri()
	}

	/**
	 * Handles the provider's redirect. Never throws for a failed flow: the result tells the browser where to go.
	 * The pending flow is consumed by the first callback that names its state, whatever the outcome.
	 */
	fun complete(
		provider: OAuthProvider,
		state: String?,
		code: String?,
		error: String?,
		sessionToken: String?,
	): OAuthCallbackResult {
		val now = clock.instant()
		val pending =
			state?.takeIf { it.isNotEmpty() }?.let { value ->
				transactions.execute {
					authorizations.findByStateHash(sha256(value))?.also { authorizations.delete(it) }
				}
			}
		if (pending == null ||
			pending.provider != provider ||
			pending.isExpiredAt(now) ||
			sessionToken == null ||
			!MessageDigest.isEqual(pending.sessionBinding.toByteArray(), sessionBinding(sessionToken).toByteArray())
		) {
			log.warn("Rejected an OAuth callback for {}: unknown, expired or foreign state", provider)
			return OAuthCallbackResult.Failed(provider, OAuthCallbackError.INVALID_STATE, pending?.purpose)
		}
		if (!error.isNullOrEmpty() || code.isNullOrEmpty()) {
			log.info("The {} authorization was not granted ({})", provider, error?.takeIf { ERROR_CODE.matches(it) } ?: "no code")
			return OAuthCallbackResult.Failed(provider, OAuthCallbackError.ACCESS_DENIED, pending.purpose)
		}
		val grant: TokenGrant
		val identity: OAuthIdentity
		try {
			grant = http.exchangeCode(provider, code, pending.codeVerifier.reveal(), OAuthScopes.tokenRequest(provider, OAuthResource.API))
			identity = http.identity(grant.idToken)
		} catch (e: OAuthException) {
			log.warn("Exchanging the {} authorization code failed: {}", provider, e.message)
			return OAuthCallbackResult.Failed(provider, OAuthCallbackError.TOKEN_EXCHANGE_FAILED, pending.purpose)
		}
		// A reconnect waits for a refresh of the old token chain, so that refresh cannot overwrite the new grant.
		val existingId = transactions.execute { connections.findByProviderAndSubject(provider, identity.subject)?.id }
		val connectionId =
			if (existingId == null) {
				store(provider, identity, grant, now)
			} else {
				tokens.exclusive(existingId) { store(provider, identity, grant, now) }
			}
		if (connectionId == null) {
			log.warn("The {} token response has no refresh token", provider)
			return OAuthCallbackResult.Failed(provider, OAuthCallbackError.NO_REFRESH_TOKEN, pending.purpose)
		}
		log.info("Connected {} account as OAuth connection {}", provider, connectionId)
		return OAuthCallbackResult.Connected(provider, connectionId, pending.purpose)
	}

	/**
	 * Creates or updates the connection of [identity] with [grant] and caches its access token. Returns null when there
	 * is no refresh token, neither in the grant nor stored.
	 */
	private fun store(
		provider: OAuthProvider,
		identity: OAuthIdentity,
		grant: TokenGrant,
		now: Instant,
	): UUID? {
		val connectionId =
			transactions.execute {
				val existing = connections.findByProviderAndSubject(provider, identity.subject)
				val refreshToken = grant.refreshToken?.let(::EncryptedSecret) ?: existing?.refreshToken
				if (refreshToken == null) return@execute null
				val connection =
					existing ?: OAuthConnection(provider, identity.subject, identity.email, identity.displayName, "", refreshToken)
				connection.email = identity.email ?: connection.email
				connection.displayName = identity.displayName ?: connection.displayName
				connection.scopes = grant.scope ?: OAuthScopes.authorization(provider)
				connection.markConnected(refreshToken, now)
				connections.save(connection).id
			} ?: return null
		tokens.forget(connectionId)
		tokens.remember(connectionId, OAuthResource.API, grant)
		return connectionId
	}

	fun list(): List<OAuthConnectionInfo> =
		transactions.execute {
			connections.findAllByOrderByCreatedAtAscIdAsc().map(OAuthConnectionInfo::of)
		}!!

	/** @throws NotFoundException (404) */
	fun get(id: UUID): OAuthConnectionInfo =
		transactions.execute { connections.findById(id).orElse(null)?.let(OAuthConnectionInfo::of) }
			?: throw NotFoundException("OAuth connection not found")

	/**
	 * Removes the connection after [OAuthConnectionDisconnected] was handled, then revokes its refresh token at the
	 * provider (best effort; Microsoft has no revocation endpoint, the owner removes the app in the account settings).
	 *
	 * @throws NotFoundException (404), PreconditionRequiredException (428), PreconditionFailedException (412)
	 */
	fun disconnect(
		id: UUID,
		ifMatch: String?,
	) {
		val (provider, refreshToken) =
			tokens.exclusive(id) {
				transactions
					.execute {
						val connection = connections.findById(id).orElse(null) ?: throw NotFoundException("OAuth connection not found")
						ETags.checkIfMatch(ifMatch, connection.version)
						events.publishEvent(OAuthConnectionDisconnected(id, connection.provider))
						connections.delete(connection)
						connection.provider to connection.refreshToken?.reveal()
					}.also { tokens.forget(id) }
			}
		log.info("Disconnected OAuth connection {}", id)
		if (refreshToken != null) http.revoke(provider, refreshToken)
	}

	private fun randomToken(bytes: Int): String = BASE64URL.encodeToString(ByteArray(bytes).also(random::nextBytes))

	private fun codeChallenge(verifier: String): String = BASE64URL.encodeToString(digest(verifier))

	/** Binds a flow to the browser session without storing the session token or its lookup hash. */
	private fun sessionBinding(sessionToken: String): String = sha256("oauth-session-binding:$sessionToken")

	private fun sha256(value: String): String = HexFormat.of().formatHex(digest(value))

	private fun digest(value: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.US_ASCII))

	companion object {
		/** How long the owner has for the consent screen. */
		val FLOW_LIFETIME: Duration = Duration.ofMinutes(10)

		private const val STATE_BYTES = 32
		private const val VERIFIER_BYTES = 48
		private val BASE64URL: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
		private val ERROR_CODE = Regex("[a-z_]{1,64}")
	}
}

/** Why a callback did not produce a connection; the value is passed to the SPA as `error=`. */
enum class OAuthCallbackError(
	val code: String,
) {
	INVALID_STATE("invalid_state"),
	ACCESS_DENIED("access_denied"),
	TOKEN_EXCHANGE_FAILED("token_exchange_failed"),
	NO_REFRESH_TOKEN("no_refresh_token"),
}

sealed interface OAuthCallbackResult {
	val provider: OAuthProvider
	val purpose: OAuthPurpose?

	data class Connected(
		override val provider: OAuthProvider,
		val connectionId: UUID,
		override val purpose: OAuthPurpose,
	) : OAuthCallbackResult

	data class Failed(
		override val provider: OAuthProvider,
		val error: OAuthCallbackError,
		override val purpose: OAuthPurpose?,
	) : OAuthCallbackResult
}

private val OAuthProvider.label: String
	get() =
		when (this) {
			OAuthProvider.GOOGLE -> "Google"
			OAuthProvider.MICROSOFT -> "Microsoft"
		}
