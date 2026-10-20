package ch.sonensei.cockpit.core.oauth

import ch.sonensei.cockpit.core.oauth.domain.OAuthConnectionDisconnected
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.containing
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders.ETAG
import org.springframework.http.HttpHeaders.IF_MATCH
import org.springframework.security.test.context.support.WithAnonymousUser
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.event.ApplicationEvents
import org.springframework.test.context.event.RecordApplicationEvents
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import java.util.UUID

/**
 * Providers, connection list with status, and disconnect (with revocation at Google). Events are recorded with
 * [RecordApplicationEvents] rather than an imported listener, so this class shares the Spring context of the other
 * OAuth tests (every extra context holds its own connection pool to the shared Postgres container).
 */
@RecordApplicationEvents
class OAuthConnectionsWebTests : OAuthWebTestBase() {
	@Test
	fun `providers tells which OAuth apps are configured`() {
		mockMvc.get("/api/v1/oauth/providers").andExpect {
			status { isOk() }
			jsonPath("$[0].provider") { value("GOOGLE") }
			jsonPath("$[0].slug") { value("google") }
			jsonPath("$[0].configured") { value(true) }
			jsonPath("$[1].provider") { value("MICROSOFT") }
		}
	}

	@Test
	fun `the list shows each connection with its status and never a token`() {
		val google = connect("google")
		val microsoft = connect("microsoft")

		val body =
			mockMvc
				.get("/api/v1/oauth/connections")
				.andExpect {
					status { isOk() }
					jsonPath("$.length()") { value(2) }
					jsonPath("$[0].id") { value(google) }
					jsonPath("$[0].provider") { value("GOOGLE") }
					jsonPath("$[0].email") { value(EMAIL) }
					jsonPath("$[0].status") { value("CONNECTED") }
					jsonPath("$[0].scopes[2]") { value("https://www.googleapis.com/auth/calendar") }
					jsonPath("$[1].id") { value(microsoft) }
				}.andReturn()
				.response.contentAsString
		body shouldNotContain REFRESH_TOKEN
		body shouldNotContain ACCESS_TOKEN

		mockMvc.get("/api/v1/oauth/connections/$google").andExpect {
			status { isOk() }
			header { string(ETAG, "\"0\"") }
			jsonPath("$.displayName") { value("Jane Doe") }
		}
		mockMvc.get("/api/v1/oauth/connections/${UUID.randomUUID()}").andExpect { status { isNotFound() } }
	}

	@Test
	fun `disconnect needs If-Match, revokes at Google and removes the connection`(events: ApplicationEvents) {
		val id = connect("google")
		wireMock.stubFor(post(urlPathEqualTo("/google/revoke")).willReturn(aResponse().withStatus(200)))
		events.stream(OAuthConnectionDisconnected::class.java).count() shouldBe 0

		mockMvc.delete("/api/v1/oauth/connections/$id") { with(csrf()) }.andExpect { status { isEqualTo(428) } }
		mockMvc
			.delete("/api/v1/oauth/connections/$id") {
				with(csrf())
				header(IF_MATCH, "\"7\"")
			}.andExpect { status { isPreconditionFailed() } }
		mockMvc.delete("/api/v1/oauth/connections/$id") { header(IF_MATCH, "\"0\"") }.andExpect { status { isForbidden() } }

		mockMvc
			.delete("/api/v1/oauth/connections/$id") {
				with(csrf())
				header(IF_MATCH, "\"0\"")
			}.andExpect { status { isNoContent() } }

		wireMock.verify(postRequestedFor(urlPathEqualTo("/google/revoke")).withRequestBody(containing("token=$REFRESH_TOKEN")))
		events.stream(OAuthConnectionDisconnected::class.java).map { it.connectionId.toString() }.toList() shouldContainExactly listOf(id)
		mockMvc.get("/api/v1/oauth/connections/$id").andExpect { status { isNotFound() } }
		mockMvc
			.delete("/api/v1/oauth/connections/$id") {
				with(csrf())
				header(IF_MATCH, "\"0\"")
			}.andExpect { status { isNotFound() } }
	}

	@Test
	fun `a failing revocation still disconnects, Microsoft is not called`() {
		val google = connect("google")
		val microsoft = connect("microsoft")
		wireMock.stubFor(post(urlPathEqualTo("/google/revoke")).willReturn(aResponse().withStatus(500)))
		wireMock.resetRequests()

		for (id in listOf(google, microsoft)) {
			mockMvc
				.delete("/api/v1/oauth/connections/$id") {
					with(csrf())
					header(IF_MATCH, "*")
				}.andExpect { status { isNoContent() } }
		}

		wireMock.findAll(postRequestedFor(urlPathMatching("/microsoft/.*"))).size shouldBe 0
		wireMock.findAll(postRequestedFor(urlPathEqualTo("/google/revoke"))).size shouldBe 1
		jdbc.queryForObject("select count(*) from oauth_connection", Int::class.java) shouldBe 0
	}

	@Test
	@WithAnonymousUser
	fun `every OAuth endpoint needs a login`() {
		for (url in listOf(
			"/api/v1/oauth/providers",
			"/api/v1/oauth/connections",
			"/api/v1/oauth/google/start?purpose=MAIL",
			"/api/v1/oauth/google/callback",
		)) {
			mockMvc.get(url).andExpect { status { isUnauthorized() } }
		}
	}
}
