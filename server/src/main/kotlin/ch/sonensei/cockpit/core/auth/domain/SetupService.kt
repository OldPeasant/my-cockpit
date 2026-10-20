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
import java.time.temporal.ChronoUnit

/**
 * First-run setup (SPEC.md §4.1), only available while no active account exists:
 * [start] creates the account with a new TOTP secret and recovery codes, [confirmTotp] activates it once the user
 * proves the authenticator app works. Starting again before confirming replaces the pending account, so a user
 * who lost the QR code can begin anew.
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
			throw ConflictException("Setup is already in progress")
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

	private fun setupComplete() = ConflictException("Setup is already complete")

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
