package ch.sonensei.cockpit.core.oauth

import ch.sonensei.cockpit.core.oauth.domain.OAuthException
import ch.sonensei.cockpit.core.oauth.domain.OAuthGrantRejectedException
import ch.sonensei.cockpit.core.oauth.domain.OAuthIdentity
import ch.sonensei.cockpit.core.oauth.domain.OAuthProvider
import ch.sonensei.cockpit.core.oauth.domain.OAuthProviderUnavailableException
import ch.sonensei.cockpit.core.oauth.infra.OAuthHttpClient
import ch.sonensei.cockpit.core.oauth.infra.OAuthProperties
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.anyUrl
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import com.github.tomakehurst.wiremock.http.Fault
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.util.Base64

/**
 * [OAuthHttpClient] without a Spring context: how token responses, error responses and ID tokens of a (WireMock)
 * provider are read, and that tokens and secrets never end up in exception messages.
 */
class OAuthHttpClientTests {
	private val client = OAuthHttpClient(properties(wireMock.baseUrl()), JsonMapper.builder().build())

	@BeforeEach
	fun reset() {
		wireMock.resetAll()
	}

	@Test
	fun `expires_in sent as a string is honoured, a missing one defaults to an hour`() {
		stubTokenBody("""{"access_token": "fictitious-at", "expires_in": "1800", "token_type": "Bearer"}""")
		refresh().expiresIn shouldBe Duration.ofSeconds(1800)

		stubTokenBody("""{"access_token": "fictitious-at", "token_type": "Bearer"}""")
		refresh().expiresIn shouldBe Duration.ofHours(1)

		stubTokenBody("""{"access_token": "fictitious-at", "expires_in": "soon"}""")
		refresh().expiresIn shouldBe Duration.ofHours(1)
	}

	@Test
	fun `a blank refresh token in the response counts as absent`() {
		stubTokenBody("""{"access_token": "fictitious-at", "expires_in": 60, "refresh_token": "  ", "scope": "openid"}""")

		val grant = refresh()

		grant.accessToken shouldBe "fictitious-at"
		grant.refreshToken shouldBe null
		grant.scope shouldBe "openid"
	}

	@Test
	fun `a token response without an access token is not a valid grant`() {
		for (body in listOf("""{"token_type": "Bearer"}""", """{"access_token": ""}""", """{"access_token": 42}""")) {
			stubTokenBody(body)
			shouldThrow<OAuthProviderUnavailableException> { refresh() }
		}
	}

	@Test
	fun `a successful response that is not JSON means the provider is unavailable`() {
		wireMock.stubFor(
			post(urlPathEqualTo("/google/token"))
				.willReturn(aResponse().withHeader("Content-Type", "text/html").withBody("<html>maintenance</html>")),
		)
		shouldThrow<OAuthProviderUnavailableException> { refresh() }

		stubTokenBody("""{"access_token": "fictitious-at", """)
		shouldThrow<OAuthProviderUnavailableException> { refresh() }

		stubTokenBody("")
		shouldThrow<OAuthProviderUnavailableException> { refresh() }
	}

	@Test
	fun `an error response without a usable error code becomes invalid_request`() {
		val bodies =
			listOf(
				"text/html" to "<html><body>Bad Request</body></html>",
				"application/json" to "",
				"application/json" to "[]",
				"application/json" to """{"error": 42}""",
				"application/json" to """{"error": "Invalid Grant: <script>"}""",
				"application/json" to """{"error": "${"a".repeat(65)}"}""",
			)
		for ((type, body) in bodies) {
			wireMock.stubFor(
				post(urlPathEqualTo("/google/token")).willReturn(aResponse().withStatus(400).withHeader("Content-Type", type).withBody(body)),
			)
			shouldThrow<OAuthGrantRejectedException> { refresh() }.error shouldBe "invalid_request"
		}
	}

	@Test
	fun `an RFC 6749 error code is passed on`() {
		wireMock.stubFor(
			post(urlPathEqualTo("/google/token"))
				.willReturn(
					aResponse()
						.withStatus(400)
						.withHeader("Content-Type", "application/json")
						.withBody("""{"error": "invalid_grant", "error_description": "Token has been expired or revoked."}"""),
				),
		)

		shouldThrow<OAuthGrantRejectedException> { refresh() }.error shouldBe "invalid_grant"
	}

	@Test
	fun `server errors and broken connections mean the provider is unavailable`() {
		wireMock.stubFor(post(anyUrl()).willReturn(aResponse().withStatus(502)))
		shouldThrow<OAuthProviderUnavailableException> { refresh() }

		wireMock.stubFor(post(anyUrl()).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)))
		shouldThrow<OAuthProviderUnavailableException> { refresh() }

		val unreachable = OAuthHttpClient(properties("http://127.0.0.1:1"), JsonMapper.builder().build())
		shouldThrow<OAuthProviderUnavailableException> { unreachable.refresh(OAuthProvider.GOOGLE, REFRESH_TOKEN, null) }
	}

	@Test
	fun `exception messages never contain tokens, response bodies or the client secret`() {
		val failures =
			listOf(
				aResponse().withStatus(400).withHeader("Content-Type", "application/json").withBody("""{"error": "invalid_grant"}"""),
				aResponse().withStatus(500).withBody("refresh_token=$REFRESH_TOKEN"),
				aResponse().withHeader("Content-Type", "application/json").withBody("""{"refresh_token": "$REFRESH_TOKEN"}"""),
			)
		for (failure in failures) {
			wireMock.stubFor(post(urlPathEqualTo("/google/token")).willReturn(failure))
			val message = shouldThrow<OAuthException> { refresh() }.toString()
			message shouldNotContain REFRESH_TOKEN
			message shouldNotContain CLIENT_SECRET
		}
	}

	@Test
	fun `revocation is skipped without an endpoint and never fails the caller`() {
		client.revoke(OAuthProvider.MICROSOFT, REFRESH_TOKEN)
		wireMock.findAll(postRequestedFor(anyUrl())).size shouldBe 0

		wireMock.stubFor(post(urlPathEqualTo("/google/revoke")).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)))
		client.revoke(OAuthProvider.GOOGLE, REFRESH_TOKEN)
		wireMock.stubFor(post(urlPathEqualTo("/google/revoke")).willReturn(aResponse().withStatus(400)))
		client.revoke(OAuthProvider.GOOGLE, REFRESH_TOKEN)
		wireMock.findAll(postRequestedFor(urlPathEqualTo("/google/revoke"))).size shouldBe 2
	}

	@Test
	fun `the identity comes from the ID token claims`() {
		client.identity(jwt("""{"sub":"abc-123","email":"jane.doe@example.test","name":"Jane Doe"}""")) shouldBe
			OAuthIdentity("abc-123", "jane.doe@example.test", "Jane Doe")
		// Microsoft work accounts often have no email claim, only the UPN.
		client.identity(jwt("""{"sub":"ms-1","preferred_username":"jane@contoso.example"}""")).email shouldBe "jane@contoso.example"
		client.identity(jwt("""{"sub":"ms-2","preferred_username":"jane-phone"}""")) shouldBe OAuthIdentity("ms-2", null, null)
		// Padded base64url is accepted as well.
		val padded = Base64.getUrlEncoder().encodeToString("""{"sub":"p"}""".toByteArray())
		client.identity("e30.$padded.sig").subject shouldBe "p"
	}

	@Test
	fun `a missing or malformed ID token is rejected`() {
		val malformed =
			listOf(
				null,
				"",
				"only-one-part",
				"two.parts",
				"a.b.c.d",
				"e30.!!!not-base64!!!.sig",
				"e30.${encode("not json")}.sig",
				"e30.${encode("[1, 2]")}.sig",
				jwt("""{"email":"jane.doe@example.test"}"""),
				jwt("""{"sub":""}"""),
				jwt("""{"sub":"   "}"""),
				jwt("""{"sub":42}"""),
			)
		for (token in malformed) {
			shouldThrow<OAuthProviderUnavailableException> { client.identity(token) }
		}
	}

	private fun refresh() = client.refresh(OAuthProvider.GOOGLE, REFRESH_TOKEN, null)

	private fun stubTokenBody(body: String) {
		wireMock.stubFor(
			post(urlPathEqualTo("/google/token")).willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(body)),
		)
	}

	companion object {
		const val REFRESH_TOKEN = "fictitious-refresh-token-unit"
		const val CLIENT_SECRET = "fictitious-unit-client-secret"

		@JvmStatic
		val wireMock: WireMockServer = WireMockServer(wireMockConfig().dynamicPort()).apply { start() }

		@JvmStatic
		@AfterAll
		fun stop() = wireMock.stop()

		fun properties(baseUrl: String) =
			OAuthProperties(
				publicUrl = "https://cockpit.example.test",
				google =
					OAuthProperties.Registration(
						clientId = "fictitious-unit-client-id",
						clientSecret = CLIENT_SECRET,
						authorizationUri = "$baseUrl/google/authorize",
						tokenUri = "$baseUrl/google/token",
						revocationUri = "$baseUrl/google/revoke",
					),
				microsoft =
					OAuthProperties.Registration(
						clientId = "fictitious-unit-client-id",
						clientSecret = CLIENT_SECRET,
						authorizationUri = "$baseUrl/microsoft/authorize",
						tokenUri = "$baseUrl/microsoft/token",
					),
			)

		fun encode(value: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray())

		fun jwt(claims: String): String = "${encode("""{"alg":"none"}""")}.${encode(claims)}.fictitious-signature"
	}
}
