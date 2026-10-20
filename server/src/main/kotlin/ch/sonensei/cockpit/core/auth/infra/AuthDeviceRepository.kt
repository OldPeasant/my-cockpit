package ch.sonensei.cockpit.core.auth.infra

import ch.sonensei.cockpit.core.auth.domain.AuthDevice
import ch.sonensei.cockpit.core.auth.domain.AuthDeviceRetiredToken
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

interface AuthDeviceRepository : JpaRepository<AuthDevice, UUID> {
	@Query("select d from AuthDevice d join fetch d.user where d.accessTokenHash = :hash")
	fun findByAccessTokenHash(
		@Param("hash") hash: String,
	): AuthDevice?

	/**
	 * Locks the row, so two refreshes with the same token are serialized: the second one no longer finds the token
	 * here (it was rotated) and is treated as a reuse.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select d from AuthDevice d where d.refreshTokenHash = :hash")
	fun findByRefreshTokenHashForUpdate(
		@Param("hash") hash: String,
	): AuthDevice?

	fun findAllByOrderByCreatedAtAsc(): List<AuthDevice>

	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("delete from AuthDevice d where d.id = :id")
	fun deleteDevice(
		@Param("id") id: UUID,
	): Int

	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("delete from AuthDevice d where d.refreshExpiresAt <= :now")
	fun deleteExpired(
		@Param("now") now: Instant,
	): Int
}

interface AuthDeviceRetiredTokenRepository : JpaRepository<AuthDeviceRetiredToken, UUID> {
	@Query("select t.device.id from AuthDeviceRetiredToken t where t.tokenHash = :hash")
	fun findDeviceIdByTokenHash(
		@Param("hash") hash: String,
	): UUID?

	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("delete from AuthDeviceRetiredToken t where t.createdAt <= :before")
	fun deleteCreatedBefore(
		@Param("before") before: Instant,
	): Int
}
