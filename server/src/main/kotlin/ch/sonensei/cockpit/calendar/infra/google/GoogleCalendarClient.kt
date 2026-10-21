package ch.sonensei.cockpit.calendar.infra.google

import ch.sonensei.cockpit.calendar.sync.CalendarProviderException
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

/**
 * Calls the Google Calendar API v3 (`cockpit.calendar.google.api-url`) with the access token of an OAuth connection.
 * Every call answers with its status and JSON body; [GoogleCalendarAdapter] decides what a status means. Token
 * failures, network errors and 5xx/429 answers become [CalendarProviderException]s whose messages hold no token, no
 * response body and no event content.
 */
class GoogleCalendarClient(
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
	}

	/**
	 * Sends [method] to [path] (a URI template; [variables] fill its `{placeholders}` and are encoded) with the query
	 * parameters [query] (null values are left out) and the optional JSON [body] and `If-Match` header.
	 */
	fun call(
		connectionId: UUID,
		method: HttpMethod,
		path: String,
		variables: Map<String, String> = emptyMap(),
		query: Map<String, String?> = emptyMap(),
		body: JsonNode? = null,
		ifMatch: String? = null,
	): Response {
		val token = accessToken(connectionId)
		val params = query.filterValues { it != null }
		val values = variables + params.entries.associate { "q_${it.key}" to it.value!! }
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
					.apply {
						ifMatch?.let { header(HttpHeaders.IF_MATCH, it) }
						body?.let { contentType(MediaType.APPLICATION_JSON).body(json.writeValueAsString(it)) }
					}.exchange { _, res -> Response(res.statusCode.value(), parse(res.body.readAllBytes())) }
			} catch (e: RestClientException) {
				throw CalendarProviderException("Google Calendar could not be reached (${e.javaClass.simpleName})", e)
			}
		when {
			response.status == 401 -> throw CalendarProviderException("Google Calendar rejected the access token (HTTP 401)")
			response.status == 429 || (response.status == 403 && isRateLimit(response.body)) ->
				throw CalendarProviderException("Google Calendar rate limit reached (HTTP ${response.status}), retrying later")
			response.status == 403 -> throw CalendarProviderException("Google Calendar refused access (HTTP 403)")
			response.status >= 500 -> throw CalendarProviderException("Google Calendar failed (HTTP ${response.status})")
		}
		return response
	}

	private fun accessToken(connectionId: UUID) =
		try {
			tokens.accessToken(connectionId)
		} catch (e: OAuthException) {
			throw CalendarProviderException(
				when (e) {
					is OAuthReconnectRequiredException -> "The Google account has to be connected again"
					is OAuthConnectionNotFoundException -> "The Google connection of this source was removed"
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

	/** Whether a 403 is Google's `rateLimitExceeded`/`userRateLimitExceeded` (retry later) rather than a denial. */
	private fun isRateLimit(body: JsonNode): Boolean =
		body.path("error").path("errors").any { it.path("reason").asString("").contains("RateLimitExceeded", ignoreCase = true) }

	companion object {
		const val DEFAULT_API_URL = "https://www.googleapis.com/calendar/v3"
		private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(10)
		private val READ_TIMEOUT: Duration = Duration.ofSeconds(30)
	}
}
