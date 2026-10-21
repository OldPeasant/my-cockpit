package ch.sonensei.cockpit.core.auth.passkey

import ch.sonensei.cockpit.core.auth.domain.Base32
import ch.sonensei.cockpit.core.auth.domain.CockpitPrincipal
import ch.sonensei.cockpit.core.auth.domain.DeviceTokenResult
import ch.sonensei.cockpit.core.auth.domain.DeviceTokenService
import ch.sonensei.cockpit.core.auth.domain.LoginResult
import ch.sonensei.cockpit.core.auth.domain.LoginService
import ch.sonensei.cockpit.core.auth.domain.LoginThrottle
import ch.sonensei.cockpit.core.auth.domain.SessionGrant
import ch.sonensei.cockpit.core.auth.domain.Totp
import ch.sonensei.cockpit.core.auth.domain.UserAccount
import ch.sonensei.cockpit.core.auth.infra.UserAccountRepository
import ch.sonensei.cockpit.core.auth.passkey.api.PasskeyRegistrationOptionsRequest
import ch.sonensei.cockpit.core.auth.passkey.domain.Passkey
import ch.sonensei.cockpit.core.auth.passkey.domain.PasskeyCeremonies
import ch.sonensei.cockpit.core.auth.passkey.domain.PasskeyService
import ch.sonensei.cockpit.core.auth.passkey.domain.RegistrationStartResult
import ch.sonensei.cockpit.core.auth.passkey.domain.RelyingParty
import ch.sonensei.cockpit.core.auth.passkey.infra.PasskeyRepository
import ch.sonensei.cockpit.core.crypto.EncryptedSecret
import ch.sonensei.cockpit.core.persistence.MutableClock
import ch.sonensei.cockpit.core.web.InvalidRequestException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.dao.DataAccessResourceFailureException
import tools.jackson.databind.json.JsonMapper
import java.time.Instant

/**
 * How [PasskeyService] drives the shared [LoginThrottle] (task passkeys-server, assumption "passkey login shares the
 * login throttle"), like LoginServiceThrottleTests for the password login, and who may use a registration ceremony.
 * Repositories and the session/device services are mocked so that failures while writing the grant can be forced;
 * webauthn4j, the ceremonies, the throttle and the clock are real.
 */
class PasskeyServiceUnitTests {
	private val clock = MutableClock(Instant.parse("2026-10-20T09:00:00Z"))
	private val throttle = LoginThrottle(clock)
	private val users = mockk<UserAccountRepository>()
	private val passkeys = mockk<PasskeyRepository>()
	private val login = mockk<LoginService>()
	private val devices = mockk<DeviceTokenService>()
	private val secret = Totp.newSecret()
	private val account =
		UserAccount(USERNAME, "fictitious-hash", EncryptedSecret(Base32.encode(secret))).apply { confirmTotp(clock.instant()) }
	private val principal = CockpitPrincipal(account.id, USERNAME)
	private val rp = RelyingParty("localhost", "My Cockpit", setOf("http://localhost"))
	private val service = PasskeyService(users, passkeys, PasskeyCeremonies(clock), login, devices, throttle, clock)
	private val jsonMapper = JsonMapper()
	private val authenticator = TestAuthenticator()
	private val stored = slot<Passkey>()

	init {
		every { users.findFirstBy() } returns account
		every { users.findFirstForUpdate() } returns account
		every { passkeys.findAllByOrderByCreatedAtAsc() } returns emptyList()
		every { passkeys.existsByCredentialId(any()) } returns false
		every { passkeys.saveAndFlush(capture(stored)) } answers { firstArg() }
		every { passkeys.findByCredentialId(any()) } answers { stored.captured }
		every { login.recordSuccess() } answers { throttle.recordSuccess() }
		every { login.startSession(any(), any(), any()) } returns SessionGrant("fictitious-session-token", false, principal)
		registerPasskey()
	}

	@Test
	fun `a passkey login whose session cannot be written is not a success and does not reset the backoff`() {
		repeat(LoginThrottle.FREE_ATTEMPTS - 1) { service.login("no-such-ceremony", "{}", false) shouldBe LoginResult.InvalidCredentials }
		every { login.startSession(any(), any(), any()) } throws DataAccessResourceFailureException("fictitious outage")

		val (ceremony, json) = assertion()
		shouldThrow<DataAccessResourceFailureException> { service.login(ceremony, json, false) }

		verify(exactly = 0) { login.recordSuccess() }
		throttle.retryAfter() shouldBe LoginThrottle.BASE_DELAY
	}

	@Test
	fun `a passkey token login whose device cannot be written is not a success and does not reset the backoff`() {
		repeat(LoginThrottle.FREE_ATTEMPTS - 1) { service.issueToken("no-such-ceremony", "{}", "Pixel 9") }
		every { devices.issueFor(any(), any(), any()) } throws DataAccessResourceFailureException("fictitious outage")

		val (ceremony, json) = assertion()
		shouldThrow<DataAccessResourceFailureException> { service.issueToken(ceremony, json, "Pixel 9") }

		verify(exactly = 0) { login.recordSuccess() }
		throttle.retryAfter() shouldBe LoginThrottle.BASE_DELAY
	}

	@Test
	fun `a blocked passkey login does not use up its ceremony and works once the block ends`() {
		repeat(LoginThrottle.FREE_ATTEMPTS) { service.login("no-such-ceremony", "{}", false) }
		val (ceremony, json) = assertion()

		service.login(ceremony, json, false).shouldBeInstanceOf<LoginResult.Throttled>().retryAfter shouldBe LoginThrottle.BASE_DELAY
		service
			.issueToken(ceremony, json, "Pixel 9")
			.shouldBeInstanceOf<DeviceTokenResult.Rejected>()
			.result
			.shouldBeInstanceOf<LoginResult.Throttled>()
		verify(exactly = 0) { passkeys.findByCredentialId(any()) }

		clock.advance(LoginThrottle.BASE_DELAY)
		service.login(ceremony, json, true).shouldBeInstanceOf<LoginResult.Success>()
		verify(exactly = 1) { login.startSession(account, true, any()) }
		throttle.retryAfter() shouldBe null
	}

	@Test
	fun `a blocked registration start does not check the TOTP code`() {
		repeat(LoginThrottle.FREE_ATTEMPTS) { service.login("no-such-ceremony", "{}", false) }
		clearMocks(users, answers = false)

		service.startRegistration(principal, currentCode(), rp).shouldBeInstanceOf<RegistrationStartResult.Throttled>()

		verify(exactly = 0) { users.findFirstBy() }
	}

	@Test
	fun `a principal that is not the active owner cannot start a registration`() {
		val stranger = CockpitPrincipal(java.util.UUID.randomUUID(), "someone.else")
		service.startRegistration(stranger, currentCode(), rp) shouldBe RegistrationStartResult.InvalidTotp
	}

	@Test
	fun `a registration ceremony can only be finished by the owner who started it`() {
		val options = service.startRegistration(principal, currentCode(), rp).shouldBeInstanceOf<RegistrationStartResult.Started>().options
		val publicKey =
			mapOf(
				"challenge" to TestAuthenticator.encode(options.challenge),
				"user" to mapOf("id" to TestAuthenticator.encode(options.userHandle)),
			)
		val stranger = CockpitPrincipal(java.util.UUID.randomUUID(), "someone.else")

		shouldThrow<InvalidRequestException> {
			service.finishRegistration(
				stranger,
				options.ceremonyId,
				"Laptop",
				jsonMapper.writeValueAsString(TestAuthenticator().register(publicKey)),
			)
		}.fieldErrors.single().field shouldBe "ceremonyId"
		verify(exactly = 1) { passkeys.saveAndFlush(any()) }
	}

	@Test
	fun `the registration options request never shows the TOTP code`() {
		PasskeyRegistrationOptionsRequest("123456").toString() shouldNotContain "123456"
	}

	private fun registerPasskey() {
		val started = service.startRegistration(principal, currentCode(), rp).shouldBeInstanceOf<RegistrationStartResult.Started>()
		val options = started.options
		val publicKey =
			mapOf(
				"challenge" to TestAuthenticator.encode(options.challenge),
				"user" to mapOf("id" to TestAuthenticator.encode(options.userHandle)),
			)
		service.finishRegistration(principal, options.ceremonyId, "Laptop", jsonMapper.writeValueAsString(authenticator.register(publicKey)))
	}

	private fun assertion(): Pair<String, String> {
		val options = service.startAuthentication(rp)
		val credential = authenticator.authenticate(mapOf("challenge" to TestAuthenticator.encode(options.challenge)))
		return options.ceremonyId to jsonMapper.writeValueAsString(credential)
	}

	private fun currentCode() = Totp().generate(secret, clock.instant())

	private companion object {
		const val USERNAME = "jane.doe"
	}
}
