package ch.sonensei.cockpit.core.oauth

import ch.sonensei.cockpit.core.oauth.domain.OAuthConnectionNotFoundException
import ch.sonensei.cockpit.core.oauth.domain.OAuthConnectionStatus
import ch.sonensei.cockpit.core.oauth.domain.OAuthGrantRejectedException
import ch.sonensei.cockpit.core.oauth.domain.OAuthProviderUnavailableException
import ch.sonensei.cockpit.core.oauth.domain.OAuthReconnectRequiredException
import ch.sonensei.cockpit.core.oauth.domain.OAuthResource
import ch.sonensei.cockpit.core.oauth.domain.OAuthTokenProvider
import ch.sonensei.cockpit.core.persistence.UuidV7
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.containing
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import java.time.Duration
import java.util.UUID

/** [OAuthTokenProvider]: cached access tokens, refresh before expiry, rotation and revoked refresh tokens. */
class OAuthTokenProviderTests : OAuthWebTestBase() {
	@Autowired private lateinit var tokens: OAuthTokenProvider

	@Test
	fun `the access token of the callback is reused until shortly before it expires, then refreshed`() {
		val id = UUID.fromString(connect())
		tokens.accessToken(id).value shouldBe ACCESS_TOKEN
		stubToken(grantType = "refresh_token", accessToken = "fictitious-access-token-2", refreshToken = null)

		clock.advance(Duration.ofMinutes(54))
		tokens.accessToken(id).value shouldBe ACCESS_TOKEN
		wireMock.findAll(postRequestedFor(urlPathEqualTo("/google/token")).withRequestBody(containing("grant_type=refresh_token"))).size shouldBe
			0

		clock.advance(Duration.ofMinutes(2))
		val refreshed = tokens.accessToken(id)
		refreshed.value shouldBe "fictitious-access-token-2"
		refreshed.expiresAt shouldBe clock.instant().plusSeconds(3600)
		wireMock.verify(
			1,
			postRequestedFor(urlPathEqualTo("/google/token"))
				.withRequestBody(containing("grant_type=refresh_token"))
				.withRequestBody(containing("refresh_token=$REFRESH_TOKEN"))
				.withRequestBody(containing("client_secret=fictitious-google-client-secret")),
		)
		tokens.accessToken(id).value shouldBe "fictitious-access-token-2"
		tokens.connection(id).shouldNotBeNull().lastRefreshedAt shouldBe clock.instant()
	}

	@Test
	fun `a rotated refresh token replaces the stored one without changing the version`() {
		val id = UUID.fromString(connect("microsoft"))
		val version = tokens.connection(id).shouldNotBeNull().version
		stubToken("microsoft", grantType = "refresh_token", accessToken = "fictitious-mail-token", refreshToken = "fictitious-refresh-token-2")

		tokens.accessToken(id, OAuthResource.MAIL).value shouldBe "fictitious-mail-token"

		wireMock.verify(
			postRequestedFor(urlPathEqualTo("/microsoft/token"))
				.withRequestBody(containing("scope=offline_access+https%3A%2F%2Foutlook.office.com%2FIMAP.AccessAsUser.All")),
		)
		val stored = jdbc.queryForObject("select refresh_token from oauth_connection", String::class.java)!!
		stored shouldNotContain "fictitious-refresh-token-2"
		tokens.connection(id).shouldNotBeNull().version shouldBe version

		// The next refresh uses the rotated token.
		clock.advance(Duration.ofHours(2))
		tokens.accessToken(id, OAuthResource.MAIL)
		wireMock.verify(
			postRequestedFor(urlPathEqualTo("/microsoft/token")).withRequestBody(containing("refresh_token=fictitious-refresh-token-2")),
		)
	}

	@Test
	fun `a revoked refresh token marks the connection reconnect required`() {
		val id = UUID.fromString(connect())
		clock.advance(Duration.ofHours(2))
		stubTokenError(grantType = "refresh_token", error = "invalid_grant")

		assertThrows<OAuthReconnectRequiredException> { tokens.accessToken(id) }

		val info = tokens.connection(id).shouldNotBeNull()
		info.status shouldBe OAuthConnectionStatus.RECONNECT_REQUIRED
		info.lastError shouldBe "invalid_grant"
		jdbc.queryForObject("select refresh_token from oauth_connection", String::class.java) shouldBe null
		// No more token requests until the owner reconnects.
		wireMock.resetRequests()
		assertThrows<OAuthReconnectRequiredException> { tokens.accessToken(id) }
		wireMock.findAll(postRequestedFor(urlPathEqualTo("/google/token"))).size shouldBe 0

		// Connecting the account again makes it usable.
		connect()
		tokens.connection(id).shouldNotBeNull().status shouldBe OAuthConnectionStatus.CONNECTED
		tokens.accessToken(id).value shouldBe ACCESS_TOKEN
	}

	@Test
	fun `other provider errors keep the connection and record the error`() {
		val id = UUID.fromString(connect())
		clock.advance(Duration.ofHours(2))
		stubTokenError(grantType = "refresh_token", error = "invalid_client", status = 401)

		assertThrows<OAuthGrantRejectedException> { tokens.accessToken(id) }.error shouldBe "invalid_client"
		tokens.connection(id).shouldNotBeNull().status shouldBe OAuthConnectionStatus.CONNECTED
		tokens.connection(id).shouldNotBeNull().lastError shouldBe "invalid_client"

		wireMock.stubFor(post(urlPathEqualTo("/google/token")).willReturn(aResponse().withStatus(503)))
		assertThrows<OAuthProviderUnavailableException> { tokens.accessToken(id) }
		tokens.connection(id).shouldNotBeNull().status shouldBe OAuthConnectionStatus.CONNECTED
	}

	@Test
	fun `an unknown connection is reported as not found`() {
		assertThrows<OAuthConnectionNotFoundException> { tokens.accessToken(UuidV7.generate()) }
		tokens.connection(UuidV7.generate()) shouldBe null
	}
}
