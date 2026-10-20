package ch.sonensei.cockpit.core.oauth

import ch.sonensei.cockpit.core.auth.WithCockpitUser
import ch.sonensei.cockpit.core.auth.infra.SessionCookies
import ch.sonensei.cockpit.core.persistence.MutableClock
import ch.sonensei.cockpit.core.persistence.MutableClockTestConfiguration
import ch.sonensei.cockpit.support.PostgresTestBase
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.containing
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import io.kotest.matchers.nulls.shouldNotBeNull
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.web.util.UriComponentsBuilder
import java.util.Base64

/**
 * Spring tests of the OAuth flows with WireMock as the authorization server of both providers (Google under
 * `/google`, Microsoft under `/microsoft`). The owner is logged in through [WithCockpitUser]; [SESSION] is the browser
 * session cookie the flows are bound to.
 *
 * The WireMock properties give these classes a Spring context nobody else shares. [DirtiesContext] closes it (and its
 * Hikari pool) after each class: cached, it held its connections to the shared Postgres container for the rest of the
 * suite and pushed `ProfileTests`/`FlywayConfigurationTests` over `max_connections` ("too many clients already").
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithCockpitUser
@Import(MutableClockTestConfiguration::class)
@DirtiesContext
abstract class OAuthWebTestBase : PostgresTestBase() {
	@Autowired protected lateinit var mockMvc: MockMvc

	@Autowired protected lateinit var jdbc: JdbcTemplate

	@Autowired protected lateinit var clock: MutableClock

	@BeforeEach
	fun cleanState() {
		clock.set(MutableClockTestConfiguration.START)
		jdbc.update("delete from oauth_authorization")
		jdbc.update("delete from oauth_connection")
		wireMock.resetAll()
	}

	/** Starts a flow and returns the `state` and `code_challenge` the browser carries to the provider. */
	protected fun start(
		provider: String = "google",
		purpose: String = "CALENDAR",
		session: Cookie = SESSION,
	): Map<String, String> {
		val location =
			mockMvc
				.get("/api/v1/oauth/$provider/start") {
					param("purpose", purpose)
					cookie(session)
				}.andReturn()
				.response
				.getHeader("Location")
				.shouldNotBeNull()
		return UriComponentsBuilder
			.fromUriString(location)
			.build()
			.queryParams
			.toSingleValueMap()
			.mapValues { java.net.URLDecoder.decode(it.value, Charsets.UTF_8) } + ("location" to location)
	}

	/** Calls the callback like the provider's redirect would and returns the redirect target. */
	protected fun callback(
		provider: String = "google",
		state: String?,
		code: String? = CODE,
		session: Cookie? = SESSION,
		error: String? = null,
	): String =
		mockMvc
			.get("/api/v1/oauth/$provider/callback") {
				state?.let { param("state", it) }
				code?.let { param("code", it) }
				error?.let { param("error", it) }
				session?.let { cookie(it) }
			}.andExpect { status { isFound() } }
			.andReturn()
			.response
			.getHeader("Location")
			.shouldNotBeNull()

	/** Stubs a successful token response for [grantType] at [provider]'s token endpoint. */
	protected fun stubToken(
		provider: String = "google",
		grantType: String = "authorization_code",
		accessToken: String = ACCESS_TOKEN,
		refreshToken: String? = REFRESH_TOKEN,
		expiresIn: Long = 3600,
		subject: String = SUBJECT,
		scope: String = "openid email https://www.googleapis.com/auth/calendar",
	) {
		val fields =
			buildList {
				add(""""access_token": "$accessToken"""")
				add(""""token_type": "Bearer"""")
				add(""""expires_in": $expiresIn""")
				add(""""scope": "$scope"""")
				refreshToken?.let { add(""""refresh_token": "$it"""") }
				if (grantType == "authorization_code") add(""""id_token": "${idToken(subject)}"""")
			}
		wireMock.stubFor(
			post(urlPathEqualTo("/$provider/token"))
				.withRequestBody(containing("grant_type=$grantType"))
				.willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(fields.joinToString(",", "{", "}"))),
		)
	}

	protected fun stubTokenError(
		provider: String = "google",
		grantType: String,
		error: String,
		status: Int = 400,
	) {
		wireMock.stubFor(
			post(urlPathEqualTo("/$provider/token"))
				.withRequestBody(containing("grant_type=$grantType"))
				.willReturn(aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody("""{"error": "$error"}""")),
		)
	}

	/** Runs a whole successful connect flow and returns the connection id. */
	protected fun connect(
		provider: String = "google",
		purpose: String = "CALENDAR",
	): String {
		stubToken(provider)
		val target = callback(provider, start(provider, purpose).getValue("state"))
		return UriComponentsBuilder
			.fromUriString(target)
			.build()
			.queryParams
			.getFirst("connectionId")
			.shouldNotBeNull()
	}

	companion object {
		val SESSION = Cookie(SessionCookies.NAME, "fictitious-session-token-of-jane")
		val OTHER_SESSION = Cookie(SessionCookies.NAME, "fictitious-session-token-of-someone-else")
		const val CODE = "fictitious-authorization-code"
		const val ACCESS_TOKEN = "fictitious-access-token-1"
		const val REFRESH_TOKEN = "fictitious-refresh-token-1"
		const val SUBJECT = "1180000000000000000042"
		const val EMAIL = "jane.doe@example.test"

		/** An unsigned ID token (the server trusts the TLS channel to the token endpoint, not the signature). */
		fun idToken(subject: String = SUBJECT): String {
			val encoder = Base64.getUrlEncoder().withoutPadding()
			val header = encoder.encodeToString("""{"alg":"RS256","typ":"JWT"}""".toByteArray())
			val payload = encoder.encodeToString("""{"sub":"$subject","email":"$EMAIL","name":"Jane Doe"}""".toByteArray())
			return "$header.$payload.fictitious-signature"
		}

		@JvmStatic
		val wireMock: WireMockServer = WireMockServer(wireMockConfig().dynamicPort()).apply { start() }

		@JvmStatic
		@DynamicPropertySource
		fun oauthProperties(registry: DynamicPropertyRegistry) {
			registry.add("cockpit.oauth.public-url") { "https://cockpit.example.test" }
			for (provider in listOf("google", "microsoft")) {
				registry.add("cockpit.oauth.$provider.client-id") { "fictitious-$provider-client-id" }
				registry.add("cockpit.oauth.$provider.client-secret") { "fictitious-$provider-client-secret" }
				registry.add("cockpit.oauth.$provider.authorization-uri") { "${wireMock.baseUrl()}/$provider/authorize" }
				registry.add("cockpit.oauth.$provider.token-uri") { "${wireMock.baseUrl()}/$provider/token" }
			}
			registry.add("cockpit.oauth.google.revocation-uri") { "${wireMock.baseUrl()}/google/revoke" }
			registry.add("cockpit.oauth.microsoft.revocation-uri") { "" }
		}
	}
}
