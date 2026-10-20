package ch.sonensei.cockpit.core.auth.domain

import ch.sonensei.cockpit.core.auth.infra.UserAccountRepository
import ch.sonensei.cockpit.core.web.ConflictException
import org.slf4j.LoggerFactory
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration

/**
 * Regenerates the recovery codes of the logged-in owner (SPEC.md §4.1). It needs a current TOTP code, so a stolen
 * session alone cannot replace them; the TOTP check shares the [LoginThrottle] with the login, so it cannot be
 * brute-forced either. All old codes, used or not, stop working.
 */
@Service
class RecoveryCodeService(
	private val users: UserAccountRepository,
	private val passwordEncoder: PasswordEncoder,
	private val throttle: LoginThrottle,
	private val clock: Clock,
) {
	private val log = LoggerFactory.getLogger(javaClass)
	private val totp = Totp()

	@Transactional
	fun regenerate(totpCode: String): RecoveryCodeResult {
		throttle.tryBeginAttempt()?.let { return RecoveryCodeResult.Throttled(it) }
		val account = users.findFirstBy()?.takeIf { it.isActive } ?: throw ConflictException("Setup is not complete")
		if (!totp.verify(Base32.decode(account.totpSecret.reveal()), totpCode, clock.instant())) {
			log.info("Recovery code regeneration rejected: invalid TOTP code")
			return RecoveryCodeResult.InvalidTotp
		}
		val codes = RecoveryCodes.generate()
		account.recoveryCodes.clear()
		codes.forEach { account.addRecoveryCode(passwordEncoder.encode(RecoveryCodes.normalize(it))!!) }
		users.saveAndFlush(account)
		throttle.recordSuccess()
		log.info("Recovery codes regenerated")
		return RecoveryCodeResult.Regenerated(codes)
	}
}

sealed interface RecoveryCodeResult {
	/** The new codes, shown exactly once. `toString` hides them. */
	class Regenerated(
		val codes: List<String>,
	) : RecoveryCodeResult {
		override fun toString(): String = "Regenerated(******)"
	}

	data object InvalidTotp : RecoveryCodeResult

	class Throttled(
		val retryAfter: Duration,
	) : RecoveryCodeResult
}
