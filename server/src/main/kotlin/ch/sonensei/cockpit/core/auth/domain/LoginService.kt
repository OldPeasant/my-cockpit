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
		// Admission counts the attempt as a failure up front, so parallel requests cannot slip past the backoff.
		throttle.tryBeginAttempt()?.let { return LoginResult.Throttled(it) }
		val account = users.findFirstBy()?.takeIf { it.isActive }
		val now = clock.instant().truncatedTo(ChronoUnit.MICROS)

		val passwordMatches = passwordEncoder.matches(credentials.password, account?.passwordHash ?: dummyPasswordHash)
		val usernameMatches = account != null && account.username == credentials.username
		val secondFactor = account?.let { checkSecondFactor(it, credentials, now) }
		if (account == null || !passwordMatches || !usernameMatches || secondFactor == null) {
			log.info("Login failed")
			return LoginResult.InvalidCredentials
		}

		if (secondFactor is SecondFactor.Recovery) {
			secondFactor.code.markUsed(now)
			log.info("Recovery code used for login; {} unused codes left", account.recoveryCodes.count { !it.isUsed })
		}
		sessions.deleteExpired(now)
		val token = newToken()
		val session =
			sessions.saveAndFlush(
				AuthSession(account, hash(token), credentials.rememberDevice, now, now.plus(AuthSession.idleLifetime(credentials.rememberDevice))),
			)
		// Only a login whose session was written counts as a success; one that throws before stays a failure.
		throttle.recordSuccess()
		log.info("Login succeeded (remember device: {})", credentials.rememberDevice)
		return LoginResult.Success(SessionGrant(token, session.rememberDevice, principalOf(account)))
	}

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
			val secret = Base32.decode(account.totpSecret.reveal())
			return if (totp.verify(secret, code, now)) SecondFactor.Totp else null
		}
		val code = credentials.recoveryCode?.let(RecoveryCodes::normalize) ?: return null
		// Check every unused code (no early exit), so the time does not depend on which one matched.
		val matches = account.recoveryCodes.filter { !it.isUsed }.filter { passwordEncoder.matches(code, it.codeHash) }
		return matches.firstOrNull()?.let(SecondFactor::Recovery)
	}

	private fun newToken(): String {
		val bytes = ByteArray(TOKEN_BYTES).also(random::nextBytes)
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
	}

	private fun principalOf(account: UserAccount) = CockpitPrincipal(account.id, account.username)

	private sealed interface SecondFactor {
		data object Totp : SecondFactor

		class Recovery(
			val code: RecoveryCode,
		) : SecondFactor
	}

	companion object {
		private const val TOKEN_BYTES = 32

		/** A session's expiry is written at most this often, not on every request. */
		val TOUCH_INTERVAL: Duration = Duration.ofMinutes(1)

		/** Sessions are looked up by the SHA-256 of their token; the token itself is never stored. */
		fun hash(token: String): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8)))
	}
}

/** What the login form sends: exactly one of [totp] and [recoveryCode] is expected. `toString` hides the secrets. */
class LoginCredentials(
	val username: String,
	val password: String,
	val totp: String?,
	val recoveryCode: String?,
	val rememberDevice: Boolean,
) {
	override fun toString(): String = "LoginCredentials(username=$username, ******)"
}

/** The authenticated owner. Contains no secrets. */
data class CockpitPrincipal(
	val userId: UUID,
	val username: String,
)

sealed interface LoginResult {
	class Success(
		val grant: SessionGrant,
	) : LoginResult

	data object InvalidCredentials : LoginResult

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
