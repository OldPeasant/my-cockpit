package ch.sonensei.cockpit.core.auth.infra

import ch.sonensei.cockpit.core.auth.domain.UserAccount
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

/** The table holds at most one row (single-user application, enforced by a unique index). */
interface UserAccountRepository : JpaRepository<UserAccount, UUID> {
	fun findFirstBy(): UserAccount?
}
