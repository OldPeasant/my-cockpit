package ch.sonensei.cockpit.core.auth

import ch.sonensei.cockpit.core.auth.api.RefreshRequest
import ch.sonensei.cockpit.core.auth.api.TokenRequest
import ch.sonensei.cockpit.core.auth.api.TokenResponse
import ch.sonensei.cockpit.core.auth.domain.AuthDevice
import ch.sonensei.cockpit.core.auth.domain.CockpitPrincipal
import ch.sonensei.cockpit.core.auth.domain.DeviceTokenService
import ch.sonensei.cockpit.core.auth.domain.DeviceTokens
import ch.sonensei.cockpit.core.auth.domain.UserAccount
import ch.sonensei.cockpit.core.auth.infra.BearerAuthenticationFilter
import ch.sonensei.cockpit.core.crypto.EncryptedSecret
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Device token objects without Spring (task p0-auth-tokens): secrets hidden from `toString`, the exact expiry instants
 * of a device's tokens, the parsing of the `Authorization` header and the bearer filter's handling of the security
 * context.
 */
class DeviceTokenUnitTests {
	@AfterEach
	fun clearContext() = SecurityContextHolder.clearContext()

	@Test
	fun `token request, refresh request, token response and device tokens hide their secrets`() {
		val texts =
			listOf(
				TokenRequest(USERNAME, PASSWORD, TOTP_CODE, RECOVERY_CODE, DEVICE_NAME).toString(),
				RefreshRequest(REFRESH_TOKEN).toString(),
				TokenResponse.of(tokens()).toString(),
				tokens().toString(),
			)

		texts.forEach { text ->
			text shouldNotContain PASSWORD
			text shouldNotContain TOTP_CODE
			text shouldNotContain RECOVERY_CODE
			text shouldNotContain ACCESS_TOKEN
			text shouldNotContain REFRESH_TOKEN
		}
		texts[0] shouldContain USERNAME
		texts[0] shouldContain DEVICE_NAME
		texts[2] shouldContain DEVICE_ID.toString()
		texts[3] shouldContain DEVICE_ID.toString()
	}

	@Test
	fun `a token response carries the bearer type and both tokens`() {
		val response = TokenResponse.of(tokens())

		response.tokenType shouldBe "Bearer"
		response.accessToken shouldBe ACCESS_TOKEN
		response.refreshToken shouldBe REFRESH_TOKEN
		response.accessTokenExpiresAt shouldBe NOW.plus(AuthDevice.ACCESS_LIFETIME)
	}

	@Test
	fun `a device's access token expires exactly 15 minutes and its refresh token exactly 90 days after issue`() {
		val device = device()

		device.accessExpiresAt shouldBe NOW.plus(Duration.ofMinutes(15))
		device.refreshExpiresAt shouldBe NOW.plus(Duration.ofDays(90))
		device.lastSeenAt shouldBe NOW
		device.isAccessExpiredAt(NOW.plus(AuthDevice.ACCESS_LIFETIME).minusNanos(1)) shouldBe false
		device.isAccessExpiredAt(NOW.plus(AuthDevice.ACCESS_LIFETIME)) shouldBe true
		device.isRefreshExpiredAt(NOW.plus(AuthDevice.REFRESH_LIFETIME).minusNanos(1)) shouldBe false
		device.isRefreshExpiredAt(NOW.plus(AuthDevice.REFRESH_LIFETIME)) shouldBe true
	}

	@Test
	fun `rotating a device replaces both hashes and restarts both lifetimes`() {
		val device = device()
		val later = NOW.plus(Duration.ofDays(10))

		device.rotate("new-access-hash", "new-refresh-hash", later)

		device.accessTokenHash shouldBe "new-access-hash"
		device.refreshTokenHash shouldBe "new-refresh-hash"
		device.accessExpiresAt shouldBe later.plus(AuthDevice.ACCESS_LIFETIME)
		device.refreshExpiresAt shouldBe later.plus(AuthDevice.REFRESH_LIFETIME)
		device.lastSeenAt shouldBe later
		device.isRefreshExpiredAt(NOW.plus(AuthDevice.REFRESH_LIFETIME)) shouldBe false
	}

	@Test
	fun `the bearer scheme is case-insensitive and the token is trimmed`() {
		BearerAuthenticationFilter.bearerToken(request("Bearer $ACCESS_TOKEN")) shouldBe ACCESS_TOKEN
		BearerAuthenticationFilter.bearerToken(request("bearer $ACCESS_TOKEN")) shouldBe ACCESS_TOKEN
		BearerAuthenticationFilter.bearerToken(request("BEARER $ACCESS_TOKEN")) shouldBe ACCESS_TOKEN
		BearerAuthenticationFilter.bearerToken(request("Bearer   $ACCESS_TOKEN  ")) shouldBe ACCESS_TOKEN
	}

	@Test
	fun `an empty bearer header is a bearer header without a token`() {
		listOf("Bearer ", "Bearer    ", "bearer \t").forEach { header ->
			val request = request(header)
			BearerAuthenticationFilter.hasBearerHeader(request) shouldBe true
			BearerAuthenticationFilter.withBearerHeader.matches(request) shouldBe true
			BearerAuthenticationFilter.bearerToken(request).shouldBeNull()
		}
	}

	@Test
	fun `other authorization schemes and a missing header are not bearer headers`() {
		listOf(null, "", "Basic amFuZTpzZWNyZXQ=", "Token $ACCESS_TOKEN", "Bear $ACCESS_TOKEN", "Bearer$ACCESS_TOKEN").forEach { header ->
			val request = request(header)
			BearerAuthenticationFilter.hasBearerHeader(request) shouldBe false
			BearerAuthenticationFilter.withBearerHeader.matches(request) shouldBe false
			BearerAuthenticationFilter.bearerToken(request).shouldBeNull()
		}
	}

	@Test
	fun `a valid bearer token authenticates the request`() {
		val principal = CockpitPrincipal(UUID.randomUUID(), USERNAME)
		val devices = mockk<DeviceTokenService> { every { authenticate(ACCESS_TOKEN) } returns principal }
		val request = request("Bearer $ACCESS_TOKEN")
		val chain = MockFilterChain()

		BearerAuthenticationFilter(devices).doFilter(request, MockHttpServletResponse(), chain)

		SecurityContextHolder.getContext().authentication!!.principal shouldBe principal
		chain.request shouldBe request
	}

	@Test
	fun `an unknown bearer token leaves the request anonymous and passes it on`() {
		val devices = mockk<DeviceTokenService> { every { authenticate(any()) } returns null }
		val request = request("Bearer not-an-access-token")
		val chain = MockFilterChain()

		BearerAuthenticationFilter(devices).doFilter(request, MockHttpServletResponse(), chain)

		SecurityContextHolder.getContext().authentication.shouldBeNull()
		chain.request shouldBe request
	}

	@Test
	fun `an empty bearer token or an authentication already present is not looked up`() {
		val devices = mockk<DeviceTokenService>()
		BearerAuthenticationFilter(devices).doFilter(request("Bearer "), MockHttpServletResponse(), MockFilterChain())

		val existing = TestingAuthenticationToken("someone.else", null, "ROLE_USER")
		SecurityContextHolder.getContext().authentication = existing
		val request = request("Bearer $ACCESS_TOKEN")
		BearerAuthenticationFilter(devices).doFilter(request, MockHttpServletResponse(), MockFilterChain())

		SecurityContextHolder.getContext().authentication shouldBe existing
		verify(exactly = 0) { devices.authenticate(any()) }
	}

	private fun request(authorization: String?) =
		MockHttpServletRequest("GET", "/api/v1/auth/me").apply {
			if (authorization != null) addHeader(HttpHeaders.AUTHORIZATION, authorization)
		}

	private fun device() =
		AuthDevice(
			UserAccount(USERNAME, "fictitious-password-hash", EncryptedSecret("fictitious-totp-secret")),
			DEVICE_NAME,
			"fictitious-access-hash",
			"fictitious-refresh-hash",
			NOW,
		)

	private fun tokens() =
		DeviceTokens(DEVICE_ID, ACCESS_TOKEN, NOW.plus(AuthDevice.ACCESS_LIFETIME), REFRESH_TOKEN, NOW.plus(AuthDevice.REFRESH_LIFETIME))

	private companion object {
		const val USERNAME = "jane.doe"
		const val PASSWORD = "correct-horse-battery-staple"
		const val TOTP_CODE = "493817"
		const val RECOVERY_CODE = "qwert-zxcvb"
		const val DEVICE_NAME = "Fictitious Phone"
		const val ACCESS_TOKEN = "fictitious-access-token-value"
		const val REFRESH_TOKEN = "fictitious-refresh-token-value"
		val DEVICE_ID: UUID = UUID.fromString("01928c3e-7a00-7000-8000-000000000001")
		val NOW: Instant = Instant.parse("2026-10-20T09:00:00Z")
	}
}
