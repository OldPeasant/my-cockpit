package ch.sonensei.cockpit.core.push.infra

import ch.sonensei.cockpit.core.push.domain.UnifiedPushRegistration
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface UnifiedPushRegistrationRepository : JpaRepository<UnifiedPushRegistration, UUID> {
	fun findByInstanceId(instanceId: String): UnifiedPushRegistration?

	/** Deletes the registration of an instance; returns the number of deleted rows (0 or 1). */
	@Modifying(flushAutomatically = true)
	@Query("delete from UnifiedPushRegistration r where r.instanceId = :instanceId")
	fun deleteByInstance(
		@Param("instanceId") instanceId: String,
	): Int

	/**
	 * Deletes registration [id] only while it still has [endpoint], so a push that failed for an old endpoint never
	 * removes the endpoint the instance registered since.
	 */
	@Modifying(flushAutomatically = true)
	@Query("delete from UnifiedPushRegistration r where r.id = :id and r.endpoint = :endpoint")
	fun deleteIfEndpoint(
		@Param("id") id: UUID,
		@Param("endpoint") endpoint: String,
	): Int
}
