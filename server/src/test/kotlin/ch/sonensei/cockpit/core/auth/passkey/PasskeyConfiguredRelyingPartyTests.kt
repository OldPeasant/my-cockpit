package ch.sonensei.cockpit.core.auth.passkey

import ch.sonensei.cockpit.core.auth.domain.Base32
import ch.sonensei.cockpit.core.auth.domain.CockpitPrincipal
import ch.sonensei.cockpit.core.auth.domain.DeviceTokenResult
import ch.sonensei.cockpit.core.auth.domain.LoginResult
import ch.sonensei.cockpit.core.auth.domain.LoginThrottle
import ch.sonensei.cockpit.core.auth.domain.SetupService
import ch.sonensei.cockpit.core.auth.domain.Totp
import ch.sonensei.cockpit.core.auth.passkey.api.PasskeyRelyingParties
import ch.sonensei.cockpit.core.auth.passkey.domain.PasskeyService
import ch.sonensei.cockpit.core.auth.passkey.domain.RegistrationStartResult
import ch.sonensei.cockpit.core.auth.passkey.domain.RelyingParty
import ch.sonensei.cockpit.core.persistence.MutableClock
import ch.sonensei.cockpit.core.persistence.MutableClockTestConfiguration
import ch.sonensei.cockpit.core.web.InvalidRequestException
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import tools.jackson.databind.json.JsonMapper

/**
 * Passkeys with a configured relying party, as a production deployment sets it (`cockpit.auth.passkey.rp-id` /
 * `origins`, resolved by [PasskeyRelyingParties]): the Android app (origin `android:apk-key-hash:...`, Credential
 * Manager) registers and logs in with the same passkey as the web origin, independent of the request's host. Other
 * origins and RP IDs are rejected.
 *
 * Runs against the real [PasskeyService], Postgres and token filter; the relying party is built by
 * [PasskeyRelyingParties] from the configured values instead of a separate Spring context with other properties, so
 * this class shares the application context of the other web tests.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MutableClockTestConfiguration::class)
class PasskeyConfiguredRelyingPartyTests(
	@Autowired private val mockMvc: MockMvc,
	@Autowired private val jdbc: JdbcTemplate,
	@Autowired private val jsonMapper: JsonMapper,
	@Autowired private val clock: MutableClock,
	@Autowired private val setup: SetupService,
	@Autowired private val throttle: LoginThrottle,
	@Autowired private val passkeys: PasskeyService,
) : PostgresTestBase() {
	private val totp = Totp()
	private lateinit var secret: ByteArray
	private lateinit var principal: CockpitPrincipal

	/** What the deployment configures; the request comes from an internal host the passkeys must not depend on. */
	private val relyingParty: RelyingParty =
		PasskeyRelyingParties(RP_ID, "Fictitious Cockpit", listOf(WEB_ORIGIN, ANDROID_ORIGIN), "")
			.resolve(MockHttpServletRequest("POST", "/api/v1/auth/passkeys/token").apply { serverName = "cockpit-server" })

	@BeforeEach
	fun activeAccount() {
		clock.set(MutableClockTestConfiguration.START)
		throttle.recordSuccess()
		jdbc.update("delete from app_user")
		val enrollment = setup.start(USERNAME, PASSWORD)
		secret = Base32.decode(enrollment.totpSecret)
		setup.confirmTotp(currentCode())
		principal = CockpitPrincipal(jdbc.queryForObject("select id from app_user", java.util.UUID::class.java)!!, USERNAME)
	}

	@AfterEach
	fun noAccountLeft() {
		throttle.recordSuccess()
		jdbc.update("delete from app_user")
	}

	@Test
	fun `the Android app registers a passkey and logs in with it, and the synced passkey also works on the web`() {
		relyingParty shouldBe RelyingParty(RP_ID, "Fictitious Cockpit", setOf(WEB_ORIGIN, ANDROID_ORIGIN))
		val authenticator = TestAuthenticator(rpId = RP_ID, origin = ANDROID_ORIGIN)
		register(authenticator)

		val tokens = issueToken(authenticator).shouldBeInstanceOf<DeviceTokenResult.Issued>().tokens
		mockMvc
			.get("/api/v1/auth/me") { header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.accessToken}") }
			.andExpect { status { isOk() } }
		jdbc.queryForObject("select name from auth_device", String::class.java) shouldBe "Pixel 9"

		authenticator.origin = WEB_ORIGIN
		val options = passkeys.startAuthentication(relyingParty)
		val assertion = authenticator.authenticate(mapOf("challenge" to TestAuthenticator.encode(options.challenge)))
		passkeys.login(options.ceremonyId, jsonMapper.writeValueAsString(assertion), false).shouldBeInstanceOf<LoginResult.Success>()
	}

	@Test
	fun `another app signature, the request's own origin or another RP ID are rejected`() {
		shouldThrow<InvalidRequestException> { register(TestAuthenticator(rpId = "cockpit-server", origin = ANDROID_ORIGIN)) }
		shouldThrow<InvalidRequestException> { register(TestAuthenticator(rpId = RP_ID, origin = "http://cockpit-server")) }

		val authenticator = TestAuthenticator(rpId = RP_ID, origin = ANDROID_ORIGIN)
		register(authenticator)
		authenticator.origin = "android:apk-key-hash:BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB"
		issueToken(authenticator).shouldBeInstanceOf<DeviceTokenResult.Rejected>().result shouldBe LoginResult.InvalidCredentials
		authenticator.origin = "http://cockpit-server"
		issueToken(authenticator).shouldBeInstanceOf<DeviceTokenResult.Rejected>().result shouldBe LoginResult.InvalidCredentials
		jdbc.queryForObject("select count(*) from auth_device", Int::class.java) shouldBe 0
	}

	@Test
	fun `with only COCKPIT_DOMAIN set, a browser behind a TLS proxy registers and logs in with the https origin`() {
		// Caddy terminates TLS and forwards plain HTTP with the public Host: the server sees http://cockpit.example.org.
		val behindProxy =
			PasskeyRelyingParties("", "My Cockpit", emptyList(), RP_ID).resolve(
				MockHttpServletRequest("POST", "/api/v1/auth/passkeys/registration/options").apply {
					scheme = "http"
					serverName = RP_ID
					serverPort = 80
				},
			)
		val authenticator = TestAuthenticator(rpId = RP_ID, origin = WEB_ORIGIN)
		register(authenticator, behindProxy)
		val options = passkeys.startAuthentication(behindProxy)
		val assertion = authenticator.authenticate(mapOf("challenge" to TestAuthenticator.encode(options.challenge)))
		passkeys.login(options.ceremonyId, jsonMapper.writeValueAsString(assertion), false).shouldBeInstanceOf<LoginResult.Success>()

		// The Android origin and a foreign web origin are not accepted without explicit origins.
		authenticator.origin = ANDROID_ORIGIN
		issueToken(authenticator, behindProxy).shouldBeInstanceOf<DeviceTokenResult.Rejected>().result shouldBe
			LoginResult.InvalidCredentials
		authenticator.origin = "https://evil.example.net"
		issueToken(authenticator, behindProxy).shouldBeInstanceOf<DeviceTokenResult.Rejected>().result shouldBe
			LoginResult.InvalidCredentials
	}

	@Test
	fun `with only COCKPIT_DOMAIN set, a request for another host accepts only the domain's https origin`() {
		val otherHost =
			PasskeyRelyingParties("", "My Cockpit", emptyList(), RP_ID)
				.resolve(MockHttpServletRequest("POST", "/api/v1/auth/passkeys/token").apply { serverName = "cockpit-server" })
		shouldThrow<InvalidRequestException> {
			register(TestAuthenticator(rpId = RP_ID, origin = "http://cockpit-server"), otherHost)
		}
		register(TestAuthenticator(rpId = RP_ID, origin = WEB_ORIGIN), otherHost)
		jdbc.queryForObject("select count(*) from auth_passkey", Int::class.java) shouldBe 1
	}

	private fun register(
		authenticator: TestAuthenticator,
		relyingParty: RelyingParty = this.relyingParty,
	) {
		val options =
			passkeys.startRegistration(principal, currentCode(), relyingParty).shouldBeInstanceOf<RegistrationStartResult.Started>().options
		options.relyingParty.id shouldBe RP_ID
		val publicKey =
			mapOf(
				"challenge" to TestAuthenticator.encode(options.challenge),
				"user" to mapOf("id" to TestAuthenticator.encode(options.userHandle)),
			)
		passkeys.finishRegistration(principal, options.ceremonyId, "Pixel 9", jsonMapper.writeValueAsString(authenticator.register(publicKey)))
	}

	private fun issueToken(
		authenticator: TestAuthenticator,
		relyingParty: RelyingParty = this.relyingParty,
	): DeviceTokenResult {
		val options = passkeys.startAuthentication(relyingParty)
		val assertion = authenticator.authenticate(mapOf("challenge" to TestAuthenticator.encode(options.challenge)))
		return passkeys.issueToken(options.ceremonyId, jsonMapper.writeValueAsString(assertion), "Pixel 9")
	}

	private fun currentCode() = totp.generate(secret, clock.instant())

	private companion object {
		const val USERNAME = "jane.doe"
		const val PASSWORD = "correct-horse-battery-staple"
		const val RP_ID = "cockpit.example.org"
		const val WEB_ORIGIN = "https://cockpit.example.org"
		const val ANDROID_ORIGIN = "android:apk-key-hash:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
	}
}
