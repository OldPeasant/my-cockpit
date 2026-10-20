package ch.sonensei.cockpit.core.oauth

import com.github.tomakehurst.wiremock.client.WireMock.containing
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.get
import java.security.MessageDigest
import java.time.Duration
import java.util.Base64

/** The connect flow: `/start` (PKCE, state bound to the session) and `/callback` against WireMock. */
class OAuthFlowWebTests : OAuthWebTestBase() {
	@Test
	fun `start redirects to the provider with PKCE, state and the combined scopes`() {
		val params = start("google", "MAIL")

		params.getValue("location") shouldStartWith "${wireMock.baseUrl()}/google/authorize?"
		params["response_type"] shouldBe "code"
		params["client_id"] shouldBe "fictitious-google-client-id"
		params["redirect_uri"] shouldBe "https://cockpit.example.test/api/v1/oauth/google/callback"
		params["code_challenge_method"] shouldBe "S256"
		params["access_type"] shouldBe "offline"
		params.getValue("scope").split(' ') shouldBe
			listOf(
				"openid",
				"email",
				"profile",
				"https://www.googleapis.com/auth/calendar",
				"https://www.googleapis.com/auth/contacts",
				"https://mail.google.com/",
			)
		// Only hashes of the state are stored, the PKCE verifier is encrypted.
		val row = jdbc.queryForMap("select state_hash, code_verifier, purpose from oauth_authorization")
		row["state_hash"] shouldBe sha256Hex(params.getValue("state"))
		row["purpose"] shouldBe "MAIL"
	}

	@Test
	fun `start asks Microsoft for Graph and Outlook mail scopes with offline access`() {
		val scopes = start("microsoft").getValue("scope").split(' ')

		scopes shouldBe
			listOf(
				"openid",
				"email",
				"profile",
				"offline_access",
				"https://graph.microsoft.com/Calendars.ReadWrite",
				"https://graph.microsoft.com/Contacts.ReadWrite",
				"https://outlook.office.com/IMAP.AccessAsUser.All",
				"https://outlook.office.com/SMTP.Send",
			)
	}

	@Test
	fun `start needs a browser session, a known provider and a purpose`() {
		mockMvc.get("/api/v1/oauth/google/start") { param("purpose", "CALENDAR") }.andExpect { status { isUnauthorized() } }
		mockMvc
			.get("/api/v1/oauth/yahoo/start") {
				param("purpose", "CALENDAR")
				cookie(SESSION)
			}.andExpect { status { isNotFound() } }
		mockMvc.get("/api/v1/oauth/google/start") { cookie(SESSION) }.andExpect { status { isBadRequest() } }
		mockMvc
			.get("/api/v1/oauth/google/start") {
				param("purpose", "PHOTOS")
				cookie(SESSION)
			}.andExpect { status { isBadRequest() } }
	}

	@Test
	fun `callback exchanges the code with the PKCE verifier and stores the refresh token encrypted`() {
		stubToken()
		val params = start("google", "CONTACTS")

		val target = callback("google", params.getValue("state"))

		target shouldStartWith "/settings/integrations?oauth=connected&provider=google&purpose=CONTACTS&connectionId="
		val request = wireMock.findAll(postRequestedFor(urlPathEqualTo("/google/token"))).single()
		val form = request.bodyAsString
		form shouldContain "code=$CODE"
		form shouldContain "client_id=fictitious-google-client-id"
		form shouldContain "redirect_uri=https%3A%2F%2Fcockpit.example.test%2Fapi%2Fv1%2Foauth%2Fgoogle%2Fcallback"
		val verifier = Regex("code_verifier=([A-Za-z0-9_-]+)").find(form)!!.groupValues[1]
		Base64.getUrlEncoder().withoutPadding().encodeToString(sha256(verifier)) shouldBe params["code_challenge"]

		val row = jdbc.queryForMap("select provider, subject, email, display_name, status, refresh_token from oauth_connection")
		row["provider"] shouldBe "GOOGLE"
		row["subject"] shouldBe SUBJECT
		row["email"] shouldBe EMAIL
		row["display_name"] shouldBe "Jane Doe"
		row["status"] shouldBe "CONNECTED"
		(row["refresh_token"] as String) shouldNotContain REFRESH_TOKEN
		// The pending flow is consumed.
		jdbc.queryForList("select id from oauth_authorization").shouldBeEmpty()
	}

	@Test
	fun `a state that was never issued is rejected without a token request`() {
		stubToken()
		start()

		callback("google", "forged-state") shouldBe "/settings/integrations?oauth=error&provider=google&error=invalid_state"
		callback("google", null) shouldBe "/settings/integrations?oauth=error&provider=google&error=invalid_state"

		wireMock.findAll(postRequestedFor(urlPathEqualTo("/google/token"))).shouldBeEmpty()
		connectionCount() shouldBe 0
	}

	@Test
	fun `a state from another browser session is rejected and consumed`() {
		stubToken()
		val state = start(session = SESSION).getValue("state")

		callback("google", state, session = OTHER_SESSION) shouldBe
			"/settings/integrations?oauth=error&provider=google&error=invalid_state&purpose=CALENDAR"
		// Single use: the rightful session cannot complete it afterwards either.
		callback("google", state, session = SESSION) shouldContain "error=invalid_state"

		wireMock.findAll(postRequestedFor(urlPathEqualTo("/google/token"))).shouldBeEmpty()
		connectionCount() shouldBe 0
	}

	@Test
	fun `a state is rejected at the other provider, after expiry and on replay`() {
		stubToken("google")
		stubToken("microsoft")
		callback("microsoft", start("google").getValue("state")) shouldContain "error=invalid_state"

		val expired = start().getValue("state")
		clock.advance(Duration.ofMinutes(11))
		callback("google", expired) shouldContain "error=invalid_state"

		val used = start().getValue("state")
		callback("google", used) shouldContain "oauth=connected"
		callback("google", used) shouldContain "error=invalid_state"
		connectionCount() shouldBe 1
	}

	@Test
	fun `a denied consent or a failed code exchange creates no connection`() {
		callback("google", start().getValue("state"), code = null, error = "access_denied") shouldBe
			"/settings/integrations?oauth=error&provider=google&error=access_denied&purpose=CALENDAR"

		stubTokenError(grantType = "authorization_code", error = "invalid_grant")
		callback("google", start().getValue("state")) shouldContain "error=token_exchange_failed"

		stubToken(refreshToken = null)
		callback("google", start().getValue("state")) shouldContain "error=no_refresh_token"
		connectionCount() shouldBe 0
	}

	@Test
	fun `connecting the same account again updates its one connection`() {
		val first = connect()
		stubToken(refreshToken = "fictitious-refresh-token-2")

		val target = callback("google", start(purpose = "MAIL").getValue("state"))

		target shouldContain "connectionId=$first"
		connectionCount() shouldBe 1
		connect("microsoft")
		connectionCount() shouldBe 2
	}

	@Test
	fun `Microsoft redeems the code for Graph only`() {
		connect("microsoft")

		wireMock.verify(
			postRequestedFor(urlPathEqualTo("/microsoft/token"))
				.withRequestBody(containing("scope=openid+email+profile+offline_access+https%3A%2F%2Fgraph.microsoft.com%2FCalendars.ReadWrite")),
		)
	}

	private fun connectionCount(): Int = jdbc.queryForObject("select count(*) from oauth_connection", Int::class.java)!!

	private fun sha256(value: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())

	private fun sha256Hex(value: String): String = sha256(value).joinToString("") { "%02x".format(it) }
}
