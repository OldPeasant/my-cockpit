package ch.sonensei.cockpit.contacts.google

import ch.sonensei.cockpit.core.oauth.domain.OAuthConnectionNotFoundException
import ch.sonensei.cockpit.core.oauth.domain.OAuthException
import ch.sonensei.cockpit.core.oauth.domain.OAuthGrantRejectedException
import ch.sonensei.cockpit.core.oauth.domain.OAuthProviderUnavailableException
import ch.sonensei.cockpit.core.oauth.domain.OAuthReconnectRequiredException
import ch.sonensei.cockpit.core.oauth.domain.OAuthTokenProvider
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.http.HttpClient
import java.time.Duration
import java.util.UUID

/** A sync run failed; the message is safe to store and show (no token, no response body, no contact data). */
class GoogleContactsException(
	message: String,
	cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * Calls the Google People API v1 (`cockpit.contacts.google.api-url`) with the access token of an OAuth connection.
 * Every call answers with its status and JSON body; [GoogleContactsSync] decides what a 4xx means. Token failures,
 * network errors, 401/403 and 5xx/429 answers become [GoogleContactsException]s.
 */
class GoogleContactsClient(
	apiUrl: String,
	private val tokens: OAuthTokenProvider,
	private val json: JsonMapper,
) {
	private val rest: RestClient =
		RestClient
			.builder()
			.baseUrl(apiUrl.trimEnd('/'))
			.requestFactory(
				JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build())
					.apply { setReadTimeout(READ_TIMEOUT) },
			).build()

	/** A status and the parsed body (a missing node when there was none or it was not JSON). */
	class Response(
		val status: Int,
		val body: JsonNode,
	) {
		val ok: Boolean get() = status in 200..299

		/** The `google.rpc.ErrorInfo` reasons and the `status` of an error body, e.g. `EXPIRED_SYNC_TOKEN`. */
		fun errorReasons(): Set<String> {
			val error = body.path("error")
			val reasons: List<String> = error.path("details").values().map { detail: JsonNode -> detail.path("reason").asString("") }
			return (reasons + error.path("status").asString("")).filter { it.isNotEmpty() }.toSet()
		}
	}

	/**
	 * Sends [method] to [path] (literal: built from checked [resourcePath]s) with the query parameters [query] (null
	 * values are left out, the others are encoded) and the optional JSON [body].
	 */
	fun call(
		connectionId: UUID,
		method: HttpMethod,
		path: String,
		query: Map<String, String?> = emptyMap(),
		body: JsonNode? = null,
	): Response {
		val token = accessToken(connectionId)
		val params = query.filterValues { it != null }
		val values = params.entries.associate { "q_${it.key}" to it.value!! }
		val response =
			try {
				rest
					.method(method)
					.uri { builder ->
						builder.path(path)
						params.keys.forEach { builder.queryParam(it, "{q_$it}") }
						builder.build(values)
					}.header(HttpHeaders.AUTHORIZATION, "Bearer ${token.value}")
					.accept(MediaType.APPLICATION_JSON)
					.apply { body?.let { contentType(MediaType.APPLICATION_JSON).body(json.writeValueAsString(it)) } }
					.exchange { _, res -> Response(res.statusCode.value(), parse(res.body.readAllBytes())) }
			} catch (e: RestClientException) {
				throw GoogleContactsException("Google Contacts could not be reached (${e.javaClass.simpleName})", e)
			}
		when {
			response.status == 401 -> throw GoogleContactsException("Google Contacts rejected the access token (HTTP 401)")
			response.status == 429 || (response.status == 403 && isRateLimit(response)) ->
				throw GoogleContactsException("Google Contacts rate limit reached (HTTP ${response.status}), retrying later")
			response.status == 403 -> throw GoogleContactsException("Google Contacts refused access (HTTP 403)")
			response.status >= 500 -> throw GoogleContactsException("Google Contacts failed (HTTP ${response.status})")
		}
		return response
	}

	private fun accessToken(connectionId: UUID) =
		try {
			tokens.accessToken(connectionId)
		} catch (e: OAuthException) {
			throw GoogleContactsException(
				when (e) {
					is OAuthReconnectRequiredException -> "The Google account has to be connected again"
					is OAuthConnectionNotFoundException -> "The Google connection was removed"
					is OAuthProviderUnavailableException -> "Google sign-in could not be reached, retrying later"
					is OAuthGrantRejectedException -> "Google rejected the token refresh (${e.error})"
				},
				e,
			)
		}

	private fun parse(bytes: ByteArray): JsonNode =
		if (bytes.isEmpty()) {
			json.missingNode()
		} else {
			try {
				json.readTree(bytes)
			} catch (e: JacksonException) {
				json.missingNode()
			}
		}

	/** Whether a 403 is a quota error (`RATE_LIMIT_EXCEEDED`, legacy `rateLimitExceeded`) rather than a denial. */
	private fun isRateLimit(response: Response): Boolean =
		response.errorReasons().any { it.contains("RATE_LIMIT", ignoreCase = true) } ||
			response.body.path("error").path("errors").any {
				it.path("reason").asString("").contains("RateLimitExceeded", ignoreCase = true)
			}

	companion object {
		const val DEFAULT_API_URL = "https://people.googleapis.com"
		private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(10)
		private val READ_TIMEOUT: Duration = Duration.ofSeconds(30)
		private val RESOURCE_NAME = Regex("^people/[A-Za-z0-9_-]+$")

		/**
		 * The API path of a person, e.g. `/v1/people/c123`. Resource names come from Google; one that does not look
		 * like `people/<id>` is refused rather than put into a URL.
		 */
		fun resourcePath(resourceName: String): String {
			if (!RESOURCE_NAME.matches(resourceName)) throw GoogleContactsException("Google returned an unexpected contact resource name")
			return "/v1/$resourceName"
		}
	}
}
