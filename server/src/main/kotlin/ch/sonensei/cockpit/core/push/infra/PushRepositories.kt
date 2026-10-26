package ch.sonensei.cockpit.core.push.infra

import ch.sonensei.cockpit.core.push.domain.PushSubscription
import ch.sonensei.cockpit.core.push.domain.VapidKey
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

interface VapidKeyRepository : JpaRepository<VapidKey, UUID> {
	fun findFirstBy(): VapidKey?
}

interface PushSubscriptionRepository : JpaRepository<PushSubscription, UUID> {
	fun findByEndpoint(endpoint: String): PushSubscription?

	/**
	 * Inserts the subscription, or replaces the keys of the one with this [endpoint] (bumping its version and
	 * `updated_at` only when they change). A single statement, so browsers subscribing the same endpoint at the same
	 * time can't run into the unique constraint. [id] (a UUIDv7) is used only for an insert.
	 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query(
		nativeQuery = true,
		value = """insert into push_subscription (id, created_at, updated_at, version, endpoint, p256dh, auth)
			values (:id, :now, :now, 0, :endpoint, :p256dh, :auth)
			on conflict (endpoint) do update
			set p256dh = excluded.p256dh, auth = excluded.auth, updated_at = excluded.updated_at,
				version = push_subscription.version + 1
			where (push_subscription.p256dh, push_subscription.auth) is distinct from (excluded.p256dh, excluded.auth)""",
	)
	fun upsert(
		@Param("id") id: UUID,
		@Param("endpoint") endpoint: String,
		@Param("p256dh") p256dh: String,
		@Param("auth") auth: String,
		@Param("now") now: Instant,
	): Int

	/** Deletes by endpoint without a version check; returns the number of deleted rows (0 or 1). */
	@Transactional
	@Modifying(flushAutomatically = true)
	@Query("delete from PushSubscription s where s.endpoint = :endpoint")
	fun deleteByEndpoint(
		@Param("endpoint") endpoint: String,
	): Int
}
