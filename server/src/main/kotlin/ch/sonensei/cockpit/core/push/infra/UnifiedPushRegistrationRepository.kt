package ch.sonensei.cockpit.core.push.infra

import ch.sonensei.cockpit.core.push.domain.UnifiedPushRegistration
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

interface UnifiedPushRegistrationRepository : JpaRepository<UnifiedPushRegistration, UUID> {
	fun findByInstanceId(instanceId: String): UnifiedPushRegistration?

	/**
	 * Stores or replaces the endpoint and keys of [instanceId] in one statement, so concurrent registrations of the same
	 * instance neither collide on the unique instance id nor fail the optimistic lock. [id] is used only for a new row.
	 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query(
		"""
		insert into push_unifiedpush_registration (id, created_at, updated_at, version, instance_id, endpoint, p256dh, auth)
		values (:id, :now, :now, 0, :instanceId, :endpoint, :p256dh, :auth)
		on conflict (instance_id) do update set
		    endpoint = excluded.endpoint, p256dh = excluded.p256dh, auth = excluded.auth,
		    updated_at = excluded.updated_at, version = push_unifiedpush_registration.version + 1
		""",
		nativeQuery = true,
	)
	fun upsert(
		@Param("id") id: UUID,
		@Param("instanceId") instanceId: String,
		@Param("endpoint") endpoint: String,
		@Param("p256dh") p256dh: String?,
		@Param("auth") auth: String?,
		@Param("now") now: Instant,
	): Int

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
