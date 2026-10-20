package ch.sonensei.cockpit.core.oauth.domain

import ch.sonensei.cockpit.core.crypto.SecretCipher
import ch.sonensei.cockpit.core.oauth.infra.OAuthConnectionRepository
import ch.sonensei.cockpit.core.oauth.infra.OAuthHttpClient
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * [OAuthTokenProvider] with an in-memory access-token cache per connection and [OAuthResource]. A cached token is
 * refreshed once it is within [REFRESH_MARGIN] (at most half its lifetime) of expiring; refreshes of one connection
 * are serialized, because Microsoft rotates the refresh token on every use. Access tokens are never persisted.
 */
@Service
class OAuthTokenService(
	private val connections: OAuthConnectionRepository,
	private val http: OAuthHttpClient,
	private val cipher: SecretCipher,
	private val transactions: TransactionTemplate,
	private val clock: Clock,
) : OAuthTokenProvider {
	private val log = LoggerFactory.getLogger(javaClass)
	private val cache = ConcurrentHashMap<Pair<UUID, OAuthResource>, CachedToken>()
	private val locks = ConcurrentHashMap<UUID, ReentrantLock>()

	override fun accessToken(
		connectionId: UUID,
		resource: OAuthResource,
	): OAuthAccessToken {
		fresh(connectionId, resource)?.let { return it }
		return exclusive(connectionId) { fresh(connectionId, resource) ?: refresh(connectionId, resource) }
	}

	override fun connection(connectionId: UUID): OAuthConnectionInfo? =
		transactions.execute { connections.findById(connectionId).orElse(null)?.let(OAuthConnectionInfo::of) }

	/**
	 * Runs [block] while no refresh of [connectionId] is in flight. Disconnect and reconnect change the stored refresh
	 * token and the cache under this lock, so a refresh can neither re-cache a token of a deleted connection nor
	 * overwrite the refresh token of a newer grant.
	 */
	internal fun <T> exclusive(
		connectionId: UUID,
		block: () -> T,
	): T = locks.computeIfAbsent(connectionId) { ReentrantLock() }.withLock(block)

	/** Caches the access token of a fresh grant (the callback's code exchange). */
	internal fun remember(
		connectionId: UUID,
		resource: OAuthResource,
		grant: TokenGrant,
	) {
		cache[connectionId to resource] = CachedToken.of(grant, clock.instant())
	}

	/** Drops the cached tokens of a connection (disconnected, reconnected, or its refresh token was rejected). */
	internal fun forget(connectionId: UUID) {
		cache.keys.removeIf { it.first == connectionId }
	}

	private fun fresh(
		connectionId: UUID,
		resource: OAuthResource,
	): OAuthAccessToken? = cache[connectionId to resource]?.takeIf { clock.instant().isBefore(it.refreshAt) }?.token

	private fun refresh(
		connectionId: UUID,
		resource: OAuthResource,
	): OAuthAccessToken {
		val (provider, refreshToken) =
			transactions.execute {
				val connection = connections.findById(connectionId).orElseThrow { OAuthConnectionNotFoundException() }
				val token = connection.refreshToken
				if (connection.status != OAuthConnectionStatus.CONNECTED || token == null) throw OAuthReconnectRequiredException()
				connection.provider to token.reveal()
			}!!
		val grant =
			try {
				http.refresh(provider, refreshToken, OAuthScopes.tokenRequest(provider, resource))
			} catch (e: OAuthGrantRejectedException) {
				throw onRejected(connectionId, e)
			}
		val now = clock.instant()
		val rotated = grant.refreshToken?.takeIf { it != refreshToken }?.let(cipher::encrypt)
		transactions.executeWithoutResult { connections.recordRefresh(connectionId, rotated, now) }
		log.debug("Refreshed the {} access token of OAuth connection {} for {}", provider, connectionId, resource)
		return CachedToken.of(grant, now).also { cache[connectionId to resource] = it }.token
	}

	/** Records a rejected refresh and returns the exception for the caller. */
	private fun onRejected(
		connectionId: UUID,
		e: OAuthGrantRejectedException,
	): OAuthException {
		if (e.error == INVALID_GRANT) {
			forget(connectionId)
			transactions.executeWithoutResult {
				connections.findById(connectionId).ifPresent { it.markReconnectRequired(INVALID_GRANT) }
			}
			log.warn("The refresh token of OAuth connection {} was rejected; the account has to be reconnected", connectionId)
			return OAuthReconnectRequiredException()
		}
		transactions.executeWithoutResult { connections.recordError(connectionId, e.error) }
		log.warn("Refreshing OAuth connection {} failed: {}", connectionId, e.error)
		return e
	}

	private class CachedToken(
		val token: OAuthAccessToken,
		val refreshAt: Instant,
	) {
		companion object {
			fun of(
				grant: TokenGrant,
				now: Instant,
			): CachedToken {
				val expiresAt = now.plus(grant.expiresIn)
				val margin = minOf(REFRESH_MARGIN, grant.expiresIn.dividedBy(2))
				return CachedToken(OAuthAccessToken(grant.accessToken, expiresAt), expiresAt.minus(margin))
			}
		}
	}

	companion object {
		/** How long before expiry a cached access token is replaced. */
		val REFRESH_MARGIN: Duration = Duration.ofMinutes(5)

		private const val INVALID_GRANT = "invalid_grant"
	}
}
