package ch.sonensei.cockpit.contacts.google

import ch.sonensei.cockpit.core.oauth.domain.OAuthAccessToken
import ch.sonensei.cockpit.core.oauth.domain.OAuthConnectionInfo
import ch.sonensei.cockpit.core.oauth.domain.OAuthConnectionNotFoundException
import ch.sonensei.cockpit.core.oauth.domain.OAuthException
import ch.sonensei.cockpit.core.oauth.domain.OAuthGrantRejectedException
import ch.sonensei.cockpit.core.oauth.domain.OAuthProviderUnavailableException
import ch.sonensei.cockpit.core.oauth.domain.OAuthReconnectRequiredException
import ch.sonensei.cockpit.core.oauth.domain.OAuthResource
import ch.sonensei.cockpit.core.oauth.domain.OAuthTokenProvider
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.any
import com.github.tomakehurst.wiremock.client.WireMock.anyUrl
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.equalToJson
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import com.github.tomakehurst.wiremock.http.Fault
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import tools.jackson.databind.json.JsonMapper
import java.net.ServerSocket
import java.time.Instant
import java.util.UUID

/**
 * [GoogleContactsClient] without Spring against WireMock: which answers become a [GoogleContactsException] (with a
 * message that is safe to store: no token, no response body), how token failures are reported, how query parameters
 * are sent, and which resource names may be put into a URL.
 */
class GoogleContactsClientTests {
	private val json: JsonMapper = JsonMapper.builder().build()
	private val tokens = Tokens()
	private val client = GoogleContactsClient(wireMock.baseUrl() + "/", tokens, json)

	/** Hands out [TOKEN] unless [failure] is set. */
	class Tokens : OAuthTokenProvider {
		var failure: OAuthException? = null

		override fun accessToken(
			connectionId: UUID,
			resource: OAuthResource,
		): OAuthAccessToken {
			failure?.let { throw it }
			return OAuthAccessToken(TOKEN, Instant.parse("2030-01-01T00:00:00Z"))
		}

		override fun connection(connectionId: UUID): OAuthConnectionInfo? = null
	}

	@BeforeEach
	fun reset() {
		wireMock.resetAll()
	}

	private fun answer(
		status: Int,
		body: String = "",
	) {
		wireMock.stubFor(
			any(anyUrl()).willReturn(aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(body)),
		)
	}

	private fun call(): GoogleContactsClient.Response = client.call(CONNECTION, HttpMethod.GET, PATH)

	private fun failure(): GoogleContactsException = shouldThrow<GoogleContactsException> { call() }

	// --- answers

	@Test
	fun `a 2xx answer is returned with its parsed body and the bearer token`() {
		answer(200, """{"connections": [{"resourceName": "people/c1"}], "nextSyncToken": "SYNC-1"}""")

		val response = call()

		response.ok shouldBe true
		response.status shouldBe 200
		response.body.path("nextSyncToken").asString() shouldBe "SYNC-1"
		wireMock.verify(getRequestedFor(urlPathEqualTo(PATH)).withHeader("Authorization", equalTo("Bearer $TOKEN")))
	}

	@Test
	fun `4xx answers other than 401, 403 and 429 are returned to the caller with their error reasons`() {
		answer(
			400,
			"""{"error": {"code": 400, "status": "FAILED_PRECONDITION",
			   "details": [{"@type": "type.googleapis.com/google.rpc.ErrorInfo", "reason": "EXPIRED_SYNC_TOKEN"}, {"@type": "x"}]}}""",
		)
		val response = call()
		response.ok shouldBe false
		response.status shouldBe 400
		response.errorReasons() shouldContainExactlyInAnyOrder setOf("FAILED_PRECONDITION", "EXPIRED_SYNC_TOKEN")

		for (status in listOf(404, 409, 410, 412)) {
			answer(status, """{"error": {"code": $status}}""")
			call().status shouldBe status
		}
	}

	@Test
	fun `an empty or non-JSON body is a missing node, not an error`() {
		answer(204)
		call().body.isMissingNode shouldBe true
		call().errorReasons() shouldBe emptySet()

		wireMock.stubFor(
			any(anyUrl()).willReturn(aResponse().withStatus(404).withHeader("Content-Type", "text/html").withBody("<html>Not Found</html>")),
		)
		val response = call()
		response.status shouldBe 404
		response.body.isMissingNode shouldBe true
		response.errorReasons() shouldBe emptySet()
	}

	@Test
	fun `401 means the access token was rejected`() {
		answer(401, """{"error": {"code": 401, "status": "UNAUTHENTICATED"}}""")

		failure().message shouldBe "Google Contacts rejected the access token (HTTP 401)"
	}

	@Test
	fun `403 without a quota reason means access was refused`() {
		answer(403, """{"error": {"code": 403, "status": "PERMISSION_DENIED", "details": [{"reason": "ACCESS_TOKEN_SCOPE_INSUFFICIENT"}]}}""")
		failure().message shouldBe "Google Contacts refused access (HTTP 403)"

		answer(403)
		failure().message shouldBe "Google Contacts refused access (HTTP 403)"
	}

	@Test
	fun `403 with a quota reason is a rate limit like 429`() {
		answer(403, """{"error": {"code": 403, "status": "PERMISSION_DENIED", "details": [{"reason": "RATE_LIMIT_EXCEEDED"}]}}""")
		failure().message shouldBe "Google Contacts rate limit reached (HTTP 403), retrying later"

		// The legacy error format of older Google APIs.
		answer(403, """{"error": {"code": 403, "errors": [{"domain": "usageLimits", "reason": "userRateLimitExceeded"}]}}""")
		failure().message shouldBe "Google Contacts rate limit reached (HTTP 403), retrying later"

		answer(429, """{"error": {"code": 429, "status": "RESOURCE_EXHAUSTED"}}""")
		failure().message shouldBe "Google Contacts rate limit reached (HTTP 429), retrying later"
	}

	@Test
	fun `5xx answers fail the call without the response body in the message`() {
		for (status in listOf(500, 502, 503)) {
			answer(status, """{"error": {"message": "Backend error for Robin Fiktiv <robin@example.test>"}}""")

			val error = failure()

			error.message shouldBe "Google Contacts failed (HTTP $status)"
			error.message shouldNotContain "robin"
		}
	}

	@Test
	fun `a network failure fails the call with a safe message`() {
		wireMock.stubFor(any(anyUrl()).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)))

		val error = failure()

		error.message shouldStartWith "Google Contacts could not be reached ("
		error.message shouldNotContain TOKEN
	}

	@Test
	fun `a server that does not listen fails the call with a safe message`() {
		val port = ServerSocket(0).use { it.localPort }
		val offline = GoogleContactsClient("http://127.0.0.1:$port", tokens, json)

		val error = shouldThrow<GoogleContactsException> { offline.call(CONNECTION, HttpMethod.GET, PATH) }

		error.message shouldStartWith "Google Contacts could not be reached ("
	}

	// --- tokens

	@Test
	fun `token failures become safe messages and Google is not called`() {
		val cases =
			mapOf(
				OAuthReconnectRequiredException("invalid_grant") to "The Google account has to be connected again",
				OAuthConnectionNotFoundException() to "The Google connection was removed",
				OAuthProviderUnavailableException("timeout") to "Google sign-in could not be reached, retrying later",
				OAuthGrantRejectedException("invalid_client") to "Google rejected the token refresh (invalid_client)",
			)
		for ((cause, message) in cases) {
			tokens.failure = cause

			val error = failure()

			error.message shouldBe message
			error.cause shouldBe cause
		}
		wireMock.allServeEvents.size shouldBe 0
	}

	// --- requests

	@Test
	fun `null query parameters are left out, the others are encoded`() {
		answer(200, "{}")
		val syncToken = "EgYI+/a=b&c%{x}"

		client.call(CONNECTION, HttpMethod.GET, PATH, query = mapOf("syncToken" to syncToken, "pageToken" to null, "pageSize" to "1000"))

		val request = wireMock.allServeEvents.single().request
		request.queryParameter("syncToken").firstValue() shouldBe syncToken
		request.queryParameter("pageSize").firstValue() shouldBe "1000"
		request.queryParameter("pageToken").isPresent shouldBe false
	}

	@Test
	fun `a body is sent as JSON`() {
		answer(200, """{"resourceName": "people/c5"}""")
		val body = json.createObjectNode().put("etag", "%e").also { it.putArray("names").addObject().put("givenName", "Kim") }

		client.call(CONNECTION, HttpMethod.POST, "/v1/people:createContact", body = body)

		wireMock.verify(
			postRequestedFor(urlPathEqualTo("/v1/people:createContact"))
				.withHeader("Content-Type", equalTo("application/json"))
				.withRequestBody(equalToJson("""{"etag": "%e", "names": [{"givenName": "Kim"}]}""")),
		)
	}

	@Test
	fun `a call without a body sends none`() {
		wireMock.stubFor(post(anyUrl()).willReturn(aResponse().withStatus(200)))

		client.call(CONNECTION, HttpMethod.POST, PATH)

		wireMock.allServeEvents
			.single()
			.request.bodyAsString shouldBe ""
	}

	// --- resource names

	@Test
	fun `a resource name of the form people slash id becomes its API path`() {
		GoogleContactsClient.resourcePath("people/c1234567890") shouldBe "/v1/people/c1234567890"
		GoogleContactsClient.resourcePath("people/c-1_A") shouldBe "/v1/people/c-1_A"
	}

	@Test
	fun `any other resource name is refused rather than put into a URL`() {
		for (name in listOf(
			"",
			"people/",
			"people/../me",
			"people/c1/x",
			"people/c1?x=1",
			"people/c1#x",
			"contactGroups/1",
			" people/c1",
			"people/c 1",
			"people/c1\n",
		)) {
			shouldThrow<GoogleContactsException> { GoogleContactsClient.resourcePath(name) }.message shouldBe
				"Google returned an unexpected contact resource name"
		}
	}

	@Test
	fun `a request is sent to the path below the configured base URL`() {
		wireMock.stubFor(get(urlPathEqualTo("/v1/people/c42")).willReturn(aResponse().withStatus(200).withBody("{}")))

		client.call(CONNECTION, HttpMethod.GET, GoogleContactsClient.resourcePath("people/c42")).ok shouldBe true
	}

	companion object {
		const val TOKEN = "fictitious-access-token-xyz"
		const val PATH = "/v1/people/me/connections"
		val CONNECTION: UUID = UUID.fromString("0192a5f0-0000-7000-8000-0000000000d1")

		val wireMock: WireMockServer = WireMockServer(wireMockConfig().dynamicPort()).apply { start() }

		@JvmStatic
		@AfterAll
		fun stop() = wireMock.stop()
	}
}
