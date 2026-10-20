package ch.sonensei.cockpit.core.oauth

import ch.sonensei.cockpit.core.crypto.SecretCipher
import ch.sonensei.cockpit.core.oauth.domain.OAuthConnectionNotFoundException
import ch.sonensei.cockpit.core.oauth.domain.OAuthConnectionStatus
import ch.sonensei.cockpit.core.oauth.domain.OAuthGrantRejectedException
import ch.sonensei.cockpit.core.oauth.domain.OAuthProviderUnavailableException
import ch.sonensei.cockpit.core.oauth.domain.OAuthReconnectRequiredException
import ch.sonensei.cockpit.core.oauth.domain.OAuthResource
import ch.sonensei.cockpit.core.oauth.domain.OAuthTokenProvider
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.containing
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainOnly
import io.kotest.matchers.ints.shouldBeInRange
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.http.HttpHeaders.IF_MATCH
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import java.time.Duration
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Edge cases of the connect flow and the token provider against WireMock: malformed provider responses, reconnects,
 * concurrent refreshes, and secrets that must never reach the logs or the API. Same Spring configuration as the other
 * OAuth tests, so the context is reused.
 */
@ExtendWith(OutputCaptureExtension::class)
class OAuthEdgeCaseWebTests : OAuthWebTestBase() {
	@Autowired private lateinit var tokens: OAuthTokenProvider

	@Autowired private lateinit var cipher: SecretCipher

	@Test
	fun `the stored refresh token decrypts to the provider's token`() {
		connect()

		val stored = jdbc.queryForObject("select refresh_token from oauth_connection", String::class.java)!!

		stored shouldNotBe REFRESH_TOKEN
		cipher.decrypt(stored) shouldBe REFRESH_TOKEN
		// The PKCE verifier of a pending flow is encrypted too.
		start()
		jdbc.queryForObject("select code_verifier from oauth_authorization", String::class.java)!!.let {
			cipher.decrypt(it).length shouldBeInRange
				43..128
		}
	}

	@Test
	fun `every start uses a fresh state and PKCE challenge and cleans up expired flows`() {
		val first = start()
		val second = start()
		first.getValue("state") shouldNotBe second.getValue("state")
		first.getValue("code_challenge") shouldNotBe second.getValue("code_challenge")
		start("microsoft")["prompt"] shouldBe "select_account"
		pendingCount() shouldBe 3

		clock.advance(Duration.ofMinutes(11))
		start()

		pendingCount() shouldBe 1
	}

	@Test
	fun `a callback without a session cookie is rejected and consumes the flow`() {
		stubToken()
		val state = start().getValue("state")

		callback("google", state, session = null) shouldContain "error=invalid_state"

		callback("google", state) shouldContain "error=invalid_state"
		wireMock.findAll(postRequestedFor(urlPathEqualTo("/google/token"))).shouldBeEmpty()
	}

	@Test
	fun `the provider's error value is never reflected into the redirect`() {
		val target = callback("google", start().getValue("state"), code = null, error = "<script>alert(1)</script>")

		target shouldBe "/settings/integrations?oauth=error&provider=google&error=access_denied&purpose=CALENDAR"
		// A code does not count when the provider also reports an error.
		callback("google", start().getValue("state"), code = CODE, error = "access_denied") shouldContain "error=access_denied"
		wireMock.findAll(postRequestedFor(urlPathEqualTo("/google/token"))).shouldBeEmpty()
	}

	@Test
	fun `a malformed ID token or a missing access token fails the code exchange`() {
		val responses =
			listOf(
				"""{"access_token": "$ACCESS_TOKEN", "refresh_token": "$REFRESH_TOKEN", "expires_in": 3600}""",
				"""{"access_token": "$ACCESS_TOKEN", "refresh_token": "$REFRESH_TOKEN", "id_token": "not-a-jwt"}""",
				"""{"access_token": "$ACCESS_TOKEN", "refresh_token": "$REFRESH_TOKEN", "id_token": "e30.e30.sig"}""",
				"""{"refresh_token": "$REFRESH_TOKEN", "id_token": "${idToken()}"}""",
			)
		for (body in responses) {
			stubTokenBody(body)
			callback("google", start().getValue("state")) shouldBe
				"/settings/integrations?oauth=error&provider=google&error=token_exchange_failed&purpose=CALENDAR"
		}
		wireMock.stubFor(post(urlPathEqualTo("/google/token")).willReturn(aResponse().withStatus(503).withBody("<html>down</html>")))
		callback("google", start().getValue("state")) shouldContain "error=token_exchange_failed"

		connectionCount() shouldBe 0
	}

	@Test
	fun `empty state or code parameters are treated as missing`() {
		stubToken()
		start()
		callback("google", "") shouldBe "/settings/integrations?oauth=error&provider=google&error=invalid_state"
		callback("google", start().getValue("state"), code = "") shouldContain "error=access_denied"

		wireMock.findAll(postRequestedFor(urlPathEqualTo("/google/token"))).shouldBeEmpty()
	}

	@Test
	fun `missing scope and profile claims fall back to the requested scopes and the known profile`() {
		val id = connect()
		val minimalIdToken =
			idToken().split('.').let { (header, _, signature) ->
				val payload =
					Base64
						.getUrlEncoder()
						.withoutPadding()
						.encodeToString("""{"sub":"$SUBJECT"}""".toByteArray())
				"$header.$payload.$signature"
			}
		stubTokenBody("""{"access_token": "$ACCESS_TOKEN", "expires_in": 3600, "id_token": "$minimalIdToken"}""")

		callback("google", start().getValue("state")) shouldContain "connectionId=$id"

		val info = tokens.connection(UUID.fromString(id)).shouldNotBeNull()
		info.email shouldBe EMAIL
		info.displayName shouldBe "Jane Doe"
		info.scopes shouldBe start().getValue("scope").split(' ')
	}

	@Test
	fun `reconnecting without a new refresh token keeps the stored one`() {
		val id = UUID.fromString(connect())
		stubToken(refreshToken = null)

		callback("google", start().getValue("state")) shouldContain "connectionId=$id"

		cipher.decrypt(jdbc.queryForObject("select refresh_token from oauth_connection", String::class.java)!!) shouldBe REFRESH_TOKEN
	}

	@Test
	fun `reconnecting with a new refresh token makes the next refresh use it`() {
		val id = UUID.fromString(connect())
		stubToken(refreshToken = "fictitious-refresh-token-2", accessToken = "fictitious-access-token-reconnect")
		callback("google", start().getValue("state"))
		// The reconnect's access token replaces the cached one.
		tokens.accessToken(id).value shouldBe "fictitious-access-token-reconnect"
		wireMock.resetRequests()
		stubToken(grantType = "refresh_token", accessToken = "fictitious-access-token-3", refreshToken = null)

		clock.advance(Duration.ofHours(2))
		tokens.accessToken(id).value shouldBe "fictitious-access-token-3"

		wireMock.verify(
			1,
			postRequestedFor(urlPathEqualTo("/google/token")).withRequestBody(containing("refresh_token=fictitious-refresh-token-2")),
		)
	}

	@Test
	fun `a reconnect without a refresh token cannot repair a revoked connection`() {
		val id = UUID.fromString(connect())
		clock.advance(Duration.ofHours(2))
		stubTokenError(grantType = "refresh_token", error = "invalid_grant")
		shouldThrow<OAuthReconnectRequiredException> { tokens.accessToken(id) }
		stubToken(refreshToken = null)

		callback("google", start().getValue("state")) shouldContain "error=no_refresh_token"

		tokens.connection(id).shouldNotBeNull().status shouldBe OAuthConnectionStatus.RECONNECT_REQUIRED
		shouldThrow<OAuthReconnectRequiredException> { tokens.accessToken(id) }
	}

	@Test
	fun `a refresh with expires_in as a string uses that lifetime`() {
		val id = UUID.fromString(connect())
		clock.advance(Duration.ofHours(2))
		stubTokenBody("""{"access_token": "fictitious-access-token-2", "token_type": "Bearer", "expires_in": "1200"}""", "refresh_token")

		tokens.accessToken(id).expiresAt shouldBe clock.instant().plusSeconds(1200)

		// Reused until 5 minutes before expiry.
		clock.advance(Duration.ofMinutes(14))
		tokens.accessToken(id).value shouldBe "fictitious-access-token-2"
		wireMock.verify(1, postRequestedFor(urlPathEqualTo("/google/token")).withRequestBody(containing("grant_type=refresh_token")))
	}

	@Test
	fun `a short-lived token is refreshed after half its lifetime`() {
		val id = UUID.fromString(connect())
		clock.advance(Duration.ofHours(2))
		stubToken(grantType = "refresh_token", accessToken = "fictitious-access-token-short", refreshToken = null, expiresIn = 120)
		tokens.accessToken(id).value shouldBe "fictitious-access-token-short"

		clock.advance(Duration.ofSeconds(59))
		tokens.accessToken(id)
		wireMock.verify(1, postRequestedFor(urlPathEqualTo("/google/token")).withRequestBody(containing("grant_type=refresh_token")))

		clock.advance(Duration.ofSeconds(1))
		tokens.accessToken(id)
		wireMock.verify(2, postRequestedFor(urlPathEqualTo("/google/token")).withRequestBody(containing("grant_type=refresh_token")))
	}

	@Test
	fun `a refresh response without an access token or with a non-JSON error leaves the connection usable`() {
		val id = UUID.fromString(connect())
		clock.advance(Duration.ofHours(2))

		stubTokenBody("""{"token_type": "Bearer", "expires_in": 3600}""", "refresh_token")
		shouldThrow<OAuthProviderUnavailableException> { tokens.accessToken(id) }
		tokens.connection(id).shouldNotBeNull().let {
			it.status shouldBe OAuthConnectionStatus.CONNECTED
			it.lastError shouldBe null
		}

		wireMock.stubFor(
			post(urlPathEqualTo("/google/token"))
				.willReturn(aResponse().withStatus(400).withHeader("Content-Type", "text/html").withBody("<html>Bad Request</html>")),
		)
		shouldThrow<OAuthGrantRejectedException> { tokens.accessToken(id) }.error shouldBe "invalid_request"
		tokens.connection(id).shouldNotBeNull().let {
			it.status shouldBe OAuthConnectionStatus.CONNECTED
			it.lastError shouldBe "invalid_request"
		}
		cipher.decrypt(jdbc.queryForObject("select refresh_token from oauth_connection", String::class.java)!!) shouldBe REFRESH_TOKEN

		// The next successful refresh clears the error.
		stubToken(grantType = "refresh_token", accessToken = "fictitious-access-token-2", refreshToken = null)
		tokens.accessToken(id).value shouldBe "fictitious-access-token-2"
		tokens.connection(id).shouldNotBeNull().lastError shouldBe null
	}

	@Test
	fun `concurrent requests for an expired token refresh it once`() {
		val id = UUID.fromString(connect())
		clock.advance(Duration.ofHours(2))
		wireMock.stubFor(
			post(urlPathEqualTo("/google/token"))
				.withRequestBody(containing("grant_type=refresh_token"))
				.willReturn(
					aResponse()
						.withHeader("Content-Type", "application/json")
						.withBody("""{"access_token": "fictitious-access-token-2", "expires_in": 3600}""")
						.withFixedDelay(300),
				),
		)

		val values = concurrently(4) { tokens.accessToken(id).value }

		values shouldContainOnly listOf("fictitious-access-token-2")
		wireMock.verify(1, postRequestedFor(urlPathEqualTo("/google/token")).withRequestBody(containing("grant_type=refresh_token")))
	}

	@Test
	fun `concurrent Microsoft refreshes for Graph and mail are serialized so the second uses the rotated token`() {
		val id = UUID.fromString(connect("microsoft"))
		clock.advance(Duration.ofHours(2))
		for ((used, rotated) in listOf(
			REFRESH_TOKEN to "fictitious-refresh-token-2",
			"fictitious-refresh-token-2" to "fictitious-refresh-token-3",
		)) {
			wireMock.stubFor(
				post(urlPathEqualTo("/microsoft/token"))
					.withRequestBody(containing("refresh_token=$used"))
					.willReturn(
						aResponse()
							.withHeader("Content-Type", "application/json")
							.withBody("""{"access_token": "fictitious-access-$used", "refresh_token": "$rotated", "expires_in": 3600}""")
							.withFixedDelay(300),
					),
			)
		}

		concurrently(2) { index -> tokens.accessToken(id, if (index == 0) OAuthResource.API else OAuthResource.MAIL) }

		val used =
			wireMock
				.findAll(postRequestedFor(urlPathEqualTo("/microsoft/token")).withRequestBody(containing("grant_type=refresh_token")))
				.map { Regex("refresh_token=([a-z0-9-]+)").find(it.bodyAsString)!!.groupValues[1] }
		used.sorted() shouldBe listOf(REFRESH_TOKEN, "fictitious-refresh-token-2")
		cipher.decrypt(jdbc.queryForObject("select refresh_token from oauth_connection", String::class.java)!!) shouldBe
			"fictitious-refresh-token-3"
	}

	@Test
	fun `disconnecting a revoked connection makes no revocation call and drops cached tokens`() {
		val google = UUID.fromString(connect())
		tokens.accessToken(google).value shouldBe ACCESS_TOKEN
		clock.advance(Duration.ofHours(2))
		stubTokenError(grantType = "refresh_token", error = "invalid_grant")
		shouldThrow<OAuthReconnectRequiredException> { tokens.accessToken(google) }
		val version = tokens.connection(google).shouldNotBeNull().version
		wireMock.stubFor(post(urlPathEqualTo("/google/revoke")).willReturn(aResponse().withStatus(200)))

		disconnect(google, version)

		wireMock.findAll(postRequestedFor(urlPathEqualTo("/google/revoke"))).shouldBeEmpty()

		// A connected one: its cached access token is gone after the disconnect.
		val other = UUID.fromString(connect())
		tokens.accessToken(other).value shouldBe ACCESS_TOKEN
		disconnect(other, 0)
		shouldThrow<OAuthConnectionNotFoundException> { tokens.accessToken(other) }
	}

	@Test
	fun `a disconnect during a refresh waits for it and leaves no cached token behind`() {
		val id = UUID.fromString(connect())
		clock.advance(Duration.ofHours(2))
		stubSlowRefresh(refreshToken = null)
		val executor = Executors.newSingleThreadExecutor()
		try {
			val refresh = executor.submit<String> { tokens.accessToken(id).value }
			awaitRefreshRequest()

			disconnect(id, 0)

			refresh.get(30, TimeUnit.SECONDS) shouldBe "fictitious-access-token-2"
			shouldThrow<OAuthConnectionNotFoundException> { tokens.accessToken(id) }
		} finally {
			executor.shutdownNow()
		}
	}

	@Test
	fun `a reconnect during a refresh keeps the new refresh token, not the one rotated from the old chain`() {
		val id = UUID.fromString(connect())
		clock.advance(Duration.ofHours(2))
		stubSlowRefresh(refreshToken = "fictitious-refresh-token-rotated")
		val executor = Executors.newSingleThreadExecutor()
		try {
			val refresh = executor.submit<String> { tokens.accessToken(id).value }
			awaitRefreshRequest()

			stubToken(accessToken = "fictitious-access-token-3", refreshToken = "fictitious-refresh-token-3")
			callback(state = start().getValue("state")) shouldContain "connectionId=$id"

			refresh.get(30, TimeUnit.SECONDS) shouldBe "fictitious-access-token-2"
			cipher.decrypt(jdbc.queryForObject("select refresh_token from oauth_connection", String::class.java)!!) shouldBe
				"fictitious-refresh-token-3"
			tokens.accessToken(id).value shouldBe "fictitious-access-token-3"
		} finally {
			executor.shutdownNow()
		}
	}

	@Test
	fun `no token, code, verifier, client secret or session token reaches the logs or the API`(output: CapturedOutput) {
		val id = UUID.fromString(connect("microsoft"))
		clock.advance(Duration.ofHours(2))
		stubToken(
			"microsoft",
			grantType = "refresh_token",
			accessToken = "fictitious-access-token-2",
			refreshToken = "fictitious-refresh-token-2",
		)
		tokens.accessToken(id, OAuthResource.MAIL)
		clock.advance(Duration.ofHours(2))
		stubTokenError("microsoft", grantType = "refresh_token", error = "invalid_client", status = 401)
		shouldThrow<OAuthGrantRejectedException> { tokens.accessToken(id) }
		stubTokenError("microsoft", grantType = "authorization_code", error = "invalid_grant")
		callback("microsoft", start("microsoft").getValue("state"))
		val bodies =
			listOf("/api/v1/oauth/connections", "/api/v1/oauth/connections/$id", "/api/v1/oauth/providers").map {
				mockMvc
					.get(it)
					.andReturn()
					.response.contentAsString
			}
		stubTokenError("microsoft", grantType = "refresh_token", error = "invalid_grant")
		shouldThrow<OAuthReconnectRequiredException> { tokens.accessToken(id) }
		disconnect(id, tokens.connection(id).shouldNotBeNull().version)

		val secrets =
			listOf(
				ACCESS_TOKEN,
				REFRESH_TOKEN,
				"fictitious-access-token-2",
				"fictitious-refresh-token-2",
				CODE,
				"fictitious-microsoft-client-secret",
				SESSION.value,
			)
		for (secret in secrets) {
			output.all shouldNotContain secret
			bodies.forEach { it shouldNotContain secret }
		}
	}

	private fun disconnect(
		id: UUID,
		version: Long,
	) {
		mockMvc
			.delete("/api/v1/oauth/connections/$id") {
				with(csrf())
				header(IF_MATCH, "\"$version\"")
			}.andExpect { status { isNoContent() } }
	}

	/** Stubs a Google refresh that answers `fictitious-access-token-2` after a second, long enough to act meanwhile. */
	private fun stubSlowRefresh(refreshToken: String?) {
		val rotated = refreshToken?.let { """, "refresh_token": "$it"""" } ?: ""
		wireMock.stubFor(
			post(urlPathEqualTo("/google/token"))
				.withRequestBody(containing("grant_type=refresh_token"))
				.willReturn(
					aResponse()
						.withHeader("Content-Type", "application/json")
						.withBody("""{"access_token": "fictitious-access-token-2", "expires_in": 3600$rotated}""")
						.withFixedDelay(1000),
				),
		)
	}

	/** Waits until the refresh request reached WireMock, so the refresh is in flight. */
	private fun awaitRefreshRequest() {
		val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
		val refreshes = postRequestedFor(urlPathEqualTo("/google/token")).withRequestBody(containing("grant_type=refresh_token"))
		while (wireMock.findAll(refreshes).isEmpty()) {
			check(System.nanoTime() < deadline) { "The refresh request never reached WireMock" }
			Thread.sleep(10)
		}
	}

	/** Runs [block] on [threads] threads released at the same moment and returns their results. */
	private fun <T> concurrently(
		threads: Int,
		block: (Int) -> T,
	): List<T> {
		val executor = Executors.newFixedThreadPool(threads)
		try {
			val go = CountDownLatch(1)
			val futures = (0 until threads).map { index -> executor.submit<T> { go.await().let { block(index) } } }
			go.countDown()
			return futures.map { it.get(30, TimeUnit.SECONDS) }
		} finally {
			executor.shutdownNow()
		}
	}

	private fun stubTokenBody(
		body: String,
		grantType: String = "authorization_code",
	) {
		wireMock.stubFor(
			post(urlPathEqualTo("/google/token"))
				.withRequestBody(containing("grant_type=$grantType"))
				.willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(body)),
		)
	}

	private fun connectionCount(): Int = jdbc.queryForObject("select count(*) from oauth_connection", Int::class.java)!!

	private fun pendingCount(): Int = jdbc.queryForObject("select count(*) from oauth_authorization", Int::class.java)!!
}
