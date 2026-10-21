package ch.sonensei.cockpit.core.auth.infra

import ch.sonensei.cockpit.core.auth.domain.UserAccount
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import java.util.UUID

/** The table holds at most one row (single-user application, enforced by a unique index). */
interface UserAccountRepository : JpaRepository<UserAccount, UUID> {
	fun findFirstBy(): UserAccount?

	/**
	 * Like [findFirstBy], but locks the row until the transaction ends. The login and the TOTP re-enrollment use it, so
	 * a login that reads [UserAccount.totpReenrollRequired] and writes its session cannot interleave with a re-enrollment
	 * that changes the flag and ends the other sessions (SPEC.md §10.2).
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select u from UserAccount u")
	fun findFirstForUpdate(): UserAccount?
}
