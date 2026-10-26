package ch.sonensei.cockpit.core.push.infra

import ch.sonensei.cockpit.core.push.domain.PushSubscription
import ch.sonensei.cockpit.core.push.domain.VapidKey
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

interface VapidKeyRepository : JpaRepository<VapidKey, UUID> {
	fun findFirstBy(): VapidKey?
}

interface PushSubscriptionRepository : JpaRepository<PushSubscription, UUID> {
	fun findByEndpoint(endpoint: String): PushSubscription?

	/** Deletes by endpoint without a version check; returns the number of deleted rows (0 or 1). */
	@Transactional
	@Modifying(flushAutomatically = true)
	@Query("delete from PushSubscription s where s.endpoint = :endpoint")
	fun deleteByEndpoint(
		@Param("endpoint") endpoint: String,
	): Int
}
