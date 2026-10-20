package ch.sonensei.cockpit.core.auth.infra

import ch.sonensei.cockpit.core.auth.domain.AuthSession
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

interface AuthSessionRepository : JpaRepository<AuthSession, UUID> {
	@Query("select s from AuthSession s join fetch s.user where s.tokenHash = :tokenHash")
	fun findByTokenHash(
		@Param("tokenHash") tokenHash: String,
	): AuthSession?

	/**
	 * Slides the session forward without an optimistic-lock check: parallel requests of the same browser touch the
	 * same session, and the last write winning is correct here.
	 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("update AuthSession s set s.lastSeenAt = :now, s.expiresAt = :expiresAt where s.id = :id")
	fun touch(
		@Param("id") id: UUID,
		@Param("now") now: Instant,
		@Param("expiresAt") expiresAt: Instant,
	): Int

	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("delete from AuthSession s where s.tokenHash = :tokenHash")
	fun deleteByTokenHash(
		@Param("tokenHash") tokenHash: String,
	): Int

	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("delete from AuthSession s where s.expiresAt <= :now")
	fun deleteExpired(
		@Param("now") now: Instant,
	): Int
}
