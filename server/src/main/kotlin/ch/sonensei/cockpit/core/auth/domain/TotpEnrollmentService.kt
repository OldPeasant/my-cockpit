package ch.sonensei.cockpit.core.auth.domain

import ch.sonensei.cockpit.core.auth.infra.UserAccountRepository
import ch.sonensei.cockpit.core.crypto.EncryptedSecret
import ch.sonensei.cockpit.core.web.ConflictException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration

/**
 * TOTP re-enrollment after a restore without the TOTP secret (SPEC.md §10.2): the login gives such an account a session
 * that may only call this service. [start] stores a new secret and returns it for the authenticator app; [confirm]
 * checks a code for it and makes the account a normal one again. Only possible while
 * [UserAccount.totpReenrollRequired] is set, so a session cannot replace a working TOTP secret this way. The code check
 * shares the [LoginThrottle] with the login.
 */
@Service
class TotpEnrollmentService(
	private val users: UserAccountRepository,
	private val throttle: LoginThrottle,
	private val clock: Clock,
) {
	private val log = LoggerFactory.getLogger(javaClass)
	private val totp = Totp()

	/** @throws ConflictException (409) when TOTP re-enrollment is not required */
	@Transactional
	fun start(): TotpEnrollment {
		val account = pendingAccount()
		val secret = Base32.encode(Totp.newSecret())
		account.startTotpReenrollment(EncryptedSecret(secret))
		users.saveAndFlush(account)
		log.info("TOTP re-enrollment started")
		return TotpEnrollment(secret, SetupService.otpauthUri(account.username, secret))
	}

	/** @throws ConflictException (409) when TOTP re-enrollment is not required */
	@Transactional
	fun confirm(code: String): TotpEnrollmentResult {
		// Before the throttle: a 409 is no code attempt and must not count as a failed one.
		val account = pendingAccount()
		throttle.tryBeginAttempt()?.let { return TotpEnrollmentResult.Throttled(it) }
		if (!totp.verify(Base32.decode(account.totpSecret.reveal()), code, clock.instant())) {
			log.info("TOTP re-enrollment rejected: invalid code")
			return TotpEnrollmentResult.InvalidCode
		}
		account.confirmTotpReenrollment()
		users.saveAndFlush(account)
		throttle.recordSuccess()
		log.info("TOTP re-enrollment complete")
		return TotpEnrollmentResult.Confirmed
	}

	private fun pendingAccount(): UserAccount =
		users.findFirstBy()?.takeIf { it.isActive && it.totpReenrollRequired }
			?: throw ConflictException("TOTP re-enrollment is not required")
}

/** A new TOTP secret to enroll: shown once, never stored in this form. `toString` hides it. */
class TotpEnrollment(
	val totpSecret: String,
	val otpauthUri: String,
) {
	override fun toString(): String = "TotpEnrollment(******)"
}

sealed interface TotpEnrollmentResult {
	data object Confirmed : TotpEnrollmentResult

	data object InvalidCode : TotpEnrollmentResult

	class Throttled(
		val retryAfter: Duration,
	) : TotpEnrollmentResult
}
