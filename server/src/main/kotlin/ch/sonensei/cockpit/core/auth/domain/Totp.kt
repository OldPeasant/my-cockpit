package ch.sonensei.cockpit.core.auth.domain

import com.eatthepath.otp.TimeBasedOneTimePasswordGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import javax.crypto.spec.SecretKeySpec

/**
 * Time-based one-time passwords (RFC 6238) as authenticator apps use them by default: HMAC-SHA1, 30-second
 * steps, 6 digits. [verify] accepts the current step and one step before or after (clock drift).
 */
class Totp(
	private val digits: Int = DIGITS,
	private val algorithm: String = TimeBasedOneTimePasswordGenerator.TOTP_ALGORITHM_HMAC_SHA1,
	private val step: Duration = STEP,
) {
	private val generator = TimeBasedOneTimePasswordGenerator(step, digits, algorithm)

	/** The code for [secret] at [at], zero-padded to [digits] digits. */
	fun generate(
		secret: ByteArray,
		at: Instant,
	): String {
		val code = generator.generateOneTimePassword(SecretKeySpec(secret, algorithm), at)
		return code.toString().padStart(digits, '0')
	}

	/** Whether [code] is valid for [secret] at [now], allowing one step of drift in either direction. */
	fun verify(
		secret: ByteArray,
		code: String,
		now: Instant,
	): Boolean {
		val candidate = code.filterNot { it.isWhitespace() }
		if (candidate.length != digits || !candidate.all { it in '0'..'9' }) return false
		// Check every window (no early exit) and compare in constant time.
		return (-DRIFT_STEPS..DRIFT_STEPS)
			.map { offset -> generate(secret, now.plus(step.multipliedBy(offset.toLong()))) }
			.fold(false) { matched, expected ->
				MessageDigest.isEqual(expected.toByteArray(), candidate.toByteArray()) or matched
			}
	}

	companion object {
		const val DIGITS = 6
		val STEP: Duration = Duration.ofSeconds(30)
		const val DRIFT_STEPS = 1

		/** Secret length recommended by RFC 4226 for HMAC-SHA1 (160 bits). */
		const val SECRET_BYTES = 20

		private val random = SecureRandom()

		fun newSecret(): ByteArray = ByteArray(SECRET_BYTES).also(random::nextBytes)
	}
}
