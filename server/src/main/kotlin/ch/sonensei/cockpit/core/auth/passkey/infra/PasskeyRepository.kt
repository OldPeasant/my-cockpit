package ch.sonensei.cockpit.core.auth.passkey.infra

import ch.sonensei.cockpit.core.auth.passkey.domain.Passkey
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface PasskeyRepository : JpaRepository<Passkey, UUID> {
	@Query("select p from Passkey p join fetch p.user where p.credentialId = :credentialId")
	fun findByCredentialId(
		@Param("credentialId") credentialId: ByteArray,
	): Passkey?

	fun existsByCredentialId(credentialId: ByteArray): Boolean

	fun findAllByOrderByCreatedAtAsc(): List<Passkey>

	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("delete from Passkey p where p.id = :id")
	fun deletePasskey(
		@Param("id") id: UUID,
	): Int
}
