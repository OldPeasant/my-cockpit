package ch.sonensei.cockpit.core.oauth

import ch.sonensei.cockpit.core.oauth.domain.OAuthReconnectRequiredException
import ch.sonensei.cockpit.core.oauth.domain.OAuthTokenProvider
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders.ETAG
import org.springframework.http.HttpHeaders.IF_MATCH
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import java.time.Duration
import java.util.UUID

/**
 * Boundaries the other OAuth tests leave open: the exact flow lifetime, Google's offline-consent parameters, the
 * reconnect-required status as the API shows it, and a connection without a stored refresh token. Same Spring
 * configuration as the other OAuth tests, so it adds no other context.
 */
class OAuthBoundaryWebTests : OAuthWebTestBase() {
	@Autowired private lateinit var tokens: OAuthTokenProvider

	@Test
	fun `a flow is accepted just before its 10 minute lifetime ends and rejected exactly at it`() {
		stubToken()
		val early = start().getValue("state")
		clock.advance(Duration.ofMinutes(10).minusSeconds(1))
		callback("google", early) shouldContain "oauth=connected"

		val late = start().getValue("state")
		clock.advance(Duration.ofMinutes(10))
		callback("google", late) shouldContain "error=invalid_state"
		wireMock.findAll(postRequestedFor(urlPathEqualTo("/google/token"))).size shouldBe 1
	}

	@Test
	fun `Google is asked for offline access with a consent prompt so it issues a refresh token`() {
		val params = start("google")

		params["access_type"] shouldBe "offline"
		params["prompt"] shouldBe "consent"
		params["include_granted_scopes"] shouldBe "true"
		params.getValue("code_challenge").length shouldBe 43
	}

	@Test
	fun `the callback of an unknown provider is not found`() {
		mockMvc
			.get("/api/v1/oauth/yahoo/callback") {
				param("state", "fictitious-state")
				param("code", CODE)
				cookie(SESSION)
			}.andExpect { status { isNotFound() } }
	}

	@Test
	fun `the API shows a revoked connection as reconnect required with a new ETag`() {
		val id = connect()
		clock.advance(Duration.ofHours(2))
		stubTokenError(grantType = "refresh_token", error = "invalid_grant")
		shouldThrow<OAuthReconnectRequiredException> { tokens.accessToken(UUID.fromString(id)) }

		mockMvc.get("/api/v1/oauth/connections").andExpect {
			status { isOk() }
			jsonPath("$[0].id") { value(id) }
			jsonPath("$[0].status") { value("RECONNECT_REQUIRED") }
			jsonPath("$[0].lastError") { value("invalid_grant") }
			jsonPath("$[0].version") { value(1) }
		}
		mockMvc.get("/api/v1/oauth/connections/$id").andExpect { header { string(ETAG, "\"1\"") } }
		// The ETag the client held before the status change is stale.
		mockMvc
			.delete("/api/v1/oauth/connections/$id") {
				with(csrf())
				header(IF_MATCH, "\"0\"")
			}.andExpect { status { isPreconditionFailed() } }
	}

	@Test
	fun `a connection without a stored refresh token needs a reconnect and makes no token request`() {
		// E.g. restored from a backup without secrets (see the migration's comment).
		val id = UUID.fromString(connect())
		jdbc.update("update oauth_connection set refresh_token = null")
		clock.advance(Duration.ofHours(2))
		wireMock.resetRequests()

		shouldThrow<OAuthReconnectRequiredException> { tokens.accessToken(id) }

		wireMock.findAll(postRequestedFor(urlPathEqualTo("/google/token"))).shouldBeEmpty()
	}
}
