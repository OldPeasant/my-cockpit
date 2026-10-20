package ch.sonensei.cockpit.core.auth.domain

import ch.sonensei.cockpit.core.auth.infra.UserAccountRepository
import ch.sonensei.cockpit.core.crypto.EncryptedSecret
import ch.sonensei.cockpit.core.web.ConflictException
import ch.sonensei.cockpit.core.web.InvalidRequestException
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.net.URLEncoder
import java.time.Clock
import java.time.Duration
import java.time.temporal.ChronoUnit

/**
 * First-run setup (SPEC.md §4.1), only available while no active account exists:
 * [start] creates the account with a new TOTP secret and recovery codes, [confirmTotp] activates it once the user
 * proves the authenticator app works.
 *
 * Starting again before confirming replaces the pending account only if the request repeats the pending username and
 * password (the owner lost the QR code and begins anew), or if the pending account was created more than
 * [ABANDONED_AFTER] ago by [clock] (an abandoned setup, e.g. the owner forgot the password). Otherwise [start] fails
 * with a conflict that does not say which check failed, so nobody can take over a setup someone else started.
 */
@Service
class SetupService(
	private val users: UserAccountRepository,
	private val passwordEncoder: PasswordEncoder,
	private val clock: Clock,
) {
	private val log = LoggerFactory.getLogger(javaClass)
	private val totp = Totp()

	@Transactional(readOnly = true)
	fun isSetupRequired(): Boolean = users.findFirstBy()?.isActive != true

	@Transactional
	fun start(
		username: String,
		password: String,
	): SetupEnrollment {
		val existing = users.findFirstBy()
		if (existing != null) {
			if (existing.isActive) throw setupComplete()
			if (!mayReplace(existing, username, password)) throw setupInProgress()
			users.delete(existing)
			users.flush()
		}
		val secret = Totp.newSecret()
		val encodedSecret = Base32.encode(secret)
		val recoveryCodes = RecoveryCodes.generate()
		val account = UserAccount(username, passwordEncoder.encode(password)!!, EncryptedSecret(encodedSecret))
		recoveryCodes.forEach { account.addRecoveryCode(passwordEncoder.encode(RecoveryCodes.normalize(it))!!) }
		try {
			users.saveAndFlush(account)
		} catch (_: DataIntegrityViolationException) {
			// A concurrent setup request created the account first (single-row unique index).
			throw setupInProgress()
		}
		log.info("Setup started: account created, waiting for TOTP confirmation")
		return SetupEnrollment(encodedSecret, otpauthUri(username, encodedSecret), recoveryCodes)
	}

	@Transactional
	fun confirmTotp(code: String) {
		val account = users.findFirstBy() ?: throw ConflictException("Setup has not been started")
		if (account.isActive) throw setupComplete()
		val now = clock.instant()
		if (!totp.verify(Base32.decode(account.totpSecret.reveal()), code, now)) {
			throw InvalidRequestException("code", "is not a valid code for the TOTP secret")
		}
		account.confirmTotp(now.truncatedTo(ChronoUnit.MICROS))
		users.saveAndFlush(account)
		log.info("Setup complete: TOTP confirmed, account active")
	}

	private fun mayReplace(
		pending: UserAccount,
		username: String,
		password: String,
	): Boolean {
		val createdAt = pending.createdAt ?: return false
		if (clock.instant().isAfter(createdAt.plus(ABANDONED_AFTER))) return true
		// Always hash, so the response time does not tell whether the username matched.
		val passwordMatches = passwordEncoder.matches(password, pending.passwordHash)
		return passwordMatches && username == pending.username
	}

	private fun setupComplete() = ConflictException("Setup is already complete")

	private fun setupInProgress() = ConflictException("Setup is already in progress")

	private fun otpauthUri(
		username: String,
		secret: String,
	): String {
		val label = "${encode(ISSUER)}:${encode(username)}"
		return "otpauth://totp/$label?secret=$secret&issuer=${encode(ISSUER)}" +
			"&algorithm=SHA1&digits=${Totp.DIGITS}&period=${Totp.STEP.seconds}"
	}

	private fun encode(value: String) = URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")

	companion object {
		const val ISSUER = "My Cockpit"

		/** After this time anyone may replace a pending (unconfirmed) account. */
		val ABANDONED_AFTER: Duration = Duration.ofHours(1)
	}
}

/** What the user needs to enroll: shown exactly once, never stored in this form. `toString` hides the secrets. */
class SetupEnrollment(
	val totpSecret: String,
	val otpauthUri: String,
	val recoveryCodes: List<String>,
) {
	override fun toString(): String = "SetupEnrollment(******)"
}
