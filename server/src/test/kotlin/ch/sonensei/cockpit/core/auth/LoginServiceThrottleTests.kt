package ch.sonensei.cockpit.core.auth

import ch.sonensei.cockpit.core.auth.domain.Base32
import ch.sonensei.cockpit.core.auth.domain.LoginCredentials
import ch.sonensei.cockpit.core.auth.domain.LoginResult
import ch.sonensei.cockpit.core.auth.domain.LoginService
import ch.sonensei.cockpit.core.auth.domain.LoginThrottle
import ch.sonensei.cockpit.core.auth.domain.Totp
import ch.sonensei.cockpit.core.auth.domain.UserAccount
import ch.sonensei.cockpit.core.auth.infra.AuthSessionRepository
import ch.sonensei.cockpit.core.auth.infra.UserAccountRepository
import ch.sonensei.cockpit.core.crypto.EncryptedSecret
import ch.sonensei.cockpit.core.persistence.MutableClock
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.security.crypto.password.PasswordEncoder
import java.time.Instant

/**
 * How [LoginService] drives [LoginThrottle] (SPEC.md §4.1, task p0-auth-login AC4 and the recorded assumption "a login
 * attempt is counted against the throttle when it starts, so a login ending in an exception counts as a failure").
 * Repositories are mocked so that failures in the middle of a login can be forced deterministically; the throttle,
 * TOTP check and clock are real.
 */
class LoginServiceThrottleTests {
	private val clock = MutableClock(Instant.parse("2026-10-20T09:00:00Z"))
	private val throttle = LoginThrottle(clock)
	private val users = mockk<UserAccountRepository>()
	private val sessions = mockk<AuthSessionRepository>()
	private val secret = Totp.newSecret()
	private val account =
		UserAccount(USERNAME, PasswordHash.of(PASSWORD), EncryptedSecret(Base32.encode(secret))).apply {
			confirmTotp(clock.instant())
		}
	private val service = LoginService(users, sessions, PasswordHash, throttle, clock)

	init {
		every { users.findFirstBy() } returns account
		every { sessions.deleteExpired(any()) } returns 0
		every { sessions.saveAndFlush(any()) } answers { firstArg() }
	}

	@Test
	fun `a blocked attempt is rejected before the account is even loaded`() {
		repeat(LoginThrottle.FREE_ATTEMPTS) { service.login(wrongPassword()) shouldBe LoginResult.InvalidCredentials }

		val blocked = service.login(correct())

		blocked.shouldBeInstanceOf<LoginResult.Throttled>().retryAfter shouldBe LoginThrottle.BASE_DELAY
		verify(exactly = LoginThrottle.FREE_ATTEMPTS) { users.findFirstBy() }
		verify(exactly = 0) { sessions.saveAndFlush(any()) }
	}

	@Test
	fun `a correct fifth attempt logs in and lifts the block it started`() {
		repeat(LoginThrottle.FREE_ATTEMPTS - 1) { service.login(wrongPassword()) }

		service.login(correct()).shouldBeInstanceOf<LoginResult.Success>()

		throttle.retryAfter().shouldBeNull()
		// Counting starts over: four more failures are free.
		repeat(LoginThrottle.FREE_ATTEMPTS - 1) { service.login(wrongPassword()) shouldBe LoginResult.InvalidCredentials }
		throttle.retryAfter().shouldBeNull()
	}

	@Test
	fun `an attempt that throws during the credential check counts as a failure`() {
		every { users.findFirstBy() } throws DataAccessResourceFailureException("fictitious outage")

		repeat(LoginThrottle.FREE_ATTEMPTS) { shouldThrow<DataAccessResourceFailureException> { service.login(correct()) } }

		throttle.retryAfter() shouldBe LoginThrottle.BASE_DELAY
		service.login(correct()).shouldBeInstanceOf<LoginResult.Throttled>()
	}

	@Test
	fun `an attempt that throws while creating its session is not a success and does not reset the backoff`() {
		repeat(LoginThrottle.FREE_ATTEMPTS - 1) { service.login(wrongPassword()) }
		every { sessions.saveAndFlush(any()) } throws DataAccessResourceFailureException("fictitious outage")

		// No session is created, so this is the fifth consecutive attempt without a successful login.
		shouldThrow<DataAccessResourceFailureException> { service.login(correct()) }

		throttle.retryAfter() shouldBe LoginThrottle.BASE_DELAY
	}

	private fun correct() = LoginCredentials(USERNAME, PASSWORD, Totp().generate(secret, clock.instant()), null, false)

	private fun wrongPassword() =
		LoginCredentials(USERNAME, "wrong-fictitious-password", Totp().generate(secret, clock.instant()), null, false)

	/** A fast stand-in for Argon2: the "hash" is the password with a prefix. */
	private object PasswordHash : PasswordEncoder {
		fun of(raw: String) = "plain:$raw"

		override fun encode(rawPassword: CharSequence?): String = of(rawPassword.toString())

		override fun matches(
			rawPassword: CharSequence?,
			encodedPassword: String?,
		): Boolean = encodedPassword == of(rawPassword.toString())
	}

	private companion object {
		const val USERNAME = "jane.doe"
		const val PASSWORD = "correct-horse-battery-staple"
	}
}
