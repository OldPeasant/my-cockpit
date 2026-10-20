package ch.sonensei.cockpit.core.oauth.infra

import ch.sonensei.cockpit.core.oauth.domain.OAuthAuthorization
import ch.sonensei.cockpit.core.oauth.domain.OAuthConnection
import ch.sonensei.cockpit.core.oauth.domain.OAuthProvider
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

interface OAuthConnectionRepository : JpaRepository<OAuthConnection, UUID> {
	fun findByProviderAndSubject(
		provider: OAuthProvider,
		subject: String,
	): OAuthConnection?

	fun findAllByOrderByCreatedAtAscIdAsc(): List<OAuthConnection>

	/**
	 * Bookkeeping of a successful refresh, without a version bump: tokens refresh in the background, and that must
	 * not invalidate the ETag a client holds. [refreshTokenCiphertext] (already encrypted) replaces the stored refresh
	 * token when the provider rotated it.
	 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query(
		nativeQuery = true,
		value =
			"update oauth_connection set refresh_token = coalesce(cast(:refreshToken as text), refresh_token), " +
				"last_refreshed_at = :now, last_error = null where id = :id and status = 'CONNECTED'",
	)
	fun recordRefresh(
		@Param("id") id: UUID,
		@Param("refreshToken") refreshTokenCiphertext: String?,
		@Param("now") now: Instant,
	): Int

	/** Records why a refresh failed while the connection itself stays usable (no version bump, see [recordRefresh]). */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query(nativeQuery = true, value = "update oauth_connection set last_error = :error where id = :id")
	fun recordError(
		@Param("id") id: UUID,
		@Param("error") error: String,
	): Int
}

interface OAuthAuthorizationRepository : JpaRepository<OAuthAuthorization, UUID> {
	fun findByStateHash(stateHash: String): OAuthAuthorization?

	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("delete from OAuthAuthorization a where a.expiresAt <= :now")
	fun deleteExpired(
		@Param("now") now: Instant,
	): Int
}
