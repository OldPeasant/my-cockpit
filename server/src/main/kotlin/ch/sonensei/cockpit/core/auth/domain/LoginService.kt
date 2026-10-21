package ch.sonensei.cockpit.core.auth.domain

import ch.sonensei.cockpit.core.auth.infra.AuthSessionRepository
import ch.sonensei.cockpit.core.auth.infra.UserAccountRepository
import org.slf4j.LoggerFactory
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.HexFormat
import java.util.UUID

/**
 * Web login with password plus TOTP or a recovery code (SPEC.md §4.1), and the sessions it creates.
 *
 * An account restored without its TOTP secret ([UserAccount.totpReenrollRequired]) cannot use TOTP: it logs in with a
 * recovery code, or with the password alone when no unused recovery code is left (so the owner is not locked out), and
 * its sessions may only enroll TOTP again ([CockpitPrincipal.totpEnrollmentRequired]). Device tokens are refused
 * until then ([CredentialCheck.Rejected] with [LoginResult.TotpEnrollmentRequired]).
 *
 * A failed login never says which part was wrong. The password is hashed even when the username does not match or no
 * account exists, and the second factor is checked whenever an account exists, so the response time does not tell
 * either. [LoginThrottle] blocks repeated failures.
 */
@Service
class LoginService(
	private val users: UserAccountRepository,
	private val sessions: AuthSessionRepository,
	private val passwordEncoder: PasswordEncoder,
	private val throttle: LoginThrottle,
	private val clock: Clock,
) {
	private val log = LoggerFactory.getLogger(javaClass)
	private val totp = Totp()
	private val random = SecureRandom()

	// Compared against when no account exists, so that case costs one Argon2 verification like the others.
	private val dummyPasswordHash: String by lazy { passwordEncoder.encode("no-account-placeholder")!! }

	@Transactional
	fun login(credentials: LoginCredentials): LoginResult {
		val (account, now) =
			when (val check = verify(credentials)) {
				is CredentialCheck.Rejected -> return check.result
				is CredentialCheck.Verified -> check
			}
		val grant = startSession(account, credentials.rememberDevice, now)
		// Only a login whose session was written counts as a success; one that throws before stays a failure.
		throttle.recordSuccess()
		log.info(
			"Login succeeded (remember device: {}, TOTP enrollment required: {})",
			credentials.rememberDevice,
			account.totpReenrollRequired,
		)
		return LoginResult.Success(grant)
	}

	/**
	 * Writes a new session for [account], whose credentials the caller has verified (password+TOTP, or a passkey).
	 * Joins the caller's transaction; the caller resets the throttle with [recordSuccess] afterwards.
	 */
	@Transactional
	fun startSession(
		account: UserAccount,
		rememberDevice: Boolean,
		now: Instant,
	): SessionGrant {
		sessions.deleteExpired(now)
		val token = newToken(random)
		val session =
			sessions.saveAndFlush(
				AuthSession(account, hash(token), rememberDevice, now, now.plus(AuthSession.idleLifetime(rememberDevice))),
			)
		return SessionGrant(token, session.rememberDevice, principalOf(account))
	}

	/**
	 * Checks [credentials] under the login throttle and marks a used recovery code. Joins the caller's transaction:
	 * on [CredentialCheck.Verified] the caller writes its grant (session, device) and then calls [recordSuccess], so
	 * an attempt whose grant fails to be written still counts as a failure.
	 */
	@Transactional
	fun verify(
		credentials: LoginCredentials,
		allowTotpEnrollment: Boolean = true,
	): CredentialCheck {
		// Admission counts the attempt as a failure up front, so parallel requests cannot slip past the backoff.
		throttle.tryBeginAttempt()?.let { return CredentialCheck.Rejected(LoginResult.Throttled(it)) }
		// Locked until the caller's grant is written: a concurrent TOTP re-enrollment confirm waits for this session and
		// ends it, or runs first, and then this login sees the cleared flag (TotpEnrollmentService).
		val account = users.findFirstForUpdate()?.takeIf { it.isActive }
		val now = clock.instant().truncatedTo(ChronoUnit.MICROS)

		val passwordMatches = passwordEncoder.matches(credentials.password, account?.passwordHash ?: dummyPasswordHash)
		val usernameMatches = account != null && account.username == credentials.username
		val secondFactor = account?.let { checkSecondFactor(it, credentials, now) }
		if (account == null || !passwordMatches || !usernameMatches || secondFactor == null) {
			log.info("Login failed")
			return CredentialCheck.Rejected(LoginResult.InvalidCredentials)
		}

		if (account.totpReenrollRequired && !allowTotpEnrollment) {
			// Before marking a recovery code used: the code still works for the web login that enrolls TOTP.
			log.info("Login refused: TOTP must be enrolled again first")
			return CredentialCheck.Rejected(LoginResult.TotpEnrollmentRequired)
		}
		if (secondFactor is SecondFactor.Recovery) {
			secondFactor.code.markUsed(now)
			log.info("Recovery code used for login; {} unused codes left", account.recoveryCodes.count { !it.isUsed })
		}
		return CredentialCheck.Verified(account, now)
	}

	/** Resets the login throttle once the grant of a [CredentialCheck.Verified] was written. */
	fun recordSuccess() = throttle.recordSuccess()

	/**
	 * The principal of the session with [token], or null if the token is unknown or the session expired. A session
	 * used again after more than [TOUCH_INTERVAL] slides forward ([SessionAuthentication.renewed]).
	 */
	@Transactional
	fun authenticate(token: String): SessionAuthentication? {
		val session = sessions.findByTokenHash(hash(token)) ?: return null
		val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
		if (session.isExpiredAt(now)) {
			// A bulk delete, not an entity delete: parallel requests with the same expired cookie may find the row gone.
			sessions.deleteByTokenHash(session.tokenHash)
			return null
		}
		val renew = Duration.between(session.lastSeenAt, now) >= TOUCH_INTERVAL
		if (renew) sessions.touch(session.id, now, now.plus(session.idleLifetime))
		return SessionAuthentication(principalOf(session.user), session.rememberDevice, renew)
	}

	@Transactional
	fun logout(token: String) {
		if (sessions.deleteByTokenHash(hash(token)) > 0) log.info("Logged out")
	}

	private fun checkSecondFactor(
		account: UserAccount,
		credentials: LoginCredentials,
		now: Instant,
	): SecondFactor? {
		credentials.totp?.let { code ->
			// While re-enrolling, the stored secret is a random one or not confirmed yet: TOTP does not log in.
			if (account.totpReenrollRequired) return null
			val secret = Base32.decode(account.totpSecret.reveal())
			return if (totp.verify(secret, code, now)) SecondFactor.Totp else null
		}
		val unused = account.recoveryCodes.filter { !it.isUsed }
		val recoveryCode = credentials.recoveryCode
		if (recoveryCode == null) {
			return if (account.totpReenrollRequired && unused.isEmpty()) SecondFactor.None else null
		}
		val code = RecoveryCodes.normalize(recoveryCode)
		// Check every unused code (no early exit), so the time does not depend on which one matched.
		val matches = unused.filter { passwordEncoder.matches(code, it.codeHash) }
		return matches.firstOrNull()?.let(SecondFactor::Recovery)
	}

	private sealed interface SecondFactor {
		data object Totp : SecondFactor

		/** The password alone: only for an account that re-enrolls TOTP and has no unused recovery code left. */
		data object None : SecondFactor

		class Recovery(
			val code: RecoveryCode,
		) : SecondFactor
	}

	companion object {
		private const val TOKEN_BYTES = 32

		/** A random 256-bit token, base64url without padding. */
		fun newToken(random: SecureRandom): String {
			val bytes = ByteArray(TOKEN_BYTES).also(random::nextBytes)
			return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
		}

		fun principalOf(account: UserAccount) = CockpitPrincipal(account.id, account.username, account.totpReenrollRequired)

		/** A session's expiry is written at most this often, not on every request. */
		val TOUCH_INTERVAL: Duration = Duration.ofMinutes(1)

		/** Sessions are looked up by the SHA-256 of their token; the token itself is never stored. */
		fun hash(token: String): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8)))
	}
}

/**
 * What the login form sends: one of [totp] and [recoveryCode], or neither for an account that re-enrolls TOTP and has
 * no unused recovery code left. `toString` hides the secrets.
 */
class LoginCredentials(
	val username: String,
	val password: String,
	val totp: String?,
	val recoveryCode: String?,
	val rememberDevice: Boolean,
) {
	override fun toString(): String = "LoginCredentials(username=$username, ******)"
}

/**
 * The authenticated owner. Contains no secrets. With [totpEnrollmentRequired] (a session of an account restored without
 * its TOTP secret) it may only enroll TOTP again.
 */
data class CockpitPrincipal(
	val userId: UUID,
	val username: String,
	val totpEnrollmentRequired: Boolean = false,
)

/** The outcome of [LoginService.verify]. */
sealed interface CredentialCheck {
	data class Verified(
		val account: UserAccount,
		val now: Instant,
	) : CredentialCheck

	class Rejected(
		val result: LoginResult,
	) : CredentialCheck
}

sealed interface LoginResult {
	class Success(
		val grant: SessionGrant,
	) : LoginResult

	data object InvalidCredentials : LoginResult

	/** Correct credentials, but TOTP must be enrolled again (in the web app) before a device can log in. */
	data object TotpEnrollmentRequired : LoginResult

	class Throttled(
		val retryAfter: Duration,
	) : LoginResult
}

/** A new session: [token] goes into the cookie and is not stored anywhere else. `toString` hides it. */
class SessionGrant(
	val token: String,
	val rememberDevice: Boolean,
	val principal: CockpitPrincipal,
) {
	override fun toString(): String = "SessionGrant(******, rememberDevice=$rememberDevice, principal=$principal)"
}

/** A valid session. [renewed] means its expiry slid forward, so a persistent cookie should be re-issued. */
data class SessionAuthentication(
	val principal: CockpitPrincipal,
	val rememberDevice: Boolean,
	val renewed: Boolean,
)
