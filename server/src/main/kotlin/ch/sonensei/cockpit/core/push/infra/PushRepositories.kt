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
	 * Inserts the subscription, or replaces the keys and the login session of the one with this [endpoint] (bumping its
	 * version and `updated_at` only when they change). A single statement, so browsers subscribing the same endpoint at
	 * the same time can't run into the unique constraint. [id] (a UUIDv7) is used only for an insert.
	 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query(
		nativeQuery = true,
		value = """insert into push_subscription (id, created_at, updated_at, version, endpoint, p256dh, auth, session_id)
			values (:id, :now, :now, 0, :endpoint, :p256dh, :auth, :sessionId)
			on conflict (endpoint) do update
			set p256dh = excluded.p256dh, auth = excluded.auth, session_id = excluded.session_id,
				updated_at = excluded.updated_at, version = push_subscription.version + 1
			where (push_subscription.p256dh, push_subscription.auth, push_subscription.session_id)
				is distinct from (excluded.p256dh, excluded.auth, excluded.session_id)""",
	)
	fun upsert(
		@Param("id") id: UUID,
		@Param("endpoint") endpoint: String,
		@Param("p256dh") p256dh: String,
		@Param("auth") auth: String,
		@Param("sessionId") sessionId: UUID,
		@Param("now") now: Instant,
	): Int

	/** The subscriptions whose login session is still valid at [now]. */
	@Query(
		nativeQuery = true,
		value = """select p.* from push_subscription p join auth_session s on s.id = p.session_id
			where s.expires_at > :now order by p.id""",
	)
	fun findWithValidSession(
		@Param("now") now: Instant,
	): List<PushSubscription>

	/**
	 * Deletes the subscriptions whose login session expired by [now] but is not deleted yet (the session itself goes
	 * with the next login or use of its cookie, and takes the remaining subscriptions with it).
	 */
	@Transactional
	@Modifying(flushAutomatically = true)
	@Query(
		nativeQuery = true,
		value = """delete from push_subscription p using auth_session s
			where s.id = p.session_id and s.expires_at <= :now""",
	)
	fun deleteWithExpiredSession(
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
