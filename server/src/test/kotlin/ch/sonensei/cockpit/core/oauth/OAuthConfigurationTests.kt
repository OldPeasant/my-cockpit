package ch.sonensei.cockpit.core.oauth

import ch.sonensei.cockpit.core.oauth.domain.OAuthAccessToken
import ch.sonensei.cockpit.core.oauth.domain.OAuthConnectionService
import ch.sonensei.cockpit.core.oauth.domain.OAuthProvider
import ch.sonensei.cockpit.core.oauth.domain.OAuthPurpose
import ch.sonensei.cockpit.core.oauth.domain.TokenGrant
import ch.sonensei.cockpit.core.oauth.infra.OAuthAuthorizationRepository
import ch.sonensei.cockpit.core.oauth.infra.OAuthConnectionRepository
import ch.sonensei.cockpit.core.oauth.infra.OAuthProperties
import ch.sonensei.cockpit.core.web.ConflictException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.confirmVerified
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** Provider configuration (without a Spring context): which apps count as configured, redirect URIs, and redaction. */
class OAuthConfigurationTests {
	@Test
	fun `a provider counts as configured only with both client id and secret`() {
		registration("id", "secret").configured shouldBe true
		registration("", "secret").configured shouldBe false
		registration("id", "").configured shouldBe false
		registration("  ", "  ").configured shouldBe false
	}

	@Test
	fun `starting a flow for an unconfigured provider is a conflict and stores nothing`() {
		val authorizations = mockk<OAuthAuthorizationRepository>()
		val connections = mockk<OAuthConnectionRepository>()
		val properties =
			OAuthProperties(
				publicUrl = "https://cockpit.example.test",
				google = registration("", ""),
				microsoft = registration("fictitious-ms-client-id", "fictitious-ms-secret"),
			)
		val service =
			OAuthConnectionService(
				properties,
				connections,
				authorizations,
				mockk(),
				mockk(),
				mockk(),
				mockk(),
				Clock.systemUTC(),
			)

		service.isConfigured(OAuthProvider.GOOGLE) shouldBe false
		service.isConfigured(OAuthProvider.MICROSOFT) shouldBe true
		val e = shouldThrow<ConflictException> { service.start(OAuthProvider.GOOGLE, OAuthPurpose.CALENDAR, "fictitious-session") }
		e.status shouldBe HttpStatus.CONFLICT
		e.message shouldContain "Google"
		confirmVerified(authorizations, connections)
	}

	@Test
	fun `the redirect URI is derived from the public URL with or without a trailing slash`() {
		for (url in listOf("https://cockpit.example.test", "https://cockpit.example.test/")) {
			val properties = OAuthProperties(url, registration("a", "b"), registration("c", "d"))
			properties.redirectUri(OAuthProvider.GOOGLE) shouldBe "https://cockpit.example.test/api/v1/oauth/google/callback"
			properties.redirectUri(OAuthProvider.MICROSOFT) shouldBe "https://cockpit.example.test/api/v1/oauth/microsoft/callback"
		}
	}

	@Test
	fun `toString never shows the client secret or tokens`() {
		registration("fictitious-client-id", "fictitious-client-secret").toString() shouldNotContain "fictitious-client-secret"
		val grant = TokenGrant("fictitious-at", Duration.ofHours(1), "fictitious-rt", "openid", "fictitious-id-token")
		grant.toString().let {
			it shouldNotContain "fictitious-at"
			it shouldNotContain "fictitious-rt"
			it shouldNotContain "fictitious-id-token"
		}
		OAuthAccessToken("fictitious-at", Instant.EPOCH).toString() shouldNotContain "fictitious-at"
	}

	@Test
	fun `provider slugs map both ways and unknown slugs are rejected`() {
		OAuthProvider.fromSlug("google") shouldBe OAuthProvider.GOOGLE
		OAuthProvider.fromSlug("microsoft") shouldBe OAuthProvider.MICROSOFT
		OAuthProvider.fromSlug("Google") shouldBe null
		OAuthProvider.fromSlug("") shouldBe null
	}

	private fun registration(
		clientId: String,
		clientSecret: String,
	) = OAuthProperties.Registration(
		clientId = clientId,
		clientSecret = clientSecret,
		authorizationUri = "https://idp.example.test/authorize",
		tokenUri = "https://idp.example.test/token",
	)
}
